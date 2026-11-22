package build.raft.app.desktop

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
}
