package top.teamaos.pdfreader.office

import java.io.File
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The Office formats from before 2007: .doc, .xls and .ppt.
 *
 * These are binary record formats, not markup, and the honest position on them is this: the text,
 * the structure and — for a workbook — every cell value can be recovered exactly, and the visual
 * formatting cannot without implementing most of Word. So that is what happens here. What the
 * reader gets is the document's content, correctly ordered and correctly decoded, and a line at
 * the top saying that is what they are looking at.
 *
 * The alternative would be to refuse to open them, which is worse: a .doc that opens and reads is
 * far more use than one that does not open at all.
 */
object LegacyOfficeParser {

    fun parseDoc(file: File, displayName: String): OfficeDocument? {
        val ole = Ole2File.open(file) ?: return null
        val document = ole.firstStream("WordDocument") ?: return null
        val text = extractWordText(ole, document) ?: return null
        val paragraphs = splitParagraphs(text)
        if (paragraphs.isEmpty()) return null
        return OfficeDocument(
            kind = OfficeKind.WORD,
            displayName = displayName,
            sections = listOf(Section.Flow(displayName, paragraphs.map { Block.Text(it) })),
            limitationNote = "This is an older Word (.doc) file. Its text is shown; the original " +
                "fonts and page layout are not.",
        )
    }

    // ------------------------------------------------------------------ Word 97

    /**
     * Pull the text out of a Word 97-2003 document.
     *
     * The characters are not stored in one run. Word keeps a piece table — a list of stretches of
     * the file, each either one byte a character or two — and the document's text is those pieces
     * read in order. Reading the stream from beginning to end instead is the classic mistake: it
     * gives deleted text, revision marks and fragments in the wrong order.
     */
    private fun extractWordText(ole: Ole2File, document: ByteArray): String? {
        if (document.size < 0x0200) return null
        val flags = Ole2File.u16(document, 0x000A)
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = ole.firstStream(tableName, "1Table", "0Table")

        val fcClx = Ole2File.i32(document, 0x01A2)
        val lcbClx = Ole2File.i32(document, 0x01A6)

        if (table != null && lcbClx > 0 && fcClx >= 0 && fcClx + lcbClx <= table.size) {
            fromPieceTable(document, table, fcClx, lcbClx)?.let { return it }
        }

        // No usable piece table: fall back to the main text range, which is right for the simple
        // documents that tend to be missing one.
        val from = Ole2File.i32(document, 0x0018)
        val to = Ole2File.i32(document, 0x001C)
        if (from in 0 until to && to <= document.size) {
            return String(document, from, to - from, WINDOWS_1252)
        }
        return null
    }

    private fun fromPieceTable(
        document: ByteArray,
        table: ByteArray,
        fcClx: Int,
        lcbClx: Int,
    ): String? {
        // The CLX is a run of property blocks followed by one piece table, each tagged by a byte.
        var offset = fcClx
        val end = fcClx + lcbClx
        var pieceTableStart = -1
        var pieceTableLength = 0
        while (offset < end) {
            when (table[offset].toInt() and 0xFF) {
                0x01 -> {
                    val length = Ole2File.u16(table, offset + 1)
                    offset += 3 + length
                }
                0x02 -> {
                    pieceTableLength = Ole2File.i32(table, offset + 1)
                    pieceTableStart = offset + 5
                    offset = end
                }
                else -> return null
            }
        }
        if (pieceTableStart < 0 || pieceTableLength <= 0) return null
        if (pieceTableStart + pieceTableLength > table.size) return null

        // A PlcPcd: n+1 character positions, then n eight-byte descriptors.
        val pieceCount = (pieceTableLength - 4) / 12
        if (pieceCount <= 0) return null
        val text = StringBuilder()
        for (index in 0 until pieceCount) {
            val cpStart = Ole2File.i32(table, pieceTableStart + index * 4)
            val cpEnd = Ole2File.i32(table, pieceTableStart + (index + 1) * 4)
            val descriptor = pieceTableStart + (pieceCount + 1) * 4 + index * 8
            if (descriptor + 8 > table.size) break
            val fc = Ole2File.i32(table, descriptor + 2)
            val characters = cpEnd - cpStart
            if (characters <= 0 || characters > MAX_CHARACTERS) continue

            // Bit 30 set means the piece is one byte a character, at half the stated offset.
            val compressed = fc and 0x40000000 != 0
            val start = if (compressed) (fc and 0x3FFFFFFF) / 2 else fc
            if (compressed) {
                if (start < 0 || start + characters > document.size) continue
                text.append(String(document, start, characters, WINDOWS_1252))
            } else {
                if (start < 0 || start + characters * 2 > document.size) continue
                text.append(String(document, start, characters * 2, Charsets.UTF_16LE))
            }
            if (text.length > MAX_CHARACTERS) break
        }
        return text.toString().ifEmpty { null }
    }

