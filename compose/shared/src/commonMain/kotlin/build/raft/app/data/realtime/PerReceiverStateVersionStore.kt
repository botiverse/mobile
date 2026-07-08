package build.raft.app.data.realtime

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId

enum class PerReceiverStateScope(val wireName: String) {
    ReadState("read_state"),
    NotificationPrefs("notification_prefs"),
    ServerOrder("server_order")
}

data class PerReceiverStateVersionDecision(
    val shouldApply: Boolean,
    val currentVersion: Long,
    val incomingVersion: Long
)

class PerReceiverStateVersionStore(
    private val store: SharedPreferenceStore
) {
    fun readVersion(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope
    ): Long =
        store.getString(key(serverId, memberId, scope))
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L

    fun decide(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope,
        incomingVersion: Long
    ): PerReceiverStateVersionDecision {
        val currentVersion = readVersion(serverId, memberId, scope)
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
        incomingVersion: Long
    ): PerReceiverStateVersionDecision {
        val decision = decide(serverId, memberId, scope, incomingVersion)
        if (decision.shouldApply) {
            store.putString(key(serverId, memberId, scope), incomingVersion.toString())
        }
        return decision
    }

    private fun key(
        serverId: ServerId,
        memberId: MemberId,
        scope: PerReceiverStateScope
    ): String =
        "realtime.perReceiverStateVersion." +
            "${keySegment(serverId.value)}." +
            "${keySegment(memberId.value)}." +
            scope.wireName

    private fun keySegment(value: String): String =
        value
            .replace("%", "%25")
            .replace(".", "%2E")
}
