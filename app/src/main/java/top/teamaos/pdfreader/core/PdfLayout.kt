package top.teamaos.pdfreader.core

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ViewMode {
    /** One long scroll, every page fitted to the viewport width. The reading default. */
    VERTICAL,

    /** One page per screen, swiped sideways. */
    HORIZONTAL,

    /** Two pages per screen, swiped sideways. Used automatically in landscape. */
    DUAL,
}

/**
 * Turns page sizes in points into on-screen rectangles.
 *
 * Positions are derived arithmetically from [PageGeometry]'s running height sums rather than stored
 * per page, so changing the zoom costs nothing and a 10 000-page document needs no more state than
 * a two-page one. Finding the visible range is a binary search over a monotonic function, so
 * scrolling stays O(log n).
 *
 * A single uniform scale is used across the document — derived from the widest page — which keeps
 * the running-sum trick valid. Documents whose pages are all the same size, which is nearly all of
 * them, are unaffected.
 */
class PdfLayout(private val geometry: PageGeometry) {

    var mode: ViewMode = ViewMode.VERTICAL
    var viewportWidth: Int = 0
    var viewportHeight: Int = 0

    /** 1.0 means "fitted": full width in [ViewMode.VERTICAL], the whole page in the paged modes. */
    var zoom: Float = 1f

    /** Gap between pages, in pixels. Constant on screen, so it does not scale with zoom. */
    var gapPx: Float = 0f

    val pageCount: Int get() = geometry.pageCount

    private val maxWidthPts: Float get() = max(1f, geometry.maxWidthPts)
    private val maxHeightPts: Float get() = max(1f, geometry.maxHeightPts)

    /** Scale that makes zoom 1.0 mean "fitted". */
    val baseScale: Float
        get() {
            if (viewportWidth <= 0 || viewportHeight <= 0) return 1f
            return when (mode) {
                ViewMode.VERTICAL -> viewportWidth / maxWidthPts
                ViewMode.HORIZONTAL -> min(viewportWidth / maxWidthPts, viewportHeight / maxHeightPts)
                ViewMode.DUAL -> min(
                    (viewportWidth - gapPx) / (2f * maxWidthPts),
                    viewportHeight / maxHeightPts,
                )
            }
        }

    val scale: Float get() = baseScale * zoom

    val isPaged: Boolean get() = mode != ViewMode.VERTICAL

    /** Pages per horizontal slot: 1 in [ViewMode.HORIZONTAL], 2 in [ViewMode.DUAL]. */
    private val pagesPerSlot: Int get() = if (mode == ViewMode.DUAL) 2 else 1

    val slotCount: Int get() = ceil(pageCount.toFloat() / pagesPerSlot).toInt().coerceAtLeast(1)

    private val slotWidth: Float
        get() = if (mode == ViewMode.DUAL) 2f * maxWidthPts * scale + gapPx else maxWidthPts * scale

    /**
     * Distance between the left edges of two consecutive slots — one page turn's worth.
     *
     * Never less than the viewport. A spread is scaled to fit both the width and the height of the
     * screen, so turning a phone sideways makes each page shorter and therefore narrower, and
     * without this the freed-up width simply showed the next spread alongside: two pages in
     * portrait became four in landscape. Holding the stride to the screen keeps one spread per
     * screen at any shape, and centres it in the space it has.
     */
    val slotStride: Float get() = max(slotWidth + gapPx, viewportWidth.toFloat())

    /** Left edge of a slot's own content, centred inside its stride. */
    private fun slotContentLeft(slot: Int): Float = slot * slotStride + (slotStride - slotWidth) / 2f

    fun pageWidth(index: Int): Float = geometry.widthPts(index) * scale

    fun pageHeight(index: Int): Float = geometry.heightPts(index) * scale

    val contentWidth: Float
        get() = when (mode) {
            ViewMode.VERTICAL -> max(viewportWidth.toFloat(), maxWidthPts * scale)
            else -> slotCount * slotStride
        }

    val contentHeight: Float
        get() = when (mode) {
            ViewMode.VERTICAL -> geometry.totalHeightPts * scale + gapPx * (pageCount + 1)
            else -> max(viewportHeight.toFloat(), maxHeightPts * scale + 2 * gapPx)
        }

    val maxScrollX: Float get() = max(0f, contentWidth - viewportWidth)

    val maxScrollY: Float get() = max(0f, contentHeight - viewportHeight)

    fun slotOf(page: Int): Int = page / pagesPerSlot

    fun firstPageOfSlot(slot: Int): Int = (slot * pagesPerSlot).coerceIn(0, pageCount - 1)

