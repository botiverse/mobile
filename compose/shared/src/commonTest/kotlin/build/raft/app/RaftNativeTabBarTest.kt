package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftNativeTabBarTest {
    private val fiveTabKeys = listOf("home", "activity", "tasks", "members", "settings")

    private fun fiveTabs(selectedKey: String, activityBadgeCount: Int = 0) =
        appShellBottomTabs(
            selectedKey = selectedKey,
            orderedTabKeys = fiveTabKeys,
            activityBadgeCount = activityBadgeCount,
            labelFor = { key -> "label-$key" },
        )

    private class Recorder : RaftNativeTabBarListener {
        val states = mutableListOf<RaftNativeTabBarState>()

        override fun onNativeTabBarStateChanged(state: RaftNativeTabBarState) {
            states += state
        }
    }

    @Test
    fun stateKeepsSharedTabOrderLabelsSymbolsAndSelection() {
        val state = raftNativeTabBarState(fiveTabs(selectedKey = "activity"), visible = true)

        assertEquals(fiveTabKeys, state.items.map { it.key })
        assertEquals(fiveTabKeys.map { "label-$it" }, state.items.map { it.title })
        assertEquals("activity", state.selectedKey)
        assertTrue(state.visible)
        assertEquals(
            listOf("house", "bell", "checkmark.square", "person.2", "gearshape"),
            state.items.map { it.systemImageName },
        )
        assertEquals(
            listOf("house.fill", "bell.fill", "checkmark.square.fill", "person.2.fill", "gearshape.fill"),
            state.items.map { it.selectedSystemImageName },
        )
    }

    @Test
    fun selectionIsNullWhenNoTabIsSelected() {
        assertNull(raftNativeTabBarState(fiveTabs(selectedKey = "rail_search"), visible = true).selectedKey)
    }

    @Test
    fun unknownTabIconFallsBackToCircle() {
        assertEquals(
            RaftNativeTabBarSymbol("circle", "circle.fill"),
            raftNativeTabBarSymbol(appShellTabIcon("brand-new-tab")),
        )
    }

    @Test
    fun badgeCountsMapToCappedBadgeValues() {
        val cases = listOf(-3 to null, 0 to null, 1 to "1", 99 to "99", 100 to "99+", 4321 to "99+")
        cases.forEach { (count, expected) ->
            assertEquals(expected, raftNativeTabBarBadgeValue(count), "count=$count")
        }
        val state =
            raftNativeTabBarState(
                fiveTabs(selectedKey = "home", activityBadgeCount = 120),
                visible = true,
            )
        val byKey = state.items.associateBy { it.key }
        assertEquals(120, byKey.getValue("activity").badgeCount)
        assertEquals("99+", byKey.getValue("activity").badgeValue)
        assertEquals(0, byKey.getValue("tasks").badgeCount)
        assertNull(byKey.getValue("tasks").badgeValue)
    }

    @Test
    fun bridgeReplaysToListenerAndSkipsEqualStates() {
        val bridge = RaftNativeTabBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val token = Any()
        val state = raftNativeTabBarState(fiveTabs(selectedKey = "home"), visible = true)

        bridge.publish(token, state) {}
        bridge.publish(token, state.copy()) {}

        assertEquals(listOf(RaftNativeTabBarState.Hidden, state), recorder.states)
        assertEquals(state, bridge.currentState)
    }

    @Test
    fun selectTabRunsShellHandlerOnlyForPublishedEnabledTabs() {
        val bridge = RaftNativeTabBarBridge()
        val taps = mutableListOf<String>()
        val items = fiveTabs(selectedKey = "home").map { if (it.key == "members") it.copy(enabled = false) else it }
        bridge.publish(Any(), raftNativeTabBarState(items, visible = true)) { taps += it }

        assertTrue(bridge.selectTab("tasks"))
        // A re-tap of the selected tab reaches the shell (scroll to top / pop to root).
        assertTrue(bridge.selectTab("home"))
        assertFalse(bridge.selectTab("members"))
        assertFalse(bridge.selectTab("unknown"))
        assertEquals(listOf("tasks", "home"), taps)
    }

    @Test
    fun retractHidesOnlyForTheActivePublisherAndKeepsItems() {
        val bridge = RaftNativeTabBarBridge()
        val stale = Any()
        val current = Any()
        val taps = mutableListOf<String>()
        bridge.publish(stale, raftNativeTabBarState(fiveTabs(selectedKey = "home"), visible = true)) {}
        val shown = raftNativeTabBarState(fiveTabs(selectedKey = "tasks"), visible = true)
        bridge.publish(current, shown) { taps += it }

        bridge.retract(stale)
        assertEquals(shown, bridge.currentState)
        assertTrue(bridge.selectTab("home"))

        bridge.retract(current)
        assertEquals(shown.copy(visible = false), bridge.currentState)
        assertFalse(bridge.selectTab("home"))
        assertEquals(listOf("home"), taps)
    }

    @Test
    fun bottomInsetIgnoresInvalidHostValues() {
        val bridge = RaftNativeTabBarBridge()
        bridge.updateBottomInset(83.0)
        bridge.updateBottomInset(Double.NaN)
        bridge.updateBottomInset(-1.0)
        bridge.updateBottomInset(Double.POSITIVE_INFINITY)

        assertEquals(83.0, bridge.bottomInsetPoints.value)
    }

    @Test
    fun registryResolvesOnlyTheRegisteredBridge() {
        val bridge = RaftNativeTabBarBridge()
        val other = RaftNativeTabBarBridge()
        RaftNativeTabBarRegistry.register("host-a", bridge)

        assertSame(bridge, RaftNativeTabBarRegistry.bridgeFor("host-a"))
        assertNull(RaftNativeTabBarRegistry.bridgeFor(""))
        assertNull(RaftNativeTabBarRegistry.bridgeFor(null))

        RaftNativeTabBarRegistry.unregister("host-a", other)
        assertSame(bridge, RaftNativeTabBarRegistry.bridgeFor("host-a"))
        RaftNativeTabBarRegistry.unregister("host-a", bridge)
        assertNull(RaftNativeTabBarRegistry.bridgeFor("host-a"))
    }

    @Test
    fun nativeHostContentInsetUsesReportedBandWithoutDoubleSafeArea() {
        val bottomSafeArea = 34.dp

        assertEquals(83.dp + RaftAppSpacing.Lg, raftNativeHostTabBarContentInset(83.dp, bottomSafeArea))
        assertEquals(
            49.dp + bottomSafeArea + RaftAppSpacing.Lg,
            raftNativeHostTabBarContentInset(0.dp, bottomSafeArea),
        )
        assertEquals(
            raftNativeHostTabBarContentInset(0.dp, bottomSafeArea),
            raftAppBottomTabBarContentInset(RaftAppTabBarRenderer.NativeHost, bottomSafeArea),
        )
    }
}
