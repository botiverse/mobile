package build.raft.app.emoji

import build.raft.app.RaftCoreInternalApi

/** Not checked on this platform: every emoji is offered (the picker hides none). */
@RaftCoreInternalApi actual fun raftCanDrawEmoji(emoji: String): Boolean = true
