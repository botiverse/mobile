package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.data.SharedAdaptiveScrollAnchor
import build.raft.app.data.SlockAuthSessionTerminalSource
import build.raft.app.data.defaultNowMillis
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

// QA carrier only. These hooks never create auth sessions, ACKs, receipts or list data.
internal data class QaAdaptiveAuthWitness(val accountId: String, val generation: Long)

internal suspend fun qaReadAdaptiveAuthWitness(
    source: SlockAuthSessionTerminalSource?,
): QaAdaptiveAuthWitness? {
    val snapshot = source?.readAuthSessionTerminalSnapshot() ?: return null
    val accountId = snapshot.session?.user?.id?.takeIf { it.isNotBlank() } ?: return null
    return QaAdaptiveAuthWitness(accountId, snapshot.generation)
}

/** No session contents are retained or logged; only the account ID and auth revision survive. */
internal suspend fun BridgeModule.qaPollAdaptiveRaceControl(gate: String, requestId: String): Boolean =
    withTimeoutOrNull(1_000L) {
        suspendCancellableCoroutine { continuation ->
            var callbackRef: CallbackRef? = null
            var completed = false
            val result = toNative(
                methodName = "qaAdaptiveRaceControl",
                param = JSONObject().put("operation", "poll").put("gate", gate)
                    .put("requestId", requestId).put("expectedAction", "release").toString(),
                callback = callback@{ reply ->
                    if (completed) return@callback
                    completed = true
                    callbackRef?.let { removeCallback(it) }
                    if (continuation.isActive) continuation.resume(
                        reply?.optString("action") == "release" &&
                            reply.optString("gate") == gate &&
                            reply.optString("requestId") == requestId &&
                            reply.optString("commandId").isNotBlank(),
                    )
                },
                syncCall = false,
            )
            callbackRef = result.callbackRef
            if (completed) callbackRef?.let { removeCallback(it) }
            continuation.invokeOnCancellation {
                completed = true
                callbackRef?.let { removeCallback(it) }
            }
        }
    } ?: false

internal data class QaAdaptiveTimelineObservation(
    val instanceKey: String,
    val status: String,
    val messageIds: List<String>,
    val userScrollGeneration: Int,
)

/** Page-owned, cancellable pause before native prepare; the actual staged anchor is unchanged. */
internal class QaAdaptiveDeleteGate(private val expectedMessageId: String) {
    private var anchor: SharedAdaptiveScrollAnchor? = null
    private var before: QaAdaptiveTimelineObservation? = null
    private var enteredRequest: String? = null
    private var releasedRequest: String? = null

    fun wasReleased(requestId: String): Boolean = releasedRequest == requestId

    fun captured(value: SharedAdaptiveScrollAnchor?, observation: QaAdaptiveTimelineObservation?) {
        anchor = value
        before = observation
        enteredRequest = null
        releasedRequest = null
    }

