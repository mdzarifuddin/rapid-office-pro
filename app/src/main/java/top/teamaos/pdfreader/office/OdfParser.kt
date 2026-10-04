package top.teamaos.pdfreader.office

import android.graphics.Color
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Reading OpenDocument: .odt, .ods and .odp, and their templates.
 *
 * What LibreOffice saves, and what a good share of government and university documents arrive as.
 * The package is a zip like OOXML, but simpler in the way that matters: every part of the content
 * is in one `content.xml`, and the styles it refers to are in that file or in `styles.xml`. So a
 * document is two XML walks and nothing else.
 *
 * The same rule as the other parsers: what the file says is read faithfully, and what would need a
 * layout engine to honour — floating frames, multi-column sections, per-cell borders — is turned
 * into the nearest thing the shared model can show rather than dropped.
 */
object OdfParser {

    enum class Flavour { TEXT, SPREADSHEET, PRESENTATION }

    /**
     * What kind of OpenDocument [pack] is, from its `mimetype` entry, or null if it is not one.
     *
     * The mimetype is the first entry of every OpenDocument zip precisely so that it can be sniffed
     * like this; the file's extension is not consulted.
     */
    fun flavourOf(pack: OoxmlPackage): Flavour? {
        val type = pack.bytes("mimetype")?.let { String(it, Charsets.US_ASCII).trim() }
        if (type != null) {
            return when {
                type.contains("opendocument.text") -> Flavour.TEXT
                type.contains("opendocument.spreadsheet") -> Flavour.SPREADSHEET
                // Drawings are pages of positioned frames, which is exactly what a slide is.
                type.contains("opendocument.presentation") || type.contains("opendocument.graphics") ->
                    Flavour.PRESENTATION
                else -> null
            }
        }
        // A few tools leave the mimetype out; the body element still says what the file is.
        val content = pack.bytes("content.xml") ?: return null
        var found: Flavour? = null
        OoxmlPackage.parse(content) { parser ->
            if (found == null && parser.eventType == XmlPullParser.START_TAG) {
                found = when (OoxmlPackage.localName(parser.name)) {
                    "text" -> if (parser.name == "office:text") Flavour.TEXT else null
                    "spreadsheet" -> Flavour.SPREADSHEET
                    "presentation", "drawing" -> Flavour.PRESENTATION
                    else -> null
                }
            }
        }
        return found
    }

    fun parse(file: File, displayName: String): OfficeDocument? {
        val pack = OoxmlPackage.open(file) ?: return null
        return pack.use { open ->
            val flavour = flavourOf(open) ?: return@use null
            val content = open.bytes("content.xml") ?: return@use null
            val styles = Styles()
            open.bytes("styles.xml")?.let { styles.read(it) }
            styles.read(content)
            when (flavour) {
                Flavour.TEXT -> parseText(open, content, styles, displayName)
                Flavour.SPREADSHEET -> parseSpreadsheet(content, styles, displayName)
                Flavour.PRESENTATION -> parsePresentation(open, content, styles, displayName)
            }
        }
    }

    // ------------------------------------------------------------------ styles

    /** What a style says, every field optional so a style can inherit what it leaves out. */
    private class Style(
        val parent: String?,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        val sizePt: Float? = null,
        val colour: Int? = null,
        val align: Align? = null,
        val columnWidthPt: Float? = null,
    )

    /** The page a document or a slide is laid out on, in points. */
    private class PageLayout(
        val widthPt: Float?,
        val heightPt: Float?,
        val marginLeftPt: Float?,
        val marginRightPt: Float?,
        val marginTopPt: Float?,
        val marginBottomPt: Float?,
    )

    /**
     * Every named style in the package, keyed by family and name.
     *
     * Keyed by family because ODF lets a paragraph style and a text style share a name, and they
     * mean different things.
     */
    private class Styles {
        val byKey = HashMap<String, Style>()
        val numberedLevels = HashMap<String, MutableSet<Int>>()
        val layouts = HashMap<String, PageLayout>()
        var masterLayout: String? = null

