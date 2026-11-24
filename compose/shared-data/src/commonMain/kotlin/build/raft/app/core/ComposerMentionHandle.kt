package build.raft.app

import build.raft.app.model.ChannelExternalMemberProfile

/**
 * The `@` token a Slack participant is mentioned by, exactly as web `MessageInput.tsx` builds it: the
 * first non-blank Slack handle (else the display name without spaces), every character outside
 * Raft's handle alphabet (`[\p{L}\p{N}_-]`) replaced by `-`, at most 128 characters (the server's
 * `parseStructuredMentions` limit). Null when nothing usable is left.
 */
@RaftCoreInternalApi fun ChannelExternalMemberProfile.composerMentionHandle(): String? {
    val raw = handles.firstOrNull { it.trim().isNotEmpty() } ?: displayName.filterNot { it.isWhitespace() }
    val handle = buildString {
        raw.forEachRaftCodePoint { codePoint ->
            if (codePoint.isRaftHandleCodePoint()) appendRaftCodePoint(codePoint) else append('-')
        }
    }
    return handle.takeIf { it.isNotEmpty() && it.length <= 128 }
}

@RaftCoreInternalApi inline fun String.forEachRaftCodePoint(action: (Int) -> Unit) {
    var index = 0
    while (index < length) {
        val codePoint = raftCodePointAtCompat(index)
        action(codePoint)
        index += if (codePoint >= 0x10000) 2 else 1
    }
}

@RaftCoreInternalApi fun String.raftCodePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
    }
    return high.code
}

@RaftCoreInternalApi fun StringBuilder.appendRaftCodePoint(codePoint: Int) {
    if (codePoint < 0x10000) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}
