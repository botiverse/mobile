package build.raft.app

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred

/**
 * Non-UI ledger behind [ThreadNewerAutoPaginationVisualFixtureProbe]: a single
 * class shared by the visual fixture and its unit-test contract so nonce gating,
 * release ordering, and per-run isolation are exercised by the same code the
 * fixture actually runs (task #58 observability contract).
 */
internal class ThreadNewerAutoFixtureLedger {
    private val lock = SynchronizedObject()
    private var activeRunNonce: String = ""
    private var initialRequests: Int = 0
    private var sentRequests: Int = 0
    private val afterRequests = mutableListOf<ThreadNewerAutoAfterRequest>()
    private val afterResponseGates = mutableMapOf<Int, CompletableDeferred<ThreadNewerAutoFixtureResponse>>()
    private val returnedAfterCalls = mutableSetOf<Int>()
    private val syncRequests = mutableListOf<ThreadNewerAutoSyncRequest>()
    private val olderRequests = mutableListOf<ThreadNewerAutoOlderRequest>()
    private val requestLog = mutableListOf<String>()

    fun beginRun(runNonce: String) {
        synchronized(lock) {
            activeRunNonce = runNonce
            initialRequests = 0
            sentRequests = 0
            afterRequests.clear()
            afterResponseGates.values.forEach { gate ->
                gate.cancel()
            }
            afterResponseGates.clear()
            returnedAfterCalls.clear()
            syncRequests.clear()
            olderRequests.clear()
            requestLog.clear()
        }
    }

    fun recordInitialRequest(runNonce: String, requestTrace: String? = null): Int =
        synchronized(lock) {
            check(activeRunNonce == runNonce) { "stale initial request for $runNonce" }
            initialRequests += 1
            requestLog += "channelMessages_initial#$initialRequests${requestTrace?.let { ",trace=$it" } ?: ""}"
            initialRequests
        }

    fun recordSyncRequest(runNonce: String, channelId: String, sinceSeq: Long, limit: Int, requestTrace: String? = null): Int =
        synchronized(lock) {
            check(activeRunNonce == runNonce) { "stale sync request for $runNonce" }
            val callNumber = syncRequests.size + 1
            syncRequests += ThreadNewerAutoSyncRequest(
                callNumber = callNumber,
                channelId = channelId,
                sinceSeq = sinceSeq,
                limit = limit,
                requestTrace = requestTrace,
            )
            requestLog += "messageSync#$callNumber(sinceSeq=$sinceSeq,limit=$limit${requestTrace?.let { ",trace=$it" } ?: ""})"
            callNumber
        }

    fun recordOlderRequest(runNonce: String, beforeSeq: Long, limit: Int, requestTrace: String? = null) {
        synchronized(lock) {
            if (activeRunNonce == runNonce) {
                olderRequests += ThreadNewerAutoOlderRequest(
                    callNumber = olderRequests.size + 1,
                    beforeSeq = beforeSeq,
                    limit = limit,
                    requestTrace = requestTrace,
                )
                requestLog += "channelMessages_older#${olderRequests.size}(beforeSeq=$beforeSeq,limit=$limit${requestTrace?.let { ",trace=$it" } ?: ""})"
            }
        }
    }

    fun recordOtherRequest(runNonce: String, description: String) {
        synchronized(lock) {
            if (activeRunNonce == runNonce) requestLog += description
        }
    }

    fun recordSentRequest(runNonce: String) {
        synchronized(lock) {
            check(runNonce == activeRunNonce) { "stale thread newer auto send run $runNonce" }
            sentRequests += 1
        }
    }

    fun sentRequestCount(runNonce: String): Int =
        synchronized(lock) {
            if (runNonce == activeRunNonce) sentRequests else 0
        }

