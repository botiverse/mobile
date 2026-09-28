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
                    .put("generation", 7.0),
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
                    .put("generation", 3.0),
            crossProcess = false,
            source = "test",
        )
        assertEquals(SlockAdaptiveDetailOpenInitiator.Outcome.Committed, outcome.await())
    }
}
