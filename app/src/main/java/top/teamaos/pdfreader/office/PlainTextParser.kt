package top.teamaos.pdfreader.office

import java.io.File
import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

/**
 * The formats that are just characters: .txt, .md, .log, .csv, .tsv and .rtf.
 *
 * Small, but the encoding guessing here matters more than any of it. A CSV exported by Excel on a
 * Windows machine is very often Windows-1252 with a UTF-8 byte-order mark, or UTF-16 with no mark
 * at all, and a reader that assumes UTF-8 shows a column of question marks for every accented name
 * in the file.
 */
object PlainTextParser {

    fun parseText(file: File, displayName: String): OfficeDocument? {
        val text = readText(file) ?: return null
        val paragraphs = text.split('\n').map { line ->
            Paragraph(
                spans = listOf(TextSpan(line.trimEnd('\r'), monospace = true, sizePt = 10f)),
                spaceAfterPt = 2f,
            )
        }
        return OfficeDocument(
            kind = OfficeKind.TEXT,
            displayName = displayName,
            sections = listOf(Section.Flow(displayName, paragraphs.map { Block.Text(it) })),
        )
    }

    /**
     * A delimited file, shown as the grid it is rather than as lines of commas.
     *
     * The separator is worked out from the first few lines rather than from the extension, because
     * plenty of files called .csv are separated by semicolons or tabs, and guessing from the
     * content is right far more often than trusting the name.
     */
    fun parseDelimited(file: File, displayName: String): OfficeDocument? {
        val text = readText(file) ?: return null
        val separator = guessSeparator(text)
        val rows = splitRows(text, separator)
        if (rows.isEmpty()) return null

        val columns = rows.maxOf { it.size }.coerceAtMost(MAX_COLUMNS)
        val widths = FloatArray(columns) { MIN_COLUMN_PT }
        rows.take(WIDTH_SAMPLE_ROWS).forEach { row ->
            row.take(columns).forEachIndexed { index, cell ->
                widths[index] = max(widths[index], min(MAX_COLUMN_PT, cell.length * CHAR_PT + PADDING_PT))
            }
        }

        val grid = rows.map { row ->
            (0 until columns).map { index ->
                val cell = row.getOrNull(index).orEmpty()
                GridCell(
                    text = cell,
                    bold = false,
                    align = if (cell.toDoubleOrNull() != null) Align.RIGHT else Align.LEFT,
                )
            }
        }
        return OfficeDocument(
            kind = OfficeKind.SPREADSHEET,
            displayName = displayName,
            sections = listOf(Section.Grid(displayName, grid, widths.toList())),
        )
    }

