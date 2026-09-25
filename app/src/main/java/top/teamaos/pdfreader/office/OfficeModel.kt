package top.teamaos.pdfreader.office

/**
 * What a document turns into once it has been read, before anything is measured or drawn.
 *
 * Deliberately one model for every format. A Word file, a spreadsheet and a slide deck are three
 * very different things on disk, but on screen they are all "pages with things placed on them", and
 * having one model means the layout engine, the renderer, search, colour modes and text selection
 * are written once instead of three times.
 *
 * Everything here is in **points** — 1/72 inch — which is the unit both Office formats and PDF
 * think in, so nothing has to be converted twice.
 */

/** The formats the reader knows about. */
enum class OfficeKind(val label: String) {
    WORD("Word"),
    SPREADSHEET("Spreadsheet"),
    SLIDES("Slides"),
    TEXT("Text"),
    ;
}

/** A run of characters that all look the same. */
data class TextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float = DEFAULT_SIZE_PT,
    val colour: Int = 0xFF000000.toInt(),
    val monospace: Boolean = false,
    val highlight: Int = 0,
    /** -1 below the line, 0 on it, 1 above it. */
    val script: Int = 0,
) {
    companion object {
        const val DEFAULT_SIZE_PT = 11f
    }
}

enum class Align { LEFT, CENTRE, RIGHT, JUSTIFY }

/** One paragraph: the spans in it and how the block of them sits on the page. */
data class Paragraph(
    val spans: List<TextSpan>,
    val align: Align = Align.LEFT,
    val indentPt: Float = 0f,
    val hangingPt: Float = 0f,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 6f,
    val lineSpacing: Float = 1f,
    /** Bullet or number already rendered as text, e.g. "•" or "3.". Null for a plain paragraph. */
    val marker: String? = null,
    val background: Int = 0,
    /** Drawn as a rule under the paragraph; used for headings and horizontal rules. */
    val ruleBelow: Boolean = false,
) {
    val plainText: String get() = spans.joinToString("") { it.text }
}

/** One cell of a table. */
data class TableCell(
    val paragraphs: List<Paragraph>,
    val background: Int = 0,
    val columnSpan: Int = 1,
    val align: Align? = null,
)

data class TableRow(val cells: List<TableCell>, val isHeader: Boolean = false)

/** Something placed on a page. */
sealed class Block {
    data class Text(val paragraph: Paragraph) : Block()

    /** [data] is the encoded image exactly as it was stored; decoding happens at layout time. */
    data class Picture(
        val data: ByteArray,
        val widthPt: Float,
        val heightPt: Float,
    ) : Block() {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Table(val rows: List<TableRow>, val columnWidthsPt: List<Float>?) : Block()

    /** A hard page break asked for by the document. */
    object PageBreak : Block()
}

/** One text box or picture on a slide, positioned absolutely the way slides work. */
sealed class SlideItem {
    abstract val xPt: Float
    abstract val yPt: Float
    abstract val widthPt: Float
    abstract val heightPt: Float

    data class Text(
        override val xPt: Float,
        override val yPt: Float,
        override val widthPt: Float,
        override val heightPt: Float,
        val paragraphs: List<Paragraph>,
        val fill: Int = 0,
        /** 0 top, 1 middle, 2 bottom. */
        val verticalAlign: Int = 0,
    ) : SlideItem()

    data class Picture(
        override val xPt: Float,
        override val yPt: Float,
        override val widthPt: Float,
        override val heightPt: Float,
        val data: ByteArray,
    ) : SlideItem() {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Shape(
        override val xPt: Float,
        override val yPt: Float,
        override val widthPt: Float,
        override val heightPt: Float,
        val fill: Int,
        val stroke: Int,
    ) : SlideItem()
}

/** A spreadsheet cell, already turned into the text a reader should see. */
data class GridCell(
    val text: String,
    val bold: Boolean = false,
    val align: Align = Align.LEFT,
    val background: Int = 0,
    val colour: Int = 0xFF000000.toInt(),
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
)

/**
 * A part of a document that is laid out as one unit.
 *
 * A Word file is a single [Flow] however long it is; a workbook is one [Grid] per sheet; a deck is
 * one [Slide] per slide. Sections are what the contents list offers to jump between.
 */
sealed class Section {
    abstract val title: String

    data class Flow(
        override val title: String,
        val blocks: List<Block>,
        val pageWidthPt: Float = LETTER_WIDTH_PT,
        val pageHeightPt: Float = LETTER_HEIGHT_PT,
        val marginLeftPt: Float = 72f,
        val marginRightPt: Float = 72f,
        val marginTopPt: Float = 72f,
        val marginBottomPt: Float = 72f,
    ) : Section()

    data class Grid(
        override val title: String,
        val rows: List<List<GridCell>>,
        val columnWidthsPt: List<Float>,
        val frozenRows: Int = 0,
    ) : Section()

    data class Slide(
        override val title: String,
        val widthPt: Float,
        val heightPt: Float,
        val items: List<SlideItem>,
        val background: Int = 0xFFFFFFFF.toInt(),
        val notes: String = "",
    ) : Section()

    companion object {
        /** US Letter, which is what Office falls back to when a file does not say. */
        const val LETTER_WIDTH_PT = 612f
        const val LETTER_HEIGHT_PT = 792f
    }
}

/**
 * A whole opened document.
 *
 * [outline] is the contents list: a title and the section it belongs to, built by the parsers from
 * headings, sheet names or slide titles, so every format gets the same navigation.
 */
class OfficeDocument(
    val kind: OfficeKind,
    val displayName: String,
    val sections: List<Section>,
    val outline: List<OutlineEntry> = emptyList(),
    /** Set when the file could only be read partly, e.g. an old binary format read as plain text. */
    val limitationNote: String? = null,
)

data class OutlineEntry(val title: String, val sectionIndex: Int, val level: Int = 0)
