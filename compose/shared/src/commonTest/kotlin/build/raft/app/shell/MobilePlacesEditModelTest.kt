package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import build.raft.app.data.RaftMobileBuiltInPlaceIds
import build.raft.app.data.RaftRailPlace
import build.raft.app.data.RaftRailPlaceApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobilePlacesEditModelTest {
    private val stampId = "11111111-1111-4111-8111-111111111111"
    private val defaultVisibleAll = RaftMobileBuiltInPlaceIds

    @Test
    fun fromVisibleIdsSplitsFirstFourIntoTabBarAndRestIntoMore() {
        val state = MobilePlacesEditState.fromVisibleIds(defaultVisibleAll)
        assertEquals(listOf("chat", "activity", "tasks", "search"), state.tabBarIds)
        assertEquals(listOf("artifacts", "members", "computers", "apps"), state.moreIds)
        assertEquals(defaultVisibleAll, state.orderedVisibleIds)
        assertTrue(state.canRemoveFromTabBar)
    }

    @Test
    fun removeFromTabBarMovesPlaceToFrontOfMoreAndPromotesFirstMoreIntoSlotFour() {
        val initial = MobilePlacesEditState.fromVisibleIds(defaultVisibleAll)

        // Removing slot 0 ("chat"): "chat" lands at More[0], "artifacts" fills slot 4.
        val afterRemoveChat = initial.removeFromTabBar("chat")
        assertEquals(listOf("activity", "tasks", "search", "artifacts"), afterRemoveChat.tabBarIds)
        assertEquals(listOf("chat", "members", "computers", "apps"), afterRemoveChat.moreIds)
        assertEquals(afterRemoveChat, MobilePlacesEditState.fromVisibleIds(afterRemoveChat.orderedVisibleIds))

        // Removing slot 4 ("search") from default: "search" lands at More[0], "artifacts" fills slot 4.
        val afterRemoveSearch = initial.removeFromTabBar("search")
        assertEquals(listOf("chat", "activity", "tasks", "artifacts"), afterRemoveSearch.tabBarIds)
        assertEquals(listOf("search", "members", "computers", "apps"), afterRemoveSearch.moreIds)
        assertEquals(afterRemoveSearch, MobilePlacesEditState.fromVisibleIds(afterRemoveSearch.orderedVisibleIds))

        // Unknown / More place is a no-op.
        assertEquals(initial, initial.removeFromTabBar("apps"))
        assertEquals(initial, initial.removeFromTabBar("unknown"))
    }

    @Test
    fun addToTabBarAppendsWhenUnderFourAndBumpsSlotFourToFrontOfMoreWhenFull() {
        // Under 4 tabs: appends without bumping.
        val partial = MobilePlacesEditState(tabBarIds = listOf("chat", "activity"), moreIds = listOf("tasks", "search"))
        val afterAppend = partial.addToTabBar("search")
        assertEquals(listOf("chat", "activity", "search"), afterAppend.tabBarIds)
        assertEquals(listOf("tasks"), afterAppend.moreIds)

        // Full 4 tabs: puts added place at slot 4 and bumps old slot 4 ("search") to More[0].
        val full = MobilePlacesEditState.fromVisibleIds(defaultVisibleAll)
        val afterBump = full.addToTabBar("computers")
        assertEquals(listOf("chat", "activity", "tasks", "computers"), afterBump.tabBarIds)
        assertEquals(listOf("search", "artifacts", "members", "apps"), afterBump.moreIds)
        assertEquals(afterBump, MobilePlacesEditState.fromVisibleIds(afterBump.orderedVisibleIds))

        // Round-trip inverse: adding More[0] then removing it restores the original order.
        val addedFirstMore = full.addToTabBar("artifacts")
        assertEquals(listOf("chat", "activity", "tasks", "artifacts"), addedFirstMore.tabBarIds)
        assertEquals(listOf("search", "members", "computers", "apps"), addedFirstMore.moreIds)
        assertEquals(full, addedFirstMore.removeFromTabBar("artifacts"))

        // Already on tab bar is a no-op.
        assertEquals(full, full.addToTabBar("chat"))
    }

    @Test
    fun removeIsDisabledForLastTabOrWhenMoreIsEmpty() {
        val singleTab = MobilePlacesEditState(tabBarIds = listOf("chat"), moreIds = listOf("tasks"))
        assertFalse(singleTab.canRemoveFromTabBar)
        assertEquals(singleTab, singleTab.removeFromTabBar("chat"))

        // Guest with Artifacts OFF only sees 4 built-ins -> More is empty -> remove disabled.
        val guestNoArtifacts =
            MobilePlacesEditState.fromMobilePlaces(
                mobilePlaces = defaultVisibleAll.map(RaftRailPlace::builtIn),
                artifactsEnabled = false,
                isGuest = true,
            )
        assertEquals(listOf("chat", "activity", "tasks", "search"), guestNoArtifacts.tabBarIds)
        assertEquals(emptyList(), guestNoArtifacts.moreIds)
        assertFalse(guestNoArtifacts.canRemoveFromTabBar)
        assertEquals(guestNoArtifacts, guestNoArtifacts.removeFromTabBar("chat"))
    }

    @Test
    fun reorderWithinTabBarAndWithinMoreStayIsolated() {
        val initial = MobilePlacesEditState.fromVisibleIds(defaultVisibleAll)

        val reorderedTabs = initial.reorderWithinTabBar(0, 3)
        assertEquals(listOf("activity", "tasks", "search", "chat"), reorderedTabs.tabBarIds)
        assertEquals(initial.moreIds, reorderedTabs.moreIds)

        val reorderedMore = reorderedTabs.reorderWithinMore(3, 1)
        assertEquals(reorderedTabs.tabBarIds, reorderedMore.tabBarIds)
        assertEquals(listOf("artifacts", "apps", "members", "computers"), reorderedMore.moreIds)

        // Out-of-bounds or same-index moves are no-ops.
        assertEquals(initial, initial.reorderWithinTabBar(-1, 2))
        assertEquals(initial, initial.reorderWithinTabBar(1, 1))
        assertEquals(initial, initial.reorderWithinMore(0, 10))
    }

    @Test
    fun moveAcrossSectionsHandlesAllFourDirectionsAndBumping() {
        val initial = MobilePlacesEditState.fromVisibleIds(defaultVisibleAll)

        // More -> TabBar at index 1: inserts at 1, bumps old slot 4 ("search") to More[0].
        val moreToTab = initial.moveAcrossSections("members", MobilePlacesEditSection.TabBar, 1)
        assertEquals(listOf("chat", "members", "activity", "tasks"), moreToTab.tabBarIds)
        assertEquals(listOf("search", "artifacts", "computers", "apps"), moreToTab.moreIds)

        // TabBar -> More at index 2: promotes More[0] ("search") to slot 4, inserts "chat" at More[2].
        val tabToMore = moreToTab.moveAcrossSections("chat", MobilePlacesEditSection.More, 2)
        assertEquals(listOf("members", "activity", "tasks", "search"), tabToMore.tabBarIds)
        assertEquals(listOf("artifacts", "computers", "chat", "apps"), tabToMore.moreIds)

        // Same-section drag via moveAcrossSections.
        val withinTab = tabToMore.moveAcrossSections("search", MobilePlacesEditSection.TabBar, 0)
        assertEquals(listOf("search", "members", "activity", "tasks"), withinTab.tabBarIds)
        assertEquals(tabToMore.moreIds, withinTab.moreIds)

        val withinMore = withinTab.moveAcrossSections("apps", MobilePlacesEditSection.More, 0)
        assertEquals(withinTab.tabBarIds, withinMore.tabBarIds)
        assertEquals(listOf("apps", "artifacts", "computers", "chat"), withinMore.moreIds)

        // Under-full TabBar accepts More -> TabBar without bumping.
        val partial = MobilePlacesEditState(tabBarIds = listOf("chat", "tasks"), moreIds = listOf("activity", "search"))
        val partialInsert = partial.moveAcrossSections("search", MobilePlacesEditSection.TabBar, 1)
        assertEquals(listOf("chat", "search", "tasks"), partialInsert.tabBarIds)
        assertEquals(listOf("activity"), partialInsert.moreIds)
    }

    @Test
    fun accessibleMoveLeftAndMoveRightTraverseAcrossTabBarAndMoreBoundary() {
        val initial = MobilePlacesEditState.fromVisibleIds(defaultVisibleAll)
        assertFalse(initial.canMoveLeft("chat"))
        assertTrue(initial.canMoveRight("chat"))
        assertTrue(initial.canMoveLeft("apps"))
        assertFalse(initial.canMoveRight("apps"))

        // Moving More[0] ("artifacts") left crosses into Tab bar slot 4, bumping "search" to More[0].
        val crossedLeft = initial.moveLeft("artifacts")
        assertEquals(listOf("chat", "activity", "tasks", "artifacts"), crossedLeft.tabBarIds)
        assertEquals(listOf("search", "members", "computers", "apps"), crossedLeft.moreIds)

        // Moving Tab bar slot 4 ("artifacts") right crosses back into More[0].
        assertEquals(initial, crossedLeft.moveRight("artifacts"))
    }

    @Test
    fun mergedWithStoredKeysPreservesHiddenBuiltInsAndApps() {
        val stampApp = RaftRailPlaceApp(stampId, "Stamp", null, "https://stamp.build")
        val storedPlaces =
            listOf(
                RaftRailPlace.builtIn("chat"),
                RaftRailPlace.builtIn("activity"),
                RaftRailPlace.builtIn("tasks"),
                RaftRailPlace.builtIn("search"),
                RaftRailPlace.builtIn("artifacts"),
                RaftRailPlace.app(stampApp),
                RaftRailPlace.builtIn("members"),
                RaftRailPlace.builtIn("computers"),
                RaftRailPlace.builtIn("apps"),
            )
        val storedKeys = storedPlaces.map { it.key }

        // Member with Artifacts OFF edits computers onto the tab bar; hidden `artifacts` & `app:$stampId` stay preserved.
        val editNoArtifacts =
            MobilePlacesEditState.fromMobilePlaces(
                mobilePlaces = storedPlaces,
                artifactsEnabled = false,
                isGuest = false,
            )
        assertEquals(listOf("chat", "activity", "tasks", "search"), editNoArtifacts.tabBarIds)
        assertEquals(listOf("members", "computers", "apps"), editNoArtifacts.moreIds)

        val updated = editNoArtifacts.addToTabBar("computers")
        val merged = updated.mergedWithStoredKeys(storedKeys)
        assertEquals(
            listOf("chat", "activity", "tasks", "computers", "artifacts", "app:$stampId", "search", "members", "apps"),
            merged,
        )

        // Guest with Artifacts OFF reorders their 4 visible tabs; all hidden built-ins & apps stay intact.
        val guestEdit =
            MobilePlacesEditState.fromMobilePlaces(
                mobilePlaces = storedPlaces,
                artifactsEnabled = false,
                isGuest = true,
            )
        val guestReordered = guestEdit.reorderWithinTabBar(3, 0)
        val guestMerged = guestReordered.mergedWithStoredKeys(storedKeys)
        assertEquals(
            listOf("search", "chat", "activity", "tasks", "artifacts", "app:$stampId", "members", "computers", "apps"),
            guestMerged,
        )
    }

    @Test
    fun findMobilePlacesDropTargetPrefersDirectHitAndHandlesGutterTolerance() {
        val slots =
            mapOf(
                MobilePlacesSlotKey(MobilePlacesEditSection.TabBar, 0, "chat") to Rect(0f, 0f, 80f, 60f),
                MobilePlacesSlotKey(MobilePlacesEditSection.TabBar, 1, "activity") to Rect(88f, 0f, 168f, 60f),
                MobilePlacesSlotKey(MobilePlacesEditSection.More, 0, "artifacts") to Rect(0f, 100f, 80f, 160f),
            )

        assertEquals(
            MobilePlacesDropTarget(MobilePlacesEditSection.TabBar, 1),
            findMobilePlacesDropTarget(Offset(120f, 30f), slots),
        )
        assertEquals(
            MobilePlacesDropTarget(MobilePlacesEditSection.More, 0),
            findMobilePlacesDropTarget(Offset(40f, 130f), slots),
        )
        // Pointer in the 8px gutter near slot 0 snaps to nearest slot within tolerance.
        assertEquals(
            MobilePlacesDropTarget(MobilePlacesEditSection.TabBar, 0),
            findMobilePlacesDropTarget(Offset(82f, 30f), slots, gapTolerancePx = 16f),
        )
        // Far away pointer (e.g. over static Settings tile outside registered slots) returns null.
        assertNull(findMobilePlacesDropTarget(Offset(300f, 300f), slots, gapTolerancePx = 16f))
    }

    @Test
    fun androidDefaultsToFourPlaceTabsAllowsAddingFifthAndShrinkingBackToFour() {
        // Android (maxTabBarCount = 5) still defaults to 4 place tabs (+ Server = 5 total).
        val initial = MobilePlacesEditState.fromVisibleIds(defaultVisibleAll, maxTabBarCount = 5)
        assertEquals(listOf("chat", "activity", "tasks", "search"), initial.tabBarIds)
        assertEquals(listOf("artifacts", "members", "computers", "apps"), initial.moreIds)
        assertEquals(4, initial.defaultTabBarCount)
        assertEquals(5, initial.maxTabBarCount)

        // Manually tapping + on a More place expands Tab bar from 4 -> 5 (+ Server = 6 total) without bumping.
        val expanded = initial.addToTabBar("artifacts")
        assertEquals(listOf("chat", "activity", "tasks", "search", "artifacts"), expanded.tabBarIds)
        assertEquals(listOf("members", "computers", "apps"), expanded.moreIds)

        // Persisted tabBarCount = 5 restores all 5 place tabs cleanly on reload.
        val restored =
            MobilePlacesEditState.fromVisibleIds(
                visibleIds = expanded.orderedVisibleIds,
                maxTabBarCount = 5,
                tabBarCount = 5,
            )
        assertEquals(expanded, restored)

        // Tapping + while already at 5 swaps slot 5 without exceeding 5.
        val swappedAtMax = expanded.addToTabBar("computers")
        assertEquals(listOf("chat", "activity", "tasks", "search", "computers"), swappedAtMax.tabBarIds)
        assertEquals(listOf("artifacts", "members", "apps"), swappedAtMax.moreIds)

        // Tapping − while above default (5 > 4) shrinks Tab bar back to 4 without pulling from More.
        val shrunk = swappedAtMax.removeFromTabBar("computers")
        assertEquals(listOf("chat", "activity", "tasks", "search"), shrunk.tabBarIds)
        assertEquals(listOf("computers", "artifacts", "members", "apps"), shrunk.moreIds)

        // Dragging TabBar -> More while above default (5 > 4) also shrinks Tab bar back to 4.
        val draggedBack = swappedAtMax.moveAcrossSections("computers", MobilePlacesEditSection.More, 1)
        assertEquals(listOf("chat", "activity", "tasks", "search"), draggedBack.tabBarIds)
        assertEquals(listOf("artifacts", "computers", "members", "apps"), draggedBack.moreIds)

        // Even when More is empty at 5 tabs (e.g. guest with Artifacts ON), − stays enabled to shrink 5 -> 4.
        val fiveVisibleOnly =
            MobilePlacesEditState.fromVisibleIds(
                visibleIds = listOf("chat", "activity", "tasks", "search", "artifacts"),
                maxTabBarCount = 5,
                tabBarCount = 5,
            )
        assertTrue(fiveVisibleOnly.moreIds.isEmpty())
        assertTrue(fiveVisibleOnly.canRemoveFromTabBar)
        val guestShrunk = fiveVisibleOnly.removeFromTabBar("artifacts")
        assertEquals(listOf("chat", "activity", "tasks", "search"), guestShrunk.tabBarIds)
        assertEquals(listOf("artifacts"), guestShrunk.moreIds)
    }
}
