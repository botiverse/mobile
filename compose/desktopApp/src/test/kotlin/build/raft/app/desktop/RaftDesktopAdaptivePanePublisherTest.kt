package build.raft.app.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.cmp.host.RaftHostEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RaftDesktopAdaptivePanePublisherTest {
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private val publisher = RaftDesktopAdaptivePanePublisher { event, data -> sent += event to data }

    @Test fun `publishes the window size with a flat posture`() {
        assertTrue(publisher.publish(widthPx = 2000, heightPx = 1600, density = 2f))
        val (event, data) = sent.single()
        assertEquals("slockAdaptivePaneChanged", event)
        assertEquals(JsonPrimitive(2000), data["widthPx"])
        assertEquals(JsonPrimitive(1600), data["heightPx"])
        assertEquals(JsonPrimitive(2f), data["density"])
        assertEquals(JsonPrimitive("flat"), data["posture"])
    }

    @Test fun `republishes on resize and skips unchanged or empty sizes`() {
        assertTrue(publisher.publish(2000, 1600, 2f))
        assertFalse(publisher.publish(2000, 1600, 2f))
        assertFalse(publisher.publish(0, 0, 2f))
        assertFalse(publisher.publish(800, 600, 0f))
        assertTrue(publisher.publish(1400, 1600, 2f))
        assertEquals(listOf(2000, 1400), sent.map { (it.second["widthPx"] as JsonPrimitive).content.toInt() })
    }

    @Test fun `seeds frame 1 size only for a top page with positive container dimensions`() {
        assertEquals(
            IntSize(2400, 1600),
            raftDesktopInitialAdaptivePaneSize(isTopPage = true, containerSize = IntSize(2400, 1600)),
        )
        assertEquals(
            IntSize.Zero,
            raftDesktopInitialAdaptivePaneSize(isTopPage = false, containerSize = IntSize(2400, 1600)),
        )
        assertEquals(
            IntSize.Zero,
            raftDesktopInitialAdaptivePaneSize(isTopPage = true, containerSize = IntSize.Zero),
        )
        assertEquals(
            IntSize.Zero,
            raftDesktopInitialAdaptivePaneSize(isTopPage = true, containerSize = IntSize(2400, 0)),
        )
        assertEquals(
            IntSize.Zero,
            raftDesktopInitialAdaptivePaneSize(isTopPage = true, containerSize = IntSize(-1, 1600)),
        )
        // Falls back to configured window DpSize when LocalWindowInfo.containerSize is still Zero.
        assertEquals(
            IntSize(2400, 1600),
            raftDesktopInitialAdaptivePaneSize(
                isTopPage = true,
                containerSize = IntSize.Zero,
                fallbackWindowSize = DpSize(1200.dp, 800.dp),
                density = 2f,
            ),
        )
        // Container size takes precedence over fallback when already positive.
        assertEquals(
            IntSize(1920, 1080),
            raftDesktopInitialAdaptivePaneSize(
                isTopPage = true,
                containerSize = IntSize(1920, 1080),
                fallbackWindowSize = DpSize(1200.dp, 800.dp),
                density = 2f,
            ),
        )
        // Non-top pages or invalid fallback dimensions stay Zero.
        assertEquals(
            IntSize.Zero,
            raftDesktopInitialAdaptivePaneSize(
                isTopPage = false,
                containerSize = IntSize.Zero,
                fallbackWindowSize = DpSize(1200.dp, 800.dp),
                density = 2f,
            ),
        )
        assertEquals(
            IntSize.Zero,
            raftDesktopInitialAdaptivePaneSize(
                isTopPage = true,
                containerSize = IntSize.Zero,
                fallbackWindowSize = DpSize.Unspecified,
                density = 2f,
            ),
        )
        assertEquals(
            IntSize.Zero,
            raftDesktopInitialAdaptivePaneSize(
                isTopPage = true,
                containerSize = IntSize.Zero,
                fallbackWindowSize = DpSize(1200.dp, 800.dp),
                density = 0f,
            ),
        )
    }

    @Test fun `sticky seed replays before first composition and deduplicates matching post-layout publish`() {
        val events = RaftHostEvents()
        val stickyPublisher = RaftDesktopAdaptivePanePublisher(events::sendSticky)

        // Frame-1 synchronous seed happens before RaftPagerHost attaches.
        assertTrue(stickyPublisher.publish(widthPx = 2400, heightPx = 1600, density = 2f))

        // RaftPagerHost replays sticky events on pager creation, then subscribes for resizes.
        val received = mutableListOf<Pair<String, JsonObject>>()
        events.replaySticky { event, data -> received += event to data }
        events.subscribe { event, data -> received += event to data }

        // Post-composition LaunchedEffect with the same size is deduplicated (no duplicate event).
        assertFalse(stickyPublisher.publish(widthPx = 2400, heightPx = 1600, density = 2f))
        assertEquals(1, received.size)
        assertEquals(JsonPrimitive(2400), received.single().second["widthPx"])

        // A later resize publishes to the subscribed listener and refreshes the sticky snapshot.
        assertTrue(stickyPublisher.publish(widthPx = 1400, heightPx = 1600, density = 2f))
        assertEquals(listOf(2400, 1400), received.map { (it.second["widthPx"] as JsonPrimitive).content.toInt() })

        val lateChild = mutableListOf<Pair<String, JsonObject>>()
        events.replaySticky { event, data -> lateChild += event to data }
        assertEquals(JsonPrimitive(1400), lateChild.single().second["widthPx"])
    }
}
