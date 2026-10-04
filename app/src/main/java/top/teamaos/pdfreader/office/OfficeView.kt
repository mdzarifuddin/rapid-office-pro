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

    /**
     * Decoded pictures, kept to an eighth of the heap: a deck of photographs would otherwise fill it.
     *
     * Evicted bitmaps are not recycled. One can be evicted while the frame that is drawing it is
     * still being recorded, and drawing a recycled bitmap throws; the collector frees them safely.
     */
    private val images = object : LruCache<ByteArray, Bitmap>(imageCacheBytes()) {
        override fun sizeOf(key: ByteArray, value: Bitmap): Int = value.byteCount
    }
    private val undecodable = HashSet<ByteArray>()

    /** Pictures queued or being decoded, so one scrolled past repeatedly is decoded once. */
    private val decoding = HashSet<ByteArray>()

    /** Bumped for every new document, so a decode finishing late cannot fill the new one's cache. */
    private var generation = 0

    private val placeholderPaint = Paint().apply { color = 0xFFE6E9EE.toInt() }

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
        slideShowPage = -1
        document = laidOut
        generation++
        decoding.clear()
        images.evictAll()
        undecodable.clear()
        val gap = PAGE_GAP_UNITS
        pageTops = FloatArray(laidOut.pages.size)
        var y = gap
        laidOut.pages.forEachIndexed { index, page ->
            pageTops[index] = y
            // A NaN height would make every page after it NaN, and scrolling to one would throw.
            y += page.height.takeIf { it.isFinite() && it > 0f } ?: 0f
            y += gap
        }
        contentHeight = y
        contentWidth = laidOut.widestPage.takeIf { it.isFinite() && it > 0f } ?: 0f
        readableZoom = if (contentWidth > WIDE_CONTENT_UNITS) contentWidth / READABLE_WIDTH_UNITS else 1f
        scrollXf = 0f
        scrollYf = 0f
        // A sheet with dozens of columns, fitted to the screen, has text too small to read. It
        // opens at reading size on its top-left corner instead; pinching out shows all of it.
        zoom = readableZoom
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
            return pageAt((scrollYf + height / 2f) / scale)
        }

    /**
     * The last page whose top is at or above [y] (layout units), by binary search: a big sheet is
     * tens of thousands of pages, and this is asked on every frame and every scroll step.
     */
    private fun pageAt(y: Float): Int {
        val tops = pageTops
        if (tops.isEmpty()) return 0
        var low = 0
        var high = tops.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (tops[middle] <= y) low = middle else high = middle - 1
        }
        return low
    }

    fun sectionOfPage(page: Int): Int = document?.pages?.getOrNull(page)?.sectionIndex ?: 0

    fun pageLabel(page: Int): String = document?.pages?.getOrNull(page)?.label.orEmpty()

    /** The first page belonging to a section, for the contents list. */
    fun firstPageOfSection(section: Int): Int =
        document?.pages?.indexOfFirst { it.sectionIndex == section }?.coerceAtLeast(0) ?: 0

    private val scale: Float get() = baseScale * zoom

    val zoomLevel: Float get() = zoom

    /**
     * The zoom at which a wide sheet's text is the size a normal page's would be: 1 for anything
     * page-shaped, more for a sheet many columns wide.
     */
    private var readableZoom = 1f

    /**
     * How far in a reader can zoom. Measured from reading size rather than from fitting the whole
     * width, so a sheet of 100 columns can be zoomed into as closely as a letter.
     */
    val maxZoomLevel: Float get() = MAX_ZOOM * readableZoom

    private fun recomputeBaseScale() {
        if (width <= 0 || contentWidth <= 0f) return
        baseScale = (width - 2 * SIDE_MARGIN_PX * resources.displayMetrics.density) / contentWidth
        if (!baseScale.isFinite() || baseScale <= 0f) baseScale = 1f
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
                scrollXf.safeRound(),
                scrollYf.safeRound(),
                0,
                (target - scrollYf).safeRound(),
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
        val clamped = requested.coerceIn(MIN_ZOOM, maxZoomLevel)
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
        val to = target.coerceIn(MIN_ZOOM, maxZoomLevel)
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
                scrollXf.safeRound(),
                scrollYf.safeRound(),
                -vx.safeRound(),
                -vy.safeRound(),
                0,
                maxX.safeRound(),
                0,
                maxY.safeRound(),
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

    // ---------------------------------------------------------------- slide show

    /** The slide being presented full screen, or -1 when not presenting. */
    var slideShowPage: Int = -1
        private set

    val isSlideShow: Boolean get() = slideShowPage >= 0

    /** Present [from] full screen, one slide at a time, fitted and centred on black. */
    fun startSlideShow(from: Int) {
        val pages = document?.pages ?: return
        if (pages.isEmpty()) return
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        selection = null
        slideShowPage = from.coerceIn(0, pages.size - 1)
        onPageChanged?.invoke(slideShowPage)
        invalidate()
    }

    /** Leave the slide show on the slide it reached. */
    fun stopSlideShow() {
        val page = slideShowPage
        if (page < 0) return
        slideShowPage = -1
        goToPage(page)
        invalidate()
    }

    fun nextSlide() = showSlide(slideShowPage + 1)

    fun previousSlide() = showSlide(slideShowPage - 1)

    private fun showSlide(page: Int) {
        val pages = document?.pages ?: return
        if (slideShowPage < 0 || page !in pages.indices || page == slideShowPage) return
        slideShowPage = page
        onPageChanged?.invoke(page)
        invalidate()
    }

    /** Tap the right two thirds or swipe left for the next slide; the left third goes back. */
    private val slideGestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.x < width / 3f) previousSlide() else nextSlide()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val forward = if (abs(vx) >= abs(vy)) vx < 0f else vy < 0f
            if (forward) nextSlide() else previousSlide()
            return true
        }
    })

    private fun drawSlide(canvas: Canvas, index: Int) {
        canvas.drawColor(android.graphics.Color.BLACK)
        val page = document?.pages?.getOrNull(index) ?: return
        if (page.width <= 0f || page.height <= 0f) return
        val fit = min(width / page.width, height / page.height)
        val slideWidth = page.width * fit
        val slideHeight = page.height * fit
        val left = (width - slideWidth) / 2f
        val top = (height - slideHeight) / 2f
        val filter = contentFilter
        val layer = if (filter != null) {
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { colorFilter = filter })
        } else {
            -1
        }
        pagePaint.color = page.background
        canvas.drawRect(left, top, left + slideWidth, top + slideHeight, pagePaint)
        val saved = canvas.save()
        canvas.clipRect(left, top, left + slideWidth, top + slideHeight)
        canvas.translate(left, top)
        canvas.scale(fit, fit)
        drawItems(canvas, page, index, fit)
        canvas.restoreToCount(saved)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isSlideShow) {
            slideGestures.onTouchEvent(event)
            return true
        }
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
        if (isSlideShow) {
            drawSlide(canvas, slideShowPage)
            return
        }
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

        // Start at the page under the top of the screen rather than walking from the first page.
        for (index in pageAt(scrollYf / scale) until pages.size) {
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
            drawItems(canvas, page, index, scale)
            canvas.restoreToCount(saved)

            canvas.drawRect(left, top, left + pageWidth, top + pageHeight, pageEdgePaint)
        }

        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawItems(canvas: Canvas, page: OfficeLayout.LaidOutPage, pageIndex: Int, scale: Float) {
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
                is OfficeLayout.PageItem.Grid -> drawGrid(canvas, item, scale)
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

    // ---------------------------------------------------------------- spreadsheets

    private val gridFillPaint = Paint()
    private val gridLinePaint = Paint().apply {
        color = OfficeLayout.TABLE_LINE_COLOUR
        strokeWidth = OfficeLayout.TABLE_LINE_UNITS
        style = Paint.Style.STROKE
    }
    private val gridClip = android.graphics.Rect()
    private val gridMetrics = Paint.FontMetrics()
    private var gridLines = FloatArray(256)

    /** One paint per look — bold or not, and colour — shared by every cell that has that look. */
    private val gridPaints = HashMap<Long, android.text.TextPaint>()
    private val rowNumbers = HashMap<Int, String>()

    private fun gridPaint(bold: Boolean, colour: Int, size: Float): android.text.TextPaint {
        val key = (colour.toLong() and 0xFFFFFFFFL) or (if (bold) 1L shl 32 else 0L)
        val paint = gridPaints.getOrPut(key) {
            android.text.TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = colour
                if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        }
        if (paint.textSize != size) paint.textSize = size
        return paint
    }

    /**
     * Draw the part of a sheet page that is on screen, straight from the cell data.
     *
     * Only the rows and columns inside the clip are touched, so the cost of a frame depends on
     * the screen, not on the sheet. Fills go first, then the grid lines, then text on top — the
     * gutter in particular is filled before its row numbers are drawn, or it would cover them.
     */
    private fun drawGrid(canvas: Canvas, item: OfficeLayout.PageItem.Grid, scale: Float) {
        if (!canvas.getClipBounds(gridClip)) return
        val rowHeight = item.rowHeight
        val rows = item.endRow - item.firstRow
        val firstVisible = ((gridClip.top - item.bodyTop) / rowHeight).toInt().coerceIn(0, rows)
        val endVisible = (((gridClip.bottom - item.bodyTop) / rowHeight).toInt() + 1).coerceIn(0, rows)
        val columns = item.columns
        val firstColumn = (item.columnAt(gridClip.left.toFloat()).takeIf { it >= 0 }
            ?: if (gridClip.left < item.columnX[0]) 0 else columns).coerceIn(0, columns)
        val endColumn = ((item.columnAt(gridClip.right.toFloat()).takeIf { it >= 0 }
            ?: if (gridClip.right >= item.right) columns - 1 else -1) + 1).coerceIn(0, columns)
        val left = item.left
        val gutterRight = item.columnX[0]
        val right = item.right
        val visibleTop = item.bodyTop + firstVisible * rowHeight
        val visibleBottom = item.bodyTop + endVisible * rowHeight
        val data = item.data

        // Fills: the header strip, the gutter, then any cell that has a background of its own.
        gridFillPaint.color = OfficeLayout.HEADER_FILL
        canvas.drawRect(left, item.top, right, item.bodyTop, gridFillPaint)
        if (endVisible > firstVisible) {
            canvas.drawRect(left, visibleTop, gutterRight, visibleBottom, gridFillPaint)
        }
        if (data.hasStyles()) {
            for (local in firstVisible until endVisible) {
                val row = item.firstRow + local
                val y = item.bodyTop + local * rowHeight
                for (column in firstColumn until endColumn) {
                    val background = data.style(row, column).background
                    if (background == 0) continue
                    gridFillPaint.color = background
                    canvas.drawRect(item.columnX[column], y, item.columnX[column + 1], y + rowHeight, gridFillPaint)
                }
            }
        }

        // Grid lines, batched into one call: one horizontal line per visible row edge and one
        // vertical line per visible column edge.
        val lineLeft = max(left, gridClip.left.toFloat())
        val lineRight = min(right, gridClip.right.toFloat())
        val lineTop = max(item.top, gridClip.top.toFloat())
        val lineBottom = min(item.bottom, gridClip.bottom.toFloat())
        val needed = ((endVisible - firstVisible + 3) + (endColumn - firstColumn + 3)) * 4
        if (gridLines.size < needed) gridLines = FloatArray(needed * 2)
        var count = 0
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            gridLines[count++] = x0
            gridLines[count++] = y0
            gridLines[count++] = x1
            gridLines[count++] = y1
        }
        if (lineRight > lineLeft) {
            if (item.top >= gridClip.top - 1) line(lineLeft, item.top, lineRight, item.top)
            for (local in firstVisible..endVisible) {
                val y = item.bodyTop + local * rowHeight
                line(lineLeft, y, lineRight, y)
            }
        }
        if (lineBottom > lineTop) {
            if (left >= gridClip.left - 1) line(left, lineTop, left, lineBottom)
            for (column in firstColumn..endColumn) {
                val x = item.columnX[column]
                line(x, lineTop, x, lineBottom)
            }
        }
        if (count > 0) canvas.drawLines(gridLines, 0, count, gridLinePaint)

        // Text is skipped when it would be too small to read: at that zoom it is grey noise, and
        // a sheet zoomed right out would otherwise draw tens of thousands of glyph runs a frame.
        if (item.textSize * scale < MIN_GRID_TEXT_PX) return
        val pad = item.padding
        val size = item.textSize
        val header = gridPaint(true, HEADER_TEXT_COLOUR, size)
        val plain = gridPaint(false, PLAIN.colour, size)
        // Everything here is set once per page rather than once per cell: the baseline offset
        // (every cell paint has the same size), the plain paint, and the widest a character can
        // be — which lets a left-aligned cell that plainly fits skip measuring altogether.
        header.getFontMetrics(gridMetrics)
        val baselineOffset = (rowHeight - (gridMetrics.descent - gridMetrics.ascent)) / 2f - gridMetrics.ascent
        val widestChar = size * WIDEST_CHAR_EMS
        val styled = data.hasStyles()

        if (item.top + rowHeight >= gridClip.top) {
            val baseline = item.top + baselineOffset
            for (column in firstColumn until endColumn) {
                val name = item.columnNames[column]
                drawCellText(
                    canvas, name, 0, name.length, item.columnX[column], item.columnX[column + 1],
                    baseline, pad, Align.CENTRE, header, widestChar,
                )
            }
        }
        if (gridClip.left <= gutterRight) {
            for (local in firstVisible until endVisible) {
                val row = item.firstRow + local
                val label = rowNumbers.getOrPut(row) { (row + 1).toString() }
                drawCellText(
                    canvas, label, 0, label.length, left, gutterRight,
                    item.bodyTop + local * rowHeight + baselineOffset, pad, Align.CENTRE, header, widestChar,
                )
            }
        }
        if (rowNumbers.size > MAX_CACHED_ROW_NUMBERS) rowNumbers.clear()

        for (local in firstVisible until endVisible) {
            val row = item.firstRow + local
            val line = data.rowText(row)
            if (line.isEmpty()) continue
            val baseline = item.bodyTop + local * rowHeight + baselineOffset
            var start = GridData.cellStart(line, firstColumn)
            var column = firstColumn
            while (start >= 0 && column < endColumn) {
                val end = GridData.cellEnd(line, start)
                if (end > start) {
                    val style = if (styled) data.style(row, column) else PLAIN
                    val paint = if (style === PLAIN || (!style.bold && style.colour == PLAIN.colour)) {
                        plain
                    } else {
                        gridPaint(style.bold, style.colour, size)
                    }
                    val align = style.align ?: if (GridData.looksNumeric(line, start, end)) Align.RIGHT else Align.LEFT
                    drawCellText(
                        canvas, line, start, end, item.columnX[column], item.columnX[column + 1],
                        baseline, pad, align, paint, widestChar,
                    )
                }
                start = if (end < line.length) end + 1 else -1
                column++
            }
        }
    }

    /**
     * One cell's text on one line, cut with an ellipsis when it does not fit — measured and cut
     * with the paint directly, so no layout object is ever made for a cell.
     *
     * A left-aligned cell short enough that it must fit even if every character were as wide as
     * the widest is drawn without being measured; that is most text cells in most sheets.
     */
    private fun drawCellText(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x0: Float,
        x1: Float,
        baseline: Float,
        pad: Float,
        align: Align,
        paint: android.text.TextPaint,
        widestChar: Float,
    ) {
        val available = x1 - x0 - 2 * pad
        if (available <= 0f) return
        if (align == Align.LEFT && (end - start) * widestChar <= available) {
            canvas.drawText(text, start, end, x0 + pad, baseline, paint)
            return
        }
        var drawEnd = end
        var width = paint.measureText(text, start, end)
        var ellipsis = 0f
        if (width > available) {
            ellipsis = paint.measureText(ELLIPSIS)
            val fit = paint.breakText(text, start, end, true, max(0f, available - ellipsis), null)
            drawEnd = start + fit
            // Never end on half a surrogate pair.
            if (drawEnd > start && Character.isHighSurrogate(text[drawEnd - 1])) drawEnd--
            width = paint.measureText(text, start, drawEnd) + ellipsis
        }
        val x = when (align) {
            Align.RIGHT -> x1 - pad - width
            Align.CENTRE -> (x0 + x1 - width) / 2f
            else -> x0 + pad
        }
        if (drawEnd > start) canvas.drawText(text, start, drawEnd, x, baseline, paint)
        if (ellipsis > 0f) canvas.drawText(ELLIPSIS, x + width - ellipsis, baseline, paint)
    }

    private fun drawImage(canvas: Canvas, item: OfficeLayout.PageItem.Image) {
        val bitmap = bitmapFor(item.data)
        if (bitmap == null) {
            // Still decoding, or not a picture this phone can read: hold its place on the page.
            canvas.drawRect(item.bounds, placeholderPaint)
            return
        }
        canvas.drawBitmap(bitmap, null, item.bounds, imagePaint)
    }

    /**
     * The decoded picture if it is ready; otherwise start decoding it and return null.
     *
     * Decoding is never done here: this runs inside [onDraw], and a single large JPEG takes long
     * enough to decode that doing it on the main thread drops frames on every scroll and, on a
     * slide deck of photographs, freezes the screen long enough to be reported as not responding.
     */
    private fun bitmapFor(data: ByteArray): Bitmap? {
        images.get(data)?.let { return it }
        if (data in undecodable || data in decoding) return null
        decoding += data
        val requested = generation
        val edge = targetImageEdge()
        DECODER.execute {
            val bitmap = decode(data, edge)
            post {
                if (requested != generation) return@post
                decoding -= data
                if (bitmap == null) undecodable += data else images.put(data, bitmap)
                invalidate()
            }
        }
        return null
    }

    /** The longest side worth decoding: the screen's, and never more than [MAX_IMAGE_EDGE]. */
    private fun targetImageEdge(): Int {
        val metrics = resources.displayMetrics
        return max(metrics.widthPixels, metrics.heightPixels).coerceIn(MIN_IMAGE_EDGE, MAX_IMAGE_EDGE)
    }

    private fun imageCacheBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(MIN_IMAGE_CACHE_BYTES, Int.MAX_VALUE.toLong()).toInt()

    private fun Float.safeRound(): Int = if (isFinite()) roundToInt() else 0

    /** The paragraph under a point: its page, its rectangle and its text. */
    private fun textAt(x: Float, y: Float): Triple<Int, RectF, String>? {
        val pages = document?.pages ?: return null
        val scale = this.scale
        val density = resources.displayMetrics.density
        val originX = SIDE_MARGIN_PX * density - scrollXf

        val index = pageAt((y + scrollYf) / scale)
        pages.getOrNull(index)?.let { page ->
            val top = pageTops[index] * scale - scrollYf
            if (y < top || y > top + page.height * scale) return null
            val left = originX + pageLeft(index) * scale
            val localX = (x - left) / scale
            val localY = (y - top) / scale
            page.items.forEach { item ->
                if (item is OfficeLayout.PageItem.Grid) {
                    val row = item.rowAt(localY)
                    val column = item.columnAt(localX)
                    if (row < 0 || column < 0) return@forEach
                    val text = item.data.text(row, column).trim()
                    if (text.isNotEmpty()) return Triple(index, item.cellBounds(row, column), text)
                    return@forEach
                }
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

    /**
     * Every occurrence of [needle] in the document, as (page, rectangle), up to [limit].
     *
     * Safe to call from a background thread: the laid-out document never changes once shown.
     * A paragraph is searched in its measured text; a sheet is searched a row string at a time,
     * and a rectangle is made only for a cell that matched — a sheet of three million cells is
     * a few hundred thousand string scans, not three million objects. [stop] is polled so a
     * search the reader has replaced ends early.
     */
    fun findMatches(needle: String, limit: Int, stop: () -> Boolean): List<Pair<Int, RectF>> {
        val pages = document?.pages ?: return emptyList()
        val found = ArrayList<Pair<Int, RectF>>()
        for (index in pages.indices) {
            if (found.size >= limit || stop()) break
            for (item in pages[index].items) {
                when (item) {
                    is OfficeLayout.PageItem.Text -> {
                        val start = item.layout.getLineStart(item.firstLine)
                        val end = item.layout.getLineEnd(item.lastLine)
                        if (end <= start) continue
                        val haystack = item.layout.text.subSequence(start, end).toString()
                        var from = 0
                        while (found.size < limit) {
                            val at = haystack.indexOf(needle, from, ignoreCase = true)
                            if (at < 0) break
                            found += index to RectF(item.x, item.y, item.x + item.layout.width, item.y + item.height)
                            from = at + needle.length
                        }
                    }
                    is OfficeLayout.PageItem.Grid -> {
                        for (row in item.firstRow until item.endRow) {
                            val line = item.data.rowText(row)
                            var from = 0
                            var column = 0
                            var counted = 0
                            while (found.size < limit) {
                                val at = line.indexOf(needle, from, ignoreCase = true)
                                if (at < 0) break
                                // Which cell the match is in: the separators before it.
                                for (position in counted until at) {
                                    if (line[position] == GridData.SEPARATOR) column++
                                }
                                counted = at
                                if (column < item.columns) found += index to item.cellBounds(row, column)
                                from = at + needle.length
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        return found
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
        // A stored zoom of NaN would reach roundToInt in the zoom label and throw.
        zoom = position[3].takeIf { it.isFinite() }?.coerceIn(MIN_ZOOM, maxZoomLevel) ?: readableZoom
        goToPage(position[0].toInt())
        onScaleChanged?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        zoomAnimator?.cancel()
        // Decodes still in flight are ignored when they land; coming back decodes afresh.
        generation++
        decoding.clear()
        images.evictAll()
    }

    private companion object {
        /** One thread: decoding several photographs at once is how the heap runs out. */
        val DECODER: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "office-image-decode").apply { isDaemon = true }
            }

        /**
         * Decode a picture at roughly the size it is drawn, off the main thread.
         *
         * Full-resolution decoding is the single easiest way to run out of memory here: a phone
         * photo pasted into a report is twelve megapixels and forty-eight megabytes decoded, to be
         * drawn three centimetres across.
         */
        fun decode(data: ByteArray, maxEdge: Int): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                var sample = 1
                while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)
            }
        } catch (outOfMemory: OutOfMemoryError) {
            // Shown as a placeholder; the rest of the document is still worth reading.
            null
        } catch (failure: RuntimeException) {
            null
        }

        /** Below this many pixels high, cell text is not drawn: it could not be read anyway. */
        const val MIN_GRID_TEXT_PX = 4f
        const val HEADER_TEXT_COLOUR = 0xFF000000.toInt()
        const val ELLIPSIS = "…"
        const val MAX_CACHED_ROW_NUMBERS = 4096

        /** No glyph in a sheet's text font is wider than this many text sizes. */
        const val WIDEST_CHAR_EMS = 1.2f
        val PLAIN = GridStyle()

        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 8f

        /** A Letter page's width, in layout units: what "reading size" is measured against. */
        private const val READABLE_WIDTH_UNITS = 612f * OfficeLayout.UNITS_PER_POINT

        /**
         * Wider than two Letter pages is a sheet, not a page or a slide (a 16:9 slide is about 1.6
         * pages wide), and is opened at reading size rather than fitted.
         */
        private const val WIDE_CONTENT_UNITS = 2f * READABLE_WIDTH_UNITS
        const val ZOOM_STEP = 1.25f
        const val DOUBLE_TAP_ZOOM = 1.5f
        const val ZOOM_ANIMATION_MS = 220L
        const val SCROLL_ANIMATION_MS = 260
        const val PAGE_GAP_UNITS = 8f * OfficeLayout.UNITS_PER_POINT
        const val SIDE_MARGIN_PX = 6f
        const val BACKDROP_COLOUR = 0xFF303338.toInt()
        const val SEPIA_BACKDROP = 0xFF4A4438.toInt()
        const val MATCH_SCREEN_FRACTION = 0.3f
        const val MAX_IMAGE_EDGE = 2048
        const val MIN_IMAGE_EDGE = 512
        const val MIN_IMAGE_CACHE_BYTES = 8L * 1024 * 1024
    }
}
