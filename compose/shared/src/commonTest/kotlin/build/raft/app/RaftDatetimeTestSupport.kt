package build.raft.app

import build.raft.app.data.RaftDatetimeFoundationOwner
import build.raft.app.data.RaftEpochMillisSource
import build.raft.app.data.RaftLocalOffsetMinutesSource

internal fun raftTestDatetimeFoundationOwner(
    kuiklyBaseEnabled: Boolean = false,
    kuiklyBaseNowMillis: () -> Long = { 0L },
    legacyNowMillis: () -> Long = { 0L },
    localOffsetMinutesAt: (Long) -> Int? = { null },
): RaftDatetimeFoundationOwner =
    RaftDatetimeFoundationOwner(
        usesKuiklyBase = kuiklyBaseEnabled,
        kuiklyBaseClock = RaftEpochMillisSource(kuiklyBaseNowMillis),
        legacyClock = RaftEpochMillisSource(legacyNowMillis),
        kuiklyBaseOffset = RaftLocalOffsetMinutesSource(localOffsetMinutesAt),
    )

internal val RaftLegacyTestDatetimeFoundationOwner =
    raftTestDatetimeFoundationOwner()
