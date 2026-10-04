package top.teamaos.pdfreader.office

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Working out what a file is, and turning it into an [OfficeDocument].
 *
 * The extension is the first guess and the file's own first bytes are the decider, because files
 * arrive renamed all the time — a .docx sent through a system that stripped the extension, a .xls
 * that is really a CSV, a .doc that is really RTF. Trusting the name alone is how a viewer ends up
 * showing a page of mojibake instead of a document.
 */
object OfficeFormats {

    /** Everything this app will open besides PDF, and lists in the file browser and search. */
    val EXTENSIONS = setOf(
        // Word processing
        "docx", "docm", "dotx", "dotm", "doc", "dot", "rtf", "odt", "ott", "fodt",
        // Spreadsheets
        "xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "ods", "ots", "csv", "tsv", "tab",
        // Presentations
        "pptx", "pptm", "potx", "potm", "ppt", "pot", "pps", "ppsx", "ppsm", "odp", "otp", "odg",
        // Web pages
        "html", "htm", "xhtml",
        // Plain text of every common kind, shown as text
        "txt", "text", "md", "markdown", "log", "json", "xml", "yaml", "yml", "ini", "conf", "cfg",
        "toml", "properties", "srt", "vtt", "sql", "sh", "bat", "py", "js", "ts", "kt", "kts",
        "java", "c", "cpp", "cc", "h", "hpp", "cs", "go", "rs", "swift", "php", "rb", "css",
        "gradle", "nfo",
        // E-books and WordPerfect
        "epub", "mobi", "azw", "azw3", "prc", "wpd", "wp", "wp5", "wp6",
    )

    /**
     * Formats people will hand this app that it cannot draw. Recognised so the reader is told
     * plainly what the file is, rather than shown a page of binary read as text.
     */
    private val UNSUPPORTED = mapOf(
        "xlsb" to "Excel Binary Workbook (.xlsb)",
        "numbers" to "Apple Numbers",
        "pages" to "Apple Pages",
        "key" to "Apple Keynote",
        "wps" to "Microsoft Works",
        "hwp" to "Hangul (.hwp)",
    )

    private const val DRM_KINDLE = "Kindle book"
    private const val DRM_EBOOK = "e-book"

    private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")
    private val DELIMITED_EXTENSIONS = setOf("csv", "tsv", "tab")

    fun isOffice(name: String): Boolean = extensionOf(name) in EXTENSIONS

