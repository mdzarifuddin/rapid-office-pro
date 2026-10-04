package top.teamaos.pdfreader.office

import java.io.File

/**
 * Reading a saved web page or an HTML export: .html, .htm, .xhtml.
 *
 * HTML is not XML — tags are left open, attributes go unquoted, `<br>` never closes — so the XML
 * pull parser the Office readers use would give up on most real pages. This is a small tolerant
 * tokenizer instead, and it only cares about the structure a reader needs: headings, paragraphs,
 * lists, tables, emphasis. Scripts, styles and everything in `<head>` are skipped, because what
 * someone opening a page wants is what it says, not how it was built.
 */
object HtmlParser {

    fun parse(file: File, displayName: String): OfficeDocument? {
        val text = PlainTextParser.readText(file) ?: return null
        return parse(text, displayName)
    }

    fun parse(html: String, displayName: String): OfficeDocument? {
        val builder = Builder()
        tokenize(html, builder)
        builder.finish()
        if (builder.blocks.isEmpty()) return null
        return OfficeDocument(
            kind = OfficeKind.WORD,
            displayName = displayName,
            sections = listOf(Section.Flow(displayName, builder.blocks)),
            outline = builder.outline,
            limitationNote = "Web pages are shown as text, with headings, lists and tables; " +
                "the page's own styling and pictures are not.",
        )
    }

    // ------------------------------------------------------------------ tokens

    private fun tokenize(html: String, into: Builder) {
        var index = 0
        val length = html.length
        while (index < length && !into.full) {
            val open = html.indexOf('<', index)
            if (open < 0) {
                into.text(decodeEntities(html.substring(index)))
                break
            }
            if (open > index) into.text(decodeEntities(html.substring(index, open)))
            when {
                html.startsWith("<!--", open) -> {
                    val close = html.indexOf("-->", open + 4)
                    index = if (close < 0) length else close + 3
                }
                html.startsWith("<!", open) || html.startsWith("<?", open) -> {
                    val close = html.indexOf('>', open)
                    index = if (close < 0) length else close + 1
                }
                else -> {
                    val close = tagEnd(html, open)
                    if (close < 0) {
                        // A lone "<" in text, as in "a < b".
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
                        into.text(decodeEntities("<$body>"))
                        continue
                    }
                    if (closing) {
                        into.end(name)
                    } else {
                        into.start(name, body)
                        // Script and style bodies are not markup, and can contain "<" freely.
                        if (name in RAW_TEXT && !body.endsWith("/")) {
                            val end = html.indexOf("</$name", index, ignoreCase = true)
                            index = if (end < 0) length else end
                        }
                    }
                }
            }
        }
    }

    /** Where a tag that starts at [open] ends, honouring quotes so `<a title="x > y">` holds together. */
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

    private fun attribute(body: String, name: String): String? {
        val match = Regex("""(?i)\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""").find(body) ?: return null
        return match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ building

    private class Builder {
        val blocks = mutableListOf<Block>()
        val outline = mutableListOf<OutlineEntry>()
        val full: Boolean get() = paragraphCount >= MAX_PARAGRAPHS

        private var paragraphCount = 0
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
            // `</head>` is optional in HTML; the body starting is what really ends it.
            if (name == "body") skipping = 0
            if (skipping > 0) {
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
                "b", "strong" -> { endRun(); bold++ }
                "i", "em", "cite", "var", "dfn" -> { endRun(); italic++ }
                "u", "ins" -> { endRun(); underline++ }
                "s", "strike", "del" -> { endRun(); strike++ }
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
            if (skipping > 0 || value.isEmpty()) return
            if (pre > 0) {
                run.append(value.replace("\r\n", "\n"))
                lastWasSpace = value.last().isWhitespace()
                return
            }
            // Outside <pre>, any run of whitespace is one space, and none at a paragraph's start.
            for (character in value) {
                if (character.isWhitespace()) {
                    if (!lastWasSpace) {
                        run.append(' ')
                        lastWasSpace = true
                    }
                } else {
                    run.append(if (character == ' ') ' ' else character)
                    lastWasSpace = false
                }
            }
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
            // Trim the edges: the whitespace collapsing above leaves one space where a line ended.
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
                    indentPt = (lists.size + quoteDepth) * INDENT_PT,
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
            if (full) return
            paragraphCount++
            val cell = cellParagraphs
            if (tableDepth > 0 && cell != null) {
                cell += paragraph
            } else {
                if (level != null) outline += OutlineEntry(paragraph.plainText.trim(), 0, level - 1)
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
        }
    }

    // ------------------------------------------------------------------ entities

    /** `&amp;`, `&#233;`, `&#xE9;` and the named entities that actually turn up in text. */
    fun decodeEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character != '&') {
                out.append(character)
                index++
                continue
            }
            val semicolon = text.indexOf(';', index + 1)
            if (semicolon < 0 || semicolon - index > 12) {
                out.append(character)
                index++
                continue
            }
            val entity = text.substring(index + 1, semicolon)
            val decoded = when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let(::codePointText)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePointText)
                else -> NAMED[entity]
            }
            if (decoded == null) {
                out.append(character)
                index++
            } else {
                out.append(decoded)
                index = semicolon + 1
            }
        }
        return out.toString()
    }

    private fun codePointText(code: Int): String? =
        if (Character.isValidCodePoint(code) && code != 0) String(Character.toChars(code)) else null

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "middot" to "·",
        "laquo" to "«", "raquo" to "»", "deg" to "°", "times" to "×", "divide" to "÷", "euro" to "€",
        "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶", "shy" to "",
        "zwnj" to "‌", "zwj" to "‍", "rarr" to "→", "larr" to "←", "plusmn" to "±",
    )

    /** Elements whose content is never shown. */
    private val SKIPPED = setOf(
        "head", "script", "style", "noscript", "template", "svg", "math", "iframe", "object",
        "canvas", "select", "button", "title", "nav",
    )
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")
    private val VOID = setOf("img", "br", "hr", "input", "meta", "link", "source", "wbr")

    /** Elements that start and end a paragraph of their own. */
    private val BLOCKS = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside", "address",
        "figure", "figcaption", "dt", "dd", "center", "caption", "form", "fieldset", "legend",
        "details", "summary", "body", "html",
    )

    private val BULLETS = listOf("•", "◦", "▪")
    private val HEADING_SIZES = floatArrayOf(22f, 18f, 15f, 13f, 12f, 11f)
    private const val INDENT_PT = 18f
    private const val MAX_SPAN = 64
    private const val MAX_PARAGRAPHS = 50_000
}
