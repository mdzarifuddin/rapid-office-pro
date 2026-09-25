package top.teamaos.pdfreader.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import top.teamaos.pdfreader.core.PageMeasurer
import top.teamaos.pdfreader.core.PageGeometry
import top.teamaos.pdfreader.core.PageRenderer
import top.teamaos.pdfreader.core.PdfLayout
import top.teamaos.pdfreader.core.RenderCache
import top.teamaos.pdfreader.core.Stroke
import top.teamaos.pdfreader.core.TileKey
import top.teamaos.pdfreader.core.ViewMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Page tinting applied at draw time, so switching costs nothing and re-renders nothing. */
enum class ColorMode { NORMAL, NIGHT, SEPIA, GRAYSCALE }

/**
 * The reading surface: scroll, zoom, and draw.
 *
 * Everything expensive is kept off the main thread and off the critical path of a gesture. While a
 * finger is down or a fling is running, the view only ever draws what is already in memory and asks
 * for cheap thumbnails; sharp renders are requested once motion settles. That is what keeps a
 * thousand-page scan scrolling at full frame rate, and it is also why an idle reader draws nothing
 * and wakes nothing.
 *
 * Page positions come from [PdfLayout], which derives them arithmetically, so no per-page view
 * objects exist and document length costs nothing.
 */
class PdfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var geometry: PageGeometry? = null
    private var renderer: PageRenderer? = null
    private var cache: RenderCache? = null
    private var measurer: PageMeasurer? = null
    private var layout: PdfLayout? = null

    private var scrollXf = 0f
    private var scrollYf = 0f

    private val scroller = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null

    private var touchActive = false
    private var scaling = false

    /** Zoom to come back to when the reader double-taps out again. */
    private var zoomBeforeDoubleTap: Float? = null

    /** Set for the instant between [onDoubleTap] and the onDown that follows it; see onDown. */
    private var startingDoubleTapZoom = false

    /** Set while a page is being drawn from a bitmap rendered for a different zoom. */
    private var awaitingSharpen = false

    /**
     * Where the current page turn started, as a scroll X.
     *
     * In the paged modes a swipe is a page turn, not a scroll: the sideways movement is held to
     * one slot either side of this, so a hard flick moves exactly one page instead of skimming
     * through five. Set when a finger goes down on a fitted paged view, cleared when it settles.
     */
    private var turnAnchorX = 0f
    private var turningPage = false

    private val turnShadowPaint = Paint()
    private val turnShadowMatrix = Matrix()
    private var leftEdgeShadow: LinearGradient? = null
    private var rightEdgeShadow: LinearGradient? = null

    private val pageRect = RectF()
    private val destRect = RectF()
    private val srcRect = Rect()
    private val tileDest = RectF()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFD54F }
    private val activeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FF9800.toInt() }
    private val highlightRect = RectF()

    /** Search matches to paint, keyed by page, in that page's own point coordinates. */
    private var highlights: Map<Int, List<RectF>> = emptyMap()

    /** Which match is the current one, as (page, index within that page's list). */
    private var activeHighlight: Pair<Int, Int>? = null
    private val pageFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pageEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x22000000
    }

    var onPageChanged: ((page: Int) -> Unit)? = null
    var onSingleTap: (() -> Unit)? = null
    var onScaleChanged: (() -> Unit)? = null

    var onSelectionChanged: ((selected: Set<Int>) -> Unit)? = null

    /** Held down over text: the point is in that page's own coordinates (points, origin bottom-left). */
    var onTextLongPress: ((page: Int, xPts: Float, yPts: Float) -> Unit)? = null

    /**
     * Asked before a tap becomes a chrome toggle: return true if a link was there and was followed.
     *
     * The view has no idea what a link is; it only knows where the finger landed on which page.
     * Whether that spot is a link, and what following it means, belongs to the reader screen.
     */
    var onTapLink: ((page: Int, xPts: Float, yPts: Float) -> Boolean)? = null

    /** A selection handle is being dragged; [start] says which end. Same coordinate space. */
    var onTextHandleDragged: ((page: Int, start: Boolean, xPts: Float, yPts: Float) -> Unit)? = null

    /** The reader let go of a selection handle. */
    var onTextHandleReleased: (() -> Unit)? = null

    /** The selection went away because the reader tapped somewhere else. */
    var onTextSelectionCleared: (() -> Unit)? = null

    // ------------------------------------------------------------------ brush

    /**
     * Drawing mode: one finger draws instead of scrolling; two still pan and zoom.
     *
     * Keeping the two-finger gestures live matters more than it sounds. Drawing on a page you
     * cannot move or magnify means drawing on whatever part of it happens to be on screen, and the
     * whole point of masking something is to put it exactly over the words underneath.
     */
    var drawMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            currentPoints.clear()
            strokePage = -1
            invalidate()
        }

    var brushColour: Int = 0xFFFFFFFF.toInt()
    var brushWidthPts: Float = 12f
    var erasing: Boolean = false

    /** Finished strokes, by page. The reader owns them; this only draws them. */
    private var marks: Map<Int, List<Stroke>> = emptyMap()

    /** Reported when a stroke is finished, in page coordinates. */
    var onStrokeDrawn: ((page: Int, points: FloatArray) -> Unit)? = null

    /** Reported when the eraser passes over strokes; they are removed by the reader, not here. */
    var onStrokesErased: ((strokes: List<Stroke>) -> Unit)? = null

    private val currentPoints = ArrayList<Float>(256)
    private var strokePage = -1
    private var erasedThisGesture = HashSet<Long>()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strokePath = Path()
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFF5252.toInt()
        strokeWidth = 2f
    }
    private var eraserX = 0f
    private var eraserY = 0f
    private var eraserShowing = false

    fun setMarks(byPage: Map<Int, List<Stroke>>) {
        marks = byPage
        invalidate()
    }

    private var textSelectionPage = -1
    private var textSelectionRects: List<RectF> = emptyList()
    private val textSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x552478C8 }
    private val textHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2478C8.toInt() }
    private val textHandleStart = PointF()
    private val textHandleEnd = PointF()
    private var draggingHandle = 0

    /** True while some text is selected, which changes what a plain tap does. */
    val hasTextSelection: Boolean get() = textSelectionRects.isNotEmpty()

    /**
     * Show [rects] — in page coordinates — as the selected text on [page].
     *
     * Kept in page coordinates rather than screen ones so the selection survives scrolling and
     * zooming without being recomputed, which is the same reason search highlights work that way.
     */
    fun setTextSelection(page: Int, rects: List<RectF>) {
        textSelectionPage = page
        textSelectionRects = rects
        invalidate()
    }

    fun clearTextSelection() {
        if (textSelectionRects.isEmpty() && textSelectionPage < 0) return
        textSelectionPage = -1
        textSelectionRects = emptyList()
        draggingHandle = 0
        invalidate()
    }

    private val selection = linkedSetOf<Int>()
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332478C8 }
    private val selectionEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2478C8.toInt()
    }

    /**
     * Reading lock: scrolling, flinging, pinch and double-tap zoom all keep working, but taps and
     * long presses do nothing.
     *
     * For reading with the phone in one hand, where a stray thumb would otherwise keep summoning
     * the toolbars or dropping into page-selection mode.
     */
    var interactionLocked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) selectionMode = false
        }

    /** Page-picking mode: taps select pages instead of toggling the chrome. */
    var selectionMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) selection.clear()
            onSelectionChanged?.invoke(selection)
            invalidate()
        }

    val selectedPages: Set<Int> get() = selection

    fun toggleSelection(page: Int) {
        if (!selection.remove(page)) selection.add(page)
        onSelectionChanged?.invoke(selection)
        invalidate()
    }

    fun selectAllPages() {
        selection.clear()
        (0 until pageCount).forEach { selection.add(it) }
        onSelectionChanged?.invoke(selection)
        invalidate()
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        onSelectionChanged?.invoke(selection)
        invalidate()
    }

    /** Which page is under a screen point, or null if the point is in the gap between pages. */
    fun pageAt(x: Float, y: Float): Int? {
        val active = layout ?: return null
        val range = active.visiblePages(scrollXf, scrollYf)
        for (page in range) {
            active.pageRect(page, pageRect)
            if (x + scrollXf in pageRect.left..pageRect.right &&
                y + scrollYf in pageRect.top..pageRect.bottom
            ) {
                return page
            }
        }
        return null
    }

    private var lastReportedPage = -1

    var colorMode: ColorMode = ColorMode.NORMAL
        set(value) {
            if (field == value) return
            field = value
            applyColorMode()
            invalidate()
        }

    var viewMode: ViewMode
        get() = layout?.mode ?: ViewMode.VERTICAL
        set(value) {
            val active = layout ?: return
            if (active.mode == value) return
            val anchor = captureAnchor()
            active.mode = value
            active.zoom = 1f
            renderer?.invalidateScale()
            restoreAnchor(anchor)
            requestRenders(fullDetail = true)
            invalidate()
        }

    val currentPage: Int
        get() = layout?.pageAtViewportCentre(scrollXf, scrollYf) ?: 0

    val pageCount: Int
        get() = layout?.pageCount ?: 0

    val zoom: Float
        get() = layout?.zoom ?: 1f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            // GestureDetector delivers onDoubleTap *before* onDown for the second tap, so an
            // unconditional cancel here killed the double-tap zoom on the frame it started —
            // the callback fired, the animator never ran, and nothing appeared to happen.
            if (startingDoubleTapZoom) startingDoubleTapZoom = false else zoomAnimator?.cancel()
            return true
        }

        // Confirmed, not "up": otherwise the first tap of a double-tap flashes the chrome on and
        // off before the zoom happens.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (interactionLocked) return true
            val page = pageAt(e.x, e.y)
            if (!selectionMode && !hasTextSelection && page != null) {
                val point = screenToPagePoint(page, e.x, e.y)
                if (point != null && onTapLink?.invoke(page, point.x, point.y) == true) return true
            }
            when {
                selectionMode -> page?.let { toggleSelection(it) }
                // Tapping away from selected text drops the selection, the way it does in a
                // browser or a document editor, rather than also flashing the toolbars.
                hasTextSelection -> {
                    clearTextSelection()
                    onTextSelectionCleared?.invoke()
                }
                else -> onSingleTap?.invoke()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (interactionLocked) return
            val page = pageAt(e.x, e.y) ?: return
            if (selectionMode) {
                toggleSelection(page)
                return
            }
            // Holding a page used to drop into page editing, which is a heavyweight mode to fall
            // into by accident. Holding text now does what holding text does everywhere else.
            val point = screenToPagePoint(page, e.x, e.y)
            if (point != null) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onTextLongPress?.invoke(page, point.x, point.y)
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return false
            scrollBy(dx, dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return false
            val active = layout ?: return false
            // A flick in the paged modes turns one page, however hard it was. A book does not
            // riffle past five sheets because you pushed harder.
            if (turningPage && abs(vx) > abs(vy)) {
                settleTurn(if (vx < 0) 1 else -1)
                return true
            }
            scroller.fling(
                scrollXf.roundToInt(),
                scrollYf.roundToInt(),
                -vx.roundToInt(),
                -vy.roundToInt(),
                0,
                active.maxScrollX.roundToInt(),
                0,
                active.maxScrollY.roundToInt(),
            )
            postInvalidateOnAnimation()
            return true
        }

        /**
         * Double tap zooms in on the spot you tapped; double tap again returns to exactly the zoom
         * you were at before, not to a fixed level.
         */
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val active = layout ?: return false
            startingDoubleTapZoom = true
            if (active.zoom > FIT_ZOOM * 1.05f) {
                val restore = zoomBeforeDoubleTap ?: FIT_ZOOM
                zoomBeforeDoubleTap = null
                animateZoomTo(restore, e.x, e.y)
            } else {
                zoomBeforeDoubleTap = active.zoom
                animateZoomTo(DOUBLE_TAP_ZOOM, e.x, e.y)
            }
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                // Two fingers means zooming, not turning: release the one-slot hold.
                turningPage = false
                return layout != null
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
                onMotionSettled()
            }
        },
    )

    init {
        isFocusable = true
        setBackgroundColor(BACKDROP_COLOUR)
        // Quick scale — double-tap then drag to zoom — is on by default, and it swallows the second
        // tap of a double-tap to start a pinch. That stopped double-tap-to-zoom from ever firing.
        scaleDetector.isQuickScaleEnabled = false
    }

    /** Attach an open document. The caller keeps ownership of everything passed in. */
    fun attach(
        geometry: PageGeometry,
        renderer: PageRenderer,
        cache: RenderCache,
        measurer: PageMeasurer,
    ) {
        this.geometry = geometry
        this.renderer = renderer
        this.cache = cache
        this.measurer = measurer
        this.layout = PdfLayout(geometry).apply {
            gapPx = PAGE_GAP_DP * resources.displayMetrics.density
            viewportWidth = width
            viewportHeight = height
        }
        scrollXf = 0f
        scrollYf = 0f
        lastReportedPage = -1
        requestRenders(fullDetail = true)
        invalidate()
    }

    fun detach() {
        geometry = null
        renderer = null
        cache = null
        measurer = null
        layout = null
        invalidate()
    }

    /**
     * Show search matches. [rectsByPage] holds each page's matches in PDF point coordinates, which
     * are zoom-independent, so highlights survive zooming without being recomputed.
     */
    fun setSearchHighlights(rectsByPage: Map<Int, List<RectF>>, active: Pair<Int, Int>?) {
        highlights = rectsByPage
        activeHighlight = active
        invalidate()
    }

    fun clearSearchHighlights() {
        if (highlights.isEmpty() && activeHighlight == null) return
        highlights = emptyMap()
        activeHighlight = null
        invalidate()
    }

    /**
     * Scroll so that [rect] — in page point coordinates — sits comfortably on screen. Used to bring
     * a search match into view without yanking the page to the very top.
     */
    fun scrollToPageRect(page: Int, rect: RectF) {
        val active = layout ?: return
        val pages = geometry ?: return
        active.pageRect(page, pageRect)
        val scaleY = pageRect.height() / max(1f, pages.heightPts(page))
        val topPts = max(rect.top, rect.bottom)
        val target = pageRect.top + (pages.heightPts(page) - topPts) * scaleY
        scrollYf = (target - active.viewportHeight * MATCH_SCREEN_FRACTION).coerceIn(0f, active.maxScrollY)
        if (active.isPaged) {
            val (x, _) = active.scrollToPage(page)
            scrollXf = x
        }
        clampScroll()
        onMotionSettled()
        invalidate()
    }

    /** Re-read positions after the background measurer corrected some page sizes. */
    fun onGeometryUpdated() {
        val anchor = captureAnchor()
        restoreAnchor(anchor)
        invalidate()
    }

    /**
     * Jump to a page while the fast-scroll handle is still being dragged.
     *
     * Deliberately not [goToPage]. That settles the motion, and settling throws away every bitmap
     * rendered for the current scale and queues full-quality renders — on each of the dozens of
     * move events one drag produces. Dragging the handle now costs a scroll and a thumbnail
     * request; the sharp render happens once, when the handle is let go.
     */
    fun seekToPage(page: Int) {
        val active = layout ?: return
        turningPage = false
        scroller.forceFinished(true)
        val (x, y) = active.scrollToPage(page)
        scrollXf = x
        scrollYf = y
        clampScroll()
        pendingSettle = true
        reportPageIfChanged()
        requestRenders(fullDetail = false)
        invalidate()
    }

    fun goToPage(page: Int, animate: Boolean = false) {
        val active = layout ?: return
        turningPage = false
        val (x, y) = active.scrollToPage(page)
        if (animate) {
            scroller.startScroll(
                scrollXf.roundToInt(),
                scrollYf.roundToInt(),
                (x - scrollXf).roundToInt(),
                (y - scrollYf).roundToInt(),
                SCROLL_ANIMATION_MS,
            )
            postInvalidateOnAnimation()
        } else {
            scrollXf = x
            scrollYf = y
            clampScroll()
            onMotionSettled()
            invalidate()
        }
    }

    fun resetZoom() {
        animateZoomTo(FIT_ZOOM, width / 2f, height / 2f)
    }

    /** Zoom so the page fills the screen sideways — the other fit people reach for. */
    fun fitWidth() {
        val active = layout ?: return
        animateZoomTo(active.fitWidthZoom(currentPage), width / 2f, 0f)
    }

    /**
     * Put the whole of [page] on screen and report where it landed, ready to be cropped.
     *
     * Cropping needs to see the entire page: the frame is dragged against the page's own edges,
     * and half of them being off screen — which is the normal state of a page fitted to the width
     * of a phone — makes the top and bottom impossible to reach. The zoom is changed here and put
     * back when the crop screen closes.
     */
    fun prepareCrop(page: Int): RectF? {
        val active = layout ?: return null
        if (page !in 0 until active.pageCount || width <= 0 || height <= 0) return null
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        turningPage = false

        val fit = min(
            width * CROP_FIT_WIDTH / max(1f, active.pageWidth(page)),
            height * CROP_FIT_HEIGHT / max(1f, active.pageHeight(page)),
        )
        active.zoom = (active.zoom * fit).coerceIn(MIN_ZOOM, MAX_ZOOM)
        renderer?.invalidateScale()

        active.pageRect(page, pageRect)
        scrollXf = pageRect.centerX() - width / 2f
        scrollYf = pageRect.centerY() - height / 2f
        clampScroll()
        onScaleChanged?.invoke()
        requestRenders(fullDetail = true)
        invalidate()
        return pageScreenRect(page)
    }

    /**
     * Where a page is sitting on screen right now, or null if it is not on screen at all.
     *
     * Used by the crop frame, which has to line up with the page rather than the viewport.
     */
    fun pageScreenRect(page: Int): RectF? {
        val active = layout ?: return null
        if (page !in 0 until active.pageCount) return null
        active.pageRect(page, pageRect)
        val rect = RectF(
            pageRect.left - scrollXf,
            pageRect.top - scrollYf,
            pageRect.right - scrollXf,
            pageRect.bottom - scrollYf,
        )
        if (rect.right <= 0f || rect.left >= width || rect.bottom <= 0f || rect.top >= height) return null
        return rect
    }

    /** Put the zoom back after a crop, without any of the settling a normal zoom change does. */
    fun restoreZoomAfterCrop(previous: Float) {
        val active = layout ?: return
        active.zoom = previous.coerceIn(MIN_ZOOM, MAX_ZOOM)
        renderer?.invalidateScale()
        clampScroll()
        onScaleChanged?.invoke()
        requestRenders(fullDetail = true)
        invalidate()
    }

    /**
     * Step the zoom about the middle of the screen, for the on-screen buttons.
     *
     * A small step, animated: repeated taps read as one smooth ramp with the percentage climbing
     * through it, rather than three jarring jumps.
     */
    fun zoomIn() = animateZoomTo(zoom * ZOOM_BUTTON_STEP, width / 2f, height / 2f)

    fun zoomOut() = animateZoomTo(zoom / ZOOM_BUTTON_STEP, width / 2f, height / 2f)

    /** Set the zoom directly, for a slider. */
    fun setZoomLevel(target: Float, animate: Boolean = false) {
        if (animate) {
            animateZoomTo(target, width / 2f, height / 2f)
        } else {
            applyZoom(target, width / 2f, height / 2f)
        }
    }

    val minZoom: Float get() = MIN_ZOOM

    val maxZoom: Float get() = MAX_ZOOM

    val canZoomIn: Boolean get() = zoom < MAX_ZOOM - 0.01f

    val canZoomOut: Boolean get() = zoom > MIN_ZOOM + 0.01f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val active = layout ?: return
        val anchor = if (oldw > 0 && oldh > 0) captureAnchor() else null
        // Turning the screen: a zoom picked for the portrait shape means something quite different
        // sideways — fit-width on a phone held upright becomes a page far taller than the screen —
        // so start the new shape fitted again, on the same spot of the same page.
        val turned = oldw > 0 && oldh > 0 && (w > h) != (oldw > oldh)
        if (turned) {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
            turningPage = false
            zoomBeforeDoubleTap = null
            active.zoom = FIT_ZOOM
        }
        active.viewportWidth = w
        active.viewportHeight = h
        renderer?.invalidateScale()
        if (anchor != null) restoreAnchor(anchor) else clampScroll()
        if (turned) onScaleChanged?.invoke()
        requestRenders(fullDetail = true)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A selection handle under the finger owns the gesture outright: it must not be handed to
        // the scroll detector as well, or adjusting a selection would drag the page with it.
        if (handleTouch(event)) return true
        if (drawMode && drawTouch(event)) return true

        scaleDetector.onTouchEvent(event)
        if (!scaling) gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                beginTurnIfPaged()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                // A fling has already started scrolling by now; settling is handled there.
                // A running zoom settles itself when it ends — snapping now would fight it.
                if (scroller.isFinished && zoomAnimator?.isRunning != true) onMotionSettled()
            }
        }
        return true
    }

    /** Returns true when the event belongs to a selection handle and nothing else should see it. */
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = handleAt(event.x, event.y)
                if (draggingHandle == 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle == 0) return false
                val point = screenToPagePoint(textSelectionPage, event.x, event.y - handleLift())
                if (point != null) {
                    onTextHandleDragged?.invoke(
                        textSelectionPage,
                        draggingHandle < 0,
                        point.x,
                        point.y,
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle == 0) return false
                draggingHandle = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                onTextHandleReleased?.invoke()
                return true
            }
        }
        return draggingHandle != 0
    }

    /** A handle is held below the text it marks, so the finger aims at the line, not the ball. */
    private fun handleLift(): Float = HANDLE_RADIUS_DP * 2f * resources.displayMetrics.density

    /**
     * One finger in drawing mode: draw. Two: let the page move.
     *
     * A second finger arriving abandons whatever was being drawn rather than finishing it, because
     * a stroke that ends where a pinch began is never the stroke anybody wanted.
     */
    private fun drawTouch(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            if (currentPoints.isNotEmpty()) {
                currentPoints.clear()
                strokePage = -1
                invalidate()
            }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                erasedThisGesture = HashSet()
                if (erasing) {
                    eraserShowing = true
                    eraseAt(event.x, event.y)
                } else {
                    strokePage = pageAt(event.x, event.y) ?: -1
                    currentPoints.clear()
                    addPoint(event.x, event.y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (erasing) {
                    // Every sample between the last event and this one, so a fast sweep does not
                    // step over a thin stroke.
                    for (index in 0 until event.historySize) {
                        eraseAt(event.getHistoricalX(index), event.getHistoricalY(index))
                    }
                    eraseAt(event.x, event.y)
                } else {
                    for (index in 0 until event.historySize) {
                        addPoint(event.getHistoricalX(index), event.getHistoricalY(index))
                    }
                    addPoint(event.x, event.y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                eraserShowing = false
                if (!erasing) finishStroke()
                erasedThisGesture = HashSet()
                invalidate()
                return true
            }
        }
        return false
    }

    private fun addPoint(x: Float, y: Float) {
        if (strokePage < 0) {
            strokePage = pageAt(x, y) ?: return
        }
        val point = screenToPagePoint(strokePage, x, y) ?: return
        currentPoints.add(point.x)
        currentPoints.add(point.y)
    }

    private fun finishStroke() {
        val page = strokePage
        val points = currentPoints.toList()
        currentPoints.clear()
        strokePage = -1
        if (page < 0 || points.size < 2) return
        // A tap with no movement is still a mark: a dot, drawn as a one-point stroke.
        val simplified = Stroke.simplify(points, brushWidthPts * SIMPLIFY_FRACTION)
        onStrokeDrawn?.invoke(page, simplified)
    }

    private fun eraseAt(x: Float, y: Float) {
        eraserX = x
        eraserY = y
        val page = pageAt(x, y) ?: return
        val here = marks[page] ?: return
        val point = screenToPagePoint(page, x, y) ?: return
        val reach = eraserRadiusPts(page)
        val hit = here.filter { it.id !in erasedThisGesture && it.distanceTo(point.x, point.y) <= reach }
        if (hit.isEmpty()) return
        hit.forEach { erasedThisGesture.add(it.id) }
        onStrokesErased?.invoke(hit)
    }

    /** The eraser is the brush's own width, so what it takes matches the circle being shown. */
    private fun eraserRadiusPts(page: Int): Float {
        val active = layout ?: return brushWidthPts
        val pages = geometry ?: return brushWidthPts
        val width = active.pageWidth(page)
        if (width <= 0f) return brushWidthPts
        val scale = width / max(1f, pages.widthPts(page))
        return max(brushWidthPts, ERASER_MIN_DP * resources.displayMetrics.density / max(0.0001f, scale))
    }

    /**
     * Paint the strokes on a page, and the one being drawn.
     *
     * Drawn per page inside the page's own transform rather than as one screen-space overlay: that
     * is what keeps a mask sitting exactly on the words it covers through every zoom and scroll,
     * and it means a stroke's width is a width on the page, not on the glass.
     */
    private fun drawMarks(canvas: Canvas, page: Int, dest: RectF) {
        val pages = geometry ?: return
        val here = marks[page]
        val drawingHere = strokePage == page && currentPoints.size >= 2
        if (here.isNullOrEmpty() && !drawingHere) return

        val widthPts = max(1f, pages.widthPts(page))
        val heightPts = max(1f, pages.heightPts(page))
        val scaleX = dest.width() / widthPts
        val scaleY = dest.height() / heightPts

        here?.forEach { stroke -> drawStroke(canvas, stroke.points, stroke.colour, stroke.widthPts, dest, heightPts, scaleX, scaleY) }
        if (drawingHere) {
            drawStroke(
                canvas,
                currentPoints.toFloatArray(),
                brushColour,
                brushWidthPts,
                dest,
                heightPts,
                scaleX,
                scaleY,
            )
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        points: FloatArray,
        colour: Int,
        widthPts: Float,
        dest: RectF,
        heightPts: Float,
        scaleX: Float,
        scaleY: Float,
    ) {
        if (points.size < 2) return
        strokePaint.color = colour
        strokePaint.strokeWidth = max(1f, widthPts * scaleX)
        if (points.size == 2) {
            canvas.drawPoint(
                dest.left + points[0] * scaleX,
                dest.top + (heightPts - points[1]) * scaleY,
                strokePaint,
            )
            return
        }
        strokePath.reset()
        strokePath.moveTo(dest.left + points[0] * scaleX, dest.top + (heightPts - points[1]) * scaleY)
        var index = 2
        while (index + 1 < points.size) {
            strokePath.lineTo(
                dest.left + points[index] * scaleX,
                dest.top + (heightPts - points[index + 1]) * scaleY,
            )
            index += 2
        }
        canvas.drawPath(strokePath, strokePaint)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXf = scroller.currX.toFloat()
            scrollYf = scroller.currY.toFloat()
            clampScroll()
            reportPageIfChanged()
            // Cheap during motion: thumbnails only, so the fling never waits on a full render.
            requestRenders(fullDetail = false)
            postInvalidateOnAnimation()
        } else if (!touchActive && !scaling && pendingSettle) {
            onMotionSettled()
        }
    }

    private var pendingSettle = false

    private fun scrollBy(dx: Float, dy: Float) {
        val active = layout ?: return
        scrollXf += dx
        scrollYf += dy
        // One page turn per gesture: the finger can uncover the next page and no further, so
        // letting go always lands on this page or the one beside it.
        if (turningPage) {
            val stride = active.slotStride
            scrollXf = scrollXf.coerceIn(turnAnchorX - stride, turnAnchorX + stride)
        }
        clampScroll()
        pendingSettle = true
        reportPageIfChanged()
        requestRenders(fullDetail = false)
        invalidate()
    }

    private fun clampScroll() {
        val active = layout ?: return
        scrollXf = scrollXf.coerceIn(0f, active.maxScrollX)
        scrollYf = scrollYf.coerceIn(0f, active.maxScrollY)
    }

    private fun applyZoom(requested: Float, focusX: Float, focusY: Float) {
        val active = layout ?: return
        val clamped = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (clamped == active.zoom) return
        // Keep the spot of the page under the fingers exactly where it is. Scaling the scroll
        // offset alone assumed the content grows from its top-left corner, which is false whenever
        // a page is narrower or shorter than the screen and so sits centred — the normal case in
        // landscape and in the paged views — and the zoom drifted towards the middle instead of
        // going where the fingers were. Pinning a point on the page itself is right in every layout.
        val page = pageAt(focusX, focusY) ?: currentPage
        active.pageRect(page, pageRect)
        val fractionX = (scrollXf + focusX - pageRect.left) / max(1f, pageRect.width())
        val fractionY = (scrollYf + focusY - pageRect.top) / max(1f, pageRect.height())
        active.zoom = clamped
        active.pageRect(page, pageRect)
        scrollXf = pageRect.left + fractionX * pageRect.width() - focusX
        scrollYf = pageRect.top + fractionY * pageRect.height() - focusY
        clampScroll()
        pendingSettle = true
        onScaleChanged?.invoke()
        invalidate()
    }

    private fun animateZoomTo(target: Float, focusX: Float, focusY: Float) {
        val active = layout ?: return
        val from = active.zoom
        val to = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(to - from) < 0.01f) return
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = ZOOM_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyZoom(it.animatedValue as Float, focusX, focusY) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onMotionSettled()
            })
            start()
        }
    }

    /**
     * A finger went down on a fitted paged view: remember which page we are turning away from.
     *
     * Not armed while zoomed in — there, sideways movement is panning around a magnified page and
     * clamping it to one slot would make the page impossible to read across.
     */
    private fun beginTurnIfPaged() {
        val active = layout ?: return
        turningPage = active.isPaged && active.zoom <= FIT_ZOOM * 1.05f
        if (turningPage) turnAnchorX = active.snappedScrollX(scrollXf)
    }

    /** Animate the current turn to its conclusion: [direction] pages on from where it started. */
    private fun settleTurn(direction: Int) {
        val active = layout ?: return
        val target = (turnAnchorX + direction * active.slotStride).coerceIn(0f, active.maxScrollX)
        turningPage = false
        pendingSettle = true
        if (abs(target - scrollXf) < 0.5f) {
            scrollXf = target
            onMotionSettled()
            return
        }
        scroller.startScroll(
            scrollXf.roundToInt(),
            scrollYf.roundToInt(),
            (target - scrollXf).roundToInt(),
            0,
            PAGE_TURN_MS,
        )
        postInvalidateOnAnimation()
    }

    /** Motion stopped: snap if paged, then ask for sharp renders. */
    private fun onMotionSettled() {
        pendingSettle = false
        val active = layout ?: return
        if (turningPage) {
            // Released mid-turn. A third of a page is enough to mean it: past that the page
            // completes its turn, short of it it falls back, the way a held sheet would.
            val travelled = scrollXf - turnAnchorX
            val stride = max(1f, active.slotStride)
            settleTurn(
                when {
                    travelled > stride * TURN_COMMIT_FRACTION -> 1
                    travelled < -stride * TURN_COMMIT_FRACTION -> -1
                    else -> 0
                },
            )
            return
        }
        if (active.isPaged && active.zoom <= FIT_ZOOM * 1.05f) {
            val snapped = active.snappedScrollX(scrollXf)
            if (abs(snapped - scrollXf) > 0.5f) {
                scroller.startScroll(
                    scrollXf.roundToInt(),
                    scrollYf.roundToInt(),
                    (snapped - scrollXf).roundToInt(),
                    0,
                    SNAP_ANIMATION_MS,
                )
                postInvalidateOnAnimation()
                return
            }
        }
        renderer?.invalidateScale()
        requestRenders(fullDetail = true)
        reportPageIfChanged()
        invalidate()
    }

    private fun reportPageIfChanged() {
        val page = currentPage
        if (page != lastReportedPage) {
            lastReportedPage = page
            onPageChanged?.invoke(page)
        }
    }

    /**
     * Queue whatever the screen is missing.
     *
     * With [fullDetail] false — during a drag or fling — only thumbnails are asked for, which keeps
     * the render thread from starting a long page render that would be stale before it finished.
     */
    private fun requestRenders(fullDetail: Boolean) {
        val active = layout ?: return
        val render = renderer ?: return
        val pages = geometry ?: return
        if (active.viewportWidth <= 0 || active.viewportHeight <= 0) return

        // Mid-scroll, a little look-ahead too: the preview of the next page is ready as it arrives.
        val lookAhead = if (active.isPaged) active.viewportWidth.toFloat() else active.viewportHeight.toFloat()
        val overscan = lookAhead * if (fullDetail) PREFETCH_SCREENS else SCROLL_LOOKAHEAD_SCREENS
        val range = active.visiblePages(scrollXf, scrollYf, overscan)
        if (range.isEmpty()) return

        // Every time, not only once motion stops. Previews queued for pages already scrolled past
        // otherwise run first — their priority was set when they were near — and the page the
        // reader lands on waits behind a long tail of pages nobody is looking at any more.
        render.retainPages(range)
        measurer?.prioritise(range)

        val centre = active.pageAtViewportCentre(scrollXf, scrollYf)
        for (page in range) {
            val distance = abs(page - centre)
            val aspect = pages.widthPts(page) / max(1f, pages.heightPts(page))
            render.requestThumb(page, aspect, distance * PRIORITY_STEP)
            if (!fullDetail) continue

            val pageWidth = active.pageWidth(page).roundToInt()
            val pageHeight = active.pageHeight(page).roundToInt()
            if (pageWidth <= 0 || pageHeight <= 0) continue
            render.requestPage(page, pageWidth, pageHeight, distance * PRIORITY_STEP + 1)

            if (render.tilesNeededFor(pageWidth, pageHeight)) {
                requestVisibleTiles(render, active, page, pageWidth, pageHeight, distance)
            }
        }
    }

    /** Only the tiles actually overlapping the viewport — never a whole zoomed page. */
    private fun requestVisibleTiles(
        render: PageRenderer,
        active: PdfLayout,
        page: Int,
        pageWidth: Int,
        pageHeight: Int,
        distance: Int,
    ) {
        active.pageRect(page, pageRect)
        val visibleLeft = max(0f, scrollXf - pageRect.left)
        val visibleTop = max(0f, scrollYf - pageRect.top)
        val visibleRight = min(pageWidth.toFloat(), scrollXf + active.viewportWidth - pageRect.left)
        val visibleBottom = min(pageHeight.toFloat(), scrollYf + active.viewportHeight - pageRect.top)
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return

        val size = PageRenderer.TILE_SIZE_PX
        val firstX = (visibleLeft / size).toInt()
        val lastX = ((visibleRight - 1) / size).toInt()
        val firstY = (visibleTop / size).toInt()
        val lastY = ((visibleBottom - 1) / size).toInt()
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                render.requestTile(page, tx, ty, pageWidth, pageHeight, distance * PRIORITY_STEP + 2)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val active = layout ?: return
        val store = cache ?: return
        val range = active.visiblePages(scrollXf, scrollYf)
        if (range.isEmpty()) return

        awaitingSharpen = false
        for (page in range) {
            active.pageRect(page, pageRect)
            destRect.set(
                pageRect.left - scrollXf,
                pageRect.top - scrollYf,
                pageRect.right - scrollXf,
                pageRect.bottom - scrollYf,
            )
            drawPage(canvas, store, active, page, destRect)
        }

        drawTextSelection(canvas)

        // The eraser's reach, shown where the finger is so its size is never a guess.
        if (drawMode && erasing && eraserShowing) {
            eraserPaint.strokeWidth = 2f * resources.displayMetrics.density
            canvas.drawCircle(eraserX, eraserY, eraserScreenRadius(), eraserPaint)
        }

        // A sharper version is on its way but motion has stopped: nudge the queue once.
        if (awaitingSharpen && !touchActive && !scaling && scroller.isFinished) {
            requestRenders(fullDetail = true)
        }
    }

    private fun drawPage(canvas: Canvas, store: RenderCache, active: PdfLayout, page: Int, dest: RectF) {
        canvas.drawRect(dest, pageFillPaint)

        val pageWidth = active.pageWidth(page).roundToInt()
        val pageHeight = active.pageHeight(page).roundToInt()

        val rendered = store.page(page)
        var drewSomething = false

        if (rendered != null) {
            canvas.drawBitmap(rendered.bitmap, null, dest, bitmapPaint)
            drewSomething = true
            if (rendered.pageWidthPx < pageWidth * SHARPNESS_TOLERANCE) awaitingSharpen = true
        } else {
            val thumb = store.thumb(page)
            if (thumb != null) {
                canvas.drawBitmap(thumb, null, dest, bitmapPaint)
                drewSomething = true
            }
            awaitingSharpen = true
        }

        // Tiles sharpen whatever is underneath once zoomed past a single-bitmap page.
        if (pageWidth > 0 && pageHeight > 0 && renderer?.tilesNeededFor(pageWidth, pageHeight) == true) {
            drawTiles(canvas, store, page, pageWidth, pageHeight, dest)
        }

        drawMarks(canvas, page, dest)
        drawHighlights(canvas, page, dest)

        if (drewSomething || rendered != null) {
            canvas.drawRect(dest, pageEdgePaint)
        }

        if (active.isPaged && turnInMotion) drawTurnShadows(canvas, dest)

        if (selectionMode && page in selection) {
            canvas.drawRect(dest, selectionFillPaint)
            selectionEdgePaint.strokeWidth = SELECTION_STROKE_DP * resources.displayMetrics.density
            canvas.drawRect(dest, selectionEdgePaint)
        }
    }

    /**
     * Screen point to page coordinates: points, origin bottom-left, the space pdfium talks in.
     *
     * Returns null when the page is not laid out, so callers can simply give up rather than act on
     * a nonsense coordinate.
     */
    fun screenToPagePoint(page: Int, x: Float, y: Float): PointF? {
        val active = layout ?: return null
        val pages = geometry ?: return null
        if (page !in 0 until active.pageCount) return null
        active.pageRect(page, pageRect)
        val widthPts = max(1f, pages.widthPts(page))
        val heightPts = max(1f, pages.heightPts(page))
        val scaleX = pageRect.width() / widthPts
        val scaleY = pageRect.height() / heightPts
        if (scaleX <= 0f || scaleY <= 0f) return null
        return PointF(
            (x + scrollXf - pageRect.left) / scaleX,
            heightPts - (y + scrollYf - pageRect.top) / scaleY,
        )
    }

    /** Page coordinates back to the screen. The inverse of [screenToPagePoint]. */
    private fun pagePointToScreen(page: Int, xPts: Float, yPts: Float, out: PointF): Boolean {
        val active = layout ?: return false
        val pages = geometry ?: return false
        if (page !in 0 until active.pageCount) return false
        active.pageRect(page, pageRect)
        val widthPts = max(1f, pages.widthPts(page))
        val heightPts = max(1f, pages.heightPts(page))
        val scaleX = pageRect.width() / widthPts
        val scaleY = pageRect.height() / heightPts
        out.set(
            pageRect.left - scrollXf + xPts * scaleX,
            pageRect.top - scrollYf + (heightPts - yPts) * scaleY,
        )
        return true
    }

    /**
     * Paint the selected text and the two handles that resize it.
     *
     * The handles are drawn below the text, teardrop-side down, so a thumb resting on one does not
     * cover the very characters being aimed at.
     */
    private fun drawTextSelection(canvas: Canvas) {
        val page = textSelectionPage
        if (page < 0 || textSelectionRects.isEmpty()) return
        val pages = geometry ?: return
        val active = layout ?: return
        if (page !in 0 until active.pageCount) return
        active.pageRect(page, pageRect)
        val widthPts = max(1f, pages.widthPts(page))
        val heightPts = max(1f, pages.heightPts(page))
        val scaleX = pageRect.width() / widthPts
        val scaleY = pageRect.height() / heightPts
        val left = pageRect.left - scrollXf
        val top = pageRect.top - scrollYf

        var first: RectF? = null
        var last: RectF? = null
        textSelectionRects.forEach { rect ->
            val topPts = max(rect.top, rect.bottom)
            val bottomPts = min(rect.top, rect.bottom)
            highlightRect.set(
                left + min(rect.left, rect.right) * scaleX,
                top + (heightPts - topPts) * scaleY,
                left + max(rect.left, rect.right) * scaleX,
                top + (heightPts - bottomPts) * scaleY,
            )
            canvas.drawRect(highlightRect, textSelectionPaint)
            if (first == null) first = RectF(highlightRect)
            last = RectF(highlightRect)
        }

        val head = first ?: return
        val tail = last ?: return
        textHandleStart.set(head.left, head.bottom)
        textHandleEnd.set(tail.right, tail.bottom)
        drawHandle(canvas, textHandleStart)
        drawHandle(canvas, textHandleEnd)
    }

    private fun drawHandle(canvas: Canvas, at: PointF) {
        val radius = HANDLE_RADIUS_DP * resources.displayMetrics.density
        canvas.drawLine(at.x, at.y - radius, at.x, at.y, textHandlePaint)
        canvas.drawCircle(at.x, at.y + radius, radius, textHandlePaint)
    }

    /** Which handle a touch has landed on: -1 for the start, 1 for the end, 0 for neither. */
    private fun handleAt(x: Float, y: Float): Int {
        if (textSelectionRects.isEmpty()) return 0
        val reach = HANDLE_TOUCH_DP * resources.displayMetrics.density
        val toStart = distance(x, y, textHandleStart)
        val toEnd = distance(x, y, textHandleEnd)
        return when {
            toStart <= reach && toStart <= toEnd -> -1
            toEnd <= reach -> 1
            else -> 0
        }
    }

    private fun distance(x: Float, y: Float, point: PointF): Float {
        val dx = x - point.x
        val dy = y - point.y - HANDLE_RADIUS_DP * resources.displayMetrics.density
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun eraserScreenRadius(): Float {
        val active = layout ?: return brushWidthPts
        val pages = geometry ?: return brushWidthPts
        val page = strokePage.takeIf { it >= 0 } ?: pageAt(eraserX, eraserY) ?: return brushWidthPts
        val scale = active.pageWidth(page) / max(1f, pages.widthPts(page))
        return max(ERASER_MIN_DP * resources.displayMetrics.density, brushWidthPts * scale)
    }

    /** True while a paged view is actually moving sideways, which is when the shadows belong. */
    private val turnInMotion: Boolean
        get() = (touchActive || !scroller.isFinished) && !scaling

    /**
     * The book feel: a soft shadow down each page edge while a page is turning.
     *
     * Two gradients, built once and moved with a matrix rather than rebuilt per frame — this runs
     * inside the draw of every visible page, and allocating a shader there would show up as jank
     * on exactly the gesture it is meant to flatter. The pair reads as a crease between two
     * sheets: the edge coming in is dark against the one going out.
     */
    private fun drawTurnShadows(canvas: Canvas, dest: RectF) {
        val span = TURN_SHADOW_DP * resources.displayMetrics.density
        if (leftEdgeShadow == null) {
            leftEdgeShadow = LinearGradient(
                0f, 0f, span, 0f,
                TURN_SHADOW_COLOUR, 0x00000000, Shader.TileMode.CLAMP,
            )
            rightEdgeShadow = LinearGradient(
                0f, 0f, span, 0f,
                0x00000000, TURN_SHADOW_COLOUR, Shader.TileMode.CLAMP,
            )
        }
        leftEdgeShadow?.let { shader ->
            turnShadowMatrix.setTranslate(dest.left, 0f)
            shader.setLocalMatrix(turnShadowMatrix)
            turnShadowPaint.shader = shader
            canvas.drawRect(dest.left, dest.top, dest.left + span, dest.bottom, turnShadowPaint)
        }
        rightEdgeShadow?.let { shader ->
            turnShadowMatrix.setTranslate(dest.right - span, 0f)
            shader.setLocalMatrix(turnShadowMatrix)
            turnShadowPaint.shader = shader
            canvas.drawRect(dest.right - span, dest.top, dest.right, dest.bottom, turnShadowPaint)
        }
        turnShadowPaint.shader = null
    }

    /**
     * Paint search matches over the page.
     *
     * pdfium reports text rectangles in PDF space — points, origin bottom-left, y increasing
     * upwards — so the vertical axis has to be flipped against the page height before they line up
     * with anything on screen.
     */
    private fun drawHighlights(canvas: Canvas, page: Int, dest: RectF) {
        val rects = highlights[page] ?: return
        val pages = geometry ?: return
        val pageWidthPts = max(1f, pages.widthPts(page))
        val pageHeightPts = max(1f, pages.heightPts(page))
        val scaleX = dest.width() / pageWidthPts
        val scaleY = dest.height() / pageHeightPts
        val active = activeHighlight

        rects.forEachIndexed { index, rect ->
            val topPts = max(rect.top, rect.bottom)
            val bottomPts = min(rect.top, rect.bottom)
            highlightRect.set(
                dest.left + min(rect.left, rect.right) * scaleX,
                dest.top + (pageHeightPts - topPts) * scaleY,
                dest.left + max(rect.left, rect.right) * scaleX,
                dest.top + (pageHeightPts - bottomPts) * scaleY,
            )
            val isActive = active != null && active.first == page && active.second == index
            canvas.drawRect(highlightRect, if (isActive) activeHighlightPaint else highlightPaint)
        }
    }

    private fun drawTiles(
        canvas: Canvas,
        store: RenderCache,
        page: Int,
        pageWidth: Int,
        pageHeight: Int,
        dest: RectF,
    ) {
        val size = PageRenderer.TILE_SIZE_PX
        val scaleX = dest.width() / pageWidth
        val scaleY = dest.height() / pageHeight
        val firstX = (max(0f, -dest.left) / size).toInt()
        val lastX = ((min(pageWidth.toFloat(), width - dest.left) - 1) / size).toInt()
        val firstY = (max(0f, -dest.top) / size).toInt()
        val lastY = ((min(pageHeight.toFloat(), height - dest.top) - 1) / size).toInt()
        if (lastX < firstX || lastY < firstY) return

        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                val tile = store.tile(TileKey(page, tx, ty, pageWidth)) ?: continue
                val left = dest.left + tx * size * scaleX
                val top = dest.top + ty * size * scaleY
                tileDest.set(
                    left,
                    top,
                    left + tile.bitmap.width * scaleX,
                    top + tile.bitmap.height * scaleY,
                )
                srcRect.set(0, 0, tile.bitmap.width, tile.bitmap.height)
                canvas.drawBitmap(tile.bitmap, srcRect, tileDest, bitmapPaint)
            }
        }
    }

    private fun applyColorMode() {
        val matrix = when (colorMode) {
            ColorMode.NORMAL -> null
            ColorMode.NIGHT -> ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            ColorMode.SEPIA -> ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            ColorMode.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
        }
        bitmapPaint.colorFilter = matrix?.let { ColorMatrixColorFilter(it) }
        // The paper itself has to follow the pages, or inverted text sits on a white sheet.
        pageFillPaint.color = when (colorMode) {
            ColorMode.NIGHT -> Color.BLACK
            ColorMode.SEPIA -> SEPIA_PAPER_COLOUR
            else -> Color.WHITE
        }
        setBackgroundColor(if (colorMode == ColorMode.NIGHT) Color.BLACK else BACKDROP_COLOUR)
    }

    private fun captureAnchor(): Anchor? {
        val active = layout ?: return null
        if (active.viewportWidth <= 0 || active.pageCount <= 0) return null
        val page = active.pageAtViewportCentre(scrollXf, scrollYf)
        active.pageRect(page, pageRect)
        if (pageRect.width() <= 0f || pageRect.height() <= 0f) return null
        val centreX = scrollXf + active.viewportWidth / 2f
        val centreY = scrollYf + active.viewportHeight / 2f
        return Anchor(
            page,
            (centreX - pageRect.left) / pageRect.width(),
            (centreY - pageRect.top) / pageRect.height(),
        )
    }

    private fun restoreAnchor(anchor: Anchor?) {
        val active = layout ?: return
        if (anchor == null) {
            clampScroll()
            return
        }
        active.pageRect(anchor.page, pageRect)
        scrollXf = pageRect.left + anchor.fractionX * pageRect.width() - active.viewportWidth / 2f
        scrollYf = pageRect.top + anchor.fractionY * pageRect.height() - active.viewportHeight / 2f
        clampScroll()
    }

    /** Reading position, stable across zoom, rotation and view-mode changes. */
    private data class Anchor(val page: Int, val fractionX: Float, val fractionY: Float)

    /** Snapshot for saving to the database, so reopening lands exactly where you left off. */
    fun readingPosition(): FloatArray {
        val active = layout ?: return floatArrayOf(0f, 0f, 0f, 1f)
        val anchor = captureAnchor() ?: return floatArrayOf(0f, 0f, 0f, active.zoom)
        return floatArrayOf(anchor.page.toFloat(), anchor.fractionX, anchor.fractionY, active.zoom)
    }

    fun restoreReadingPosition(position: FloatArray) {
        val active = layout ?: return
        if (position.size < 4) return
        active.zoom = position[3].coerceIn(MIN_ZOOM, MAX_ZOOM)
        renderer?.invalidateScale()
        restoreAnchor(Anchor(position[0].toInt().coerceIn(0, max(0, active.pageCount - 1)), position[1], position[2]))
        requestRenders(fullDetail = true)
        invalidate()
    }

    companion object {
        /** Zoom 1.0 is "fitted"; going below it shows more than one page at a time. */
        private const val FIT_ZOOM = 1f
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 8f
        private const val DOUBLE_TAP_ZOOM = 1.5f
        private const val ZOOM_BUTTON_STEP = 1.25f
        private const val PAGE_GAP_DP = 8f
        private const val BACKDROP_COLOUR = 0xFF303338.toInt()
        private const val SEPIA_PAPER_COLOUR = 0xFFF4ECD8.toInt()
        private const val ZOOM_ANIMATION_MS = 220L
        private const val SNAP_ANIMATION_MS = 180

        /** How long a page takes to finish turning. Slow enough to read as a sheet, not a jump. */
        private const val PAGE_TURN_MS = 300

        /** How far a page has to be dragged before letting go completes the turn. */
        private const val TURN_COMMIT_FRACTION = 0.32f

        /** Width of the shadow along a page edge during a turn. */
        private const val TURN_SHADOW_DP = 14f
        private const val TURN_SHADOW_COLOUR = 0x4D000000
        private const val SCROLL_ANIMATION_MS = 260

        /** Screens of look-ahead to render once motion settles. */
        private const val PREFETCH_SCREENS = 0.75f

        /** Look-ahead for previews while the page is still moving. */
        private const val SCROLL_LOOKAHEAD_SCREENS = 0.5f

        /** Priority spacing between pages, leaving room for thumb/page/tile ordering within a page. */
        private const val PRIORITY_STEP = 10

        /** A page bitmap narrower than this fraction of the layout size counts as too soft. */
        private const val SHARPNESS_TOLERANCE = 0.9f

        /** How far a point must be from the last kept one to be worth keeping, as a fraction
         *  of the brush width. */
        private const val SIMPLIFY_FRACTION = 0.25f

        /** However fine the brush, the eraser stays big enough to aim with a finger. */
        private const val ERASER_MIN_DP = 14f

        private const val HANDLE_RADIUS_DP = 8f
        private const val HANDLE_TOUCH_DP = 30f

        /** How far down the screen a jumped-to search match lands. */
        private const val MATCH_SCREEN_FRACTION = 0.3f

        private const val SELECTION_STROKE_DP = 2.5f

        /** How much of the screen a page is fitted into on the crop screen. */
        private const val CROP_FIT_WIDTH = 0.88f
        private const val CROP_FIT_HEIGHT = 0.70f
    }
}
