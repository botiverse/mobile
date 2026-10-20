package build.raft.app

import build.raft.app.data.PendingAdaptiveDetailOpen
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAdaptiveDetailOpenContractTest {

    @Test
    fun openRequestEnvelopeRoundTrips() {
        val request = request(requestId = "req-1", hostId = "pager-9")

        val parsed = request.toJson().toRaftAdaptiveDetailOpenRequestOrNull()

        assertNotNull(parsed)
        assertEquals("req-1", parsed.requestId)
        assertEquals("pager-9", parsed.hostId)
        assertEquals("shell-a", parsed.shellInstanceId)
        assertEquals("server-a", parsed.identity.serverId)
        assertEquals("stream-a", parsed.identity.threadId)
        assertEquals(2200, parsed.windowFact.widthPx)
        assertEquals(1000L, parsed.windowFact.capturedAtMillis)
        assertNotNull(parsed.decodeLaunchPayload())
    }

    @Test
    fun openRequestRejectsMalformedEnvelope() {
        // Blank launch payload must fail envelope validation rather than decode downstream.
        val malformed =
            request(requestId = "req-1")
                .toJson()
                .withEntry(RaftAdaptiveDetailOpenRequest.KEY_LAUNCH_PAYLOAD, " ")
        assertNull(malformed.toRaftAdaptiveDetailOpenRequestOrNull())
        // Invalid window facts are rejected the same way.
        val badFact =
            request(requestId = "req-1")
                .toJson()
                .withEntry(
                    RaftAdaptiveDetailOpenRequest.KEY_WINDOW_FACT,
                    JSONObject()
                        .put(RaftAdaptiveOpenWindowFact.KEY_WIDTH_PX, 0.0)
                        .put(RaftAdaptiveOpenWindowFact.KEY_HEIGHT_PX, 1200.0)
                        .put(RaftAdaptiveOpenWindowFact.KEY_DENSITY, 2.0)
                        .put(RaftAdaptiveOpenWindowFact.KEY_POSTURE, "flat")
                        .put(RaftAdaptiveOpenWindowFact.KEY_CAPTURED_AT_MS, 1000.0)
                        .toJsonObject(),
                )
        assertNull(badFact.toRaftAdaptiveDetailOpenRequestOrNull())
    }

    @Test
    fun windowFactFreshnessIsHostScopedThenWallClock() {
        // Same host: only non-older captures accepted.
        assertTrue(adaptiveWindowFactReplayAccepted("pager-1", 1000L, "pager-1", 1000L))
        assertTrue(adaptiveWindowFactReplayAccepted("pager-1", 1000L, "pager-1", 1200L))
        assertEquals(
            false,
            adaptiveWindowFactReplayAccepted("pager-1", 1200L, "pager-1", 1000L),
        )
        // Different host: sequence numbers are not comparable — wall clock orders.
        assertTrue(adaptiveWindowFactReplayAccepted("pager-1", 1000L, "pager-2", 1001L))
        assertEquals(
            false,
            adaptiveWindowFactReplayAccepted("pager-1", 1000L, "pager-2", 1000L),
        )
        assertEquals(
            false,
            adaptiveWindowFactReplayAccepted("pager-1", 1000L, "pager-2", 900L),
        )
    }

    @Test
    fun evaluateAcceptsAndRecordsPendingWithFreshDualPaneFact() {
        val session = sessionWithWorkspace(dualPane = false)

        val (next, decision) =
            evaluateRaftAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val accepted = decision as? RaftAdaptiveDetailOpenDecision.Accepted
            ?: error("expected accept, got $decision")
        assertTrue(accepted.generation > 0)
        assertEquals("stream-a", next.workspace?.selection?.identity?.threadId)
        assertNotNull(next.launchPayload)
        assertEquals(accepted.generation, next.pendingDetailOpens["req-1"]?.detailGeneration)
        assertEquals("pager-9", next.lastWindowFactHostId)
        assertEquals(1000L, next.lastWindowFactCapturedAtMillis)
    }

    @Test
    fun evaluateRejectsStaleWindowFactSoSupersededDualPaneCannotResurrect() {
        // Shell already applied a newer fact (window narrowed again after the source posted).
        val session =
            sessionWithWorkspace(dualPane = true).copy(
                lastWindowFactHostId = "pager-9",
                lastWindowFactCapturedAtMillis = 2000L,
            )

        val (next, decision) =
            evaluateRaftAdaptiveDetailOpen(
                request =
                    request(requestId = "req-1", hostId = "pager-9", capturedAtMillis = 1000L),
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val rejected = decision as? RaftAdaptiveDetailOpenDecision.Rejected
            ?: error("expected reject, got $decision")
        assertEquals(
            RaftAdaptiveDetailOpenRejectReason.STALE_WINDOW_FACT.wireName,
            rejected.reason,
        )
        assertTrue(next.pendingDetailOpens.isEmpty())
        assertNull(next.workspace?.selection)
    }

    @Test
    fun evaluateRejectsAuthorityMismatch() {
        val session = sessionWithWorkspace(dualPane = true)
        val request =
            request(requestId = "req-1", hostId = "pager-9").let {
                it.copy(
                    identity =
                        it.identity.copy(serverId = "server-b"),
                )
            }

        val (next, decision) =
            evaluateRaftAdaptiveDetailOpen(
                request = request,
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val rejected = decision as? RaftAdaptiveDetailOpenDecision.Rejected
            ?: error("expected reject, got $decision")
        assertEquals(
            RaftAdaptiveDetailOpenRejectReason.AUTHORITY_MISMATCH.wireName,
            rejected.reason,
        )
        assertTrue(next.pendingDetailOpens.isEmpty())
    }

    @Test
    fun evaluateDedupesSameRequestIdWithoutReReduce() {
        val session = sessionWithWorkspace(dualPane = true).copy(
            pendingDetailOpens =
                mapOf(
                    "req-1" to
                        PendingAdaptiveDetailOpen(
                            requestId = "req-1",
                            hostId = "pager-9",
                            identity = detailIdentity(),
                            detailGeneration = 7L,
                            windowFactCapturedAtMillis = 900L,
                        )
                )
        )

        val (next, decision) =
            evaluateRaftAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val accepted = decision as? RaftAdaptiveDetailOpenDecision.Accepted
            ?: error("expected idempotent accept, got $decision")
        assertEquals(7L, accepted.generation)
        // Same session object — no re-reduce, no new pending entry.
        assertTrue(next === session)
    }

    @Test
    fun evaluateRejectsWhenFlagOff() {
        val session = sessionWithWorkspace(dualPane = true)
        val (next, decision) =
            evaluateRaftAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = false,
                session = session,
                nativeGuardedCommitAvailable = true,
            )
        assertTrue(decision is RaftAdaptiveDetailOpenDecision.Rejected)
        assertTrue(next.pendingDetailOpens.isEmpty())
    }

    @Test
    fun evaluateRejectsWhenNativeGuardedCommitUnavailable() {
        // Kill-switch (task #18): until the native route-token-verified commit lands, the
        // migration path must stay inert even for an otherwise valid request.
        val session = sessionWithWorkspace(dualPane = true)
        val (next, decision) =
            evaluateRaftAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = session,
            )
        val rejected = decision as? RaftAdaptiveDetailOpenDecision.Rejected
            ?: error("expected reject, got $decision")
        assertEquals(
            RaftAdaptiveDetailOpenRejectReason.NATIVE_COMMIT_UNAVAILABLE.wireName,
            rejected.reason,
        )
        assertTrue(next === session)
    }

    @Test
    fun completeMarksPendingAcknowledgedAndKeepsIt() {
        val workspace =
            RaftAdaptiveWorkspaceState(shellInstanceId = "shell-a", serverId = "server-a").let {
                reduceRaftAdaptiveWorkspace(
                    it,
                    RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()),
                )
            }.let {
                reduceRaftAdaptiveWorkspace(
                    it,
                    RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity()),
                )
            }
        val generation = workspace.selection!!.generation
        val session =
            SharedAdaptiveWorkspaceSession(
                workspace = workspace,
                pendingDetailOpens =
                    mapOf(
                        "req-1" to
                            PendingAdaptiveDetailOpen(
                                requestId = "req-1",
                                hostId = "pager-9",
                                identity = detailIdentity(),
                                detailGeneration = generation,
                                windowFactCapturedAtMillis = 1000L,
                            )
                    ),
            )

        val (next, pending) =
            completeRaftAdaptiveDetailOpen(
                identityKey = detailIdentity().key,
                generation = generation,
                session = session,
            )

        assertEquals("req-1", pending?.requestId)
        // READY keeps the pending entry (marked acknowledged) — it settles on the native
        // commit receipt, not on READY.
        assertEquals(true, next.pendingDetailOpens["req-1"]?.acknowledged)
    }

    @Test
    fun completeIgnoresLateReadyForSupersededGeneration() {
        val workspace =
            RaftAdaptiveWorkspaceState(shellInstanceId = "shell-a", serverId = "server-a").let {
                reduceRaftAdaptiveWorkspace(
                    it,
                    RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()),
                )
            }.let {
                reduceRaftAdaptiveWorkspace(
                    it,
                    RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity()),
                )
            }
        val session =
            SharedAdaptiveWorkspaceSession(
                workspace = workspace,
                pendingDetailOpens =
                    mapOf(
                        "req-1" to
                            PendingAdaptiveDetailOpen(
                                requestId = "req-1",
                                hostId = "pager-9",
                                identity = detailIdentity(),
                                detailGeneration = workspace.selection!!.generation,
                                windowFactCapturedAtMillis = 1000L,
                            )
                    ),
            )

        val (next, pending) =
            completeRaftAdaptiveDetailOpen(
                identityKey = detailIdentity().key,
                generation = workspace.selection!!.generation + 99L,
                session = session,
            )

        assertNull(pending)
        assertTrue(next === session)
    }

    @Test
    fun nativeCommitResultParsesCommittedAndRejected() {
        val committed =
            JSONObject()
                .put("requestId", "req-1")
                .put("status", "committed")
                .put("reason", "")
                .put("sourceControllerToken", "src-1")
                .put("targetControllerToken", "tgt-1")
                .toJsonObject()
                .toRaftAdaptiveNativeCommitResultOrNull()
        assertNotNull(committed)
        assertTrue(committed.committed)
        assertEquals("src-1", committed.sourceControllerToken)
        val rejected =
            JSONObject()
                .put("requestId", "req-1")
                .put("status", "rejected")
                .put("reason", "stack_changed")
                .toJsonObject()
                .toRaftAdaptiveNativeCommitResultOrNull()
        assertNotNull(rejected)
        assertTrue(!rejected.committed)
        assertNull(JSONObject().put("status", "committed").toJsonObject().toRaftAdaptiveNativeCommitResultOrNull())
    }

    private fun commitResult(
        requestId: String,
        status: String = "committed",
        sourceToken: String = "src-1",
        targetToken: String = "tgt-1",
    ) = RaftAdaptiveNativeCommitResult(
        requestId = requestId,
        status = status,
        reason = "",
        sourceControllerToken = sourceToken,
        targetControllerToken = targetToken,
    )

    @Test
    fun settleResolvesOnlyAcknowledgedPendingWithMatchingTokens() {
        val pending =
            PendingAdaptiveDetailOpen(
                requestId = "req-1",
                hostId = "pager-9",
                identity = detailIdentity(),
                detailGeneration = 7L,
                windowFactCapturedAtMillis = 1000L,
                sourceControllerToken = "src-1",
                targetControllerToken = "tgt-1",
            )
        val workspaceSession =
            sessionWithWorkspace(dualPane = true).copy(
                pendingDetailOpens = mapOf("req-1" to pending),
            )
        // Pre-READY receipt must not settle.
        val (unsettled, ignored) =
            settleRaftAdaptiveDetailOpen(commitResult("req-1"), workspaceSession)
        assertNull(ignored)
        assertTrue(unsettled === workspaceSession)
        // Acknowledged pending with matching tokens settles.
        val acked =
            workspaceSession.copy(
                pendingDetailOpens = mapOf("req-1" to pending.copy(acknowledged = true)),
            )
        val (settled, resolved) =
            settleRaftAdaptiveDetailOpen(commitResult("req-1"), acked)
        assertEquals("req-1", resolved?.requestId)
        assertTrue(settled.pendingDetailOpens.isEmpty())
        // Foreign requestId never matches.
        val (other, none) = settleRaftAdaptiveDetailOpen(commitResult("req-9"), acked)
        assertNull(none)
        assertTrue(other === acked)
        // Token mismatch (stray receipt for a different commit) does not settle.
        val (mismatch, stray) =
            settleRaftAdaptiveDetailOpen(
                commitResult("req-1", sourceToken = "src-9"),
                acked,
            )
        assertNull(stray)
        assertTrue(mismatch === acked)
        // Without a prepared binding the pending cannot settle on a receipt.
        val unbound =
            acked.copy(
                pendingDetailOpens =
                    mapOf("req-1" to pending.copy(acknowledged = true, sourceControllerToken = null)),
            )
        val (noBind, noSettle) = settleRaftAdaptiveDetailOpen(commitResult("req-1"), unbound)
        assertNull(noSettle)
        assertTrue(noBind === unbound)
    }

    @Test
    fun preparedEventBindsControllerTokensToPending() {
        val pending =
            PendingAdaptiveDetailOpen(
                requestId = "req-1",
                hostId = "pager-9",
                identity = detailIdentity(),
                detailGeneration = 7L,
                windowFactCapturedAtMillis = 1000L,
            )
        val session =
            SharedAdaptiveWorkspaceSession(
                pendingDetailOpens = mapOf("req-1" to pending),
            )
        val bound =
            markRaftAdaptiveDetailOpenPrepared(
                requestId = "req-1",
                sourceControllerToken = "src-1",
                targetControllerToken = "tgt-1",
                session = session,
            )
        assertEquals("src-1", bound.pendingDetailOpens["req-1"]?.sourceControllerToken)
        assertEquals("tgt-1", bound.pendingDetailOpens["req-1"]?.targetControllerToken)
        // Unknown requestId and blank tokens are no-ops.
        assertTrue(
            markRaftAdaptiveDetailOpenPrepared("req-9", "a", "b", session) === session,
        )
        assertTrue(
            markRaftAdaptiveDetailOpenPrepared("req-1", "", "b", session) === session,
        )
    }

    @Test
    fun cancelDropRemovesExactPendingAndKeepsRetry() {
        // task #18 hardening: timeout/cancel drops by requestId — a retry of the same identity
        // under a new requestId must survive the drop of the stale one.
        val stalePending =
            PendingAdaptiveDetailOpen(
                requestId = "req-1",
                hostId = "pager-9",
                identity = detailIdentity(),
                detailGeneration = 7L,
                windowFactCapturedAtMillis = 1000L,
            )
        val retryPending = stalePending.copy(requestId = "req-2", detailGeneration = 8L)
        val session =
            SharedAdaptiveWorkspaceSession(
                pendingDetailOpens =
                    mapOf("req-1" to stalePending, "req-2" to retryPending),
            )

        val dropped = dropRaftAdaptiveDetailOpen("req-1", session)
        assertNull(dropped.pendingDetailOpens["req-1"])
        assertEquals(retryPending, dropped.pendingDetailOpens["req-2"])
        // Unknown requestId is a no-op returning the same session.
        assertTrue(dropRaftAdaptiveDetailOpen("req-9", session) === session)
    }

    @Test
    fun cancelDropRemovesAcknowledgedPendingBeforeCommitReceipt() {
        // SangLan review point: ACK sent does not mean commit dispatched — draft_drifted/aborted
        // exits after the accepted ack leave no commit receipt to settle the entry, so the drop
        // must apply to acknowledged pendings too. Conversely, a REAL commit dispatch suppresses
        // the cancel path entirely, so no cancel event can target an in-flight commit.
        val acknowledgedPending =
            PendingAdaptiveDetailOpen(
                requestId = "req-ack",
                hostId = "pager-9",
                identity = detailIdentity(),
                detailGeneration = 7L,
                windowFactCapturedAtMillis = 1000L,
                acknowledged = true,
                sourceControllerToken = "src-1",
                targetControllerToken = "tgt-1",
            )
        val session =
            SharedAdaptiveWorkspaceSession(
                pendingDetailOpens = mapOf("req-ack" to acknowledgedPending),
            )

        val dropped = dropRaftAdaptiveDetailOpen("req-ack", session)
        assertTrue(dropped.pendingDetailOpens.isEmpty())
    }

    @Test
    fun lateReadyAfterCancelDropCannotAckStaleGeneration() {
        // Regression for the 2026-09-28 device timeline: OPEN accepted at T0, source times out
        // at T1 and posts cancel, the destination's READY lands at T2 — after the drop the
        // pending is gone so completeRaftAdaptiveDetailOpen resolves to null (shell then logs
        // ready_no_pending and sends NO accepted ack).
        val workspace =
            RaftAdaptiveWorkspaceState(shellInstanceId = "shell-a", serverId = "server-a").let {
                reduceRaftAdaptiveWorkspace(
                    it,
                    RaftAdaptiveWorkspaceIntent.WindowChanged(widePane()),
                )
            }.let {
                reduceRaftAdaptiveWorkspace(
                    it,
                    RaftAdaptiveWorkspaceIntent.OpenDetail(detailIdentity()),
                )
            }
        val generation = workspace.selection!!.generation
        val pending =
            PendingAdaptiveDetailOpen(
                requestId = "req-1",
                hostId = "pager-9",
                identity = detailIdentity(),
                detailGeneration = generation,
                windowFactCapturedAtMillis = 1000L,
            )
        val accepted =
            SharedAdaptiveWorkspaceSession(
                workspace = workspace,
                pendingDetailOpens = mapOf("req-1" to pending),
            )

        // Cancel arrives before READY: pending dropped, late READY ignored.
        val cancelled = dropRaftAdaptiveDetailOpen("req-1", accepted)
        val (afterLateReady, latePending) =
            completeRaftAdaptiveDetailOpen(
                identityKey = detailIdentity().key,
                generation = generation,
                session = cancelled,
            )
        assertNull(latePending)
        assertTrue(afterLateReady.pendingDetailOpens.isEmpty())

        // Retry under a new requestId: the stale READY (same generation) still cannot resolve —
        // the retry's pending carries a different generation and remains for its own READY.
        val retryPendingEntry =
            pending.copy(requestId = "req-2", detailGeneration = generation + 1)
        val retried =
            cancelled.copy(pendingDetailOpens = mapOf("req-2" to retryPendingEntry))
        val (afterStaleReady, stalePending) =
            completeRaftAdaptiveDetailOpen(
                identityKey = detailIdentity().key,
                generation = generation,
                session = retried,
            )
        assertNull(stalePending)
        assertEquals(
            retryPendingEntry,
            afterStaleReady.pendingDetailOpens["req-2"],
        )
    }

    @Test
    fun retryAfterCancelMintsFreshGenerationSoStaleReadyCannotMatch() {
        // Jony's v12 counterexample: same-identity retry reused generation 1, so the dead
        // attempt's delayed READY (same identity, same generation) completed the new pending
        // and emitted a stray accepted ACK -> CommitDispatched. The fix mints a new generation
        // whenever the selection is orphaned (no pending still owns its generation).
        val first =
            evaluateRaftAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = sessionWithWorkspace(dualPane = false),
                nativeGuardedCommitAvailable = true,
            )
        val firstGen =
            (first.second as? RaftAdaptiveDetailOpenDecision.Accepted)?.generation
                ?: error("first request must be accepted")
        // Cancel drops the pending but the workspace selection for that identity survives.
        val cancelled = dropRaftAdaptiveDetailOpen("req-1", first.first)
        assertNull(cancelled.pendingDetailOpens["req-1"])
        assertEquals(
            detailIdentity(),
            cancelled.workspace?.selection?.identity,
        )

        val second =
            evaluateRaftAdaptiveDetailOpen(
                request =
                    request(requestId = "req-2", hostId = "pager-9", capturedAtMillis = 2000L),
                workspaceEnabled = true,
                session = cancelled,
                nativeGuardedCommitAvailable = true,
            )
        val secondGen =
            (second.second as? RaftAdaptiveDetailOpenDecision.Accepted)?.generation
                ?: error("retry must be accepted")
        assertTrue(
            secondGen > firstGen,
            "retry must mint a fresh generation (got $secondGen after $firstGen)",
        )
        assertEquals(secondGen, second.first.pendingDetailOpens["req-2"]?.detailGeneration)

        // The stale READY (identity + firstGen) resolves nothing now.
        val (afterStaleReady, stalePending) =
            completeRaftAdaptiveDetailOpen(
                identityKey = detailIdentity().key,
                generation = firstGen,
                session = second.first,
            )
        assertNull(stalePending)
        assertEquals(
            "req-2",
            afterStaleReady.pendingDetailOpens["req-2"]?.requestId,
        )
    }

    @Test
    fun sameRequestIdRedeliveryKeepsOriginalGeneration() {
        // Dedupe: replayed requestId answers with the recorded pending generation, never
        // re-reduces — the selection reuse guard must not fire on this path.
        val first =
            evaluateRaftAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = sessionWithWorkspace(dualPane = false),
                nativeGuardedCommitAvailable = true,
            )
        val firstGen =
            (first.second as? RaftAdaptiveDetailOpenDecision.Accepted)?.generation
                ?: error("first request must be accepted")

        val second =
            evaluateRaftAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = first.first,
                nativeGuardedCommitAvailable = true,
            )
        assertEquals(
            firstGen,
            (second.second as? RaftAdaptiveDetailOpenDecision.Accepted)?.generation,
        )
    }

    private fun request(
        requestId: String,
        hostId: String = "pager-1",
        capturedAtMillis: Long = 1000L,
    ): RaftAdaptiveDetailOpenRequest {
        val payload =
            WireThreadLaunchPayload(
                instanceId = "instance-a",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                sourceThreadId = "stream-a",
                streamChannelId = "stream-a",
            )
        return RaftAdaptiveDetailOpenRequest(
            shellInstanceId = "shell-a",
            requestId = requestId,
            hostId = hostId,
            identity = detailIdentity(),
            launchPayloadJson = payload.encodeForAdaptiveDetailOpen(),
            windowFact =
                RaftAdaptiveOpenWindowFact(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = "flat",
                    capturedAtMillis = capturedAtMillis,
                ),
        )
    }

    private fun detailIdentity(): RaftAdaptiveDetailIdentity =
        RaftAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = "stream-a",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelId = "channel-stream-a",
        )

    private fun widePane(): RaftAdaptivePaneState =
        RaftAdaptivePaneState(
            widthPx = 2200,
            heightPx = 1200,
            density = 2f,
            posture = RaftAdaptivePanePosture.Flat,
            layout = RaftAdaptivePaneLayout.DualPane,
        )

    private fun sessionWithWorkspace(dualPane: Boolean): SharedAdaptiveWorkspaceSession {
        val pane =
            if (dualPane) widePane() else RaftAdaptivePaneState()
        val workspace =
            reduceRaftAdaptiveWorkspace(
                RaftAdaptiveWorkspaceState(shellInstanceId = "shell-a", serverId = "server-a"),
                RaftAdaptiveWorkspaceIntent.WindowChanged(pane),
            )
        return SharedAdaptiveWorkspaceSession(workspace = workspace)
    }
}
