package build.raft.app.cmp.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftHostEventsTest {
    @Test
    fun sendStickyNotifiesLiveListenersAndReplaysLatestSnapshotPerEventInOrder() {
        val events = RaftHostEvents()
        val live = mutableListOf<Pair<String, JsonObject>>()
        events.subscribe { event, data -> live += event to data }

        val paneInitial = JsonObject(mapOf("widthPx" to JsonPrimitive(2400)))
        val secondSticky = JsonObject(mapOf("mode" to JsonPrimitive("wide")))
        val paneResized = JsonObject(mapOf("widthPx" to JsonPrimitive(1600)))

        events.sendSticky("slockAdaptivePaneChanged", paneInitial)
        events.sendSticky("slockTestStickyFact", secondSticky)
        events.sendSticky("slockAdaptivePaneChanged", paneResized)

        val replayed = mutableListOf<Pair<String, JsonObject>>()
        events.replaySticky { event, data -> replayed += event to data }

        assertEquals(
            listOf(
                "slockAdaptivePaneChanged" to paneInitial,
                "slockTestStickyFact" to secondSticky,
                "slockAdaptivePaneChanged" to paneResized,
            ),
            live,
        )
        assertEquals(
            listOf(
                "slockAdaptivePaneChanged" to paneResized,
                "slockTestStickyFact" to secondSticky,
            ),
            replayed,
        )
    }

    @Test
    fun preFirstCompositionReplayDeliversStickyAdaptivePaneToPagerHandlerBeforeLiveSubscribe() {
        val events = RaftHostEvents()
        val initialPane =
            JsonObject(
                mapOf(
                    "widthPx" to JsonPrimitive(2400),
                    "heightPx" to JsonPrimitive(1600),
                    "density" to JsonPrimitive(2f),
                    "posture" to JsonPrimitive("flat"),
                )
            )
        val resizedPane =
            JsonObject(
                mapOf(
                    "widthPx" to JsonPrimitive(1400),
                    "heightPx" to JsonPrimitive(1600),
                    "density" to JsonPrimitive(2f),
                    "posture" to JsonPrimitive("flat"),
                )
            )

        // Host seeds adaptive pane synchronously before RaftPagerHost creates the pager.
        events.sendSticky("slockAdaptivePaneChanged", initialPane)

        // RaftPagerHost replays sticky events into Pager.onReceivePagerEvent ahead of Frame 1,
        // then subscribes in DisposableEffect for subsequent live resizes.
        val pagerReceived = mutableListOf<Pair<String, JsonObject>>()
        val onReceivePagerEvent: (String, JsonObject) -> Unit = { event, data ->
            pagerReceived += event to data
        }
        events.replaySticky(onReceivePagerEvent)
        assertEquals(listOf("slockAdaptivePaneChanged" to initialPane), pagerReceived)

        val unsubscribe = events.subscribe(onReceivePagerEvent)
        events.sendSticky("slockAdaptivePaneChanged", resizedPane)
        unsubscribe()

        assertEquals(
            listOf(
                "slockAdaptivePaneChanged" to initialPane,
                "slockAdaptivePaneChanged" to resizedPane,
            ),
            pagerReceived,
        )
    }
}
