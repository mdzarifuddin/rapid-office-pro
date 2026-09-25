package top.teamaos.pdfreader.office

import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Turning a parsed document into pages of things to draw.
 *
 * Everything is measured once, in **layout units** — [UNITS_PER_POINT] of them to a point — and
 * never measured again. Zooming and rotating only change the scale the pages are drawn at, so a
 * pinch costs a matrix multiply rather than re-flowing a two-hundred-page document, and the page
 * a reader is on never shifts under them because the text reflowed slightly differently.
 *
 * Measuring at four units per point rather than one is what makes that safe: line breaking at a
 * text size of 11 px rounds coarsely enough to be visible, and at 44 px it does not.
 */
object OfficeLayout {

    const val UNITS_PER_POINT = 4f

    /** What one page turned into: its size and everything on it, all in layout units. */
    class LaidOutPage(
        val width: Float,
        val height: Float,
        val items: List<PageItem>,
        val background: Int,
        /** Which section this page came from, for the contents list and the page label. */
        val sectionIndex: Int,
        val label: String,
    )

    sealed class PageItem {
        /**
         * Part of a paragraph. [firstLine] and [lastLine] let one measured paragraph be spread
         * across a page boundary without measuring it twice — the page draws its slice by
         * translating the layout up and clipping.
         */
        class Text(
            val layout: StaticLayout,
            val x: Float,
            val y: Float,
            val firstLine: Int,
            val lastLine: Int,
        ) : PageItem() {
            val height: Float
                get() = (layout.getLineBottom(lastLine) - layout.getLineTop(firstLine)).toFloat()
        }

        class Image(val data: ByteArray, val bounds: RectF) : PageItem()

        class Box(
            val bounds: RectF,
            val fill: Int,
            val stroke: Int,
            val strokeWidth: Float,
        ) : PageItem()
    }

    class LaidOutDocument(
        val pages: List<LaidOutPage>,
        val widestPage: Float,
        val tallestPage: Float,
    )

    /**
     * Lay [document] out.
     *
     * Runs off the main thread — a long report is thousands of [StaticLayout] builds — and reports
     * how far it has got through [onProgress] so a big file can show something moving.
     */
    fun paginate(
        document: OfficeDocument,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): LaidOutDocument {
        val pages = mutableListOf<LaidOutPage>()
        document.sections.forEachIndexed { index, section ->
            when (section) {
                is Section.Flow -> pages += flowPages(section, index)
                is Section.Grid -> pages += gridPages(section, index)
                is Section.Slide -> pages += slidePage(section, index)
            }
            onProgress?.invoke(index + 1, document.sections.size)
        }
        if (pages.isEmpty()) {
            pages += LaidOutPage(
                Section.LETTER_WIDTH_PT * UNITS_PER_POINT,
                Section.LETTER_HEIGHT_PT * UNITS_PER_POINT,
                emptyList(),
                Color.WHITE,
                0,
                "1",
            )
        }
        return LaidOutDocument(
            pages,
            pages.maxOf { it.width },
            pages.maxOf { it.height },
        )
    }

    // ---------------------------------------------------------------- flowing text

