package build.raft.app.data

import build.raft.app.model.ServerId

/**
 * #81: typed projection for the selected-server app badge. NOT a new
 * server endpoint — derived entirely from existing
 * [SharedHomeSnapshot.unreadTotal] which is computed in
 * `HomeParser.kt:102-103` as
 *
 *     unreadTotal = serverUnreadTotal ?: channelUnreadTotal
 *
 * with realtime projection applied by [SharedHomeStore]. The source
 * provenance is recorded explicitly so that future React / product changes
 * to the badge source can be adapted in one place without mis-aligning the
 * host bridge contract.
 *
 * Platform host receives this through [build.raft.app.base.BridgeModule.appBadge]
 * and MUST display only [count]. [source] is for diagnostics / logging
 * only; the host MUST NOT branch on `SERVER_UNREAD_SUMMARY` vs
 * `CHANNEL_DM_FALLBACK` to decide which UI to show.
 */
data class AppBadgeCount(
    /**
     * Final badge value to display. `0` is a valid value (no badge shown
     * but [source] may still be `SERVER_UNREAD_SUMMARY` — meaning the
     * server has 0 unread items right now, not that the badge is unknown).
     */
    val count: Int,
    /**
     * Where the [count] came from. `UNKNOWN` is the pre-login / pre-load
     * state; `DISABLED` is the cleared state (server switch in flight,
     * logout, etc.) where the host should not show a badge.
     */
    val source: AppBadgeSource,
    /**
     * The server this count applies to. `null` in `DISABLED` /
     * `UNKNOWN` states. Used to detect "user switched servers since this
     * count was computed" without re-fetching.
     */
    val selectedServerId: ServerId?,
    /**
     * Wall-clock millis when [count] was last successfully derived. The
     * `Long` (not `Instant`) is intentional (the datetime klib
     * stability rule adopted in `d56ed07d`); UI layer formats via
     * [build.raft.app.model.RaftTime].
     *
     * Refresh-failure semantics: this field MUST NOT advance on a failed
     * fetch. Either keep the previous value or flip [isStale] to `true`
     * (we use the [isStale] flag). Tests assert this.
     */
    val lastUpdated: Long,
    /**
     * `true` when the most recent fetch for this count failed and the
     * value is held over from the previous successful fetch. The host
     * MAY still display [count] (badge not flashing is better than badge
     * oscillating), but a future UI layer could surface "stale" as a
     * visual hint.
     */
    val isStale: Boolean = false
) {
    companion object {
        val UNKNOWN: AppBadgeCount = AppBadgeCount(
            count = 0,
            source = AppBadgeSource.UNKNOWN,
            selectedServerId = null,
            lastUpdated = 0L,
            isStale = false
        )

        val DISABLED: AppBadgeCount = AppBadgeCount(
            count = 0,
            source = AppBadgeSource.DISABLED,
            selectedServerId = null,
            lastUpdated = 0L,
            isStale = false
        )
    }
}

enum class AppBadgeSource(val wire: String) {
    /** Source = process-owned Activity unread summary for the selected server. */
    ACTIVITY_UNREAD_SUMMARY("activity_unread_summary"),

    /**
     * Source = `servers/unread-summary` `ServerUnreadSummaryDto.unreadCount`
     * (the primary React-aligned server-level unread endpoint). This is
     * the canonical source; when present, we use it directly.
     */
    SERVER_UNREAD_SUMMARY("server_unread_summary"),

    /**
     * Fallback when the server unread summary is missing or empty for
     * the active server. We then sum channel + DM unread counts. This
     * matches `HomeParser.kt:97-103` behavior.
     */
    CHANNEL_DM_FALLBACK("channel_dm_fallback"),

    /**
     * Cleared state. Server is in-flight (server switch) or user just
     * logged out. Host SHOULD NOT show a badge. [count] is `0` and
     * [selectedServerId] is `null`.
     */
    DISABLED("disabled"),

    /**
     * Initial state before any home data has been loaded. Host SHOULD NOT
     * show a badge (the first load will publish a real value).
     */
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): AppBadgeSource = when (value) {
            "activity_unread_summary" -> ACTIVITY_UNREAD_SUMMARY
            "server_unread_summary" -> SERVER_UNREAD_SUMMARY
            "channel_dm_fallback" -> CHANNEL_DM_FALLBACK
            "disabled" -> DISABLED
            else -> UNKNOWN
        }
    }
}
