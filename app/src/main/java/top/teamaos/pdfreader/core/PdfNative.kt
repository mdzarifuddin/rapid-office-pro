package top.teamaos.pdfreader.core

/**
 * The handful of pdfium entry points that io.legere:pdfiumandroid does not wrap.
 *
 * See `src/main/cpp/pdfium_extra.cpp`. If the native library cannot be loaded, or pdfium's symbols
 * cannot be found, [isAvailable] reports false and the reader simply carries on without page
 * rotation rather than failing to open documents.
 */
object PdfNative {

    private val loaded: Boolean = runCatching {
        System.loadLibrary("pdfiumextra")
        true
    }.getOrDefault(false)

    val isAvailable: Boolean by lazy { loaded && runCatching { nativeIsAvailable() }.getOrDefault(false) }

    /** Set a page's rotation, in quarter turns clockwise (0–3). */
    fun setPageRotation(pagePointer: Long, quarterTurns: Int): Boolean =
        isAvailable && runCatching { nativeSetPageRotation(pagePointer, quarterTurns) }.getOrDefault(false)

    fun pageRotation(pagePointer: Long): Int =
        if (!isAvailable) 0 else runCatching { nativeGetPageRotation(pagePointer) }.getOrDefault(0)

    /**
     * Write a copy of a document with each flagged page turned into two halves, side by side.
     *
     * [splitFlags] has one character per page of the source: '1' to split, anything else to leave
     * alone. A flag string rather than a range list because the caller already knows the whole
     * document and this keeps the native side to one pass with no parsing.
     *
     * [rightFirst] is for documents that read right to left, where the right half is page one.
     */
    fun splitPages(
        fd: Int,
        sourcePath: String?,
        password: String?,
        splitFlags: String,
        rightFirst: Boolean,
        outputPath: String,
    ): Boolean {
        if (!canExtractPages || !canCrop || splitFlags.isEmpty()) return false
        return runCatching {
            nativeSplitPages(fd, sourcePath, password, splitFlags, rightFirst, outputPath)
        }.getOrDefault(false)
    }

    /**
     * Open a private handle on a document, used only to read its links. 0 if that is not possible.
     *
     * Links need a real document handle — a link's destination is often a *name*, and only the
     * document can say which page that name is on — and the rendering library does not hand one
     * out. The caller keeps this for as long as the document is open and closes it afterwards.
     */
    fun openLinkReader(fd: Int, sourcePath: String?, password: String?): Long =
        if (!isAvailable) {
            0L
        } else {
            runCatching { nativeOpenLinkReader(fd, sourcePath, password) }.getOrDefault(0L)
        }

    fun closeLinkReader(handle: Long) {
        if (handle != 0L) runCatching { nativeCloseLinkReader(handle) }
    }

    /**
     * Links on one page, each as "left,bottom,right,top|" followed by either `P` and a page
     * number or `U` and a web address. Page coordinates, as everything else here uses.
     */
    fun linksOnPage(handle: Long, pageIndex: Int): List<String> =
        if (handle == 0L) {
            emptyList()
        } else {
            runCatching { nativeLinksOnPage(handle, pageIndex)?.toList() }.getOrNull().orEmpty()
        }

    /** Whether brush strokes can be written into a page on this build of pdfium. */
    val canDraw: Boolean by lazy { loaded && runCatching { nativeCanDraw() }.getOrDefault(false) }

    /**
     * Write one stroke into a page as a path object, making it part of the document.
     *
     * [points] are x, y pairs in page coordinates. The page needs its content regenerated
     * afterwards — see [generatePageContent] — once for the page, not once per stroke.
     */
    fun addStroke(pagePointer: Long, colour: Int, widthPts: Float, points: FloatArray): Boolean =
        canDraw && pagePointer != 0L && points.size >= 2 &&
            runCatching { nativeAddStroke(pagePointer, colour, widthPts, points) }.getOrDefault(false)

    fun generatePageContent(pagePointer: Long): Boolean =
        isAvailable && pagePointer != 0L &&
            runCatching { nativeGeneratePageContent(pagePointer) }.getOrDefault(false)

    /** Whether trimming a page's visible area is possible on this build of pdfium. */
    val canCrop: Boolean by lazy { loaded && runCatching { nativeCanCrop() }.getOrDefault(false) }