    /** Word's paragraph mark is a carriage return; the rest are control codes to drop. */
    private fun splitParagraphs(text: String): List<Paragraph> {
        val cleaned = text
            .replace('\u0007', '\n')  // cell and row marks
            .replace('\u000B', '\n')  // line break inside a paragraph
            .replace('\u000C', '\n')  // page break
            .replace('\r', '\n')
            .replace("\u0002", "")    // footnote and endnote anchors
            .filter { it == '\n' || it == '\t' || it.code >= 32 }
        return cleaned.split('\n').map { line ->
            Paragraph(listOf(TextSpan(line.trim())), spaceAfterPt = 6f)
        }.dropLastWhile { it.plainText.isBlank() }
    }

    // ------------------------------------------------------------------ Excel 97

    fun parseXls(file: File, displayName: String): OfficeDocument? {
        val ole = Ole2File.open(file) ?: return null
        val workbook = ole.firstStream("Workbook", "Book") ?: return null
        val sheets = readBiffSheets(workbook)
        if (sheets.isEmpty()) return null

        val sections = sheets.map { sheet ->
            val columns = (sheet.widest + 1).coerceAtLeast(1)
            val rows = (0..sheet.tallest).map { row ->
                (0 until columns).map { column ->
                    val value = sheet.cells[row.toLong() shl 20 or column.toLong()]
                    GridCell(
                        text = value?.text.orEmpty(),
                        align = if (value?.numeric == true) Align.RIGHT else Align.LEFT,
                    )
                }
            }
            Section.Grid(
                title = sheet.name,
                rows = rows,
                columnWidthsPt = List(columns) { DEFAULT_COLUMN_PT },
            )
        }
        return OfficeDocument(
            kind = OfficeKind.SPREADSHEET,
            displayName = displayName,
            sections = sections,
            outline = sections.mapIndexed { index, section -> OutlineEntry(section.title, index) },
            limitationNote = "This is an older Excel (.xls) file. Every cell value is shown; " +
                "cell colours and fonts are not.",
        )
    }

    private class BiffCell(val text: String, val numeric: Boolean)

    private class BiffSheet(val name: String, val start: Int) {
        val cells = HashMap<Long, BiffCell>()
        var widest = 0
        var tallest = 0

        fun put(row: Int, column: Int, cell: BiffCell) {
            if (row > MAX_ROWS || column > MAX_COLUMNS || cell.text.isEmpty()) return
            cells[row.toLong() shl 20 or column.toLong()] = cell
            if (row > tallest) tallest = row
            if (column > widest) widest = column
        }
    }

    /**
     * Walk the workbook's record stream.
     *
     * BIFF is a flat list of records — two bytes of type, two of length, then the data — so the
     * whole format is one loop. The globals come first and name the sheets and the shared string
     * table; each sheet's own records follow at an offset the globals give.
     */
    private fun readBiffSheets(data: ByteArray): List<BiffSheet> {
        val sheets = mutableListOf<BiffSheet>()
        val strings = mutableListOf<String>()

        var offset = 0
        while (offset + 4 <= data.size) {
            val type = Ole2File.u16(data, offset)
            val length = Ole2File.u16(data, offset + 2)
            val body = offset + 4
            if (body + length > data.size) break

            when (type) {
                RECORD_BOUNDSHEET -> {
                    val start = Ole2File.i32(data, body)
                    val name = shortUnicodeString(data, body + 6)
                    if (name.isNotEmpty()) sheets += BiffSheet(name, start)
                }
                RECORD_SST -> strings += readSharedStrings(data, body, length)
                RECORD_EOF -> if (sheets.isNotEmpty()) {
                    // The globals are finished; the sheets are read from their own offsets below.
                    offset = data.size
                    continue
                }
            }
            offset = body + length
        }

        sheets.forEach { sheet -> readSheetRecords(data, sheet, strings) }
        return sheets.filter { it.cells.isNotEmpty() }
    }

