package build.raft.app

import build.raft.designsystem.components.RaftSideActionHostColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The iOS side-column host bridge: the column's wire format, taps, the capability switch. */
class RaftSideActionHostBridgeTest {
    private fun item(id: String, symbol: String, enabled: Boolean = true, attention: Boolean = false) =
        RaftSideActionHostColumn.Item(
            id = id,
            label = id.replaceFirstChar { it.uppercase() },
            systemSymbol = symbol,
            enabled = enabled,
            attention = attention,
            attentionDescription = if (attention) "2 new" else null,
        )

    private val channel =
        RaftSideActionHostColumn(
            primary = listOf(item("back", "chevron.backward")),
            actions = listOf(item("thread-search", "magnifyingglass"), item("thread-settings", "gearshape")),
            visibleActionIds = listOf("thread-search", "thread-settings"),
            overflowActionIds = emptyList(),
            overflowLabel = "More Actions",
        )

    private class Recorder : RaftSideActionHostListener {
        val events = mutableListOf<String?>()

        override fun onSideActionColumnChanged(json: String) {
            events += json
        }

        override fun onSideActionColumnCleared() {
            events += null
        }
    }

    @Test
    fun theColumnSerialisesToTheWireShape() {
        assertEquals(
            """{"version":1,""" +
                """"primary":[{"id":"back","label":"Back","systemSymbol":"chevron.backward","enabled":true,"attention":false}],""" +
                """"actions":[{"id":"thread-search","label":"Thread-search","systemSymbol":"magnifyingglass","enabled":true,"attention":false},""" +
                """{"id":"thread-settings","label":"Thread-settings","systemSymbol":"gearshape","enabled":true,"attention":false}],""" +
                """"visibleActionIds":["thread-search","thread-settings"],"overflowActionIds":[],""" +
                """"overflowLabel":"More Actions","overflowAttention":false}""",
            raftSideActionHostJson(channel),
        )
    }

    @Test
    fun overflowAndAttentionReachTheHost() {
        val column =
            RaftSideActionHostColumn(
                primary = emptyList(),
                actions =
                    listOf(
                        item("a", "circle"),
                        item("b", "square", enabled = false),
                        item("bell", "bell", attention = true),
                    ),
                visibleActionIds = listOf("a", "b"),
                overflowActionIds = listOf("bell"),
                overflowLabel = "More \"Actions\"",
            )
        val json = Json.parseToJsonElement(raftSideActionHostJson(column)).jsonObject
        assertEquals(1, json.getValue("version").jsonPrimitive.int)
        assertEquals(JsonArray(emptyList()), json.getValue("primary"))
        val actions = json.getValue("actions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b", "bell"), actions.map { it.getValue("id").jsonPrimitive.content })
        assertFalse(actions[1].getValue("enabled").jsonPrimitive.boolean)
        assertTrue(actions[2].getValue("attention").jsonPrimitive.boolean)
        assertEquals("2 new", actions[2].getValue("attentionDescription").jsonPrimitive.content)
        assertFalse("attentionDescription" in actions[0], "absent, not null, without one")
        assertEquals(JsonArray(listOf(JsonPrimitive("bell"))), json.getValue("overflowActionIds"))
        assertEquals("More \"Actions\"", json.getValue("overflowLabel").jsonPrimitive.content, "escaped")
        assertTrue(json.getValue("overflowAttention").jsonPrimitive.boolean, "the ⋯ wears the bell's pixel")
    }

    @Test
    fun offByDefaultTheHostDeclinesAndShowsNothing() {
        val bridge = RaftSideActionHostBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        assertFalse(bridge.isAvailable)
        assertFalse(bridge.host.present(channel) {})
        assertNull(bridge.currentColumnJson)
        assertEquals(listOf<String?>(null), recorder.events, "only the attach replay")
        assertFalse(bridge.sideActionTapped("back"))
    }

    @Test
    fun presentPublishesOnceAndClearWithdraws() {
        val bridge = RaftSideActionHostBridge()
        bridge.setAvailable(true)
        val recorder = Recorder()
        bridge.attachListener(recorder)
        assertTrue(bridge.host.present(channel) {})
        assertTrue(bridge.host.present(channel.copy()) {})
        assertEquals(listOf(null, raftSideActionHostJson(channel)), recorder.events, "an identical column is not re-sent")
        assertEquals(raftSideActionHostJson(channel), bridge.currentColumnJson)

        val late = Recorder()
        bridge.attachListener(late)
        assertEquals(listOf<String?>(raftSideActionHostJson(channel)), late.events, "a new listener gets the current column")

        bridge.host.clear()
        bridge.host.clear()
        assertEquals(listOf(raftSideActionHostJson(channel), null), late.events)
        assertNull(bridge.currentColumnJson)
        assertFalse(bridge.sideActionTapped("back"), "nothing shown, nothing runs")
    }

    @Test
    fun switchingOffWithdrawsTheColumnAtOnce() {
        val bridge = RaftSideActionHostBridge()
        bridge.setAvailable(true)
        val recorder = Recorder()
        bridge.attachListener(recorder)
        bridge.host.present(channel) {}
        bridge.setAvailable(false)
        assertEquals(listOf(null, raftSideActionHostJson(channel), null), recorder.events)
        assertFalse(bridge.host.present(channel) {})
        assertEquals(3, recorder.events.size)
    }

    @Test
    fun aTapRunsOnlyAShownEnabledItem() {
        val bridge = RaftSideActionHostBridge()
        bridge.setAvailable(true)
        val tapped = mutableListOf<String>()
        val column = channel.copy(actions = channel.actions + item("off", "xmark", enabled = false))
        bridge.host.present(column) { tapped += it }
        assertTrue(bridge.sideActionTapped("back"))
        assertTrue(bridge.sideActionTapped("thread-settings"))
        assertFalse(bridge.sideActionTapped("off"))
        assertFalse(bridge.sideActionTapped("nope"))
        assertEquals(listOf("back", "thread-settings"), tapped)
    }

    @Test
    fun theRegistryFindsTheHostsBridge() {
        val bridge = RaftSideActionHostBridge()
        val other = RaftSideActionHostBridge()
        RaftSideActionHostRegistry.register("side-a", bridge)
        assertSame(bridge, RaftSideActionHostRegistry.bridgeFor("side-a"))
        assertNull(RaftSideActionHostRegistry.bridgeFor(""))
        assertNull(RaftSideActionHostRegistry.bridgeFor(null))
        RaftSideActionHostRegistry.unregister("side-a", other)
        assertSame(bridge, RaftSideActionHostRegistry.bridgeFor("side-a"))
        RaftSideActionHostRegistry.unregister("side-a", bridge)
        assertNull(RaftSideActionHostRegistry.bridgeFor("side-a"))
    }

    @Test
    fun onlyTheChannelPageCarriesSystemSymbols() {
        assertTrue(threadHeaderSideColumnSystemSymbols(RaftThreadSurfaceKind.Channel))
        assertFalse(threadHeaderSideColumnSystemSymbols(RaftThreadSurfaceKind.DirectMessage))
        assertFalse(threadHeaderSideColumnSystemSymbols(RaftThreadSurfaceKind.ReplyThread))
    }
}
