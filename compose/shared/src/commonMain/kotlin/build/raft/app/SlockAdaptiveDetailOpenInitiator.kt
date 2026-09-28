package build.raft.app

import build.raft.app.data.SharedAdaptiveScrollAnchor
import build.raft.app.data.SharedAdaptiveScrollAnchorStore
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SlockEventBus
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    /**
     * Suspended request/reply call for prepare/cancel. [expectedEditorRefTag] +
     * [expectedEditorText] are included in the native args when non-null — the native
     * prepare contract (task #25/#27) binds the exact editor instance + its confirmed
     * persisted text to the nonce and re-verifies them at commit time; calls without a
     * valid pair are rejected natively.
     */
    private val nativeAdaptiveNavigation:
        suspend (
            action: String,
            requestId: String,
            token: String?,
            expectedEditorRefTag: String?,
            expectedEditorText: String?,
        ) -> JSONObject?,
    /**
     * Fire-and-forget commit dispatch (iOS never calls a source callback for commit —
     * the renderer may be torn down mid-pop). Separate from [nativeAdaptiveNavigation]
     * precisely so nothing in this orchestration suspends waiting for a reply that can
     * never arrive.
     */
    private val dispatchAdaptiveCommit: (requestId: String, token: String?) -> Unit,
    /**
     * Fire-and-forget cancel dispatch for cleanup in the finally path — must be callable
     * under NonCancellable when the caller coroutine is already cancelled, so it shares the
     * no-callback contract with [dispatchAdaptiveCommit].
     */
    private val dispatchAdaptiveCancel: (requestId: String, token: String?) -> Unit,
    /**
     * Identity-bearing draft capture: returns the persisted text bound to the draft key +
     * composer instance it was persisted under (see SharedThreadComposerDraftSnapshot in
     * ThreadPage.kt). The pre-commit fence requires an EXACT snapshot match — same text
     * under a different draft/session identity is drift, not a pass.
     */
    private val captureLatestDraft: suspend (requestId: String) -> SharedThreadComposerDraftSnapshot?,
    private val captureAnchor: (requestId: String) -> SharedAdaptiveScrollAnchor?,
    private val log: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
) {
    enum class Outcome {
        /**
         * Commit was *dispatched* to the native layer — never a success signal. The
         * `slockAdaptiveNativeCommitResult` event on the target shell is the only authority;
         * it settles the pending entry and consumes the staged anchor. Consumers must not
         * clear anchor/pending state or trigger success follow-ups off this value.
         */
        CommitDispatched,
        RejectedByShell,
        PrepareFailed,
        DraftCaptureFailed,
        /**
         * Live editor text drifted from the prepared snapshot (or the pre-commit re-read
         * failed) during ACK/READY wait — the transaction was cancelled, the source page
         * and the newer draft survive for a fresh attempt.
         */
        DraftDrifted,
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
        var commitDispatched = false
        var cancelDispatched = false
        var ackedGeneration: Long = 0L
        val readyDeferred = CompletableDeferred<Unit>()
        var readySubscription: RealtimeSubscription? = null
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
            // The returned snapshot is the version the pre-commit fence compares against.
            val preparedDraft = captureLatestDraft(requestId)
            if (preparedDraft == null) {
                logWarn(
                    "adaptive_initiate_draft_capture_failed requestId=$requestId " +
                        "identity=${identity.key}"
                )
                discardAnchor(identity, requestId)
                return Outcome.DraftCaptureFailed
            }
            // Bound the prepare wait: the bridge may wedge and native contract gives no
            // guarantee a reply is emitted for every request.
            if (preparedDraft.editorNativeRef < 0) {
                // No attributable editor instance — the native commit-time fence cannot
                // bind this capture to a concrete AutoHeightTextAreaView; treat as a
                // capture failure rather than arming a guard we cannot honor.
                logWarn(
                    "adaptive_initiate_editor_ref_missing requestId=$requestId " +
                        "identity=${identity.key}"
                )
                discardAnchor(identity, requestId)
                return Outcome.DraftCaptureFailed
            }
            val prepare =
                withTimeoutOrNull(NATIVE_ACTION_TIMEOUT_MILLIS) {
                    nativeAdaptiveNavigation(
                        ACTION_PREPARE,
                        requestId,
                        null,
                        preparedDraft.editorNativeRef.toString(),
                        preparedDraft.text,
                    )
                } ?: run {
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
                cancelDispatched = true
                runCatching { nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token.ifBlank { null }, null, null) }
                return Outcome.PrepareFailed
            }
            preparedToken = token
            var readyGenerationSeen: Long = 0L
            readySubscription =
                if (!awaitReadyBeforeCommit) {
                    null
                } else {
                    eventBus.observe(
                        eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
                        crossProcess = true,
                    ) { eventData ->
                        // READY carries only the identity key + generation and may arrive
                        // before our ack waiter resumes (the shell acks out of its own READY
                        // handler). Record the latest generation matching our identity; the
                        // ack path then compares and releases the deferred so an already-
                        // delivered READY is never lost.
                        val generation = eventData
                            ?.optDouble(SLOCK_ADAPTIVE_DETAIL_GENERATION, 0.0)?.toLong() ?: return@observe
                        val key = eventData.optString(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY)
                        if (key == identity.key) {
                            readyGenerationSeen = generation
                            if (ackedGeneration != 0L && generation == ackedGeneration) {
                                readyDeferred.complete(Unit)
                            }
                        }
                    }
                }
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
                // PREPARED binds controller tokens onto the pending entry OPEN just created;
                // posting it earlier gets dropped by markSlockAdaptiveDetailOpenPrepared.
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
                val ack =
                    withTimeoutOrNull(ackTimeoutMillis) { ackDeferred.await() }
                        ?: run {
                            logWarn(
                                "adaptive_initiate_ack_timeout requestId=$requestId " +
                                    "timeoutMs=$ackTimeoutMillis identity=${identity.key}"
                            )
                            discardAnchor(identity, requestId)
                            cancelDispatched = true
                            runCatching {
                                nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token, null, null)
                            }
                            return Outcome.AckTimeout
                        }
                ackedGeneration = ack.generation
                // Guard the sentinel: ack.generation parses to 0 when the ACK carried no
                // generation — 0==0 must not masquerade as a delivered READY.
                if (ackedGeneration != 0L && readyGenerationSeen == ackedGeneration) {
                    readyDeferred.complete(Unit)
                }
                if (!ack.accepted) {
                    // No auto-retry: the caller re-arms only on a new DualPane fact. The shell
                    // drops/discards its pending record on reject; mirror that for the anchor.
                    log(
                        "adaptive_initiate_rejected requestId=$requestId " +
                            "reason=${ack.reason} identity=${identity.key}"
                    )
                    discardAnchor(identity, requestId)
                    cancelDispatched = true
                    runCatching { nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token, null, null) }
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
                    cancelDispatched = true
                    runCatching {
                        nativeAdaptiveNavigation(ACTION_CANCEL, requestId, token, null, null)
                    }
                    return Outcome.ReadyTimeout
                }
            }
            // Pre-commit editor VERSION fence: re-capture the live editor + draft identity
            // and require an EXACT snapshot match — same text under a different draft key or
            // composer instance is drift, not a pass. A blind re-persist would silently
            // store keystrokes typed during ACK/READY while the destination already restored
            // the older draft — cross-page drift. On any mismatch or read failure, cancel
            // and keep the source page + the (newer) draft for a fresh attempt.
            val preCommitDraft = captureLatestDraft(requestId)
            if (preCommitDraft == null || preCommitDraft != preparedDraft) {
                logWarn(
                    "adaptive_initiate_precommit_drift requestId=$requestId " +
                        "readFailed=${preCommitDraft == null} " +
                        "drift=${preCommitDraft != preparedDraft} " +
                        "identity=${identity.key}"
                )
                cancelDispatched = true
                runCatching {
                    dispatchAdaptiveCancel(requestId, token)
                }
                return Outcome.DraftDrifted
            }
            // Commit is DISPATCH-ONLY with no source callback: iOS deliberately never invokes
            // one — the renderer may be torn down mid-pop — so there is no suspend point here
            // at all. The authoritative result is the slockAdaptiveNativeCommitResult event
            // on the target shell; the shell settles the pending entry and consumes the
            // staged anchor.
            commitDispatched = true
            runCatching {
                dispatchAdaptiveCommit(requestId, token)
            }.onFailure { error ->
                logWarn(
                    "adaptive_initiate_commit_dispatch_failed requestId=$requestId " +
                        "type=${error::class.simpleName} identity=${identity.key}"
                )
            }
            return Outcome.CommitDispatched
        } finally {
            readySubscription?.close()
            if (inFlightRequestId == requestId) inFlightRequestId = null
            // Coroutine-cancel mid-flight (pane event re-key, page dispose) must not leak a
            // prepared transaction: native commit expiry does not free the request slot, and
            // an unprepared caller's token would otherwise block a fresh prepare with
            // request_in_progress. Never cancel after commit dispatch — the authoritative
            // result then belongs to the target shell.
            val leakedToken = preparedToken
            if (leakedToken != null && !commitDispatched && !cancelDispatched) {
                withContext(NonCancellable) {
                    runCatching {
                        dispatchAdaptiveCancel(requestId, leakedToken)
                    }
                }
            }
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
        // Prepare/cancel replies are bridged suspends with no contract that a reply is
        // emitted for every request — bound them like ACK.
        const val NATIVE_ACTION_TIMEOUT_MILLIS = 10_000L
    }
}
