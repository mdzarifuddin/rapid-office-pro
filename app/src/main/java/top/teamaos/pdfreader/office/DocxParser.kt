package top.teamaos.pdfreader.office

import android.graphics.Color
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlin.math.max

/**
 * Reading a .docx.
 *
 * A Word file's body is a flat list of paragraphs and tables, which is a much simpler thing than
 * Word's own layout engine suggests: the hard parts of Word are pagination and text shaping, and
 * both of those are [OfficeLayout]'s job here. This class is only concerned with what the file
 * says — what the words are, and what they look like.
 *
 * Styles are read first and applied underneath direct formatting, which is the order Word itself
 * resolves them in: a run that says nothing about its size inherits the paragraph style's, and
 * that is what makes headings come out as headings rather than as body text.
 */
object DocxParser {

    fun parse(file: File, displayName: String): OfficeDocument? {
        val pack = OoxmlPackage.open(file) ?: return null
        return pack.use { open ->
            val documentPart = mainPart(open) ?: return@use null
            val body = open.bytes(documentPart) ?: return@use null
            val styles = readStyles(open)
            val numbering = readNumbering(open)
            val relationships = open.relationships(documentPart)
            val reader = BodyReader(open, styles, numbering, relationships)
            OoxmlPackage.parse(body) { reader.onEvent(it) }
            reader.finish()

            val section = Section.Flow(
                title = displayName,
                blocks = reader.blocks,
                pageWidthPt = reader.pageWidthPt,
                pageHeightPt = reader.pageHeightPt,
                marginLeftPt = reader.marginLeftPt,
                marginRightPt = reader.marginRightPt,
                marginTopPt = reader.marginTopPt,
                marginBottomPt = reader.marginBottomPt,
            )
            OfficeDocument(
                kind = OfficeKind.WORD,
                displayName = displayName,
                sections = listOf(section),
                outline = reader.outline,
            )
        }
    }

    /** Normally `word/document.xml`, but the part name is only guaranteed by the content types. */
    private fun mainPart(pack: OoxmlPackage): String? {
        if (pack.exists("word/document.xml")) return "word/document.xml"
        return pack.names().firstOrNull { it.endsWith("/document.xml") || it == "document.xml" }
    }

    // ------------------------------------------------------------------ styles

    /** What a named style contributes, before any direct formatting on top. */
    private class NamedStyle(
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val sizePt: Float? = null,
        val colour: Int? = null,
        val align: Align? = null,
        val outlineLevel: Int? = null,
        val spaceBeforePt: Float? = null,
        val spaceAfterPt: Float? = null,
        val basedOn: String? = null,
        val name: String = "",
    )