    private fun flowPages(section: Section.Flow, sectionIndex: Int): List<LaidOutPage> {
        val pageWidth = section.pageWidthPt * UNITS_PER_POINT
        val pageHeight = section.pageHeightPt * UNITS_PER_POINT
        val left = section.marginLeftPt * UNITS_PER_POINT
        val top = section.marginTopPt * UNITS_PER_POINT
        val right = pageWidth - section.marginRightPt * UNITS_PER_POINT
        val bottom = pageHeight - section.marginBottomPt * UNITS_PER_POINT
        val contentWidth = max(MIN_CONTENT_UNITS, right - left)

        val pages = mutableListOf<LaidOutPage>()
        var items = mutableListOf<PageItem>()
        var y = top

        fun newPage() {
            pages += LaidOutPage(
                pageWidth,
                pageHeight,
                items,
                Color.WHITE,
                sectionIndex,
                (pages.size + 1).toString(),
            )
            items = mutableListOf()
            y = top
        }

        for (block in section.blocks) {
            when (block) {
                is Block.PageBreak -> if (items.isNotEmpty()) newPage()

                is Block.Text -> {
                    val paragraph = block.paragraph
                    y += paragraph.spaceBeforePt * UNITS_PER_POINT
                    val indent = paragraph.indentPt * UNITS_PER_POINT
                    val width = max(MIN_CONTENT_UNITS, contentWidth - indent)
                    val layout = buildLayout(paragraph, width)
                    var line = 0
                    while (line < layout.lineCount) {
                        val roomLeft = bottom - y
                        // A page with nothing on it yet must take at least one line, or a
                        // paragraph taller than the page would loop for ever.
                        val fits = linesFitting(layout, line, roomLeft, atLeastOne = items.isEmpty())
                        if (fits <= 0) {
                            newPage()
                            continue
                        }
                        val last = line + fits - 1
                        items += PageItem.Text(layout, left + indent, y, line, last)
                        if (paragraph.background != 0) {
                            items.add(
                                0,
                                PageItem.Box(
                                    RectF(
                                        left + indent - PAD,
                                        y - PAD,
                                        left + indent + width + PAD,
                                        y + sliceHeight(layout, line, last) + PAD,
                                    ),
                                    paragraph.background,
                                    0,
                                    0f,
                                ),
                            )
                        }
                        y += sliceHeight(layout, line, last)
                        line = last + 1
                        if (line < layout.lineCount) newPage()
                    }
                    if (paragraph.ruleBelow) {
                        y += 2f * UNITS_PER_POINT
                        items += PageItem.Box(
                            RectF(left, y, right, y + RULE_UNITS),
                            RULE_COLOUR,
                            0,
                            0f,
                        )
                        y += RULE_UNITS
                    }
                    y += paragraph.spaceAfterPt * UNITS_PER_POINT
                }

                is Block.Picture -> {
                    var width = block.widthPt * UNITS_PER_POINT
                    var height = block.heightPt * UNITS_PER_POINT
                    if (width <= 0f || height <= 0f) continue
                    val shrink = min(1f, contentWidth / width)
                    width *= shrink
                    height *= shrink
                    val maxHeight = bottom - top
                    if (height > maxHeight) {
                        val fit = maxHeight / height
                        width *= fit
                        height *= fit
                    }
                    if (y + height > bottom && items.isNotEmpty()) newPage()
                    val x = left + (contentWidth - width) / 2f
                    items += PageItem.Image(block.data, RectF(x, y, x + width, y + height))
                    y += height + PICTURE_GAP
                }

                is Block.Table -> {
                    val table = layOutTable(block, contentWidth)
                    for (row in table) {
                        if (y + row.height > bottom && items.isNotEmpty()) newPage()
                        row.items.forEach { items += it.offsetBy(left, y) }
                        y += row.height
                    }
                    y += TABLE_GAP
                }
            }
        }
        if (items.isNotEmpty() || pages.isEmpty()) newPage()
        return pages
    }

    /**
     * How many lines starting at [from] fit into [room].
     *
     * [atLeastOne] is set when the page is still empty: a paragraph line taller than a whole page
     * has to go somewhere, and refusing it would start a new page for ever.
     */
    private fun linesFitting(layout: StaticLayout, from: Int, room: Float, atLeastOne: Boolean): Int {
        val start = layout.getLineTop(from)
        var count = 0
        while (from + count < layout.lineCount) {
            if (layout.getLineBottom(from + count) - start > room) break
            count++
        }
        return if (count == 0 && atLeastOne) 1 else count
    }

    private fun sliceHeight(layout: StaticLayout, first: Int, last: Int): Float =
        (layout.getLineBottom(last) - layout.getLineTop(first)).toFloat()

    // ---------------------------------------------------------------- tables

    private class LaidOutRow(val items: List<PageItem>, val height: Float)

    private fun PageItem.offsetBy(dx: Float, dy: Float): PageItem = when (this) {
        is PageItem.Text -> PageItem.Text(layout, x + dx, y + dy, firstLine, lastLine)
        is PageItem.Image -> PageItem.Image(
            data,
            RectF(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy),
        )
        is PageItem.Box -> PageItem.Box(
            RectF(bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy),
            fill,
            stroke,
            strokeWidth,
        )
    }

