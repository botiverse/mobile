package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftMobileBuiltInPlaceIds
import build.raft.app.data.RaftRailPlace
import build.raft.app.iconName
import build.raft.app.members.iconName
import build.raft.app.settings.iconName
import build.raft.app.shell.AppShellPhoneDefaultPlaceTabs
import build.raft.app.shell.AppShellServerTabKey
import build.raft.app.shell.MobileRecentlyUsedEntry
import build.raft.app.shell.RaftAppServerMenuOverlay
import build.raft.app.shell.RaftBottomTabBar
import build.raft.app.shell.RaftBottomTabItemPayload
import build.raft.app.shell.RaftMobilePlacesPopoverTags
import build.raft.app.shell.RaftServerMenuAnchor
import build.raft.app.shell.RaftServerMenuEntry
import build.raft.app.shell.RaftServerMenuMobilePlacesConfig
import build.raft.app.shell.RaftServerMenuModel
import build.raft.app.shell.appShellDynamicPhoneTabKeys
import build.raft.app.shell.appShellMobileServerMenuEntries
import build.raft.app.shell.appShellPhoneMaxPlaceTabs
import build.raft.app.shell.raftServerMenuEntriesFor
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftMobileNavServerPopoverState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MobilePlacesServerPopoverUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val servers =
        listOf(
            RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L),
            RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 2L),
        )

    private val model =
        RaftServerMenuModel(
            servers = servers,
            currentServer = servers.first(),
            attentionEntries = setOf(RaftServerMenuEntry.Computers),
        )

    @Test
    fun normalViewShowsRecentlyUsedAndMoreHeaderAndHandlesPickAndEmptyOmission() =
        runRobolectricComposeUiTest {
            var recentItems by mutableStateOf<List<MobileRecentlyUsedEntry>>(
                listOf(
                    MobileRecentlyUsedEntry.Artifact(id = "art-1", title = "Q4 Design Spec"),
                    MobileRecentlyUsedEntry.App(
                        clientId = "11111111-1111-4111-8111-111111111111",
                        name = "Stamp",
                        iconUrl = null,
                        homepageUrl = "https://stamp.build",
                    ),
                ),
            )
            val pickedRecent = mutableListOf<MobileRecentlyUsedEntry>()
            val state = RaftMobileNavServerPopoverState().apply { show() }
            val places = RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn)
            val tabKeys =
                appShellDynamicPhoneTabKeys(
                    enabled = true,
                    mobilePlaces = places,
                    artifactsEnabled = true,
                    isGuest = false,
                    maxPlaceTabs = appShellPhoneMaxPlaceTabs(),
                )
            val entries = appShellMobileServerMenuEntries(places, tabKeys, wide = false, guest = false, artifacts = true)

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize()) {
                            RaftAppServerMenuOverlay(
                                model = model,
                                state = state,
                                anchor = RaftServerMenuAnchor.BottomBar,
                                onPick = {},
                                prefersChineseCommunity = false,
                                onCreateServerClick = {},
                                onInviteHumanClick = null,
                                screenHeight = 844.dp,
                                entries = entries,
                                mobilePlacesConfig =
                                    RaftServerMenuMobilePlacesConfig(
                                        mobilePlaces = places,
                                        artifactsEnabled = true,
                                        isGuest = false,
                                        recentlyUsed = recentItems,
                                        resolveAppIconUrl = { it },
                                        onPickRecent = { pickedRecent += it },
                                        onSaveOrder = {},
                                        onResetToDefault = {},
                                    ),
                            )
                        }
                    }
                }
            }

            waitForIdle()
            val recentBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.RECENTLY_USED_SECTION, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val serverListBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.SERVER_LIST, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val moreHeaderBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.MORE_HEADER, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            assertTrue(
                recentBounds.bottom < serverListBounds.top,
                "Expected Recently used section at the top above the server list (with a divider between), got recent=$recentBounds serverList=$serverListBounds",
            )
            assertTrue(
                serverListBounds.top - recentBounds.bottom <= 2.dp,
                "Expected only the hairline divider between Recently used and server list, got gap=${serverListBounds.top - recentBounds.bottom}",
            )
            assertTrue(
                serverListBounds.bottom < moreHeaderBounds.top,
                "Expected server list above the More section, got serverList=$serverListBounds moreHeader=$moreHeaderBounds",
            )
            onNodeWithText("Q4 Design Spec").assertIsDisplayed()
            onNodeWithText("Stamp").assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_BUTTON).assertIsDisplayed()

            // Tapping a recently used Artifact picks it and dismisses the popover.
            onNodeWithText("Q4 Design Spec").performClick()
            waitForIdle()
            assertEquals(listOf("artifact:art-1"), pickedRecent.map { it.placeKey })
            assertFalse(state.open)

            // Re-open with empty Recently used -> section and its divider are omitted completely.
            recentItems = emptyList()
            state.show()
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithTag(RaftMobilePlacesPopoverTags.RECENTLY_USED_SECTION, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
            val emptyServerListBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.SERVER_LIST, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val emptyMoreHeaderBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.MORE_HEADER, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            assertTrue(
                emptyServerListBounds.bottom < emptyMoreHeaderBounds.top,
                "Expected server list above More when Recently used is empty, got serverList=$emptyServerListBounds moreHeader=$emptyMoreHeaderBounds",
            )
            assertEquals(
                serverListBounds.height,
                emptyServerListBounds.height,
                "Expected server list height to remain unchanged when Recently used is omitted",
            )
        }

    @Test
    fun editModeHandlesMinusPlusResetDoneAndKeepsSettingsStatic() =
        runRobolectricComposeUiTest {
            var places by mutableStateOf(RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn))
            var placeTabCount by mutableStateOf(AppShellPhoneDefaultPlaceTabs)
            val savedOrders = mutableListOf<List<String>>()
            var resetCount = 0
            val state = RaftMobileNavServerPopoverState().apply { show() }

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                val tabKeys =
                    appShellDynamicPhoneTabKeys(
                        enabled = true,
                        mobilePlaces = places,
                        artifactsEnabled = true,
                        isGuest = false,
                        maxPlaceTabs = placeTabCount,
                    )
                val entries = appShellMobileServerMenuEntries(places, tabKeys, wide = false, guest = false, artifacts = true)
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize()) {
                            RaftAppServerMenuOverlay(
                                model = model,
                                state = state,
                                anchor = RaftServerMenuAnchor.BottomBar,
                                onPick = {},
                                prefersChineseCommunity = false,
                                onCreateServerClick = {},
                                onInviteHumanClick = null,
                                screenHeight = 844.dp,
                                entries = entries,
                                mobilePlacesConfig =
                                    RaftServerMenuMobilePlacesConfig(
                                        mobilePlaces = places,
                                        artifactsEnabled = true,
                                        isGuest = false,
                                        recentlyUsed = emptyList(),
                                        resolveAppIconUrl = { it },
                                        tabBarPlaceCount = placeTabCount,
                                        onPickRecent = {},
                                        onSaveOrder = { next ->
                                            savedOrders += next
                                            places = next.map(RaftRailPlace::builtIn)
                                        },
                                        onSaveTabCount = { nextCount ->
                                            placeTabCount = nextCount
                                        },
                                        onResetToDefault = {
                                            resetCount += 1
                                            placeTabCount = AppShellPhoneDefaultPlaceTabs
                                            places = RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn)
                                        },
                                    ),
                            )
                        }
                    }
                }
            }

            waitForIdle()
            // Enter Edit mode via More header Edit button: defaults to 4 places (+ Server = 5 tabs) on Android too.
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_SURFACE, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_SETTINGS_TILE).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.moreTile("artifacts"), useUnmergedTree = true).assertIsDisplayed()
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.removeBadge("settings")).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.addBadge("settings")).fetchSemanticsNodes().size)

            // Tap − on Home ("chat") at default 4 tabs: moves chat to More[0], promotes artifacts into slot 4.
            onNodeWithTag(RaftMobilePlacesPopoverTags.removeBadge("chat")).assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(4, placeTabCount)
            assertEquals(
                listOf("activity", "tasks", "search", "artifacts", "chat", "members", "computers", "apps"),
                savedOrders.last(),
            )
            onNodeWithTag(RaftMobilePlacesPopoverTags.tabTile("artifacts"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.moreTile("chat"), useUnmergedTree = true).assertIsDisplayed()

            // Tap + on Computers on Android: manually expands Tab bar from 4 -> 5 places (+ Server = 6 total).
            onNodeWithTag(RaftMobilePlacesPopoverTags.addBadge("computers")).performClick()
            waitForIdle()
            assertEquals(5, placeTabCount)
            assertEquals(
                listOf("activity", "tasks", "search", "artifacts", "computers", "chat", "members", "apps"),
                savedOrders.last(),
            )
            onNodeWithTag(RaftMobilePlacesPopoverTags.tabTile("computers"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.tabTile("artifacts"), useUnmergedTree = true).assertIsDisplayed()

            // Tap − on Computers while at 5 tabs: shrinks Tab bar back from 5 -> 4 places (+ Server = 5 total).
            onNodeWithTag(RaftMobilePlacesPopoverTags.removeBadge("computers")).assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(4, placeTabCount)
            assertEquals(
                listOf("activity", "tasks", "search", "artifacts", "computers", "chat", "members", "apps"),
                savedOrders.last(),
            )
            onNodeWithTag(RaftMobilePlacesPopoverTags.moreTile("computers"), useUnmergedTree = true).assertIsDisplayed()

            // Expand again, then tap Reset: restores both default order and default 4 place tabs.
            onNodeWithTag(RaftMobilePlacesPopoverTags.addBadge("computers")).performClick()
            waitForIdle()
            assertEquals(5, placeTabCount)
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_RESET_BUTTON).performClick()
            waitForIdle()
            assertEquals(1, resetCount)
            assertEquals(4, placeTabCount)
            onNodeWithTag(RaftMobilePlacesPopoverTags.tabTile("chat"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.moreTile("artifacts"), useUnmergedTree = true).assertIsDisplayed()

            // Tap Done: returns to normal popover view while keeping popover open.
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_DONE_BUTTON).performClick()
            waitForIdle()
            assertTrue(state.open)
            assertEquals(
                0,
                onAllNodesWithTag(RaftMobilePlacesPopoverTags.EDIT_SURFACE, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
            onNodeWithTag(RaftMobilePlacesPopoverTags.MORE_HEADER, useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun serverListCapsAtFiveRowsLeavingRoomForRecentlyUsedAndMore() =
        runRobolectricComposeUiTest {
            val manyServers =
                (1..12).map { index ->
                    RaftServerSwitcherItem(
                        id = "s$index",
                        name = "Server $index",
                        slug = "server-$index",
                        activityUnreadCount = if (index == 2) 3L else 0L,
                    )
                }
            val manyModel =
                RaftServerMenuModel(
                    servers = manyServers,
                    currentServer = manyServers.first(),
                    attentionEntries = emptySet(),
                )
            val places = RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn)
            val tabKeys =
                appShellDynamicPhoneTabKeys(
                    enabled = true,
                    mobilePlaces = places,
                    artifactsEnabled = true,
                    isGuest = false,
                    maxPlaceTabs = AppShellPhoneDefaultPlaceTabs,
                )
            val entries = appShellMobileServerMenuEntries(places, tabKeys, wide = false, guest = false, artifacts = true)
            val state = RaftMobileNavServerPopoverState().apply { show() }

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize()) {
                            RaftAppServerMenuOverlay(
                                model = manyModel,
                                state = state,
                                anchor = RaftServerMenuAnchor.BottomBar,
                                onPick = {},
                                prefersChineseCommunity = false,
                                onCreateServerClick = {},
                                onInviteHumanClick = null,
                                screenHeight = 844.dp,
                                entries = entries,
                                mobilePlacesConfig =
                                    RaftServerMenuMobilePlacesConfig(
                                        mobilePlaces = places,
                                        artifactsEnabled = true,
                                        isGuest = false,
                                        recentlyUsed =
                                            listOf(
                                                MobileRecentlyUsedEntry.Artifact(id = "art-1", title = "Release Notes"),
                                            ),
                                        resolveAppIconUrl = { it },
                                        onPickRecent = {},
                                        onSaveOrder = {},
                                        onResetToDefault = {},
                                    ),
                            )
                        }
                    }
                }
            }

            waitForIdle()
            val recentBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.RECENTLY_USED_SECTION, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val serverListBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.SERVER_LIST, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val moreHeaderBounds =
                onNodeWithTag(RaftMobilePlacesPopoverTags.MORE_HEADER, useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            val listHeight = serverListBounds.height
            assertTrue(
                listHeight <= 310.dp,
                "Expected server switcher list to cap around 5 rows instead of stretching full-screen, got $listHeight",
            )
            assertTrue(
                recentBounds.bottom < serverListBounds.top,
                "Expected Recently used section above the 5-row capped server list, got recent=$recentBounds serverList=$serverListBounds",
            )
            assertTrue(
                serverListBounds.bottom < moreHeaderBounds.top,
                "Expected server list above More header, got serverList=$serverListBounds moreHeader=$moreHeaderBounds",
            )
        }

    @Test
    fun recentlyUsedSectionDeduplicatesShortAndCanonicalArtifactIds() =
        runRobolectricComposeUiTest {
            val shortId = "2QthvfPeGfDnqwr7LG6itx"
            val canonicalId = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
            val state = RaftMobileNavServerPopoverState().apply { show() }
            val places = RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn)
            val tabKeys =
                appShellDynamicPhoneTabKeys(
                    enabled = true,
                    mobilePlaces = places,
                    artifactsEnabled = true,
                    isGuest = false,
                    maxPlaceTabs = AppShellPhoneDefaultPlaceTabs,
                )
            val entries = appShellMobileServerMenuEntries(places, tabKeys, wide = false, guest = false, artifacts = true)

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize()) {
                            RaftAppServerMenuOverlay(
                                model = model,
                                state = state,
                                anchor = RaftServerMenuAnchor.BottomBar,
                                onPick = {},
                                prefersChineseCommunity = false,
                                onCreateServerClick = {},
                                onInviteHumanClick = null,
                                screenHeight = 844.dp,
                                entries = entries,
                                mobilePlacesConfig =
                                    RaftServerMenuMobilePlacesConfig(
                                        mobilePlaces = places,
                                        artifactsEnabled = true,
                                        isGuest = false,
                                        recentlyUsed =
                                            listOf(
                                                MobileRecentlyUsedEntry.Artifact(
                                                    id = shortId,
                                                    title = "1.31.0 release notes — review",
                                                ),
                                                MobileRecentlyUsedEntry.Artifact(
                                                    id = canonicalId,
                                                    title = "1.31.0 release notes — review",
                                                ),
                                            ),
                                        resolveAppIconUrl = { it },
                                        onPickRecent = {},
                                        onSaveOrder = {},
                                        onResetToDefault = {},
                                    ),
                            )
                        }
                    }
                }
            }

            waitForIdle()
            assertEquals(
                1,
                onAllNodesWithText("1.31.0 release notes — review").fetchSemanticsNodes().size,
            )
        }

    @Test
    fun guestWithoutArtifactsDisablesRemoveButtonInEditMode() =
        runRobolectricComposeUiTest {
            val places = RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn)
            val state = RaftMobileNavServerPopoverState().apply { show() }
            val tabKeys =
                appShellDynamicPhoneTabKeys(
                    enabled = true,
                    mobilePlaces = places,
                    artifactsEnabled = false,
                    isGuest = true,
                    maxPlaceTabs = appShellPhoneMaxPlaceTabs(),
                )
            val entries = appShellMobileServerMenuEntries(places, tabKeys, wide = false, guest = true, artifacts = false)

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize()) {
                            RaftAppServerMenuOverlay(
                                model = model,
                                state = state,
                                anchor = RaftServerMenuAnchor.BottomBar,
                                onPick = {},
                                prefersChineseCommunity = false,
                                onCreateServerClick = {},
                                onInviteHumanClick = null,
                                screenHeight = 844.dp,
                                entries = entries,
                                mobilePlacesConfig =
                                    RaftServerMenuMobilePlacesConfig(
                                        mobilePlaces = places,
                                        artifactsEnabled = false,
                                        isGuest = true,
                                        recentlyUsed = emptyList(),
                                        resolveAppIconUrl = { it },
                                        initialEditMode = true,
                                        onPickRecent = {},
                                        onSaveOrder = {},
                                        onResetToDefault = {},
                                    ),
                            )
                        }
                    }
                }
            }

            waitForIdle()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_SURFACE, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.removeBadge("chat")).assertIsNotEnabled()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_SETTINGS_TILE).assertIsDisplayed()
        }

    @Test
    fun flagOffLeavesLegacyPopoverUnchanged() =
        runRobolectricComposeUiTest {
            val state = RaftMobileNavServerPopoverState().apply { show() }
            val entries = raftServerMenuEntriesFor(model.entries, wide = false, guest = false, artifacts = true)

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                    RaftDesignSystemTheme {
                        Box(Modifier.fillMaxSize()) {
                            RaftAppServerMenuOverlay(
                                model = model,
                                state = state,
                                anchor = RaftServerMenuAnchor.BottomBar,
                                onPick = {},
                                prefersChineseCommunity = false,
                                onCreateServerClick = {},
                                onInviteHumanClick = null,
                                screenHeight = 844.dp,
                                entries = entries,
                                mobilePlacesConfig = null,
                            )
                        }
                    }
                }
            }

            waitForIdle()
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.RECENTLY_USED_SECTION, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.MORE_HEADER, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.EDIT_BUTTON).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.EDIT_SURFACE, useUnmergedTree = true).fetchSemanticsNodes().size)
        }

    @Test
    fun androidBottomBarLongPressTriggersEditCallbackForPlaceTabsOnly() =
        runRobolectricComposeUiTest {
            val longPressedKeys = mutableListOf<String>()
            val items =
                listOf(
                    RaftBottomTabItemPayload(key = "home", label = "Home", iconName = RaftLucideIconName.House, selected = true),
                    RaftBottomTabItemPayload(key = "activity", label = "Activity", iconName = RaftLucideIconName.Activity),
                    RaftBottomTabItemPayload(key = AppShellServerTabKey, label = "Botiverse", iconName = RaftLucideIconName.Server, opensPanel = true),
                )

            setContent {
                RaftDesignSystemTheme {
                    RaftBottomTabBar(
                        items = items,
                        onSelect = {},
                        onLongPress = { key -> longPressedKeys += key },
                    )
                }
            }

            waitForIdle()
            onNodeWithText("Activity").performTouchInput { longClick() }
            waitForIdle()
            assertEquals(listOf("activity"), longPressedKeys)

            // Server tab must never invoke the place-tab edit long-press handler.
            onNodeWithText("Botiverse").performTouchInput { longClick() }
            waitForIdle()
            assertEquals(listOf("activity"), longPressedKeys)
        }
}
