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
 * recreated.
 *
 * Up to [MAX_TABS] documents stay in the list, but only [MAX_LIVE_TABS] of them hold an open file,
 * a render thread and bitmaps at any moment. The rest are *parked*: name, page count and reading
 * position only, a few hundred bytes each. Switching to a parked tab reopens its file in a fraction
 * of a second. Holding every tab open was what made the reader run out of memory after a few days —
 * pages fell back to blurry previews and scanned pages stopped appearing — because nothing was ever
 * given back until the system killed the app.
 */
object DocumentTabs {

    const val MAX_TABS = 25

    /** The visible tab and the one before it, so flipping between two documents stays instant. */
    const val MAX_LIVE_TABS = 2

    /** Outlives any single activity, so a controller's background work is not tied to one screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val openTabs = mutableListOf<DocumentController>()

    /** Most-recently-shown order, used to decide which tab to park or evict. */
    private val recency = mutableListOf<DocumentController>()

    val tabs: List<DocumentController> get() = openTabs

    var active: DocumentController? = null
        private set

    val count: Int get() = openTabs.size

    fun find(uri: Uri): DocumentController? = openTabs.firstOrNull { it.uri == uri }

    /**
     * Bring [uri] to the front, opening it if it is not already a tab.
     *
     * Reopening something already open is free; a parked tab reopens its file first.
     */
    suspend fun openOrSwitchTo(
        context: Context,
        uri: Uri,
        password: String? = null,
    ): DocumentController {
        find(uri)?.let { existing ->
            activate(existing)
            return existing
        }

        val controller = DocumentController(context.applicationContext, uri, scope)
        try {
            controller.open(password)
        } catch (e: Throwable) {
            // Whatever was half-opened must not linger holding the file.
            runCatching { controller.release() }
            throw e
        }

        while (openTabs.size >= MAX_TABS) {
            if (!evictLeastRecentlyUsed()) break
        }
        openTabs += controller
        makeActive(controller)
        parkExcess()
        return controller
    }

    /**
     * Switch to [controller], reopening its file first if it was parked.
     *
     * @throws java.io.IOException if a parked document can no longer be opened — deleted or moved
     *   since. The tab is closed in that case, so the list never offers a dead entry twice.
     */
    suspend fun activate(controller: DocumentController) {
        if (controller.isParked) {
            try {
                controller.wake()
            } catch (e: Throwable) {
                close(controller)
                throw e
            }
        }
        makeActive(controller)
        parkExcess()
    }

    /** Switch to an already-open, live tab. */
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
        controller.onGeometryChanged = null
        controller.release()
        if (active === controller) {
            active = recency.lastOrNull()
        }
    }

    fun closeAll() {
        openTabs.forEach {
            it.onInvalidate = null
            it.onGeometryChanged = null
            it.release()
        }
        openTabs.clear()
        recency.clear()
        active = null
    }

    /**
     * The app is in the background or short of memory: park every tab but the visible one, and
     * have that one drop its bitmaps.
     */
    fun trimBackgroundTabs() {
        openTabs.forEach { if (it !== active) it.park() }
        active?.onBackgrounded()
    }

    /** Short of memory while reading: every tab but the visible one gives back its file. */
    fun parkBackgroundTabs() {
        openTabs.forEach { if (it !== active) it.park() }
    }

    /** The reader has closed: nothing needs to stay open until a document is shown again. */
    fun parkAll() {
        openTabs.forEach { it.park() }
    }

    private fun touch(controller: DocumentController) {
        recency.remove(controller)
        recency += controller
    }

    /** Keep at most [MAX_LIVE_TABS] documents open, parking the least recently shown first. */
    private fun parkExcess() {
        var live = openTabs.count { it.isLive }
        for (candidate in recency.toList()) {
            if (live <= MAX_LIVE_TABS) break
            if (candidate === active || !candidate.isLive) continue
            if (candidate.park()) live--
        }
    }

    /**
     * Make room for one more tab. A tab with unsaved changes is the last to go. Returns false if
     * nothing could be closed.
     */
    private fun evictLeastRecentlyUsed(): Boolean {
        val victim = recency.firstOrNull { it !== active && !it.hasUnsavedChanges }
            ?: recency.firstOrNull { it !== active }
            ?: openTabs.firstOrNull { it !== active }
            ?: return false
        close(victim)
        return true
    }
}
