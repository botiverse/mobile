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
        // The iOS bar draws the web's Lucide glyphs from the asset catalog (lucide-<wire name>).
        assertEquals(
            listOf("lucide-house", "lucide-activity", "lucide-square-check-big", "lucide-users", "lucide-settings"),
            state.items.map { it.imageAssetName },
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
        assertEquals(listOf("home", "activity", "tasks", AppShellSearchTabKey, "more"), state.items.map { it.key })
        val byKey = state.items.associateBy { it.key }
        // UIKit has no small dot: the iOS bar shows Activity's unread count, capped like the app.
        assertEquals(120, byKey.getValue("activity").badgeCount)
        assertEquals("99+", byKey.getValue("activity").badgeValue)
        assertFalse(byKey.getValue("activity").attentionDot)
        assertFalse(byKey.getValue("tasks").attentionDot)
        // Search sits directly left of More as an ordinary tab (stdrc option B).
        val search = state.items[3]
        assertEquals(AppShellSearchTabKey, search.key)
        assertEquals("label-search", search.title)
        assertFalse(search.searchAction)
        assertEquals("magnifyingglass", search.systemImageName)
        assertEquals("home", state.selectedKey)
        assertTrue(state.items.none { it.searchAction })
        // The selected Search tab is published as the selection.
        val onSearch =
            raftNativeTabBarState(
                listOf(RaftBottomTabItemPayload(key = AppShellSearchTabKey, selected = true)),
                visible = true,
            )
        assertEquals(AppShellSearchTabKey, onSearch.selectedKey)
        // The selected Activity tab never carries the dot.
        val onActivity = raftNativeTabBarState(fiveTabs(selectedKey = "activity", activityUnreadCount = 3), visible = true)
        assertFalse(onActivity.items.single { it.key == "activity" }.attentionDot)
        assertNull(onActivity.items.single { it.key == "activity" }.badgeValue)
    }

    @Test
    fun activityDotCarriesItsCountForTheNativeBarOnly() {
        val activity = fiveTabs(selectedKey = "home", activityUnreadCount = 7).single { it.key == "activity" }
        // The Compose bar keeps the dot and no number (raft-design MobileNav).
        assertTrue(activity.attentionDot)
        assertEquals(0, activity.badgeCount)
        assertEquals(7, activity.nativeBadgeCount)
        val native = raftNativeTabBarItem(activity)
        assertEquals("7", native.badgeValue)
        assertFalse(native.attentionDot)
        // No unread: no dot, no count.
        val quiet = fiveTabs(selectedKey = "home").single { it.key == "activity" }
        assertEquals(0, quiet.nativeBadgeCount)
        assertNull(raftNativeTabBarItem(quiet).badgeValue)
        // A dot without a known count keeps no badge on the native bar (never an empty badge).
        val flagOnly = raftNativeTabBarItem(RaftBottomTabItemPayload(key = "more", attentionDot = true))
        assertNull(flagOnly.badgeValue)
        assertTrue(flagOnly.attentionDot)
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
    fun bandKeepsTheEdgeTheHostLaidTheBarOutOn() {
        val bridge = RaftNativeTabBarBridge()
        bridge.updateBand("bottom", 83.0)
        assertEquals(RaftNativeTabBarBand(RaftNativeTabBarEdge.Bottom, 83.0), bridge.band.value)
        assertEquals(83.0, bridge.bottomInsetPoints.value)

        bridge.updateBand("top", 96.0)
        assertEquals(RaftNativeTabBarBand(RaftNativeTabBarEdge.Top, 96.0), bridge.band.value)
        // A top bar reserves no bottom band.
        assertEquals(0.0, bridge.bottomInsetPoints.value)

        bridge.updateBand("diagonal", 10.0)
        bridge.updateBand("right", Double.NaN)
        assertEquals(RaftNativeTabBarBand(RaftNativeTabBarEdge.Top, 96.0), bridge.band.value)
    }

    @Test
    fun nonBottomBarsReserveTheirOwnEdgeNotTheBottom() {
        val bottomSafeArea = 34.dp
        // Bottom bar: unchanged contract.
        assertEquals(
            83.dp + RaftAppSpacing.Lg,
            raftNativeHostTabBarContentInset(RaftNativeTabBarBand(RaftNativeTabBarEdge.Bottom, 83.0), bottomSafeArea),
        )
        // A top or side bar: the bottom keeps only the safe area (no full-height band).
        for (edge in listOf(RaftNativeTabBarEdge.Top, RaftNativeTabBarEdge.Left, RaftNativeTabBarEdge.Right)) {
            assertEquals(
                bottomSafeArea + RaftAppSpacing.Lg,
                raftNativeHostTabBarContentInset(RaftNativeTabBarBand(edge, 797.0), bottomSafeArea),
            )
        }
        assertEquals(
            RaftNativeTabBarEdgeInsets(top = 96.dp),
            raftNativeTabBarEdgeInsets(RaftNativeTabBarBand(RaftNativeTabBarEdge.Top, 96.0), visible = true),
        )
        assertEquals(
            RaftNativeTabBarEdgeInsets(right = 100.dp),
            raftNativeTabBarEdgeInsets(RaftNativeTabBarBand(RaftNativeTabBarEdge.Right, 100.0), visible = true),
        )
        assertEquals(
            RaftNativeTabBarEdgeInsets(left = 80.dp),
            raftNativeTabBarEdgeInsets(RaftNativeTabBarBand(RaftNativeTabBarEdge.Left, 80.0), visible = true),
        )
        // Hidden bars and bottom bars take nothing from the other edges.
        assertEquals(
            RaftNativeTabBarEdgeInsets.Zero,
            raftNativeTabBarEdgeInsets(RaftNativeTabBarBand(RaftNativeTabBarEdge.Right, 100.0), visible = false),
        )
        assertEquals(
            RaftNativeTabBarEdgeInsets.Zero,
            raftNativeTabBarEdgeInsets(RaftNativeTabBarBand(RaftNativeTabBarEdge.Bottom, 83.0), visible = true),
        )
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
