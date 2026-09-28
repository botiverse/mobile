package build.raft.app

import build.raft.app.data.SharedAdaptiveScrollAnchor
import build.raft.app.data.SharedAdaptiveScrollAnchorStore
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SlockEventBus
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Source-side orchestration for one standalone-Thread → detail-pane adoption (task #18).
 *
 * Sequence (all on the source page's coroutine):
 *   1. stage the viewport scroll anchor under (shellInstanceId, identity, requestId)
 *   2. capture the latest native editor value and persist the draft (awaited — Flush alone is
 *      not a barrier)
 *   3. native `adaptiveNavigation("prepare")` — proves the source is still the top controller
 *      directly above an AppShell and returns a one-shot token + controller identities
 *   4. post `slockAdaptiveDetailPrepared` binding src/tgt controller tokens to the pending
 *      request so a stray commit receipt cannot settle it
 *   5. post `slockAdaptiveDetailOpen` request and await the shell ACK
 *   6. accepted → `adaptiveNavigation("commit")`; rejected → `adaptiveNavigation("cancel")`
 *
 * The authoritive migration completion is the `slockAdaptiveNativeCommitResult` event the
 * native layer posts to the target shell — never this source page (it may be destroyed).
 * A rejected request or a failed/cancelled prepare ends the attempt: no auto-retry (the
 * caller re-arms only on a fresh DualPane window fact), and staged state is discarded.
 */
internal class SlockAdaptiveDetailOpenInitiator(
    private val shellInstanceId: String,
    private val hostId: String,
    private val anchorStore: SharedAdaptiveScrollAnchorStore,
    private val eventBus: SlockEventBus,
    private val nowEpochMillis: () -> Long,
    private val nativeAdaptiveNavigation: suspend (action: String, requestId: String, token: String?) -> JSONObject?,
    private val captureLatestDraft: suspend (requestId: String) -> Boolean,
    private val captureAnchor: (requestId: String) -> SharedAdaptiveScrollAnchor?,
    private val log: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
) {
    enum class Outcome {
        /** Commit was handed to the native layer; the result event settles the adoption. */
        Committed,
        RejectedByShell,
        PrepareFailed,
        DraftCaptureFailed,
        AckTimeout,
        ReadyTimeout,
        Aborted,
    }

    private var inFlightRequestId: String? = null

    /**
     * Runs one adoption attempt for [identity]. Reentrant calls while an attempt is in flight
     * are ignored — a second DualPane fact re-delivered during the same attempt must not
     * re-stage the anchor or double-prepare.
     */
    suspend fun initiate(
        identity: SlockAdaptiveDetailIdentity,
        launchPayload: WireThreadLaunchPayload,
        windowFact: SlockAdaptiveOpenWindowFact,
        requestId: String,
        ackTimeoutMillis: Long = DEFAULT_ACK_TIMEOUT_MILLIS,
        /**
         * Transactions which keep the native dock visible do not pop: the source page stays
         * mounted as a covered-top controller, so the commit dispatch is deferred until the
         * destination reports READY — the evidence the covered source uses to prove the handoff
         * actually rendered before surrendering its top slot. Covering transactions (the dock
         * reclaims the band) commit immediately after the accepted ack; the destination mount
         * is no longer observable from the covered source anyway. [awaitReadyBeforeCommit] is
         * the only branch difference; every earlier step is identical.
         */
        awaitReadyBeforeCommit: Boolean = false,
        readyTimeoutMillis: Long = DEFAULT_READY_TIMEOUT_MILLIS,
    ): Outcome {
        if (inFlightRequestId != null) {
            log("adaptive_initiate_skip reason=in_flight inFlight=$inFlightRequestId next=$requestId")
            return Outcome.Aborted
        }
        inFlightRequestId = requestId
        var preparedToken: String? = null
        try {
            captureAnchor(requestId)?.let { anchor ->
                anchorStore.stage(
                    shellInstanceId = shellInstanceId,
                    identity = identity,
                    anchor = anchor,
                )
            }
            // Latest editor value must reach the durable draft before prepare: after the
            // source pops, the destination restores from the persisted draft, not the editor.
            if (!captureLatestDraft(requestId)) {
                logWarn(
                    "adaptive_initiate_draft_capture_failed requestId=$requestId " +
                        "identity=${identity.key}"
                )
                discardAnchor(identity, requestId)
                return Outcome.DraftCaptureFailed
            }
            val prepare =
                nativeAdaptiveNavigation(ACTION_PREPARE, requestId, null)
                    ?: run {
                        logWarn(
                            "adaptive_initiate_prepare_unavailable requestId=$requestId " +
                                "identity=${identity.key}"
                        )
                        discardAnchor(identity, requestId)
                        return Outcome.PrepareFailed
                    }
            if (prepare.optString("status") != PREPARED_STATUS) {
                logWarn(
                    "adaptive_initiate_prepare_rejected requestId=$requestId " +
                        "status=${prepare.optString("status")} " +
                        "reason=${prepare.optString("reason")} identity=${identity.key}"
                )
                discardAnchor(identity, requestId)
                return Outcome.PrepareFailed
            }
            val token = prepare.optString("token").trim()
            val context = prepare.optJSONObject("context")
            val sourceToken = context?.optString("sourceControllerToken")?.trim().orEmpty()
            val targetToken = context?.optString("targetControllerToken")?.trim().orEmpty()
            if (token.isBlank() || sourceToken.isBlank() || targetToken.isBlank()) {
                logWarn(
                    "adaptive_initiate_prepare_incomplete requestId=$requestId " +
                        "tokenBlank=${token.isBlank()} srcBlank=${sourceToken.isBlank()} " +
                        "tgtBlank=${targetToken.isBlank()}"
                )
                discardAnchor(identity, requestId)
                runCatching { nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token.ifBlank { null }) }
                return Outcome.PrepareFailed
            }
            preparedToken = token
            // Bind the prepared controller identities before the open request lands: the shell
            // rejects settle for any pending lacking this binding.
            eventBus.post(
                eventName = SLOCK_ADAPTIVE_DETAIL_PREPARED_EVENT,
                eventData =
                    SlockAdaptiveDetailPrepared(
                        shellInstanceId = shellInstanceId,
                        requestId = requestId,
                        sourceControllerToken = sourceToken,
                        targetControllerToken = targetToken,
                    ).toJson(),
                crossProcess = true,
                source = "adaptive_detail_prepared",
            )
            val ackDeferred = CompletableDeferred<SlockAdaptiveDetailOpenAck>()
            val ackSubscription =
                eventBus.observe(
                    eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
                    crossProcess = true,
                ) { eventData ->
                    val ack = eventData?.toSlockAdaptiveDetailOpenAckOrNull() ?: return@observe
                    if (ack.shellInstanceId != shellInstanceId || ack.requestId != requestId) {
                        return@observe
                    }
                    ackDeferred.complete(ack)
                }
            val readyDeferred = CompletableDeferred<Unit>()
            val readySubscription =
                if (!awaitReadyBeforeCommit) {
                    null
                } else {
                    eventBus.observe(
                        eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
                        crossProcess = true,
                    ) { eventData ->
                        // READY carries only the identity key + generation: match it against
                        // the pending adoption this initiator opened. The requestId never
                        // reaches the child page, so the generation the shell echoed back in
                        // its ack is the join key.
                        val generation = eventData
                            ?.optDouble(SLOCK_ADAPTIVE_DETAIL_GENERATION, 0.0)?.toLong() ?: return@observe
                        val key = eventData.optString(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY)
                        if (key == identity.key && generation == ackedGeneration) {
                            readyDeferred.complete(Unit)
                        }
                    }
                }
            var ackedGeneration: Long = 0L
            try {
                eventBus.post(
                    eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_EVENT,
                    eventData =
                        SlockAdaptiveDetailOpenRequest(
                            shellInstanceId = shellInstanceId,
                            requestId = requestId,
                            hostId = hostId,
                            identity = identity,
                            launchPayloadJson = launchPayload.encodeForAdaptiveDetailOpen(),
                            windowFact = windowFact,
                        ).toJson(),
                    crossProcess = true,
                    source = "adaptive_detail_open_request",
                )
                val ack =
                    withTimeoutOrNull(ackTimeoutMillis) { ackDeferred.await() }
                        ?: run {
                            logWarn(
                                "adaptive_initiate_ack_timeout requestId=$requestId " +
                                    "timeoutMs=$ackTimeoutMillis identity=${identity.key}"
                            )
                            discardAnchor(identity, requestId)
                            runCatching {
                                nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token)
                            }
                            return Outcome.AckTimeout
                        }
                ackedGeneration = ack.generation
                if (!ack.accepted) {
                    // No auto-retry: the caller re-arms only on a new DualPane fact. The shell
                    // drops/discards its pending record on reject; mirror that for the anchor.
                    log(
                        "adaptive_initiate_rejected requestId=$requestId " +
                            "reason=${ack.reason} identity=${identity.key}"
                    )
                    discardAnchor(identity, requestId)
                    runCatching { nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token) }
                    return Outcome.RejectedByShell
                }
            } finally {
                ackSubscription.close()
            }
            if (awaitReadyBeforeCommit) {
                val generation = ackedGeneration
                val ready =
                    withTimeoutOrNull(readyTimeoutMillis) { readyDeferred.await() }
                if (ready == null) {
                    // The destination never reported mounted. Keep the staged anchor: cancel
                    // is only *dispatched* to native — if the prepared transaction already
                    // committed natively (late READY lost), the source may still pop, and the
                    // staged anchor is then the only recovery record for the viewport. The
                    // shell's pending entry and this anchor both survive until an explicit
                    // retry consumes or the shell's drop/reset path discards them.
                    logWarn(
                        "adaptive_initiate_ready_timeout requestId=$requestId " +
                            "generation=$generation timeoutMs=$readyTimeoutMillis " +
                            "identity=${identity.key}"
                    )
                    runCatching {
                        nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token)
                    }
                    return Outcome.ReadyTimeout
                }
            }
            // Hand the commit to the native layer. Its result arrives as the
            // slockAdaptiveNativeCommitResult event on the target shell; this page may already
            // be gone by then, so nothing after this point may require source state.
            runCatching { nativeAdaptiveNavigation(ACTION_COMMIT, requestId, token) }
                .onFailure { error ->
                    logWarn(
                        "adaptive_initiate_commit_dispatch_failed requestId=$requestId " +
                            "type=${error::class.simpleName} identity=${identity.key}"
                    )
                }
            return Outcome.Committed
        } finally {
            readySubscription?.close()
            if (inFlightRequestId == requestId) inFlightRequestId = null
        }
    }

    private fun discardAnchor(identity: SlockAdaptiveDetailIdentity, requestId: String) {
        anchorStore.discard(
            shellInstanceId = shellInstanceId,
            identity = identity,
            requestId = requestId,
        )
    }

    private companion object {
        const val ACTION_PREPARE = "prepare"
        const val ACTION_COMMIT = "commit"
        const val ACTION_CANCEL = "cancel"
        const val PREPARED_STATUS = "prepared"
        const val DEFAULT_ACK_TIMEOUT_MILLIS = 10_000L
        const val DEFAULT_READY_TIMEOUT_MILLIS = 10_000L
    }
}
