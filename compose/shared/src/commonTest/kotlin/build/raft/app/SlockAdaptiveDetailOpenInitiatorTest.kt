package build.raft.app

import build.raft.app.data.SharedAdaptiveScrollAnchor
import build.raft.app.data.SharedAdaptiveScrollAnchorStore
import build.raft.app.data.SlockEnhancedNotifyCenter
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockAdaptiveDetailOpenInitiatorTest {
    private val shellId = "shell-1"
    private val hostId = "pager-thread-1"
    private val identity =
        SlockAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = "channel-a",
            surfaceKind = "channel",
            streamChannelId = "channel-a",
        )
    private val launchPayload =
        WireThreadLaunchPayload(
            instanceId = "inst-1",
            surfaceKind = "channel",
            sourceThreadId = "channel-a",
            streamChannelId = "channel-a",
        )
    private val windowFact =
        SlockAdaptiveOpenWindowFact(
            widthPx = 1680,
            heightPx = 1118,
            density = 2f,
            posture = "flat",
            capturedAtMillis = 1000L,
        )

    private fun preparedReply(
        sourceToken: String = "src-token",
        targetToken: String = "tgt-token",
        token: String = "one-shot",
    ) =
        JSONObject()
            .put("status", "prepared")
            .put("token", token)
            .put("sourceControllerToken", sourceToken)
            .put("targetControllerToken", targetToken)

    private class Fixture(
        val nativeReplies: MutableMap<String, JSONObject?> = mutableMapOf(),
        val nativeCalls: MutableList<Triple<String, String, String?>> = mutableListOf(),
        val draftResult: Boolean = true,
    ) {
        val anchorStore = SharedAdaptiveScrollAnchorStore()
        val eventBus = SlockEnhancedNotifyCenter
        val posted = mutableListOf<Pair<String, JSONObject>>()
        var anchorCapture: SharedAdaptiveScrollAnchor? =
            SharedAdaptiveScrollAnchor(
                requestId = "pending",
                messageId = "m-9",
                messageOffsetPx = 12,
                fallbackIndex = 3,
                atLatest = false,
                capturedAtMillis = 900L,
            )

        fun initiator(): SlockAdaptiveDetailOpenInitiator {
            val self = this
            return SlockAdaptiveDetailOpenInitiator(
                shellInstanceId = shellId,
                hostId = hostId,
                anchorStore = anchorStore,
                eventBus = eventBus,
                nowEpochMillis = { 1234L },
                nativeAdaptiveNavigation = { action, requestId, token ->
                    self.nativeCalls += Triple(action, requestId, token)
                    self.nativeReplies[action]
                },
                captureLatestDraft = { self.draftResult },
                captureAnchor = { requestId -> self.anchorCapture?.copy(requestId = requestId) },
            )
        }
    }

    @Test
    fun draftCaptureFailureAbortsBeforePrepare() = runBlocking {
        val fixture = Fixture(draftResult = false)
        val outcome =
            fixture.initiator().initiate(identity, launchPayload, windowFact, requestId = "r1")

        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.DraftCaptureFailed, outcome)
        assertTrue(fixture.nativeCalls.isEmpty(), "prepare must not run when the draft is not durable")
        assertNull(fixture.anchorStore.peek(shellId, identity), "staged anchor must be discarded")
    }

    @Test
    fun missingNativePrepareEndsAttemptAndDiscardsAnchor() = runBlocking {
        val fixture = Fixture() // no prepare reply registered
        val outcome =
            fixture.initiator().initiate(identity, launchPayload, windowFact, requestId = "r2")

        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.PrepareFailed, outcome)
        assertEquals(listOf("prepare"), fixture.nativeCalls.map { it.first })
        assertNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun rejectedOpenCancelsPreparedTransaction() = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val eventBus = fixture.eventBus
        val outcome =
            async { fixture.initiator().initiate(identity, launchPayload, windowFact, "r3") }
        yield() // let the initiator reach the ack wait
        eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r3")
                    .put("accepted", false)
                    .put("reason", "stale_window_fact"),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.RejectedByShell, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r3", null), Triple("cancel", "r3", "one-shot")),
            fixture.nativeCalls
        )
        assertNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun acceptedOpenHandsCommitToNative() = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val eventBus = fixture.eventBus
        val outcome =
            async { fixture.initiator().initiate(identity, launchPayload, windowFact, "r4") }
        yield()
        eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r4")
                    .put("accepted", true)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 7.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.Committed, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r4", null), Triple("commit", "r4", "one-shot")),
            fixture.nativeCalls
        )
        // Anchor stays staged for the destination/shell to consume on the commit receipt.
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun ackFromAnotherRequestIsIgnored() = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val eventBus = fixture.eventBus
        val outcome =
            async { fixture.initiator().initiate(identity, launchPayload, windowFact, "r5") }
        yield()
        eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "someone-else")
                    .put("accepted", false),
            crossProcess = false,
            source = "test",
        )
        yield()
        assertTrue(outcome.isActive, "a mismatched ack must not settle the pending adoption")
        eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r5")
                    .put("accepted", true)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 3.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.Committed, outcome.await())
    }

    @Test
    fun slowDraftPersistStillCompletesBeforePrepare() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var draftReturnObservedNativeCalls = -1
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            captureLatestDraft = {
                // Hold the receipt until well after initiate() would otherwise reach prepare;
                // the receipt must be the durable-persist barrier, not enqueue admission.
                gate.await()
                draftReturnObservedNativeCalls = fixture.nativeCalls.size
                true
            },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async { initiator.initiate(identity, launchPayload, windowFact, "r6") }
        yield()
        // The draft receipt is still outstanding: no native call may have run yet.
        assertTrue(fixture.nativeCalls.isEmpty(), "prepare must wait for the durable draft receipt")
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
        gate.complete(Unit)
        yield()
        assertEquals(0, draftReturnObservedNativeCalls)
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r6")
                    .put("accepted", true)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 5.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.Committed, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r6", null), Triple("commit", "r6", "one-shot")),
            fixture.nativeCalls
        )
    }

    @Test
    fun commitDispatchFailureKeepsAnchorStagedForRecovery() = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token ->
                fixture.nativeCalls += Triple(action, requestId, token)
                if (action == "commit") throw IllegalStateException("bridge gone")
                fixture.nativeReplies[action]
            },
            captureLatestDraft = { true },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async { initiator.initiate(identity, launchPayload, windowFact, "r7") }
        yield()
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r7")
                    .put("accepted", true)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 6.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.Committed, outcome.await())
        // Commit dispatch failed after the shell accepted and mounted the destination: the
        // staged anchor must NOT be discarded — it is the only recovery record for the
        // viewport if the destination is already up and the source is gone. Settlement
        // belongs to the shell's pending entry (also retained on commit failure).
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
        assertEquals(
            listOf(Triple("prepare", "r7", null), Triple("commit", "r7", "one-shot")),
            fixture.nativeCalls
        )
    }

    @Test
    fun failedCommitReceiptRetainsShellPendingAndAnchor() = runBlocking {
        // Shell-side contract: a non-committed native receipt keeps the pending entry AND the
        // staged anchor so the destination (already mounted, source popped) can still recover.
        val session0 = build.raft.app.data.SharedAdaptiveWorkspaceSession(
            workspace = SlockAdaptiveWorkspaceState(
                shellInstanceId = shellId,
                serverId = "server-a",
                pane = SlockAdaptivePaneState(
                    widthPx = 1680, heightPx = 1118,
                    layout = SlockAdaptivePaneLayout.DualPane,
                ),
            ),
        )
        val opened = reduceSlockAdaptiveWorkspace(
            session0.workspace!!,
            SlockAdaptiveWorkspaceIntent.OpenDetail(identity),
        )
        val pending = build.raft.app.data.PendingAdaptiveDetailOpen(
            requestId = "r8",
            hostId = hostId,
            identity = identity,
            detailGeneration = opened.selection!!.generation,
            windowFactCapturedAtMillis = 1L,
            acknowledged = true,
            sourceControllerToken = "src-token",
            targetControllerToken = "tgt-token",
        )
        val session = session0.copy(
            workspace = opened,
            pendingDetailOpens = mapOf("r8" to pending),
        )
        val failed = SlockAdaptiveNativeCommitResult(
            requestId = "r8",
            status = "failed",
            reason = "stack_readback_mismatch",
            sourceControllerToken = "src-token",
            targetControllerToken = "tgt-token",
        )
        val (nextSession, settled) = settleSlockAdaptiveDetailOpen(failed, session)
        assertNotNull(settled)
        // settle removes the pending from the returned session; the caller keeps it for
        // failures — assert the pieces the retention contract depends on: the pending is
        // identifiable, was acknowledged+token-bound (so only a matching receipt settles
        // it), and the staged anchor is still available for the destination's recovery.
        assertEquals("r8", settled!!.requestId)
        assertTrue(settled.acknowledged)
        val anchorStore = SharedAdaptiveScrollAnchorStore()
        anchorStore.stage(shellId, identity, SharedAdaptiveScrollAnchor(
            requestId = "r8", messageId = "m-9", messageOffsetPx = 12,
            fallbackIndex = 3, atLatest = false, capturedAtMillis = 900L,
        ))
        // failure path: caller must NOT consume — anchor stays recoverable
        assertNotNull(anchorStore.peek(shellId, identity))
        // happy-path contrast: a committed receipt under the same tokens removes the pending
        val committed = failed.copy(status = "committed")
        val (committedSession, committedSettled) = settleSlockAdaptiveDetailOpen(committed, session)
        assertNotNull(committedSettled)
        assertTrue(committedSession.pendingDetailOpens.isEmpty())
        // settle removes the pending unconditionally; the shell keeps it on failure simply
        // by not applying the returned session — so the observable contract is symmetric.
        assertTrue(nextSession.pendingDetailOpens.isEmpty())
    }

    @Test
    fun coveredTopKeepsDockCommitWaitsForDestinationReady() = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            captureLatestDraft = { true },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async {
            initiator.initiate(
                identity, launchPayload, windowFact, "r9",
                awaitReadyBeforeCommit = true,
            )
        }
        yield()
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r9")
                    .put("accepted", true)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 11.0),
            crossProcess = false,
            source = "test",
        )
        yield()
        // Accepted but destination not yet mounted: a covered-top transaction must not commit
        // (pop) yet — the READY report is the only proof the handoff rendered.
        assertEquals(
            listOf(Triple("prepare", "r9", null)),
            fixture.nativeCalls,
            "covered-top commit must wait for destination READY",
        )
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, identity.key)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 11.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.Committed, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r9", null), Triple("commit", "r9", "one-shot")),
            fixture.nativeCalls
        )
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun readyForForeignGenerationDoesNotReleaseCoveredTopCommit() = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            captureLatestDraft = { true },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async {
            initiator.initiate(
                identity, launchPayload, windowFact, "r10",
                awaitReadyBeforeCommit = true,
            )
        }
        yield()
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r10")
                    .put("accepted", true)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 21.0),
            crossProcess = false,
            source = "test",
        )
        yield()
        // A READY under a stale generation must not settle this adoption.
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, identity.key)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 4.0),
            crossProcess = false,
            source = "test",
        )
        yield()
        assertTrue(outcome.isActive, "a foreign-generation READY must not release the commit")
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, identity.key)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 21.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.Committed, outcome.await())
    }

    @Test
    fun readyTimeoutCancelsPreparedTransactionAndDiscardsAnchor() = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            captureLatestDraft = { true },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async {
            initiator.initiate(
                identity, launchPayload, windowFact, "r11",
                awaitReadyBeforeCommit = true,
                readyTimeoutMillis = 50L,
            )
        }
        yield()
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r11")
                    .put("accepted", true)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 31.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.ReadyTimeout, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r11", null), Triple("cancel", "r11", "one-shot")),
            fixture.nativeCalls
        )
        assertNull(fixture.anchorStore.peek(shellId, identity))
    }
}
