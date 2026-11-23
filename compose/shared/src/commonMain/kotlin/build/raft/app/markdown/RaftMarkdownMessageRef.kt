package build.raft.app.markdown

/**
 * A message named in message text (Web `RaftRefTarget` `kind: "message"` / `"dm-message"` /
 * `"lounge-message"`, `raftRefs.ts`): `#channel msg=ID`, `#channel:short msg=ID`, `dm:@peer msg=ID`,
 * `dm:@peer:short msg=ID`, `lounge:@agent msg=ID` or `lounge:@agent:short msg=ID`. Exactly one of
 * [channelName] / [directMessagePeerName] / [loungeAgentName] is set; [threadShortId] is the thread
 * the message is in, and [messageId] may be a short id or a full uuid.
 */
internal data class RaftMarkdownMessageRef(
    val messageId: String,
    val threadShortId: String? = null,
    val channelName: String? = null,
    /** The DM peer as written, with its optional `~agent` / `~human` kind. */
    val directMessagePeerName: String? = null,
    /** The agent whose Lounge the message is in, as written (slock #16279). */
    val loungeAgentName: String? = null,
) {
    /** The conversation as the app's conversation resolver reads it: a channel or DM peer name, or `lounge:@agent`. */
    val conversationLabel: String
        get() = channelName ?: directMessagePeerName ?: loungeAgentName?.let { "$RaftLoungeRefPrefix$it" }.orEmpty()

    /** The target with a single space before `msg=` (Web `formatRaftRefTarget`): what the chip shows. */
    val label: String
        get() =
            buildString {
                when {
                    channelName != null -> append('#').append(channelName)
                    loungeAgentName != null -> append(RaftLoungeRefPrefix).append(loungeAgentName)
                    else -> append("dm:@").append(directMessagePeerName)
                }
                if (threadShortId != null) append(':').append(threadShortId)
                append(" msg=").append(messageId)
            }
}

private const val RaftMarkdownMessageRefKey = "msg="
private const val RaftMarkdownDirectMessagePrefix = "dm:@"

/**
 * Parses a message-ref token as the Markdown dialect claims it (`RaftMarkdownDialect`). Anything
 * else (a channel, a thread ref, an invalid message id) is null.
 */
internal fun parseRaftMarkdownMessageRefLabel(label: String): RaftMarkdownMessageRef? {
    val text = label.trim()
    if (text.startsWith(RaftLoungeRefPrefix, ignoreCase = true)) {
        val lounge = parseRaftLoungeRef(text) as? RaftLoungeRef.Message ?: return null
        return RaftMarkdownMessageRef(lounge.messageId, lounge.threadParentShortId, loungeAgentName = lounge.agentName)
    }
    val keyIndex = text.lastIndexOf(RaftMarkdownMessageRefKey, ignoreCase = true)
    if (keyIndex <= 0 || !text[keyIndex - 1].isWhitespace()) return null
    val messageId = text.substring(keyIndex + RaftMarkdownMessageRefKey.length)
    if (!messageId.isRaftMessageRefId()) return null
    val target = text.substring(0, keyIndex).trimEnd()
    val direct = target.startsWith(RaftMarkdownDirectMessagePrefix, ignoreCase = true)
    if (!direct && !target.startsWith('#')) return null
    val conversationAndThread = target.substring(if (direct) RaftMarkdownDirectMessagePrefix.length else 1)
    val conversation = conversationAndThread.substringBefore(':')
    val threadShortId = conversationAndThread.substringAfter(':', missingDelimiterValue = "").takeIf { ':' in conversationAndThread }
    if (threadShortId != null && !threadShortId.isRaftMessageRefThreadShortId()) return null
    return if (direct) {
        val peerName = parseRaftDirectMessagePeerRef(conversation)?.peerName ?: return null
        if (peerName.isEmpty() || !peerName.first().isRaftMessageRefAsciiWord()) return null
        if (!peerName.all { it.isRaftMessageRefAsciiWord() || it == '.' || it == '-' }) return null
        RaftMarkdownMessageRef(messageId, threadShortId, directMessagePeerName = conversation)
    } else {
        if (conversation.isEmpty() || !conversation.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return null
        RaftMarkdownMessageRef(messageId, threadShortId, channelName = conversation)
    }
}

/** Web `RAFT_REF_MESSAGE_ID_PATTERN`: `[A-Za-z0-9][A-Za-z0-9-]{1,63}`. */
private fun String.isRaftMessageRefId(): Boolean =
    length in 2..64 && first().isRaftMessageRefAlphanumeric() && all { it.isRaftMessageRefAlphanumeric() || it == '-' }

/** Web `RAFT_REF_THREAD_SHORT_ID_PATTERN` under its `i` flag. */
private fun String.isRaftMessageRefThreadShortId(): Boolean =
    length in 6..8 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun Char.isRaftMessageRefAlphanumeric(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isRaftMessageRefAsciiWord(): Boolean = isRaftMessageRefAlphanumeric() || this == '_'
