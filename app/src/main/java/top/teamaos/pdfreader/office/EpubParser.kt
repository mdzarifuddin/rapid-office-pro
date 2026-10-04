package top.teamaos.pdfreader.office

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Reading EPUB e-books: .epub, EPUB 2 and EPUB 3 alike.
 *
 * An EPUB is a zip of XHTML chapters with a manifest saying what is in it and a spine saying what
 * order to read it in. `META-INF/container.xml` names the package file (the OPF); the OPF lists the
 * chapters; each chapter is read through [EbookHtml] into one section, so the contents list can
 * jump chapter to chapter. The contents titles come from the book's own table of contents — the
 * EPUB 3 `nav` document or the EPUB 2 `toc.ncx` — and from the chapters' headings when it has none.
 *
 * Books sold with DRM have their chapters encrypted; those are recognised by [isProtected] and not
 * read, because what would come out is noise.
 */
object EpubParser {

    fun parse(file: File, displayName: String): OfficeDocument? {
        val pack = OoxmlPackage.open(file) ?: return null
        return pack.use { open -> runCatching { read(open, displayName) }.getOrNull() }
    }

    /**
     * True when [file] is an EPUB whose content is encrypted (Adobe ADEPT, Apple FairPlay,
     * Readium LCP and the like). Font obfuscation, which every EPUB tool applies to embedded fonts,
     * is not protection and does not count.
     */
    fun isProtected(file: File): Boolean {
        val pack = OoxmlPackage.open(file) ?: return false
        return pack.use { open -> runCatching { isProtected(open) }.getOrDefault(false) }
    }

    /** True when [pack]'s first entry is "mimetype" holding application/epub+zip, or it has an OPF. */
    fun isEpub(pack: OoxmlPackage): Boolean {
        val type = pack.bytes("mimetype")?.let { String(it, Charsets.US_ASCII).trim() }
        if (type != null) return type.startsWith("application/epub+zip")
        return pack.exists("META-INF/container.xml") && packagePath(pack) != null
    }

    private fun isProtected(pack: OoxmlPackage): Boolean {
        if (pack.exists("META-INF/sinf.xml") || pack.exists("META-INF/license.lcpl")) return true
        val encryption = pack.bytes("META-INF/encryption.xml") ?: return false
        var algorithm: String? = null
        var protected = false
        OoxmlPackage.parse(encryption) { parser ->
            if (protected || parser.eventType != XmlPullParser.START_TAG) return@parse
            when (OoxmlPackage.localName(parser.name)) {
                "EncryptedData" -> algorithm = null
                "EncryptionMethod" -> algorithm = OoxmlPackage.attr(parser, "Algorithm")
                "CipherReference" -> {
                    val uri = OoxmlPackage.attr(parser, "URI")?.lowercase().orEmpty()
                    val obfuscation = algorithm in FONT_OBFUSCATION
                    val font = FONT_EXTENSIONS.any { uri.substringBefore('?').endsWith(it) }
                    if (!obfuscation && !font) protected = true
                }
            }
        }
        return protected
    }

    // ------------------------------------------------------------------ package

    private class Item(val id: String, val path: String, val mediaType: String, val properties: String)

