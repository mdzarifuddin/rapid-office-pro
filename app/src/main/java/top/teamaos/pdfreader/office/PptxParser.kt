package top.teamaos.pdfreader.office

import android.graphics.Color
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Reading a .pptx deck.
 *
 * Slides are the one Office format that is genuinely positional: nothing flows, everything sits at
 * a stated place on a fixed canvas. That makes them simpler than a Word file in one way — there is
 * no pagination to work out, one slide is one page — and harder in another, because a shape that
 * says nothing about where it is inherits its position from a layout and then from a master.
 *
 * Inheritance is followed one step, into the slide's layout, which covers the case that matters:
 * the title and body placeholders on a slide built from a standard layout. Anything still without
 * a position is given a sensible one rather than being dropped, because a slide missing its title
 * is worse than a slide whose title is a few points out.
 */
object PptxParser {

    fun parse(file: File, displayName: String): OfficeDocument? {
        val pack = OoxmlPackage.open(file) ?: return null
        return pack.use { open ->
            val presentationPart = when {
                open.exists("ppt/presentation.xml") -> "ppt/presentation.xml"
                else -> open.names().firstOrNull { it.endsWith("presentation.xml") }
            } ?: return@use null

            val size = readSlideSize(open, presentationPart)
            val relationships = open.relationships(presentationPart)
            val slideParts = readSlideOrder(open, presentationPart, relationships)
            if (slideParts.isEmpty()) return@use null

            val sections = slideParts.mapIndexedNotNull { index, part ->
                val data = open.bytes(part) ?: return@mapIndexedNotNull null
                val slideRelationships = open.relationships(part)
                val layout = layoutPlaceholders(open, slideRelationships)
                val reader = SlideReader(open, slideRelationships, layout, size.first, size.second)
                OoxmlPackage.parse(data) { reader.onEvent(it) }
                reader.finish()
                Section.Slide(
                    title = reader.title.ifBlank { "Slide ${index + 1}" },
                    widthPt = size.first,
                    heightPt = size.second,
                    items = reader.items,
                    background = Color.WHITE,
                    notes = "",
                )
            }
            if (sections.isEmpty()) return@use null

            OfficeDocument(
                kind = OfficeKind.SLIDES,
                displayName = displayName,
                sections = sections,
                outline = sections.mapIndexed { index, section ->
                    OutlineEntry("${index + 1}. ${section.title}", index)
                },
            )
        }
    }