    /** True for the MIME types a provider reports for the formats this app opens besides PDF. */
    fun isOfficeMimeType(type: String): Boolean {
        val lower = type.lowercase()
        return lower.startsWith("text/") ||
            lower.contains("word") ||
            lower.contains("excel") ||
            lower.contains("powerpoint") ||
            lower.contains("spreadsheet") ||
            lower.contains("presentation") ||
            lower.contains("officedocument") ||
            lower.contains("opendocument") ||
            lower.contains("rtf") ||
            lower.contains("csv") ||
            lower.contains("html") ||
            lower.contains("json") ||
            lower.contains("xml") ||
            lower.contains("yaml") ||
            // What messaging apps label an attachment they did not recognise; the content decides.
            lower.contains("epub") ||
            lower.contains("mobipocket") ||
            lower.contains("amazon.ebook") ||
            lower.contains("wordperfect") ||
            lower == "application/octet-stream"
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** The label shown on the file's badge in the list. */
    fun badgeFor(name: String): String = when (extensionOf(name)) {
        "docx", "docm", "dotx", "dotm", "doc", "dot", "rtf", "odt", "ott", "fodt" -> "DOC"
        "xlsx", "xlsm", "xltx", "xltm", "xls", "xlt", "ods", "ots" -> "XLS"
        "csv", "tsv", "tab" -> "CSV"
        "pptx", "pptm", "potx", "potm", "ppt", "pot", "pps", "ppsx", "ppsm", "odp", "otp", "odg" -> "PPT"
        "html", "htm", "xhtml" -> "HTML"
        "epub", "mobi", "azw", "azw3", "prc" -> "BOOK"
        "wpd", "wp", "wp5", "wp6" -> "DOC"
        "pdf" -> "PDF"
        else -> "TXT"
    }

    /** What opening a file came to. */
    sealed class Opened {
        class Document(val document: OfficeDocument) : Opened()

        /** It is a PDF, whatever it was called; the PDF reader should have it. */
        object Pdf : Opened()

        /**
         * A format recognised but not drawn here; [format] names it for the message. [drm] is set
         * for a book locked by the store that sold it, which no reader but the store's can open —
         * worth saying differently, so the reader does not go looking for another app.
         */
        class Unsupported(val format: String, val drm: Boolean = false) : Opened()

        object Unreadable : Opened()
    }

    /** [open], for callers that only want the document. */
    fun read(file: File, displayName: String): OfficeDocument? =
        (open(file, displayName) as? Opened.Document)?.document

    /**
     * Work out what [file] is and read it.
     *
     * The file's own bytes decide, and the name only breaks ties. Attachments arrive from
     * WhatsApp, Telegram and mail as `application/octet-stream` with a name like "1234" or
     * "document.bin", and a renamed .docx is still a .docx: a zip is looked inside, a compound file
     * is asked which streams it holds, and text is checked for PDF, RTF and HTML signatures before
     * the extension is consulted at all.
     *
     * Every parser returns null rather than throwing when the file is not what it expected, so a
     * file that fools the sniffing still falls through to one that understands it.
     */
    fun open(file: File, displayName: String): Opened {
        val extension = extensionOf(displayName).ifEmpty { extensionOf(file.name) }
        val head = header(file)
        if (isPdf(head)) return Opened.Pdf

        val attempts: List<() -> OfficeDocument?> = when {
            isZip(head) -> {
                val names = runCatching { OoxmlPackage.open(file)?.use { it.names() } }.getOrNull()
                    ?: return Opened.Unreadable
                zipAttempts(file, displayName, extension, names) { return it }
            }
            isOle(head) -> {
                val streams = runCatching { Ole2File.open(file)?.names() }.getOrNull().orEmpty()
                oleAttempts(file, displayName, extension, streams) { return it }
            }
            isRtf(head) -> listOf({ PlainTextParser.parseRtf(file, displayName) })
            isHtml(head) || (extension in HTML_EXTENSIONS && !PlainTextParser.looksBinary(head)) -> listOf(
                { HtmlParser.parse(file, displayName) },
                { PlainTextParser.parseText(file, displayName) },
            )
            // Binary content that matched no signature is not something to show as text. The
            // extension only names it: a ".key" that is text is a PEM key, not a Keynote deck.
            MobiParser.isMobi(head) ->
                if (MobiParser.isProtected(file)) return Opened.Unsupported(DRM_KINDLE, drm = true)
                else listOf({ MobiParser.parse(file, displayName) })
            WpdParser.isWordPerfect(head) ->
                if (WpdParser.isProtected(file)) return Opened.Unsupported("password-protected WordPerfect")
                else listOf({ WpdParser.parse(file, displayName) })
            PlainTextParser.looksBinary(head) ->
                return UNSUPPORTED[extension]?.let { Opened.Unsupported(it) } ?: Opened.Unreadable
            extension in DELIMITED_EXTENSIONS ->
                listOf({ PlainTextParser.parseDelimited(file, displayName) })
            else -> listOf({ PlainTextParser.parseText(file, displayName) })
        }

        attempts.forEach { attempt ->
            runCatching { attempt() }.getOrNull()
                ?.takeIf { it.sections.isNotEmpty() }
                ?.let { return Opened.Document(it) }
        }
        return Opened.Unreadable
    }

    /** Which reader a zip goes to, decided by the parts inside it rather than by its name. */
    private inline fun zipAttempts(
        file: File,
        displayName: String,
        extension: String,
        names: List<String>,
        stop: (Opened) -> Nothing,
    ): List<() -> OfficeDocument?> {
        val docx = { DocxParser.parse(file, displayName) }
        val xlsx = { XlsxParser.parse(file, displayName) }
        val pptx = { PptxParser.parse(file, displayName) }
        val odf = { OdfParser.parse(file, displayName) }
        return when {
            // Excel's binary workbook is a zip with "xl/" in it too, but its parts are not XML.
            names.any { it.equals("xl/workbook.bin", true) } -> stop(Opened.Unsupported(UNSUPPORTED.getValue("xlsb")))
            names.any { it.startsWith("word/") } -> listOf(docx, xlsx, pptx)
            names.any { it.startsWith("xl/") } -> listOf(xlsx, docx, pptx)
            names.any { it.startsWith("ppt/") } -> listOf(pptx, docx, xlsx)
            // An EPUB has a mimetype entry too, so it is told apart before OpenDocument is tried.
            "META-INF/container.xml" in names &&
                runCatching { OoxmlPackage.open(file)?.use { EpubParser.isEpub(it) } }.getOrNull() == true ->
                if (EpubParser.isProtected(file)) stop(Opened.Unsupported(DRM_EBOOK, drm = true))
                else listOf({ EpubParser.parse(file, displayName) })
            "mimetype" in names || "content.xml" in names -> listOf(odf)
            // iWork files are zips of Apple's own binary format.
            names.any { it.startsWith("Index/") || it.endsWith(".iwa") } ->
                stop(Opened.Unsupported(UNSUPPORTED[extension] ?: "Apple iWork"))
            else -> when (extension) {
                "xlsx", "xlsm", "xltx", "xltm" -> listOf(xlsx, docx, pptx, odf)
                "pptx", "pptm", "potx", "potm", "ppsx", "ppsm" -> listOf(pptx, docx, xlsx, odf)
                else -> UNSUPPORTED[extension]?.let { stop(Opened.Unsupported(it)) }
                    ?: listOf(docx, xlsx, pptx, odf)
            }
        }
    }

    /** Which reader a compound file goes to, from the streams it holds. */
    private inline fun oleAttempts(
        file: File,
        displayName: String,
        extension: String,
        streams: List<String>,
        stop: (Opened) -> Nothing,
    ): List<() -> OfficeDocument?> {
        val doc = { LegacyOfficeParser.parseDoc(file, displayName) }
        val xls = { LegacyOfficeParser.parseXls(file, displayName) }
        val ppt = { LegacyOfficeParser.parsePpt(file, displayName) }
        return when {
            // A password-protected .docx/.xlsx/.pptx is stored encrypted inside a compound file.
            hasStream(streams, "EncryptedPackage") -> stop(Opened.Unsupported("password-protected Office"))
            hasStream(streams, "WordDocument") -> listOf(doc, xls, ppt)
            hasStream(streams, "Workbook") || hasStream(streams, "Book") -> listOf(xls, doc, ppt)
            hasStream(streams, "PowerPoint Document") -> listOf(ppt, doc, xls)
            else -> when (extension) {
                "xls", "xlt" -> listOf(xls, doc, ppt)
                "ppt", "pot", "pps" -> listOf(ppt, xls, doc)
                else -> UNSUPPORTED[extension]?.let { stop(Opened.Unsupported(it)) }
                    ?: listOf(doc, xls, ppt)
            }
        }
    }

    private fun hasStream(streams: List<String>, name: String): Boolean =
        streams.any { it.equals(name, ignoreCase = true) }

    private fun header(file: File): ByteArray = runCatching {
        file.inputStream().use { stream ->
            val buffer = ByteArray(HEADER_BYTES)
            var read = 0
            while (read < buffer.size) {
                val count = stream.read(buffer, read, buffer.size - read)
                if (count <= 0) break
                read += count
            }
            buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    private fun startsWith(head: ByteArray, vararg bytes: Int): Boolean =
        head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }

    private fun isZip(head: ByteArray): Boolean =
        startsWith(head, 0x50, 0x4B) && head.size >= 4 &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte())

    private fun isOle(head: ByteArray): Boolean =
        startsWith(head, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)

    /** "%PDF-" anywhere in the first kilobyte: the PDF format allows junk before its header. */
    private fun isPdf(head: ByteArray): Boolean {
        val limit = minOf(head.size, 1024) - 5
        for (index in 0..limit) {
            if (head[index] == '%'.code.toByte() && head[index + 1] == 'P'.code.toByte() &&
                head[index + 2] == 'D'.code.toByte() && head[index + 3] == 'F'.code.toByte() &&
                head[index + 4] == '-'.code.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun leadingText(head: ByteArray): String {
        var start = 0
        if (startsWith(head, 0xEF, 0xBB, 0xBF)) start = 3
        // A UTF-16 page is sniffed through its ASCII bytes with the zeros dropped.
        val bytes = head.copyOfRange(start, head.size).filter { it != 0.toByte() }.toByteArray()
        return String(bytes, Charsets.ISO_8859_1).trimStart('﻿', 'ÿ', 'þ', ' ', '\t', '\r', '\n')
    }

    private fun isRtf(head: ByteArray): Boolean = leadingText(head).startsWith("{\\rtf")

    private fun isHtml(head: ByteArray): Boolean {
        val text = leadingText(head).take(1024).lowercase()
        return text.startsWith("<!doctype html") || text.startsWith("<html") ||
            (text.startsWith("<?xml") && text.contains("<html")) ||
            (text.startsWith("<") && (text.contains("<html") || text.contains("<body") || text.contains("<head>")))
    }

    /**
     * A local file for [uri], copying it into the cache only when it is not already one.
     *
     * All three parsers need random access — a zip's directory is at the end of the file, and a
     * compound file is a filesystem — so a stream is not enough. A `file://` URI is used where it
     * lies; anything from a content provider is copied once.
     */
    fun localCopy(context: Context, uri: Uri): File? {
        if (uri.scheme == null || uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            return if (file.isFile) file else null
        }
        val target = File(cacheDirectory(context), "open-${abs(uri.toString().hashCode())}.bin")
        if (target.isFile && target.length() > 0) return target
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target
        }.getOrNull()
    }

    private fun abs(value: Int): Long = if (value < 0) -value.toLong() else value.toLong()

    private fun cacheDirectory(context: Context): File =
        File(context.cacheDir, "office").apply {
            mkdirs()
            // Copies are share-and-forget; keep only the most recent handful.
            listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_CACHED_COPIES)
                ?.forEach { it.delete() }
        }

    fun displayNameOf(context: Context, uri: Uri): String {
        if (uri.scheme == null || uri.scheme == "file") {
            return uri.lastPathSegment ?: "Document"
        }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: "Document"
    }

    private const val MAX_CACHED_COPIES = 4
    private const val HEADER_BYTES = 4096
}
