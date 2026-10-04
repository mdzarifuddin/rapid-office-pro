package top.teamaos.pdfreader.office

/**
 * A spreadsheet's cells, stored for a phone's heap rather than for convenience.
 *
 * A sheet of 250,000 rows by 12 columns is three million cells. As one object per cell — a text,
 * a style, a list to hold them — that is hundreds of megabytes before anything is drawn, which is
 * what made big CSV files take half a minute and most of a 2 GB phone to open. Here a row is one
 * string with its cells joined by [SEPARATOR], and a row's styles are one small int array that
 * exists only when something in the row is not plain. Cells are found by scanning the row string,
 * which costs nothing next to drawing them, and only ever happens for the cells on screen.
 */
class GridData internal constructor(
    private val rows: Array<String>,
    /** Per-row style indexes into [styles], or null for a row (or a whole sheet) of plain cells. */
    private val rowStyles: Array<IntArray?>?,
    val styles: List<GridStyle>,
    val columnCount: Int,
) {
    val rowCount: Int get() = rows.size

    /** The whole row, cells separated by [SEPARATOR]. For drawing and search without copies. */
    fun rowText(row: Int): String = rows[row]

    /** The text of one cell; empty when the cell is. Allocates — for a tap, not for every frame. */
    fun text(row: Int, column: Int): String {
        if (row !in rows.indices) return ""
        val line = rows[row]
        val start = cellStart(line, column)
        if (start < 0) return ""
        return line.substring(start, cellEnd(line, start))
    }

    fun style(row: Int, column: Int): GridStyle {
        val index = rowStyles?.getOrNull(row)?.getOrNull(column) ?: 0
        return styles.getOrElse(index) { styles[0] }
    }

    fun hasStyles(): Boolean = rowStyles != null

    companion object {
        /** ASCII unit separator: made for exactly this, and never in a cell a person typed. */
        const val SEPARATOR = '\u001F'

        /** Where [column] starts in [line], or -1 if the row has fewer cells than that. */
        fun cellStart(line: String, column: Int): Int {
            var at = 0
            var remaining = column
            while (remaining > 0) {
                val next = line.indexOf(SEPARATOR, at)
                if (next < 0) return -1
                at = next + 1
                remaining--
            }
            return at
        }

        /**
         * Whether a cell reads as a number — "1,234.50", "-3", "12%", "03/04/2026", "10:30" — so
         * it is right-aligned the way a spreadsheet shows numbers. Decided while drawing, for the
         * cells on screen, so that plain sheets need no stored style at all.
         */
        fun looksNumeric(text: CharSequence, start: Int, end: Int): Boolean {
            var first = start
            while (first < end && text[first] == ' ') first++
            if (first >= end) return false
            val lead = text[first]
            if (!(lead in '0'..'9' || lead == '-' || lead == '+' || lead == '.')) return false
            var digits = 0
            for (index in first until end) {
                when (text[index]) {
                    in '0'..'9' -> digits++
                    '-', '+', '.', ',', 'e', 'E', '%', '/', ':', ' ' -> Unit
                    else -> return false
                }
            }
            return digits > 0
        }

        /** Where the cell that starts at [start] ends. */
        fun cellEnd(line: String, start: Int): Int {
            val next = line.indexOf(SEPARATOR, start)
            return if (next < 0) line.length else next
        }
    }
}

/** How a cell looks. [align] null means "right if it reads as a number", as a spreadsheet does. */
data class GridStyle(
    val bold: Boolean = false,
    val align: Align? = null,
    val background: Int = 0,
    val colour: Int = 0xFF000000.toInt(),
)

/**
 * How much of a workbook one document may hold, shared by all its sheets.
 *
 * Sized for a 2 GB phone: a million rows, four million cells and forty million characters is
 * comfortably within a large heap in the compact form above, and well past any sheet anyone reads
 * on a phone. Past it, the rest is left out and the reader told.
 */
class GridLimits(
    val maxRows: Int = 1_000_000,
    val maxCells: Long = 4_000_000,
    val maxChars: Long = 40_000_000,
) {
    var cells = 0L
        internal set
    var chars = 0L
        internal set
    var truncated = false
        internal set

    val note: String?
        get() = if (truncated) {
            "This file is too large to show in full; only the first part of it is shown."
        } else {
            null
        }
}

/**
 * Builds a [GridData] row by row.
 *
 * Rows must arrive in order, as every spreadsheet format stores them; cells within a row may come
 * in any order. Gaps — rows a sheet skips — cost one shared empty string each, and empty rows at
 * the end are dropped.
 */
class GridBuilder(private val limits: GridLimits, private val maxColumns: Int = MAX_COLUMNS) {

    private val rows = ArrayList<String>()
    private var rowStyles: ArrayList<IntArray?>? = null
    private val styles = arrayListOf(GridStyle())
    private val styleIndex = hashMapOf(GridStyle() to 0)

    private var currentRow = -1
    private val cells = ArrayList<String?>()
    private var cellStyles = IntArray(16)
    private var styledCells = false
    private val joined = StringBuilder()

    private var columnCount = 0
    private var lastUsedRow = -1

    /** True once a limit is reached: callers stop reading, there is nowhere to put more. */
    var full = false
        private set

