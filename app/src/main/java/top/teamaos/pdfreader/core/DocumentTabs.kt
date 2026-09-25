package top.teamaos.pdfreader.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The documents currently open, WPS-style: several at once, switched between, closed individually.
 *
 * Process-scoped rather than activity-scoped, because a tab has to survive the reader being
 * recreated — the whole point of a tab is that coming back to it is instant, and that only holds if
 * the native handles, measured page sizes and thumbnails are still there.
 *
 * Two things keep that from turning into a memory leak. Tabs are capped at [MAX_TABS], with the
 * least recently used one closed to make room; and every tab except the visible one is told to drop
 * its page bitmaps and pdfium's decoded-image caches, keeping only what makes the switch back feel
 * instant.
 */
object DocumentTabs {

    const val MAX_TABS = 6

    /** Outlives any single activity, so a controller's background work is not tied to one screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val openTabs = mutableListOf<DocumentController>()

    /** Most-recently-shown order, used to decide which tab to evict at [MAX_TABS]. */
    private val recency = mutableListOf<DocumentController>()

    val tabs: List<DocumentController> get() = openTabs

    var active: DocumentController? = null
        private set

    val count: Int get() = openTabs.size

    fun find(uri: Uri): DocumentController? = openTabs.firstOrNull { it.uri == uri }

    /**
     * Bring [uri] to the front, opening it if it is not already a tab.
     *
     * Reopening something already open is free: the existing controller is returned untouched, so
     * switching back to a 600-page scan costs nothing.
     */
    suspend fun openOrSwitchTo(
        context: Context,
        uri: Uri,
        password: String? = null,
    ): DocumentController {
        find(uri)?.let { existing ->
            makeActive(existing)
            return existing
        }

        val controller = DocumentController(context.applicationContext, uri, scope)
        controller.open(password)

        if (openTabs.size >= MAX_TABS) evictLeastRecentlyUsed()
        openTabs += controller
        makeActive(controller)
        return controller
    }

    /** Switch to an already-open tab. */
    fun makeActive(controller: DocumentController) {
        if (active === controller) {
            touch(controller)
            return
        }
        active?.onBackgrounded()
        active = controller
        touch(controller)
    }

    fun close(controller: DocumentController) {
        openTabs.remove(controller)
        recency.remove(controller)
        controller.onInvalidate = null
        controller.release()
        if (active === controller) {
            active = recency.lastOrNull()
        }
    }

    fun closeAll() {
        openTabs.forEach {
            it.onInvalidate = null
            it.release()
        }
        openTabs.clear()
        recency.clear()
        active = null
    }

    /** Background tabs give back their page bitmaps; called when the whole app goes to the back. */
    fun trimBackgroundTabs() {
        openTabs.forEach { if (it !== active) it.onBackgrounded() }
    }

    private fun touch(controller: DocumentController) {
        recency.remove(controller)
        recency += controller
    }

    private fun evictLeastRecentlyUsed() {
        val victim = recency.firstOrNull { it !== active } ?: openTabs.firstOrNull() ?: return
        close(victim)
    }
}