    private fun readSlideSize(pack: OoxmlPackage, part: String): Pair<Float, Float> {
        var width = DEFAULT_WIDTH_PT
        var height = DEFAULT_HEIGHT_PT
        pack.bytes(part)?.let { data ->
            OoxmlPackage.parse(data) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG &&
                    OoxmlPackage.localName(parser.name) == "sldSz"
                ) {
                    OoxmlPackage.attr(parser, "cx")?.toFloatOrNull()
                        ?.let { width = it / OoxmlPackage.EMU_PER_POINT }
                    OoxmlPackage.attr(parser, "cy")?.toFloatOrNull()
                        ?.let { height = it / OoxmlPackage.EMU_PER_POINT }
                }
            }
        }
        return width to height
    }

    /** The order slides are shown in is the order of `sldIdLst`, not the order of the files. */
    private fun readSlideOrder(
        pack: OoxmlPackage,
        part: String,
        relationships: Map<String, String>,
    ): List<String> {
        val ordered = mutableListOf<String>()
        pack.bytes(part)?.let { data ->
            OoxmlPackage.parse(data) { parser ->
                if (parser.eventType == XmlPullParser.START_TAG &&
                    OoxmlPackage.localName(parser.name) == "sldId"
                ) {
                    // Two attributes are both called "id" once prefixes are ignored: p:id is the
                    // slide's own number and r:id points at the file. Only the prefixed one is
                    // wanted, so it is read by its full name rather than by its tail.
                    val relationId = (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it).endsWith(":id") }
                        ?.let { parser.getAttributeValue(it) }
                    val target = relationId?.let { relationships[it] }
                    if (target != null && pack.exists(target)) ordered += target
                }
            }
        }
        if (ordered.isNotEmpty()) return ordered
        return pack.names()
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { name -> name.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    /** Placeholder boxes from the slide's layout, keyed by placeholder type, as a fallback. */
    private fun layoutPlaceholders(
        pack: OoxmlPackage,
        slideRelationships: Map<String, String>,
    ): Map<String, FloatArray> {
        val layoutPart = slideRelationships.values.firstOrNull { it.contains("slideLayout") }
            ?: return emptyMap()
        val data = pack.bytes(layoutPart) ?: return emptyMap()
        val boxes = mutableMapOf<String, FloatArray>()
        var type: String? = null
        var index: String? = null
        var offsetX: Float? = null
        var offsetY: Float? = null
        var width: Float? = null
        var height: Float? = null

        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "sp" -> {
                        type = null; index = null
                        offsetX = null; offsetY = null; width = null; height = null
                    }
                    "ph" -> {
                        type = OoxmlPackage.attr(parser, "type") ?: "body"
                        index = OoxmlPackage.attr(parser, "idx")
                    }
                    "off" -> {
                        offsetX = OoxmlPackage.attr(parser, "x")?.toFloatOrNull()
                            ?.div(OoxmlPackage.EMU_PER_POINT)
                        offsetY = OoxmlPackage.attr(parser, "y")?.toFloatOrNull()
                            ?.div(OoxmlPackage.EMU_PER_POINT)
                    }
                    "ext" -> {
                        width = OoxmlPackage.attr(parser, "cx")?.toFloatOrNull()
                            ?.div(OoxmlPackage.EMU_PER_POINT)
                        height = OoxmlPackage.attr(parser, "cy")?.toFloatOrNull()
                            ?.div(OoxmlPackage.EMU_PER_POINT)
                    }
                }
                XmlPullParser.END_TAG -> if (OoxmlPackage.localName(parser.name) == "sp") {
                    val x = offsetX; val y = offsetY; val w = width; val h = height
                    val key = type
                    if (key != null && x != null && y != null && w != null && h != null) {
                        boxes[placeholderKey(key, index)] = floatArrayOf(x, y, w, h)
                    }
                }
            }
        }
        return boxes
    }

    private fun placeholderKey(type: String, index: String?): String =
        if (index.isNullOrEmpty()) type else "$type#$index"

    /**
     * Walks one slide, collecting the shapes and pictures on it.
     *
     * Shapes nest — a group holds shapes, which hold text bodies — but only two things are needed
     * from that tree: where a shape is, and what is written in it. Tracking those two with a small
     * amount of state is a great deal less code than modelling the tree, and behaves the same for
     * every deck that is not built out of deeply nested groups.
     */
    private class SlideReader(
        private val pack: OoxmlPackage,
        private val relationships: Map<String, String>,
        private val layout: Map<String, FloatArray>,
        private val slideWidthPt: Float,
        private val slideHeightPt: Float,
    ) {
        val items = mutableListOf<SlideItem>()
        var title = ""

        private var inShape = false
        private var inPicture = false
        private var inSpPr = false
        private var inTextBody = false
        private var inText = false
        private var inRunProperties = false

        private var offsetX: Float? = null
        private var offsetY: Float? = null
        private var boxWidth: Float? = null
        private var boxHeight: Float? = null
        private var placeholderType: String? = null
        private var placeholderIndex: String? = null
        private var fill = 0
        private var pictureId: String? = null

        private var paragraphs = mutableListOf<Paragraph>()
        private var spans = mutableListOf<TextSpan>()
        private var runText = StringBuilder()
        private var paragraphAlign: Align? = null
        private var paragraphLevel = 0
        private var bulleted = false

        private var runBold = false
        private var runItalic = false
        private var runUnderline = false
        private var runSize: Float? = null
        private var runColour: Int? = null

        fun onEvent(parser: XmlPullParser) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> start(parser)
                XmlPullParser.TEXT -> if (inText) runText.append(parser.text)
                XmlPullParser.END_TAG -> end(parser)
            }
        }

        private fun start(parser: XmlPullParser) {
            when (OoxmlPackage.localName(parser.name)) {
                "sp" -> beginShape()
                "pic" -> {
                    beginShape()
                    inPicture = true
                }
                "spPr", "grpSpPr" -> inSpPr = true
                "ph" -> {
                    placeholderType = OoxmlPackage.attr(parser, "type") ?: "body"
                    placeholderIndex = OoxmlPackage.attr(parser, "idx")
                }
                "off" -> if (inShape) {
                    offsetX = OoxmlPackage.attr(parser, "x")?.toFloatOrNull()
                        ?.div(OoxmlPackage.EMU_PER_POINT)
                    offsetY = OoxmlPackage.attr(parser, "y")?.toFloatOrNull()
                        ?.div(OoxmlPackage.EMU_PER_POINT)
                }
                "ext" -> if (inShape) {
                    boxWidth = OoxmlPackage.attr(parser, "cx")?.toFloatOrNull()
                        ?.div(OoxmlPackage.EMU_PER_POINT)
                    boxHeight = OoxmlPackage.attr(parser, "cy")?.toFloatOrNull()
                        ?.div(OoxmlPackage.EMU_PER_POINT)
                }
                "srgbClr" -> {
                    val colour = OoxmlPackage.colourOf(OoxmlPackage.attr(parser, "val"), 0)
                    if (inRunProperties) runColour = colour else if (inSpPr && !inPicture) fill = colour
                }
                "blip" -> if (inPicture) {
                    pictureId = OoxmlPackage.attr(parser, "embed") ?: OoxmlPackage.attr(parser, "link")
                }
                "txBody" -> {
                    inTextBody = true
                    paragraphs = mutableListOf()
                }
                "p" -> if (inTextBody) beginParagraph()
                "pPr" -> if (inTextBody) {
                    paragraphAlign = alignOf(OoxmlPackage.attr(parser, "algn"))
                    paragraphLevel = OoxmlPackage.attr(parser, "lvl")?.toIntOrNull() ?: 0
                }
                "buChar", "buAutoNum" -> bulleted = true
                "buNone" -> bulleted = false
                "r" -> if (inTextBody) beginRun()
                "rPr", "defRPr", "endParaRPr" -> {
                    inRunProperties = true
                    OoxmlPackage.attr(parser, "sz")?.toFloatOrNull()?.let { runSize = it / 100f }
                    runBold = OoxmlPackage.attr(parser, "b") == "1"
                    runItalic = OoxmlPackage.attr(parser, "i") == "1"
                    runUnderline = OoxmlPackage.attr(parser, "u").let { it != null && it != "none" }
                }
                "t" -> if (inTextBody) inText = true
                "br" -> if (inTextBody) runText.append('\n')
            }
        }

        private fun end(parser: XmlPullParser) {
            when (OoxmlPackage.localName(parser.name)) {
                "spPr", "grpSpPr" -> inSpPr = false
                "rPr", "defRPr", "endParaRPr" -> inRunProperties = false
                "t" -> inText = false
                "r" -> endRun()
                "p" -> if (inTextBody) endParagraph()
                "txBody" -> inTextBody = false
                "sp" -> endShape()
                "pic" -> endPicture()
            }
        }

        private fun beginShape() {
            inShape = true
            inPicture = false
            offsetX = null; offsetY = null; boxWidth = null; boxHeight = null
            placeholderType = null; placeholderIndex = null
            fill = 0
            pictureId = null
            paragraphs = mutableListOf()
        }

        private fun beginParagraph() {
            spans = mutableListOf()
            paragraphAlign = null
            paragraphLevel = 0
            bulleted = false
        }

        private fun beginRun() {
            runText = StringBuilder()
            runBold = false; runItalic = false; runUnderline = false
            runSize = null; runColour = null
        }

        private fun endRun() {
            val text = runText.toString()
            runText = StringBuilder()
            if (text.isEmpty()) return
            spans += TextSpan(
                text = text,
                bold = runBold,
                italic = runItalic,
                underline = runUnderline,
                sizePt = runSize ?: defaultSizeFor(placeholderType),
                colour = runColour ?: Color.BLACK,
            )
        }

        private fun endParagraph() {
            endRun()
            if (spans.isEmpty()) {
                paragraphs += Paragraph(emptyList(), spaceAfterPt = 4f)
                return
            }
            paragraphs += Paragraph(
                spans = spans.toList(),
                align = paragraphAlign ?: Align.LEFT,
                indentPt = paragraphLevel * LEVEL_INDENT_PT,
                spaceAfterPt = 4f,
                marker = if (bulleted) "•" else null,
            )
            spans = mutableListOf()
        }

        private fun endShape() {
            if (!inShape) return
            val box = resolveBox()
            val content = paragraphs.filter { it.spans.isNotEmpty() }
            if (content.isNotEmpty()) {
                if (title.isBlank() && (placeholderType == "title" || placeholderType == "ctrTitle")) {
                    title = content.first().plainText.trim()
                }
                items += SlideItem.Text(
                    xPt = box[0],
                    yPt = box[1],
                    widthPt = box[2],
                    heightPt = box[3],
                    paragraphs = content,
                    fill = fill,
                    verticalAlign = if (placeholderType == "ctrTitle") 1 else 0,
                )
            } else if (fill != 0 && boxWidth != null) {
                items += SlideItem.Shape(box[0], box[1], box[2], box[3], fill, 0)
            }
            inShape = false
            paragraphs = mutableListOf()
        }

        private fun endPicture() {
            val id = pictureId
            if (inShape && id != null) {
                val target = relationships[id]
                val data = target?.let { pack.bytes(it) }
                if (data != null) {
                    val box = resolveBox()
                    items += SlideItem.Picture(box[0], box[1], box[2], box[3], data)
                }
            }
            inShape = false
            inPicture = false
            pictureId = null
        }

        /** Where this shape goes: its own position, else its layout's, else a sensible default. */
        private fun resolveBox(): FloatArray {
            val x = offsetX
            val y = offsetY
            val width = boxWidth
            val height = boxHeight
            if (x != null && y != null && width != null && height != null && width > 0 && height > 0) {
                return floatArrayOf(x, y, width, height)
            }
            placeholderType?.let { type ->
                layout[placeholderKey(type, placeholderIndex)]?.let { return it }
                layout[type]?.let { return it }
            }
            val margin = slideWidthPt * 0.08f
            return if (placeholderType == "title" || placeholderType == "ctrTitle") {
                floatArrayOf(margin, slideHeightPt * 0.10f, slideWidthPt - 2 * margin, slideHeightPt * 0.18f)
            } else {
                floatArrayOf(margin, slideHeightPt * 0.32f, slideWidthPt - 2 * margin, slideHeightPt * 0.55f)
            }
        }

        private fun defaultSizeFor(type: String?): Float = when (type) {
            "title", "ctrTitle" -> 34f
            "subTitle" -> 22f
            else -> 18f
        }

        fun finish() {
            if (inShape) endShape()
            if (title.isBlank()) {
                title = items.filterIsInstance<SlideItem.Text>()
                    .firstOrNull()
                    ?.paragraphs
                    ?.firstOrNull { it.plainText.isNotBlank() }
                    ?.plainText
                    ?.trim()
                    .orEmpty()
                    .take(60)
            }
        }
    }

    private fun alignOf(value: String?): Align? = when (value) {
        "ctr" -> Align.CENTRE
        "r" -> Align.RIGHT
        "just", "dist" -> Align.JUSTIFY
        "l" -> Align.LEFT
        else -> null
    }

    /** 4:3 at 10 inches wide, which is what PowerPoint used before widescreen. */
    private const val DEFAULT_WIDTH_PT = 720f
    private const val DEFAULT_HEIGHT_PT = 540f
    private const val LEVEL_INDENT_PT = 22f
}
