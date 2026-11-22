package build.raft.app

import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftEpochMillisSource
import build.raft.app.data.RaftLocalOffsetMinutesSource
import build.raft.app.format.RaftFormatLocale

/** Message times as an English UI writes them (what `rememberRaftMessageTimeFormat` builds there). */
internal val RaftEnglishMessageTime = RaftMessageTimeFormat(RaftFormatLocale.English, "Yesterday")

internal fun raftTestDatetimeFoundationOwner(
    nowMillis: () -> Long = { 0L },
    localOffsetMinutesAt: (Long) -> Int? = { null },
): RaftDatetimeFoundationOwner =
    RaftDatetimeFoundationOwner(
        kuiklyBaseClock = RaftEpochMillisSource(nowMillis),
        kuiklyBaseOffset = RaftLocalOffsetMinutesSource(localOffsetMinutesAt),
    )

/** No system zone reading: every offset falls back to the legacy payload's minutes. */
internal val RaftLegacyTestDatetimeFoundationOwner =
    raftTestDatetimeFoundationOwner()