    private fun readSheetRecords(data: ByteArray, sheet: BiffSheet, strings: List<String>) {
        var offset = sheet.start
        if (offset < 0 || offset + 4 > data.size) return
        var seenBof = false
        var lastRow = 0
        var lastColumn = 0

        while (offset + 4 <= data.size) {
            val type = Ole2File.u16(data, offset)
            val length = Ole2File.u16(data, offset + 2)
            val body = offset + 4
            if (body + length > data.size) break

            when (type) {
                RECORD_BOF -> {
                    if (seenBof) return
                    seenBof = true
                }
                RECORD_EOF -> return
                RECORD_LABELSST -> {
                    val row = Ole2File.u16(data, body)
                    val column = Ole2File.u16(data, body + 2)
                    val index = Ole2File.i32(data, body + 6)
                    strings.getOrNull(index)?.let { sheet.put(row, column, BiffCell(it, false)) }
                }
                RECORD_LABEL -> {
                    val row = Ole2File.u16(data, body)
                    val column = Ole2File.u16(data, body + 2)
                    val text = shortUnicodeString(data, body + 6, twoByteLength = true)
                    sheet.put(row, column, BiffCell(text, false))
                }
                RECORD_NUMBER -> {
                    val row = Ole2File.u16(data, body)
                    val column = Ole2File.u16(data, body + 2)
                    val bits = readLong(data, body + 6)
                    sheet.put(row, column, BiffCell(formatDouble(Double.fromBits(bits)), true))
                }
                RECORD_RK -> {
                    val row = Ole2File.u16(data, body)
                    val column = Ole2File.u16(data, body + 2)
                    val value = decodeRk(Ole2File.i32(data, body + 6))
                    sheet.put(row, column, BiffCell(formatDouble(value), true))
                }
                RECORD_MULRK -> {
                    val row = Ole2File.u16(data, body)
                    val first = Ole2File.u16(data, body + 2)
                    val count = (length - 6) / 6
                    for (index in 0 until count) {
                        val value = decodeRk(Ole2File.i32(data, body + 4 + index * 6 + 2))
                        sheet.put(row, first + index, BiffCell(formatDouble(value), true))
                    }
                }
                RECORD_BOOLERR -> {
                    val row = Ole2File.u16(data, body)
                    val column = Ole2File.u16(data, body + 2)
                    val value = data[body + 6].toInt() and 0xFF
                    val isError = data[body + 7].toInt() != 0
                    val text = if (isError) "#ERR" else if (value != 0) "TRUE" else "FALSE"
                    sheet.put(row, column, BiffCell(text, false))
                }
                RECORD_FORMULA -> {
                    lastRow = Ole2File.u16(data, body)
                    lastColumn = Ole2File.u16(data, body + 2)
                    // A formula's cached result is a double unless the top two bytes are 0xFFFF,
                    // which flags a string, boolean or error carried in the record that follows.
                    val marker = Ole2File.u16(data, body + 12)
                    if (marker != 0xFFFF) {
                        val bits = readLong(data, body + 6)
                        sheet.put(lastRow, lastColumn, BiffCell(formatDouble(Double.fromBits(bits)), true))
                    }
                }
                RECORD_STRING -> {
                    val text = shortUnicodeString(data, body, twoByteLength = true)
                    if (text.isNotEmpty()) sheet.put(lastRow, lastColumn, BiffCell(text, false))
                }
            }
            offset = body + length
        }
    }