    /**
     * Measure a table into rows, each positioned relative to the table's own top-left.
     *
     * Rows are measured whole and never split, which is what makes a table readable when it runs
     * over a page boundary — a row cut in half down the middle is worse than a gap.
     */
    private fun layOutTable(table: Block.Table, contentWidth: Float): List<LaidOutRow> {
        val columns = table.rows.maxOfOrNull { row -> row.cells.sumOf { it.columnSpan.coerceAtLeast(1) } }
            ?: return emptyList()
        if (columns <= 0) return emptyList()
        val widths = columnWidths(table, columns, contentWidth)

        return table.rows.map { row ->
            val backgrounds = mutableListOf<PageItem.Box>()
            val borders = mutableListOf<PageItem.Box>()
            val texts = mutableListOf<Pair<Float, List<StaticLayout>>>()
            var column = 0
            var x = 0f
            var tallest = MIN_ROW_UNITS

            row.cells.forEach { cell ->
                if (column >= columns) return@forEach
                val span = cell.columnSpan.coerceAtLeast(1)
                val cellWidth = (column until min(columns, column + span))
                    .fold(0f) { total, index -> total + widths[index] }
                    .coerceAtLeast(MIN_CONTENT_UNITS)
                val inner = max(MIN_CONTENT_UNITS, cellWidth - 2 * CELL_PAD)
                val layouts = cell.paragraphs.map { paragraph ->
                    buildLayout(
                        if (cell.align != null) paragraph.copy(align = cell.align) else paragraph,
                        inner,
                    )
                }
                tallest = max(tallest, layouts.fold(2 * CELL_PAD) { total, l -> total + l.height })
                if (cell.background != 0) {
                    backgrounds += PageItem.Box(RectF(x, 0f, x + cellWidth, 0f), cell.background, 0, 0f)
                }
                borders += PageItem.Box(RectF(x, 0f, x + cellWidth, 0f), 0, TABLE_LINE_COLOUR, TABLE_LINE_UNITS)
                texts += x to layouts
                x += cellWidth
                column += span
            }

            // Fills first, then the grid, then the words: nothing must paint over the text.
            val items = mutableListOf<PageItem>()
            backgrounds.forEach { items += it.withHeight(tallest) }
            borders.forEach { items += it.withHeight(tallest) }
            texts.forEach { (cellX, layouts) ->
                var y = CELL_PAD
                layouts.forEach { layout ->
                    items += PageItem.Text(layout, cellX + CELL_PAD, y, 0, layout.lineCount - 1)
                    y += layout.height
                }
            }
            LaidOutRow(items, tallest)
        }
    }

    private fun PageItem.Box.withHeight(height: Float) =
        PageItem.Box(RectF(bounds.left, 0f, bounds.right, height), fill, stroke, strokeWidth)

    private fun columnWidths(table: Block.Table, columns: Int, contentWidth: Float): FloatArray {
        val declared = table.columnWidthsPt
        if (declared != null && declared.size == columns && declared.all { it > 0f }) {
            val total = declared.sum() * UNITS_PER_POINT
            val scale = if (total > contentWidth) contentWidth / total else 1f
            return FloatArray(columns) { declared[it] * UNITS_PER_POINT * scale }
        }
        return FloatArray(columns) { contentWidth / columns }
    }

    // ---------------------------------------------------------------- spreadsheets

