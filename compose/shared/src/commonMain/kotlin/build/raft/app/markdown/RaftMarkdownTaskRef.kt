package build.raft.app.markdown

import build.raft.app.model.TaskStatus

/**
 * A task named in message text (Web `RaftRefTarget` `kind: "task"`, `raftRefs.ts`):
 * the bare `#7` (a task of the conversation being read), `#channel:#7` (task 7 of
 * `#channel`) or `dm:@peer:#7` / `dm:@peer~agent:#7` (task 7 of the DM with `peer`).
 * At most one of [channelName] / [directMessagePeerName] is set.
 */
internal data class RaftMarkdownTaskRef(
    val taskNumber: Int,
    val channelName: String? = null,
    /** The DM peer as written, with its optional `~agent` / `~human` kind. */
    val directMessagePeerName: String? = null,
) {
    val qualified: Boolean
        get() = channelName != null || directMessagePeerName != null
}

private const val RaftMarkdownTaskRefSeparator = ":#"

/**
 * Parses a task-ref token as the Markdown dialect claims it (`RaftMarkdownDialect`): `#7`,
 * `#channel:#7`, `dm:@peer:#7`. Anything else (a channel, a thread ref, a malformed number such as
 * `#07`) is null.
 */
internal fun parseRaftMarkdownTaskRefLabel(label: String): RaftMarkdownTaskRef? {
    val text = label.trim()
    val separator = text.lastIndexOf(RaftMarkdownTaskRefSeparator)
    if (separator < 0) {
        if (!text.startsWith('#')) return null
        return text.drop(1).toRaftTaskNumberOrNull()?.let { RaftMarkdownTaskRef(it) }
    }
    val taskNumber = text.substring(separator + RaftMarkdownTaskRefSeparator.length).toRaftTaskNumberOrNull() ?: return null
    val conversation = text.substring(0, separator)
    return when {
        conversation.startsWith("dm:@", ignoreCase = true) -> {
            val peer = conversation.substring(4)
            val peerRef = parseRaftDirectMessagePeerRef(peer) ?: return null
            val name = peerRef.peerName
            if (!name.first().isRaftTaskRefAsciiWord() || name.any { !it.isRaftTaskRefAsciiWord() && it != '.' && it != '-' }) {
                return null
            }
            RaftMarkdownTaskRef(taskNumber, directMessagePeerName = peer)
        }
        conversation.startsWith('#') -> {
            val name = conversation.substring(1)
            if (name.isEmpty() || !name.first().isLetter() || name.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) {
                return null
            }
            RaftMarkdownTaskRef(taskNumber, channelName = name)
        }
        else -> null
    }
}

/** `[1-9]\d*` (Web `RAFT_REF_TASK_NUMBER_PATTERN`). */
private fun String.toRaftTaskNumberOrNull(): Int? {
    if (isEmpty() || first() !in '1'..'9' || any { it !in '0'..'9' }) return null
    return toIntOrNull()?.takeIf { it > 0 }
}

private fun Char.isRaftTaskRefAsciiWord(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

/**
 * Key of a conversation-qualified task in [LocalRaftMarkdownTaskRefStatuses]: the channel name or
 * DM peer name (case-insensitive, peer kind dropped) and the task number.
 */
internal fun raftMarkdownTaskRefStatusKey(
    taskNumber: Int,
    channelName: String? = null,
    directMessagePeerName: String? = null,
): String? {
    channelName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
        return "#${name.lowercase()}$RaftMarkdownTaskRefSeparator$taskNumber"
    }
    directMessagePeerName?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        val peer = parseRaftDirectMessagePeerRef(raw)?.peerName ?: return null
        return "dm:@${peer.lowercase()}$RaftMarkdownTaskRefSeparator$taskNumber"
    }
    return null
}

/**
 * The status a task-ref chip shows. A bare `#7` reads the surface's by-number map (the conversation
 * being read); a qualified `#proj:#7` / `dm:@peer:#7` reads only [byRef], since the number is unique
 * only inside its own conversation (Web `taskRefKey(channelId, number)`). No match → null: the chip
 * claims no status.
 */
internal fun raftMarkdownTaskChipStatus(
    action: RaftRichTextAction,
    byNumber: Map<Int, TaskStatus>,
    byRef: Map<String, TaskStatus>,
): TaskStatus? {
    if (!action.label.contains(RaftMarkdownTaskRefSeparator)) return taskNumberFromRefLabel(action.label)?.let(byNumber::get)
    val ref = parseRaftMarkdownTaskRefLabel(action.label)?.takeIf { it.qualified } ?: return null
    val key =
        raftMarkdownTaskRefStatusKey(
            taskNumber = ref.taskNumber,
            channelName = action.channelName ?: ref.channelName,
            directMessagePeerName = action.directMessagePeerName ?: ref.directMessagePeerName,
        ) ?: return null
    return byRef[key]
}