    private fun readStyles(pack: OoxmlPackage): Map<String, NamedStyle> {
        val data = pack.bytes("word/styles.xml") ?: return emptyMap()
        val styles = mutableMapOf<String, NamedStyle>()
        var id: String? = null
        var name = ""
        var bold: Boolean? = null
        var italic: Boolean? = null
        var size: Float? = null
        var colour: Int? = null
        var align: Align? = null
        var outline: Int? = null
        var before: Float? = null
        var after: Float? = null
        var basedOn: String? = null

        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "style" -> {
                        id = OoxmlPackage.attr(parser, "styleId")
                        name = ""
                        bold = null; italic = null; size = null; colour = null
                        align = null; outline = null; before = null; after = null; basedOn = null
                    }
                    "name" -> name = OoxmlPackage.attr(parser, "val").orEmpty()
                    "basedOn" -> basedOn = OoxmlPackage.attr(parser, "val")
                    "b" -> bold = OoxmlPackage.isOn(OoxmlPackage.attr(parser, "val"))
                    "i" -> italic = OoxmlPackage.isOn(OoxmlPackage.attr(parser, "val"))
                    "sz" -> size = halfPoints(OoxmlPackage.attr(parser, "val"))
                    "color" -> colour = OoxmlPackage.attr(parser, "val")
                        ?.let { OoxmlPackage.colourOf(it, Color.BLACK) }
                    "jc" -> align = alignOf(OoxmlPackage.attr(parser, "val"))
                    "outlineLvl" -> outline = OoxmlPackage.attr(parser, "val")?.toIntOrNull()
                    "spacing" -> {
                        OoxmlPackage.attr(parser, "before")?.toFloatOrNull()
                            ?.let { before = it / OoxmlPackage.TWIPS_PER_POINT }
                        OoxmlPackage.attr(parser, "after")?.toFloatOrNull()
                            ?.let { after = it / OoxmlPackage.TWIPS_PER_POINT }
                    }
                }
                XmlPullParser.END_TAG -> if (OoxmlPackage.localName(parser.name) == "style") {
                    id?.let {
                        styles[it] = NamedStyle(
                            bold, italic, size, colour, align,
                            outline ?: headingLevelFromName(name),
                            before, after, basedOn, name,
                        )
                    }
                    id = null
                }
            }
        }
        return styles
    }

    /** Word marks headings with an outline level; files that do not still call them "heading 1". */
    private fun headingLevelFromName(name: String): Int? {
        val lower = name.lowercase()
        if (!lower.startsWith("heading")) return null
        return lower.removePrefix("heading").trim().toIntOrNull()?.minus(1)?.coerceIn(0, 8)
    }

    // ------------------------------------------------------------------ numbering

    /** numId to the format of each level, so a list comes out numbered rather than as "•" always. */
    private fun readNumbering(pack: OoxmlPackage): Map<String, Map<Int, String>> {
        val data = pack.bytes("word/numbering.xml") ?: return emptyMap()
        val abstractFormats = mutableMapOf<String, MutableMap<Int, String>>()
        val numToAbstract = mutableMapOf<String, String>()
        var abstractId: String? = null
        var numId: String? = null
        var level = 0

        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "abstractNum" -> abstractId = OoxmlPackage.attr(parser, "abstractNumId")
                    "num" -> numId = OoxmlPackage.attr(parser, "numId")
                    "abstractNumId" -> {
                        val target = OoxmlPackage.attr(parser, "val")
                        if (numId != null && target != null) numToAbstract[numId!!] = target
                    }
                    "lvl" -> level = OoxmlPackage.attr(parser, "ilvl")?.toIntOrNull() ?: 0
                    "numFmt" -> {
                        val format = OoxmlPackage.attr(parser, "val") ?: "bullet"
                        abstractId?.let {
                            abstractFormats.getOrPut(it) { mutableMapOf() }[level] = format
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "abstractNum" -> abstractId = null
                    "num" -> numId = null
                }
            }
        }
        return numToAbstract.mapValues { (_, abstract) -> abstractFormats[abstract] ?: emptyMap() }
    }

    // ------------------------------------------------------------------ the body

    /**
     * Walks the document body once, building blocks as it goes.
     *
     * Written as a small state machine rather than recursion because the parser is a pull parser
     * and the body is deeply but simply nested: a table holds rows, which hold cells, which hold
     * paragraphs, and nothing else nests at all.
     */
    private class BodyReader(
        private val pack: OoxmlPackage,
        private val styles: Map<String, NamedStyle>,
        private val numbering: Map<String, Map<Int, String>>,
        private val relationships: Map<String, String>,
    ) {
        val blocks = mutableListOf<Block>()
        val outline = mutableListOf<OutlineEntry>()

        var pageWidthPt = Section.LETTER_WIDTH_PT
        var pageHeightPt = Section.LETTER_HEIGHT_PT
        var marginLeftPt = 72f
        var marginRightPt = 72f
        var marginTopPt = 72f
        var marginBottomPt = 72f

        private var spans = mutableListOf<TextSpan>()
        private var runText = StringBuilder()

        // Paragraph properties, reset for every w:p.
        private var styleId: String? = null
        private var align: Align? = null
        private var indentPt = 0f
        private var spaceBefore: Float? = null
        private var spaceAfter: Float? = null
        private var lineSpacing = 1f
        private var numId: String? = null
        private var listLevel = 0
        private var paragraphBreakBefore = false

        // Run properties, reset for every w:r.
        private var runBold: Boolean? = null
        private var runItalic: Boolean? = null
        private var runUnderline = false
        private var runStrike = false
        private var runSize: Float? = null
        private var runColour: Int? = null
        private var runHighlight = 0
        private var runScript = 0
        private var runMono = false

        private var inTable = 0
        private var tableRows = mutableListOf<TableRow>()
        private var rowCells = mutableListOf<TableCell>()
        private var cellParagraphs = mutableListOf<Paragraph>()
        private var cellSpan = 1
        private var cellBackground = 0
        private var tableColumnWidths: MutableList<Float>? = null
        private var inDeletedText = false

        /** Running numbers for ordered lists, keyed by numId and level. */
        private val counters = mutableMapOf<String, Int>()

        fun onEvent(parser: XmlPullParser) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> start(parser)
                // Only what is inside a <w:t> is text. Everything between tags is indentation in
                // the XML, and appending that would pad every paragraph with the file's own layout.
                XmlPullParser.TEXT -> if (inText && !inDeletedText) runText.append(parser.text)
                XmlPullParser.END_TAG -> end(parser)
            }
        }

        private fun start(parser: XmlPullParser) {
            when (OoxmlPackage.localName(parser.name)) {
                "p" -> beginParagraph()
                "pStyle" -> styleId = OoxmlPackage.attr(parser, "val")
                "jc" -> align = alignOf(OoxmlPackage.attr(parser, "val"))
                "ind" -> {
                    OoxmlPackage.attr(parser, "left")?.toFloatOrNull()
                        ?.let { indentPt = max(0f, it / OoxmlPackage.TWIPS_PER_POINT) }
                    OoxmlPackage.attr(parser, "start")?.toFloatOrNull()
                        ?.let { indentPt = max(0f, it / OoxmlPackage.TWIPS_PER_POINT) }
                }
                "spacing" -> {
                    OoxmlPackage.attr(parser, "before")?.toFloatOrNull()
                        ?.let { spaceBefore = it / OoxmlPackage.TWIPS_PER_POINT }
                    OoxmlPackage.attr(parser, "after")?.toFloatOrNull()
                        ?.let { spaceAfter = it / OoxmlPackage.TWIPS_PER_POINT }
                    OoxmlPackage.attr(parser, "line")?.toFloatOrNull()?.let {
                        // Word's line spacing is in 240ths of a line when the rule is "auto".
                        val rule = OoxmlPackage.attr(parser, "lineRule")
                        if (rule == null || rule == "auto") lineSpacing = (it / 240f).coerceIn(0.6f, 3f)
                    }
                }
                "numPr" -> numId = null
                "ilvl" -> listLevel = OoxmlPackage.attr(parser, "val")?.toIntOrNull() ?: 0
                "numId" -> numId = OoxmlPackage.attr(parser, "val")

                "r" -> beginRun()
                "t" -> inText = true
                "b" -> if (inRunProperties) runBold = OoxmlPackage.isOn(OoxmlPackage.attr(parser, "val"))
                "i" -> if (inRunProperties) runItalic = OoxmlPackage.isOn(OoxmlPackage.attr(parser, "val"))
                "u" -> if (inRunProperties) {
                    runUnderline = OoxmlPackage.attr(parser, "val").let { it == null || it != "none" }
                }
                "strike" -> if (inRunProperties) runStrike = OoxmlPackage.isOn(OoxmlPackage.attr(parser, "val"))
                "sz" -> if (inRunProperties) runSize = halfPoints(OoxmlPackage.attr(parser, "val"))
                "color" -> if (inRunProperties) {
                    runColour = OoxmlPackage.attr(parser, "val")?.let { OoxmlPackage.colourOf(it, Color.BLACK) }
                }
                "highlight" -> if (inRunProperties) {
                    runHighlight = OoxmlPackage.highlightOf(OoxmlPackage.attr(parser, "val"))
                }
                "vertAlign" -> if (inRunProperties) {
                    runScript = when (OoxmlPackage.attr(parser, "val")) {
                        "superscript" -> 1
                        "subscript" -> -1
                        else -> 0
                    }
                }
                "rFonts" -> if (inRunProperties) {
                    val font = OoxmlPackage.attr(parser, "ascii").orEmpty()
                    runMono = font.contains("Courier", true) || font.contains("Consol", true) ||
                        font.contains("Mono", true)
                }
                "rPr" -> inRunProperties = true
                "del" -> inDeletedText = true
                "tab" -> runText.append('\t')
                "br" -> {
                    if (OoxmlPackage.attr(parser, "type") == "page") paragraphBreakBefore = true
                    runText.append('\n')
                }
                "cr" -> runText.append('\n')

                "tbl" -> {
                    inTable++
                    if (inTable == 1) {
                        tableRows = mutableListOf()
                        tableColumnWidths = null
                    }
                }
                "gridCol" -> if (inTable == 1) {
                    val width = OoxmlPackage.attr(parser, "w")?.toFloatOrNull()
                    if (width != null) {
                        val list = tableColumnWidths ?: mutableListOf<Float>().also { tableColumnWidths = it }
                        list += width / OoxmlPackage.TWIPS_PER_POINT
                    }
                }
                "tr" -> if (inTable == 1) rowCells = mutableListOf()
                "tc" -> if (inTable == 1) {
                    cellParagraphs = mutableListOf()
                    cellSpan = 1
                    cellBackground = 0
                }
                "gridSpan" -> if (inTable == 1) {
                    cellSpan = OoxmlPackage.attr(parser, "val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                }
                "shd" -> {
                    val fill = OoxmlPackage.attr(parser, "fill")
                    if (fill != null && !fill.equals("auto", true)) {
                        cellBackground = OoxmlPackage.colourOf(fill, 0)
                    }
                }

                "drawing", "pict" -> Unit
                "extent" -> {
                    pendingImageWidth = OoxmlPackage.attr(parser, "cx")?.toFloatOrNull()
                        ?.div(OoxmlPackage.EMU_PER_POINT)
                    pendingImageHeight = OoxmlPackage.attr(parser, "cy")?.toFloatOrNull()
                        ?.div(OoxmlPackage.EMU_PER_POINT)
                }
                "blip" -> {
                    val id = OoxmlPackage.attr(parser, "embed") ?: OoxmlPackage.attr(parser, "link")
                    pendingImageId = id
                }

                "pgSz" -> {
                    OoxmlPackage.attr(parser, "w")?.toFloatOrNull()
                        ?.let { pageWidthPt = it / OoxmlPackage.TWIPS_PER_POINT }
                    OoxmlPackage.attr(parser, "h")?.toFloatOrNull()
                        ?.let { pageHeightPt = it / OoxmlPackage.TWIPS_PER_POINT }
                }
                "pgMar" -> {
                    OoxmlPackage.attr(parser, "left")?.toFloatOrNull()
                        ?.let { marginLeftPt = it / OoxmlPackage.TWIPS_PER_POINT }
                    OoxmlPackage.attr(parser, "right")?.toFloatOrNull()
                        ?.let { marginRightPt = it / OoxmlPackage.TWIPS_PER_POINT }
                    OoxmlPackage.attr(parser, "top")?.toFloatOrNull()
                        ?.let { marginTopPt = it / OoxmlPackage.TWIPS_PER_POINT }
                    OoxmlPackage.attr(parser, "bottom")?.toFloatOrNull()
                        ?.let { marginBottomPt = it / OoxmlPackage.TWIPS_PER_POINT }
                }
            }
        }

        private var inRunProperties = false
        private var inText = false
        private var pendingImageId: String? = null
        private var pendingImageWidth: Float? = null
        private var pendingImageHeight: Float? = null

        private fun end(parser: XmlPullParser) {
            when (OoxmlPackage.localName(parser.name)) {
                "rPr" -> inRunProperties = false
                "del" -> inDeletedText = false
                "t" -> inText = false
                "r" -> endRun()
                "p" -> endParagraph()
                "drawing", "pict" -> placeImage()
                "tc" -> if (inTable == 1) {
                    rowCells += TableCell(
                        cellParagraphs.ifEmpty { listOf(Paragraph(emptyList(), spaceAfterPt = 0f)) },
                        cellBackground,
                        cellSpan,
                    )
                }
                "tr" -> if (inTable == 1 && rowCells.isNotEmpty()) tableRows += TableRow(rowCells)
                "tbl" -> {
                    inTable--
                    if (inTable == 0 && tableRows.isNotEmpty()) {
                        blocks += Block.Table(tableRows, tableColumnWidths)
                        tableRows = mutableListOf()
                    }
                }
            }
        }

        private fun beginParagraph() {
            styleId = null
            align = null
            indentPt = 0f
            spaceBefore = null
            spaceAfter = null
            lineSpacing = 1f
            numId = null
            listLevel = 0
            spans = mutableListOf()
        }

        private fun beginRun() {
            runText = StringBuilder()
            runBold = null; runItalic = null
            runUnderline = false; runStrike = false
            runSize = null; runColour = null
            runHighlight = 0; runScript = 0; runMono = false
        }

        private fun endRun() {
            val text = runText.toString()
            runText = StringBuilder()
            if (text.isEmpty()) return
            val style = resolvedStyle()
            spans += TextSpan(
                text = text,
                bold = runBold ?: style?.bold ?: false,
                italic = runItalic ?: style?.italic ?: false,
                underline = runUnderline,
                strike = runStrike,
                sizePt = runSize ?: style?.sizePt ?: TextSpan.DEFAULT_SIZE_PT,
                colour = runColour ?: style?.colour ?: Color.BLACK,
                monospace = runMono,
                highlight = runHighlight,
                script = runScript,
            )
        }

        /** Follow basedOn so a style built on Heading 1 still comes out looking like a heading. */
        private fun resolvedStyle(): NamedStyle? {
            var current = styles[styleId ?: return null] ?: return null
            // The style's own name, kept before the walk up the basedOn chain replaces `current`:
            // a list style based on Normal would otherwise come back calling itself Normal.
            val ownName = current.name
            var bold = current.bold
            var italic = current.italic
            var size = current.sizePt
            var colour = current.colour
            var align = current.align
            var outline = current.outlineLevel
            var before = current.spaceBeforePt
            var after = current.spaceAfterPt
            var depth = 0
            while (current.basedOn != null && depth < MAX_STYLE_DEPTH) {
                current = styles[current.basedOn] ?: break
                bold = bold ?: current.bold
                italic = italic ?: current.italic
                size = size ?: current.sizePt
                colour = colour ?: current.colour
                align = align ?: current.align
                outline = outline ?: current.outlineLevel
                before = before ?: current.spaceBeforePt
                after = after ?: current.spaceAfterPt
                depth++
            }
            return NamedStyle(bold, italic, size, colour, align, outline, before, after, null, ownName)
        }

        /**
         * A paragraph is only ever emitted here, at its closing tag.
         *
         * An empty one is kept: in Word a bare paragraph is a blank line, and dropping them turns
         * a document into one unbroken slab of text.
         */
        private fun endParagraph() {
            endRun()
            val style = resolvedStyle()
            val heading = style?.outlineLevel
            val marker = markerFor()
            val paragraph = Paragraph(
                spans = spans.toList(),
                align = align ?: style?.align ?: Align.LEFT,
                indentPt = indentPt + listLevel * LIST_INDENT_PT +
                    if (indentPt == 0f && marker != null) LIST_INDENT_PT else 0f,
                spaceBeforePt = spaceBefore ?: style?.spaceBeforePt ?: 0f,
                spaceAfterPt = spaceAfter ?: style?.spaceAfterPt ?: DEFAULT_SPACE_AFTER_PT,
                lineSpacing = lineSpacing,
                marker = marker,
                ruleBelow = heading != null && heading <= 1 && spans.isNotEmpty(),
            )
            if (paragraphBreakBefore) {
                paragraphBreakBefore = false
                if (inTable == 0) blocks += Block.PageBreak
            }
            if (inTable > 0) {
                cellParagraphs += paragraph
            } else {
                if (heading != null && paragraph.plainText.isNotBlank()) {
                    outline += OutlineEntry(paragraph.plainText.trim(), 0, heading)
                }
                blocks += Block.Text(paragraph)
            }
            spans = mutableListOf()
        }

        /**
         * The bullet or number in front of a list item, with ordered lists counted as we go.
         *
         * A list item does not have to say it is one. Word puts the numbering on the paragraph for
         * a list made by hand, and only in the style for a list made by picking "List Bullet" from
         * the gallery — and a document written the second way came out as unmarked paragraphs
         * until the style name was consulted as well.
         */
        private fun markerFor(): String? {
            val styleName = resolvedStyle()?.name?.lowercase().orEmpty()
            val id = numId ?: return when {
                styleName.startsWith("list number") -> numberFromStyle()
                styleName.startsWith("list bullet") || styleName.startsWith("list paragraph") ->
                    bulletForLevel()
                else -> null
            }
            val format = numbering[id]?.get(listLevel) ?: "bullet"
            if (format == "bullet" || format == "none") return bulletForLevel()
            val key = "$id/$listLevel"
            val next = (counters[key] ?: 0) + 1
            counters[key] = next
            return when (format) {
                "lowerLetter" -> "${('a' + (next - 1) % 26)}."
                "upperLetter" -> "${('A' + (next - 1) % 26)}."
                "lowerRoman" -> roman(next).lowercase() + "."
                "upperRoman" -> roman(next) + "."
                else -> "$next."
            }
        }

        private fun bulletForLevel(): String = when (listLevel % 3) {
            0 -> "•"
            1 -> "◦"
            else -> "▪"
        }

        /** A style-driven numbered list has nothing to count from, so it counts by style name. */
        private fun numberFromStyle(): String {
            val key = "style/$listLevel"
            val next = (counters[key] ?: 0) + 1
            counters[key] = next
            return "$next."
        }

        private fun placeImage() {
            val id = pendingImageId
            val width = pendingImageWidth
            val height = pendingImageHeight
            pendingImageId = null
            pendingImageWidth = null
            pendingImageHeight = null
            if (id == null) return
            val target = relationships[id] ?: return
            val data = pack.bytes(target) ?: return
            val block = Block.Picture(
                data,
                width ?: DEFAULT_IMAGE_PT,
                height ?: DEFAULT_IMAGE_PT,
            )
            if (inTable > 0) return else blocks += block
        }

        fun finish() {
            endRun()
            if (spans.isNotEmpty()) endParagraph()
        }
    }

    private fun roman(value: Int): String {
        val numerals = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
            100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
            10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )
        var remaining = value
        val text = StringBuilder()
        numerals.forEach { (number, symbol) ->
            while (remaining >= number) {
                text.append(symbol)
                remaining -= number
            }
        }
        return text.toString()
    }

    private fun alignOf(value: String?): Align? = when (value) {
        "center", "centre" -> Align.CENTRE
        "right", "end" -> Align.RIGHT
        "both", "distribute" -> Align.JUSTIFY
        "left", "start" -> Align.LEFT
        else -> null
    }

    /** Word stores text size in half-points. */
    private fun halfPoints(value: String?): Float? =
        value?.toFloatOrNull()?.div(2f)?.takeIf { it > 0.5f && it < 400f }

    private const val LIST_INDENT_PT = 18f
    private const val DEFAULT_SPACE_AFTER_PT = 6f
    private const val DEFAULT_IMAGE_PT = 144f
    private const val MAX_STYLE_DEPTH = 8
}