    /**
     * A sheet becomes pages of rows, with the column letters and row numbers a spreadsheet has.
     *
     * Cells are one line each and clipped, exactly as a spreadsheet shows them: wrapping every
     * long cell would turn a dense sheet into an unreadable wall, and the reader can widen nothing
     * here anyway.
     */
    private fun gridPages(section: Section.Grid, sectionIndex: Int): List<LaidOutPage> {
        val gutter = GUTTER_UNITS
        val widths = section.columnWidthsPt.map { max(MIN_COLUMN_UNITS, it * UNITS_PER_POINT) }
        val totalWidth = gutter + widths.sum()
        // The page is the sheet, not a sheet of paper: a four-column table on a Letter page would
        // be scaled down to fit a width it never needed, and come out unreadably small.
        val pageWidth = max(MIN_SHEET_WIDTH, totalWidth + 2 * SHEET_MARGIN)
        val maxRowsPerPage = max(
            1,
            ((Section.LETTER_HEIGHT_PT * UNITS_PER_POINT - 2 * SHEET_MARGIN - ROW_UNITS) / ROW_UNITS).toInt(),
        )

        val pages = mutableListOf<LaidOutPage>()
        var index = 0
        var pageNumber = 1
        do {
            val items = mutableListOf<PageItem>()
            var y = SHEET_MARGIN

            // Column letters across the top, so a reference like "D14" can be found by eye.
            items += PageItem.Box(
                RectF(SHEET_MARGIN, y, SHEET_MARGIN + totalWidth, y + ROW_UNITS),
                HEADER_FILL,
                TABLE_LINE_COLOUR,
                TABLE_LINE_UNITS,
            )
            var x = SHEET_MARGIN + gutter
            widths.forEachIndexed { column, width ->
                items += cellText(columnName(column), x, y, width, bold = true, align = Align.CENTRE)
                items += PageItem.Box(
                    RectF(x, y, x + width, y + ROW_UNITS),
                    0,
                    TABLE_LINE_COLOUR,
                    TABLE_LINE_UNITS,
                )
                x += width
            }
            y += ROW_UNITS

            var drawn = 0
            while (drawn < maxRowsPerPage && index < section.rows.size) {
                val row = section.rows[index]
                items += cellText(
                    (index + 1).toString(),
                    SHEET_MARGIN,
                    y,
                    gutter,
                    bold = true,
                    align = Align.CENTRE,
                )
                items += PageItem.Box(
                    RectF(SHEET_MARGIN, y, SHEET_MARGIN + gutter, y + ROW_UNITS),
                    HEADER_FILL,
                    TABLE_LINE_COLOUR,
                    TABLE_LINE_UNITS,
                )
                var cellX = SHEET_MARGIN + gutter
                widths.forEachIndexed { column, width ->
                    val cell = row.getOrNull(column)
                    if (cell != null && cell.background != 0) {
                        items += PageItem.Box(
                            RectF(cellX, y, cellX + width, y + ROW_UNITS),
                            cell.background,
                            0,
                            0f,
                        )
                    }
                    items += PageItem.Box(
                        RectF(cellX, y, cellX + width, y + ROW_UNITS),
                        0,
                        TABLE_LINE_COLOUR,
                        TABLE_LINE_UNITS,
                    )
                    if (cell != null && cell.text.isNotEmpty()) {
                        items += cellText(
                            cell.text,
                            cellX,
                            y,
                            width,
                            cell.bold,
                            cell.align,
                            cell.colour,
                        )
                    }
                    cellX += width
                }
                y += ROW_UNITS
                index++
                drawn++
            }

            pages += LaidOutPage(
                pageWidth,
                y + SHEET_MARGIN,
                items,
                Color.WHITE,
                sectionIndex,
                if (pageNumber == 1) section.title else "${section.title} · $pageNumber",
            )
            pageNumber++
        } while (index < section.rows.size)
        return pages
    }

    private fun cellText(
        text: String,
        x: Float,
        y: Float,
        width: Float,
        bold: Boolean,
        align: Align,
        colour: Int = Color.BLACK,
    ): PageItem.Text {
        val paragraph = Paragraph(
            spans = listOf(TextSpan(text, bold = bold, sizePt = SHEET_TEXT_PT, colour = colour)),
            align = align,
            spaceAfterPt = 0f,
        )
        val layout = buildLayout(paragraph, max(MIN_CONTENT_UNITS, width - 2 * CELL_PAD), maxLines = 1)
        val centred = y + (ROW_UNITS - layout.height) / 2f
        return PageItem.Text(layout, x + CELL_PAD, centred, 0, 0)
    }

    /** Spreadsheet column names: A, B, ... Z, AA, AB, ... */
    fun columnName(index: Int): String {
        var remaining = index
        val name = StringBuilder()
        while (remaining >= 0) {
            name.insert(0, ('A' + remaining % 26))
            remaining = remaining / 26 - 1
        }
        return name.toString()
    }

    // ---------------------------------------------------------------- slides

    private fun slidePage(section: Section.Slide, sectionIndex: Int): List<LaidOutPage> {
        val width = section.widthPt * UNITS_PER_POINT
        val height = section.heightPt * UNITS_PER_POINT
        val items = mutableListOf<PageItem>()

        section.items.forEach { item ->
            val bounds = RectF(
                item.xPt * UNITS_PER_POINT,
                item.yPt * UNITS_PER_POINT,
                (item.xPt + item.widthPt) * UNITS_PER_POINT,
                (item.yPt + item.heightPt) * UNITS_PER_POINT,
            )
            when (item) {
                is SlideItem.Picture -> items += PageItem.Image(item.data, bounds)
                is SlideItem.Shape -> items += PageItem.Box(
                    bounds,
                    item.fill,
                    item.stroke,
                    if (item.stroke != 0) TABLE_LINE_UNITS else 0f,
                )
                is SlideItem.Text -> {
                    if (item.fill != 0) items += PageItem.Box(bounds, item.fill, 0, 0f)
                    val inner = max(MIN_CONTENT_UNITS, bounds.width() - 2 * SLIDE_PAD)
                    val layouts = item.paragraphs.map { buildLayout(it, inner) }
                    val total = layouts.sumOf { it.height.toDouble() }.toFloat()
                    var y = when (item.verticalAlign) {
                        1 -> bounds.top + (bounds.height() - total) / 2f
                        2 -> bounds.bottom - total - SLIDE_PAD
                        else -> bounds.top + SLIDE_PAD
                    }.coerceAtLeast(bounds.top)
                    layouts.forEach { layout ->
                        items += PageItem.Text(layout, bounds.left + SLIDE_PAD, y, 0, layout.lineCount - 1)
                        y += layout.height
                    }
                }
            }
        }
        return listOf(
            LaidOutPage(width, height, items, section.background, sectionIndex, section.title),
        )
    }

