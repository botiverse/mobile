package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore

/**
 * Agent Lounge v0 (web `slock#16248`, `packages/web/src/components/agent/agentConversationSideMemory.ts`):
 * remembers which of an agent's two paired conversations (its Lounge, or your private DM with it)
 * you last had open, per `(userId, serverId, agentId)`, kept in local preferences
 * (`raft:agent-conversation-side:v1:<userId>:<serverId>:<agentId>`, `"lounge"` | `"private"`).
 *
 * Never synced across devices. Tapping the agent's merged sidebar row in Home reopens the
 * remembered side: the private DM when `"private"` was last open and a DM row exists, else the
 * Lounge (also the first time, or when the stored value is missing/unreadable/storage throws).
 * Written whenever an agent's Lounge or DM is shown with the Lounge / Private avatar switcher menu
 * (however it was opened: Home row, avatar switcher, link, search, Activity, notification).
 */
internal const val AGENT_CONVERSATION_SIDE_STORAGE_PREFIX = "raft:agent-conversation-side:v1:"

private const val SIDE_VALUE_PRIVATE = "private"
private const val SIDE_VALUE_LOUNGE = "lounge"

internal fun agentConversationSideStorageKey(
    userId: String,
    serverId: String,
    agentId: String,
): String =
    "$AGENT_CONVERSATION_SIDE_STORAGE_PREFIX${encodeSideMemorySegment(userId)}:" +
        "${encodeSideMemorySegment(serverId)}:${encodeSideMemorySegment(agentId)}"

/** The side last open for [agentId]; [AgentLoungeConversationSide.Lounge] when nothing readable is kept. */
internal fun readAgentConversationSide(
    store: SharedPreferenceStore?,
    userId: String?,
    serverId: String?,
    agentId: String?,
): AgentLoungeConversationSide {
    val cleanUser = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return AgentLoungeConversationSide.Lounge
    val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return AgentLoungeConversationSide.Lounge
    val cleanAgent = agentId?.trim()?.takeIf { it.isNotEmpty() } ?: return AgentLoungeConversationSide.Lounge
    val raw = runCatching { store?.getString(agentConversationSideStorageKey(cleanUser, cleanServer, cleanAgent)) }.getOrNull()
    return if (raw == SIDE_VALUE_PRIVATE) AgentLoungeConversationSide.Private else AgentLoungeConversationSide.Lounge
}

/** Keeps [side] for [agentId]; no-op when any identity segment or [side] is missing, or storage fails. */
internal fun writeAgentConversationSide(
    store: SharedPreferenceStore?,
    userId: String?,
    serverId: String?,
    agentId: String?,
    side: AgentLoungeConversationSide?,
) {
    val cleanUser = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val cleanAgent = agentId?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val resolvedSide = side ?: return
    val wire =
        when (resolvedSide) {
            AgentLoungeConversationSide.Private -> SIDE_VALUE_PRIVATE
            AgentLoungeConversationSide.Lounge -> SIDE_VALUE_LOUNGE
        }
    runCatching { store?.putString(agentConversationSideStorageKey(cleanUser, cleanServer, cleanAgent), wire) }
}

/**
 * Which side tapping an eligible agent's merged sidebar row should open:
 * [AgentLoungeConversationSide.Private] when the user last had Private DM open for this agent AND
 * a direct message row exists; otherwise [AgentLoungeConversationSide.Lounge] (including first visit).
 */
internal fun resolveMergedAgentRowOpenSide(
    store: SharedPreferenceStore?,
    userId: String?,
    serverId: String?,
    agentId: String?,
    hasDirectMessage: Boolean,
): AgentLoungeConversationSide {
    if (!hasDirectMessage) return AgentLoungeConversationSide.Lounge
    return readAgentConversationSide(store = store, userId = userId, serverId = serverId, agentId = agentId)
}

internal fun defaultAgentConversationSideStore(): SharedPreferenceStore? =
    runCatching { raftSharedPreferenceStore() }.getOrNull()

private fun encodeSideMemorySegment(segment: String): String {
    val bytes = segment.encodeToByteArray()
    val out = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val unreserved =
            (c in 'A'.code..'Z'.code) ||
                (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) {
            out.append(c.toChar())
        } else {
            out.append('%')
            out.append(HEX_CHARS[c ushr 4])
            out.append(HEX_CHARS[c and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX_CHARS = "0123456789ABCDEF"
