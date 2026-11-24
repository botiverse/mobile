package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.normalizeRaftTimeFormatPreference
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

@RaftCoreInternalApi fun interface RaftEpochMillisSource {
    fun nowEpochMillis(): Long
}

@RaftCoreInternalApi fun interface RaftLocalOffsetMinutesSource {
    /** Returns null when the new foundation cannot represent the legacy minute boundary exactly. */
    fun atEpochMillis(epochMillis: Long): Int?
}

/**
 * Process-level owner for wall-clock and effective viewer time-zone reads (account `preferredTimezone`
 * when configured, else device system zone — matching web `translationStore.settings.effectiveTimezone`).
 *
 * The legacy offset remains an explicit fallback because the compatibility
 * payload only carries whole minutes; historical second offsets and platform
 * read failures must never be silently truncated.
 */
@RaftCoreInternalApi class RaftDatetimeFoundationOwner(
    private val kuiklyBaseClock: RaftEpochMillisSource,
    private val kuiklyBaseOffset: RaftLocalOffsetMinutesSource,
    private val effectiveTimeZoneId: () -> String? = { null },
) {
    fun nowEpochMillis(): Long = kuiklyBaseClock.nowEpochMillis()

    fun localOffsetMinutesAt(
        epochMillis: Long,
        legacyOffsetMinutes: Int,
    ): Int = kuiklyBaseOffset.atEpochMillis(epochMillis) ?: legacyOffsetMinutes

    fun effectiveTimeZoneIdOrNull(): String? = effectiveTimeZoneId()
}

@RaftCoreInternalApi fun normalizeValidIanaTimeZoneOrNull(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        TimeZone.of(trimmed)
        trimmed
    }.getOrNull()
}

@RaftCoreInternalApi fun resolveEffectiveRaftTimeZone(
    preferredTimeZoneId: String? = RaftTimeZoneRuntime.currentOrNull,
): TimeZone =
    preferredTimeZoneId
        ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
        ?: TimeZone.currentSystemDefault()

@RaftCoreInternalApi fun raftOffsetMinutesForTimeZoneOrNull(
    timeZoneId: String?,
    epochMillis: Long,
): Int? {
    val normalized = timeZoneId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        val offsetSeconds =
            TimeZone.of(normalized).offsetAt(Instant.fromEpochMilliseconds(epochMillis)).totalSeconds
        // Whole minutes only: a historical second offset must fall back, never be truncated.
        check(offsetSeconds % 60 == 0) { "offset $offsetSeconds s is not a whole minute" }
        offsetSeconds / 60
    }.getOrNull()
}

/**
 * Resolves the UTC offset in minutes for [epochMillis] in [this] context: when [RaftTimeContext.timeZoneId]
 * is present, evaluates that IANA zone at [epochMillis] (handling DST transitions for past/future dates);
 * otherwise returns [RaftTimeContext.localOffsetMinutes].
 */
@RaftCoreInternalApi fun RaftTimeContext.offsetMinutesAt(epochMillis: Long): Int =
    raftOffsetMinutesForTimeZoneOrNull(timeZoneId, epochMillis) ?: localOffsetMinutes

private val KuiklyBaseEpochMillisSource =
    RaftEpochMillisSource { Clock.System.now().toEpochMilliseconds() }

private val KuiklyBaseLocalOffsetMinutesSource =
    RaftLocalOffsetMinutesSource { epochMillis ->
        runCatching {
            val offsetSeconds =
                resolveEffectiveRaftTimeZone().offsetAt(Instant.fromEpochMilliseconds(epochMillis)).totalSeconds
            // Whole minutes only: a historical second offset must fall back, never be truncated.
            check(offsetSeconds % 60 == 0) { "offset $offsetSeconds s is not a whole minute" }
            offsetSeconds / 60
        }.getOrNull()
    }

@RaftCoreInternalApi val ProcessRaftDatetimeFoundationOwner =
    RaftDatetimeFoundationOwner(
        kuiklyBaseClock = KuiklyBaseEpochMillisSource,
        kuiklyBaseOffset = KuiklyBaseLocalOffsetMinutesSource,
        effectiveTimeZoneId = {
            RaftTimeZoneRuntime.currentOrNull ?: runCatching { TimeZone.currentSystemDefault().id }.getOrNull()
        },
    )

@RaftCoreInternalApi fun defaultNowMillis(): Long =
    ProcessRaftDatetimeFoundationOwner.nowEpochMillis()

/**
 * Process-wide reactive holder for the signed-in account's IANA preferred timezone
 * (`preferredTimezone` from `GET/PATCH /api/auth/me`, matching web `translationStore.settings.preferredTimezone`).
 *
 * When non-null and valid, [ProcessRaftDatetimeFoundationOwner] resolves offsets in this zone
 * instead of the device zone, matching web `effectiveTimezone = preferredTimezone ?? detectBrowserTimezone()`.
 * Backed by [MutableStateFlow] rather than Compose `mutableStateOf` so writes from repository /
 * session flows never allocate Compose snapshot state records off the main thread (#264).
 */
object RaftTimeZoneRuntime {
    private val overrideFlow = MutableStateFlow<String?>(null)

    val currentOrNull: String?
        get() = overrideFlow.value

    val state: StateFlow<String?>
        get() = overrideFlow.asStateFlow()

    @Composable
    fun observe(): String? = overrideFlow.collectAsState().value

    fun update(value: String?) {
        overrideFlow.value = normalizeValidIanaTimeZoneOrNull(value)
    }

    @RaftCoreInternalApi fun resetForTest() {
        overrideFlow.value = null
    }
}

/**
 * Process-wide reactive holder for the signed-in account's 12h/24h time-format preference.
 *
 * Backed by [MutableStateFlow] rather than Compose `mutableStateOf` so writes from repository /
 * session flows never allocate Compose snapshot state records off the main thread (#264).
 */
object RaftTimeFormatRuntime {
    private val overrideFlow = MutableStateFlow<String?>(null)

    val current: String
        get() = overrideFlow.value ?: RaftTimeFormat24Hour

    val currentOrNull: String?
        get() = overrideFlow.value

    val state: StateFlow<String?>
        get() = overrideFlow.asStateFlow()

    @Composable
    fun observe(fallback: String = RaftTimeFormat24Hour): String =
        overrideFlow.collectAsState().value ?: normalizeRaftTimeFormatPreference(fallback)

    fun update(value: String?) {
        overrideFlow.value = value?.let(::normalizeRaftTimeFormatPreference)
    }

    @RaftCoreInternalApi fun resetForTest() {
        overrideFlow.value = null
    }
}
