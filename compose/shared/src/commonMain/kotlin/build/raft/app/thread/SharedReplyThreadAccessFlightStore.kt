package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

private const val SharedReplyThreadAccessFailureTtlMillis = 10_000L
private const val SharedReplyThreadAccessFailureLimit = 64
private val SharedReplyThreadAccessFlightOrigin = TimeSource.Monotonic.markNow()

/**
 * Exact authority binding for the unresolved Reply Thread POST→GET flight.
 *
 * [authBinding] is retained only for in-process equality. It is deliberately excluded from
 * [toString] so diagnostics cannot expose a credential.
 */
internal class SharedReplyThreadAccessFlightKey(
    val environmentId: String,
    val principalId: String,
    val serverId: String,
    private val authBinding: String,
    val parentChannelId: String,
    val parentMessageId: String
) {
    override fun equals(other: Any?): Boolean =
        other is SharedReplyThreadAccessFlightKey &&
            environmentId == other.environmentId &&
            principalId == other.principalId &&
            serverId == other.serverId &&
            authBinding == other.authBinding &&
            parentChannelId == other.parentChannelId &&
            parentMessageId == other.parentMessageId

    override fun hashCode(): Int {
        var result = environmentId.hashCode()
        result = 31 * result + principalId.hashCode()
        result = 31 * result + serverId.hashCode()
        result = 31 * result + authBinding.hashCode()
        result = 31 * result + parentChannelId.hashCode()
        result = 31 * result + parentMessageId.hashCode()
        return result
    }

    override fun toString(): String =
        "SharedReplyThreadAccessFlightKey(" +
            "environmentId=$environmentId,principalId=$principalId,serverId=$serverId," +
            "authBinding=[redacted],parentChannelId=$parentChannelId,parentMessageId=$parentMessageId)"
}

internal fun sharedReplyThreadAccessFlightKey(
    environmentId: String,
    principalId: String,
    serverId: String,
    authBinding: String,
    parentChannelId: String,
    parentMessageId: String
): SharedReplyThreadAccessFlightKey? {
    val normalizedEnvironment = environmentId.trim().trimEnd('/')
    val normalizedPrincipal = principalId.trim()
    val normalizedServer = serverId.trim()
    val normalizedParentChannel = parentChannelId.trim()
    val normalizedParentMessage = parentMessageId.trim()
    if (
        normalizedEnvironment.isBlank() ||
        normalizedPrincipal.isBlank() ||
        normalizedServer.isBlank() ||
        authBinding.isBlank() ||
        normalizedParentChannel.isBlank() ||
        normalizedParentMessage.isBlank()
    ) {
        return null
    }
    return SharedReplyThreadAccessFlightKey(
        environmentId = normalizedEnvironment,
        principalId = normalizedPrincipal,
        serverId = normalizedServer,
        authBinding = authBinding,
        parentChannelId = normalizedParentChannel,
        parentMessageId = normalizedParentMessage
    )
}

/**
 * App-scope single-flight plus a bounded error-only backoff ledger.
 *
 * A caller cancellation only stops that waiter; the app-scope producer and its original transport
 * deadline remain authoritative. Content and permission results are never retained after terminal.
 */
internal class SharedReplyThreadAccessFlightStore<T>(
    private val scope: CoroutineScope,
    private val failureTtlMillis: Long = SharedReplyThreadAccessFailureTtlMillis,
    private val failureLimit: Int = SharedReplyThreadAccessFailureLimit,
    private val nowMillis: () -> Long = {
        SharedReplyThreadAccessFlightOrigin.elapsedNow().inWholeMilliseconds
    }
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<SharedReplyThreadAccessFlightKey, Deferred<DataResult<T>>>()
    private val failures = linkedMapOf<SharedReplyThreadAccessFlightKey, FailureEntry>()

    suspend fun joinOrStart(
        key: SharedReplyThreadAccessFlightKey,
        replaceFailure: Boolean = false,
        fetch: suspend () -> DataResult<T>
    ): DataResult<T> {
        var immediateFailure: DataResult.Error? = null
        var created: Deferred<DataResult<T>>? = null
        val shared =
            mutex.withLock {
                pruneFailuresLocked(nowMillis())
                if (replaceFailure) failures.remove(key)
                failures[key]?.let { entry ->
                    immediateFailure = DataResult.Error(entry.error)
                    return@withLock null
                }
                inFlight[key]
                    ?: scope.async(start = CoroutineStart.LAZY) {
                        var terminal: DataResult<T>? = null
                        try {
                            fetch().also { result -> terminal = result }
                        } finally {
                            val currentJob = currentCoroutineContext()[Job]
                            mutex.withLock {
                                if (inFlight[key] === currentJob) {
                                    inFlight.remove(key)
                                    val result = terminal
                                    if (result is DataResult.Error) {
                                        failures.remove(key)
                                        failures[key] =
                                            FailureEntry(
                                                error = result.error,
                                                expiresAtMillis =
                                                    nowMillis().saturatingPlus(failureTtlMillis.coerceAtLeast(1L))
                                            )
                                        trimFailuresLocked()
                                    } else if (result != null) {
                                        failures.remove(key)
                                    }
                                }
                            }
                        }
                    }.also { candidate ->
                        inFlight[key] = candidate
                        created = candidate
                    }
            }
        immediateFailure?.let { return it }
        val producer = shared ?: error("reply thread access flight lookup missing")
        created?.start()
        return producer.await()
    }

    suspend fun failure(key: SharedReplyThreadAccessFlightKey): ScreenError? =
        mutex.withLock {
            pruneFailuresLocked(nowMillis())
            failures[key]?.error
        }

    suspend fun clearFailure(key: SharedReplyThreadAccessFlightKey) {
        mutex.withLock { failures.remove(key) }
    }

    private fun pruneFailuresLocked(now: Long) {
        failures.entries.removeAll { (_, entry) -> entry.expiresAtMillis <= now }
    }

    private fun trimFailuresLocked() {
        val limit = failureLimit.coerceAtLeast(1)
        while (failures.size > limit) {
            failures.remove(failures.keys.firstOrNull() ?: return)
        }
    }

    private data class FailureEntry(
        val error: ScreenError,
        val expiresAtMillis: Long
    )
}

private fun Long.saturatingPlus(other: Long): Long =
    if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other
