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

    /** Everything this app will open besides PDF. */
    val EXTENSIONS = setOf(
        "docx", "docm", "dotx", "doc", "dot", "rtf",
        "xlsx", "xlsm", "xltx", "xls", "xlt", "csv", "tsv",
        "pptx", "pptm", "potx", "ppt", "pot", "pps", "ppsx",
        "txt", "md", "log", "json", "xml",
    )

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
            lower.contains("rtf") ||
            lower.contains("csv")
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** The label shown on the file's badge in the list. */
    fun badgeFor(name: String): String = when (extensionOf(name)) {
        "docx", "docm", "dotx", "doc", "dot", "rtf" -> "DOC"
        "xlsx", "xlsm", "xltx", "xls", "xlt" -> "XLS"
        "csv", "tsv" -> "CSV"
        "pptx", "pptm", "potx", "ppt", "pot", "pps", "ppsx" -> "PPT"
        "pdf" -> "PDF"
        else -> "TXT"
    }

    /**
     * Read [file] into a document.
     *
     * Every parser is tried in the order the file's own shape suggests, and each one returns null
     * rather than throwing when the file is not what it expected, so a mislabelled file simply
     * falls through to the parser that does understand it. The last resort is to read it as text,
     * which always produces something a reader can look at.
     */
    fun read(file: File, displayName: String): OfficeDocument? {
        val extension = extensionOf(displayName).ifEmpty { extensionOf(file.name) }
        val zipped = isZip(file)
        val ole = !zipped && Ole2File.looksLikeOle(file)

        val attempts: List<() -> OfficeDocument?> = when {
            zipped -> when (extension) {
                "xlsx", "xlsm", "xltx" -> listOf(
                    { XlsxParser.parse(file, displayName) },
                    { DocxParser.parse(file, displayName) },
                    { PptxParser.parse(file, displayName) },
                )
                "pptx", "pptm", "potx", "ppsx" -> listOf(
                    { PptxParser.parse(file, displayName) },
                    { DocxParser.parse(file, displayName) },
                    { XlsxParser.parse(file, displayName) },
                )
                else -> listOf(
                    { DocxParser.parse(file, displayName) },
                    { XlsxParser.parse(file, displayName) },
                    { PptxParser.parse(file, displayName) },
                )
            }
            ole -> when (extension) {
                "xls", "xlt" -> listOf(
                    { LegacyOfficeParser.parseXls(file, displayName) },
                    { LegacyOfficeParser.parseDoc(file, displayName) },
                    { LegacyOfficeParser.parsePpt(file, displayName) },
                )
                "ppt", "pot", "pps" -> listOf(
                    { LegacyOfficeParser.parsePpt(file, displayName) },
                    { LegacyOfficeParser.parseXls(file, displayName) },
                    { LegacyOfficeParser.parseDoc(file, displayName) },
                )
                else -> listOf(
                    { LegacyOfficeParser.parseDoc(file, displayName) },
                    { LegacyOfficeParser.parseXls(file, displayName) },
                    { LegacyOfficeParser.parsePpt(file, displayName) },
                )
            }
            isRtf(file) -> listOf({ PlainTextParser.parseRtf(file, displayName) })
            extension == "csv" || extension == "tsv" ->
                listOf({ PlainTextParser.parseDelimited(file, displayName) })
            else -> listOf({ PlainTextParser.parseText(file, displayName) })
        }

        attempts.forEach { attempt ->
            runCatching { attempt() }.getOrNull()?.takeIf { it.sections.isNotEmpty() }?.let { return it }
        }
        // Nothing understood it. Showing the readable characters is more use than an error.
        return runCatching { PlainTextParser.parseText(file, displayName) }.getOrNull()
    }

    private fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(4)
            stream.read(header) == 4 &&
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
        }
    }.getOrDefault(false)

    private fun isRtf(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(5)
            stream.read(header) == 5 && String(header, Charsets.US_ASCII) == "{\\rtf"
        }
    }.getOrDefault(false)

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
}
