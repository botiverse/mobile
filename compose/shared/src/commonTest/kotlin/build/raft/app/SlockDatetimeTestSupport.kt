package build.raft.app

import build.raft.app.data.SlockDatetimeFoundationOwner
import build.raft.app.data.SlockEpochMillisSource
import build.raft.app.data.SlockLocalOffsetMinutesSource

internal fun slockTestDatetimeFoundationOwner(
    kuiklyBaseEnabled: Boolean = false,
    kuiklyBaseNowMillis: () -> Long = { 0L },
    legacyNowMillis: () -> Long = { 0L },
    localOffsetMinutesAt: (Long) -> Int? = { null },
): SlockDatetimeFoundationOwner =
    SlockDatetimeFoundationOwner(
        usesKuiklyBase = kuiklyBaseEnabled,
        kuiklyBaseClock = SlockEpochMillisSource(kuiklyBaseNowMillis),
        legacyClock = SlockEpochMillisSource(legacyNowMillis),
        kuiklyBaseOffset = SlockLocalOffsetMinutesSource(localOffsetMinutesAt),
    )

internal val SlockLegacyTestDatetimeFoundationOwner =
    slockTestDatetimeFoundationOwner()
