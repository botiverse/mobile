package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * The gated-feature events (`drafts_page_viewed`, `drafts_item_opened`, `agent_read_progress_shown`):
 * names, properties and values vendored from botiverse/slock `packages/shared/src/productEvents.ts`
 * at [PinnedSlockSha] (#13636 head), and the payload each recorder call puts in a consented batch.
 */
class RaftProductGatedFeatureEventsTest {
    private companion object {
        const val PinnedSlockSha = "a5a145f9fe3ce4da49cba8e7d2ec39293c1f1270"

        /** `PRODUCT_EVENT_REGISTRY` entries (every property required, `sources: APP_CLIENTS`). */
        val Registry =
            mapOf(
                "drafts_page_viewed" to mapOf("drafts" to setOf("0", "1", "2-5", "6+")),
                "drafts_item_opened" to mapOf("place" to setOf("channel", "dm", "thread", "task")),
                "agent_read_progress_shown" to
                    mapOf(
                        "part" to setOf("faces", "not_reaching_line"),
                        "conversation" to setOf("channel", "thread"),
                    ),
            )
    }

    private fun TestScope.recorderWithTransport(
        transport: RecordingProductEventsTransport = RecordingProductEventsTransport(),
    ): Pair<RaftProductEventsRecorder, RecordingProductEventsTransport> {
        val client = productEventsClient(transport)
        client.setSignedIn(ServerId("srv-a"))
        return RaftProductEventsRecorder(client, nowEpochMillis = { testScheduler.currentTime }) to transport
    }

    private fun TestScope.sent(
        transport: RecordingProductEventsTransport,
        recorder: RaftProductEventsRecorder,
    ): List<RaftProductEventWire> {
        recorder.client.flushNow()
        testScheduler.runCurrent()
        return transport.sentEvents
    }

    private fun RaftProductEventWire.props(): Map<String, String> =
        properties.mapValues { (_, value) -> value.jsonPrimitive.content }

    private fun allEvents(): List<RaftProductEvent<*>> =
        RaftDraftCountBucket.entries.map { RaftProductEvent.DraftsPageViewed(RaftDraftsPageViewedProperties(it)) } +
            RaftDraftPlaceKind.entries.map { RaftProductEvent.DraftsItemOpened(RaftDraftsItemOpenedProperties(it)) } +
            RaftAgentReadProgressPart.entries.flatMap { part ->
                RaftAgentReadProgressConversation.entries.map { conversation ->
                    RaftProductEvent.AgentReadProgressShown(RaftAgentReadProgressShownProperties(part, conversation))
                }
            }

    @Test
    fun everyEmittableValueIsRegisteredAndCarriesEveryRequiredProperty() {
        assertEquals(PinnedSlockSha, RAFT_PRODUCT_EVENTS_GATED_FEATURES_SLOCK_SHA)
        val events = allEvents()
        assertEquals(Registry.keys, events.map { it.name }.toSet())
        events.forEach { event ->
            val spec = Registry.getValue(event.name)
            val properties = event.encodeProperties()
            assertEquals(spec.keys, properties.keys, "${event.name} carries exactly its registered properties")
            properties.forEach { (key, value) ->
                assertTrue(value.jsonPrimitive.content in spec.getValue(key), "${event.name}.$key=${value.jsonPrimitive.content}")
            }
        }
        // The app covers every registered value of these enums.
        assertEquals(Registry.getValue("drafts_page_viewed").getValue("drafts"), RaftDraftCountBucket.entries.map { it.wire }.toSet())
        assertEquals(Registry.getValue("drafts_item_opened").getValue("place"), RaftDraftPlaceKind.entries.map { it.wire }.toSet())
    }

    @Test
    fun draftCountBucketsMatchWeb() {
        val cases =
            mapOf(
                -1 to "0", 0 to "0", 1 to "1", 2 to "2-5", 5 to "2-5", 6 to "6+", 42 to "6+",
            )
        cases.forEach { (count, bucket) -> assertEquals(bucket, raftDraftCountBucket(count).wire, "count=$count") }
    }

    @Test
    fun draftsEventsCarryOnlyTheBucketOrThePlace() =
        runTest {
            val (recorder, transport) = recorderWithTransport()
            recorder.draftsPageViewed(3)
            recorder.draftsItemOpened(RaftDraftPlaceKind.Dm)
            val events = sent(transport, recorder)
            assertEquals(listOf("drafts_page_viewed", "drafts_item_opened"), events.map { it.event })
            assertEquals(mapOf("drafts" to "2-5"), events[0].props())
            assertEquals(mapOf("place" to "dm"), events[1].props())
        }

    @Test
    fun agentReadProgressShownCountsEachPartOncePerConversation() =
        runTest {
            val (recorder, transport) = recorderWithTransport()
            recorder.agentReadProgressShown("ch-1", RaftAgentReadProgressConversation.Channel, faces = true, notReachingLine = false)
            // Recomposition, the line appearing later, and a reopen of the same conversation.
            recorder.agentReadProgressShown("ch-1", RaftAgentReadProgressConversation.Channel, faces = true, notReachingLine = false)
            recorder.agentReadProgressShown("ch-1", RaftAgentReadProgressConversation.Channel, faces = true, notReachingLine = true)
            recorder.agentReadProgressShown("ch-1", RaftAgentReadProgressConversation.Channel, faces = true, notReachingLine = true)
            // Another conversation counts on its own; nothing generated sends nothing.
            recorder.agentReadProgressShown("th-2", RaftAgentReadProgressConversation.Thread, faces = true, notReachingLine = false)
            recorder.agentReadProgressShown("th-3", RaftAgentReadProgressConversation.Thread, faces = false, notReachingLine = false)
            recorder.agentReadProgressShown(" ", RaftAgentReadProgressConversation.Channel, faces = true, notReachingLine = true)
            val events = sent(transport, recorder)
            assertTrue(events.all { it.event == "agent_read_progress_shown" })
            assertEquals(
                listOf(
                    mapOf("part" to "faces", "conversation" to "channel"),
                    mapOf("part" to "not_reaching_line", "conversation" to "channel"),
                    mapOf("part" to "faces", "conversation" to "thread"),
                ),
                events.map { it.props() },
            )
        }

    @Test
    fun agentReadProgressShownTrackerDedupesPerScopeAndPart() {
        val tracker = RaftAgentReadProgressShownTracker()
        assertEquals(
            listOf(RaftAgentReadProgressPart.Faces, RaftAgentReadProgressPart.NotReachingLine),
            tracker.take("ch-1", faces = true, notReachingLine = true),
        )
        assertEquals(emptyList(), tracker.take("ch-1", faces = true, notReachingLine = true))
        assertEquals(listOf(RaftAgentReadProgressPart.NotReachingLine), tracker.take("ch-2", faces = false, notReachingLine = true))
        assertEquals(listOf(RaftAgentReadProgressPart.Faces), tracker.take("ch-2", faces = true, notReachingLine = true))
    }

    @Test
    fun gatedFeatureEventsWaitForConsentLikeEveryOtherEvent() =
        runTest {
            val transport = RecordingProductEventsTransport().apply { configAnswer = { RaftProductEventsConfigResult.Answered(false) } }
            val (recorder, _) = recorderWithTransport(transport)
            recorder.draftsPageViewed(0)
            recorder.draftsItemOpened(RaftDraftPlaceKind.Task)
            recorder.agentReadProgressShown("ch-1", RaftAgentReadProgressConversation.Channel, faces = true, notReachingLine = true)
            settleProductEvents()
            assertEquals(listOf(ServerId("srv-a")), transport.configCalls)
            assertEquals(0, transport.sendAttempts.size, "a server that does not allow client events gets no batch")
            assertEquals(0, recorder.client.queuedEventCount)
        }
}