        fun read(data: ByteArray) {
            var key: String? = null
            var parent: String? = null
            var family = ""
            var bold: Boolean? = null
            var italic: Boolean? = null
            var underline: Boolean? = null
            var strike: Boolean? = null
            var size: Float? = null
            var colour: Int? = null
            var align: Align? = null
            var columnWidth: Float? = null
            var listStyle: String? = null
            var layoutName: String? = null

            OoxmlPackage.parse(data) { parser ->
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "style:style", "style:default-style" -> {
                            family = OoxmlPackage.attr(parser, "family").orEmpty()
                            val name = if (parser.name == "style:default-style") {
                                DEFAULT_NAME
                            } else {
                                OoxmlPackage.attr(parser, "name")
                            }
                            key = name?.let { "$family/$it" }
                            parent = OoxmlPackage.attr(parser, "parent-style-name")
                                ?: if (name != DEFAULT_NAME) DEFAULT_NAME else null
                            bold = null; italic = null; underline = null; strike = null
                            size = null; colour = null; align = null; columnWidth = null
                        }
                        "style:text-properties" -> if (key != null) {
                            OoxmlPackage.attr(parser, "font-weight")?.let {
                                bold = it == "bold" || (it.toIntOrNull() ?: 400) >= 600
                            }
                            OoxmlPackage.attr(parser, "font-style")?.let { italic = it != "normal" }
                            OoxmlPackage.attr(parser, "text-underline-style")?.let { underline = it != "none" }
                            OoxmlPackage.attr(parser, "text-line-through-style")?.let { strike = it != "none" }
                            lengthPt(OoxmlPackage.attr(parser, "font-size"))
                                ?.takeIf { it > 0.5f && it < 400f }
                                ?.let { size = it }
                            OoxmlPackage.attr(parser, "color")?.let { colour = OoxmlPackage.colourOf(it, Color.BLACK) }
                        }
                        "style:paragraph-properties" -> if (key != null) {
                            align = alignOf(OoxmlPackage.attr(parser, "text-align"))
                        }
                        "style:table-column-properties" -> if (key != null) {
                            columnWidth = lengthPt(OoxmlPackage.attr(parser, "column-width"))
                        }
                        "text:list-style" -> listStyle = OoxmlPackage.attr(parser, "name")
                        "text:list-level-style-number" -> {
                            val level = OoxmlPackage.attr(parser, "level")?.toIntOrNull()
                            val list = listStyle
                            if (list != null && level != null) {
                                numberedLevels.getOrPut(list) { HashSet() } += level
                            }
                        }
                        "style:page-layout" -> layoutName = OoxmlPackage.attr(parser, "name")
                        "style:page-layout-properties" -> layoutName?.let { name ->
                            layouts[name] = PageLayout(
                                pagePt(OoxmlPackage.attr(parser, "page-width")),
                                pagePt(OoxmlPackage.attr(parser, "page-height")),
                                lengthPt(OoxmlPackage.attr(parser, "margin-left")),
                                lengthPt(OoxmlPackage.attr(parser, "margin-right")),
                                lengthPt(OoxmlPackage.attr(parser, "margin-top")),
                                lengthPt(OoxmlPackage.attr(parser, "margin-bottom")),
                            )
                        }
                        "style:master-page" -> if (masterLayout == null) {
                            masterLayout = OoxmlPackage.attr(parser, "page-layout-name")
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "style:style", "style:default-style" -> {
                            key?.let {
                                byKey[it] = Style(
                                    parent, bold, italic, underline, strike, size, colour, align, columnWidth,
                                )
                            }
                            key = null
                        }
                        "text:list-style" -> listStyle = null
                        "style:page-layout" -> layoutName = null
                    }
                }
            }
        }

        /** A style with everything it inherits filled in, walking at most a few parents up. */
        fun resolved(family: String, name: String?): Style? {
            var current = byKey["$family/${name ?: DEFAULT_NAME}"] ?: return byKey["$family/$DEFAULT_NAME"]
            var bold = current.bold
            var italic = current.italic
            var underline = current.underline
            var strike = current.strike
            var size = current.sizePt
            var colour = current.colour
            var align = current.align
            val width = current.columnWidthPt
            var depth = 0
            while (depth++ < MAX_STYLE_DEPTH) {
                val parentName = current.parent ?: break
                current = byKey["$family/$parentName"] ?: break
                bold = bold ?: current.bold
                italic = italic ?: current.italic
                underline = underline ?: current.underline
                strike = strike ?: current.strike
                size = size ?: current.sizePt
                colour = colour ?: current.colour
                align = align ?: current.align
            }
            return Style(null, bold, italic, underline, strike, size, colour, align, width)
        }

