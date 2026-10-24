package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/** Starting delay of [RaftStaleReadRecovery]: the server's own body is at most 5 minutes old. */
internal const val RaftStaleRecoveryBaseDelayMillis = 15_000L

/** Longest wait between two recovery reads; the server's remembered body is at most this old. */
internal const val RaftStaleRecoveryMaxDelayMillis = 300_000L

/**
 * 15 s, 30 s, 60 s, 120 s, 240 s, then every 5 minutes, each spread by up to ±20 % so the clients
 * a restart made stale together do not come back together.
 */
internal fun raftStaleRecoveryDelayMillis(attempt: Int, random: Random = Random.Default): Long {
    val exponent = attempt.coerceIn(0, 5)
    val base = minOf(RaftStaleRecoveryBaseDelayMillis shl exponent, RaftStaleRecoveryMaxDelayMillis)
    val spread = base / 5
    return base - spread + random.nextLong(0, 2 * spread + 1)
}

/**
 * At most one pending recovery read per surface while its last answer was stale (task #164).
 *
 * [schedule] after a stale answer arms one delayed read with backoff; while one is armed another
 * stale answer arms nothing. [reset] after a fresh answer drops the pending read and the backoff.
 * The read itself goes through the store's ordinary single-flight load, at background priority,
 * and the store skips it while the surface is hidden, so a stale surface adds one request per
 * backoff step at most, never a retry loop.
 */
internal class RaftStaleReadRecovery(
    private val scope: CoroutineScope,
    private val label: String,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val delayMillis: (attempt: Int) -> Long = { attempt -> raftStaleRecoveryDelayMillis(attempt) },
) {
    private val lock = SynchronizedObject()
    private var job: Job? = null
    private var attempt = 0

    val pending: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    fun schedule(read: suspend () -> Unit) {
        synchronized(lock) {
            if (job?.isActive == true) return
            val waitMillis = delayMillis(attempt)
            val step = attempt
            attempt += 1
            diagnosticsLogger("stale_read_recovery_scheduled surface=$label attempt=$step delayMs=$waitMillis")
            job =
                scope.launch {
                    delay(waitMillis)
                    val self = coroutineContext[Job]
                    synchronized(lock) { if (job === self) job = null }
                    diagnosticsLogger("stale_read_recovery_read surface=$label attempt=$step")
                    read()
                }
        }
    }

    fun reset() {
        synchronized(lock) {
            if (attempt == 0 && job == null) return
            diagnosticsLogger("stale_read_recovery_reset surface=$label attempts=$attempt")
            attempt = 0
            job?.cancel()
            job = null
        }
    }
}