    /**
     * The area of a page a viewer actually shows, as {left, bottom, right, top} in PDF points.
     *
     * pdfium's own coordinate order, origin bottom-left. Null if the page has neither a crop box
     * nor a media box that can be read.
     */
    fun visibleBox(pagePointer: Long): FloatArray? =
        if (!isAvailable || pagePointer == 0L) {
            null
        } else {
            runCatching { nativeGetVisibleBox(pagePointer) }.getOrNull()
        }

    /** Trim a page to [left]..[right] by [bottom]..[top], in PDF points, origin bottom-left. */
    fun setCropBox(pagePointer: Long, left: Float, bottom: Float, right: Float, top: Float): Boolean =
        canCrop && runCatching {
            nativeSetCropBox(pagePointer, left, bottom, right, top)
        }.getOrDefault(false)

    /** Whether building a new PDF from selected pages is possible on this build of pdfium. */
    val canExtractPages: Boolean by lazy {
        loaded && runCatching { nativeCanExtract() }.getOrDefault(false)
    }

    /**
     * Write the pages in [pages] (zero-based) from the PDF at [sourcePath] into a new PDF at
     * [outputPath].
     *
     * [sourcePath] may be `/proc/self/fd/N`, which is how a document that arrived through a content
     * provider is read without being copied first.
     */
    fun extractPages(
        sourcePath: String,
        password: String?,
        pages: Collection<Int>,
        outputPath: String,
    ): Boolean {
        if (!canExtractPages || pages.isEmpty() || sourcePath.isEmpty()) return false
        return runCatching {
            nativeExtractPages(sourcePath, password, toPageRanges(pages), outputPath)
        }.getOrDefault(false)
    }

    /**
     * The same, for a document that only exists as an open file descriptor.
     *
     * Anything opened through the system file picker has no path this process may open — the
     * `/proc/self/fd/N` that looks like one is not openable for a provider's descriptor, which is
     * why building a new PDF used to fail on every file picked that way.
     */
    fun extractPagesFromDescriptor(
        fd: Int,
        password: String?,
        pages: Collection<Int>,
        outputPath: String,
    ): Boolean {
        if (!canExtractPages || pages.isEmpty() || fd < 0) return false
        return runCatching {
            nativeExtractPagesFd(fd, password, toPageRanges(pages), outputPath)
        }.getOrDefault(false)
    }

    /**
     * pdfium's page-range syntax: one-based, comma separated, with runs collapsed to "start-end".
     * Collapsing matters — selecting a 500-page run would otherwise build a 3 kB argument string.
     */
    internal fun toPageRanges(pages: Collection<Int>): String {
        val sorted = pages.distinct().sorted()
        val parts = mutableListOf<String>()
        var runStart = sorted.first()
        var previous = runStart
        for (page in sorted.drop(1)) {
            if (page == previous + 1) {
                previous = page
                continue
            }
            parts += formatRun(runStart, previous)
            runStart = page
            previous = page
        }
        parts += formatRun(runStart, previous)
        return parts.joinToString(",")
    }

    private fun formatRun(start: Int, end: Int): String =
        if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"

    private external fun nativeIsAvailable(): Boolean

    private external fun nativeCanExtract(): Boolean

    private external fun nativeExtractPages(
        sourcePath: String,
        password: String?,
        ranges: String,
        outputPath: String,
    ): Boolean

    private external fun nativeSetPageRotation(pagePointer: Long, rotation: Int): Boolean

    private external fun nativeGetPageRotation(pagePointer: Long): Int

    private external fun nativeGeneratePageContent(pagePointer: Long): Boolean

    private external fun nativeExtractPagesFd(
        fd: Int,
        password: String?,
        ranges: String,
        outputPath: String,
    ): Boolean

    private external fun nativeSplitPages(
        fd: Int,
        sourcePath: String?,
        password: String?,
        splitFlags: String,
        rightFirst: Boolean,
        outputPath: String,
    ): Boolean

    private external fun nativeOpenLinkReader(fd: Int, sourcePath: String?, password: String?): Long

    private external fun nativeCloseLinkReader(handle: Long)

    private external fun nativeLinksOnPage(handle: Long, pageIndex: Int): Array<String>?

    private external fun nativeCanDraw(): Boolean

    private external fun nativeAddStroke(
        pagePointer: Long,
        colour: Int,
        width: Float,
        points: FloatArray,
    ): Boolean

    private external fun nativeCanCrop(): Boolean

    private external fun nativeGetVisibleBox(pagePointer: Long): FloatArray?

    private external fun nativeSetCropBox(
        pagePointer: Long,
        left: Float,
        bottom: Float,
        right: Float,
        top: Float,
    ): Boolean
}
