package top.teamaos.pdfreader.core

import java.util.PriorityQueue

/**
 * A single background worker that runs render jobs closest to the viewport first.
 *
 * One thread, deliberately. pdfium puts every native call behind one global lock, so extra threads
 * would queue on that lock instead of the queue here — same throughput, more races, more wakeups,
 * more battery. One thread also means [PdfSession]'s open-page cache has a single owner and needs
 * no locking.
 *
 * The worker blocks on [lock] when there is nothing to do, so an idle reader costs zero CPU.
 */
class RenderScheduler(threadName: String) {

    private class Job(
        val key: Any,
        var priority: Int,
        val sequence: Long,
        val work: () -> Unit,
    ) : Comparable<Job> {
        @Volatile
        var cancelled = false

        override fun compareTo(other: Job): Int {
            val byPriority = priority.compareTo(other.priority)
            // Ties go to whatever was asked for first, so nothing starves behind equal-priority work.
            return if (byPriority != 0) byPriority else sequence.compareTo(other.sequence)
        }
    }

    private val lock = Object()
    private val pending = PriorityQueue<Job>()
    private val byKey = HashMap<Any, Job>()
    private var sequence = 0L
    private var running = true

    /** The job the worker is executing right now, if any. Read under [lock]. */
    private var inFlightKey: Any? = null

    private val worker = Thread({ loop() }, threadName).apply {
        // Below default: a stuttering UI thread is worse than a slightly later page.
        priority = Thread.NORM_PRIORITY - 2
        isDaemon = true
        start()
    }

    /**
     * Queue [work] under [key]. Lower [priority] runs sooner — callers pass the page's distance
     * from the viewport. Re-submitting a queued key only re-prioritises it; the work is not
     * duplicated. A key already being rendered is ignored, since the result is about to land.
     */
    fun submit(key: Any, priority: Int, work: () -> Unit) {
        synchronized(lock) {
            if (!running || key == inFlightKey) return
            val existing = byKey[key]
            if (existing != null) {
                if (priority < existing.priority) {
                    // PriorityQueue cannot re-sort in place, so retire the old entry and re-add.
                    existing.cancelled = true
                    pending.remove(existing)
                    val promoted = Job(key, priority, sequence++, work)
                    byKey[key] = promoted
                    pending.add(promoted)
                    lock.notifyAll()
                }
                return
            }
            val job = Job(key, priority, sequence++, work)
            byKey[key] = job
            pending.add(job)
            lock.notifyAll()
        }
    }

    fun isQueuedOrRunning(key: Any): Boolean = synchronized(lock) { byKey.containsKey(key) || key == inFlightKey }

    /**
     * Drop every queued job whose key [keep] rejects. Called as the viewport moves so work for
     * pages that scrolled away never runs — the main reason scrolling stays cheap on battery.
     */
    fun retainOnly(keep: (Any) -> Boolean) {
        synchronized(lock) {
            val iterator = byKey.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!keep(entry.key)) {
                    entry.value.cancelled = true
                    pending.remove(entry.value)
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            pending.forEach { it.cancelled = true }
            pending.clear()
            byKey.clear()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            running = false
            pending.forEach { it.cancelled = true }
            pending.clear()
            byKey.clear()
            lock.notifyAll()
        }
    }

    private fun loop() {
        while (true) {
            val job: Job = synchronized(lock) {
                while (running && pending.isEmpty()) {
                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (!running) return
                val next = pending.poll() ?: return@synchronized null
                byKey.remove(next.key)
                inFlightKey = next.key
                next
            } ?: continue

            try {
                if (!job.cancelled) job.work()
            } catch (e: Throwable) {
                // One bad page must not take the render thread down with it.
            } finally {
                synchronized(lock) { inFlightKey = null }
            }
        }
    }
}