    suspend fun awaitAfterResponse(
        runNonce: String,
        afterSeq: Long,
        limit: Int,
        requestTrace: String? = null,
    ): ThreadNewerAutoFixtureResponse {
        val (callNumber, gate) = synchronized(lock) {
            check(activeRunNonce == runNonce) { "stale newer request for $runNonce" }
            val callNumber = afterRequests.size + 1
            check(callNumber !in afterResponseGates) { "duplicate newer request number $callNumber" }
            afterRequests +=
                ThreadNewerAutoAfterRequest(
                    callNumber = callNumber,
                    afterSeq = afterSeq,
                    limit = limit,
                    requestTrace = requestTrace,
                )
            requestLog += "channelMessages_after#$callNumber(afterSeq=$afterSeq,limit=$limit${requestTrace?.let { ",trace=$it" } ?: ""})"
            val gate = CompletableDeferred<ThreadNewerAutoFixtureResponse>()
            afterResponseGates[callNumber] = gate
            callNumber to gate
        }
        val response = gate.await()
        synchronized(lock) {
            if (activeRunNonce == runNonce) {
                returnedAfterCalls += callNumber
                requestLog += "afterResponse#$callNumber(${response.logLabel()})"
            }
        }
        return response
    }

    fun snapshot(runNonce: String): ThreadNewerAutoPaginationFixtureSnapshot =
        synchronized(lock) {
            check(activeRunNonce == runNonce) {
                "fixture run mismatch: expected=$activeRunNonce requested=$runNonce"
            }
            ThreadNewerAutoPaginationFixtureSnapshot(
                runNonce = activeRunNonce,
                initialRequestCount = initialRequests,
                afterRequests = afterRequests.toList(),
                returnedAfterCalls = returnedAfterCalls.sorted(),
                syncRequests = syncRequests.toList(),
                olderRequests = olderRequests.toList(),
                requestLog = requestLog.toList(),
            )
        }

    fun release(runNonce: String, callNumber: Int, response: ThreadNewerAutoFixtureResponse): Boolean {
        val gate =
            synchronized(lock) {
                if (activeRunNonce != runNonce) return@synchronized null
                afterResponseGates[callNumber]
            } ?: run {
                synchronized(lock) {
                    if (activeRunNonce == runNonce) {
                        requestLog += "release_attempt#$callNumber(${response.logLabel()}) result=no_gate"
                    }
                }
                return false
            }
        return synchronized(lock) {
            if (activeRunNonce != runNonce) {
                false
            } else {
                val completed = gate.complete(response)
                requestLog += "release#$callNumber(${response.logLabel()}) completed=$completed"
                completed
            }
        }
    }
}

public data class ThreadNewerAutoPaginationFixtureSnapshot(
    val runNonce: String,
    val initialRequestCount: Int,
    val afterRequests: List<ThreadNewerAutoAfterRequest>,
    val returnedAfterCalls: List<Int>,
    val syncRequests: List<ThreadNewerAutoSyncRequest> = emptyList(),
    val olderRequests: List<ThreadNewerAutoOlderRequest> = emptyList(),
    val requestLog: List<String> = emptyList(),
)

public data class ThreadNewerAutoSyncRequest(
    val callNumber: Int,
    val channelId: String,
    val sinceSeq: Long,
    val limit: Int,
    val requestTrace: String? = null,
)

public data class ThreadNewerAutoOlderRequest(
    val callNumber: Int,
    val beforeSeq: Long,
    val limit: Int,
    val requestTrace: String? = null,
)

public data class ThreadNewerAutoAfterRequest(
    val callNumber: Int,
    val afterSeq: Long,
    val limit: Int,
    val requestTrace: String? = null,
)

internal sealed interface ThreadNewerAutoFixtureResponse {
    data class Success(val messageCount: Int) : ThreadNewerAutoFixtureResponse

    data object Error : ThreadNewerAutoFixtureResponse

    fun logLabel(): String =
        when (this) {
            is Success -> "success rows=$messageCount"
            Error -> "error"
        }
}

