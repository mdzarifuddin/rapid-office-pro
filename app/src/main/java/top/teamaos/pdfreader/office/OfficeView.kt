package top.teamaos.pdfreader.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import top.teamaos.pdfreader.view.ColorMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The reading surface for Office documents.
 *
 * Deliberately the same shape as the PDF view: pages stacked down the screen with a gap between
 * them, a fling that costs nothing, and a zoom that is a matrix rather than a re-layout. What is
 * different is what a page is made of — measured text rather than a rendered bitmap — and that
 * turns out to be an advantage: text drawn from a [android.text.StaticLayout] is sharp at every
 * zoom without a single re-render, so pinching a Word file never goes blurry and never allocates.
 *
 * Nothing is measured here. [OfficeLayout] did that once, off the main thread; this only ever
 * decides which pages are on screen and draws them.
 */
class OfficeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var document: OfficeLayout.LaidOutDocument? = null

    /** Vertical offset of every page's top edge, in layout units, including the gaps. */
    private var pageTops = FloatArray(0)
    private var contentHeight = 0f
    private var contentWidth = 0f

    private var scrollXf = 0f
    private var scrollYf = 0f

    /** Screen pixels per layout unit at zoom 1: the whole widest page fitted across the screen. */
    private var baseScale = 1f
    private var zoom = 1f

    private val scroller = OverScroller(context)
    private var zoomAnimator: android.animation.ValueAnimator? = null
    private var scaling = false
    private var touchActive = false

    var onPageChanged: ((page: Int) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null
    var onScaleChanged: (() -> Unit)? = null

    /** Held down over a paragraph: hands back its text, for copy, search and translate. */
    var onTextLongPress: ((text: String, page: Int) -> Unit)? = null

    private var lastReportedPage = -1

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pageEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x22000000
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFD54F }
    private val activeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FF9800.toInt() }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x552478C8 }

    /** Decoded pictures, kept small: a deck of photographs would otherwise fill the heap. */
    private val images = object : LruCache<ByteArray, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: ByteArray, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: ByteArray, old: Bitmap, new: Bitmap?) {
            if (evicted && old !== new) old.recycle()
        }
    }
    private val undecodable = HashSet<ByteArray>()

    var colorMode: ColorMode = ColorMode.NORMAL
        set(value) {
            if (field == value) return
            field = value
            applyColorMode()
            invalidate()
        }

    private var contentFilter: ColorMatrixColorFilter? = null
    private var backdrop = BACKDROP_COLOUR

    /** Search matches, as (page, rectangle in layout units). */
    private var matches: List<Pair<Int, RectF>> = emptyList()
    private var activeMatch = -1

    /** The paragraph the reader is holding, drawn as a selection. */
    private var selection: Pair<Int, RectF>? = null

    init {
        isFocusable = true
        setBackgroundColor(BACKDROP_COLOUR)
    }

    fun show(laidOut: OfficeLayout.LaidOutDocument) {
        document = laidOut
        val gap = PAGE_GAP_UNITS
        pageTops = FloatArray(laidOut.pages.size)
        var y = gap
        laidOut.pages.forEachIndexed { index, page ->
            pageTops[index] = y
            y += page.height + gap
        }
        contentHeight = y
        contentWidth = laidOut.widestPage
        scrollXf = 0f
        scrollYf = 0f
        zoom = 1f
        lastReportedPage = -1
        recomputeBaseScale()
        invalidate()
        reportPageIfChanged()
    }

    val pageCount: Int get() = document?.pages?.size ?: 0

    val currentPage: Int
        get() {
            val pages = document?.pages ?: return 0
            if (pages.isEmpty()) return 0
            val middle = (scrollYf + height / 2f) / scale
            var best = 0
            for (index in pages.indices) {
                if (pageTops[index] <= middle) best = index else break
            }
            return best
        }

    fun sectionOfPage(page: Int): Int = document?.pages?.getOrNull(page)?.sectionIndex ?: 0

    fun pageLabel(page: Int): String = document?.pages?.getOrNull(page)?.label.orEmpty()

    /** The first page belonging to a section, for the contents list. */
    fun firstPageOfSection(section: Int): Int =
        document?.pages?.indexOfFirst { it.sectionIndex == section }?.coerceAtLeast(0) ?: 0

    private val scale: Float get() = baseScale * zoom

    val zoomLevel: Float get() = zoom

    private fun recomputeBaseScale() {
        if (width <= 0 || contentWidth <= 0f) return
        baseScale = (width - 2 * SIDE_MARGIN_PX * resources.displayMetrics.density) / contentWidth
        if (baseScale <= 0f) baseScale = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val anchorPage = currentPage
        recomputeBaseScale()
        goToPage(anchorPage)
    }

    // ---------------------------------------------------------------- movement

    fun goToPage(page: Int, animate: Boolean = false) {
        val pages = document?.pages ?: return
        if (pages.isEmpty()) return
        val index = page.coerceIn(0, pages.size - 1)
        val target = pageTops[index] * scale - PAGE_GAP_UNITS * scale
        if (animate) {
            scroller.startScroll(
                scrollXf.roundToInt(),
                scrollYf.roundToInt(),
                0,
                (target - scrollYf).roundToInt(),
                SCROLL_ANIMATION_MS,
            )
            postInvalidateOnAnimation()
        } else {
            scroller.forceFinished(true)
            scrollYf = target
            clampScroll()
            invalidate()
            reportPageIfChanged()
        }
    }

    /** Bring a rectangle on a page into view, for a search match. */
    fun scrollToMatch(page: Int, bounds: RectF) {
        val pages = document?.pages ?: return
        if (page !in pages.indices) return
        scroller.forceFinished(true)
        scrollYf = (pageTops[page] + bounds.top) * scale - height * MATCH_SCREEN_FRACTION
        scrollXf = (bounds.left + pageLeft(page)) * scale - width * 0.25f
        clampScroll()
        invalidate()
        reportPageIfChanged()
    }

    fun setMatches(found: List<Pair<Int, RectF>>, active: Int) {
        matches = found
        activeMatch = active
        invalidate()
    }

    fun clearMatches() {
        if (matches.isEmpty() && activeMatch < 0) return
        matches = emptyList()
        activeMatch = -1
        invalidate()
    }

    fun clearSelection() {
        if (selection == null) return
        selection = null
        invalidate()
    }

    private fun clampScroll() {
        val maxY = max(0f, contentHeight * scale - height)
        val maxX = max(0f, contentWidth * scale - width + 2 * SIDE_MARGIN_PX * resources.displayMetrics.density)
        scrollYf = scrollYf.coerceIn(0f, maxY)
        scrollXf = scrollXf.coerceIn(0f, maxX)
    }

    private fun reportPageIfChanged() {
        val page = currentPage
        if (page != lastReportedPage) {
            lastReportedPage = page
            onPageChanged?.invoke(page)
        }
    }

    fun setZoomLevel(target: Float, animate: Boolean = false) {
        if (animate) animateZoomTo(target, width / 2f, height / 2f) else applyZoom(target, width / 2f, height / 2f)
    }

    fun zoomIn() = animateZoomTo(zoom * ZOOM_STEP, width / 2f, height / 2f)

    fun zoomOut() = animateZoomTo(zoom / ZOOM_STEP, width / 2f, height / 2f)

    fun resetZoom() = animateZoomTo(1f, width / 2f, height / 2f)

    /** Fit the page's width to the screen, ignoring the reading margin. */
    fun fitWidth() {
        if (contentWidth <= 0f || width <= 0) return
        animateZoomTo(width / (contentWidth * baseScale), width / 2f, 0f)
    }

    private fun applyZoom(requested: Float, focusX: Float, focusY: Float) {
        val clamped = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(clamped - zoom) < 0.0005f) return
        val ratio = clamped / zoom
        zoom = clamped
        scrollXf = (scrollXf + focusX) * ratio - focusX
        scrollYf = (scrollYf + focusY) * ratio - focusY
        clampScroll()
        onScaleChanged?.invoke()
        invalidate()
    }

    private fun animateZoomTo(target: Float, focusX: Float, focusY: Float) {
        val to = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(to - zoom) < 0.01f) return
        zoomAnimator?.cancel()
        zoomAnimator = android.animation.ValueAnimator.ofFloat(zoom, to).apply {
            duration = ZOOM_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyZoom(it.animatedValue as Float, focusX, focusY) }
            start()
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            if (startingDoubleTapZoom) startingDoubleTapZoom = false else zoomAnimator?.cancel()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (selection != null) {
                clearSelection()
                return true
            }
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            startingDoubleTapZoom = true
            if (zoom > 1.05f) {
                animateZoomTo(zoomBeforeDoubleTap ?: 1f, e.x, e.y)
                zoomBeforeDoubleTap = null
            } else {
                zoomBeforeDoubleTap = zoom
                animateZoomTo(DOUBLE_TAP_ZOOM, e.x, e.y)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val hit = textAt(e.x, e.y) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            selection = hit.first to hit.second
            invalidate()
            onTextLongPress?.invoke(hit.third, hit.first)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return false
            scrollXf += dx
            scrollYf += dy
            clampScroll()
            reportPageIfChanged()
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return false
            val maxY = max(0f, contentHeight * scale - height)
            val maxX = max(0f, contentWidth * scale - width)
            scroller.fling(
                scrollXf.roundToInt(),
                scrollYf.roundToInt(),
                -vx.roundToInt(),
                -vy.roundToInt(),
                0,
                maxX.roundToInt(),
                0,
                maxY.roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    private var zoomBeforeDoubleTap: Float? = null
    private var startingDoubleTapZoom = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                return document != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
            }
        },
    ).apply { isQuickScaleEnabled = false }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaling) gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchActive = false
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXf = scroller.currX.toFloat()
            scrollYf = scroller.currY.toFloat()
            clampScroll()
            reportPageIfChanged()
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun pageLeft(page: Int): Float {
        val pages = document?.pages ?: return 0f
        val width = pages.getOrNull(page)?.width ?: return 0f
        return (contentWidth - width) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val pages = document?.pages ?: return
        canvas.drawColor(backdrop)
        if (pages.isEmpty()) return

        val scale = this.scale
        val density = resources.displayMetrics.density
        val originX = SIDE_MARGIN_PX * density - scrollXf

        // Everything on the page goes through one filtered layer, so night and sepia recolour the
        // text, the tables and the pictures together rather than only the parts drawn as bitmaps.
        val filter = contentFilter
        val layer = if (filter != null) {
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { colorFilter = filter })
        } else {
            -1
        }

        for (index in pages.indices) {
            val page = pages[index]
            val top = pageTops[index] * scale - scrollYf
            val pageHeight = page.height * scale
            if (top + pageHeight < 0f) continue
            if (top > height) break

            val left = originX + pageLeft(index) * scale
            val pageWidth = page.width * scale
            pagePaint.color = page.background
            canvas.drawRect(left, top, left + pageWidth, top + pageHeight, pagePaint)

            val saved = canvas.save()
            canvas.clipRect(left, top, left + pageWidth, top + pageHeight)
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            drawItems(canvas, page, index)
            canvas.restoreToCount(saved)

            canvas.drawRect(left, top, left + pageWidth, top + pageHeight, pageEdgePaint)
        }

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawItems(canvas: Canvas, page: OfficeLayout.LaidOutPage, pageIndex: Int) {
        page.items.forEach { item ->
            when (item) {
                is OfficeLayout.PageItem.Box -> {
                    if (item.fill != 0) {
                        fillPaint.color = item.fill
                        canvas.drawRect(item.bounds, fillPaint)
                    }
                    if (item.stroke != 0 && item.strokeWidth > 0f) {
                        strokePaint.color = item.stroke
                        strokePaint.strokeWidth = item.strokeWidth
                        canvas.drawRect(item.bounds, strokePaint)
                    }
                }
                is OfficeLayout.PageItem.Image -> drawImage(canvas, item)
                is OfficeLayout.PageItem.Text -> drawText(canvas, item)
            }
        }

        selection?.let { (selectedPage, bounds) ->
            if (selectedPage == pageIndex) canvas.drawRect(bounds, selectionPaint)
        }
        matches.forEachIndexed { index, (matchPage, bounds) ->
            if (matchPage != pageIndex) return@forEachIndexed
            canvas.drawRect(bounds, if (index == activeMatch) activeHighlightPaint else highlightPaint)
        }
    }

    /**
     * Draw one paragraph, or the slice of it that belongs to this page.
     *
     * A [android.text.StaticLayout] can only draw from its own origin, so a paragraph split across
     * a page boundary is drawn shifted up by the lines already shown and clipped to the slice —
     * which costs a clip and a translate instead of measuring the paragraph a second time.
     */
    private fun drawText(canvas: Canvas, item: OfficeLayout.PageItem.Text) {
        val saved = canvas.save()
        val top = item.layout.getLineTop(item.firstLine).toFloat()
        canvas.clipRect(
            item.x,
            item.y,
            item.x + item.layout.width,
            item.y + item.height,
        )
        canvas.translate(item.x, item.y - top)
        item.layout.draw(canvas)
        canvas.restoreToCount(saved)
    }

    private fun drawImage(canvas: Canvas, item: OfficeLayout.PageItem.Image) {
        val bitmap = bitmapFor(item.data) ?: return
        canvas.drawBitmap(bitmap, null, item.bounds, imagePaint)
    }

    /**
     * Decode a picture at roughly the size it is drawn.
     *
     * Full-resolution decoding is the single easiest way to run out of memory here: a phone photo
     * pasted into a report is twelve megapixels and forty-eight megabytes decoded, to be drawn
     * three centimetres across.
     */
    private fun bitmapFor(data: ByteArray): Bitmap? {
        images.get(data)?.let { return it }
        if (data in undecodable) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            undecodable += data
            return null
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_EDGE || bounds.outHeight / sample > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options) }.getOrNull()
        if (bitmap == null) {
            undecodable += data
            return null
        }
        images.put(data, bitmap)
        return bitmap
    }

    /** The paragraph under a point: its page, its rectangle and its text. */
    private fun textAt(x: Float, y: Float): Triple<Int, RectF, String>? {
        val pages = document?.pages ?: return null
        val scale = this.scale
        val density = resources.displayMetrics.density
        val originX = SIDE_MARGIN_PX * density - scrollXf

        pages.forEachIndexed { index, page ->
            val top = pageTops[index] * scale - scrollYf
            if (y < top || y > top + page.height * scale) return@forEachIndexed
            val left = originX + pageLeft(index) * scale
            val localX = (x - left) / scale
            val localY = (y - top) / scale
            page.items.forEach { item ->
                if (item !is OfficeLayout.PageItem.Text) return@forEach
                val bounds = RectF(
                    item.x,
                    item.y,
                    item.x + item.layout.width,
                    item.y + item.height,
                )
                if (bounds.contains(localX, localY)) {
                    val start = item.layout.getLineStart(item.firstLine)
                    val end = item.layout.getLineEnd(item.lastLine)
                    val text = item.layout.text.subSequence(start, end).toString().trim()
                    if (text.isNotEmpty()) return Triple(index, bounds, text)
                }
            }
        }
        return null
    }

    /** Every paragraph on the document, for search. Cheap: the layouts already exist. */
    fun eachParagraph(action: (page: Int, bounds: RectF, text: CharSequence) -> Unit) {
        val pages = document?.pages ?: return
        pages.forEachIndexed { index, page ->
            page.items.forEach { item ->
                if (item !is OfficeLayout.PageItem.Text) return@forEach
                val start = item.layout.getLineStart(item.firstLine)
                val end = item.layout.getLineEnd(item.lastLine)
                if (end <= start) return@forEach
                action(
                    index,
                    RectF(item.x, item.y, item.x + item.layout.width, item.y + item.height),
                    item.layout.text.subSequence(start, end),
                )
            }
        }
    }

    private fun applyColorMode() {
        backdrop = when (colorMode) {
            ColorMode.NIGHT -> Color.BLACK
            ColorMode.SEPIA -> SEPIA_BACKDROP
            else -> BACKDROP_COLOUR
        }
        setBackgroundColor(backdrop)
        contentFilter = when (colorMode) {
            ColorMode.NORMAL -> null
            ColorMode.NIGHT -> ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -0.85f, 0f, 0f, 0f, 235f,
                        0f, -0.85f, 0f, 0f, 235f,
                        0f, 0f, -0.85f, 0f, 235f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            ColorMode.SEPIA -> ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0.96f, 0.05f, 0f, 0f, 8f,
                        0.03f, 0.92f, 0f, 0f, 2f,
                        0.02f, 0.03f, 0.80f, 0f, -6f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            ColorMode.GRAYSCALE -> ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(0f) },
            )
        }
    }

    /** Where the reader was, so reopening lands in the same place. */
    fun readingPosition(): FloatArray = floatArrayOf(currentPage.toFloat(), 0f, 0f, zoom)

    fun restoreReadingPosition(position: FloatArray) {
        if (position.size < 4) return
        zoom = position[3].coerceIn(MIN_ZOOM, MAX_ZOOM)
        goToPage(position[0].toInt())
        onScaleChanged?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        zoomAnimator?.cancel()
        images.evictAll()
    }

    private companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 8f
        const val ZOOM_STEP = 1.25f
        const val DOUBLE_TAP_ZOOM = 1.5f
        const val ZOOM_ANIMATION_MS = 220L
        const val SCROLL_ANIMATION_MS = 260
        const val PAGE_GAP_UNITS = 8f * OfficeLayout.UNITS_PER_POINT
        const val SIDE_MARGIN_PX = 6f
        const val BACKDROP_COLOUR = 0xFF303338.toInt()
        const val SEPIA_BACKDROP = 0xFF4A4438.toInt()
        const val MATCH_SCREEN_FRACTION = 0.3f
        const val MAX_IMAGE_EDGE = 1600
        const val IMAGE_CACHE_BYTES = 24 * 1024 * 1024
    }
}
