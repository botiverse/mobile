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

/** What the host saw open the app (Android launch intent, iOS launch / notification response). */
enum class RaftAppOpenSignal {
    /** The launcher icon. */
    Launcher,
    /** A tap on one of the app's push notifications. */
    Notification,
    /** Anything else (a link, a share, a restore from recents): `via` is omitted. */
    Unknown,
}

/**
 * `app_opened.via`: the latest open signal the host reported since the app last went to the
 * background, taken by the next foreground. No signal means unknown, and `via` is omitted.
 */
class RaftAppOpenViaTracker {
    private val lock = SynchronizedObject()
    private var signal: RaftAppOpenSignal? = null

    /**
     * A known signal replaces the previous one; [RaftAppOpenSignal.Unknown] does not replace a known
     * one (Android: a page the opened activity forwards to does not hide the launcher open).
     */
    fun onSignal(signal: RaftAppOpenSignal) {
        synchronized(lock) {
            if (signal == RaftAppOpenSignal.Unknown && this.signal != null) return
            this.signal = signal
        }
    }

    /** [signal] unless the host already reported one for this open (iOS plain launch = launcher). */
    fun onDefaultSignal(signal: RaftAppOpenSignal) {
        synchronized(lock) { if (this.signal == null) this.signal = signal }
    }

    fun take(): RaftAppOpenVia? =
        synchronized(lock) {
            val taken = signal
            signal = null
            taken?.toVia()
        }

    fun onBackground() {
        synchronized(lock) { signal = null }
    }
}

internal fun RaftAppOpenSignal.toVia(): RaftAppOpenVia? =
    when (this) {
        RaftAppOpenSignal.Launcher -> RaftAppOpenVia.Launcher
        RaftAppOpenSignal.Notification -> RaftAppOpenVia.Notification
        RaftAppOpenSignal.Unknown -> null
    }

/**
 * One `page_viewed` per page entry. A slot is one mounted page (a pager id, or a shell-owned inline
 * pane); hosts report its route and its visibility (page appear/disappear).
 *
 * - A slot reports its route once per entry: recomposition, a repeated appear, or the same route
 *   reported again are not entries.
 * - Coming back to a slot that was covered by another page (Back from a pushed page, switching
 *   back to a tab) is an entry: web counts route changes the same way. Only an entry covers: a
 *   page that merely shows again (the shell around a tab after a pause) does not.
 * - A slot that appears again after the app was in the background is not an entry, nor is one
 *   that only paused (a system dialog) without another page being entered in between.
 * - A slot with a visit key that remounts (closed, then a new slot with the same key and route,
 *   with no page entered in between) continues the closed visit: e.g. the shell's Home tab page
 *   rebuilt while the signed-in shell settles on a cold start is not a second Home entry.
 */
