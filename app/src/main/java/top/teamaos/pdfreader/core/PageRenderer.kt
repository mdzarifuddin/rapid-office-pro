package top.teamaos.pdfreader.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** How much memory and bandwidth to spend per pixel. Lower settings also mean less battery. */
enum class RenderQuality {
    /** 16-bit everywhere. Noticeably lighter on memory and GPU upload; fine for scans. */
    FAST,

    /** 16-bit thumbnails, 32-bit pages. The default. */
    BALANCED,

    /** 32-bit everywhere. */
    HIGH,
}

/**
 * Decides what to draw next and feeds it to the render thread.
 *
 * Three levels of detail, each covering the level above's latency:
 *
 *  1. **Thumbnail** — a ~200 px wide render kept for hundreds of pages. Cheap enough to produce
 *     while flinging, so a page is never blank; it is simply blurry for a moment.
 *  2. **Whole page** — one render at the on-screen size. One pdfium call means one JPEG decode for
 *     a scanned page, which is why this is preferred over tiling wherever the page still fits in a
 *     sane bitmap.
 *  3. **Tiles** — only once zoomed past [MAX_PAGE_PIXELS], where a whole-page bitmap would be
 *     absurd. Tiles re-decode the page each time, so they are a last resort, not the default.
 *
 * [generation] rises whenever the zoom changes, so work queued for an old zoom is discarded instead
 * of burning CPU on pixels nobody will see.
 */
