package top.teamaos.pdfreader.office

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * The bits of an Office file that every OOXML format shares.
 *
 * A .docx, .xlsx or .pptx is a zip of XML, so all three are read the same way: open the zip, pull
 * out parts by name, and follow the relationship files that turn an `r:embed="rId4"` into
 * `word/media/image1.png`. Doing that here once keeps the three parsers to the part that is
 * actually different — what the XML means.
 *
 * Entries are read on demand rather than all at once. A deck full of photographs is mostly images,
 * and holding every one of them in memory before deciding which slides are even on screen is how a
 * viewer runs a phone out of heap on a file the phone could otherwise open.
 */
class OoxmlPackage(private val zip: ZipFile) : AutoCloseable {

    fun bytes(name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > MAX_ENTRY_BYTES) return null
        return runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    fun exists(name: String): Boolean = zip.getEntry(name) != null

    fun names(): List<String> = zip.entries().toList().map { it.name }

    /**
     * The relationships declared for one part.
     *
     * `word/document.xml` keeps its relationships in `word/_rels/document.xml.rels`, and the ids in
     * them are what the document body points at. Returns id to target, with targets already made
     * absolute within the package.
     */
    fun relationships(partName: String): Map<String, String> {
        val directory = partName.substringBeforeLast('/', "")
        val file = partName.substringAfterLast('/')
        val relsName = if (directory.isEmpty()) "_rels/$file.rels" else "$directory/_rels/$file.rels"
        val data = bytes(relsName) ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        parse(data) { parser ->
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                val mode = parser.getAttributeValue(null, "TargetMode")
                if (id != null && target != null && mode != "External") {
                    result[id] = resolve(directory, target)
                }
            }
        }
        return result
    }

    /** Turn a relationship target, which may be relative and may climb, into a package path. */
    private fun resolve(directory: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayDeque<String>()
        directory.split('/').filter { it.isNotEmpty() }.forEach { parts.addLast(it) }
        target.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        /** Nothing inside an Office file should be bigger than this; something is wrong if it is. */
        private const val MAX_ENTRY_BYTES = 96L * 1024 * 1024

        fun open(file: File): OoxmlPackage? =
            runCatching { OoxmlPackage(ZipFile(file)) }.getOrNull()

        /**
         * Walk an XML part, calling [onEvent] for every event.
         *
         * A pull parser rather than a DOM on purpose: a spreadsheet's sheet XML can be tens of
         * megabytes, and building a tree of it before reading a single cell is the difference
         * between opening in a moment and not opening at all.
         */
        fun parse(data: ByteArray, onEvent: (XmlPullParser) -> Unit) {
            runCatching {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(ByteArrayInputStream(data), null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    onEvent(parser)
                    event = parser.next()
                }
            }
        }

        /** Strip the namespace prefix: the parsers care about `p`, not `w:p`. */
        fun localName(name: String?): String = name?.substringAfterLast(':') ?: ""

        fun attr(parser: XmlPullParser, name: String): String? {
            parser.getAttributeValue(null, name)?.let { return it }
            // Namespaced attributes come through with their prefix when namespace processing is
            // off, and which prefix a file uses is not fixed, so fall back to matching the tail.
            for (index in 0 until parser.attributeCount) {
                if (localName(parser.getAttributeName(index)) == name) {
                    return parser.getAttributeValue(index)
                }
            }
            return null
        }

        /** Office measures in EMU: 914400 to the inch, so 12700 to the point. */
        const val EMU_PER_POINT = 12700f

        /** Word measures in twentieths of a point. */
        const val TWIPS_PER_POINT = 20f

        fun colourOf(value: String?, fallback: Int = 0xFF000000.toInt()): Int {
            val text = value?.trim()?.removePrefix("#") ?: return fallback
            if (text.isEmpty() || text.equals("auto", ignoreCase = true)) return fallback
            val hex = when (text.length) {
                6 -> "FF$text"
                8 -> text
                else -> return fallback
            }
            return runCatching { hex.toLong(16).toInt() }.getOrDefault(fallback)
        }

        /** Word's named highlight colours; anything unknown is left unhighlighted. */
        fun highlightOf(name: String?): Int = when (name?.lowercase()) {
            null, "none" -> 0
            "yellow" -> 0xFFFFF176.toInt()
            "green" -> 0xFF81C784.toInt()
            "cyan" -> 0xFF4DD0E1.toInt()
            "magenta" -> 0xFFF06292.toInt()
            "blue" -> 0xFF64B5F6.toInt()
            "red" -> 0xFFE57373.toInt()
            "darkblue" -> 0xFF5C6BC0.toInt()
            "darkred" -> 0xFFB71C1C.toInt()
            "darkgray", "darkgrey" -> 0xFF9E9E9E.toInt()
            "lightgray", "lightgrey" -> 0xFFE0E0E0.toInt()
            else -> 0xFFFFF176.toInt()
        }

        /** OOXML booleans: absent means true for `<w:b/>`, and "0"/"false"/"none" mean off. */
        fun isOn(value: String?): Boolean =
            value == null || !(value == "0" || value.equals("false", true) || value.equals("none", true))
    }
}
