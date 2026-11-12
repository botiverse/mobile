package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * `app_rating_prompt_requested`, vendored from botiverse/slock `packages/shared/src/productEvents.ts`
 * at [PinnedSlockSha] (#14522): one required property `trigger`, nothing else (no platform or app
 * version; the batch carries those).
 */
class RaftProductRatingPromptEventTest {
    private companion object {
        const val PinnedSlockSha = "9da0de7879d8c8290363e6ab3719d73f2d7bb483"
        val Triggers = setOf("task_result_viewed", "action_card_approved")
    }

    @Test
    fun theEventCarriesExactlyItsRegisteredTrigger() = runTest {
        assertEquals(PinnedSlockSha, RAFT_PRODUCT_EVENTS_APP_RATING_SLOCK_SHA)
        assertEquals(Triggers, RaftAppRatingPromptTrigger.entries.map { it.wire }.toSet())
        val transport = RecordingProductEventsTransport()
        val client = productEventsClient(transport).also { it.setSignedIn(ServerId("srv-a")) }
        val recorder = RaftProductEventsRecorder(client, nowEpochMillis = { testScheduler.currentTime })
        RaftAppRatingPromptTrigger.entries.forEach(recorder::appRatingPromptRequested)
        client.flushNow()
        runCurrent()
        val events = transport.sentEvents
        assertEquals(List(Triggers.size) { "app_rating_prompt_requested" }, events.map { it.event })
        assertEquals(
            RaftAppRatingPromptTrigger.entries.map { mapOf("trigger" to it.wire) },
            events.map { event -> event.properties.mapValues { (_, value) -> value.jsonPrimitive.content } },
        )
    }

    @Test
    fun theEventWaitsForTheServersConsentLikeEveryOtherEvent() = runTest {
        val transport = RecordingProductEventsTransport().apply { configAnswer = { RaftProductEventsConfigResult.Answered(false) } }
        val client = productEventsClient(transport).also { it.setSignedIn(ServerId("srv-a")) }
        RaftProductEventsRecorder(client, nowEpochMillis = { testScheduler.currentTime })
            .appRatingPromptRequested(RaftAppRatingPromptTrigger.ActionCardApproved)
        settleProductEvents()
        assertEquals(listOf(ServerId("srv-a")), transport.configCalls)
        assertEquals(0, transport.sendAttempts.size, "Share usage data off: no batch")
        assertEquals(0, client.queuedEventCount)
    }
}
