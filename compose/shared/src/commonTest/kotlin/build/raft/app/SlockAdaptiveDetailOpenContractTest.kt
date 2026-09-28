package build.raft.app

import build.raft.app.data.PendingAdaptiveDetailOpen
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import build.raft.app.data.SharedAdaptiveWorkspaceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockAdaptiveDetailOpenContractTest {

    @Test
    fun openRequestEnvelopeRoundTrips() {
        val request = request(requestId = "req-1", hostId = "pager-9")

        val parsed = request.toJson().toSlockAdaptiveDetailOpenRequestOrNull()

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
                .put(SlockAdaptiveDetailOpenRequest.KEY_LAUNCH_PAYLOAD, " ")
        assertNull(malformed.toSlockAdaptiveDetailOpenRequestOrNull())
        // Invalid window facts are rejected the same way.
        val badFact =
            request(requestId = "req-1")
                .toJson()
                .put(
                    SlockAdaptiveDetailOpenRequest.KEY_WINDOW_FACT,
                    JSONObject()
                        .put(SlockAdaptiveOpenWindowFact.KEY_WIDTH_PX, 0.0)
                        .put(SlockAdaptiveOpenWindowFact.KEY_HEIGHT_PX, 1200.0)
                        .put(SlockAdaptiveOpenWindowFact.KEY_DENSITY, 2.0)
                        .put(SlockAdaptiveOpenWindowFact.KEY_POSTURE, "flat")
                        .put(SlockAdaptiveOpenWindowFact.KEY_CAPTURED_AT_MS, 1000.0),
                )
        assertNull(badFact.toSlockAdaptiveDetailOpenRequestOrNull())
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
            evaluateSlockAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val accepted = decision as? SlockAdaptiveDetailOpenDecision.Accepted
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
            evaluateSlockAdaptiveDetailOpen(
                request =
                    request(requestId = "req-1", hostId = "pager-9", capturedAtMillis = 1000L),
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val rejected = decision as? SlockAdaptiveDetailOpenDecision.Rejected
            ?: error("expected reject, got $decision")
        assertEquals(
            SlockAdaptiveDetailOpenRejectReason.STALE_WINDOW_FACT.wireName,
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
            evaluateSlockAdaptiveDetailOpen(
                request = request,
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val rejected = decision as? SlockAdaptiveDetailOpenDecision.Rejected
            ?: error("expected reject, got $decision")
        assertEquals(
            SlockAdaptiveDetailOpenRejectReason.AUTHORITY_MISMATCH.wireName,
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
            evaluateSlockAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = session,
                nativeGuardedCommitAvailable = true,
            )

        val accepted = decision as? SlockAdaptiveDetailOpenDecision.Accepted
            ?: error("expected idempotent accept, got $decision")
        assertEquals(7L, accepted.generation)
        // Same session object — no re-reduce, no new pending entry.
        assertTrue(next === session)
    }

    @Test
    fun evaluateRejectsWhenFlagOff() {
        val session = sessionWithWorkspace(dualPane = true)
        val (next, decision) =
            evaluateSlockAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = false,
                session = session,
                nativeGuardedCommitAvailable = true,
            )
        assertTrue(decision is SlockAdaptiveDetailOpenDecision.Rejected)
        assertTrue(next.pendingDetailOpens.isEmpty())
    }

    @Test
    fun evaluateRejectsWhenNativeGuardedCommitUnavailable() {
        // Kill-switch (task #18): until the native route-token-verified commit lands, the
        // migration path must stay inert even for an otherwise valid request.
        val session = sessionWithWorkspace(dualPane = true)
        val (next, decision) =
            evaluateSlockAdaptiveDetailOpen(
                request = request(requestId = "req-1", hostId = "pager-9"),
                workspaceEnabled = true,
                session = session,
            )
        val rejected = decision as? SlockAdaptiveDetailOpenDecision.Rejected
            ?: error("expected reject, got $decision")
        assertEquals(
            SlockAdaptiveDetailOpenRejectReason.NATIVE_COMMIT_UNAVAILABLE.wireName,
            rejected.reason,
        )
        assertTrue(next === session)
    }

    @Test
    fun completeMarksPendingAcknowledgedAndKeepsIt() {
        val workspace =
            SlockAdaptiveWorkspaceState(shellInstanceId = "shell-a", serverId = "server-a").let {
                reduceSlockAdaptiveWorkspace(
                    it,
                    SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()),
                )
            }.let {
                reduceSlockAdaptiveWorkspace(
                    it,
                    SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity()),
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
            completeSlockAdaptiveDetailOpen(
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
            SlockAdaptiveWorkspaceState(shellInstanceId = "shell-a", serverId = "server-a").let {
                reduceSlockAdaptiveWorkspace(
                    it,
                    SlockAdaptiveWorkspaceIntent.WindowChanged(widePane()),
                )
            }.let {
                reduceSlockAdaptiveWorkspace(
                    it,
                    SlockAdaptiveWorkspaceIntent.OpenDetail(detailIdentity()),
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
            completeSlockAdaptiveDetailOpen(
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
                .toSlockAdaptiveNativeCommitResultOrNull()
        assertNotNull(committed)
        assertTrue(committed.committed)
        assertEquals("src-1", committed.sourceControllerToken)
        val rejected =
            JSONObject()
                .put("requestId", "req-1")
                .put("status", "rejected")
                .put("reason", "stack_changed")
                .toSlockAdaptiveNativeCommitResultOrNull()
        assertNotNull(rejected)
        assertTrue(!rejected.committed)
        assertNull(JSONObject().put("status", "committed").toSlockAdaptiveNativeCommitResultOrNull())
    }

    private fun commitResult(
        requestId: String,
        status: String = "committed",
        sourceToken: String = "src-1",
        targetToken: String = "tgt-1",
    ) = SlockAdaptiveNativeCommitResult(
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
            settleSlockAdaptiveDetailOpen(commitResult("req-1"), workspaceSession)
        assertNull(ignored)
        assertTrue(unsettled === workspaceSession)
        // Acknowledged pending with matching tokens settles.
        val acked =
            workspaceSession.copy(
                pendingDetailOpens = mapOf("req-1" to pending.copy(acknowledged = true)),
            )
        val (settled, resolved) =
            settleSlockAdaptiveDetailOpen(commitResult("req-1"), acked)
        assertEquals("req-1", resolved?.requestId)
        assertTrue(settled.pendingDetailOpens.isEmpty())
        // Foreign requestId never matches.
        val (other, none) = settleSlockAdaptiveDetailOpen(commitResult("req-9"), acked)
        assertNull(none)
        assertTrue(other === acked)
        // Token mismatch (stray receipt for a different commit) does not settle.
        val (mismatch, stray) =
            settleSlockAdaptiveDetailOpen(
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
        val (noBind, noSettle) = settleSlockAdaptiveDetailOpen(commitResult("req-1"), unbound)
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
            markSlockAdaptiveDetailOpenPrepared(
                requestId = "req-1",
                sourceControllerToken = "src-1",
                targetControllerToken = "tgt-1",
                session = session,
            )
        assertEquals("src-1", bound.pendingDetailOpens["req-1"]?.sourceControllerToken)
        assertEquals("tgt-1", bound.pendingDetailOpens["req-1"]?.targetControllerToken)
        // Unknown requestId and blank tokens are no-ops.
        assertTrue(
            markSlockAdaptiveDetailOpenPrepared("req-9", "a", "b", session) === session,
        )
        assertTrue(
            markSlockAdaptiveDetailOpenPrepared("req-1", "", "b", session) === session,
        )
    }

    private fun request(
        requestId: String,
        hostId: String = "pager-1",
        capturedAtMillis: Long = 1000L,
    ): SlockAdaptiveDetailOpenRequest {
        val payload =
            WireThreadLaunchPayload(
                instanceId = "instance-a",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = "stream-a",
                streamChannelId = "stream-a",
            )
        return SlockAdaptiveDetailOpenRequest(
            shellInstanceId = "shell-a",
            requestId = requestId,
            hostId = hostId,
            identity = detailIdentity(),
            launchPayloadJson = payload.encodeForAdaptiveDetailOpen(),
            windowFact =
                SlockAdaptiveOpenWindowFact(
                    widthPx = 2200,
                    heightPx = 1200,
                    density = 2f,
                    posture = "flat",
                    capturedAtMillis = capturedAtMillis,
                ),
        )
    }

    private fun detailIdentity(): SlockAdaptiveDetailIdentity =
        SlockAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = "stream-a",
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            channelId = "channel-stream-a",
        )

    private fun widePane(): SlockAdaptivePaneState =
        SlockAdaptivePaneState(
            widthPx = 2200,
            heightPx = 1200,
            density = 2f,
            posture = SlockAdaptivePanePosture.Flat,
            layout = SlockAdaptivePaneLayout.DualPane,
        )

    private fun sessionWithWorkspace(dualPane: Boolean): SharedAdaptiveWorkspaceSession {
        val pane =
            if (dualPane) widePane() else SlockAdaptivePaneState()
        val workspace =
            reduceSlockAdaptiveWorkspace(
                SlockAdaptiveWorkspaceState(shellInstanceId = "shell-a", serverId = "server-a"),
                SlockAdaptiveWorkspaceIntent.WindowChanged(pane),
            )
        return SharedAdaptiveWorkspaceSession(workspace = workspace)
    }
}