    /**
     * The shared string table, which spills across CONTINUE records.
     *
     * Every string in a workbook lives here once. The awkward part is that a string can be cut in
     * half by a record boundary, and the half in the next record repeats the byte that says
     * whether it is one byte or two per character — so the boundary has to be followed rather than
     * the records simply concatenated.
     */
    private fun readSharedStrings(data: ByteArray, start: Int, length: Int): List<String> {
        val strings = mutableListOf<String>()
        val unique = Ole2File.i32(data, start + 4)
        if (unique <= 0 || unique > MAX_STRINGS) return strings

        var offset = start + 8
        var limit = start + length
        val text = StringBuilder()

        fun advanceRecord(): Boolean {
            // Positioned at the end of a record: the next one must be a CONTINUE to carry on.
            if (limit + 4 > data.size) return false
            val type = Ole2File.u16(data, limit)
            if (type != RECORD_CONTINUE) return false
            val continueLength = Ole2File.u16(data, limit + 2)
            offset = limit + 4
            limit = offset + continueLength
            return limit <= data.size
        }

        while (strings.size < unique) {
            if (offset + 3 > limit && !advanceRecord()) break
            if (offset + 3 > limit) break
            val characters = Ole2File.u16(data, offset)
            var flags = data[offset + 2].toInt() and 0xFF
            offset += 3
            var wide = flags and 0x01 != 0
            val richRuns = if (flags and 0x08 != 0) {
                val count = Ole2File.u16(data, offset); offset += 2; count
            } else {
                0
            }
            val extraBytes = if (flags and 0x04 != 0) {
                val size = Ole2File.i32(data, offset); offset += 4; size
            } else {
                0
            }

            text.setLength(0)
            var remaining = characters
            while (remaining > 0) {
                if (offset >= limit) {
                    if (!advanceRecord()) break
                    // The continuation restates the width for the rest of this string.
                    if (offset < limit) {
                        wide = data[offset].toInt() and 0x01 != 0
                        offset++
                    }
                }
                val available = if (wide) (limit - offset) / 2 else limit - offset
                if (available <= 0) {
                    if (!advanceRecord()) break else continue
                }
                val take = minOf(remaining, available)
                if (wide) {
                    text.append(String(data, offset, take * 2, Charsets.UTF_16LE))
                    offset += take * 2
                } else {
                    text.append(String(data, offset, take, WINDOWS_1252))
                    offset += take
                }
                remaining -= take
            }
            strings += text.toString()
            offset += richRuns * 4 + extraBytes
            if (offset > limit && !advanceRecord()) break
        }
        return strings
    }

    /** A short XLUnicodeString: a length, a flags byte, then the characters. */
    private fun shortUnicodeString(data: ByteArray, at: Int, twoByteLength: Boolean = false): String {
        if (at >= data.size) return ""
        val length = if (twoByteLength) Ole2File.u16(data, at) else data[at].toInt() and 0xFF
        val flagsAt = if (twoByteLength) at + 2 else at + 1
        if (length <= 0 || flagsAt >= data.size) return ""
        val wide = data[flagsAt].toInt() and 0x01 != 0
        val from = flagsAt + 1
        return if (wide) {
            if (from + length * 2 > data.size) "" else String(data, from, length * 2, Charsets.UTF_16LE)
        } else {
            if (from + length > data.size) "" else String(data, from, length, WINDOWS_1252)
        }
    }

    /** Excel's packed number: two flag bits say whether it is an integer and whether to divide. */
    private fun decodeRk(bits: Int): Double {
        val isInteger = bits and 0x02 != 0
        val divide = bits and 0x01 != 0
        val value = if (isInteger) {
            (bits shr 2).toDouble()
        } else {
            Double.fromBits((bits.toLong() and 0xFFFFFFFCL) shl 32)
        }
        return if (divide) value / 100.0 else value
    }

