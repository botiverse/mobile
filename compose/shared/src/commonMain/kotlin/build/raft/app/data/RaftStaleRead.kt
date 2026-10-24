package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The read warehouse's degraded mode, as the server answers it (task #164; botiverse/slock
 * f6ace2b9 `packages/server/src/routes/rwStaleRead.ts` and `routes/errorResponse.ts`).
 *
 * Four reads go through it: `GET /servers/unread-summary`, `GET /channels/unread`,
 * `GET /channels/inbox` (first page, no query) and `GET /channels/threads/followed` (first
 * page). While RisingWave is overloaded (or, for the unread summary and the inbox, slow) the
 * server answers with a body it remembered, at most 5 minutes old, and the header
 * `X-Raft-Stale: rw_overloaded | rw_slow`. Past that, or with nothing remembered, it answers
 * `503` + `Retry-After: 2` + `{"error":"Temporarily overloaded, retry shortly","code":"rw_overloaded","retryable":true}`.
 * There is no Postgres fallback.
 *
 * Either answer means "the last good numbers may be old", never "zero" or "nothing": the
 * stores keep what they last showed and say so. Only this file reads the header and the error
 * code; repositories surface them as [RaftStaleReason] / [ScreenError.serverCode], and the
 * platform layer only passes headers through.
 */
internal const val RaftStaleResponseHeader = "X-Raft-Stale"

/** `code` of the server's overload body (`errorResponse.ts` `respondToRisingWaveOverload`). */
internal const val RwOverloadedErrorCode = "rw_overloaded"

/** `X-Raft-Stale`'s value (`rwStaleRead.ts` `StaleReason`); an unknown value still means stale. */
enum class RaftStaleReason(val wireValue: String?) {
    RwOverloaded("rw_overloaded"),
    RwSlow("rw_slow"),
    Unknown(null);

    companion object {
        fun fromWire(value: String): RaftStaleReason =
            entries.firstOrNull { it.wireValue != null && it.wireValue == value.trim() } ?: Unknown
    }
}