    /** The index of [style] in this grid's style table, adding it if it is new. */
    fun styleOf(style: GridStyle): Int = styleIndex.getOrPut(style) {
        styles += style
        styles.size - 1
    }

    /** Put one cell. A row before the current one is treated as the current one. */
    fun put(row: Int, column: Int, text: String, style: Int = 0) {
        if (full || column < 0 || column >= maxColumns || row < 0) return
        if (text.isEmpty() && style == 0) return
        if (row != currentRow && row > currentRow) {
            flushRow()
            if (full) return
            if (row >= limits.maxRows) {
                stop()
                return
            }
            currentRow = row
        }
        while (cells.size <= column) cells += null
        cells[column] = clean(text)
        if (style != 0) {
            if (cellStyles.size <= column) cellStyles = cellStyles.copyOf(maxOf(column + 1, cellStyles.size * 2))
            cellStyles[column] = style
            styledCells = true
        }
    }

    /**
     * Append a row that is already joined with [GridData.SEPARATOR] — the CSV reader builds rows
     * this way so that no cell ever becomes an object of its own. [cellCount] is how many cells
     * it holds, for the limits.
     */
    fun addJoinedRow(row: CharSequence, cellCount: Int) {
        if (full) return
        flushRow()
        val index = maxOf(rows.size, currentRow + 1)
        if (index >= limits.maxRows) {
            stop()
            return
        }
        var end = row.length
        while (end > 0 && row[end - 1] == GridData.SEPARATOR) end--
        if (end == 0) {
            currentRow = index
            padTo(index)
            rows += ""
            return
        }
        if (!account(cellCount, end)) return
        padTo(index)
        rows += row.subSequence(0, end).toString()
        rowStyles?.add(null)
        columnCount = maxOf(columnCount, minOf(cellCount, maxColumns))
        currentRow = index
        lastUsedRow = index
    }

    /** Finish the row in progress; the next [put] for a later row would do the same. */
    fun endRow() = flushRow()

    fun build(): GridData {
        flushRow()
        // Trailing empty rows, which spreadsheets like to leave behind, are not part of the sheet.
        while (rows.size > lastUsedRow + 1) {
            rows.removeAt(rows.size - 1)
            rowStyles?.let { if (it.size > rows.size) it.removeAt(it.size - 1) }
        }
        val styleArray = rowStyles?.let { list ->
            while (list.size < rows.size) list += null
            list.toTypedArray()
        }
        return GridData(rows.toTypedArray(), styleArray, styles.toList(), maxOf(columnCount, 1))
    }

    private fun flushRow() {
        if (currentRow < 0 || cells.isEmpty()) {
            cells.clear()
            return
        }
        var last = cells.size - 1
        while (last >= 0 && cells[last].isNullOrEmpty() && (!styledCells || cellStyles.getOrElse(last) { 0 } == 0)) last--
        if (last < 0) {
            resetRow()
            return
        }
        joined.setLength(0)
        var filled = 0
        for (index in 0..last) {
            if (index > 0) joined.append(GridData.SEPARATOR)
            val text = cells[index]
            if (!text.isNullOrEmpty()) {
                joined.append(text)
                filled++
            }
        }
        if (!account(filled, joined.length)) {
            resetRow()
            return
        }
        padTo(currentRow)
        rows += if (last == 0) cells[0].orEmpty() else joined.toString()
        if (styledCells) {
            val list = rowStyles ?: ArrayList<IntArray?>().also { created ->
                repeat(rows.size - 1) { created += null }
                rowStyles = created
            }
            while (list.size < rows.size - 1) list += null
            list += cellStyles.copyOf(last + 1)
        } else {
            rowStyles?.add(null)
        }
        columnCount = maxOf(columnCount, last + 1)
        lastUsedRow = currentRow
        resetRow()
    }

    private fun resetRow() {
        cells.clear()
        if (styledCells) cellStyles.fill(0)
        styledCells = false
    }

    /** Empty rows up to (not including) [row], so the next row added lands at its index. */
    private fun padTo(row: Int) {
        while (rows.size < row) {
            rows += ""
            rowStyles?.add(null)
        }
    }

    private fun account(cellCount: Int, length: Int): Boolean {
        if (limits.cells + cellCount > limits.maxCells || limits.chars + length > limits.maxChars) {
            stop()
            return false
        }
        limits.cells += cellCount
        limits.chars += length
        return true
    }

    private fun stop() {
        full = true
        limits.truncated = true
    }

    /**
     * One line, without the separator: a cell is drawn on a single line, and a separator inside
     * one would split it in two.
     */
    private fun clean(text: String): String {
        var dirty = false
        for (character in text) {
            if (character == GridData.SEPARATOR || character == '\n' || character == '\r' || character == '\t') {
                dirty = true
                break
            }
        }
        if (!dirty) return text
        return buildString(text.length) {
            for (character in text) {
                append(
                    when (character) {
                        GridData.SEPARATOR, '\n', '\r', '\t' -> ' '
                        else -> character
                    },
                )
            }
        }
    }

    companion object {
        const val MAX_COLUMNS = 256
    }
}

/**
 * Stop here if the screen that asked for this document has gone away.
 *
 * Parsing and laying out run on a background thread that is interrupted when the reader backs
 * out; checking in the loops that matter is what lets a 20 MB file stop in a moment rather than
 * finish reading for nobody.
 */
internal fun checkCancelled() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
}
