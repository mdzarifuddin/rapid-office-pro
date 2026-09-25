package top.teamaos.pdfreader.core

import android.os.Handler
import android.os.Looper
import kotlin.math.min

/**
 * Fills in the true page sizes in the background, after the document is already on screen.
 *
 * Measuring every page of a long document costs pdfium a page-dictionary load each time — the very
 * stall this app avoids by opening with page 1's size copied across the whole document.
 *
 * The work runs **on the render thread's own queue, at the lowest possible priority**, rather than
 * on a separate coroutine. That matters more than it sounds: pdfium serialises every native call
 * behind one global lock, so a measuring loop on another thread does not run *alongside* rendering,
 * it runs *instead of* it. An earlier version did exactly that and drove scrolling from under 2%
 * dropped frames to over 90%. Queued here, a page the reader is waiting for always jumps ahead of
 * measuring, and the sweep simply fills the gaps.
 *
 * [prioritise] lets the view jump the queue for pages the reader is actually looking at, so a jump
 * to page 8000 corrects itself immediately instead of after the sweep gets there.
 */
class PageMeasurer(
    private val session: PdfSession,
    private val geometry: PageGeometry,
    private val scheduler: RenderScheduler,
) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var sweepIndex = 0

    @Volatile
    private var priorityRange: IntRange? = null

    private var onBatch: ((measured: Int, total: Int) -> Unit)? = null

    /** Begin the sweep. Safe to call when the geometry is already complete; it does nothing. */
    fun start(onBatch: (measured: Int, total: Int) -> Unit) {
        if (running || geometry.isFullyMeasured) return
        this.onBatch = onBatch
        running = true
        sweepIndex = 0
        queueNextChunk()
    }

    fun stop() {
        running = false
        onBatch = null
    }

    /** Ask for these pages to be measured before the sweep continues. Safe from the main thread. */
    fun prioritise(range: IntRange) {
        if (!running || range.isEmpty()) return
        for (page in range) {
            if (!geometry.isMeasured(page)) {
                priorityRange = range
                return
            }
        }
    }

    private fun queueNextChunk() {
        if (!running || session.isClosed) return
        scheduler.submit(QUEUE_KEY, Int.MAX_VALUE) { measureOneChunk() }
    }

    /** Runs on the render thread, only when nothing more urgent is waiting. */
    private fun measureOneChunk() {
        if (!running || session.isClosed) return
        val total = session.pageCount

        // Serve whatever the reader is looking at before carrying on down the document.
        val urgent = priorityRange?.also { priorityRange = null }
        val pages: List<Int> = if (urgent != null) {
            urgent.filter { it in 0 until total && !geometry.isMeasured(it) }.take(CHUNK_SIZE)
        } else {
            var start = sweepIndex
            var collected = emptyList<Int>()
            // Skip over stretches already covered by the cache or by earlier priority requests.
            while (start < total && collected.isEmpty()) {
                val end = min(start + CHUNK_SIZE, total)
                collected = (start until end).filter { !geometry.isMeasured(it) }
                start = end
            }
            sweepIndex = start
            collected
        }

        if (pages.isEmpty() && sweepIndex >= total) {
            running = false
            main.post { onBatch?.invoke(geometry.measuredCount, total) }
            return
        }

        val widths = FloatArray(pages.size)
        val heights = FloatArray(pages.size)
        pages.forEachIndexed { slot, page ->
            val size = session.pageSizePoints(page)
            widths[slot] = size?.getOrNull(0) ?: 0f
            heights[slot] = size?.getOrNull(1) ?: 0f
        }

        main.post {
            var changed = false
            pages.forEachIndexed { slot, page ->
                if (widths[slot] > 0f && heights[slot] > 0f) {
                    geometry.setMeasured(page, widths[slot], heights[slot])
                    changed = true
                }
            }
            if (changed) {
                geometry.rebuild()
                onBatch?.invoke(geometry.measuredCount, session.pageCount)
            }
            queueNextChunk()
        }
    }

    private companion object {
        /**
         * Small on purpose. Each chunk holds pdfium's lock for its whole duration, so this is the
         * longest a page render can be made to wait behind measuring.
         */
        const val CHUNK_SIZE = 12

        /** One key for the whole sweep: only ever one measuring job queued at a time. */
        const val QUEUE_KEY = "measure-pages"
    }
}