/** The stale reason of a successful answer the server served from its remembered body; null when fresh. */
internal fun HomeNetworkResponse.raftStaleReason(): RaftStaleReason? {
    if (!isSuccessful) return null
    val value =
        responseHeaders.entries
            .firstOrNull { (name, _) -> name.equals(RaftStaleResponseHeader, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
    return RaftStaleReason.fromWire(value)
}

private val rwOverloadJson = Json { ignoreUnknownKeys = true }

/** Server overload body (routes/errorResponse.ts): `{error, code:"rw_overloaded", retryable:true}`. */
@Serializable
private data class RwOverloadedErrorDto(
    val code: String? = null,
    val retryable: Boolean? = null
)

/** The overload body's `code` / `retryable`, from a 503 only; null for every other answer. */
private fun HomeNetworkResponse.rwOverloadedErrorOrNull(): RwOverloadedErrorDto? {
    if (code != 503) return null
    val payload = errorBodyText?.takeIf { it.isNotBlank() } ?: bodyText?.takeIf { it.isNotBlank() } ?: return null
    val error =
        runCatching { rwOverloadJson.decodeFromString(RwOverloadedErrorDto.serializer(), payload) }.getOrNull()
            ?: return null
    return error.takeIf { it.code == RwOverloadedErrorCode }
}

/** True for the server's `503 {code:"rw_overloaded"}`: the read warehouse is behind, retry later. */
internal fun HomeNetworkResponse.isRwOverloaded(): Boolean = rwOverloadedErrorOrNull() != null

/** True for a `503 {code:"rw_overloaded", retryable:true}` (the only shape the inbox retries). */
internal fun HomeNetworkResponse.isRetryableRwOverload(): Boolean = rwOverloadedErrorOrNull()?.retryable == true

/** The typed error of a failed read whose cause was the read warehouse being overloaded. */
val ScreenError.isRwOverloaded: Boolean
    get() = serverCode == RwOverloadedErrorCode

/**
 * A stale answer or an overload: the read warehouse could not give fresh numbers. Other failures
 * (a timeout, a 500) keep their own error and never raise the stale notice.
 */
val DataResult<*>.isRwOverloadedError: Boolean
    get() = this is DataResult.Error && error.isRwOverloaded

/**
 * The server's `Retry-After` as a wait in milliseconds: delta-seconds (what `errorResponse.ts`
 * sends) or an HTTP-date; null when absent or unreadable.
 */
internal fun HomeNetworkResponse.raftRetryAfterMillis(nowEpochMillis: () -> Long = ::defaultNowMillis): Long? {
    val raw =
        responseHeaders.entries
            .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    raw.toDoubleOrNull()?.let { seconds ->
        return if (seconds.isFinite() && seconds >= 0.0) (seconds * 1_000.0).toLong() else null
    }
    val dateMillis = parseRaftHttpDateMillis(raw) ?: return null
    return (dateMillis - nowEpochMillis()).coerceAtLeast(0L)
}

/** A `503 rw_overloaded`'s `Retry-After`, in whole seconds rounded up ([ScreenError.retryAfterSeconds]). */
internal fun HomeNetworkResponse.raftRetryAfterSecondsIfRwOverloaded(): Long? =
    if (isRwOverloaded()) raftRetryAfterMillis()?.let { (it + 999) / 1_000 } else null

/** The wait an overloaded read's error asks for, in milliseconds. */
val ScreenError.rwRetryAfterMillis: Long?
    get() = if (isRwOverloaded) retryAfterSeconds?.times(1_000) else null

private val HttpDateMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`), the only HTTP-date form servers send now. */
internal fun parseRaftHttpDateMillis(value: String): Long? {
    val match =
        Regex("""^[A-Za-z]{3}, (\d{2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$""").matchEntire(value.trim())
            ?: return null
    val (day, monthName, year, hour, minute, second) = match.destructured
    val month = HttpDateMonths.indexOf(monthName) + 1
    if (month == 0) return null
    val y = year.toLong()
    // Days from civil (Howard Hinnant), proleptic Gregorian, UTC.
    val yy = if (month <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day.toLong() - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146_097 + doe - 719_468
    return ((days * 24 + hour.toLong()) * 60 + minute.toLong()) * 60_000 + second.toLong() * 1_000
}

/** Waits between recovery reads after the first (web `rwStaleCounts.ts` `BACKOFF_MS`). */
internal val RaftStaleRecoveryBackoffMillis: List<Long> = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)

/** No recovery wait is longer than this, `Retry-After` included (web `RW_RECOVERY_MAX_DELAY_MS`). */
internal const val RaftStaleRecoveryMaxDelayMillis = 60_000L

/**
 * The wait before the [attempt]-th recovery read (0-based), as the web (slock #11156
 * `rwRecoveryDelayMs` + `withJitter`): the first waits for the server's `Retry-After` (5 s without
 * one), then 5, 10, 20, 40 and 60 s; each spread by ±20 % so clients made stale together do not come
 * back together, and never past 60 s.
 */
internal fun raftStaleRecoveryDelayMillis(
    attempt: Int,
    retryAfterMillis: Long?,
    random: Random = Random.Default
): Long {
    val base =
        if (attempt <= 0) {
            minOf(retryAfterMillis ?: RaftStaleRecoveryBackoffMillis.first(), RaftStaleRecoveryMaxDelayMillis)
        } else {
            RaftStaleRecoveryBackoffMillis[minOf(attempt - 1, RaftStaleRecoveryBackoffMillis.lastIndex)]
        }
    val jittered = (base * (0.8 + 0.4 * random.nextDouble())).roundToLong()
    return minOf(RaftStaleRecoveryMaxDelayMillis, jittered)
}

/**
 * Process-wide moments a stale read should be tried again at once (web: page visible or focused, and
 * the socket's `rooms:joined`), and whether the app is in the foreground (recovery reads only run
 * then). Fed by [updateRaftProcessNetworkVisibility] (the platform hosts' process visibility owner)
 * and by the realtime ingress's `rooms:joined` signal; read by every [RaftStaleReadRecovery].
 */
object RaftStaleReadRecoverySignals {
    private val foregroundState = MutableStateFlow(true)
    private val nudgeEvents = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** True until a host reports the process in the background. */
    val foreground: StateFlow<Boolean> = foregroundState.asStateFlow()
    val nudges: SharedFlow<String> = nudgeEvents.asSharedFlow()

    fun onProcessVisibility(foreground: Boolean) {
        val wasForeground = foregroundState.value
        foregroundState.value = foreground
        if (foreground && !wasForeground) nudgeEvents.tryEmit("foreground")
    }

    /** The socket (re)joined its rooms: realtime is back, so the read warehouse may be too. */
    fun onRealtimeRoomsJoined() {
        nudgeEvents.tryEmit("socket_reconnect")
    }
}

/**
 * At most one pending recovery read per surface while its last answer was stale or overloaded
 * (task #164, same schedule as the web, slock #11156 `rwStaleCounts.ts`).
 *
 * [schedule] after a stale answer arms the surface and starts one delayed read
 * ([raftStaleRecoveryDelayMillis]); while a timer runs another stale answer starts nothing. The read
 * runs only while the app is in the foreground: a timer that ends in the background waits, and the
 * app coming back to the foreground, or the socket rejoining its rooms, reads at once
 * ([RaftStaleReadRecoverySignals]). [reset] after a fresh answer disarms it. The read goes through
 * the store's own single-flight load at background priority; it returns false when it started
 * nothing (the store will be asked again by its next trigger).
 */
internal class RaftStaleReadRecovery(
    private val scope: CoroutineScope,
    private val label: String,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val delayMillis: (attempt: Int, retryAfterMillis: Long?) -> Long =
        { attempt, retryAfter -> raftStaleRecoveryDelayMillis(attempt, retryAfter) },
    private val foreground: StateFlow<Boolean> = RaftStaleReadRecoverySignals.foreground,
    private val nudges: Flow<String> = RaftStaleReadRecoverySignals.nudges,
) {
    private val lock = SynchronizedObject()
    private var armedRead: (suspend () -> Boolean)? = null
    private var timer: Job? = null
    /** Listens for [nudges] only while armed, so an idle surface holds no coroutine. */
    private var nudgeListener: Job? = null
    private var readRunning = false
    private var attempt = 0

    /** Armed: the last answer was stale and no fresh one has come since. */
    val armed: Boolean
        get() = synchronized(lock) { armedRead != null }

    fun schedule(retryAfterMillis: Long?, read: suspend () -> Boolean) {
        synchronized(lock) {
            armedRead = read
            readRunning = false
            if (nudgeListener?.isActive != true) {
                nudgeListener = scope.launch { nudges.collect { reason -> nudge(reason) } }
            }
            if (timer?.isActive == true) return
            val step = attempt
            val waitMillis = delayMillis(step, retryAfterMillis)
            attempt += 1
            diagnosticsLogger("stale_read_recovery_scheduled surface=$label attempt=$step delayMs=$waitMillis")
            timer =
                scope.launch {
                    delay(waitMillis)
                    val self = coroutineContext[Job]
                    synchronized(lock) { if (timer === self) timer = null }
                    if (!foreground.value) {
                        diagnosticsLogger("stale_read_recovery_wait surface=$label reason=background")
                        return@launch
                    }
                    run("timer")
                }
        }
    }

    /** The app came back or realtime reconnected: an armed surface reads now instead of waiting. */
    fun nudge(reason: String) {
        synchronized(lock) {
            if (armedRead == null || readRunning) return
            timer?.cancel()
            timer = null
        }
        if (!foreground.value) return
        scope.launch { run(reason) }
    }

    fun reset() {
        synchronized(lock) {
            if (armedRead == null && attempt == 0 && timer == null && nudgeListener == null) return
            diagnosticsLogger("stale_read_recovery_reset surface=$label attempts=$attempt")
            armedRead = null
            readRunning = false
            attempt = 0
            timer?.cancel()
            timer = null
            nudgeListener?.cancel()
            nudgeListener = null
        }
    }

    private suspend fun run(reason: String) {
        val read =
            synchronized(lock) {
                val read = armedRead ?: return
                if (readRunning) return
                readRunning = true
                read
            }
        diagnosticsLogger("stale_read_recovery_read surface=$label reason=$reason")
        val started = read()
        // Nothing started (the store skipped): the next schedule, nudge or visibility change asks again.
        if (!started) synchronized(lock) { readRunning = false }
    }
}
