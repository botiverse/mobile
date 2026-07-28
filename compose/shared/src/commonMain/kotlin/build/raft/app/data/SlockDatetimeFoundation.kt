package build.raft.app.data

import build.raft.kuiklybase.datetime.Clock
import build.raft.kuiklybase.datetime.Instant
import build.raft.kuiklybase.datetime.SystemTimeZone

internal fun interface SlockEpochMillisSource {
    fun nowEpochMillis(): Long
}

internal fun interface SlockLocalOffsetMinutesSource {
    /** Returns null when the new foundation cannot represent the legacy minute boundary exactly. */
    fun atEpochMillis(epochMillis: Long): Int?
}

internal data class SlockDatetimeClockComparison(
    val selectedEpochMillis: Long,
    val kuiklyBaseEpochMillis: Long,
    val legacyEpochMillis: Long,
) {
    val absoluteDriftMillis: Long
        get() = kotlin.math.abs(kuiklyBaseEpochMillis - legacyEpochMillis)
}

/**
 * Process-level owner for wall-clock and current-system-zone reads.
 *
 * The legacy clock remains compiled and selectable for rollback. The legacy
 * offset remains an explicit fallback because the compatibility payload only
 * carries whole minutes; historical second offsets and platform read failures
 * must never be silently truncated.
 */
internal class SlockDatetimeFoundationOwner(
    internal val usesKuiklyBase: Boolean,
    private val kuiklyBaseClock: SlockEpochMillisSource,
    private val legacyClock: SlockEpochMillisSource,
    private val kuiklyBaseOffset: SlockLocalOffsetMinutesSource,
) {
    fun nowEpochMillis(): Long =
        if (usesKuiklyBase) kuiklyBaseClock.nowEpochMillis() else legacyClock.nowEpochMillis()

    fun localOffsetMinutesAt(
        epochMillis: Long,
        legacyOffsetMinutes: Int,
    ): Int =
        if (usesKuiklyBase) {
            kuiklyBaseOffset.atEpochMillis(epochMillis) ?: legacyOffsetMinutes
        } else {
            legacyOffsetMinutes
        }

    fun compareClocks(): SlockDatetimeClockComparison {
        val kuiklyBase = kuiklyBaseClock.nowEpochMillis()
        val legacy = legacyClock.nowEpochMillis()
        return SlockDatetimeClockComparison(
            selectedEpochMillis = if (usesKuiklyBase) kuiklyBase else legacy,
            kuiklyBaseEpochMillis = kuiklyBase,
            legacyEpochMillis = legacy,
        )
    }
}

private val KuiklyBaseEpochMillisSource =
    SlockEpochMillisSource { Clock.System.now().toEpochMilliseconds() }

private val LegacyPlatformEpochMillisSource =
    SlockEpochMillisSource(::legacyPlatformNowMillis)

private val KuiklyBaseLocalOffsetMinutesSource =
    SlockLocalOffsetMinutesSource { epochMillis ->
        runCatching {
            SystemTimeZone
                .snapshot(Instant.fromEpochMilliseconds(epochMillis))
                .offset
                .toWholeMinutesExact()
        }.getOrNull()
    }

internal val ProcessSlockDatetimeFoundationOwner =
    SlockDatetimeFoundationOwner(
        usesKuiklyBase = SlockKuiklyBaseDatetimeEnabledByBuild,
        kuiklyBaseClock = KuiklyBaseEpochMillisSource,
        legacyClock = LegacyPlatformEpochMillisSource,
        kuiklyBaseOffset = KuiklyBaseLocalOffsetMinutesSource,
    )

internal fun defaultNowMillis(): Long =
    ProcessSlockDatetimeFoundationOwner.nowEpochMillis()

/** Previous platform implementation retained only for the build rollback path. */
internal expect fun legacyPlatformNowMillis(): Long