    private fun read(pack: OoxmlPackage, displayName: String): OfficeDocument? {
        if (isProtected(pack)) return null
        val opfPath = packagePath(pack) ?: return null
        val opf = pack.bytes(opfPath) ?: return null
        val opfDirectory = opfPath.substringBeforeLast('/', "")

        val manifest = LinkedHashMap<String, Item>()
        val spine = mutableListOf<String>()
        var ncxId: String? = null
        var title: String? = null
        var inTitle = false
        var inMetadata = false
        val titleText = StringBuilder()
        OoxmlPackage.parse(opf) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "metadata" -> inMetadata = true
                    "title" -> if (inMetadata && title == null) {
                        inTitle = true
                        titleText.setLength(0)
                    }
                    "item" -> {
                        val id = OoxmlPackage.attr(parser, "id")
                        val href = OoxmlPackage.attr(parser, "href")
                        if (id != null && href != null && manifest.size < MAX_MANIFEST) {
                            manifest[id] = Item(
                                id,
                                resolve(opfDirectory, href),
                                OoxmlPackage.attr(parser, "media-type").orEmpty().lowercase(),
                                OoxmlPackage.attr(parser, "properties").orEmpty(),
                            )
                        }
                    }
                    "itemref" -> OoxmlPackage.attr(parser, "idref")?.let { if (spine.size < MAX_MANIFEST) spine += it }
                    "spine" -> ncxId = OoxmlPackage.attr(parser, "toc")
                }
                XmlPullParser.TEXT -> if (inTitle) titleText.append(parser.text)
                XmlPullParser.END_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "metadata" -> inMetadata = false
                    "title" -> if (inTitle) {
                        inTitle = false
                        title = titleText.toString().trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
                    }
                }
            }
        }

        val chapters = spine.mapNotNull { manifest[it] }.filter { isDocument(it) }.distinctBy { it.path }
        if (chapters.isEmpty()) return null

        val toc = tableOfContents(pack, manifest.values, ncxId)
        val budget = EbookHtml.Budget()
        val pictures = OoxmlPackage.Pictures(pack)
        val sections = mutableListOf<Section.Flow>()
        val headingOutline = mutableListOf<OutlineEntry>()
        // Where each chapter file landed; a chapter that came out empty points at the next one.
        val sectionOfPath = HashMap<String, Int>()
        val pendingPaths = mutableListOf<String>()

        for (chapter in chapters) {
            if (budget.full || sections.size >= EbookHtml.MAX_SECTIONS) {
                budget.truncated = true
                break
            }
            pendingPaths += chapter.path
            val data = pack.bytes(chapter.path) ?: continue
            val html = decode(data)
            val directory = chapter.path.substringBeforeLast('/', "")
            val result = EbookHtml.read(html, budget) { source, tag ->
                picture(pack, pictures, directory, source, tag)
            }
            if (result.blocks.isEmpty()) continue
            val index = sections.size
            pendingPaths.forEach { sectionOfPath[it] = index }
            pendingPaths.clear()
            val chapterTitle = toc.firstOrNull { it.path == chapter.path }?.title
                ?: result.headings.firstOrNull()?.title
                ?: if (index == 0 && title != null) title!! else "Chapter ${index + 1}"
            sections += Section.Flow(chapterTitle, result.blocks)
            result.headings.forEach { headingOutline += OutlineEntry(it.title, index, it.level) }
        }
        if (sections.isEmpty()) return null

        val outline = toc.mapNotNull { entry ->
            sectionOfPath[entry.path]?.let { OutlineEntry(entry.title, it, entry.level) }
        }.ifEmpty { headingOutline }.take(MAX_OUTLINE)

        return OfficeDocument(
            kind = OfficeKind.WORD,
            displayName = displayName,
            sections = sections,
            outline = outline,
            limitationNote = if (budget.truncated) EbookHtml.TRUNCATED_NOTE else null,
        )
    }

    private fun isDocument(item: Item): Boolean =
        item.mediaType == "application/xhtml+xml" || item.mediaType == "text/html" ||
            (item.mediaType.isEmpty() || item.mediaType == "application/octet-stream") &&
            item.path.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm", "xml")

    /** The OPF named by META-INF/container.xml, or the first .opf in the zip if that is missing. */
    private fun packagePath(pack: OoxmlPackage): String? {
        var found: String? = null
        pack.bytes("META-INF/container.xml")?.let { container ->
            OoxmlPackage.parse(container) { parser ->
                if (found == null && parser.eventType == XmlPullParser.START_TAG &&
                    OoxmlPackage.localName(parser.name) == "rootfile"
                ) {
                    val type = OoxmlPackage.attr(parser, "media-type")
                    val path = OoxmlPackage.attr(parser, "full-path")
                    if (path != null && (type == null || type.contains("oebps-package"))) found = resolve("", path)
                }
            }
        }
        found?.takeIf { pack.exists(it) }?.let { return it }
        return pack.names().firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    // ------------------------------------------------------------------ contents

    private class TocEntry(val title: String, val path: String, val level: Int)

    private fun tableOfContents(pack: OoxmlPackage, items: Collection<Item>, ncxId: String?): List<TocEntry> {
        items.firstOrNull { item -> item.properties.split(' ').any { it == "nav" } }?.let { nav ->
            pack.bytes(nav.path)?.let { data ->
                runCatching { navEntries(decode(data), nav.path.substringBeforeLast('/', "")) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        val ncx = items.firstOrNull { it.id == ncxId }
            ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?: return emptyList()
        val data = pack.bytes(ncx.path) ?: return emptyList()
        return runCatching { ncxEntries(data, ncx.path.substringBeforeLast('/', "")) }.getOrDefault(emptyList())
    }

    /**
     * The EPUB 3 navigation document's `<nav epub:type="toc">`: nested lists of links. Read with a
     * tolerant scan rather than an XML parser, because these files routinely use `&nbsp;` and other
     * HTML entities an XML parser rejects.
     */
    private fun navEntries(html: String, directory: String): List<TocEntry> {
        val lower = html.lowercase()
        var start = -1
        var search = 0
        while (true) {
            val nav = lower.indexOf("<nav", search)
            if (nav < 0) break
            val tagEnd = lower.indexOf('>', nav)
            if (tagEnd < 0) break
            if (start < 0) start = nav
            if (Regex("""type\s*=\s*["'][^"']*\btoc\b""").containsMatchIn(lower.substring(nav, tagEnd))) {
                start = nav
                break
            }
            search = tagEnd
        }
        if (start < 0) return emptyList()
        val end = lower.indexOf("</nav", start).let { if (it < 0) html.length else it }
        val region = html.substring(start, end)
        val entries = mutableListOf<TocEntry>()
        var depth = 0
        val tags = Regex("""(?is)<(/?)(ol|ul)\b[^>]*>|<a\b([^>]*)>(.*?)</a\s*>""")
        for (match in tags.findAll(region)) {
            if (entries.size >= MAX_OUTLINE) break
            if (match.groupValues[2].isNotEmpty()) {
                depth = if (match.groupValues[1].isEmpty()) depth + 1 else (depth - 1).coerceAtLeast(0)
                continue
            }
            val href = EbookHtml.attribute(match.groupValues[3], "href") ?: continue
            val text = HtmlParser.decodeEntities(match.groupValues[4].replace(Regex("<[^>]*>"), " "))
                .replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty() || href.startsWith("http:") || href.startsWith("https:")) continue
            entries += TocEntry(text.take(MAX_TITLE), resolve(directory, href), (depth - 1).coerceIn(0, MAX_LEVEL))
        }
        return entries
    }

    /** The EPUB 2 `toc.ncx`: nested navPoints, each a label and a content link. */
    private fun ncxEntries(data: ByteArray, directory: String): List<TocEntry> {
        class Point(var label: String? = null, var source: String? = null, var emitted: Boolean = false)

        val stack = ArrayList<Point>()
        val entries = mutableListOf<TocEntry>()
        var inText = false
        val text = StringBuilder()
        OoxmlPackage.parse(data) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "navPoint" -> if (stack.size < MAX_DEPTH) stack += Point()
                    "text" -> if (stack.isNotEmpty()) {
                        inText = true
                        text.setLength(0)
                    }
                    "content" -> stack.lastOrNull()?.let { point ->
                        if (point.source == null) point.source = OoxmlPackage.attr(parser, "src")
                    }
                }
                XmlPullParser.TEXT -> if (inText) text.append(parser.text)
                XmlPullParser.END_TAG -> when (OoxmlPackage.localName(parser.name)) {
                    "text" -> if (inText) {
                        inText = false
                        stack.lastOrNull()?.let { if (it.label == null) it.label = text.toString() }
                    }
                    "navPoint" -> stack.removeLastOrNull()
                }
            }
            val point = stack.lastOrNull()
            if (point != null && !point.emitted && point.label != null && point.source != null &&
                entries.size < MAX_OUTLINE
            ) {
                point.emitted = true
                val label = point.label!!.replace(Regex("\\s+"), " ").trim()
                if (label.isNotEmpty()) {
                    entries += TocEntry(label.take(MAX_TITLE), resolve(directory, point.source!!), (stack.size - 1).coerceIn(0, MAX_LEVEL))
                }
            }
        }
        return entries
    }

    // ------------------------------------------------------------------ pictures

    private fun picture(
        pack: OoxmlPackage,
        pictures: OoxmlPackage.Pictures,
        directory: String,
        source: String?,
        tag: String,
    ): Block.Picture? {
        val reference = source?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val data = if (reference.startsWith("data:", ignoreCase = true)) {
            val comma = reference.indexOf(',')
            if (comma < 0 || !reference.substring(0, comma).contains("base64", ignoreCase = true)) return null
            if (reference.length - comma > MAX_INLINE_PICTURE_CHARS) return null
            runCatching { Base64.decode(reference.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
        } else {
            if (reference.contains("://")) return null
            val path = resolve(directory, reference)
            if (path.endsWith(".svg", ignoreCase = true)) return null
            if (!pack.exists(path)) return null
            pictures.bytes(path)
        } ?: return null
        return EbookHtml.picture(data, EbookHtml.attribute(tag, "width"), EbookHtml.attribute(tag, "height"))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A chapter's bytes as text: a byte-order mark, then the XML declaration's or meta tag's
     * charset, then UTF-8, which the EPUB specification requires anyway.
     */
    private fun decode(data: ByteArray): String {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            return String(data, 3, data.size - 3, Charsets.UTF_8)
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
            return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        }
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        }
        val head = String(data, 0, minOf(data.size, 1024), Charsets.ISO_8859_1)
        val declared = Regex("""(?i)(?:encoding|charset)\s*=\s*["']?([A-Za-z0-9._-]+)""").find(head)?.groupValues?.get(1)
        val charset = declared?.let { name -> runCatching { charset(name) }.getOrNull() } ?: Charsets.UTF_8
        return String(data, charset)
    }

    /** A link inside the book, made a zip path: fragment and query dropped, %-escapes decoded, `..` climbed. */
    internal fun resolve(directory: String, href: String): String {
        val bare = href.substringBefore('#').substringBefore('?')
        val decoded = percentDecode(bare)
        if (decoded.startsWith("/")) return normalise("", decoded.removePrefix("/"))
        return normalise(directory, decoded)
    }

    private fun normalise(directory: String, target: String): String {
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

    private fun percentDecode(text: String): String {
        if (text.indexOf('%') < 0) return text
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '%' && index + 2 < text.length) {
                val value = text.substring(index + 1, index + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    index += 3
                    continue
                }
            }
            bytes.write(character.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private val FONT_OBFUSCATION = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    )
    private val FONT_EXTENSIONS = listOf(".ttf", ".otf", ".woff", ".woff2", ".fnt", ".pfb")

    private const val MAX_MANIFEST = 20_000
    private const val MAX_OUTLINE = 5000
    private const val MAX_TITLE = 200
    private const val MAX_LEVEL = 8
    private const val MAX_DEPTH = 32
    private const val MAX_INLINE_PICTURE_CHARS = 16 * 1024 * 1024
}
