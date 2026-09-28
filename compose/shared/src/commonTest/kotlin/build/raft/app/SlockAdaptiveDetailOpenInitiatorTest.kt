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

internal class SlockAdaptiveDetailOpenInitiatorTest {
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
            .put(
                "context",
                JSONObject()
                    .put("sourceControllerToken", sourceToken)
                    .put("targetControllerToken", targetToken),
            )

    private inner class Fixture(
        val nativeReplies: MutableMap<String, JSONObject?> = mutableMapOf(),
        val nativeCalls: MutableList<Triple<String, String, String?>> = mutableListOf(),
        val commitDispatches: MutableList<Pair<String, String?>> = mutableListOf(),
        // Snapshots returned by successive captureLatestDraft calls; null = capture failure.
        val draftResults: MutableList<SharedThreadComposerDraftSnapshot?> =
            mutableListOf(SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42)),
        val cancelDispatches: MutableList<Pair<String, String?>> = mutableListOf(),
    ) {
        var draftCaptureCount = 0
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
                nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                    self.nativeCalls += Triple(action, requestId, token)
                    self.nativeReplies[action]
                },
                dispatchAdaptiveCommit = { requestId, token ->
                    self.nativeCalls += Triple("commit", requestId, token)
                    self.commitDispatches += requestId to token
                },
                dispatchAdaptiveCancel = { requestId, token ->
                    self.nativeCalls += Triple("cancel", requestId, token)
                    self.cancelDispatches += requestId to token
                },
                captureLatestDraft = {
                    // Repeat the last supplied text for calls beyond the list — the pre-commit
                    // version fence always re-reads once more than tests explicitly stage.
                    val index = self.draftCaptureCount++
                    self.draftResults.getOrNull(index) ?: self.draftResults.lastOrNull()
                },
                captureAnchor = { requestId -> self.anchorCapture?.copy(requestId = requestId) },
            )
        }
    }

    @Test
    fun draftCaptureFailureAbortsBeforePrepare(): Unit = runBlocking {
        val fixture = Fixture(draftResults = mutableListOf(null))
        val outcome =
            fixture.initiator().initiate(identity, launchPayload, windowFact, requestId = "r1")

        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.DraftCaptureFailed, outcome)
        assertTrue(fixture.nativeCalls.isEmpty(), "prepare must not run when the draft is not durable")
        assertNull(fixture.anchorStore.peek(shellId, identity), "staged anchor must be discarded")
    }

    @Test
    fun missingNativePrepareEndsAttemptAndDiscardsAnchor(): Unit = runBlocking {
        val fixture = Fixture() // no prepare reply registered
        val outcome =
            fixture.initiator().initiate(identity, launchPayload, windowFact, requestId = "r2")

        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.PrepareFailed, outcome)
        assertEquals(listOf("prepare"), fixture.nativeCalls.map { it.first })
        assertNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun rejectedOpenCancelsPreparedTransaction(): Unit = runBlocking {
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
    fun acceptedOpenHandsCommitToNative(): Unit = runBlocking {
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
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 7.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.CommitDispatched, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r4", null), Triple("commit", "r4", "one-shot")),
            fixture.nativeCalls
        )
        // Anchor stays staged for the destination/shell to consume on the commit receipt.
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun ackFromAnotherRequestIsIgnored(): Unit = runBlocking {
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
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 3.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.CommitDispatched, outcome.await())
    }

    @Test
    fun slowDraftPersistStillCompletesBeforePrepare(): Unit = runBlocking {
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
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = {
                // Hold the receipt until well after initiate() would otherwise reach prepare;
                // the receipt must be the durable-persist barrier, not enqueue admission.
                gate.await()
                draftReturnObservedNativeCalls = fixture.nativeCalls.size
                SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42)
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
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 5.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.CommitDispatched, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r6", null), Triple("commit", "r6", "one-shot")),
            fixture.nativeCalls
        )
    }

    @Test
    fun commitDispatchFailureKeepsAnchorStagedForRecovery(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
                throw IllegalStateException("bridge gone")
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = { SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42) },
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
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 6.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.CommitDispatched, outcome.await())
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
    fun failedCommitReceiptRetainsShellPendingAndAnchor(): Unit = runBlocking {
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
    fun coveredTopKeepsDockCommitWaitsForDestinationReady(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = { SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42) },
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
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 11.0),
            crossProcess = false,
            source = "test",
        )
        yield()
        // Accepted but destination not yet mounted: a covered-top transaction must not commit
        // (pop) yet — the READY report is the only proof the handoff rendered.
        assertEquals<List<Triple<String, String, String?>>>(
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
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.CommitDispatched, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r9", null), Triple("commit", "r9", "one-shot")),
            fixture.nativeCalls
        )
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun readyForForeignGenerationDoesNotReleaseCoveredTopCommit(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = { SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42) },
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
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 21.0),
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
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.CommitDispatched, outcome.await())
    }

    @Test
    fun readyTimeoutCancelsPreparedTransactionAndDiscardsAnchor(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = { SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42) },
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
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 31.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.ReadyTimeout, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r11", null), Triple("cancel", "r11", "one-shot")),
            fixture.nativeCalls
        )
        // Anchor is RETAINED on READY timeout: cancel is only dispatched, so if the prepared
        // transaction already committed natively (late READY lost), the source may still pop —
        // the staged anchor is then the only recovery record for the viewport.
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun lateReadyArrivingAfterTimeoutDoesNotCommit(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = { SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42) },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async {
            initiator.initiate(
                identity, launchPayload, windowFact, "r12",
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
                    .put("requestId", "r12")
                    .put("accepted", true)
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 41.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.ReadyTimeout, outcome.await())
        // A READY arriving after the timeout must not resurrect the transaction: the initiator
        // already returned and its subscription is closed; no commit may fire.
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, identity.key)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 41.0),
            crossProcess = false,
            source = "test",
        )
        yield()
        assertEquals(
            listOf(Triple("prepare", "r12", null), Triple("cancel", "r12", "one-shot")),
            fixture.nativeCalls,
            "a late READY must not trigger commit after ReadyTimeout",
        )
        // Source page is still alive (commit never dispatched) and the anchor survives for a
        // fresh retry — the original draft/scroll remain recoverable.
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun readyArrivingBeforeAckStillReleasesCoveredTopCommit(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = { SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42) },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async {
            initiator.initiate(
                identity, launchPayload, windowFact, "r13",
                awaitReadyBeforeCommit = true,
            )
        }
        yield()
        // READY lands BEFORE the ack (the shell acks inside its READY handler — ordering is
        // not guaranteed). The matching generation must still release the commit.
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, identity.key)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 51.0),
            crossProcess = false,
            source = "test",
        )
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r13")
                    .put("accepted", true)
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 51.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.CommitDispatched, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r13", null), Triple("commit", "r13", "one-shot")),
            fixture.nativeCalls
        )
    }

    @Test
    fun preCommitDraftReadFailureCancelsPreparedTransaction(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        var draftCalls = 0
        val initiator = SlockAdaptiveDetailOpenInitiator(
            shellInstanceId = shellId,
            hostId = hostId,
            anchorStore = fixture.anchorStore,
            eventBus = fixture.eventBus,
            nowEpochMillis = { 0L },
            nativeAdaptiveNavigation = { action, requestId, token, _, _ ->
                fixture.nativeCalls += Triple(action, requestId, token)
                fixture.nativeReplies[action]
            },
            dispatchAdaptiveCommit = { requestId, token ->
                fixture.nativeCalls += Triple("commit", requestId, token)
            },
            dispatchAdaptiveCancel = { requestId, token ->
                fixture.nativeCalls += Triple("cancel", requestId, token)
            },
            captureLatestDraft = {
                draftCalls += 1
                // Second capture (the pre-commit fence) fails → must not dispatch commit.
                if (draftCalls < 2) {
                    SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", 42)
                } else {
                    null
                }
            },
            captureAnchor = { requestId -> fixture.anchorCapture?.copy(requestId = requestId) },
        )
        val outcome = async { initiator.initiate(identity, launchPayload, windowFact, "r14") }
        yield()
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r14")
                    .put("accepted", true)
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 61.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.DraftDrifted, outcome.await())
        assertEquals(2, draftCalls)
        assertEquals(
            listOf(Triple("prepare", "r14", null), Triple("cancel", "r14", "one-shot")),
            fixture.nativeCalls,
            "commit must not be dispatched when the pre-commit draft re-capture fails",
        )
        // Anchor retained for recovery — the destination may already be mounted.
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun editorTypedDuringAckWaitCancelsCommitAsDrift(): Unit = runBlocking {
        // Jony regression: capture persists A → destination restores A and reports READY →
        // user types B in the source editor → ACK arrives → the transaction must cancel,
        // never commit with a destination still showing A while B is durably persisted.
        val fixture = Fixture(
            draftResults =
                mutableListOf(
                    SharedThreadComposerDraftSnapshot("draft-A", "key-1", "inst-1", 42),
                    SharedThreadComposerDraftSnapshot("draft-A-with-B", "key-1", "inst-1", 42),
                )
        )
        fixture.nativeReplies["prepare"] = preparedReply()
        val initiator = fixture.initiator()
        val outcome =
            async {
                initiator.initiate(
                    identity,
                    launchPayload,
                    windowFact,
                    "r15",
                    awaitReadyBeforeCommit = true,
                )
            }
        yield()
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r15")
                    .put("accepted", true)
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 71.0),
            crossProcess = false,
            source = "test",
        )
        // Destination restored draft-A and reported READY for this generation.
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_READY_EVENT,
            eventData =
                JSONObject()
                    .put(SLOCK_ADAPTIVE_DETAIL_IDENTITY_KEY, identity.key)
                    .put(SLOCK_ADAPTIVE_DETAIL_GENERATION, 71.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.DraftDrifted, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r15", null), Triple("cancel", "r15", "one-shot")),
            fixture.nativeCalls,
            "mid-flight edit must cancel the prepared transaction — never commit a stale restore",
        )
        assertTrue(
            fixture.commitDispatches.isEmpty(),
            "commit must not be dispatched after the editor drifted",
        )
        // Source page + the newer draft survive; the staged anchor stays for recovery.
        assertNotNull(fixture.anchorStore.peek(shellId, identity))
    }

    @Test
    fun sameTextDifferentDraftIdentityCancelsAsDrift(): Unit = runBlocking {
        // Equal text under a different draftKey/instance is NOT a version match.
        val fixture = Fixture(
            draftResults =
                mutableListOf(
                    SharedThreadComposerDraftSnapshot("same", "key-1", "inst-1", 42),
                    SharedThreadComposerDraftSnapshot("same", "key-2", "inst-1", 42),
                )
        )
        fixture.nativeReplies["prepare"] = preparedReply()
        val outcome =
            async { fixture.initiator().initiate(identity, launchPayload, windowFact, "r16") }
        yield()
        fixture.eventBus.post(
            eventName = SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT,
            eventData =
                JSONObject()
                    .put("shellInstanceId", shellId)
                    .put("requestId", "r16")
                    .put("accepted", true)
                    .put(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 81.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.DraftDrifted, outcome.await())
        assertEquals(
            listOf(Triple("prepare", "r16", null), Triple("cancel", "r16", "one-shot")),
            fixture.nativeCalls,
            "same text under a different draft identity must still cancel",
        )
    }

    @Test
    fun captureWithoutEditorRefFailsBeforePrepare(): Unit = runBlocking {
        // editorNativeRef < 0: the native commit-time fence cannot bind the capture to a
        // concrete editor — prepare must not arm a guard it cannot honor.
        val fixture = Fixture(
            draftResults =
                mutableListOf(SharedThreadComposerDraftSnapshot("draft", "key-1", "inst-1", -1))
        )
        val outcome =
            fixture.initiator().initiate(identity, launchPayload, windowFact, requestId = "r17")

        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.DraftCaptureFailed, outcome)
        assertTrue(fixture.nativeCalls.isEmpty(), "prepare must not run without a bound editor ref")
    }

    @Test
    fun coroutineCancelDuringAckWaitDispatchesCancel(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.nativeReplies["prepare"] = preparedReply()
        val outcome =
            async { fixture.initiator().initiate(identity, launchPayload, windowFact, "r18") }
        yield()
        outcome.cancel()
        outcome.join()
        // The finally cleanup retires the accepted preparation — native must never be left
        // holding a live nonce whose owner is gone.
        assertEquals(
            listOf(Triple("prepare", "r18", null), Triple("cancel", "r18", "one-shot")),
            fixture.nativeCalls,
        )
    }
}
