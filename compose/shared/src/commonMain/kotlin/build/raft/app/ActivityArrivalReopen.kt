package build.raft.app

import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedPreferenceStore

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
): InboxItem? {
    if (!wide || somethingOpen || lastOpenedKey == null) return null
    return items.firstOrNull { it.key == lastOpenedKey }
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
