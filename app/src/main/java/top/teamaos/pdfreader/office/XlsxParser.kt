package top.teamaos.pdfreader.office

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Reading a .xlsx workbook.
 *
 * A spreadsheet on a phone is read, not edited, so what matters is that every cell shows the value
 * the author saw — which is not the same as the value stored. Excel keeps `0.1`, a number format
 * saying "two decimals and a percent sign", and a date as a count of days since 1900; getting from
 * those to "10.00%" and "3 March 2026" is most of the work here.
 *
 * Formulas are not evaluated. Excel caches every formula's last result in the file, and that cached
 * value is what is shown — the same thing the author last saw, without this having to become a
 * calculation engine that could disagree with Excel.
 */
object XlsxParser {

    fun parse(file: File, displayName: String): OfficeDocument? {
        val pack = OoxmlPackage.open(file) ?: return null
        return pack.use { open ->
            val workbookPart = when {
                open.exists("xl/workbook.xml") -> "xl/workbook.xml"
                else -> open.names().firstOrNull { it.endsWith("workbook.xml") }
            } ?: return@use null

            val sharedStrings = readSharedStrings(open)
            val styles = readStyles(open)
            val relationships = open.relationships(workbookPart)
            val sheets = readSheetList(open, workbookPart, relationships)
            if (sheets.isEmpty()) return@use null

            var truncated = false
            val sections = sheets.mapNotNull { sheet ->
                val data = open.bytes(sheet.part) ?: return@mapNotNull null
                val grid = readSheet(data, sharedStrings, styles)
                if (grid.truncated) truncated = true
                Section.Grid(
                    title = sheet.name,
                    rows = grid.rows,
                    columnWidthsPt = grid.columnWidths,
                    frozenRows = 0,
                )
            }
            if (sections.isEmpty()) return@use null

            OfficeDocument(
                kind = OfficeKind.SPREADSHEET,
                displayName = displayName,
                sections = sections,
                outline = sections.mapIndexed { index, section -> OutlineEntry(section.title, index) },
                limitationNote = if (truncated) {
                    "This workbook is larger than $MAX_ROWS rows a sheet; the rest is not shown."
                } else {
                    null
                },
            )
        }
    }

    private class SheetRef(val name: String, val part: String)

    private fun readSheetList(
        pack: OoxmlPackage,
        workbookPart: String,
        relationships: Map<String, String>,
    ): List<SheetRef> {
        val data = pack.bytes(workbookPart) ?: return emptyList()
        val sheets = mutableListOf<SheetRef>()
        OoxmlPackage.parse(data) { parser ->
            if (parser.eventType == XmlPullParser.START_TAG &&
                OoxmlPackage.localName(parser.name) == "sheet"
            ) {
                val name = OoxmlPackage.attr(parser, "name") ?: "Sheet ${sheets.size + 1}"
                val id = OoxmlPackage.attr(parser, "id")
                val part = relationships[id]
                    ?: "xl/worksheets/sheet${sheets.size + 1}.xml"
                if (pack.exists(part)) sheets += SheetRef(name, part)
            }
        }
        return sheets
    }

