package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/** The payload each entry-level call site reports, as it reaches the batch. */
class RaftProductEntryEventsRecorderTest {
    private fun TestScope.recorderWithTransport(): Pair<RaftProductEventsRecorder, RecordingProductEventsTransport> {
        val transport = RecordingProductEventsTransport()
        val client = productEventsClient(transport)
        client.setSignedIn(ServerId("srv-a"))
        return RaftProductEventsRecorder(client, nowEpochMillis = { testScheduler.currentTime }) to transport
    }

    private fun TestScope.single(
        transport: RecordingProductEventsTransport,
        recorder: RaftProductEventsRecorder,
    ): RaftProductEventWire {
        recorder.client.flushNow()
        testScheduler.runCurrent()
        return transport.sentEvents.single()
    }

    private fun RaftProductEventWire.props(): Map<String, String> =
        properties.mapValues { (_, value) -> value.jsonPrimitive.content }

    @Test
    fun channelTabSelectedCarriesTabHeaderTabsSurfaceAndWasActive() =
        runTest {
            val cases =
                listOf(
                    Triple(RaftChannelTab.Chat, false, mapOf("tab" to "chat", "surface" to "header_tabs", "was_active" to "false")),
                    Triple(RaftChannelTab.Tasks, false, mapOf("tab" to "tasks", "surface" to "header_tabs", "was_active" to "false")),
                    Triple(RaftChannelTab.Files, true, mapOf("tab" to "files", "surface" to "header_tabs", "was_active" to "true")),
                )
            cases.forEach { (tab, wasActive, expected) ->
                val (recorder, transport) = recorderWithTransport()
                recorder.channelTabSelected(tab, wasActive)
                val event = single(transport, recorder)
                assertEquals("channel_tab_selected", event.event)
                assertEquals(expected, event.props())
                assertEquals(wasActive, event.properties.getValue("was_active").jsonPrimitive.boolean)
            }
        }

    @Test
    fun activityEventsCarryOnlyTheRegisteredField() =
        runTest {
            val open = recorderWithTransport()
            open.first.activityItemOpen(RaftActivityItemKind.MentionAction)
            val openEvent = single(open.second, open.first)
            assertEquals("activity_item_open", openEvent.event)
            assertEquals(mapOf("item_kind" to "mention_action"), openEvent.props())

            RaftActivityMarkAction.entries.forEach { action ->
                val mark = recorderWithTransport()
                mark.first.activityMark(action)
                val markEvent = single(mark.second, mark.first)
                assertEquals("activity_mark", markEvent.event)
                assertEquals(mapOf("action" to action.wire), markEvent.props())
            }
        }
}
