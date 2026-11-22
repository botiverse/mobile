package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

/**
 * The gated-feature events (`drafts_page_viewed`, `drafts_item_opened`):
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
            RaftDraftPlaceKind.entries.map { RaftProductEvent.DraftsItemOpened(RaftDraftsItemOpenedProperties(it)) }

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
}