    suspend fun beforeDraftCapture(
        requestId: String,
        bridge: BridgeModule,
        readTimeline: () -> QaAdaptiveTimelineObservation?,
        readAuthority: suspend () -> QaAdaptiveAuthWitness?,
    ): Boolean {
        if (enteredRequest == requestId) return true // second, real pre-commit capture
        enteredRequest = requestId
        val captured = anchor
        val original = before
        val originalIndex = original?.messageIds?.indexOf(expectedMessageId) ?: -1
        if (expectedMessageId.isBlank() || captured?.requestId != requestId ||
            captured.messageId != expectedMessageId || captured.atLatest ||
            captured.fallbackIndex < 0 || original == null || originalIndex < 0
        ) {
            bridge.log("adaptive_qa delete_gate_not_covered requestId=$requestId reason=anchor_precondition")
            return false
        }
        val authority = readAuthority() ?: return false
        val neighbors = listOfNotNull(
            original.messageIds.getOrNull(originalIndex - 1),
            original.messageIds.getOrNull(originalIndex + 1),
        )
        bridge.log("adaptive_qa anchor_staged_waiting requestId=$requestId messageId=$expectedMessageId " +
            "fallbackIndex=${captured.fallbackIndex} itemOffset=${captured.messageOffsetPx} atLatest=false " +
            "authGeneration=${authority.generation} instance=${original.instanceKey} neighbors=${neighbors.joinToString()}")
        var releaseReceived = false
        var outcome = "expired"
        try {
            return withTimeoutOrNull(120_000L) {
                while (true) {
                    if (readAuthority() != authority) {
                        outcome = "authority_changed"
                        return@withTimeoutOrNull false
                    }
                    val current = readTimeline()
                    if (current == null || current.instanceKey != original.instanceKey ||
                        current.userScrollGeneration != original.userScrollGeneration
                    ) {
                        outcome = "source_or_user_scroll_changed"
                        return@withTimeoutOrNull false
                    }
                    if (!releaseReceived) {
                        releaseReceived = bridge.qaPollAdaptiveRaceControl("delete", requestId)
                        if (releaseReceived) bridge.log("adaptive_qa delete_release_received requestId=$requestId")
                    }
                    // File release is only the owner's timing signal. Real canonical rows
                    // still decide whether the expected anchor is absent in this same window.
                    val targetPresent = expectedMessageId in current.messageIds
                    val neighborsPresent = neighbors.count { it in current.messageIds }
                    if (releaseReceived && !targetPresent &&
                        current.status in setOf("content", "empty") &&
                        neighbors.isNotEmpty() && neighborsPresent == neighbors.size
                    ) {
                        // Revalidate after the asynchronous control read too.
                        if (readAuthority() != authority) {
                            outcome = "authority_changed"
                            return@withTimeoutOrNull false
                        }
                        val latest = readTimeline()
                        if (latest != current) continue
                        outcome = "released_real_absence"
                        releasedRequest = requestId
                        bridge.log("adaptive_qa delete_gate_released requestId=$requestId targetPresent=false " +
                            "status=${current.status} messages=${current.messageIds.size} neighborsPresent=$neighborsPresent")
                        return@withTimeoutOrNull true
                    }
                    delay(200L)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            bridge.log("adaptive_qa delete_gate_end requestId=$requestId outcome=$outcome releaseReceived=$releaseReceived")
            anchor = null
            before = null
        }
    }
}

internal data class QaAdaptiveSavedReady(
    val requestId: String,
    val envelope: String,
    val shellId: String,
    val identityKey: String,
    val detailGeneration: Long,
    val authority: QaAdaptiveAuthWitness,
)

internal sealed interface QaAdaptiveReadyAdmission {
    data object Pass : QaAdaptiveReadyAdmission
    data object DuplicateHeld : QaAdaptiveReadyAdmission
    data class Captured(val saved: QaAdaptiveSavedReady) : QaAdaptiveReadyAdmission
    data class HoldReturnedAccount(
        val saved: QaAdaptiveSavedReady,
        val authority: QaAdaptiveAuthWitness,
    ) : QaAdaptiveReadyAdmission
}

/**
 * One bounded QA case per process launch. Only a real READY envelope and identity facts survive
 * shell disposal. No old shell callback, event bus, auth session or store is retained here.
 */
internal object QaAdaptiveReadyReplay {
    private val lock = SynchronizedObject()
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var expiryJob: Job? = null
    private var saved: QaAdaptiveSavedReady? = null
    private var expiresAt = 0L
    private var sawOtherAccount = false
    private var returnedAuthority: QaAdaptiveAuthWitness? = null
    private var holdClaimed = false
    private var replayReceiverShell: String? = null
    private var replayProcessed = false
    private var finished = false

    fun observeAuthority(authority: QaAdaptiveAuthWitness?): String = synchronized(lock) {
        val origin = saved?.authority ?: return@synchronized if (finished) "finished" else "uncaptured"
        when {
            authority == null -> "signed_out"
            authority.accountId != origin.accountId -> {
                sawOtherAccount = true
                "B"
            }
            sawOtherAccount && authority.generation > origin.generation -> {
                returnedAuthority = authority
                "A2"
            }
            else -> "A1"
        }
    }

    fun admit(
        requestId: String,
        envelope: String,
        authority: QaAdaptiveAuthWitness,
    ): QaAdaptiveReadyAdmission = synchronized(lock) {
        val event = JSONObject(envelope)
        val origin = saved
        if (finished) return@synchronized QaAdaptiveReadyAdmission.Pass
        if (origin == null) {
            val captured = QaAdaptiveSavedReady(
                requestId, envelope,
                event.optString(SLOCK_ADAPTIVE_DETAIL_SHELL_ID),
                event.optString(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY),
                event.optDouble(SLOCK_ADAPTIVE_DETAIL_GENERATION, 0.0).toLong(), authority,
            )
            saved = captured
            expiresAt = defaultNowMillis() + 600_000L
            expiryJob = expiryScope.launch {
                delay(600_000L)
                finish(requestId)
            }
            return@synchronized QaAdaptiveReadyAdmission.Captured(captured)
        }
        if (defaultNowMillis() >= expiresAt) {
            clearLocked()
            return@synchronized QaAdaptiveReadyAdmission.Pass
        }
        if (matches(origin, event)) {
            return@synchronized if (replayReceiverShell != null) QaAdaptiveReadyAdmission.Pass
            else QaAdaptiveReadyAdmission.DuplicateHeld
        }
        if (!holdClaimed && returnedAuthority == authority && requestId != origin.requestId) {
            holdClaimed = true
            return@synchronized QaAdaptiveReadyAdmission.HoldReturnedAccount(origin, authority)
        }
        QaAdaptiveReadyAdmission.Pass
    }

    fun beginReplay(caseId: String, receiverShell: String, authority: QaAdaptiveAuthWitness): String? =
        synchronized(lock) {
            val origin = saved ?: return@synchronized null
            if (origin.requestId != caseId || returnedAuthority != authority || !holdClaimed ||
                replayReceiverShell != null || defaultNowMillis() >= expiresAt
            ) return@synchronized null
            replayReceiverShell = receiverShell
            origin.envelope
        }

    fun recordProcessed(event: JSONObject?, receiverShell: String) = synchronized(lock) {
        val origin = saved ?: return@synchronized
        if (event != null && replayReceiverShell == receiverShell && matches(origin, event)) {
            replayProcessed = true
        }
    }

    fun wasProcessed(caseId: String): Boolean = synchronized(lock) {
        saved?.requestId == caseId && replayProcessed
    }

    fun finish(caseId: String) = synchronized(lock) {
        if (saved?.requestId == caseId) clearLocked()
    }

    private fun clearLocked() {
        saved = null
        returnedAuthority = null
        sawOtherAccount = false
        holdClaimed = false
        replayReceiverShell = null
        replayProcessed = false
        finished = true
        expiryJob?.cancel()
        expiryJob = null
    }

    private fun matches(origin: QaAdaptiveSavedReady, event: JSONObject): Boolean =
        event.optString(SLOCK_ADAPTIVE_DETAIL_SHELL_ID) == origin.shellId &&
            event.optString(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY) == origin.identityKey &&
            event.optDouble(SLOCK_ADAPTIVE_DETAIL_GENERATION, 0.0).toLong() == origin.detailGeneration
}