    // ---------------------------------------------------------------- text measuring

    /**
     * Measure one paragraph.
     *
     * The marker — a bullet or a list number — is put in front of the text with a hanging indent
     * so wrapped lines line up under the first word rather than under the bullet, which is what
     * makes a list read as a list.
     */
    fun buildLayout(paragraph: Paragraph, width: Float, maxLines: Int = Int.MAX_VALUE): StaticLayout {
        val builder = SpannableStringBuilder()
        paragraph.marker?.let { marker ->
            val start = builder.length
            builder.append(marker).append(' ')
            builder.setSpan(
                AbsoluteSizeSpan(sizeOf(paragraph).toInt()),
                start,
                builder.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        paragraph.spans.forEach { span ->
            if (span.text.isEmpty()) return@forEach
            val start = builder.length
            builder.append(span.text)
            val end = builder.length
            fun mark(what: Any) =
                builder.setSpan(what, start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)

            val style = when {
                span.bold && span.italic -> Typeface.BOLD_ITALIC
                span.bold -> Typeface.BOLD
                span.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) mark(StyleSpan(style))
            if (span.underline) mark(UnderlineSpan())
            if (span.strike) mark(StrikethroughSpan())
            if (span.monospace) mark(TypefaceSpan("monospace"))
            mark(AbsoluteSizeSpan(ceil(span.sizePt * UNITS_PER_POINT).toInt()))
            if (span.colour != Color.BLACK) mark(ForegroundColorSpan(span.colour))
            if (span.highlight != 0) mark(BackgroundColorSpan(span.highlight))
            when (span.script) {
                1 -> mark(SuperscriptSpan())
                -1 -> mark(SubscriptSpan())
            }
        }

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = sizeOf(paragraph)
            color = Color.BLACK
        }
        val alignment = when (paragraph.align) {
            Align.CENTRE -> Layout.Alignment.ALIGN_CENTER
            Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder
            .obtain(builder, 0, builder.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(0f, paragraph.lineSpacing.coerceIn(0.6f, 3f))
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(if (maxLines == 1) android.text.TextUtils.TruncateAt.END else null)
            .apply {
                if (paragraph.align == Align.JUSTIFY) {
                    setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                }
            }
            .build()
    }

    private fun sizeOf(paragraph: Paragraph): Float {
        val first = paragraph.spans.firstOrNull()?.sizePt ?: TextSpan.DEFAULT_SIZE_PT
        return first * UNITS_PER_POINT
    }

    private const val MIN_CONTENT_UNITS = 8f
    private const val PAD = 2f * UNITS_PER_POINT
    private const val CELL_PAD = 3f * UNITS_PER_POINT
    private const val PICTURE_GAP = 8f * UNITS_PER_POINT
    private const val TABLE_GAP = 8f * UNITS_PER_POINT
    private const val MIN_ROW_UNITS = 14f * UNITS_PER_POINT
    private const val TABLE_LINE_UNITS = 0.6f * UNITS_PER_POINT
    private const val RULE_UNITS = 0.8f * UNITS_PER_POINT
    private const val TABLE_LINE_COLOUR = 0xFFB9BFC9.toInt()
    private const val RULE_COLOUR = 0xFFCBD2DC.toInt()

    private const val SHEET_MARGIN = 18f * UNITS_PER_POINT
    private const val ROW_UNITS = 17f * UNITS_PER_POINT
    private const val GUTTER_UNITS = 34f * UNITS_PER_POINT
    private const val MIN_COLUMN_UNITS = 24f * UNITS_PER_POINT
    private const val SHEET_TEXT_PT = 10f

    /** Below this a sheet page would be a sliver on screen; above it, it is its own width. */
    private const val MIN_SHEET_WIDTH = 300f * UNITS_PER_POINT
    private const val HEADER_FILL = 0xFFEDF1F6.toInt()

    private const val SLIDE_PAD = 6f * UNITS_PER_POINT
}