        fun columnWidth(name: String?): Float? = name?.let { byKey["table-column/$it"]?.columnWidthPt }

        fun isNumbered(listStyle: String?, level: Int): Boolean =
            listStyle != null && numberedLevels[listStyle]?.contains(level) == true

        fun pageLayout(): PageLayout? = layouts[masterLayout] ?: layouts.values.firstOrNull()
    }

    // ------------------------------------------------------------------ text, shared

    /**
     * Collects paragraphs — their runs, their styles, their list markers — from the events of any
     * ODF body. Text documents and slides write text identically, so both use this.
     *
     * Paragraphs nest in ODF (a text box anchored in a paragraph holds paragraphs of its own), so
     * the paragraph being built is a stack: an inner paragraph is emitted on its own, and the outer
     * one carries on afterwards with its own style.
     */
    private class TextCollector(
        private val styles: Styles,
        private val onParagraph: (Paragraph, headingLevel: Int?) -> Unit,
    ) {
        var defaultSizePt = TextSpan.DEFAULT_SIZE_PT

        private class Open(val style: String?, val heading: Int?) {
            val spans = mutableListOf<TextSpan>()
            val run = StringBuilder()
            val spanStyles = ArrayList<String?>()
        }

        private class ListLevel(val style: String?) {
            var counter = 0
        }

        private val open = ArrayList<Open>()
        private val lists = ArrayList<ListLevel>()
        private var markerPending = false

        val inParagraph: Boolean get() = open.isNotEmpty()

        /** True if the element was one this collector handles. */
        fun start(parser: XmlPullParser): Boolean {
            when (parser.name) {
                "text:p", "text:h" -> {
                    open.lastOrNull()?.let { flush(it, keepOpen = true) }
                    val heading = if (parser.name == "text:h") {
                        (OoxmlPackage.attr(parser, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 10)
                    } else {
                        null
                    }
                    open += Open(OoxmlPackage.attr(parser, "style-name"), heading)
                }
                "text:span", "text:a" -> open.lastOrNull()?.let { paragraph ->
                    endRun(paragraph)
                    paragraph.spanStyles += if (parser.name == "text:span") {
                        OoxmlPackage.attr(parser, "style-name")
                    } else {
                        null
                    }
                }
                "text:s" -> open.lastOrNull()?.run?.let { run ->
                    val count = OoxmlPackage.attr(parser, "c")?.toIntOrNull()?.coerceIn(1, MAX_SPACES) ?: 1
                    repeat(count) { run.append(' ') }
                }
                "text:tab" -> open.lastOrNull()?.run?.append('\t')
                "text:line-break" -> open.lastOrNull()?.run?.append('\n')
                "text:list" -> {
                    val style = OoxmlPackage.attr(parser, "style-name") ?: lists.lastOrNull()?.style
                    lists += ListLevel(style)
                }
                "text:list-item" -> {
                    lists.lastOrNull()?.let { it.counter++ }
                    markerPending = true
                }
                "text:list-header" -> markerPending = false
                else -> return false
            }
            return true
        }

        fun text(value: String) {
            open.lastOrNull()?.run?.append(value)
        }

        fun end(name: String) {
            when (name) {
                "text:p", "text:h" -> open.removeLastOrNull()?.let { flush(it, keepOpen = false) }
                "text:span", "text:a" -> open.lastOrNull()?.let { paragraph ->
                    endRun(paragraph)
                    paragraph.spanStyles.removeLastOrNull()
                }
                "text:list" -> lists.removeLastOrNull()
            }
        }

        private fun endRun(paragraph: Open) {
            if (paragraph.run.isEmpty()) return
            val base = styles.resolved("paragraph", paragraph.style)
            var bold = base?.bold
            var italic = base?.italic
            var underline = base?.underline
            var strike = base?.strike
            var size = base?.sizePt
            var colour = base?.colour
            // Outermost span first, so what an inner span says overrides it.
            for (name in paragraph.spanStyles) {
                val span = styles.resolved("text", name ?: continue) ?: continue
                bold = span.bold ?: bold
                italic = span.italic ?: italic
                underline = span.underline ?: underline
                strike = span.strike ?: strike
                size = span.sizePt ?: size
                colour = span.colour ?: colour
            }
            val heading = paragraph.heading
            paragraph.spans += TextSpan(
                text = paragraph.run.toString(),
                bold = bold ?: (heading != null),
                italic = italic ?: false,
                underline = underline ?: false,
                strike = strike ?: false,
                sizePt = size ?: heading?.let(::headingSize) ?: defaultSizePt,
                colour = colour ?: Color.BLACK,
            )
            paragraph.run.setLength(0)
        }

        private fun flush(paragraph: Open, keepOpen: Boolean) {
            endRun(paragraph)
            if (keepOpen && paragraph.spans.isEmpty()) return
            val marker = if (markerPending && lists.isNotEmpty()) {
                markerPending = false
                val level = lists.size
                if (styles.isNumbered(lists.last().style, level)) "${lists.last().counter}." else bulletFor(level)
            } else {
                null
            }
            val style = styles.resolved("paragraph", paragraph.style)
            val heading = paragraph.heading
            onParagraph(
                Paragraph(
                    spans = paragraph.spans.toList(),
                    align = style?.align ?: Align.LEFT,
                    indentPt = lists.size * LIST_INDENT_PT,
                    spaceBeforePt = if (heading != null) 6f else 0f,
                    spaceAfterPt = 6f,
                    marker = marker,
                    ruleBelow = heading != null && heading <= 1 && paragraph.spans.isNotEmpty(),
                ),
                heading,
            )
            paragraph.spans.clear()
        }

        private fun bulletFor(level: Int): String = when ((level - 1) % 3) {
            0 -> "•"
            1 -> "◦"
            else -> "▪"
        }

        private fun headingSize(level: Int): Float = when (level) {
            1 -> 20f
            2 -> 16f
            3 -> 14f
            else -> 12f
        }
    }

    /**
     * Walk [data], skipping the parts that are not the document's own visible content: comments,
     * footnote bodies, tracked deletions and speaker notes.
     */
    private fun walk(
        data: ByteArray,
        onStart: (XmlPullParser) -> Unit,
        onText: (String) -> Unit,
        onEnd: (String) -> Unit,
    ) {
        var skipping = 0
        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when {
                    skipping > 0 || parser.name in SKIPPED -> skipping++
                    else -> onStart(parser)
                }
                XmlPullParser.TEXT -> if (skipping == 0) onText(parser.text)
                XmlPullParser.END_TAG -> if (skipping > 0) skipping-- else onEnd(parser.name)
            }
        }
    }

    // ------------------------------------------------------------------ .odt

    private fun parseText(
        pack: OoxmlPackage,
        content: ByteArray,
        styles: Styles,
        displayName: String,
    ): OfficeDocument? {
        val pictures = OoxmlPackage.Pictures(pack)
        val blocks = mutableListOf<Block>()
        val outline = mutableListOf<OutlineEntry>()

        var tableDepth = 0
        var rows = mutableListOf<TableRow>()
        var cells = mutableListOf<TableCell>()
        var cellParagraphs = mutableListOf<Paragraph>()
        var cellSpan = 1
        var columnWidths = mutableListOf<Float>()
        var frameWidth: Float? = null
        var frameHeight: Float? = null
        var imageHref: String? = null
        var inBody = false

        val collector = TextCollector(styles) { paragraph, heading ->
            if (blocks.size >= MAX_BLOCKS) return@TextCollector
            if (tableDepth > 0) {
                cellParagraphs += paragraph
            } else {
                if (heading != null && paragraph.plainText.isNotBlank()) {
                    outline += OutlineEntry(paragraph.plainText.trim(), 0, heading - 1)
                }
                blocks += Block.Text(paragraph)
            }
        }

        walk(
            content,
            onStart = { parser ->
                if (parser.name == "office:text") inBody = true
                if (!inBody || collector.start(parser)) return@walk
                when (parser.name) {
                    "table:table" -> {
                        tableDepth++
                        if (tableDepth == 1) {
                            rows = mutableListOf()
                            columnWidths = mutableListOf()
                        }
                    }
                    "table:table-column" -> if (tableDepth == 1) {
                        val repeat = repeatOf(parser, "number-columns-repeated", MAX_TABLE_COLUMNS)
                        val width = styles.columnWidth(OoxmlPackage.attr(parser, "style-name")) ?: 0f
                        repeat(repeat) { if (columnWidths.size < MAX_TABLE_COLUMNS) columnWidths += width }
                    }
                    "table:table-row" -> if (tableDepth == 1) cells = mutableListOf()
                    "table:table-cell" -> if (tableDepth == 1) {
                        cellParagraphs = mutableListOf()
                        cellSpan = repeatOf(parser, "number-columns-spanned", MAX_TABLE_COLUMNS)
                    }
                    "draw:frame" -> {
                        frameWidth = lengthPt(OoxmlPackage.attr(parser, "width"))
                        frameHeight = lengthPt(OoxmlPackage.attr(parser, "height"))
                        imageHref = null
                    }
                    "draw:image" -> imageHref = OoxmlPackage.attr(parser, "href")
                }
            },
            onText = { text -> if (inBody) collector.text(text) },
            onEnd = { name ->
                if (!inBody) return@walk
                collector.end(name)
                when (name) {
                    "office:text" -> inBody = false
                    "table:table-cell" -> if (tableDepth == 1) {
                        cells += TableCell(
                            cellParagraphs.ifEmpty { listOf(Paragraph(emptyList(), spaceAfterPt = 0f)) },
                            columnSpan = cellSpan,
                        )
                    }
                    "table:table-row" -> if (tableDepth == 1 && cells.isNotEmpty()) rows += TableRow(cells)
                    "table:table" -> {
                        tableDepth--
                        if (tableDepth == 0 && rows.isNotEmpty() && blocks.size < MAX_BLOCKS) {
                            val widths = columnWidths.takeIf { list -> list.isNotEmpty() && list.all { it > 0f } }
                            blocks += Block.Table(rows, widths)
                        }
                    }
                    "draw:frame" -> {
                        val href = imageHref
                        if (href != null && tableDepth == 0 && blocks.size < MAX_BLOCKS) {
                            pictures.bytes(packagePath(href))?.let { data ->
                                blocks += Block.Picture(
                                    data,
                                    frameWidth?.takeIf { it > 0f } ?: DEFAULT_IMAGE_PT,
                                    frameHeight?.takeIf { it > 0f } ?: DEFAULT_IMAGE_PT,
                                )
                            }
                        }
                        imageHref = null
                    }
                }
            },
        )
        if (blocks.isEmpty()) return null

        val layout = styles.pageLayout()
        val section = Section.Flow(
            title = displayName,
            blocks = blocks,
            pageWidthPt = layout?.widthPt ?: Section.LETTER_WIDTH_PT,
            pageHeightPt = layout?.heightPt ?: Section.LETTER_HEIGHT_PT,
            marginLeftPt = layout?.marginLeftPt ?: 72f,
            marginRightPt = layout?.marginRightPt ?: 72f,
            marginTopPt = layout?.marginTopPt ?: 72f,
            marginBottomPt = layout?.marginBottomPt ?: 72f,
        )
        return OfficeDocument(
            kind = OfficeKind.WORD,
            displayName = displayName,
            sections = listOf(section),
            outline = outline,
            limitationNote = if (blocks.size >= MAX_BLOCKS) {
                "This document is very long; only the first part of it is shown."
            } else {
                null
            },
        )
    }

    // ------------------------------------------------------------------ .ods

    /**
     * Sheets, as grids.
     *
     * ODF compresses runs of identical rows and cells with a repeat count, and LibreOffice uses it
     * freely — the last row of a sheet is routinely "this empty row, a million times". So nothing
     * empty is ever expanded: blank rows are only counted, and written out only if a row with
     * something in it follows them.
     */
    private fun parseSpreadsheet(content: ByteArray, styles: Styles, displayName: String): OfficeDocument? {
        val sections = mutableListOf<Section.Grid>()
        val limits = GridLimits()

        var sheetName = ""
        var builder = GridBuilder(limits)
        var leftStyle = 0
        var rightStyle = 0
        var widths = mutableListOf<Float>()
        var nextRow = 0L
        var widest = -1

        // The cells of the row being read: (column, text, numeric). Small, and reused.
        val row = ArrayList<Triple<Int, String, Boolean>>()
        var rowRepeat = 1
        var column = 0
        var cellRepeat = 1
        var cellNumeric = false
        val cellText = StringBuilder()
        var cellParagraphs = 0
        var inCell = false
        var inCellParagraph = false
        var tableDepth = 0

        walk(
            content,
            onStart = { parser ->
                when (parser.name) {
                    "table:table" -> {
                        tableDepth++
                        if (tableDepth == 1) {
                            sheetName = OoxmlPackage.attr(parser, "name") ?: "Sheet ${sections.size + 1}"
                            builder = GridBuilder(limits)
                            leftStyle = builder.styleOf(GridStyle(align = Align.LEFT))
                            rightStyle = builder.styleOf(GridStyle(align = Align.RIGHT))
                            widths = mutableListOf()
                            nextRow = 0L
                            widest = -1
                        }
                    }
                    "table:table-column" -> if (tableDepth == 1) {
                        val repeat = repeatOf(parser, "number-columns-repeated", MAX_COLUMNS)
                        val width = styles.columnWidth(OoxmlPackage.attr(parser, "style-name"))
                            ?.coerceIn(18f, 260f) ?: DEFAULT_COLUMN_PT
                        repeat(repeat) { if (widths.size < MAX_COLUMNS) widths += width }
                    }
                    "table:table-row" -> if (tableDepth == 1) {
                        row.clear()
                        rowRepeat = repeatOf(parser, "number-rows-repeated", Int.MAX_VALUE)
                        column = 0
                    }
                    "table:table-cell", "table:covered-table-cell" -> if (tableDepth == 1) {
                        cellRepeat = repeatOf(parser, "number-columns-repeated", Int.MAX_VALUE)
                        val type = OoxmlPackage.attr(parser, "value-type")
                        cellNumeric = type in NUMERIC_TYPES
                        cellText.setLength(0)
                        cellParagraphs = 0
                        inCell = true
                    }
                    "text:p" -> if (inCell) {
                        if (cellParagraphs++ > 0) cellText.append(' ')
                        inCellParagraph = true
                    }
                    "text:s" -> if (inCellParagraph) {
                        val count = OoxmlPackage.attr(parser, "c")?.toIntOrNull()?.coerceIn(1, MAX_SPACES) ?: 1
                        repeat(count) { cellText.append(' ') }
                    }
                    "text:tab" -> if (inCellParagraph) cellText.append('\t')
                }
            },
            onText = { text ->
                if (inCellParagraph && cellText.length < MAX_CELL_CHARS) cellText.append(text)
            },
            onEnd = { name ->
                when (name) {
                    "text:p" -> inCellParagraph = false
                    "table:table-cell", "table:covered-table-cell" -> if (tableDepth == 1 && inCell) {
                        inCell = false
                        val text = cellText.toString()
                        if (text.isNotEmpty() && column < MAX_COLUMNS) {
                            val last = minOf(MAX_COLUMNS.toLong(), column.toLong() + cellRepeat).toInt()
                            for (index in column until last) row += Triple(index, text, cellNumeric)
                            widest = maxOf(widest, last - 1)
                        }
                        column = minOf(column.toLong() + cellRepeat, Int.MAX_VALUE.toLong()).toInt()
                    }
                    "table:table-row" -> if (tableDepth == 1) {
                        checkCancelled()
                        if (row.isEmpty() || builder.full) {
                            // Empty rows are only counted; the builder fills the gap if a row with
                            // something in it follows, and never does if none does.
                            nextRow += rowRepeat
                        } else {
                            repeat(rowRepeat) {
                                if (builder.full || nextRow >= limits.maxRows) return@repeat
                                for ((index, text, numeric) in row) {
                                    val auto = GridData.looksNumeric(text, 0, text.length)
                                    val style = when {
                                        auto == numeric -> 0
                                        numeric -> rightStyle
                                        else -> leftStyle
                                    }
                                    builder.put(nextRow.toInt(), index, text, style)
                                }
                                nextRow++
                            }
                        }
                    }
                    "table:table" -> {
                        if (tableDepth == 1) {
                            val count = (widest + 1).coerceAtLeast(1)
                            sections += Section.Grid(
                                title = sheetName,
                                grid = builder.build(),
                                columnWidthsPt = (0 until count).map { widths.getOrNull(it) ?: DEFAULT_COLUMN_PT },
                            )
                        }
                        tableDepth--
                    }
                }
            },
        )
        if (sections.isEmpty()) return null
        return OfficeDocument(
            kind = OfficeKind.SPREADSHEET,
            displayName = displayName,
            sections = sections,
            outline = sections.mapIndexed { index, section -> OutlineEntry(section.title, index) },
            limitationNote = limits.note,
        )
    }

    // ------------------------------------------------------------------ .odp

    private fun parsePresentation(
        pack: OoxmlPackage,
        content: ByteArray,
        styles: Styles,
        displayName: String,
    ): OfficeDocument? {
        val pictures = OoxmlPackage.Pictures(pack)
        val layout = styles.pageLayout()
        val width = layout?.widthPt ?: DEFAULT_SLIDE_WIDTH_PT
        val height = layout?.heightPt ?: DEFAULT_SLIDE_HEIGHT_PT

        val sections = mutableListOf<Section.Slide>()
        var items = mutableListOf<SlideItem>()
        var pageName = ""
        var title = ""
        var inPage = false

        var frameDepth = 0
        var frameBox = FloatArray(4)
        var frameClass: String? = null
        var frameParagraphs = mutableListOf<Paragraph>()
        var frameImage: String? = null

        val collector = TextCollector(styles) { paragraph, _ ->
            if (frameDepth > 0) frameParagraphs += paragraph
        }

        walk(
            content,
            onStart = { parser ->
                if (parser.name == "draw:page") {
                    inPage = true
                    items = mutableListOf()
                    pageName = OoxmlPackage.attr(parser, "name").orEmpty()
                    title = ""
                    return@walk
                }
                if (!inPage) return@walk
                if (parser.name in FRAMES) {
                    frameDepth++
                    if (frameDepth == 1) {
                        frameBox = floatArrayOf(
                            lengthPt(OoxmlPackage.attr(parser, "x")) ?: 0f,
                            lengthPt(OoxmlPackage.attr(parser, "y")) ?: 0f,
                            lengthPt(OoxmlPackage.attr(parser, "width")) ?: 0f,
                            lengthPt(OoxmlPackage.attr(parser, "height")) ?: 0f,
                        )
                        frameClass = OoxmlPackage.attr(parser, "class")
                        frameParagraphs = mutableListOf()
                        frameImage = null
                        collector.defaultSizePt = when (frameClass) {
                            "title" -> 32f
                            "subtitle" -> 22f
                            else -> 18f
                        }
                    }
                    return@walk
                }
                if (collector.start(parser)) return@walk
                if (parser.name == "draw:image" && frameDepth > 0 && frameImage == null) {
                    frameImage = OoxmlPackage.attr(parser, "href")
                }
            },
            onText = { text -> if (inPage) collector.text(text) },
            onEnd = { name ->
                if (!inPage) return@walk
                collector.end(name)
                when {
                    name in FRAMES -> {
                        if (frameDepth == 1) {
                            val box = frameBox
                            val placed = box[2] > 0f && box[3] > 0f
                            val texts = frameParagraphs.filter { it.spans.isNotEmpty() }
                            val image = frameImage
                            if (texts.isNotEmpty()) {
                                if (title.isBlank() && frameClass == "title") {
                                    title = texts.first().plainText.trim()
                                }
                                val bounds = if (placed) {
                                    box
                                } else {
                                    floatArrayOf(width * 0.08f, height * 0.3f, width * 0.84f, height * 0.6f)
                                }
                                items += SlideItem.Text(
                                    bounds[0], bounds[1], bounds[2], bounds[3],
                                    texts,
                                    verticalAlign = if (frameClass == "title") 1 else 0,
                                )
                            } else if (image != null && placed) {
                                pictures.bytes(packagePath(image))?.let { data ->
                                    items += SlideItem.Picture(box[0], box[1], box[2], box[3], data)
                                }
                            }
                        }
                        frameDepth = (frameDepth - 1).coerceAtLeast(0)
                    }
                    name == "draw:page" -> {
                        inPage = false
                        if (sections.size < MAX_SLIDES) {
                            val fallback = items.filterIsInstance<SlideItem.Text>().firstOrNull()
                                ?.paragraphs?.firstOrNull { it.plainText.isNotBlank() }
                                ?.plainText?.trim()?.take(60).orEmpty()
                            sections += Section.Slide(
                                title = title.ifBlank { fallback }.ifBlank { pageName }
                                    .ifBlank { "Slide ${sections.size + 1}" },
                                widthPt = width,
                                heightPt = height,
                                items = items,
                                background = Color.WHITE,
                            )
                        }
                    }
                }
            },
        )
        if (sections.isEmpty()) return null
        return OfficeDocument(
            kind = OfficeKind.SLIDES,
            displayName = displayName,
            sections = sections,
            outline = sections.mapIndexed { index, section -> OutlineEntry("${index + 1}. ${section.title}", index) },
        )
    }

    // ------------------------------------------------------------------ helpers

    /** A repeat or span count, clamped: these come from the file and can be any number at all. */
    private fun repeatOf(parser: XmlPullParser, name: String, max: Int): Int =
        OoxmlPackage.attr(parser, name)?.toIntOrNull()?.coerceIn(1, max) ?: 1

    /** `./Pictures/x.png` and `Pictures/x.png` are the same entry. */
    private fun packagePath(href: String): String = href.removePrefix("./").removePrefix("/")

    /**
     * An ODF length — "2.54cm", "12pt", "0.5in" — in points, or null if it is not one.
     * Percentages and unitless numbers are not lengths and come back null.
     */
    private fun lengthPt(value: String?): Float? {
        val text = value?.trim() ?: return null
        val unitAt = text.indexOfFirst { it.isLetter() || it == '%' }
        if (unitAt <= 0) return null
        val number = text.substring(0, unitAt).toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        val factor = when (text.substring(unitAt).lowercase()) {
            "pt" -> 1f
            "cm" -> 72f / 2.54f
            "mm" -> 72f / 25.4f
            "in", "inch" -> 72f
            "pc" -> 12f
            "px" -> 0.75f
            else -> return null
        }
        return number * factor
    }

    /** A page dimension: a length that is positive and not absurd, or null to keep the default. */
    private fun pagePt(value: String?): Float? = lengthPt(value)?.takeIf { it > 36f && it <= 14400f }

    private fun alignOf(value: String?): Align? = when (value) {
        "center" -> Align.CENTRE
        "end", "right" -> Align.RIGHT
        "justify" -> Align.JUSTIFY
        "start", "left" -> Align.LEFT
        else -> null
    }

    /** Not the document's visible content: comments, footnotes, deleted text, speaker notes. */
    private val SKIPPED = setOf(
        "office:annotation", "text:note", "text:tracked-changes", "presentation:notes",
        "office:forms", "text:sequence-decls", "table:shapes",
    )

    private val FRAMES = setOf("draw:frame", "draw:custom-shape", "draw:rect", "draw:ellipse")
    private val NUMERIC_TYPES = setOf("float", "percentage", "currency", "date", "time")

    private const val DEFAULT_NAME = "__default__"
    private const val MAX_STYLE_DEPTH = 8
    private const val MAX_SPACES = 1000
    private const val MAX_TABLE_COLUMNS = 64
    private const val MAX_BLOCKS = 200_000
    private const val MAX_COLUMNS = GridBuilder.MAX_COLUMNS
    private const val MAX_CELL_CHARS = 32_000
    private const val MAX_SLIDES = 2000
    private const val LIST_INDENT_PT = 18f
    private const val DEFAULT_IMAGE_PT = 144f
    private const val DEFAULT_COLUMN_PT = 64f

    /** LibreOffice's default 16:9 slide, 28 cm by 15.75 cm. */
    private const val DEFAULT_SLIDE_WIDTH_PT = 793.7f
    private const val DEFAULT_SLIDE_HEIGHT_PT = 446.5f
}
