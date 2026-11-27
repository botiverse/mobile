package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.isTaskConversation
import build.raft.app.data.showsUnread
import build.raft.app.thread.key

/*
 * Wide Activity (list and detail side by side) reopens, on arrival, the conversation opened last time
 * on this server, when it is still listed; without one the detail stays as it is. Nothing happens when
 * something is already open beside the list, or on a phone, where opening is a pushed page. The
 * automatic open is not a pick: it does not mark the row read.
 *
 * web ThreadsInbox / activityArrivalOpen.ts (slock #10785, and #10820, which dropped the top-row
 * fallback: "it never picks the top row", stdrc).
 */

/** The conversation opened last in Activity, per server (web localStorage `raft.activity.lastOpened.<serverId>`). */
internal class ActivityLastOpenedStore(private val store: SharedPreferenceStore) {
    fun read(serverId: String): String? =
        serverId.takeIf { it.isNotBlank() }?.let { store.getString(key(it)) }?.takeIf { it.isNotBlank() }

    fun write(serverId: String, itemKey: String) {
        if (serverId.isBlank() || itemKey.isBlank()) return
        store.putString(key(serverId), itemKey)
    }

    private fun key(serverId: String): String = "$KeyPrefix$serverId"

    companion object {
        const val KeyPrefix = "raft.activity.lastOpened."
    }
}

/** web `pickReopen`: the row opened last time, when it is still listed; otherwise none. */
internal fun activityArrivalReopenTarget(
    wide: Boolean,
    somethingOpen: Boolean,
    items: List<InboxItem>,
    lastOpenedKey: String?,
    liveItems: List<InboxItem>? = null,
): InboxItem? {
    if (!wide || somethingOpen || lastOpenedKey == null) return null
    val candidate = items.firstOrNull { it.key == lastOpenedKey } ?: return null
    // Web `useThreadsInboxOpen.reopen`: an Artifact is never auto-reopened on arrival (loading its
    // viewer advances its read position with no auto-read hold), while keeping its key as lastOpened
    // prevents reopening an older conversation instead.
    if (candidate.kind == build.raft.app.model.InboxItemKind.Object) return null
    // Web `useThreadsInboxOpen.reopen` (botiverse/slock #16717): a task conversation comes back as
    // left only when it has nothing unread (opening its task reads it with no auto-read hold). Check
    // the row's current live state in case a reply landed since arrival picked it; an unread or
    // marked-unread task stays closed while keeping its key as lastOpened so no older conversation
    // opens instead.
    if (candidate.kind == build.raft.app.model.InboxItemKind.Thread && candidate.isTaskConversation) {
        val fresh = liveItems?.firstOrNull { it.key == candidate.key } ?: candidate
        if (fresh.showsUnread) return null
    }
    return candidate
}

/**
 * Once per visit to Activity, after the list first loads (web `activityVisit.claimArrival`). The
 * Activity tab stays composed while hidden, so a visit is an active stretch: leaving ends it.
 */
internal class ActivityArrivalVisit {
    private var claimed = false

    /** True exactly once per visit, the first time the list is [loaded]. */
    fun claim(active: Boolean, loaded: Boolean): Boolean {
        if (!active) {
            claimed = false
            return false
        }
        if (claimed || !loaded) return false
        claimed = true
        return true
    }

    /** The visit ended (the surface went inactive): the next active, loaded list claims again. */
    fun leave() {
        claimed = false
    }
}

/**
 * The Activity surface's visits, one per server, held by the surface above its list (xxchan, mobile
 * #193). The wide list leaves composition while it is collapsed and a conversation is open
 * ([RaftAdaptiveListDetailSplit]), and comes back when Back closes the conversation: a visit
 * remembered inside the list started afresh there, claimed the arrival again and reopened the
 * conversation Back had just closed, so Back could never leave it.
 */
internal class ActivityArrivalVisits {
    private val byServer = mutableMapOf<String, ActivityArrivalVisit>()

    /** [serverId]'s visit (the list's activity server, as the list keyed it before). */
    fun of(serverId: String): ActivityArrivalVisit = byServer.getOrPut(serverId) { ActivityArrivalVisit() }

    /** The surface went inactive: every visit ended, also while the list is not composed. */
    fun leave() {
        byServer.values.forEach(ActivityArrivalVisit::leave)
    }
}

/** [ActivityArrivalVisits] for a surface; they end whenever [active] turns false. */
@Composable
internal fun rememberActivityArrivalVisits(active: Boolean): ActivityArrivalVisits {
    val visits = remember { ActivityArrivalVisits() }
    LaunchedEffect(visits, active) {
        if (!active) visits.leave()
    }
    return visits
}

/** Claims [visit]'s arrival once the list is [active] and [loaded], then runs [reopen] (at most once per visit). */
@Composable
internal fun ActivityArrivalReopenEffect(
    visit: ActivityArrivalVisit,
    active: Boolean,
    loaded: Boolean,
    reopen: () -> Unit,
) {
    val latestReopen by rememberUpdatedState(reopen)
    LaunchedEffect(visit, active, loaded) {
        if (visit.claim(active = active, loaded = loaded)) latestReopen()
    }
}

/**
 * web: "pinning or unpinning while Pinned is shown reloads it". The first pins seen are the baseline;
 * a later change reloads when the Pinned filter is selected. Pins not known yet (null) change nothing.
 */
internal class ActivityPinnedScopeReloadEdge {
    private var seen: List<SettingsServerSidebarPinnedItem>? = null

    fun onPins(pins: List<SettingsServerSidebarPinnedItem>?, selectedFilter: InboxFilter): Boolean {
        if (pins == null) return false
        val previous = seen
        seen = pins
        return previous != null && previous != pins && selectedFilter == InboxFilter.Pinned
    }
}

/**
 * web: "Saved lists the threads followed through a save: a save or unsave the server accepted while it
 * is shown reloads it" (savedStore `writeRevision`). The first revision seen is the baseline; a later
 * one reloads when the Saved filter is selected.
 */
internal class ActivitySavedScopeReloadEdge {
    private var seen: Long? = null

    fun onSavedWrite(revision: Long, selectedFilter: InboxFilter): Boolean {
        val previous = seen
        seen = revision
        return previous != null && previous != revision && selectedFilter == InboxFilter.Saved
    }
}