class RaftPageViewTracker(
    private val emit: (RaftProductPageView) -> Unit,
) {
    private class Slot {
        var route: RaftProductPageView? = null
        var visitKey: String? = null
        var visible = false
        var shownBefore = false
        var lastEmitted: RaftProductPageView? = null
        var hiddenAtEntrySequence: Long? = null
        var backgroundSinceShown = false
    }

    /** The visit of a closed slot, which a remount of the same page continues. */
    private class ClosedVisit(val visitKey: String, val view: RaftProductPageView, val entrySequence: Long)

    private val lock = SynchronizedObject()
    private val slots = HashMap<String, Slot>()
    private var entrySequence = 0L
    private var closedVisit: ClosedVisit? = null

    /**
     * [visitKey] names the place the slot shows (stable across remounts of the same page, unique
     * per place), or null when a new slot is always a new visit.
     */
    fun onPageRoute(slotKey: String, route: RaftProductPageView?, visitKey: String? = null) {
        val view =
            synchronized(lock) {
                val slot = slots.getOrPut(slotKey) { Slot() }
                slot.route = route
                if (visitKey != null) slot.visitKey = visitKey
                if (slot.visible) slot.takeEntry() else null
            }
        view?.let(emit)
    }

    fun onPageShown(slotKey: String) {
        val view =
            synchronized(lock) {
                val slot = slots.getOrPut(slotKey) { Slot() }
                if (slot.visible) return
                slot.visible = true
                val firstShow = !slot.shownBefore
                slot.shownBefore = true
                val hiddenAt = slot.hiddenAtEntrySequence
                val returnedFromCover = hiddenAt != null && entrySequence > hiddenAt && !slot.backgroundSinceShown
                slot.hiddenAtEntrySequence = null
                slot.backgroundSinceShown = false
                when {
                    firstShow && slot.continuesClosedVisit() -> {
                        slot.lastEmitted = slot.route
                        null
                    }
                    firstShow || returnedFromCover -> {
                        if (returnedFromCover) slot.lastEmitted = null
                        entrySequence += 1
                        slot.takeEmission()
                    }
                    // Shown again without another page entered in between: a resume, not an entry
                    // (unless its route changed while hidden).
                    else -> slot.takeEntry()
                }
            }
        view?.let(emit)
    }

    fun onPageHidden(slotKey: String) {
        synchronized(lock) {
            val slot = slots[slotKey] ?: return
            if (!slot.visible) return
            slot.visible = false
            slot.hiddenAtEntrySequence = entrySequence
        }
    }

    fun onPageClosed(slotKey: String) {
        synchronized(lock) {
            val slot = slots.remove(slotKey) ?: return
            val visitKey = slot.visitKey
            val view = slot.lastEmitted
            // Only a slot still on its visit (not covered by a later entry) can be continued.
            val onVisit = slot.visible || slot.hiddenAtEntrySequence == entrySequence
            if (visitKey != null && view != null && onVisit) {
                closedVisit = ClosedVisit(visitKey, view, entrySequence)
            }
        }
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

    private fun Slot.continuesClosedVisit(): Boolean {
        val closed = closedVisit ?: return false
        val key = visitKey ?: return false
        if (closed.visitKey != key || closed.view != route || closed.entrySequence != entrySequence) return false
        closedVisit = null
        return true
    }

    /** A route newly reported on a shown slot: an entry when it differs from the last one. */
    private fun Slot.takeEntry(): RaftProductPageView? =
        takeEmission()?.also { entrySequence += 1 }

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
    private val openVia = RaftAppOpenViaTracker()

    /** How the app is being opened, reported before the foreground it belongs to. */
    fun onAppOpenSignal(signal: RaftAppOpenSignal) {
        openVia.onSignal(signal)
    }

    /** See [RaftAppOpenViaTracker.onDefaultSignal]. */
    fun onDefaultAppOpenSignal(signal: RaftAppOpenSignal) {
        openVia.onDefaultSignal(signal)
    }

    /** [via] overrides the reported open signal (tests, hosts that know better). */
    fun onAppForeground(via: RaftAppOpenVia? = null) {
        pageViews.onAppForeground()
        val signalled = openVia.take()
        val start = appOpen.onForeground(nowEpochMillis()) ?: return
        client.startNewSession()
        client.track(RaftProductEvent.AppOpened(RaftAppOpenedProperties(start = start, via = via ?: signalled)))
    }

    fun onAppBackground() {
        openVia.onBackground()
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

    fun channelTabSelected(tab: RaftChannelTab, wasActive: Boolean) {
        client.track(
            RaftProductEvent.ChannelTabSelected(
                RaftChannelTabSelectedProperties(tab = tab, surface = RaftChannelTabSurface.HeaderTabs, wasActive = wasActive)
            )
        )
    }

    /** The header pager settled on [tab] after a user swipe: always a different tab, so `was_active` is false. */
    fun channelTabSwiped(tab: RaftChannelTab) {
        client.track(
            RaftProductEvent.ChannelTabSelected(
                RaftChannelTabSelectedProperties(tab = tab, surface = RaftChannelTabSurface.HeaderSwipe, wasActive = false)
            )
        )
    }

    /** One long-press message menu action; [inThread] is true for a thread reply, false for any other message (including a thread's parent). */
    fun messageActionSelected(action: RaftMessageAction, inThread: Boolean) {
        client.track(
            RaftProductEvent.MessageActionSelected(
                RaftMessageActionSelectedProperties(
                    action = action,
                    surface = RaftMessageActionSurface.LongPress,
                    inThread = inThread,
                )
            )
        )
    }

    fun activityItemOpen(itemKind: RaftActivityItemKind) {
        client.track(RaftProductEvent.ActivityItemOpen(RaftActivityItemOpenProperties(itemKind)))
    }

    fun activityMark(action: RaftActivityMarkAction) {
        client.track(RaftProductEvent.ActivityMark(RaftActivityMarkProperties(action)))
    }

    fun agentCreateOpened(entry: RaftAgentCreateEntry) {
        client.track(RaftProductEvent.AgentCreateOpened(RaftAgentCreateOpenedProperties(entry)))
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
