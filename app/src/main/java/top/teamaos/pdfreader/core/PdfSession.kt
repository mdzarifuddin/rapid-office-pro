package top.teamaos.pdfreader.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.AlreadyClosedBehavior
import io.legere.pdfiumandroid.api.Bookmark
import io.legere.pdfiumandroid.api.Config
import io.legere.pdfiumandroid.api.DefaultLogger
import io.legere.pdfiumandroid.api.Meta
import io.legere.pdfiumandroid.api.PdfWriteCallback
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Somewhere on a page that can be tapped.
 *
 * Exactly one of [url] and [destinationPage] is set: a link either leaves the document or moves
 * within it. [bounds] is in page coordinates, with top above bottom in screen terms.
 */
data class PdfLinkTarget(val bounds: RectF, val url: String?, val destinationPage: Int?)

/** Thrown when a document needs a password we do not have, or the one we have is wrong. */
class PdfPasswordException(message: String) : IOException(message)

/**
 * Owns the native pdfium handles for one open document.
 *
 * Opening goes through a [ParcelFileDescriptor] so pdfium memory-maps the file instead of reading
 * it into memory: a 500 MB scan opens as fast as a 50 KB invoice, which is the whole point here.
 *
 * pdfium serialises every native call behind one global lock, so extra render threads buy nothing.
 * All rendering happens on the single thread owned by [RenderScheduler], and [pageLru] is only ever
 * touched from there. Callers on other threads use [withTextPage] or [pageSizePoints], which open
 * and close their own short-lived handles and so cannot race with the render thread's pages.
 */
