package top.teamaos.pdfreader.office

import org.xmlpull.v1.XmlPullParser
import java.io.File
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

            // One set of limits for the whole workbook: it is the total that has to fit in memory.
            val limits = GridLimits()
            val sections = sheets.mapNotNull { sheet ->
                checkCancelled()
                val contents = readSheet(open, sheet.part, sharedStrings, styles, limits)
                    ?: return@mapNotNull null
                Section.Grid(
                    title = sheet.name,
                    grid = contents.grid,
                    columnWidthsPt = contents.columnWidths,
                )
            }
            if (sections.isEmpty()) return@use null

            OfficeDocument(
                kind = OfficeKind.SPREADSHEET,
                displayName = displayName,
                sections = sections,
                outline = sections.mapIndexed { index, section -> OutlineEntry(section.title, index) },
                limitationNote = limits.note,
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

        private val looks = HashMap<Int, NumberLook>()

        fun lookFor(styleIndex: Int): NumberLook = looks.getOrPut(styleIndex) { lookOf(formatFor(styleIndex)) }
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

    private class SheetContents(val grid: GridData, val columnWidths: List<Float>)

    /**
     * One sheet, streamed out of the zip and straight into a [GridBuilder].
     *
     * Linear in the size of the sheet and allocation-light on purpose: per cell there is one
     * string for its text and nothing else, number formats are worked out once per style rather
     * than once per cell, and dates are computed with arithmetic instead of a Calendar.
     */
    private fun readSheet(
        pack: OoxmlPackage,
        part: String,
        sharedStrings: List<String>,
        styles: CellStyles,
        limits: GridLimits,
    ): SheetContents? {
        val builder = GridBuilder(limits)
        val declaredWidths = HashMap<Int, Float>()
        // Style index and kind of cell to the grid's own style, worked out once per combination.
        val gridStyles = HashMap<Int, Int>()

        var rowIndex = -1
        var column = 0
        var cellType: String? = null
        var styleIndex = 0
        val value = StringBuilder()
        var inValue = false
        var inInlineText = false
        var rowsSeen = 0

        val found = OoxmlPackage.parseEntry(pack, part) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "c" -> {
                        val reference = OoxmlPackage.attr(parser, "r")
                        column = reference?.let { columnOf(it) } ?: column
                        cellType = OoxmlPackage.attr(parser, "t")
                        styleIndex = OoxmlPackage.attr(parser, "s")?.toIntOrNull() ?: 0
                        value.setLength(0)
                    }
                    "v" -> inValue = true
                    "t" -> inInlineText = true
                    "row" -> {
                        val declared = OoxmlPackage.attr(parser, "r")?.toIntOrNull()
                        rowIndex = (declared?.minus(1))?.coerceAtLeast(rowIndex) ?: (rowIndex + 1)
                        column = 0
                        if (++rowsSeen and 0x3FF == 0) checkCancelled()
                        if (builder.full) throw OoxmlPackage.Stop()
                    }
                    "col" -> {
                        // A zero or negative min would make this loop run billions of times.
                        val from = OoxmlPackage.attr(parser, "min")?.toIntOrNull()?.coerceAtLeast(1)
                            ?: return@parseEntry
                        val to = OoxmlPackage.attr(parser, "max")?.toIntOrNull() ?: from
                        val width = OoxmlPackage.attr(parser, "width")?.toFloatOrNull()
                            ?.takeIf { it.isFinite() } ?: return@parseEntry
                        // Excel's column width is in characters of the default font; the usual
                        // conversion is seven pixels a character at 96 dpi, which is 5.25 points.
                        for (index in from..minOf(to, MAX_COLUMNS)) {
                            declaredWidths[index - 1] = (width * CHAR_WIDTH_PT).coerceIn(18f, 260f)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inValue || inInlineText) value.append(parser.text)
                XmlPullParser.END_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        if (rowIndex >= 0 && column in 0 until MAX_COLUMNS && value.isNotEmpty()) {
                            val text = displayValue(value.toString(), cellType, styleIndex, sharedStrings, styles)
                            if (text.isNotEmpty()) {
                                val numeric = isNumeric(cellType)
                                val bold = styles.boldFor(styleIndex)
                                // Plain cells need no stored style: the grid right-aligns what
                                // reads as a number by itself. Only cells where that guess would
                                // be wrong, or that are bold, carry one.
                                val auto = GridData.looksNumeric(text, 0, text.length)
                                val style = if (!bold && auto == numeric) {
                                    0
                                } else {
                                    val key = styleIndex * 2 + if (numeric) 1 else 0
                                    gridStyles.getOrPut(key) {
                                        builder.styleOf(
                                            GridStyle(bold = bold, align = if (numeric) Align.RIGHT else Align.LEFT),
                                        )
                                    }
                                }
                                builder.put(rowIndex, column, text, style)
                            }
                        }
                        column++
                    }
                }
            }
        }
        if (!found) return null

        val grid = builder.build()
        val widths = (0 until grid.columnCount).map { declaredWidths[it] ?: DEFAULT_COLUMN_PT }
        return SheetContents(grid, widths)
    }

    /** Whether Excel stored the cell as a number (or a date, which is a number in a costume). */
    private fun isNumeric(type: String?): Boolean =
        type == null || type == "n" || type == "d"

    /** "BC12" names column 54; the letters are base-26 with no zero. */
    private fun columnOf(reference: String): Int {
        var column = 0
        for (character in reference) {
            if (!character.isLetter()) break
            column = column * 26 + (character.uppercaseChar() - 'A' + 1)
            // Far past any real column; stop before a long run of letters overflows into one.
            if (column > MAX_COLUMN_REFERENCE) return MAX_COLUMN_REFERENCE
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
                // "NaN" and "1e999" parse as numbers that every formatter below chokes on, and
                // they are not numbers anyone typed; show them as the text they are.
                val number = raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return raw
                formatNumber(number, styles.lookFor(styleIndex))
            }
        }
    }

    /**
     * What a number format asks for, worked out once per style.
     *
     * Reading the format code — stripping colours and quoted text, finding decimals and date parts
     * — used to happen for every cell, with two regular expressions each time; on a sheet of half
     * a million numbers that alone took longer than everything else put together.
     */
    private class NumberLook(
        val date: Boolean,
        val hasDate: Boolean,
        val hasTime: Boolean,
        val percent: Boolean,
        val decimals: Int?,
        val grouped: Boolean,
    )

    private val PLAIN_NUMBER = NumberLook(false, false, false, false, null, false)

    private fun lookOf(format: String?): NumberLook {
        val code = format?.substringBefore(';')?.trim() ?: return PLAIN_NUMBER
        if (looksLikeDate(code)) {
            return NumberLook(
                date = true,
                hasDate = code.any { it == 'y' || it == 'd' } || code.contains('/'),
                hasTime = code.contains(':'),
                percent = false,
                decimals = null,
                grouped = false,
            )
        }
        return NumberLook(
            date = false,
            hasDate = false,
            hasTime = false,
            percent = code.contains('%'),
            decimals = code.substringAfter('.', "").takeWhile { it == '0' || it == '#' }
                .count { it == '0' }.coerceAtMost(MAX_DECIMALS),
            grouped = code.contains("#,#"),
        )
    }

    /**
     * Turn a stored number into what Excel would show for it.
     *
     * Only the shapes that actually appear in real files are handled — dates, times, percentages,
     * a fixed number of decimals, thousands separators — and anything else falls back to a plain,
     * honest number. A half-implemented format language that quietly shows the wrong figure would
     * be far worse than one that shows the right figure plainly.
     */
    private fun formatNumber(number: Double, look: NumberLook): String {
        if (look.date && abs(number) < MAX_DATE_SERIAL) return formatDate(number, look)

        val scaled = if (look.percent) number * 100 else number
        if (!scaled.isFinite()) return number.toString()
        val decimals = look.decimals
        val text = when {
            decimals != null && decimals > 0 -> {
                // Rounded from the shortest decimal form of the double, half up — the same figure
                // String.format gives, without its cost.
                val fixed = java.math.BigDecimal(scaled.toString())
                    .setScale(decimals, java.math.RoundingMode.HALF_UP)
                    .toPlainString()
                if (look.grouped) grouped(fixed) else fixed
            }
            abs(scaled - scaled.roundToLong()) < 1e-9 -> {
                val whole = scaled.roundToLong().toString()
                if (look.grouped) grouped(whole) else whole
            }
            else -> trimZeros(
                java.math.BigDecimal(scaled.toString())
                    .setScale(6, java.math.RoundingMode.HALF_UP)
                    .toPlainString(),
            )
        }
        return if (look.percent) "$text%" else text
    }

    /** Thousands separators in the whole-number part of a plain decimal string. */
    private fun grouped(text: String): String {
        val negative = text.startsWith('-')
        val digitsStart = if (negative) 1 else 0
        val point = text.indexOf('.').let { if (it < 0) text.length else it }
        val whole = point - digitsStart
        if (whole <= 3) return text
        val out = StringBuilder(text.length + whole / 3)
        if (negative) out.append('-')
        for (index in digitsStart until point) {
            if (index > digitsStart && (point - index) % 3 == 0) out.append(',')
            out.append(text[index])
        }
        out.append(text, point, text.length)
        return out.toString()
    }

    private fun trimZeros(text: String): String =
        if (!text.contains('.')) text else text.trimEnd('0').trimEnd('.')

    private fun looksLikeDate(code: String): Boolean {
        val stripped = code.replace(BRACKETED, "").replace(QUOTED, "")
        return stripped.any { it == 'y' || it == 'd' } ||
            (stripped.contains('m') && stripped.contains(':').not() && stripped.contains('/')) ||
            stripped.contains("h:") || stripped.contains("hh:")
    }

    /**
     * Excel counts days from 1900, and believes 1900 was a leap year.
     *
     * That bug is part of the file format — every date in every workbook is offset by it — so the
     * epoch used here is 30 December 1899, which cancels it out for every date from March 1900 on.
     * The calendar date is computed directly (the civil-from-days method), which is exact for
     * every date and costs a few divisions instead of a Calendar per cell.
     */
    private fun formatDate(serial: Double, look: NumberLook): String {
        var days = floor(serial).toLong()
        var seconds = ((serial - days) * 86400).roundToLong()
        if (seconds >= 86400) {
            days++
            seconds -= 86400
        }
        // Days since 1970-01-01, then to year, month and day.
        val z = days - EXCEL_EPOCH_TO_UNIX_DAYS + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val dayOfEra = z - era * 146097
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val shifted = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * shifted + 2) / 5 + 1
        val month = if (shifted < 10) shifted + 3 else shifted - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0

        val out = StringBuilder(16)
        if (look.hasDate || !look.hasTime) {
            pad(out, day, 2).append('/')
            pad(out, month, 2).append('/')
            pad(out, year, 4)
        }
        if (look.hasTime) {
            if (out.isNotEmpty()) out.append(' ')
            pad(out, seconds / 3600, 2).append(':')
            pad(out, seconds % 3600 / 60, 2)
        }
        return out.toString()
    }

    private fun pad(out: StringBuilder, value: Long, width: Int): StringBuilder {
        val text = value.toString()
        repeat(width - text.length) { out.append('0') }
        return out.append(text)
    }

    /** 1899-12-30 to 1970-01-01. */
    private const val EXCEL_EPOCH_TO_UNIX_DAYS = 25569L

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

    /**
     * `[Red]`, `[$-409]` and the like. The closing bracket is escaped inside the class: whether
     * `[^]]` means "not a bracket" differs between regex engines, and Android's is ICU.
     * Compiled once rather than for every formatted cell.
     */
    private val BRACKETED = Regex("\\[[^\\]]*\\]")
    private val QUOTED = Regex("\"[^\"]*\"")

    /** Year 9999 is serial 2958465; anything beyond is not a date Calendar should be asked about. */
    private const val MAX_DATE_SERIAL = 3_000_000.0
    private const val MAX_DECIMALS = 30
    private const val MAX_COLUMN_REFERENCE = 1 shl 20

    /** One character of Excel's default font, in points. */
    private const val CHAR_WIDTH_PT = 5.25f
    private const val DEFAULT_COLUMN_PT = 48f

    /** Past this a sheet stops being something anyone reads on a phone. */
    private const val MAX_COLUMNS = GridBuilder.MAX_COLUMNS
}
