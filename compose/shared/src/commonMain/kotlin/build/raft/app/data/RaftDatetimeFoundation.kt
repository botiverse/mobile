package build.raft.app.data

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

internal fun interface RaftEpochMillisSource {
    fun nowEpochMillis(): Long
}

internal fun interface RaftLocalOffsetMinutesSource {
    /** Returns null when the new foundation cannot represent the legacy minute boundary exactly. */
    fun atEpochMillis(epochMillis: Long): Int?
}

internal data class RaftDatetimeClockComparison(
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
internal class RaftDatetimeFoundationOwner(
    internal val usesKuiklyBase: Boolean,
    private val kuiklyBaseClock: RaftEpochMillisSource,
    private val legacyClock: RaftEpochMillisSource,
    private val kuiklyBaseOffset: RaftLocalOffsetMinutesSource,
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

    fun compareClocks(): RaftDatetimeClockComparison {
        val kuiklyBase = kuiklyBaseClock.nowEpochMillis()
        val legacy = legacyClock.nowEpochMillis()
        return RaftDatetimeClockComparison(
            selectedEpochMillis = if (usesKuiklyBase) kuiklyBase else legacy,
            kuiklyBaseEpochMillis = kuiklyBase,
            legacyEpochMillis = legacy,
        )
    }
}

private val KuiklyBaseEpochMillisSource =
    RaftEpochMillisSource { Clock.System.now().toEpochMilliseconds() }

private val LegacyPlatformEpochMillisSource =
    RaftEpochMillisSource(::legacyPlatformNowMillis)

private val KuiklyBaseLocalOffsetMinutesSource =
    RaftLocalOffsetMinutesSource { epochMillis ->
        runCatching {
            val offsetSeconds =
                TimeZone.currentSystemDefault().offsetAt(Instant.fromEpochMilliseconds(epochMillis)).totalSeconds
            // Whole minutes only: a historical second offset must fall back, never be truncated.
            check(offsetSeconds % 60 == 0) { "offset $offsetSeconds s is not a whole minute" }
            offsetSeconds / 60
        }.getOrNull()
    }

internal val ProcessRaftDatetimeFoundationOwner =
    RaftDatetimeFoundationOwner(
        usesKuiklyBase = RaftKuiklyBaseDatetimeEnabledByBuild,
        kuiklyBaseClock = KuiklyBaseEpochMillisSource,
        legacyClock = LegacyPlatformEpochMillisSource,
        kuiklyBaseOffset = KuiklyBaseLocalOffsetMinutesSource,
    )

internal fun defaultNowMillis(): Long =
    ProcessRaftDatetimeFoundationOwner.nowEpochMillis()

/** Previous platform implementation retained only for the build rollback path. */
internal expect fun legacyPlatformNowMillis(): Long
