package top.teamaos.pdfreader.core

import android.graphics.RectF
import io.legere.pdfiumandroid.api.FindFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** One match: where it is and how long it is, in the page's character stream. */
data class SearchHit(val page: Int, val charIndex: Int, val charCount: Int)

/**
 * Full-text search across a document, using the text layer pdfium already has.
 *
 * Searching a 3000-page document is exactly the kind of thing that freezes other readers, so this
 * never gathers everything before showing anything: pages are searched one at a time on a
 * background thread and hits are reported as they are found, so the first result appears almost
 * immediately and the reader can jump to it while the rest is still running.
 *
 * The sweep starts at the page being read and wraps around, so the first hits offered are the ones
 * nearest to where you already are.
 *
 * Documents with no text layer — plain scans — simply return nothing. There is no OCR here.
 */
class PdfSearch(private val session: PdfSession) {

    /**
     * Search for [query], reporting hits through [onHit] on the calling context and progress
     * through [onProgress]. Returns when the whole document has been covered, or when the
     * surrounding coroutine is cancelled.
     */
    suspend fun run(
        query: String,
        startPage: Int,
        matchCase: Boolean = false,
        onHit: suspend (SearchHit) -> Unit,
        onProgress: suspend (searched: Int, total: Int) -> Unit,
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val total = session.pageCount
        val flags = if (matchCase) setOf(FindFlags.MatchCase) else emptySet()

        for (offset in 0 until total) {
            coroutineContext.ensureActive()
            if (session.isClosed) return
            val page = (startPage + offset) % total

            val hits = withContext(Dispatchers.IO) { hitsOnPage(page, trimmed, flags) }
            hits.forEach { onHit(it) }
            onProgress(offset + 1, total)
        }
    }

    private fun hitsOnPage(page: Int, query: String, flags: Set<FindFlags>): List<SearchHit> =
        session.withTextPage(page) { textPage ->
            val found = mutableListOf<SearchHit>()
            runCatching {
                textPage.findStart(query, flags, 0)?.use { finder ->
                    while (finder.findNext()) {
                        val index = finder.getSchResultIndex()
                        val count = finder.getSchCount()
                        if (count <= 0) break
                        found += SearchHit(page, index, count)
                        if (found.size >= MAX_HITS_PER_PAGE) break
                    }
                }
            }
            found.toList()
        } ?: emptyList()

    /**
     * Rectangles covering [hit], in the page's own coordinate space (points, origin bottom-left).
     * The view converts these to screen space, since it is the one that knows the current zoom.
     */
    fun rectsFor(hit: SearchHit): List<RectF> =
        session.withTextPage(hit.page) { textPage ->
            runCatching {
                val count = textPage.textPageCountRects(hit.charIndex, hit.charCount)
                (0 until count).mapNotNull { index -> textPage.textPageGetRect(index) }
            }.getOrDefault(emptyList())
        } ?: emptyList()

    private companion object {
        /** A page with more matches than this is noise; the reader will not scroll through them. */
        const val MAX_HITS_PER_PAGE = 200
    }
}