    /**
     * Rich Text, read for its words.
     *
     * RTF is a control-word language rather than a markup one, and rendering it properly means
     * implementing most of Word. What it is used for in practice — a document someone was sent —
     * is served by pulling the text out with its paragraph breaks and its bold and italic intact,
     * which is what happens here. The reader is told that is what they are looking at.
     */
    fun parseRtf(file: File, displayName: String): OfficeDocument? {
        val text = readText(file) ?: return null
        val paragraphs = mutableListOf<Paragraph>()
        val spans = mutableListOf<TextSpan>()
        val run = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        var skipDepth = -1
        var depth = 0
        var index = 0

        fun flushRun() {
            if (run.isEmpty()) return
            spans += TextSpan(run.toString(), bold = bold, italic = italic, underline = underline)
            run.setLength(0)
        }

        fun endParagraph() {
            flushRun()
            paragraphs += Paragraph(spans.toList(), spaceAfterPt = 6f)
            spans.clear()
        }

        while (index < text.length) {
            when (val character = text[index]) {
                '{' -> {
                    depth++
                    index++
                }
                '}' -> {
                    depth--
                    if (skipDepth in 0..depth) Unit else if (skipDepth > depth) skipDepth = -1
                    index++
                }
                '\\' -> {
                    val word = StringBuilder()
                    var cursor = index + 1
                    if (cursor < text.length && !text[cursor].isLetter()) {
                        // An escaped character, or a hex byte like \'e9.
                        if (text[cursor] == '\'' && cursor + 2 < text.length) {
                            val hex = text.substring(cursor + 1, cursor + 3)
                            hex.toIntOrNull(16)?.let { if (skipDepth < 0) run.append(it.toChar()) }
                            index = cursor + 3
                            continue
                        }
                        if (skipDepth < 0) run.append(text[cursor])
                        index = cursor + 1
                        continue
                    }
                    while (cursor < text.length && text[cursor].isLetter()) {
                        word.append(text[cursor])
                        cursor++
                    }
                    val parameter = StringBuilder()
                    if (cursor < text.length && (text[cursor] == '-' || text[cursor].isDigit())) {
                        parameter.append(text[cursor])
                        cursor++
                        while (cursor < text.length && text[cursor].isDigit()) {
                            parameter.append(text[cursor])
                            cursor++
                        }
                    }
                    if (cursor < text.length && text[cursor] == ' ') cursor++

                    when (word.toString()) {
                        "par", "line" -> if (skipDepth < 0) endParagraph()
                        "pard" -> if (skipDepth < 0) { flushRun(); bold = false; italic = false; underline = false }
                        "b" -> { flushRun(); bold = parameter.toString() != "0" }
                        "i" -> { flushRun(); italic = parameter.toString() != "0" }
                        "ul" -> { flushRun(); underline = parameter.toString() != "0" }
                        "ulnone" -> { flushRun(); underline = false }
                        "tab" -> if (skipDepth < 0) run.append('\t')
                        // These groups hold machinery, not text: fonts, colours, styles, pictures.
                        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "themedata",
                        "datastore", "generator", "listtable", "rsidtbl", "xmlnstbl",
                        -> if (skipDepth < 0) skipDepth = depth
                        "u" -> if (skipDepth < 0) {
                            parameter.toString().toIntOrNull()?.let { code ->
                                run.append(if (code < 0) (code + 65536).toChar() else code.toChar())
                            }
                            if (cursor < text.length && text[cursor] == '?') cursor++
                        }
                    }
                    index = cursor
                }
                '\r', '\n' -> index++
                else -> {
                    if (skipDepth < 0) run.append(character)
                    index++
                }
            }
        }
        endParagraph()

        val kept = paragraphs.filter { it.spans.any { span -> span.text.isNotBlank() } }
        if (kept.isEmpty()) return null
        return OfficeDocument(
            kind = OfficeKind.WORD,
            displayName = displayName,
            sections = listOf(Section.Flow(displayName, kept.map { Block.Text(it) })),
            limitationNote = "Rich Text files are shown as text with bold, italic and underline.",
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Read a file as text, working out the encoding from what is actually in it.
     *
     * A byte-order mark settles it outright. Without one, a file with alternating zero bytes is
     * UTF-16, and otherwise UTF-8 is tried strictly — if it does not decode cleanly the file is
     * not UTF-8, and Windows-1252 is the overwhelmingly likely alternative.
     */
    fun readText(file: File, limit: Int = MAX_TEXT_BYTES): String? {
        val bytes = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(min(limit.toLong(), file.length()).toInt().coerceAtLeast(0))
                var read = 0
                while (read < buffer.size) {
                    val count = stream.read(buffer, read, buffer.size - read)
                    if (count <= 0) break
                    read += count
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return ""

        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val sample = bytes.take(SNIFF_BYTES)
        val zeros = sample.count { it == 0.toByte() }
        if (zeros > sample.size / 4) {
            val evenZeros = sample.filterIndexed { index, _ -> index % 2 == 0 }.count { it == 0.toByte() }
            return String(bytes, if (evenZeros > zeros / 2) Charsets.UTF_16BE else Charsets.UTF_16LE)
        }
        decodeStrictly(bytes, Charsets.UTF_8)?.let { return it }
        return String(bytes, WINDOWS_1252)
    }

    private fun decodeStrictly(bytes: ByteArray, charset: Charset): String? = runCatching {
        val decoder = charset.newDecoder()
        decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun guessSeparator(text: String): Char {
        val sample = text.lineSequence().take(SEPARATOR_SAMPLE_LINES).toList()
        if (sample.isEmpty()) return ','
        val candidates = listOf(',', ';', '\t', '|')
        return candidates.maxByOrNull { candidate ->
            // The right separator is the one that appears the same number of times on every line.
            val counts = sample.map { line -> line.count { it == candidate } }
            val typical = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (typical == null || typical.key == 0) 0 else typical.value * 100 + typical.key
        } ?: ','
    }

    /** Split respecting quotes, so a comma inside "Smith, John" does not start a new column. */
    private fun splitRows(text: String, separator: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length && rows.size < MAX_ROWS) {
            val character = text[index]
            when {
                quoted && character == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                !quoted && character == separator -> {
                    row += cell.toString()
                    cell.setLength(0)
                }
                !quoted && (character == '\n' || character == '\r') -> {
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString()
                    cell.setLength(0)
                    rows += row
                    row = mutableListOf()
                }
                else -> cell.append(character)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return rows.filter { line -> line.any { it.isNotBlank() } }
    }

    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }
        .getOrDefault(Charsets.ISO_8859_1)

    private const val MAX_TEXT_BYTES = 24 * 1024 * 1024
    private const val SNIFF_BYTES = 512
    private const val SEPARATOR_SAMPLE_LINES = 12
    private const val MAX_ROWS = 20000
    private const val MAX_COLUMNS = 128
    private const val CHAR_PT = 5.2f
    private const val PADDING_PT = 10f
    private const val MIN_COLUMN_PT = 44f
    private const val MAX_COLUMN_PT = 220f
    private const val WIDTH_SAMPLE_ROWS = 60
}