    private fun readLong(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or ((data.getOrNull(offset + index)?.toInt() ?: 0).toLong() and 0xFF)
        }
        return value
    }

    private fun formatDouble(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ""
        return if (abs(value - value.roundToLong()) < 1e-10) {
            value.roundToLong().toString()
        } else {
            java.math.BigDecimal(value).setScale(10, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    }

    // ------------------------------------------------------------------ PowerPoint 97

    fun parsePpt(file: File, displayName: String): OfficeDocument? {
        val ole = Ole2File.open(file) ?: return null
        val stream = ole.firstStream("PowerPoint Document", "PP97_DUALSTORAGE") ?: return null
        val slides = readPptSlides(stream)
        if (slides.isEmpty()) return null

        val sections = slides.mapIndexed { index, lines ->
            val title = lines.firstOrNull { it.isNotBlank() }?.take(60).orEmpty()
            val paragraphs = lines.mapIndexed { line, content ->
                Paragraph(
                    spans = listOf(
                        TextSpan(content, bold = line == 0, sizePt = if (line == 0) 28f else 18f),
                    ),
                    spaceAfterPt = 8f,
                )
            }
            Section.Slide(
                title = title.ifBlank { "Slide ${index + 1}" },
                widthPt = 720f,
                heightPt = 540f,
                items = listOf(
                    SlideItem.Text(
                        xPt = 56f,
                        yPt = 56f,
                        widthPt = 608f,
                        heightPt = 428f,
                        paragraphs = paragraphs,
                    ),
                ),
            )
        }
        return OfficeDocument(
            kind = OfficeKind.SLIDES,
            displayName = displayName,
            sections = sections,
            outline = sections.mapIndexed { index, section ->
                OutlineEntry("${index + 1}. ${section.title}", index)
            },
            limitationNote = "This is an older PowerPoint (.ppt) file. The text of each slide is " +
                "shown; the original design is not.",
        )
    }

    /**
     * Walk the record tree and collect each slide's text.
     *
     * PowerPoint's stream is records inside records: the high nibble of the first field marks a
     * container, and a container's body is more records. Slides are containers, and the text on
     * them sits in two kinds of atom depending on whether it needed more than one byte a
     * character. Following the tree keeps the text attached to the right slide.
     */
    private fun readPptSlides(data: ByteArray): List<List<String>> {
        val slides = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null

        fun walk(from: Int, to: Int, depth: Int) {
            if (depth > MAX_DEPTH) return
            var offset = from
            while (offset + 8 <= to) {
                val versionInstance = Ole2File.u16(data, offset)
                val type = Ole2File.u16(data, offset + 2)
                val length = Ole2File.i32(data, offset + 4)
                val body = offset + 8
                if (length < 0 || body + length > to) return

                when {
                    type == TYPE_SLIDE -> {
                        current = mutableListOf()
                        slides += current!!
                        walk(body, body + length, depth + 1)
                    }
                    versionInstance and 0x000F == 0x000F -> walk(body, body + length, depth + 1)
                    type == TYPE_TEXT_CHARS -> current?.addAll(
                        splitRuns(String(data, body, length, Charsets.UTF_16LE)),
                    )
                    type == TYPE_TEXT_BYTES -> current?.addAll(
                        splitRuns(String(data, body, length, WINDOWS_1252)),
                    )
                }
                offset = body + length
            }
        }

        walk(0, data.size, 0)
        return slides.filter { slide -> slide.any { it.isNotBlank() } }
    }

    /** Inside one text atom, a carriage return separates paragraphs and \\v separates lines. */
    private fun splitRuns(text: String): List<String> =
        text.replace('\u000B', '\n')
            .replace('\r', '\n')
            .split('\n')
            .map { line -> line.filter { it == '\t' || it.code >= 32 }.trim() }
            .filter { it.isNotEmpty() }

    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }
        .getOrDefault(Charsets.ISO_8859_1)

    private const val RECORD_BOF = 0x0809
    private const val RECORD_EOF = 0x000A
    private const val RECORD_BOUNDSHEET = 0x0085
    private const val RECORD_SST = 0x00FC
    private const val RECORD_CONTINUE = 0x003C
    private const val RECORD_LABELSST = 0x00FD
    private const val RECORD_LABEL = 0x0204
    private const val RECORD_NUMBER = 0x0203
    private const val RECORD_RK = 0x027E
    private const val RECORD_MULRK = 0x00BD
    private const val RECORD_FORMULA = 0x0006
    private const val RECORD_STRING = 0x0207
    private const val RECORD_BOOLERR = 0x0205

    private const val TYPE_SLIDE = 1006
    private const val TYPE_TEXT_CHARS = 4000
    private const val TYPE_TEXT_BYTES = 4008

    private const val MAX_ROWS = 20000
    private const val MAX_COLUMNS = 128
    private const val MAX_STRINGS = 200000
    private const val MAX_CHARACTERS = 8_000_000
    private const val MAX_DEPTH = 24
    private const val DEFAULT_COLUMN_PT = 72f
}
