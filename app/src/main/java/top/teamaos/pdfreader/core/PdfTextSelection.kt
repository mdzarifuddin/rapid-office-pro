package top.teamaos.pdfreader.core

import android.graphics.RectF

/**
 * A stretch of selected text on one page.
 *
 * [rects] are in the page's own coordinate space — points, origin bottom-left — the same as search
 * highlights, because the view is the only thing that knows the current zoom and scroll.
 */
data class TextSelection(
    val page: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val rects: List<RectF>,
) {
    val isEmpty: Boolean get() = text.isBlank() || rects.isEmpty()
}

/**
 * Reading the text layer under a finger: which character is there, and which word it belongs to.
 *
 * Every call opens and closes its own text page, so this is safe to run off the render thread —
 * which matters, because a long press has to answer within a frame or two and the render thread
 * may well be busy with a page.
 *
 * A scan with no text layer answers nothing here. There is no OCR.
 */
class PdfTextSelector(private val session: PdfSession) {

    /** Select the word under a point given in page coordinates, or null if there is no text there. */
    fun wordAt(page: Int, xPts: Double, yPts: Double): TextSelection? =
        session.withTextPage(page) { textPage ->
            runCatching {
                val total = textPage.textPageCountChars()
                if (total <= 0) return@runCatching null
                val hit = textPage.textPageGetCharIndexAtPos(xPts, yPts, TOUCH_TOLERANCE, TOUCH_TOLERANCE)
                if (hit < 0 || hit >= total) return@runCatching null

                var start = hit
                var end = hit
                if (isWordCharacter(textPage.textPageGetUnicode(hit))) {
                    while (start > 0 && isWordCharacter(textPage.textPageGetUnicode(start - 1))) start--
                    while (end + 1 < total && isWordCharacter(textPage.textPageGetUnicode(end + 1))) end++
                }
                build(page, start, end, textPage)
            }.getOrNull()
        }

    /** Rebuild a selection between two character indices, in either order. */
    fun between(page: Int, first: Int, second: Int): TextSelection? =
        session.withTextPage(page) { textPage ->
            runCatching {
                val total = textPage.textPageCountChars()
                if (total <= 0) return@runCatching null
                val start = minOf(first, second).coerceIn(0, total - 1)
                val end = maxOf(first, second).coerceIn(0, total - 1)
                build(page, start, end, textPage)
            }.getOrNull()
        }

    /** The whole page's text, for "select all". */
    fun wholePage(page: Int): TextSelection? =
        session.withTextPage(page) { textPage ->
            runCatching {
                val total = textPage.textPageCountChars()
                if (total <= 0) null else build(page, 0, total - 1, textPage)
            }.getOrNull()
        }

    /**
     * The character nearest a point, for dragging a selection handle.
     *
     * Unlike [wordAt] this never gives up: a handle dragged into a margin, or past the last line,
     * should still take the selection to the nearest character rather than freezing. pdfium's own
     * hit test is tried first with a generous tolerance, and the fallback walks the characters and
     * keeps the closest — linear, but only over one page, and only while a handle is moving.
     */
    fun nearestCharacter(page: Int, xPts: Double, yPts: Double): Int? =
        session.withTextPage(page) { textPage ->
            runCatching {
                val total = textPage.textPageCountChars()
                if (total <= 0) return@runCatching null
                val direct = textPage.textPageGetCharIndexAtPos(xPts, yPts, DRAG_TOLERANCE, DRAG_TOLERANCE)
                if (direct in 0 until total) return@runCatching direct

                var best = -1
                var bestDistance = Float.MAX_VALUE
                for (index in 0 until total) {
                    val box = textPage.textPageGetCharBox(index) ?: continue
                    if (box.width() <= 0f && box.height() <= 0f) continue
                    val dx = (xPts.toFloat() - box.centerX())
                    // Vertical distance counts for more: lines are what a finger is really picking.
                    val dy = (yPts.toFloat() - box.centerY()) * LINE_WEIGHT
                    val distance = dx * dx + dy * dy
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = index
                    }
                }
                if (best >= 0) best else null
            }.getOrNull()
        }

    private fun build(
        page: Int,
        start: Int,
        end: Int,
        textPage: io.legere.pdfiumandroid.PdfTextPage,
    ): TextSelection? {
        val count = end - start + 1
        if (count <= 0) return null
        val text = runCatching { textPage.textPageGetText(start, count) }.getOrNull().orEmpty()
        val rects = runCatching {
            val rectCount = textPage.textPageCountRects(start, count)
            (0 until rectCount).mapNotNull { textPage.textPageGetRect(it) }
        }.getOrDefault(emptyList())
        if (text.isBlank() && rects.isEmpty()) return null
        return TextSelection(page, start, end, text, rects)
    }

    private fun isWordCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '\'' || character == '-' || character == '_'

    private companion object {
        /** How far from a character a tap still counts, in points. About 2 mm. */
        const val TOUCH_TOLERANCE = 6.0
        const val DRAG_TOLERANCE = 14.0

        /** Sideways slop is cheap; jumping a line is not. */
        const val LINE_WEIGHT = 2.5f
    }
}
