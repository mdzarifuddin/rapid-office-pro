package top.teamaos.pdfreader.office

import android.graphics.BitmapFactory

/**
 * The HTML reading shared by the e-book formats: EPUB chapters and Mobipocket / KF8 text.
 *
 * It is [HtmlParser]'s tolerant tokenizer and paragraph builder, with the three things an e-book
 * needs that a saved web page does not: pictures (`<img>`, and the `<image>` an SVG cover wraps),
 * Mobipocket's `<mbp:pagebreak/>`, and one text budget shared by every chapter, so a book of a
 * thousand short chapters is capped as a whole rather than chapter by chapter.
 *
 * HtmlParser keeps its builder private and is being worked on separately, so the logic lives here
 * as its own copy rather than as a change to it.
 */
internal object EbookHtml {

    /** Text and paragraph allowance for a whole book, spent by every chapter read through it. */
    class Budget(val maxChars: Int = MAX_CHARS, val maxParagraphs: Int = MAX_PARAGRAPHS) {
        var chars = 0
        var paragraphs = 0
        var truncated = false
        val full: Boolean get() = chars >= maxChars || paragraphs >= maxParagraphs
    }

    /** A heading met while reading, and the index of the block it became. */
    data class Heading(val title: String, val level: Int, val blockIndex: Int)

    class Result(val blocks: List<Block>, val headings: List<Heading>)

    /**
     * Turns an image reference into a picture, or null to leave it out.
     *
     * [source] is the `src` / `href` as written; [tag] is the whole tag body, for formats that keep
     * the reference in an attribute of their own (Mobipocket's `recindex`).
     */
    fun interface ImageResolver {
        fun resolve(source: String?, tag: String): Block.Picture?
    }

    fun read(html: String, budget: Budget, images: ImageResolver?): Result {
        val builder = Builder(budget, images)
        tokenize(html, builder)
        builder.finish()
        return Result(builder.blocks, builder.headings)
    }

