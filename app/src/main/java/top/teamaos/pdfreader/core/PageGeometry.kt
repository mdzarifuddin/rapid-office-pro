package top.teamaos.pdfreader.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Every page's size, in PostScript points, plus the running vertical sum used to lay pages out.
 *
 * Measuring a 10 000-page document up front is exactly the stall this app exists to avoid, so the
 * geometry starts as a guess — page 1's size copied across every page — and is corrected in
 * batches by [PageMeasurer] as they are actually measured. Sizes are kept in points rather than
 * pixels so zooming never invalidates them; the layout multiplies by the current scale instead.
 *
 * Not thread-safe by design: all mutation and reading happens on the main thread, and the
 * background measurer hands its results over in batches.
 */
class PageGeometry(val pageCount: Int) {

    private val widthPts = FloatArray(pageCount)
    private val heightPts = FloatArray(pageCount)
    private val measured = BooleanArray(pageCount)

    /** `cumulativeTopPts[i]` is the summed height of pages before `i`; index [pageCount] is the total. */
    private val cumulativeTopPts = FloatArray(pageCount + 1)

    var maxWidthPts: Float = DEFAULT_WIDTH_PTS
        private set

    var maxHeightPts: Float = DEFAULT_HEIGHT_PTS
        private set

    var measuredCount: Int = 0
        private set

    /** Bumped whenever the layout changed, so views know to recompute anchors. */
    var version: Int = 0
        private set

    val isFullyMeasured: Boolean get() = measuredCount == pageCount

    init {
        seed(DEFAULT_WIDTH_PTS, DEFAULT_HEIGHT_PTS)
    }

    /** Fill every not-yet-measured page with a guess, normally page 1's real size. */
    fun seed(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        for (i in 0 until pageCount) {
            if (!measured[i]) {
                widthPts[i] = width
                heightPts[i] = height
            }
        }
        rebuild()
    }

    /** Record one page's true size. Call [rebuild] once after a batch of these. */
    fun setMeasured(index: Int, width: Float, height: Float) {
        if (index !in 0 until pageCount || width <= 0f || height <= 0f) return
        if (!measured[index]) {
            measured[index] = true
            measuredCount++
        }
        widthPts[index] = width
        heightPts[index] = height
    }

    fun isMeasured(index: Int): Boolean = index in 0 until pageCount && measured[index]

    /** Recompute the running sums. O(pageCount), so call it per batch and not per page. */
    fun rebuild() {
        var running = 0f
        var widest = 1f
        var tallest = 1f
        for (i in 0 until pageCount) {
            cumulativeTopPts[i] = running
            running += heightPts[i]
            if (widthPts[i] > widest) widest = widthPts[i]
            if (heightPts[i] > tallest) tallest = heightPts[i]
        }
        cumulativeTopPts[pageCount] = running
        maxWidthPts = widest
        maxHeightPts = tallest
        version++
    }

    fun widthPts(index: Int): Float = widthPts[index.coerceIn(0, pageCount - 1)]

    fun heightPts(index: Int): Float = heightPts[index.coerceIn(0, pageCount - 1)]

    /** Summed height of all pages before [index], ignoring inter-page gaps. */
    fun topPts(index: Int): Float = cumulativeTopPts[index.coerceIn(0, pageCount)]

    val totalHeightPts: Float get() = cumulativeTopPts[pageCount]

    /**
     * Largest page index whose [topPts] is at or below [target]. Binary search, so finding the
     * visible range stays O(log n) no matter how many pages the document has.
     */
    fun pageAtCumulativeTop(target: Float): Int {
        var low = 0
        var high = pageCount - 1
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cumulativeTopPts[mid] <= target) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /** Pack the measured sizes for the on-disk cache, so reopening a document skips measuring. */
    fun toBlob(): ByteArray? {
        if (measuredCount != pageCount) return null
        val buffer = ByteBuffer.allocate(pageCount * 2 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until pageCount) {
            buffer.putFloat(widthPts[i])
            buffer.putFloat(heightPts[i])
        }
        return buffer.array()
    }

    /** Restore sizes cached by [toBlob]. Returns false if the blob does not match this document. */
    fun loadBlob(blob: ByteArray): Boolean {
        if (blob.size != pageCount * 2 * Float.SIZE_BYTES) return false
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until pageCount) {
            val w = buffer.float
            val h = buffer.float
            if (w <= 0f || h <= 0f) return false
            widthPts[i] = w
            heightPts[i] = h
            measured[i] = true
        }
        measuredCount = pageCount
        rebuild()
        return true
    }

    companion object {
        /** US Letter, used only until page 1 has been measured a few milliseconds after opening. */
        const val DEFAULT_WIDTH_PTS = 612f
        const val DEFAULT_HEIGHT_PTS = 792f
    }
}