class PageRenderer(
    private val session: PdfSession,
    private val cache: RenderCache,
    private val scheduler: RenderScheduler,
    private val onRendered: () -> Unit,
) {

    private sealed interface JobKey {
        val page: Int
    }

    private data class ThumbKey(override val page: Int) : JobKey

    private data class PageKey(override val page: Int, val generation: Int) : JobKey

    private data class TileJobKey(override val page: Int, val generation: Int, val tile: TileKey) : JobKey

    private val main = Handler(Looper.getMainLooper())

    var quality: RenderQuality = RenderQuality.BALANCED

    private var generation: Int = 0

    /** Call when the zoom changed so queued work for the old scale is abandoned. */
    fun invalidateScale() {
        generation++
    }

    private val pageConfig: Bitmap.Config
        get() = if (quality == RenderQuality.FAST) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    private val thumbConfig: Bitmap.Config
        get() = if (quality == RenderQuality.HIGH) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565

    /**
     * Queue a thumbnail if it is missing. Safe to call during a fling: thumbnails are small, and
     * they are the reason fast scrolling shows content rather than white rectangles.
     */
    fun requestThumb(page: Int, aspectRatio: Float, priority: Int) {
        if (cache.thumb(page) != null) return
        val key = ThumbKey(page)
        if (scheduler.isQueuedOrRunning(key)) return
        val width = THUMB_WIDTH_PX
        val height = (width / aspectRatio.coerceAtLeast(0.05f)).toInt().coerceIn(1, THUMB_MAX_HEIGHT_PX)
        scheduler.submit(key, priority) {
            val bitmap = allocate(width, height, thumbConfig) ?: return@submit
            if (session.renderInto(bitmap, page, 0, 0, width, height)) {
                publish { cache.putThumb(page, bitmap) }
            }
        }
    }

    /**
     * Queue a whole-page render at [targetWidth] x [targetHeight]. Oversized pages are rendered
     * smaller and scaled up by the view, with [tilesNeededFor] reporting when that happened so
     * tiles can sharpen what is actually on screen.
     */
    fun requestPage(page: Int, targetWidth: Int, targetHeight: Int, priority: Int) {
        if (targetWidth <= 0 || targetHeight <= 0) return
        val (width, height) = capToBudget(targetWidth, targetHeight)
        val cached = cache.page(page)
        if (cached != null && cached.pageWidthPx >= width) return
        val key = PageKey(page, generation)
        if (scheduler.isQueuedOrRunning(key)) return
        scheduler.submit(key, priority) {
            val bitmap = allocate(width, height, pageConfig) ?: return@submit
            if (session.renderInto(bitmap, page, 0, 0, width, height)) {
                publish { cache.putPage(page, RenderedTile(bitmap, width, height)) }
            }
        }
    }

    /** True when the page is zoomed beyond what one bitmap can hold, so tiles are required. */
    fun tilesNeededFor(targetWidth: Int, targetHeight: Int): Boolean {
        val (width, _) = capToBudget(targetWidth, targetHeight)
        return width < targetWidth * TILE_TRIGGER_RATIO
    }

    /**
     * Queue the tile at [tileX], [tileY] of a page being displayed at [pageWidth] x [pageHeight].
     * Only tiles overlapping the viewport should be asked for.
     */
    fun requestTile(page: Int, tileX: Int, tileY: Int, pageWidth: Int, pageHeight: Int, priority: Int) {
        val tileKey = TileKey(page, tileX, tileY, pageWidth)
        if (cache.tile(tileKey) != null) return
        val key = TileJobKey(page, generation, tileKey)
        if (scheduler.isQueuedOrRunning(key)) return
        val left = tileX * TILE_SIZE_PX
        val top = tileY * TILE_SIZE_PX
        val width = min(TILE_SIZE_PX, pageWidth - left)
        val height = min(TILE_SIZE_PX, pageHeight - top)
        if (width <= 0 || height <= 0) return
        scheduler.submit(key, priority) {
            val bitmap = allocate(width, height, pageConfig) ?: return@submit
            // Negative offsets slide the full-size virtual page so this tile lands on the bitmap.
            if (session.renderInto(bitmap, page, -left, -top, pageWidth, pageHeight)) {
                publish { cache.putTile(tileKey, RenderedTile(bitmap, pageWidth, pageHeight, tileX, tileY)) }
            }
        }
    }

    fun tileCountX(pageWidth: Int): Int = ceil(pageWidth.toFloat() / TILE_SIZE_PX).toInt()

    fun tileCountY(pageHeight: Int): Int = ceil(pageHeight.toFloat() / TILE_SIZE_PX).toInt()

    /**
     * Throw away queued work outside [keep], and any page or tile work from an older zoom. This is
     * what stops a fast scroll from leaving a long tail of pointless rendering behind it.
     */
    fun retainPages(keep: IntRange) {
        val currentGeneration = generation
        scheduler.retainOnly { key ->
            when (key) {
                is ThumbKey -> key.page in keep
                is PageKey -> key.page in keep && key.generation == currentGeneration
                is TileJobKey -> key.page in keep && key.generation == currentGeneration
                // Not ours: extracting, rotating, cropping, measuring and the like share this
                // thread, and dropping them as the page moved left "New PDF" waiting forever.
                else -> true
            }
        }
    }

    fun shutdown() {
        scheduler.clear()
    }

    /** Shrink a requested size until its bitmap fits the per-page budget, preserving aspect. */
    private fun capToBudget(width: Int, height: Int): Pair<Int, Int> {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= MAX_PAGE_PIXELS) return width to height
        val factor = sqrt(MAX_PAGE_PIXELS.toDouble() / pixels).toFloat()
        return (width * factor).toInt().coerceAtLeast(1) to (height * factor).toInt().coerceAtLeast(1)
    }

    private fun allocate(width: Int, height: Int, config: Bitmap.Config): Bitmap? =
        try {
            Bitmap.createBitmap(width, height, config)
        } catch (e: OutOfMemoryError) {
            // Give the caches a chance to hand memory back rather than taking the process down.
            publish { cache.trim() }
            null
        }

    private fun publish(store: () -> Unit) {
        main.post {
            store()
            onRendered()
        }
    }

    companion object {
        const val TILE_SIZE_PX = 512

        /** ~24 MB at 32-bit: comfortably more than a full-screen page, far less than a zoomed one. */
        private const val MAX_PAGE_PIXELS = 6_000_000L

        /** Below this fraction of the requested width, the whole-page render is too soft to rely on. */
        private const val TILE_TRIGGER_RATIO = 0.95f

        /** Wide enough to make out headings and pictures mid-scroll, still cheap per page. */
        private const val THUMB_WIDTH_PX = 320
        private const val THUMB_MAX_HEIGHT_PX = 1600
    }
}
