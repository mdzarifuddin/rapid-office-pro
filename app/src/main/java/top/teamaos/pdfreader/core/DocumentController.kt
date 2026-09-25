package top.teamaos.pdfreader.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.teamaos.pdfreader.data.DocumentRecord
import top.teamaos.pdfreader.data.ReaderDatabase
import top.teamaos.pdfreader.data.ThumbnailStore
import java.io.File
import java.io.FileOutputStream

/**
 * Everything one open document needs, in one lifetime-managed object.
 *
 * Grouping it this way is what makes several documents open at once tractable: each tab is one
 * controller, and a tab moved to the background can drop its page bitmaps with [onBackgrounded]
 * while keeping its position, thumbnails and native handle ready for an instant return.
 */
class DocumentController(
    private val context: Context,
    val uri: Uri,
    private val scope: CoroutineScope,
) {

    lateinit var session: PdfSession
        private set

    lateinit var geometry: PageGeometry
        private set

    lateinit var cache: RenderCache
        private set

    lateinit var renderer: PageRenderer
        private set

    lateinit var measurer: PageMeasurer
        private set

    private var scheduler: RenderScheduler? = null
    private var measuringJob: Job? = null

    var documentId: Long = -1L
        private set

    var record: DocumentRecord? = null
        private set

    var displayName: String = ""
        private set

    /**
     * Called on the main thread whenever new pixels or page sizes are ready.
     *
     * A property rather than a constructor argument because a controller outlives the view showing
     * it: switching tabs re-points this at the new reader without disturbing the document.
     */
    var onInvalidate: (() -> Unit)? = null

    /**
     * Called when page *sizes* changed and the layout has to be rebuilt around them.
     *
     * Kept strictly separate from [onInvalidate]. Re-anchoring the view is not free and not exactly
     * reversible — each round trip nudges the scroll position by a fraction of a pixel, which
     * changes the visible range, which schedules more rendering, which fires the callback again.
     * Doing that on every finished bitmap put the reader into a feedback loop that burned about
     * half a core while sitting still. Finished pixels only ever mean "draw again".
     */
    var onGeometryChanged: (() -> Unit)? = null

    /**
     * Extra observers of the redraw signal, for anything showing pages besides the reader itself —
     * the thumbnail grid, for instance, which needs to redraw as its thumbnails arrive.
     */
    private val extraInvalidateListeners = mutableListOf<() -> Unit>()

    fun addInvalidateListener(listener: () -> Unit) {
        extraInvalidateListeners += listener
    }

    fun removeInvalidateListener(listener: () -> Unit) {
        extraInvalidateListeners -= listener
    }

    private fun notifyInvalidated() {
        onInvalidate?.invoke()
        // Copy first: a listener is free to unregister itself while being told.
        extraInvalidateListeners.toList().forEach { it() }
    }

    private fun notifyGeometryChanged() {
        onGeometryChanged?.invoke()
        extraInvalidateListeners.toList().forEach { it() }
    }

    /** Where the reader was when this tab was last visible, for restoring on switch back. */
    var lastPosition: FloatArray? = null

    val pageCount: Int get() = if (::session.isInitialized) session.pageCount else 0

    private var released = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Open the document and get the reader on screen as fast as possible.
     *
     * Only three things happen before the first frame can be drawn: map the file, read the page
     * count, and measure page 1. Everything else — the real sizes of the other pages, the reading
     * history lookup — happens afterwards.
     */
    suspend fun open(password: String? = null) {
        val startedAt = SystemClock.elapsedRealtime()
        val opened = withContext(Dispatchers.IO) { PdfSession.open(context, uri, password) }
        session = opened
        val mappedAt = SystemClock.elapsedRealtime()

        val firstPageSize = withContext(Dispatchers.IO) { opened.pageSizePoints(0) }
        geometry = PageGeometry(opened.pageCount).apply {
            firstPageSize?.let { seed(it[0], it[1]) }
            // Page 1 is genuinely measured; the rest are its size until proven otherwise.
            firstPageSize?.let { setMeasured(0, it[0], it[1]); rebuild() }
        }

        cache = RenderCache(context)
        val renderThread = RenderScheduler("pdf-render-${uri.hashCode()}")
        scheduler = renderThread
        renderer = PageRenderer(opened, cache, renderThread) { notifyInvalidated() }
        measurer = PageMeasurer(opened, geometry, renderThread)

        loadHistory()
        startMeasuring()
        captureCoverThumbnail()

        Log.i(
            TAG,
            "opened ${session.pageCount} pages in ${SystemClock.elapsedRealtime() - startedAt} ms " +
                "(map+xref ${mappedAt - startedAt} ms), geometry cached=${geometry.isFullyMeasured}",
        )
    }

    private suspend fun loadHistory() {
        val database = ReaderDatabase.get(context)
        val metadata = withContext(Dispatchers.IO) { readFileMetadata() }
        displayName = metadata.first
        documentId = database.upsertDocument(
            uri = uri.toString(),
            displayName = metadata.first,
            folder = metadata.second,
            sizeBytes = metadata.third,
            modifiedAt = 0L,
            pageCount = session.pageCount,
            openedAt = System.currentTimeMillis(),
        )
        record = database.findDocument(uri.toString())

        // A cached geometry means a long document is laid out correctly on the very first frame.
        database.pageSizes(documentId, session.pageCount)?.let { blob ->
            if (geometry.loadBlob(blob)) return
        }
    }

    private fun startMeasuring() {
        if (geometry.isFullyMeasured) return
        measurer.start { _, _ ->
            notifyGeometryChanged()
            if (geometry.isFullyMeasured && documentId > 0 && !geometrySaved) {
                geometrySaved = true
                geometry.toBlob()?.let { blob ->
                    scope.launch { ReaderDatabase.get(context).savePageSizes(documentId, blob) }
                }
            }
        }
    }

    /** The measured sizes are written to the cache once, not on every batch. */
    private var geometrySaved = false

    /**
     * Render the first page once, small, for the file list to show as a cover.
     *
     * Queued at the lowest priority so it can never delay a page the reader is waiting for, and
     * skipped entirely if this document already has one.
     */
    private fun captureCoverThumbnail() {
        val store = ThumbnailStore.get(context)
        val key = uri.toString()
        if (store.has(key)) return
        val renderThread = scheduler ?: return
        renderThread.submit(COVER_THUMBNAIL_KEY, Int.MAX_VALUE) {
            val width = ThumbnailStore.THUMBNAIL_WIDTH_PX
            val aspect = geometry.widthPts(0) / geometry.heightPts(0).coerceAtLeast(1f)
            val height = (width / aspect.coerceAtLeast(0.05f)).toInt().coerceIn(1, 2000)
            val bitmap = runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            }.getOrNull() ?: return@submit
            if (session.renderInto(bitmap, 0, 0, 0, width, height)) {
                scope.launch { store.save(key, bitmap) }
            }
        }
    }

    val hasUnsavedChanges: Boolean
        get() = ::session.isInitialized && session.hasUnsavedChanges

    /**
     * Build a new PDF out of [pages] and hand back the file it was written to.
     *
     * The result lands in the cache directory under a name derived from this document, ready to be
     * shared straight away or copied somewhere permanent. Returns null if extraction is unavailable
     * or fails, in which case nothing was written.
     */
    fun extractPages(pages: Collection<Int>, onDone: (File?) -> Unit) {
        val renderThread = scheduler
        if (renderThread == null || pages.isEmpty() || !PdfNative.canExtractPages) {
            onDone(null)
            return
        }
        val sorted = pages.distinct().sorted()
        val baseName = displayName.substringBeforeLast('.', displayName).take(60).ifBlank { "document" }
        val suffix = if (sorted.size == 1) "page-${sorted.first() + 1}" else "${sorted.size}-pages"
        val target = File(extractionDirectory(), "$baseName ($suffix).pdf")

        renderThread.submit("extract-${sorted.hashCode()}", Int.MIN_VALUE) {
            val ok = runCatching { session.extractPagesTo(sorted, target.absolutePath) }.getOrDefault(false)
            Log.i(TAG, "extract ${sorted.size} page(s): ok=$ok -> ${target.name}")
            mainHandler.post { onDone(if (ok && target.length() > 0L) target else null) }
        }
    }

    /**
     * Read the links on [pages], on the render thread, and report them on the main one.
     *
     * The render thread and nowhere else. Reading a link reaches pdfium directly rather than
     * through the wrapper that serialises everything else, so doing it on a background thread put
     * two callers inside pdfium at once and took the process down with a SIGTRAP the first time a
     * page was scrolled while links were being read. Queued at a low priority, so a page being
     * looked at always renders first.
     */
    fun loadLinks(pages: List<Int>, onDone: (Map<Int, List<PdfLinkTarget>>) -> Unit) {
        val renderThread = scheduler
        if (renderThread == null || pages.isEmpty()) {
            onDone(emptyMap())
            return
        }
        val wanted = pages.toList()
        renderThread.submit("links-${wanted.hashCode()}", LINK_PRIORITY) {
            val found = wanted.associateWith {
                runCatching { session.linksOn(it) }.getOrDefault(emptyList())
            }
            mainHandler.post { onDone(found) }
        }
    }

    /**
     * Write a copy of the document with [pages] split down the middle, and hand back the file.
     *
     * Slower than the other page operations by a lot — every page is copied — so the caller is
     * expected to be showing something while it runs.
     */
    fun splitPages(pages: Collection<Int>, rightFirst: Boolean, onDone: (File?) -> Unit) {
        val renderThread = scheduler
        if (renderThread == null || pages.isEmpty() || !PdfNative.canExtractPages || !PdfNative.canCrop) {
            onDone(null)
            return
        }
        val baseName = displayName.substringBeforeLast('.', displayName).take(60).ifBlank { "document" }
        val target = File(extractionDirectory(), "$baseName (split).pdf")
        val wanted = pages.toList()

        renderThread.submit("split-${wanted.hashCode()}", Int.MIN_VALUE) {
            val ok = runCatching {
                session.splitPagesTo(wanted, target.absolutePath, rightFirst)
            }.getOrDefault(false)
            Log.i(TAG, "split ${wanted.size} page(s): ok=$ok -> ${target.name}")
            mainHandler.post { onDone(if (ok && target.length() > 0L) target else null) }
        }
    }

    /** Extracted files live in their own bounded folder so they can be cleaned up as a group. */
    private fun extractionDirectory(): File =
        File(context.cacheDir, "extracted").apply {
            mkdirs()
            // Keep only the most recent handful; these are share-and-forget files.
            listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_EXTRACTED_FILES)
                ?.forEach { it.delete() }
        }

    /**
     * Turn [pages] by [quarterTurns] clockwise.
     *
     * Runs on the render thread, since that is the only thread allowed to touch open pages. Once
     * pdfium has the new rotation, the affected pages are dropped from the cache and re-measured —
     * a quarter turn swaps their width and height, so the layout has to be told.
     */
    fun rotatePages(pages: Collection<Int>, quarterTurns: Int, onDone: (Boolean) -> Unit) {
        val renderThread = scheduler
        if (renderThread == null || pages.isEmpty()) {
            onDone(false)
            return
        }
        val targets = pages.toList()
        renderThread.submit("rotate-${targets.hashCode()}-$quarterTurns", Int.MIN_VALUE) {
            var changed = false
            val sizes = HashMap<Int, FloatArray>(targets.size)
            targets.forEach { page ->
                if (session.rotatePage(page, quarterTurns)) {
                    changed = true
                    session.pageSizePoints(page)?.let { sizes[page] = it }
                }
            }
            Log.i(TAG, "rotate ${targets.size} page(s) by $quarterTurns: changed=$changed")
            mainHandler.post {
                if (changed) {
                    targets.forEach { cache.evictPage(it) }
                    sizes.forEach { (page, size) -> geometry.setMeasured(page, size[0], size[1]) }
                    geometry.rebuild()
                    renderer.invalidateScale()
                    notifyGeometryChanged()
                }
                onDone(changed)
            }
        }
    }

    /**
     * Trim [pages] to the part of the page described by [fractions].
     *
     * Same shape as [rotatePages], and for the same reason: pages belong to the render thread, and
     * a trimmed page is a different size, so the layout has to be told before it draws again.
     */
    fun cropPages(
        pages: Collection<Int>,
        fractions: RectF,
        onDone: (changed: Boolean, previousBoxes: Map<Int, FloatArray>) -> Unit,
    ) {
        val renderThread = scheduler
        if (renderThread == null || pages.isEmpty()) {
            onDone(false, emptyMap())
            return
        }
        val targets = pages.toList()
        renderThread.submit("crop-${targets.hashCode()}", Int.MIN_VALUE) {
            var changed = false
            val sizes = HashMap<Int, FloatArray>(targets.size)
            // What each page looked like before, so the crop can be taken back off again.
            val previous = HashMap<Int, FloatArray>(targets.size)
            targets.forEach { page ->
                val before = session.cropBoxOf(page)
                if (session.cropPage(page, fractions)) {
                    changed = true
                    before?.let { previous[page] = it }
                    session.pageSizePoints(page)?.let { sizes[page] = it }
                }
            }
            Log.i(TAG, "crop ${targets.size} page(s): changed=$changed")
            mainHandler.post {
                if (changed) {
                    targets.forEach { cache.evictPage(it) }
                    sizes.forEach { (page, size) -> geometry.setMeasured(page, size[0], size[1]) }
                    geometry.rebuild()
                    renderer.invalidateScale()
                    notifyGeometryChanged()
                }
                onDone(changed, previous)
            }
        }
    }

    /** Put pages back to the boxes they had before a crop. The undo of [cropPages]. */
    fun restoreCropBoxes(boxes: Map<Int, FloatArray>, onDone: (Boolean) -> Unit) {
        val renderThread = scheduler
        if (renderThread == null || boxes.isEmpty()) {
            onDone(false)
            return
        }
        val wanted = boxes.toMap()
        renderThread.submit("uncrop-${wanted.keys.hashCode()}", Int.MIN_VALUE) {
            var changed = false
            val sizes = HashMap<Int, FloatArray>(wanted.size)
            wanted.forEach { (page, box) ->
                if (session.restoreCropBox(page, box)) {
                    changed = true
                    session.pageSizePoints(page)?.let { sizes[page] = it }
                }
            }
            mainHandler.post {
                if (changed) {
                    wanted.keys.forEach { cache.evictPage(it) }
                    sizes.forEach { (page, size) -> geometry.setMeasured(page, size[0], size[1]) }
                    geometry.rebuild()
                    renderer.invalidateScale()
                    notifyGeometryChanged()
                }
                onDone(changed)
            }
        }
    }

    /**
     * Write the reader's brush strokes into the document itself.
     *
     * After this they are page content: other readers show them, and this app must stop drawing
     * them as an overlay or they would appear twice.
     */
    fun burnMarks(byPage: Map<Int, List<Stroke>>, onDone: (Boolean) -> Unit) {
        val renderThread = scheduler
        if (renderThread == null || byPage.isEmpty() || !PdfNative.canDraw) {
            onDone(false)
            return
        }
        val wanted = byPage.toMap()
        renderThread.submit("burn-${wanted.keys.hashCode()}", Int.MIN_VALUE) {
            var wrote = false
            wanted.forEach { (page, strokes) ->
                if (session.applyStrokes(page, strokes)) wrote = true
            }
            Log.i(TAG, "burn marks on ${wanted.size} page(s): ok=$wrote")
            mainHandler.post {
                if (wrote) {
                    wanted.keys.forEach { cache.evictPage(it) }
                    renderer.invalidateScale()
                    notifyGeometryChanged()
                }
                onDone(wrote)
            }
        }
    }

    /**
     * Write pending edits back to the document.
     *
     * Always a full rewrite, never an append.
     *
     * pdfium's `FPDF_INCREMENTAL` flag was tried first, since appending a few kilobytes to a 500 MB
     * scan would be instant. It does not do that here: it emits a complete document, header and
     * all. Appending that to the original produces a file whose cross-reference offsets are wrong —
     * a quietly corrupted document. So the new document goes to a temporary file, is checked by
     * reopening it and comparing the page count, and only then replaces the original. Slower on
     * enormous files, but it cannot damage them.
     */
    suspend fun saveChanges(): Boolean = withContext(Dispatchers.IO) {
        if (!::session.isInitialized || !session.hasUnsavedChanges) return@withContext true
        val saved = saveByRewriting()
        if (saved) session.markSaved()
        Log.i(TAG, "save ${if (saved) "succeeded" else "failed"} for $uri")
        saved
    }

    private fun saveByRewriting(): Boolean {
        val temp = File(context.cacheDir, "save-${SystemClock.elapsedRealtime()}.pdf")
        return try {
            val written = FileOutputStream(temp).use { out -> session.writeFullTo(out) }
            if (!written || temp.length() <= 0L) {
                Log.w(TAG, "pdfium wrote nothing")
                return false
            }
            if (!isReadablePdf(temp)) {
                Log.w(TAG, "rewritten copy did not verify; original left alone")
                return false
            }

            when (uri.scheme) {
                "file", null -> {
                    val target = uri.path?.let(::File) ?: return false
                    temp.inputStream().use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
                else -> {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
                    stream.use { output -> temp.inputStream().use { input -> input.copyTo(output) } }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Save failed", e)
            false
        } finally {
            temp.delete()
        }
    }

    /** Sanity check before overwriting anything: the new file must open and have the same pages. */
    private fun isReadablePdf(file: File): Boolean = runCatching {
        PdfSession.open(context, Uri.fromFile(file)).use { check ->
            check.pageCount == session.pageCount
        }
    }.getOrDefault(false)

    /**
     * Persist where the reader got to.
     *
     * Fire and forget, on the document's own process-scoped coroutine scope rather than the
     * reader's. That matters: the moment this is most needed is when the reader is closing, and a
     * write started on the activity's scope is cancelled by the activity being destroyed a
     * moment later — which is why the reading position was so often not there on the way back in.
     */
    fun savePosition(position: FloatArray, viewMode: ViewMode, colorModeOrdinal: Int) {
        if (documentId <= 0 || position.size < 4) return
        val id = documentId
        lastPosition = position
        scope.launch {
            ReaderDatabase.get(context).saveReadingPosition(
                documentId = id,
                page = position[0].toInt(),
                fractionX = position[1],
                fractionY = position[2],
                zoom = position[3],
                viewMode = viewMode.ordinal,
                colorMode = colorModeOrdinal,
            )
        }
    }

    /**
     * This document is no longer the visible tab. Drop the expensive bitmaps but keep the native
     * handle and thumbnails, so switching back is instant without holding full-page memory.
     */
    fun onBackgrounded() {
        val renderThread = scheduler ?: return
        renderThread.clear()
        if (::cache.isInitialized) cache.trim()
        // pdfium's per-page image caches dwarf our own on scanned documents, so drop those too.
        // Must happen on the render thread, which is the only owner of the open-page cache.
        if (::session.isInitialized) {
            renderThread.submit(RELEASE_PAGES_KEY, Int.MIN_VALUE) { session.releaseCachedPages() }
        }
    }

    fun release() {
        if (released) return
        released = true
        measurer.takeIf { ::measurer.isInitialized }?.stop()
        scheduler?.shutdown()
        if (::cache.isInitialized) cache.clear()
        if (::session.isInitialized) session.close()
    }

    /** Display name, containing folder, and size — whatever the URI scheme is willing to tell us. */
    private fun readFileMetadata(): Triple<String, String?, Long> {
        if (uri.scheme == "file" || uri.scheme == null) {
            val file = uri.path?.let(::File)
            if (file != null) return Triple(file.name, file.parent, file.length())
        }
        var name: String? = null
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return Triple(name ?: uri.lastPathSegment ?: "Document", null, size)
    }

    private companion object {
        /** Links wait behind everything on screen; nobody taps a page before it has drawn. */
        const val LINK_PRIORITY = 10_000

        const val TAG = "RapidPDF"
        const val RELEASE_PAGES_KEY = "release-cached-pages"
        const val COVER_THUMBNAIL_KEY = "cover-thumbnail"
        const val MAX_EXTRACTED_FILES = 8
    }
}
