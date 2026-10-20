package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi
const val ChannelNameMaxLength: Int = 32

// Unicode category validation remains server-owned so every client follows the
// server runtime's Unicode version without embedding a drifting category table.
@RaftCoreInternalApi
fun String.isSubmittableChannelName(): Boolean {
    val normalized = trim()
    return normalized.length in 1..ChannelNameMaxLength
}