    /**
     * The shared string table.
     *
     * Excel stores every distinct piece of text once and refers to it by number, which is why a
     * sheet's own XML is mostly integers. A rich-text string is split into runs; the formatting is
     * dropped here and the pieces joined, because a spreadsheet cell is drawn as one line anyway.
     */
    private fun readSharedStrings(pack: OoxmlPackage): List<String> {
        val data = pack.bytes("xl/sharedStrings.xml") ?: return emptyList()
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inItem = false
        var inText = false
        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "si" -> {
                        inItem = true
                        current.setLength(0)
                    }
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inItem && inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "t" -> inText = false
                    "si" -> {
                        strings += current.toString()
                        inItem = false
                    }
                }
            }
        }
        return strings
    }

    /** What a cell's style index means: is it bold, and what number format does it use. */
    private class CellStyles(
        val numberFormats: Map<Int, String>,
        val formatOfXf: List<Int>,
        val boldOfXf: List<Boolean>,
    ) {
        fun formatFor(styleIndex: Int): String? {
            val id = formatOfXf.getOrNull(styleIndex) ?: return null
            numberFormats[id]?.let { return it }
            return builtInFormat(id)
        }

        fun boldFor(styleIndex: Int): Boolean = boldOfXf.getOrNull(styleIndex) ?: false
    }

    private fun readStyles(pack: OoxmlPackage): CellStyles {
        val data = pack.bytes("xl/styles.xml")
            ?: return CellStyles(emptyMap(), emptyList(), emptyList())
        val numberFormats = mutableMapOf<Int, String>()
        val formatOfXf = mutableListOf<Int>()
        val boldOfXf = mutableListOf<Boolean>()
        val fontBold = mutableListOf<Boolean>()

        var inCellXfs = false
        var inFonts = false
        var currentFontBold = false

        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "numFmt" -> {
                        val id = OoxmlPackage.attr(parser, "numFmtId")?.toIntOrNull()
                        val code = OoxmlPackage.attr(parser, "formatCode")
                        if (id != null && code != null) numberFormats[id] = code
                    }
                    "fonts" -> inFonts = true
                    "font" -> if (inFonts) currentFontBold = false
                    "b" -> if (inFonts) currentFontBold = OoxmlPackage.isOn(OoxmlPackage.attr(parser, "val"))
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) {
                        formatOfXf += OoxmlPackage.attr(parser, "numFmtId")?.toIntOrNull() ?: 0
                        val fontId = OoxmlPackage.attr(parser, "fontId")?.toIntOrNull() ?: 0
                        boldOfXf += fontBold.getOrNull(fontId) ?: false
                    }
                }
                XmlPullParser.END_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "font" -> if (inFonts) fontBold += currentFontBold
                    "fonts" -> inFonts = false
                    "cellXfs" -> inCellXfs = false
                }
            }
        }
        return CellStyles(numberFormats, formatOfXf, boldOfXf)
    }

    private class SheetContents(
        val rows: List<List<GridCell>>,
        val columnWidths: List<Float>,
        val truncated: Boolean,
    )

    private fun readSheet(
        data: ByteArray,
        sharedStrings: List<String>,
        styles: CellStyles,
    ): SheetContents {
        val rows = mutableListOf<MutableList<GridCell>>()
        val declaredWidths = mutableMapOf<Int, Float>()
        var widestColumn = 0
        var truncated = false

        var rowIndex = -1
        var column = 0
        var cellType: String? = null
        var styleIndex = 0
        val value = StringBuilder()
        var inValue = false
        var inInlineText = false

        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "col" -> {
                        val from = OoxmlPackage.attr(parser, "min")?.toIntOrNull() ?: return@parse
                        val to = OoxmlPackage.attr(parser, "max")?.toIntOrNull() ?: from
                        val width = OoxmlPackage.attr(parser, "width")?.toFloatOrNull() ?: return@parse
                        // Excel's column width is in characters of the default font; the usual
                        // conversion is seven pixels a character at 96 dpi, which is 5.25 points.
                        for (index in from..minOf(to, MAX_COLUMNS)) {
                            declaredWidths[index - 1] = (width * CHAR_WIDTH_PT).coerceIn(18f, 260f)
                        }
                    }
                    "row" -> {
                        val declared = OoxmlPackage.attr(parser, "r")?.toIntOrNull()
                        rowIndex = (declared?.minus(1)) ?: (rowIndex + 1)
                        column = 0
                        if (rowIndex >= MAX_ROWS) truncated = true
                        while (rows.size <= minOf(rowIndex, MAX_ROWS - 1)) rows.add(mutableListOf())
                    }
                    "c" -> {
                        val reference = OoxmlPackage.attr(parser, "r")
                        column = reference?.let { columnOf(it) } ?: column
                        cellType = OoxmlPackage.attr(parser, "t")
                        styleIndex = OoxmlPackage.attr(parser, "s")?.toIntOrNull() ?: 0
                        value.setLength(0)
                    }
                    "v" -> inValue = true
                    "t" -> inInlineText = true
                }
                XmlPullParser.TEXT -> if (inValue || inInlineText) value.append(parser.text)
                XmlPullParser.END_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        if (rowIndex in 0 until MAX_ROWS && column in 0 until MAX_COLUMNS) {
                            val text = displayValue(value.toString(), cellType, styleIndex, sharedStrings, styles)
                            if (text.isNotEmpty()) {
                                val row = rows[rowIndex]
                                while (row.size <= column) row += EMPTY_CELL
                                row[column] = GridCell(
                                    text = text,
                                    bold = styles.boldFor(styleIndex),
                                    align = if (isNumeric(cellType, text)) Align.RIGHT else Align.LEFT,
                                )
                                if (column > widestColumn) widestColumn = column
                            }
                        }
                        column++
                    }
                }
            }
        }

        val columnCount = (widestColumn + 1).coerceAtLeast(1)
        val widths = (0 until columnCount).map { declaredWidths[it] ?: DEFAULT_COLUMN_PT }
        // Trim the trailing empty rows Excel likes to leave behind.
        val lastUsed = rows.indexOfLast { row -> row.any { it.text.isNotEmpty() } }
        val kept = if (lastUsed >= 0) rows.subList(0, lastUsed + 1) else emptyList()
        return SheetContents(kept.map { it.toList() }, widths, truncated)
    }

    private val EMPTY_CELL = GridCell("")

    private fun isNumeric(type: String?, text: String): Boolean =
        type != "s" && type != "str" && type != "inlineStr" &&
            text.isNotEmpty() && text.first().let { it.isDigit() || it == '-' || it == '.' }

    /** "BC12" names column 54; the letters are base-26 with no zero. */
    private fun columnOf(reference: String): Int {
        var column = 0
        for (character in reference) {
            if (!character.isLetter()) break
            column = column * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return (column - 1).coerceAtLeast(0)
    }

    private fun displayValue(
        raw: String,
        type: String?,
        styleIndex: Int,
        sharedStrings: List<String>,
        styles: CellStyles,
    ): String {
        if (raw.isEmpty()) return ""
        return when (type) {
            "s" -> raw.trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
            "inlineStr", "str" -> raw
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            "e" -> raw
            else -> {
                val number = raw.trim().toDoubleOrNull() ?: return raw
                formatNumber(number, styles.formatFor(styleIndex))
            }
        }
    }

    /**
     * Turn a stored number into what Excel would show for it.
     *
     * Only the shapes that actually appear in real files are handled — dates, times, percentages,
     * a fixed number of decimals, thousands separators — and anything else falls back to a plain,
     * honest number. A half-implemented format language that quietly shows the wrong figure would
     * be far worse than one that shows the right figure plainly.
     */
    private fun formatNumber(number: Double, format: String?): String {
        val code = format?.substringBefore(';')?.trim()
        if (code != null && looksLikeDate(code)) return formatDate(number, code)

        val percent = code?.contains('%') == true
        val scaled = if (percent) number * 100 else number
        val decimals = code?.substringAfter('.', "")?.takeWhile { it == '0' || it == '#' }?.count { it == '0' }
        val grouped = code?.contains(",") == true && code.contains("#,#")

        val text = when {
            decimals != null && decimals > 0 ->
                String.format(Locale.US, "%,.${decimals}f", scaled).let {
                    if (grouped) it else it.replace(",", "")
                }
            abs(scaled - scaled.roundToLong()) < 1e-9 -> {
                val whole = scaled.roundToLong()
                if (grouped) String.format(Locale.US, "%,d", whole) else whole.toString()
            }
            else -> trimZeros(String.format(Locale.US, "%.6f", scaled))
        }
        return if (percent) "$text%" else text
    }

    private fun trimZeros(text: String): String =
        if (!text.contains('.')) text else text.trimEnd('0').trimEnd('.')

    private fun looksLikeDate(code: String): Boolean {
        val stripped = code.replace(Regex("\\[[^]]*]"), "").replace("\"[^\"]*\"".toRegex(), "")
        return stripped.any { it == 'y' || it == 'd' } ||
            (stripped.contains('m') && stripped.contains(':').not() && stripped.contains('/')) ||
            stripped.contains("h:") || stripped.contains("hh:")
    }

    /**
     * Excel counts days from 1900, and believes 1900 was a leap year.
     *
     * That bug is part of the file format — every date in every workbook is offset by it — so the
     * epoch used here is 30 December 1899, which cancels it out for every date from March 1900 on.
     */
    private fun formatDate(serial: Double, code: String): String {
        val days = floor(serial).toLong()
        val fraction = serial - days
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
            add(Calendar.DAY_OF_MONTH, days.toInt())
            add(Calendar.SECOND, (fraction * 86400).roundToLong().toInt())
        }
        val hasDate = code.any { it == 'y' || it == 'd' } || code.contains('/')
        val hasTime = code.contains(':')
        val date = String.format(
            Locale.US,
            "%02d/%02d/%04d",
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.YEAR),
        )
        val time = String.format(
            Locale.US,
            "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
        return when {
            hasDate && hasTime -> "$date $time"
            hasTime -> time
            else -> date
        }
    }

    /** The number formats Excel does not write down because every copy of Excel already knows them. */
    private fun builtInFormat(id: Int): String? = when (id) {
        1 -> "0"
        2 -> "0.00"
        3 -> "#,##0"
        4 -> "#,##0.00"
        9 -> "0%"
        10 -> "0.00%"
        11 -> "0.00E+00"
        14 -> "dd/mm/yyyy"
        15 -> "d-mmm-yy"
        16 -> "d-mmm"
        17 -> "mmm-yy"
        18 -> "h:mm AM/PM"
        19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"
        21 -> "h:mm:ss"
        22 -> "dd/mm/yyyy h:mm"
        45 -> "mm:ss"
        46 -> "[h]:mm:ss"
        47 -> "mm:ss.0"
        else -> null
    }

    /** One character of Excel's default font, in points. */
    private const val CHAR_WIDTH_PT = 5.25f
    private const val DEFAULT_COLUMN_PT = 48f

    /** Past this a sheet stops being something anyone reads on a phone. */
    private const val MAX_ROWS = 20000
    private const val MAX_COLUMNS = 128
}