    /**
     * A picture block for encoded image [data], sized from the tag's width and height when they are
     * plain pixel numbers and from the image itself otherwise. Null when Android cannot decode it.
     */
    fun picture(data: ByteArray, widthAttribute: String?, heightAttribute: String?): Block.Picture? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, options) }
        val naturalWidth = options.outWidth
        val naturalHeight = options.outHeight
        if (naturalWidth <= 0 || naturalHeight <= 0) return null
        // Spacer GIFs and tracking pixels are not pictures anyone wants to see.
        if (naturalWidth < MIN_PICTURE_PX && naturalHeight < MIN_PICTURE_PX) return null
        val width = pixels(widthAttribute)
        val height = pixels(heightAttribute)
        val (pxWidth, pxHeight) = when {
            width != null && height != null -> width to height
            width != null -> width to width * naturalHeight / naturalWidth
            height != null -> height * naturalWidth / naturalHeight to height
            else -> naturalWidth.toFloat() to naturalHeight.toFloat()
        }
        if (!(pxWidth > 0f && pxHeight > 0f) || !pxWidth.isFinite() || !pxHeight.isFinite()) return null
        return Block.Picture(data, pxWidth * POINTS_PER_PIXEL, pxHeight * POINTS_PER_PIXEL)
    }

    /** "300" or "300px"; percentages and ems say nothing about pixels, so they are ignored. */
    private fun pixels(value: String?): Float? {
        val text = value?.trim()?.removeSuffix("px")?.trim() ?: return null
        return text.toFloatOrNull()?.takeIf { it.isFinite() && it >= 1f && it <= MAX_PICTURE_PX }
    }

    // ------------------------------------------------------------------ tokens

    private fun tokenize(html: String, into: Builder) {
        var index = 0
        val length = html.length
        while (index < length && !into.full) {
            val open = html.indexOf('<', index)
            if (open < 0) {
                into.text(HtmlParser.decodeEntities(html.substring(index)))
                break
            }
            if (open > index) into.text(HtmlParser.decodeEntities(html.substring(index, open)))
            when {
                html.startsWith("<!--", open) -> {
                    val close = html.indexOf("-->", open + 4)
                    index = if (close < 0) length else close + 3
                }
                html.startsWith("<![CDATA[", open) -> {
                    val close = html.indexOf("]]>", open + 9)
                    val end = if (close < 0) length else close
                    into.text(html.substring(open + 9, end))
                    index = if (close < 0) length else close + 3
                }
                html.startsWith("<!", open) || html.startsWith("<?", open) -> {
                    val close = html.indexOf('>', open)
                    index = if (close < 0) length else close + 1
                }
                else -> {
                    val close = tagEnd(html, open)
                    if (close < 0) {
                        into.text("<")
                        index = open + 1
                        continue
                    }
                    val body = html.substring(open + 1, close)
                    index = close + 1
                    val closing = body.startsWith("/")
                    val name = body.removePrefix("/").takeWhile { it.isLetterOrDigit() || it == ':' || it == '-' }
                        .lowercase().substringAfter(':')
                    if (name.isEmpty()) {
                        into.text(HtmlParser.decodeEntities("<$body>"))
                        continue
                    }
                    if (closing) {
                        into.end(name)
                    } else {
                        into.start(name, body)
                        if (name in RAW_TEXT && !body.endsWith("/")) {
                            val end = html.indexOf("</$name", index, ignoreCase = true)
                            index = if (end < 0) length else end
                        }
                    }
                }
            }
        }
    }

    private fun tagEnd(html: String, open: Int): Int {
        if (open + 1 >= html.length) return -1
        val next = html[open + 1]
        if (!(next.isLetter() || next == '/')) return -1
        var quote = 0.toChar()
        var index = open + 1
        while (index < html.length) {
            val character = html[index]
            when {
                quote != 0.toChar() -> if (character == quote) quote = 0.toChar()
                character == '"' || character == '\'' -> quote = character
                character == '>' -> return index
            }
            index++
        }
        return -1
    }

    /** The value of attribute [name] in a tag body, matching `xlink:href` when asked for `href`. */
    fun attribute(body: String, name: String): String? {
        val match = Regex("""(?i)(?:^|[\s:])$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""").find(body) ?: return null
        return match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }?.let(HtmlParser::decodeEntities)
    }

    // ------------------------------------------------------------------ building

    private class Builder(private val budget: Budget, private val images: ImageResolver?) {
        val blocks = mutableListOf<Block>()
        val headings = mutableListOf<Heading>()
        val full: Boolean get() = budget.full

        private val spans = mutableListOf<TextSpan>()
        private val run = StringBuilder()
        private var lastWasSpace = true

        private var skipping = 0
        private var bold = 0
        private var italic = 0
        private var underline = 0
        private var strike = 0
        private var mono = 0
        private var pre = 0
        private var heading = 0
        private var quoteDepth = 0
        private var superscript = 0
        private var subscript = 0
        private var align: Align? = null

        private class ListLevel(val ordered: Boolean) {
            var counter = 0
        }

        private val lists = ArrayList<ListLevel>()
        private var markerPending: String? = null

        private var tableDepth = 0
        private var rows = mutableListOf<TableRow>()
        private var cells: MutableList<TableCell>? = null
        private var cellParagraphs: MutableList<Paragraph>? = null
        private var cellSpan = 1
        private var cellIsHeader = false

        fun start(name: String, body: String) {
            if (name == "body") skipping = 0
            // KF8 text is one XHTML document after another; each new one is a new chapter.
            if (name == "html") {
                skipping = 0
                pageBreak()
                return
            }
            if (skipping > 0) {
                // An SVG cover page is an <svg> wrapping one <image>: the picture is the page.
                if (name == "image") picture(body)
                if (name in SKIPPED && !body.endsWith("/")) skipping++
                return
            }
            if (name in SKIPPED) {
                if (!body.endsWith("/") && name !in VOID) skipping++
                return
            }
            when (name) {
                "br" -> {
                    run.append('\n')
                    lastWasSpace = true
                }
                "hr" -> {
                    endParagraph()
                    emit(Paragraph(emptyList(), spaceAfterPt = 6f, ruleBelow = true), null)
                }
                "img", "image" -> picture(body)
                "pagebreak" -> pageBreak()
                "b", "strong" -> { endRun(); bold++ }
                "i", "em", "cite", "var", "dfn" -> { endRun(); italic++ }
                "u", "ins" -> { endRun(); underline++ }
                "s", "strike", "del" -> { endRun(); strike++ }
                "sup" -> { endRun(); superscript++ }
                "sub" -> { endRun(); subscript++ }
                "code", "tt", "kbd", "samp" -> { endRun(); mono++ }
                "pre" -> { endParagraph(); pre++; mono++ }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    endParagraph()
                    heading = name[1] - '0'
                }
                "blockquote" -> { endParagraph(); quoteDepth++ }
                "ul", "ol", "menu", "dl" -> {
                    endParagraph()
                    lists += ListLevel(ordered = name == "ol")
                }
                "li" -> {
                    endParagraph()
                    val list = lists.lastOrNull()
                    if (list != null) {
                        list.counter++
                        markerPending = if (list.ordered) "${list.counter}." else BULLETS[(lists.size - 1) % 3]
                    } else {
                        markerPending = "•"
                    }
                }
                "table" -> {
                    endParagraph()
                    tableDepth++
                    if (tableDepth == 1) {
                        rows = mutableListOf()
                        cells = null
                        cellParagraphs = null
                    }
                }
                "tr" -> if (tableDepth == 1) {
                    endCell()
                    endRow()
                    cells = mutableListOf()
                }
                "td", "th" -> if (tableDepth == 1) {
                    endCell()
                    if (cells == null) cells = mutableListOf()
                    cellParagraphs = mutableListOf()
                    cellSpan = attribute(body, "colspan")?.trim()?.toIntOrNull()?.coerceIn(1, MAX_SPAN) ?: 1
                    cellIsHeader = name == "th"
                    if (cellIsHeader) bold++
                } else {
                    endParagraph()
                }
                else -> if (name in BLOCKS) {
                    endParagraph()
                    if (name == "center") align = Align.CENTRE
                    attribute(body, "align")?.let { value ->
                        align = when (value.lowercase()) {
                            "center" -> Align.CENTRE
                            "right" -> Align.RIGHT
                            "justify" -> Align.JUSTIFY
                            else -> null
                        }
                    }
                }
            }
        }

        fun end(name: String) {
            if (skipping > 0) {
                if (name in SKIPPED) skipping--
                return
            }
            when (name) {
                "b", "strong" -> { endRun(); bold = (bold - 1).coerceAtLeast(0) }
                "i", "em", "cite", "var", "dfn" -> { endRun(); italic = (italic - 1).coerceAtLeast(0) }
                "u", "ins" -> { endRun(); underline = (underline - 1).coerceAtLeast(0) }
                "s", "strike", "del" -> { endRun(); strike = (strike - 1).coerceAtLeast(0) }
                "sup" -> { endRun(); superscript = (superscript - 1).coerceAtLeast(0) }
                "sub" -> { endRun(); subscript = (subscript - 1).coerceAtLeast(0) }
                "code", "tt", "kbd", "samp" -> { endRun(); mono = (mono - 1).coerceAtLeast(0) }
                "pre" -> {
                    endParagraph()
                    pre = (pre - 1).coerceAtLeast(0)
                    mono = (mono - 1).coerceAtLeast(0)
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    endParagraph()
                    heading = 0
                }
                "blockquote" -> { endParagraph(); quoteDepth = (quoteDepth - 1).coerceAtLeast(0) }
                "ul", "ol", "menu", "dl" -> {
                    endParagraph()
                    lists.removeLastOrNull()
                }
                "li" -> endParagraph()
                "td", "th" -> if (tableDepth == 1) endCell() else endParagraph()
                "tr" -> if (tableDepth == 1) {
                    endCell()
                    endRow()
                }
                "table" -> {
                    endParagraph()
                    if (tableDepth == 1) {
                        endCell()
                        endRow()
                        if (rows.isNotEmpty()) blocks += Block.Table(rows, null)
                        rows = mutableListOf()
                    }
                    tableDepth = (tableDepth - 1).coerceAtLeast(0)
                }
                else -> if (name in BLOCKS) {
                    endParagraph()
                    align = null
                }
            }
        }

        fun text(value: String) {
            if (skipping > 0 || value.isEmpty() || full) return
            if (pre > 0) {
                run.append(value.replace("\r\n", "\n"))
                lastWasSpace = value.last().isWhitespace()
                return
            }
            for (character in value) {
                if (character.isWhitespace() && character != '\u00A0') {
                    if (!lastWasSpace) {
                        run.append(' ')
                        lastWasSpace = true
                    }
                } else if (character == '\u00A0') {
                    // A non-breaking space is deliberate spacing; keep it as written.
                    run.append(character)
                    lastWasSpace = false
                } else {
                    run.append(character)
                    lastWasSpace = false
                }
            }
        }

        private fun pageBreak() {
            endParagraph()
            if (tableDepth == 0 && blocks.isNotEmpty() && blocks.last() !== Block.PageBreak) {
                blocks += Block.PageBreak
            }
        }

        /** Pictures go between paragraphs; one inside a table cell is left out, as Word's are. */
        private fun picture(body: String) {
            val resolver = images ?: return
            if (tableDepth > 0 || full) return
            val source = attribute(body, "src") ?: attribute(body, "href")
            val block = runCatching { resolver.resolve(source, body) }.getOrNull() ?: return
            endParagraph()
            blocks += block
        }

        private fun endRun() {
            if (run.isEmpty()) return
            spans += TextSpan(
                text = run.toString(),
                bold = bold > 0 || heading > 0,
                italic = italic > 0,
                underline = underline > 0,
                strike = strike > 0,
                monospace = mono > 0,
                script = when {
                    superscript > 0 -> 1
                    subscript > 0 -> -1
                    else -> 0
                },
                sizePt = when {
                    heading > 0 -> HEADING_SIZES[heading - 1]
                    mono > 0 -> 10f
                    else -> TextSpan.DEFAULT_SIZE_PT
                },
            )
            run.setLength(0)
        }

        private fun endParagraph() {
            endRun()
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.size - 1)
            while (spans.isNotEmpty() && spans.first().text.isBlank()) spans.removeAt(0)
            if (spans.isEmpty()) {
                lastWasSpace = true
                return
            }
            if (pre == 0) {
                spans[spans.size - 1] = spans.last().copy(text = spans.last().text.trimEnd())
                spans[0] = spans.first().copy(text = spans.first().text.trimStart())
            }
            val level = heading.takeIf { it > 0 }
            emit(
                Paragraph(
                    spans = spans.toList(),
                    align = align ?: Align.LEFT,
                    indentPt = (lists.size + quoteDepth).coerceAtMost(MAX_INDENT_LEVELS) * INDENT_PT,
                    spaceBeforePt = if (level != null) 8f else 0f,
                    spaceAfterPt = if (pre > 0) 0f else 6f,
                    marker = markerPending,
                    ruleBelow = level != null && level <= 1,
                ),
                level,
            )
            markerPending = null
            spans.clear()
            lastWasSpace = true
        }

        private fun emit(paragraph: Paragraph, level: Int?) {
            if (full) {
                budget.truncated = true
                return
            }
            budget.paragraphs++
            budget.chars += paragraph.spans.sumOf { it.text.length }
            if (budget.full) budget.truncated = true
            val cell = cellParagraphs
            if (tableDepth > 0 && cell != null) {
                cell += paragraph
            } else {
                if (level != null) {
                    val title = paragraph.plainText.trim().replace('\n', ' ')
                    if (title.isNotEmpty()) headings += Heading(title.take(MAX_TITLE), level - 1, blocks.size)
                }
                blocks += Block.Text(paragraph)
            }
        }

        private fun endCell() {
            val paragraphs = cellParagraphs ?: return
            endParagraph()
            if (cellIsHeader) bold = (bold - 1).coerceAtLeast(0)
            cellIsHeader = false
            (cells ?: mutableListOf<TableCell>().also { cells = it }) += TableCell(
                paragraphs.toList().ifEmpty { listOf(Paragraph(emptyList(), spaceAfterPt = 0f)) },
                columnSpan = cellSpan,
            )
            cellParagraphs = null
        }

        private fun endRow() {
            val row = cells ?: return
            if (row.isNotEmpty()) rows += TableRow(row.toList())
            cells = null
        }

        fun finish() {
            if (tableDepth > 0) {
                endCell()
                endRow()
                if (rows.isNotEmpty()) blocks += Block.Table(rows, null)
                tableDepth = 0
            }
            endParagraph()
            while (blocks.isNotEmpty() && blocks.last() === Block.PageBreak) blocks.removeAt(blocks.size - 1)
        }
    }

    /**
     * Cut [blocks] into sections at their page breaks, each titled from its first heading, with the
     * headings turned into contents entries pointing at the section they fell in. For Mobipocket,
     * whose one long text marks chapters only with `<mbp:pagebreak/>`.
     */
    fun splitAtPageBreaks(
        result: Result,
        fallbackTitle: (Int) -> String,
        maxSections: Int = MAX_SECTIONS,
    ): Pair<List<Section.Flow>, List<OutlineEntry>> {
        val sections = mutableListOf<Section.Flow>()
        val outline = mutableListOf<OutlineEntry>()
        var current = mutableListOf<Block>()
        var currentHeadings = mutableListOf<Heading>()
        var headingIndex = 0

        fun close() {
            if (current.none { it !== Block.PageBreak }) return
            val index = sections.size
            val title = currentHeadings.firstOrNull()?.title ?: fallbackTitle(index)
            sections += Section.Flow(title, current.toList())
            currentHeadings.forEach { outline += OutlineEntry(it.title, index, it.level) }
            current = mutableListOf()
            currentHeadings = mutableListOf()
        }

        result.blocks.forEachIndexed { index, block ->
            while (headingIndex < result.headings.size && result.headings[headingIndex].blockIndex == index) {
                currentHeadings += result.headings[headingIndex++]
            }
            // Past the section allowance the rest of the book stays in the last section.
            if (block === Block.PageBreak && sections.size < maxSections - 1) close() else current += block
        }
        close()
        return sections to outline
    }

    private val SKIPPED = setOf(
        "head", "script", "style", "noscript", "template", "svg", "math", "iframe", "object",
        "canvas", "select", "button", "title", "nav",
    )
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")
    private val VOID = setOf("img", "br", "hr", "input", "meta", "link", "source", "wbr", "image", "pagebreak")

    private val BLOCKS = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside", "address",
        "figure", "figcaption", "dt", "dd", "center", "caption", "form", "fieldset", "legend",
        "details", "summary", "body", "html",
    )

    private val BULLETS = listOf("•", "◦", "▪")
    private val HEADING_SIZES = floatArrayOf(22f, 18f, 15f, 13f, 12f, 11f)
    private const val INDENT_PT = 18f
    private const val MAX_INDENT_LEVELS = 8
    private const val MAX_SPAN = 64
    private const val MAX_TITLE = 200

    /** The same allowance PlainTextParser lays out: four million characters. */
    const val MAX_CHARS = 4 * 1024 * 1024
    const val MAX_PARAGRAPHS = 100_000
    const val MAX_SECTIONS = 2000

    /** E-books are made for 96 dpi screens; a CSS pixel is three quarters of a point. */
    private const val POINTS_PER_PIXEL = 0.75f
    private const val MIN_PICTURE_PX = 4
    private const val MAX_PICTURE_PX = 20000f

    const val TRUNCATED_NOTE = "This book is very long; only the first part of it is shown."
}