class PdfSession private constructor(
    private val doc: PdfDocument,
    private val pfd: ParcelFileDescriptor?,
    val pageCount: Int,
    val docKey: String,
    /**
     * A path pdfium can reopen this document from.
     *
     * For a local file that is its real path; for anything that arrived through a content provider
     * it is `/proc/self/fd/N` for the descriptor this session holds open, which lets the file be
     * read again without copying a single byte of it.
     */
    val sourcePath: String?,
) : Closeable {

    @Volatile
    private var closed = false

    /**
     * One lock in front of pdfium, for every call this class makes.
     *
     * Most of the calls here go through a wrapper library that has a global lock of its own, and a
     * few — extraction, cropping, splitting, links — reach pdfium directly and are not covered by
     * it. Mixing the two is what matters: a link being read on one thread while a page rendered on
     * another put two callers inside pdfium at once and killed the process outright. Rather than
     * reasoning about which pairs are safe, everything queues here. It costs nothing, because
     * pdfium serialises itself anyway; what it buys is that no future call can get this wrong.
     *
     * Reentrant, being a plain monitor: rotating a page takes it, and the page it opens takes it
     * again on the way through.
     */
    private val pdfium = Any()

    /** Open [PdfPage] handles, least-recently-used first. Render-thread only. */
    private val pageLru = LinkedHashMap<Int, PdfPage>(PAGE_LRU_SIZE, 0.75f, true)

    val isClosed: Boolean get() = closed

    /** Page size in PostScript points, read without holding the page open. */
    fun pageSizePoints(index: Int): FloatArray? = synchronized(pdfium) {
        if (closed) return null
        return try {
            val size = doc.getPageSize(index, POINTS_DPI)
            if (size.width <= 0 || size.height <= 0) {
                null
            } else {
                floatArrayOf(size.width.toFloat(), size.height.toFloat())
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Render part of a page into [bitmap].
     *
     * [drawWidth] and [drawHeight] are the size the *whole* page would have on screen, while
     * [startX] and [startY] place that virtual page relative to the bitmap's top-left corner. To
     * draw a complete page, pass startX/startY of 0 and the bitmap's own size; to draw one tile,
     * pass the tile's negated offset together with the full scaled page size.
     *
     * Must be called from the render thread.
     */
    fun renderInto(
        bitmap: Bitmap,
        index: Int,
        startX: Int,
        startY: Int,
        drawWidth: Int,
        drawHeight: Int,
    ): Boolean = synchronized(pdfium) {
        if (closed) return false
        val page = acquirePage(index) ?: return false
        return try {
            page.renderPageBitmap(
                bitmap,
                startX,
                startY,
                drawWidth,
                drawHeight,
                RENDER_ANNOTATIONS,
                false,
                Color.WHITE,
                Color.WHITE,
            )
            true
        } catch (e: Exception) {
            // A damaged page should cost us that page, not the whole document.
            dropPage(index)
            false
        }
    }

    /** Render-thread only. Keeps parsed pages around so re-rendering while scrolling stays cheap. */
    private fun acquirePage(index: Int): PdfPage? = synchronized(pdfium) {
        pageLru[index]?.let { return it }
        val page = try {
            doc.openPage(index)
        } catch (e: Exception) {
            null
        } ?: return null
        pageLru[index] = page
        if (pageLru.size > PAGE_LRU_SIZE) {
            val iterator = pageLru.entries.iterator()
            if (iterator.hasNext()) {
                val eldest = iterator.next()
                iterator.remove()
                runCatching { eldest.value.close() }
            }
        }
        return page
    }

    private fun dropPage(index: Int) = synchronized(pdfium) {
        pageLru.remove(index)?.let { runCatching { it.close() } }
        Unit
    }

    /**
     * Close every open page, releasing pdfium's per-page decoded-image caches.
     *
     * Must be called from the render thread — schedule it rather than calling it directly.
     */
    fun releaseCachedPages() = synchronized(pdfium) {
        pageLru.values.forEach { runCatching { it.close() } }
        pageLru.clear()
    }

    /**
     * Turn a page by [quarterTurns] clockwise, relative to however it sits now.
     *
     * This changes the page dictionary inside the open document, so the change is visible to
     * rendering straight away and is included by [writeFullTo]. Nothing is written to disk here.
     *
     * Render-thread only.
     */
    fun rotatePage(index: Int, quarterTurns: Int): Boolean = synchronized(pdfium) {
        if (closed || quarterTurns == 0) return false
        val page = acquirePage(index) ?: return false
        val pointer = PdfPointers.pagePointer(page)
        if (pointer == 0L) return false
        val current = PdfNative.pageRotation(pointer)
        val applied = PdfNative.setPageRotation(pointer, (current + quarterTurns) % 4)
        if (applied) hasUnsavedChanges = true
        return applied
    }

    /**
     * Trim a page down to part of what it currently shows.
     *
     * [fractions] describes the keep-area as fractions of the page **as it appears on screen**,
     * measured from its top-left corner. Two conversions happen here, and both matter:
     *
     *  * the page may be turned, and its crop box is stored in unturned page space, so the corners
     *    are mapped back through the rotation first;
     *  * PDF boxes have their origin at the bottom-left with y increasing upwards, the opposite of
     *    everything on screen.
     *
     * The page is dropped from the cache afterwards: pdfium works out a page's size when it opens
     * it, so the already-open handle would keep drawing the old, untrimmed page.
     *
     * Nothing is written to disk here. Render-thread only.
     */
    fun cropPage(index: Int, fractions: RectF): Boolean = synchronized(pdfium) {
        if (closed) return false
        val page = acquirePage(index) ?: return false
        val pointer = PdfPointers.pagePointer(page)
        if (pointer == 0L) return false
        val box = PdfNative.visibleBox(pointer) ?: return false
        val boxWidth = box[2] - box[0]
        val boxHeight = box[3] - box[1]
        if (boxWidth <= 0f || boxHeight <= 0f) return false

        val unturned = unturnFractions(fractions, PdfNative.pageRotation(pointer))
        val left = box[0] + unturned.left * boxWidth
        val right = box[0] + unturned.right * boxWidth
        val top = box[3] - unturned.top * boxHeight
        val bottom = box[3] - unturned.bottom * boxHeight
        if (right - left < MIN_CROP_PTS || top - bottom < MIN_CROP_PTS) return false

        val applied = PdfNative.setCropBox(pointer, left, bottom, right, top)
        if (applied) {
            hasUnsavedChanges = true
            dropPage(index)
        }
        return applied
    }

    /** Turn on-screen fractions back into fractions of the page as the file stores it. */
    private fun unturnFractions(shown: RectF, quarterTurns: Int): RectF {
        val corners = when (((quarterTurns % 4) + 4) % 4) {
            1 -> floatArrayOf(shown.top, 1f - shown.right, shown.bottom, 1f - shown.left)
            2 -> floatArrayOf(1f - shown.right, 1f - shown.bottom, 1f - shown.left, 1f - shown.top)
            3 -> floatArrayOf(1f - shown.bottom, shown.left, 1f - shown.top, shown.right)
            else -> floatArrayOf(shown.left, shown.top, shown.right, shown.bottom)
        }
        return RectF(
            minOf(corners[0], corners[2]).coerceIn(0f, 1f),
            minOf(corners[1], corners[3]).coerceIn(0f, 1f),
            maxOf(corners[0], corners[2]).coerceIn(0f, 1f),
            maxOf(corners[1], corners[3]).coerceIn(0f, 1f),
        )
    }

    /** The page's visible box, as {left, bottom, right, top} in points. Render-thread only. */
    fun cropBoxOf(index: Int): FloatArray? = synchronized(pdfium) {
        if (closed) return null
        val page = acquirePage(index) ?: return null
        PdfNative.visibleBox(PdfPointers.pagePointer(page))
    }

    /**
     * Put a page's visible box back to exactly what it was.
     *
     * The counterpart of [cropPage], and the reason undoing a crop can restore the page rather
     * than approximate it: the box that was there before is kept and written straight back.
     */
    fun restoreCropBox(index: Int, box: FloatArray): Boolean = synchronized(pdfium) {
        if (closed || box.size < 4) return false
        val page = acquirePage(index) ?: return false
        val pointer = PdfPointers.pagePointer(page)
        if (pointer == 0L) return false
        val applied = PdfNative.setCropBox(pointer, box[0], box[1], box[2], box[3])
        if (applied) {
            hasUnsavedChanges = true
            dropPage(index)
        }
        return applied
    }

    /**
     * Write brush strokes into a page, turning them from an overlay into part of the document.
     *
     * The page's content stream is regenerated once at the end rather than after each stroke:
     * regenerating rebuilds everything on the page, and doing that per stroke on a marked-up page
     * is the difference between a moment and a minute.
     */
    fun applyStrokes(index: Int, strokes: List<Stroke>): Boolean = synchronized(pdfium) {
        if (closed || strokes.isEmpty()) return false
        val page = acquirePage(index) ?: return false
        val pointer = PdfPointers.pagePointer(page)
        if (pointer == 0L) return false
        var wrote = false
        strokes.forEach { stroke ->
            if (PdfNative.addStroke(pointer, stroke.colour, stroke.widthPts, stroke.points)) wrote = true
        }
        if (wrote) {
            PdfNative.generatePageContent(pointer)
            hasUnsavedChanges = true
        }
        return wrote
    }

    /** Current rotation of a page, in quarter turns. Render-thread only. */
    fun pageRotation(index: Int): Int = synchronized(pdfium) {
        if (closed) return 0
        val page = acquirePage(index) ?: return 0
        return PdfNative.pageRotation(PdfPointers.pagePointer(page))
    }

    /**
     * Build a new PDF containing only [pages] (zero-based) and write it to [outputPath].
     *
     * Render-thread only: pdfium is not re-entrant, and this reaches past the wrapper's lock.
     */
    fun extractPagesTo(
        pages: Collection<Int>,
        outputPath: String,
        password: String? = null,
    ): Boolean = synchronized(pdfium) {
        if (closed || pages.isEmpty()) return false

        // A real file on disk is opened by name; anything that came through a content provider is
        // read from the descriptor this session already holds. The descriptor route used to be a
        // "/proc/self/fd/N" path, which pdfium cannot open for a provider's file — that is what
        // made "make a new PDF" fail for every document opened from the system picker.
        val path = sourcePath
        if (path != null && !path.startsWith(PROC_FD_PREFIX)) {
            if (PdfNative.extractPages(path, password, pages, outputPath)) return true
        }
        val descriptor = pfd?.fd ?: return false
        return PdfNative.extractPagesFromDescriptor(descriptor, password, pages, outputPath)
    }

    /**
     * Write a copy of this document with [pages] each split into two halves.
     *
     * Nothing about the open document changes; the result is a new file, which the reader then
     * opens. Splitting is a change to how many pages a document has, and doing that in place would
     * invalidate every page number the reader is holding — the bookmark, the reading position, the
     * search results — for a document they may not want to keep this way.
     *
     * Render-thread only, like the rest of pdfium.
     */
    fun splitPagesTo(
        pages: Collection<Int>,
        outputPath: String,
        rightFirst: Boolean = false,
        password: String? = null,
    ): Boolean = synchronized(pdfium) {
        if (closed || pages.isEmpty()) return false
        val wanted = pages.toHashSet()
        val flags = CharArray(pageCount) { if (it in wanted) '1' else '0' }.concatToString()
        val path = sourcePath?.takeIf { !it.startsWith(PROC_FD_PREFIX) }
        return PdfNative.splitPages(pfd?.fd ?: -1, path, password, flags, rightFirst, outputPath)
    }

    /** True once something has been changed that is not yet on disk. */
    @Volatile
    var hasUnsavedChanges: Boolean = false
        private set

    fun markSaved() {
        hasUnsavedChanges = false
    }

    /**
     * Write a complete, self-contained copy of the document, including any rotations applied.
     *
     * Only the full-rewrite flag is offered. pdfium's FPDF_INCREMENTAL was tried and does not
     * produce an appendable delta here — it emits a whole document, header and all — so exposing it
     * would only invite someone to append it to the original and corrupt the file.
     */
    fun writeFullTo(output: OutputStream): Boolean = synchronized(pdfium) {
        if (closed) return false
        return runCatching {
            doc.saveAsCopy(
                object : PdfWriteCallback {
                    override fun WriteBlock(data: ByteArray?): Int {
                        if (data == null || data.isEmpty()) return 0
                        output.write(data)
                        return data.size
                    }
                },
                PdfDocument.FPDF_NO_INCREMENTAL,
            )
        }.getOrDefault(false)
    }

    /**
     * Every link on a page: the ones the file declares, and the web addresses in its text.
     *
     * Both kinds matter and they are stored quite differently. A properly made PDF marks a link as
     * an annotation with a target; a PDF made by printing to file often has the address only as
     * text, and pdfium can find those separately. A reader tapping either expects the same thing
     * to happen.
     *
     * Rectangles come back in page coordinates — points, origin bottom-left — like search hits.
     * Safe off the render thread: every handle opened here is closed here.
     */
    fun linksOn(index: Int): List<PdfLinkTarget> = synchronized(pdfium) {
        if (closed) return emptyList()
        return runCatching {
            val found = mutableListOf<PdfLinkTarget>()
            // pdfium's own reading of the link annotations, which resolves named destinations —
            // the kind Word and LaTeX write, and the kind the wrapper library cannot follow.
            PdfNative.linksOnPage(linkReader(), index).forEach { entry ->
                parseLinkEntry(entry)?.let { found += it }
            }
            doc.openPage(index)?.use { page ->
                if (found.isEmpty()) {
                    page.getPageLinks().forEach { link ->
                        val bounds = link.bounds ?: return@forEach
                        if (link.uri == null && link.destPageIdx == null) return@forEach
                        found += PdfLinkTarget(normalise(bounds), link.uri, link.destPageIdx)
                    }
                }
                page.openTextPage().use { text ->
                    text.loadWebLink()?.use { web ->
                        val count = web.countWebLinks()
                        for (link in 0 until count) {
                            val url = web.getURL(link, MAX_URL_CHARS)?.takeIf { it.isNotBlank() }
                                ?: continue
                            for (rect in 0 until web.countRects(link)) {
                                val rectangle = web.getRect(link, rect) ?: continue
                                found += PdfLinkTarget(normalise(rectangle), url, null)
                            }
                        }
                    }
                }
            }
            found.toList()
        }.getOrDefault(emptyList())
    }

    /**
     * The link-reading document handle, opened the first time a link is asked for.
     *
     * Lazy because most reading never touches a link, and opening a second view of a large
     * document costs a cross-reference parse that would otherwise be paid on every open.
     */
    @Volatile
    private var linkReaderHandle = 0L
    private var linkReaderTried = false

    private fun linkReader(): Long = synchronized(pdfium) {
        if (linkReaderTried || closed) return linkReaderHandle
        linkReaderTried = true
        val path = sourcePath?.takeIf { !it.startsWith(PROC_FD_PREFIX) }
        linkReaderHandle = PdfNative.openLinkReader(pfd?.fd ?: -1, path, null)
        linkReaderHandle
    }

    /** "12.0,30.0,90.0,44.0|Uhttps://example.com" or "…|P17". */
    private fun parseLinkEntry(entry: String): PdfLinkTarget? {
        val bar = entry.indexOf('|')
        if (bar <= 0 || bar + 1 >= entry.length) return null
        val numbers = entry.substring(0, bar).split(',')
        if (numbers.size != 4) return null
        val values = numbers.map { it.toFloatOrNull() ?: return null }
        val bounds = normalise(RectF(values[0], values[1], values[2], values[3]))
        if (bounds.width() <= 0f || bounds.top - bounds.bottom <= 0f) return null
        val target = entry.substring(bar + 1)
        return when (target.firstOrNull()) {
            'U' -> PdfLinkTarget(bounds, target.drop(1), null)
            'P' -> target.drop(1).toIntOrNull()?.takeIf { it >= 0 }
                ?.let { PdfLinkTarget(bounds, null, it) }
            else -> null
        }
    }

    /** pdfium hands back rectangles with y increasing upwards and no promised corner order. */
    private fun normalise(rect: RectF): RectF = RectF(
        minOf(rect.left, rect.right),
        maxOf(rect.top, rect.bottom),
        maxOf(rect.left, rect.right),
        minOf(rect.top, rect.bottom),
    )

    /**
     * Run [block] with a freshly opened text page. Safe off the render thread: the handle is
     * private to this call, so nothing else can close it underneath us.
     */
    fun <T> withTextPage(index: Int, block: (PdfTextPage) -> T): T? = synchronized(pdfium) {
        if (closed) return null
        return try {
            doc.openPage(index)?.use { page ->
                page.openTextPage().use { text -> block(text) }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun tableOfContents(): List<Bookmark> = synchronized(pdfium) {
        if (closed) emptyList() else runCatching { doc.getTableOfContents() }.getOrDefault(emptyList())
    }

    fun metadata(): Meta? = synchronized(pdfium) {
        if (closed) null else runCatching { doc.getDocumentMeta() }.getOrNull()
    }

    override fun close() {
        synchronized(pdfium) {
            if (closed) return
            closed = true
            pageLru.values.forEach { runCatching { it.close() } }
            pageLru.clear()
            PdfNative.closeLinkReader(linkReaderHandle)
            linkReaderHandle = 0L
            runCatching { doc.close() }
            runCatching { pfd?.close() }
        }
    }

    companion object {
        private const val POINTS_DPI = 72
        private const val PROC_FD_PREFIX = "/proc/self/fd/"

        /**
         * How many parsed pages to keep open.
         *
         * Deliberately small. pdfium hangs a render cache off every open page, and for a scanned
         * document that cache holds the page's *decoded* image — around 26 MB for a 300 dpi A4
         * scan. Keeping eight pages open cost nearly 400 MB on a 600-page scan. Re-parsing a page
         * costs a fraction of a millisecond, so three is a much better trade.
         */
        private const val PAGE_LRU_SIZE = 3
        private const val RENDER_ANNOTATIONS = true

        /** Refuse to trim a page to less than this, in points — about 4 mm. Guards a fat-fingered drag. */
        private const val MIN_CROP_PTS = 12f

        /** A URL longer than this is not one anybody meant to follow. */
        private const val MAX_URL_CHARS = 2048

        /**
         * pdfium's own page retention defaults to 8, on top of [pageLru], which on a 300 dpi scan
         * meant eight decoded A4 images — around 200 MB — sitting in memory the whole time. Four is
         * enough to cover the three pages this class holds open.
         *
         * [AlreadyClosedBehavior.IGNORE] rather than the default EXCEPTION: a reader closing a
         * document while a render is in flight is a normal race, not a reason to crash.
         */
        private val READER_CONFIG = Config(
            DefaultLogger(),
            AlreadyClosedBehavior.IGNORE,
            PAGE_LRU_SIZE + 1,
        )

        /**
         * Open [uri]. Cheap for any file size: pdfium maps the descriptor and parses only the
         * cross-reference table up front.
         *
         * @throws PdfPasswordException if the document is encrypted and [password] does not fit.
         * @throws IOException if the document cannot be opened at all.
         */
        @Throws(IOException::class)
        fun open(context: Context, uri: Uri, password: String? = null): PdfSession {
            val pfd = openDescriptor(context, uri) ?: throw IOException("Could not open $uri")
            val core = PdfiumCore(context.applicationContext, READER_CONFIG)
            val doc = try {
                core.newDocument(pfd, password)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                throw asOpenFailure(e)
            }
            val count = try {
                doc.getPageCount()
            } catch (e: Exception) {
                runCatching { doc.close() }
                runCatching { pfd.close() }
                throw IOException("Could not read page count", e)
            }
            if (count <= 0) {
                runCatching { doc.close() }
                runCatching { pfd.close() }
                throw IOException("Document has no pages")
            }
            return PdfSession(doc, pfd, count, uri.toString(), sourcePathFor(uri, pfd))
        }

        /** Where pdfium can reopen this document from; see [PdfSession.sourcePath]. */
        private fun sourcePathFor(uri: Uri, pfd: ParcelFileDescriptor): String? = when (uri.scheme) {
            null, "file" -> uri.path
            else -> "/proc/self/fd/${pfd.fd}"
        }

        private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? =
            when (uri.scheme) {
                null, "file" -> uri.path?.let { path ->
                    val file = File(path)
                    if (file.isFile) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } else {
                        null
                    }
                }
                else -> context.contentResolver.openFileDescriptor(uri, "r")
            }

        private fun asOpenFailure(e: Exception): IOException {
            val text = (e.message ?: "").lowercase()
            return if ("password" in text || "encrypt" in text) {
                PdfPasswordException(e.message ?: "Password required")
            } else {
                IOException(e.message ?: "Could not open document", e)
            }
        }
    }
}
