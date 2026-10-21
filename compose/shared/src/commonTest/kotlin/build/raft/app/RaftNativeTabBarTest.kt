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

    private fun fiveTabs(selectedKey: String, activityUnreadCount: Int = 0) =
        appShellBottomTabs(
            selectedKey = selectedKey,
            orderedTabKeys = fiveTabKeys,
            activityUnreadCount = activityUnreadCount,
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
            listOf("house", "waveform.path.ecg.rectangle", "checkmark.square", "person.2", "gearshape"),
            state.items.map { it.systemImageName },
        )
        assertEquals(
            listOf("house.fill", "waveform.path.ecg.rectangle.fill", "checkmark.square.fill", "person.2.fill", "gearshape.fill"),
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
        val counted = raftNativeTabBarItem(RaftBottomTabItemPayload(key = "x", badgeCount = 120))
        assertEquals(120, counted.badgeCount)
        assertEquals("99+", counted.badgeValue)
        assertFalse(counted.attentionDot)
    }

    @Test
    fun phoneBarPublishesFiveItemsWithSearchLeftOfMore() {
        val state =
            raftNativeTabBarState(
                appShellPhoneBottomTabs(
                    selectedTab = "home",
                    homeLabel = "h",
                    activityLabel = "a",
                    searchLabel = "label-search",
                    tasksLabel = "t",
                    moreLabel = "m",
                    activityUnreadCount = 120,
                ),
                visible = true,
            )
        assertEquals(listOf("home", "activity", "tasks", AppShellSearchActionKey, "more"), state.items.map { it.key })
        val byKey = state.items.associateBy { it.key }
        assertTrue(byKey.getValue("activity").attentionDot)
        assertEquals(0, byKey.getValue("activity").badgeCount)
        assertNull(byKey.getValue("activity").badgeValue)
        assertFalse(byKey.getValue("tasks").attentionDot)
        // Search sits directly left of More, flagged as the action, never the selection.
        val search = state.items[3]
        assertEquals(AppShellSearchActionKey, search.key)
        assertEquals("label-search", search.title)
        assertTrue(search.searchAction)
        assertEquals("magnifyingglass", search.systemImageName)
        assertEquals("home", state.selectedKey)
        assertEquals(listOf(search), state.items.filter { it.searchAction })
        // A payload claiming Search is selected still publishes no Search selection.
        val forced =
            raftNativeTabBarState(
                listOf(RaftBottomTabItemPayload(key = AppShellSearchActionKey, selected = true)),
                visible = true,
            )
        assertNull(forced.selectedKey)
        // The selected Activity tab never carries the dot.
        val onActivity = raftNativeTabBarState(fiveTabs(selectedKey = "activity", activityUnreadCount = 3), visible = true)
        assertFalse(onActivity.items.single { it.key == "activity" }.attentionDot)
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
    fun coverHidesTheBarUntilEveryCoverIsReleased() {
        val bridge = RaftNativeTabBarBridge()
        val recorder = Recorder()
        bridge.attachListener(recorder)
        val publisher = Any()
        val drawer = Any()
        val other = Any()
        val shown = raftNativeTabBarState(fiveTabs(selectedKey = "home"), visible = true)
        bridge.publish(publisher, shown) {}

        bridge.cover(drawer)
        bridge.cover(drawer)
        bridge.cover(other)
        assertEquals(shown.copy(visible = false), bridge.currentState)
        // A shell update under the cover keeps the bar hidden but carries the new tabs.
        val tasks = raftNativeTabBarState(fiveTabs(selectedKey = "tasks"), visible = true)
        bridge.publish(publisher, tasks) {}
        assertEquals(tasks.copy(visible = false), bridge.currentState)

        bridge.uncover(drawer)
        assertFalse(bridge.currentState.visible)
        bridge.uncover(other)
        assertEquals(tasks, bridge.currentState)
        bridge.uncover(other)

        assertEquals(
            listOf(true, false, false, true),
            recorder.states.drop(1).map { it.visible },
        )
    }

    @Test
    fun releasingACoverDoesNotShowARetractedBar() {
        val bridge = RaftNativeTabBarBridge()
        val publisher = Any()
        val drawer = Any()
        bridge.publish(publisher, raftNativeTabBarState(fiveTabs(selectedKey = "home"), visible = true)) {}
        bridge.cover(drawer)
        bridge.retract(publisher)
        bridge.uncover(drawer)

        assertFalse(bridge.currentState.visible)
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
