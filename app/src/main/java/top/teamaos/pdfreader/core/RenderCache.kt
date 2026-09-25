package top.teamaos.pdfreader.core

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.collection.LruCache

/** A rendered image plus the full-page pixel size it was rendered for. */
class RenderedTile(
    val bitmap: Bitmap,
    /** Width the whole page had when this was produced, so the view can tell stale zoom levels apart. */
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    /** Tile column and row, or -1 for a whole-page render. */
    val tileX: Int = -1,
    val tileY: Int = -1,
) {
    val byteCount: Int get() = bitmap.allocationByteCount
}

/** Identifies one tile of one page at one zoom level. */
data class TileKey(
    val page: Int,
    val tileX: Int,
    val tileY: Int,
    /** Full-page width in pixels, which is what "zoom level" means for cache purposes. */
    val pageWidthPx: Int,
)

/**
 * Three separate LRUs, because the three kinds of render have very different lifetimes.
 *
 * Thumbnails are tiny, cheap and worth keeping for hundreds of pages — they are what stops the
 * screen ever going blank while you fling. Whole-page renders are the normal reading path. Tiles
 * only exist while you are zoomed past the point where a whole page still fits in one bitmap.
 *
 * Evicted bitmaps are never explicitly recycled: the draw pass on the main thread may still hold a
 * reference to one the render thread just evicted, and recycling it underneath would crash. Letting
 * the collector take them is correct and, since API 26, bitmap memory is native anyway.
 */
class RenderCache(context: Context) {

    private val budgetBytes: Int = run {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val heapMb = activityManager.largeMemoryClass.toLong()
        // Sized for what reading actually needs — roughly a dozen full-screen pages — rather than
        // for whatever the heap allows. pdfium's own per-page image caches are the bigger consumer
        // on scanned documents, so leaving it headroom keeps the whole process well clear of the
        // low-memory killer, and a smaller working set is easier on the battery too.
        (heapMb * 1024L * 1024L / 6L).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES).toInt()
    }

    private val thumbs = object : LruCache<Int, Bitmap>(THUMB_BUDGET_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount
    }

    // Long arithmetic on purpose: a 320 MB budget times a percentage overflows a signed Int, which
    // silently yields a negative cache size.
    private fun share(percent: Int): Int = (budgetBytes.toLong() * percent / 100L).toInt()

    private val pages = object : LruCache<Int, RenderedTile>(share(PAGE_SHARE_PERCENT)) {
        override fun sizeOf(key: Int, value: RenderedTile): Int = value.byteCount
    }

    private val tiles = object : LruCache<TileKey, RenderedTile>(share(TILE_SHARE_PERCENT)) {
        override fun sizeOf(key: TileKey, value: RenderedTile): Int = value.byteCount
    }

    fun thumb(page: Int): Bitmap? = thumbs[page]

    fun putThumb(page: Int, bitmap: Bitmap) {
        thumbs.put(page, bitmap)
    }

    fun page(page: Int): RenderedTile? = pages[page]

    fun putPage(page: Int, tile: RenderedTile) {
        pages.put(page, tile)
    }

    fun tile(key: TileKey): RenderedTile? = tiles[key]

    fun putTile(key: TileKey, tile: RenderedTile) {
        tiles.put(key, tile)
    }

    /**
     * Forget everything rendered for one page. Called after rotating it, so the stale orientation
     * is not shown from cache.
     */
    fun evictPage(page: Int) {
        thumbs.remove(page)
        pages.remove(page)
        // LruCache has no predicate removal, so the tile keys have to be walked.
        tiles.snapshot().keys.filter { it.page == page }.forEach { tiles.remove(it) }
    }

    /** Called on trim-memory: keep thumbnails (they are what prevents blank pages) and drop the rest. */
    fun trim() {
        pages.evictAll()
        tiles.evictAll()
    }

    fun clear() {
        thumbs.evictAll()
        pages.evictAll()
        tiles.evictAll()
    }

    companion object {
        private const val MIN_BUDGET_BYTES = 40L * 1024L * 1024L
        private const val MAX_BUDGET_BYTES = 128L * 1024L * 1024L
        /** Around a hundred previews at their larger size, so scrolling back is instant. */
        private const val THUMB_BUDGET_BYTES = 24 * 1024 * 1024
        private const val PAGE_SHARE_PERCENT = 65
        private const val TILE_SHARE_PERCENT = 35
    }
}
