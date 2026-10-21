package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** The session gap (docs/analytics/product-events.md "Sessions"): a return after this long is a new open. */
const val RaftAppOpenForegroundGapMillis: Long = 30 * 60_000L

/**
 * `app_opened.start`: [RaftAppOpenStart.Cold] on the process's first foreground,
 * [RaftAppOpenStart.Foreground] only for a return after at least [foregroundGapMillis] in the
 * background, nothing otherwise. Repeated foreground signals without a background in between
 * (iOS inactive -> active) are no-ops. Wall-clock time so a sleeping device still counts.
 */
class RaftAppOpenTracker(
    private val foregroundGapMillis: Long = RaftAppOpenForegroundGapMillis,
) {
    private val lock = SynchronizedObject()
    private var everForeground = false
    private var inForeground = false
    private var backgroundSinceMillis: Long? = null

    fun onForeground(nowMillis: Long): RaftAppOpenStart? =
        synchronized(lock) {
            if (inForeground) return null
            inForeground = true
            if (!everForeground) {
                everForeground = true
                return RaftAppOpenStart.Cold
            }
            val since = backgroundSinceMillis
            backgroundSinceMillis = null
            if (since != null && nowMillis - since >= foregroundGapMillis) RaftAppOpenStart.Foreground else null
        }

    fun onBackground(nowMillis: Long) {
        synchronized(lock) {
            if (!inForeground) return
            inForeground = false
            backgroundSinceMillis = nowMillis
        }
    }
}

/**
 * One `page_viewed` per page entry. A slot is one mounted page (a pager id, or a shell-owned inline
 * pane); hosts report its route and its visibility (page appear/disappear).
 *
 * - A slot reports its route once per entry: recomposition, a repeated appear, or the same route
 *   reported again are not entries.
 * - Coming back to a slot that was covered by another page (Back from a pushed page, switching
 *   back to a tab) is an entry: web counts route changes the same way.
 * - A slot that appears again after the app was in the background is not an entry, nor is one
 *   that only paused (a system dialog) without another page showing in between.
 */
class RaftPageViewTracker(
    private val emit: (RaftProductPageView) -> Unit,
) {
    private class Slot {
        var route: RaftProductPageView? = null
        var visible = false
        var lastEmitted: RaftProductPageView? = null
        var hiddenAtShowSequence: Long? = null
        var backgroundSinceShown = false
    }

    private val lock = SynchronizedObject()
    private val slots = HashMap<String, Slot>()
    private var showSequence = 0L

    fun onPageRoute(slotKey: String, route: RaftProductPageView?) {
        val view =
            synchronized(lock) {
                val slot = slots.getOrPut(slotKey) { Slot() }
                slot.route = route
                if (slot.visible) slot.takeEmission() else null
            }
        view?.let(emit)
    }

    fun onPageShown(slotKey: String) {
        val view =
            synchronized(lock) {
                val slot = slots.getOrPut(slotKey) { Slot() }
                if (slot.visible) return
                slot.visible = true
                val hiddenAt = slot.hiddenAtShowSequence
                val returnedFromCover = hiddenAt != null && showSequence > hiddenAt && !slot.backgroundSinceShown
                if (returnedFromCover) slot.lastEmitted = null
                slot.hiddenAtShowSequence = null
                slot.backgroundSinceShown = false
                showSequence += 1
                slot.takeEmission()
            }
        view?.let(emit)
    }

    fun onPageHidden(slotKey: String) {
        synchronized(lock) {
            val slot = slots[slotKey] ?: return
            if (!slot.visible) return
            slot.visible = false
            slot.hiddenAtShowSequence = showSequence
        }
    }

    fun onPageClosed(slotKey: String) {
        synchronized(lock) { slots.remove(slotKey) }
    }

    /** The app went to the background: pages that show again afterwards are not new entries. */
    fun onAppBackground() {
        synchronized(lock) { slots.values.forEach { it.backgroundSinceShown = true } }
    }

    /**
     * The app is in the foreground again. A page the platform never reported hidden stayed on
     * screen across the background; it starts clean, so a later cover/Back still counts.
     */
    fun onAppForeground() {
        synchronized(lock) { slots.values.forEach { if (it.visible) it.backgroundSinceShown = false } }
    }

    private fun Slot.takeEmission(): RaftProductPageView? {
        val current = route ?: return null
        if (current == lastEmitted) return null
        lastEmitted = current
        return current
    }
}

/**
 * Product analytics for one app process: the queue ([client]) plus the app-open and page-view
 * rules. Hosts report lifecycle and navigation; this decides what becomes an event.
 */
class RaftProductEventsRecorder(
    val client: RaftProductEventsClient,
    private val nowEpochMillis: () -> Long,
    foregroundGapMillis: Long = RaftAppOpenForegroundGapMillis,
) {
    private val appOpen = RaftAppOpenTracker(foregroundGapMillis)
    val pageViews = RaftPageViewTracker { view -> client.track(view.toEvent()) }
    private val lock = SynchronizedObject()
    private val taskThreadInstanceIds = LinkedHashSet<String>()

    fun onAppForeground(via: RaftAppOpenVia? = null) {
        pageViews.onAppForeground()
        val start = appOpen.onForeground(nowEpochMillis()) ?: return
        client.startNewSession()
        client.track(RaftProductEvent.AppOpened(RaftAppOpenedProperties(start = start, via = via)))
    }

    fun onAppBackground() {
        appOpen.onBackground(nowEpochMillis())
        pageViews.onAppBackground()
        client.flushNow()
    }

    fun navEntrySelected(destination: RaftNavDestination, surface: RaftNavSurface, wasActive: Boolean) {
        client.track(
            RaftProductEvent.NavEntrySelected(
                RaftNavEntrySelectedProperties(destination = destination, surface = surface, wasActive = wasActive)
            )
        )
    }

    /** The next thread page with [instanceId] opens a task (a task opens as a thread on phones). */
    fun markTaskThreadOpening(instanceId: String) {
        val key = instanceId.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            taskThreadInstanceIds += key
            while (taskThreadInstanceIds.size > 16) taskThreadInstanceIds.remove(taskThreadInstanceIds.first())
        }
    }

    fun consumeTaskThreadOpening(instanceId: String): Boolean {
        val key = instanceId.trim().takeIf { it.isNotEmpty() } ?: return false
        return synchronized(lock) { taskThreadInstanceIds.remove(key) }
    }
}