    /** Fill [out] with the page's rectangle in content coordinates. */
    fun pageRect(index: Int, out: RectF): RectF {
        val width = pageWidth(index)
        val height = pageHeight(index)
        when (mode) {
            ViewMode.VERTICAL -> {
                val left = (contentWidth - width) / 2f
                val top = gapPx + geometry.topPts(index) * scale + gapPx * index
                out.set(left, top, left + width, top + height)
            }
            ViewMode.HORIZONTAL -> {
                val slotLeft = slotContentLeft(slotOf(index))
                val left = slotLeft + (slotWidth - width) / 2f
                val top = (contentHeight - height) / 2f
                out.set(left, top, left + width, top + height)
            }
            ViewMode.DUAL -> {
                val slot = slotOf(index)
                val slotLeft = slotContentLeft(slot)
                val halfWidth = maxWidthPts * scale
                // Even pages sit on the left of the spread, odd pages on the right.
                val halfLeft = if (index % 2 == 0) slotLeft else slotLeft + halfWidth + gapPx
                val left = halfLeft + (halfWidth - width) / 2f
                val top = (contentHeight - height) / 2f
                out.set(left, top, left + width, top + height)
            }
        }
        return out
    }

    /** Top edge of a page in content coordinates. Monotonic in [index], which the search relies on. */
    private fun pageTop(index: Int): Float = gapPx + geometry.topPts(index) * scale + gapPx * index

    /**
     * Inclusive range of pages touching the viewport, widened by [overscanPx] so the renderer can
     * work slightly ahead of the scroll without rendering the whole document.
     */
    fun visiblePages(scrollX: Float, scrollY: Float, overscanPx: Float = 0f): IntRange {
        if (pageCount <= 0) return IntRange.EMPTY
        return when (mode) {
            ViewMode.VERTICAL -> {
                val top = scrollY - overscanPx
                val bottom = scrollY + viewportHeight + overscanPx
                val first = searchFirstPageEndingAfter(top)
                var last = first
                while (last + 1 < pageCount && pageTop(last + 1) < bottom) last++
                first..last
            }
            else -> {
                val left = scrollX - overscanPx
                val right = scrollX + viewportWidth + overscanPx
                val stride = slotStride
                val firstSlot = (left / stride).toInt().coerceIn(0, slotCount - 1)
                val lastSlot = (right / stride).toInt().coerceIn(0, slotCount - 1)
                val first = firstPageOfSlot(firstSlot)
                val last = (firstPageOfSlot(lastSlot) + pagesPerSlot - 1).coerceAtMost(pageCount - 1)
                first..last
            }
        }
    }

    /** Binary search for the first page whose bottom edge is below [y]. */
    private fun searchFirstPageEndingAfter(y: Float): Int {
        var low = 0
        var high = pageCount - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pageTop(mid) + pageHeight(mid) >= y) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    /** The page a reader would say they are on: whichever covers the middle of the screen. */
    fun pageAtViewportCentre(scrollX: Float, scrollY: Float): Int {
        if (pageCount <= 0) return 0
        return when (mode) {
            ViewMode.VERTICAL -> {
                val centre = scrollY + viewportHeight / 2f
                searchFirstPageEndingAfter(centre).coerceIn(0, pageCount - 1)
            }
            else -> {
                val centre = scrollX + viewportWidth / 2f
                val slot = (centre / slotStride).toInt().coerceIn(0, slotCount - 1)
                firstPageOfSlot(slot)
            }
        }
    }

    /** Scroll position that puts [page] at the top (or left) of the viewport. */
    fun scrollToPage(page: Int): Pair<Float, Float> {
        val index = page.coerceIn(0, max(0, pageCount - 1))
        return when (mode) {
            ViewMode.VERTICAL -> {
                val y = (pageTop(index) - gapPx).coerceIn(0f, maxScrollY)
                0f to y
            }
            else -> {
                val x = (slotOf(index) * slotStride).coerceIn(0f, maxScrollX)
                x to 0f
            }
        }
    }

    /** In paged modes, the scroll X that settles on a whole page rather than between two. */
    fun snappedScrollX(scrollX: Float): Float {
        if (!isPaged) return scrollX
        val stride = slotStride
        if (stride <= 0f) return scrollX
        val slot = (scrollX / stride).roundToInt().coerceIn(0, slotCount - 1)
        return (slot * stride).coerceIn(0f, maxScrollX)
    }

    /** Zoom that would make [page] exactly fill the viewport width, used by double-tap. */
    fun fitWidthZoom(page: Int): Float {
        val pageWidthPts = max(1f, geometry.widthPts(page))
        val fitted = viewportWidth / pageWidthPts
        return if (baseScale <= 0f) 1f else fitted / baseScale
    }
}
