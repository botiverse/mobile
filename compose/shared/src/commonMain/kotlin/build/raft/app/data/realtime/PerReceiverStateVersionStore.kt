package build.raft.app.data.realtime

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId

enum class PerReceiverStateScope(val wireName: String) {
    ReadState("read_state"),
    NotificationPrefs("notification_prefs"),
    ServerOrder("server_order"),
    Pinned("pinned")
}

data class PerReceiverStateVersionDecision(
    val shouldApply: Boolean,
    val currentVersion: Long,
    val incomingVersion: Long
)

/**
 * Version-gate for RFC 041 per-receiver state pushes.
 *
 * Granularity follows the server's version columns (PR #4175):
 * `read_state` and `notification_prefs` versions live on the per-(user,
 * channel) cursor row (`user_channel_read_cursors` PK (user_id, channel_id)),
 * so callers MUST pass the [scopeId] those versions are counted against.
 * `server_order` and `pinned` are one blob per (server, member), so they
 * gate at family level with no [scopeId].
 */
class PerReceiverStateVersionStore(
    private val store: SharedPreferenceStore
) {
    fun readVersion(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        scopeId: ChannelId? = null
    ): Long =
        store.getString(key(serverId, memberId, scope, scopeId))
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L

    fun decide(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        incomingVersion: Long,
        scopeId: ChannelId? = null
    ): PerReceiverStateVersionDecision {
        val currentVersion = readVersion(serverId, memberId, scope, scopeId)
        return PerReceiverStateVersionDecision(
            shouldApply = incomingVersion > currentVersion,
            currentVersion = currentVersion,
            incomingVersion = incomingVersion
        )
    }

    fun recordApplied(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        incomingVersion: Long,
        scopeId: ChannelId? = null
    ): PerReceiverStateVersionDecision {
        val decision = decide(serverId, memberId, scope, incomingVersion, scopeId)
        if (decision.shouldApply) {
            store.putString(key(serverId, memberId, scope, scopeId), incomingVersion.toString())
        }
        return decision
    }

    /** Persist a version that the domain reducer has already accepted. */
    internal fun writeAcceptedVersion(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        acceptedVersion: Long,
        scopeId: ChannelId? = null
    ) {
        store.putString(key(serverId, memberId, scope, scopeId), acceptedVersion.toString())
    }

    private fun key(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        scopeId: ChannelId?
    ): String =
        "realtime.perReceiverStateVersion." +
            "${keySegment(serverId.value)}." +
            "${keySegment(memberId.value)}." +
            scope.wireName +
            (scopeId?.let { ".${keySegment(it.value)}" } ?: "")

    private fun keySegment(value: String): String =
        value
            .replace("%", "%25")
            .replace(".", "%2E")
}
