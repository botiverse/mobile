package build.raft.app.markdown

/**
 * The address family of an agent's Lounge (botiverse/slock `packages/shared/src/raftRefs.ts`, #16279):
 * `lounge:@agent` names the Lounge of the agent currently called `agent`, `lounge:@agent:<short id>`
 * a thread in it, `lounge:@agent:#7` its task 7, and `lounge:@agent msg=<id>` /
 * `lounge:@agent:<short id> msg=<id>` a message in it. Which agent a name means is
 * [resolveRaftLoungeRefAgent]'s rule; the Lounge is still an ordinary channel.
 */
internal sealed interface RaftLoungeRef {
    /** The agent's name as written. */
    val agentName: String

    /** Web `{ kind: "lounge" }`. */
    data class Lounge(override val agentName: String) : RaftLoungeRef

    /** Web `{ kind: "lounge-thread" }`. */
    data class Thread(override val agentName: String, val threadShortId: String) : RaftLoungeRef

    /** Web `{ kind: "task", loungeAgentName }`. */
    data class Task(override val agentName: String, val taskNumber: Int) : RaftLoungeRef

    /** Web `{ kind: "lounge-message" }`: [threadParentShortId] is the thread the message is in. */
    data class Message(
        override val agentName: String,
        val messageId: String,
        val threadParentShortId: String? = null,
    ) : RaftLoungeRef
}

/** Web `RAFT_LOUNGE_TARGET_PREFIX`. */
internal const val RaftLoungeRefPrefix = "lounge:@"

/** Web `formatRaftLoungeTaskRef`: `lounge:@agent:#7`. */
internal fun formatRaftLoungeTaskRef(agentName: String, taskNumber: Int): String =
    "$RaftLoungeRefPrefix$agentName:#$taskNumber"

/** Web `formatRaftRefTarget` for the Lounge kinds: the canonical target, one space before `msg=`. */
internal fun RaftLoungeRef.format(): String =
    when (this) {
        is RaftLoungeRef.Lounge -> "$RaftLoungeRefPrefix$agentName"
        is RaftLoungeRef.Thread -> "$RaftLoungeRefPrefix$agentName:$threadShortId"
        is RaftLoungeRef.Task -> formatRaftLoungeTaskRef(agentName, taskNumber)
        is RaftLoungeRef.Message ->
            if (threadParentShortId != null) {
                "$RaftLoungeRefPrefix$agentName:$threadParentShortId msg=$messageId"
            } else {
                "$RaftLoungeRefPrefix$agentName msg=$messageId"
            }
    }

/**
 * Web `parseRaftRefTarget` for the Lounge kinds (`LOUNGE_RE`, `LOUNGE_THREAD_RE`, `LOUNGE_TASK_RE`,
 * `LOUNGE_MESSAGE_RE`): one whole target, trimmed, its prefix in any case. The agent's name is
 * letters, digits, `_` and `-`. Null for anything else, a malformed one included (`lounge:@ops.x`,
 * `lounge:@ops~agent`, `lounge:@ops:xyz`, `lounge:@ops:#0`, `lounge:@ops:#7:#8`).
 */
internal fun parseRaftLoungeRef(raw: String): RaftLoungeRef? {
    val target = raw.trim()
    if (!target.startsWith(RaftLoungeRefPrefix, ignoreCase = true)) return null
    val nameStart = RaftLoungeRefPrefix.length
    var nameEnd = nameStart
    while (nameEnd < target.length && target[nameEnd].isRaftLoungeAgentNamePart()) nameEnd++
    if (nameEnd == nameStart) return null
    val agentName = target.substring(nameStart, nameEnd)
    if (nameEnd == target.length) return RaftLoungeRef.Lounge(agentName)
    if (target.startsWith(RaftLoungeTaskSeparator, nameEnd)) {
        val taskNumber = target.substring(nameEnd + RaftLoungeTaskSeparator.length).toRaftLoungeTaskNumberOrNull() ?: return null
        return RaftLoungeRef.Task(agentName, taskNumber)
    }
    var cursor = nameEnd
    var threadShortId: String? = null
    if (target[cursor] == ':') {
        val shortStart = cursor + 1
        var shortEnd = shortStart
        while (shortEnd < target.length && target[shortEnd].isRaftLoungeShortIdPart()) shortEnd++
        if (shortEnd - shortStart !in 6..8) return null
        val shortId = target.substring(shortStart, shortEnd)
        if (shortEnd == target.length) return RaftLoungeRef.Thread(agentName, shortId)
        threadShortId = shortId
        cursor = shortEnd
    }
    // `\s+msg=<id>`: a message, top-level or in the thread above.
    if (!target[cursor].isWhitespace()) return null
    while (cursor < target.length && target[cursor].isWhitespace()) cursor++
    if (!target.startsWith(RaftLoungeMessageKey, cursor, ignoreCase = true)) return null
    val messageId = target.substring(cursor + RaftLoungeMessageKey.length)
    if (!messageId.isRaftLoungeMessageId()) return null
    return RaftLoungeRef.Message(agentName, messageId, threadShortId)
}

/**
 * The agent a conversation label `lounge:@agent` (the Lounge itself, nothing in it) names, as the
 * app's conversation resolver reads it; null for any other label (a channel or DM peer name).
 */
internal fun raftLoungeConversationAgentName(label: String): String? =
    (parseRaftLoungeRef(label) as? RaftLoungeRef.Lounge)?.agentName

/** An agent a `lounge:@<name>` ref may name: its id, its current name (handle) and display name. */
internal data class RaftLoungeRefAgent(
    val id: String,
    val name: String,
    val displayName: String? = null,
)

/**
 * The agent `lounge:@<agentName>` names among [agents] (this server's live agents), by the server's
 * rule (slock `agentLoungeService.ts` `resolveLoungeChannelIdByAgentName`): the one whose current name
 * is exactly [agentName]; else the only one whose name matches it ignoring case. Null when none does
 * or the name is ambiguous, so after a rename the old name names nothing (never the wrong Lounge).
 */
internal fun resolveRaftLoungeRefAgent(agentName: String, agents: Iterable<RaftLoungeRefAgent>): RaftLoungeRefAgent? {
    if (agentName.isEmpty()) return null
    val matches = agents.filter { it.name.equals(agentName, ignoreCase = true) }.distinctBy { it.id }
    matches.singleOrNull { it.name == agentName }?.let { return it }
    return matches.singleOrNull()
}

private const val RaftLoungeTaskSeparator = ":#"
private const val RaftLoungeMessageKey = "msg="

/** Web `RAFT_REF_USER_NAME_PATTERN`: `[\p{L}\p{N}_-]`. */
private fun Char.isRaftLoungeAgentNamePart(): Boolean = isLetterOrDigit() || this == '_' || this == '-'

/** Web `RAFT_REF_THREAD_SHORT_ID_PATTERN` under its `i` flag. */
private fun Char.isRaftLoungeShortIdPart(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Web `RAFT_REF_TASK_NUMBER_PATTERN`: `[1-9]\d*`. */
private fun String.toRaftLoungeTaskNumberOrNull(): Int? {
    if (isEmpty() || first() !in '1'..'9' || any { it !in '0'..'9' }) return null
    return toIntOrNull()
}

/** Web `RAFT_REF_MESSAGE_ID_PATTERN`: `[A-Za-z0-9][A-Za-z0-9-]{1,63}`. */
private fun String.isRaftLoungeMessageId(): Boolean =
    length in 2..64 && first().isRaftLoungeAlphanumeric() && all { it.isRaftLoungeAlphanumeric() || it == '-' }

private fun Char.isRaftLoungeAlphanumeric(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
