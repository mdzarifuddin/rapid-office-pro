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
        val (lines, truncated) = layoutLines(text)
        val paragraphs = lines.map { line ->
            Paragraph(
                spans = listOf(TextSpan(line, monospace = true, sizePt = 10f)),
                spaceAfterPt = 2f,
            )
        }
        return OfficeDocument(
            kind = OfficeKind.TEXT,
            displayName = displayName,
            sections = listOf(Section.Flow(displayName, paragraphs.map { Block.Text(it) })),
            limitationNote = if (truncated) {
                "This file is too long to show in full; the first ${lines.size} lines are shown."
            } else {
                null
            },
        )
    }

    /**
     * The lines of [text] that will actually be laid out, and whether any were left out.
     *
     * Every line becomes a measured layout, so a 20 MB log is hundreds of thousands of them and
     * runs a phone out of memory before the first page shows. And a single 5 MB line — a minified
     * file, a base64 blob — is one layout that Android measures for seconds and may never draw;
     * cutting it into chunks keeps every line a size the text engine is happy with.
     */
    private fun layoutLines(text: String): Pair<List<String>, Boolean> {
        var truncated = text.length > MAX_LAYOUT_CHARS
        val limited = if (truncated) text.substring(0, MAX_LAYOUT_CHARS) else text
        val lines = ArrayList<String>()
        outer@ for (raw in limited.splitToSequence('\n')) {
            val line = raw.trimEnd('\r')
            var from = 0
            do {
                if (lines.size >= MAX_LAYOUT_LINES) {
                    truncated = true
                    break@outer
                }
                var to = min(line.length, from + MAX_LINE_CHARS)
                // Never split a surrogate pair: half an emoji draws as a box.
                if (to < line.length && to > from + 1 && line[to - 1].isHighSurrogate()) to--
                lines += line.substring(from, to)
                from = to
            } while (from < line.length)
        }
        return lines to truncated
    }

    /**
     * A delimited file, shown as the grid it is rather than as lines of commas.
     *
     * The separator is worked out from the first few lines rather than from the extension, because
     * plenty of files called .csv are separated by semicolons or tabs, and guessing from the
     * content is right far more often than trusting the name.
     */
    fun parseDelimited(file: File, displayName: String): OfficeDocument? {
        val (charset, skip) = sniffCharset(file) ?: return null
        val limits = GridLimits()
        val builder = GridBuilder(limits)
        val widths = FloatArray(MAX_COLUMNS)
        var widest = 0

        val decoder = charset.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val input = java.io.FileInputStream(file)
        try {
            var skipped = 0L
            while (skipped < skip) {
                val count = input.skip(skip - skipped)
                if (count <= 0) break
                skipped += count
            }
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input, decoder), READ_CHARS)

            // The separator is guessed from the first lines, read ahead and then given back.
            reader.mark(SAMPLE_CHARS + 1)
            val sampleBuffer = CharArray(SAMPLE_CHARS)
            var sampled = 0
            while (sampled < sampleBuffer.size) {
                val count = reader.read(sampleBuffer, sampled, sampleBuffer.size - sampled)
                if (count <= 0) break
                sampled += count
            }
            reader.reset()
            val separator = guessSeparator(String(sampleBuffer, 0, sampled))

            // A state machine over the characters as they stream in. A row is built directly as
            // one string of separator-joined cells: no cell is ever an object of its own, which
            // is what keeps a 250,000-row file to a fraction of the memory it used to take.
            val row = StringBuilder(256)
            var quoted = false
            var quotePending = false
            var afterCarriageReturn = false
            var column = 0
            var fieldLength = 0
            var filled = 0
            var hasContent = false
            var rows = 0

            fun endField() {
                if (column < MAX_COLUMNS) {
                    if (rows < WIDTH_SAMPLE_ROWS && fieldLength > 0) {
                        widths[column] = max(widths[column], min(MAX_COLUMN_PT, fieldLength * CHAR_PT + PADDING_PT))
                    }
                    if (fieldLength > 0) filled++
                    row.append(GridData.SEPARATOR)
                    if (fieldLength > 0 && column + 1 > widest) widest = column + 1
                }
                column++
                fieldLength = 0
            }

            fun endRow() {
                endField()
                if (hasContent) {
                    builder.addJoinedRow(row, filled)
                    rows++
                    if (rows and 0xFFF == 0) checkCancelled()
                }
                row.setLength(0)
                column = 0
                filled = 0
                hasContent = false
            }

            fun append(character: Char) {
                if (column >= MAX_COLUMNS || fieldLength >= MAX_FIELD_CHARS) return
                row.append(
                    when (character) {
                        GridData.SEPARATOR, '\t', '\n', '\r' -> ' '
                        else -> character
                    },
                )
                fieldLength++
                if (!hasContent && !character.isWhitespace()) hasContent = true
            }

            val buffer = CharArray(READ_CHARS)
            reading@ while (!builder.full) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val character = buffer[index]
                    if (quotePending) {
                        quotePending = false
                        if (character == '"') {
                            append('"')
                            continue
                        }
                        quoted = false
                    }
                    if (afterCarriageReturn) {
                        afterCarriageReturn = false
                        if (character == '\n') continue
                    }
                    if (quoted) {
                        if (character == '"') quotePending = true else append(character)
                        continue
                    }
                    when (character) {
                        '"' -> quoted = true
                        separator -> endField()
                        '\n' -> endRow()
                        '\r' -> {
                            endRow()
                            afterCarriageReturn = true
                        }
                        else -> append(character)
                    }
                    if (builder.full) break@reading
                }
            }
            if (hasContent) endRow()
        } finally {
            runCatching { input.close() }
        }

        val grid = builder.build()
        if (grid.rowCount == 0) return null
        val columns = max(1, widest)
        return OfficeDocument(
            kind = OfficeKind.SPREADSHEET,
            displayName = displayName,
            sections = listOf(
                Section.Grid(displayName, grid, List(columns) { max(MIN_COLUMN_PT, widths[it]) }),
            ),
            limitationNote = limits.note,
        )
    }

    /**
     * The encoding of [file] and how many bytes of byte-order mark to skip, judged from its start.
     *
     * The same rules as [readText], from a sample instead of the whole file, so a large file can
     * be streamed rather than held in memory twice over.
     */
    private fun sniffCharset(file: File): Pair<Charset, Long>? {
        val sample = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(SNIFF_CHARSET_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val count = stream.read(buffer, read, buffer.size - read)
                    if (count <= 0) break
                    read += count
                }
                buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        if (sample.size >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte()) {
            return Charsets.UTF_8 to 3L
        }
        if (sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2L
        if (sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2L
        val head = sample.copyOf(min(sample.size, SNIFF_BYTES))
        val zeros = head.count { it == 0.toByte() }
        if (zeros > head.size / 4) {
            val evenZeros = head.filterIndexed { index, _ -> index % 2 == 0 }.count { it == 0.toByte() }
            return (if (evenZeros > zeros / 2) Charsets.UTF_16BE else Charsets.UTF_16LE) to 0L
        }
        val whole = file.length() <= sample.size
        val body = if (whole) sample else withoutPartialUtf8Tail(sample)
        if (decodeStrictly(body, Charsets.UTF_8) != null) return Charsets.UTF_8 to 0L
        val (valid, invalid) = utf8Sequences(body)
        if (valid > 0 && valid >= invalid * 4) return Charsets.UTF_8 to 0L
        return WINDOWS_1252 to 0L
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
            // Capped like plain text: every paragraph is a measured layout.
            if (paragraphs.size < MAX_LAYOUT_LINES) paragraphs += Paragraph(spans.toList(), spaceAfterPt = 6f)
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
        val length = file.length()
        val read = runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(min(limit.toLong(), length).toInt().coerceAtLeast(0))
                var read = 0
                while (read < buffer.size) {
                    val count = stream.read(buffer, read, buffer.size - read)
                    if (count <= 0) break
                    read += count
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
        }.getOrNull() ?: return null
        // Cut off at the limit, the last character may be half there. Left in, it fails the strict
        // UTF-8 check below and a perfectly good UTF-8 file (Bangla, say) comes out as mojibake.
        val bytes = if (length > read.size) withoutPartialUtf8Tail(read) else read
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
        // Not clean UTF-8, but mostly UTF-8 with a stray bad byte or two — a log with one corrupt
        // line, a file joined from two sources. Read as Windows-1252 every Bangla, Hindi or Arabic
        // character in it would turn to junk, so a few replacement marks are far the better loss.
        val (valid, invalid) = utf8Sequences(bytes)
        if (valid > 0 && valid >= invalid * 4) return String(bytes, Charsets.UTF_8)
        return String(bytes, WINDOWS_1252)
    }

    /**
     * True when [head] — the first few kilobytes of a file — is binary rather than text in any
     * encoding this reader decodes: NUL bytes that are not UTF-16's, or a run of control codes.
     */
    fun looksBinary(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        if (head.size >= 2 && (
                (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) ||
                    (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte())
                )
        ) {
            return false
        }
        var zeros = 0
        var evenZeros = 0
        var controls = 0
        head.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                zeros++
                if (index % 2 == 0) evenZeros++
            } else if (value < 0x20 && value !in TEXT_CONTROLS) {
                controls++
            }
        }
        if (zeros > 0) {
            // UTF-16 without a mark: the zeros are the high bytes of ASCII, all on one side.
            val oneSided = maxOf(evenZeros, zeros - evenZeros) >= zeros * 9 / 10
            return !(oneSided && zeros >= 4)
        }
        return controls > head.size / 20
    }

    /** Drop an incomplete UTF-8 sequence from the end of [bytes], if there is one. */
    private fun withoutPartialUtf8Tail(bytes: ByteArray): ByteArray {
        var lead = bytes.size - 1
        var continuation = 0
        while (lead >= 0 && continuation < 3 && (bytes[lead].toInt() and 0xC0) == 0x80) {
            lead--
            continuation++
        }
        if (lead < 0) return bytes
        val first = bytes[lead].toInt() and 0xFF
        val needed = when {
            first >= 0xF0 -> 4
            first >= 0xE0 -> 3
            first >= 0xC0 -> 2
            else -> 1
        }
        return if (needed > continuation + 1) bytes.copyOf(lead) else bytes
    }

    /** Well-formed multi-byte UTF-8 sequences in [bytes], and bytes that fit no sequence. */
    private fun utf8Sequences(bytes: ByteArray): Pair<Int, Int> {
        var valid = 0
        var invalid = 0
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            if (first < 0x80) {
                index++
                continue
            }
            val length = when (first) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> 0
            }
            val complete = length > 0 && index + length <= bytes.size &&
                (1 until length).all { (bytes[index + it].toInt() and 0xC0) == 0x80 }
            if (complete) {
                valid++
                index += length
            } else {
                invalid++
                index++
            }
        }
        return valid to invalid
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

    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }
        .getOrDefault(Charsets.ISO_8859_1)

    private const val MAX_TEXT_BYTES = 24 * 1024 * 1024
    /** About 4 MB of characters and 50,000 lines: past that a text file is not read on a phone. */
    private const val MAX_LAYOUT_CHARS = 4 * 1024 * 1024
    private const val MAX_LAYOUT_LINES = 50_000
    private const val MAX_LINE_CHARS = 4000
    private const val SNIFF_BYTES = 512

    /** Tab, line feed, form feed, carriage return, escape and backspace turn up in real text. */
    private val TEXT_CONTROLS = setOf(8, 9, 10, 11, 12, 13, 26, 27)
    private const val SEPARATOR_SAMPLE_LINES = 12
    private const val MAX_COLUMNS = GridBuilder.MAX_COLUMNS

    /** A single cell is drawn on one line; past this nothing more of it could be seen. */
    private const val MAX_FIELD_CHARS = 4000
    private const val READ_CHARS = 64 * 1024
    private const val SAMPLE_CHARS = 64 * 1024
    private const val SNIFF_CHARSET_BYTES = 256 * 1024
    private const val CHAR_PT = 5.2f
    private const val PADDING_PT = 10f
    private const val MIN_COLUMN_PT = 44f
    private const val MAX_COLUMN_PT = 220f
    private const val WIDTH_SAMPLE_ROWS = 60
}
