package build.raft.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import build.raft.app.model.RaftTimeFormat24Hour
import build.raft.app.model.normalizeRaftTimeFormatPreference
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

internal fun interface RaftEpochMillisSource {
    fun nowEpochMillis(): Long
}

internal fun interface RaftLocalOffsetMinutesSource {
    /** Returns null when the new foundation cannot represent the legacy minute boundary exactly. */
    fun atEpochMillis(epochMillis: Long): Int?
}

/**
 * Process-level owner for wall-clock and current-system-zone reads.
 *
 * The legacy offset remains an explicit fallback because the compatibility
 * payload only carries whole minutes; historical second offsets and platform
 * read failures must never be silently truncated.
 */
internal class RaftDatetimeFoundationOwner(
    private val kuiklyBaseClock: RaftEpochMillisSource,
    private val kuiklyBaseOffset: RaftLocalOffsetMinutesSource,
) {
    fun nowEpochMillis(): Long = kuiklyBaseClock.nowEpochMillis()

    fun localOffsetMinutesAt(
        epochMillis: Long,
        legacyOffsetMinutes: Int,
    ): Int = kuiklyBaseOffset.atEpochMillis(epochMillis) ?: legacyOffsetMinutes
}

private val KuiklyBaseEpochMillisSource =
    RaftEpochMillisSource { Clock.System.now().toEpochMilliseconds() }

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
        kuiklyBaseClock = KuiklyBaseEpochMillisSource,
        kuiklyBaseOffset = KuiklyBaseLocalOffsetMinutesSource,
    )

internal fun defaultNowMillis(): Long =
    ProcessRaftDatetimeFoundationOwner.nowEpochMillis()

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

    internal fun resetForTest() {
        overrideFlow.value = null
    }
}
