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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftMobileBuiltInPlaceIds
import build.raft.app.data.RaftRailPlace
import build.raft.app.iconName
import build.raft.app.settings.iconName
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
            onNodeWithTag(RaftMobilePlacesPopoverTags.RECENTLY_USED_SECTION, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("Q4 Design Spec").assertIsDisplayed()
            onNodeWithText("Stamp").assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.MORE_HEADER, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_BUTTON).assertIsDisplayed()

            // Tapping a recently used Artifact picks it and dismisses the popover.
            onNodeWithText("Q4 Design Spec").performClick()
            waitForIdle()
            assertEquals(listOf("artifact:art-1"), pickedRecent.map { it.placeKey })
            assertFalse(state.open)

            // Re-open with empty Recently used -> section is omitted completely.
            recentItems = emptyList()
            state.show()
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithTag(RaftMobilePlacesPopoverTags.RECENTLY_USED_SECTION, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
            onNodeWithTag(RaftMobilePlacesPopoverTags.MORE_HEADER, useUnmergedTree = true).assertIsDisplayed()
        }

    @Test
    fun editModeHandlesMinusPlusResetDoneAndKeepsSettingsStatic() =
        runRobolectricComposeUiTest {
            var places by mutableStateOf(RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn))
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
                        maxPlaceTabs = appShellPhoneMaxPlaceTabs(),
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
                                        onPickRecent = {},
                                        onSaveOrder = { next ->
                                            savedOrders += next
                                            places = next.map(RaftRailPlace::builtIn)
                                        },
                                        onResetToDefault = {
                                            resetCount += 1
                                            places = RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn)
                                        },
                                    ),
                            )
                        }
                    }
                }
            }

            waitForIdle()
            // Enter Edit mode via More header Edit button.
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_BUTTON).performClick()
            waitForIdle()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_SURFACE, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_SETTINGS_TILE).assertIsDisplayed()
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.removeBadge("settings")).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithTag(RaftMobilePlacesPopoverTags.addBadge("settings")).fetchSemanticsNodes().size)

            // Tap − on Home ("chat"): on Android (5+1=6), moves chat to More[0], promotes members to Tab bar slot 5.
            onNodeWithTag(RaftMobilePlacesPopoverTags.removeBadge("chat")).assertIsEnabled().performClick()
            waitForIdle()
            assertEquals(
                listOf("activity", "tasks", "search", "artifacts", "members", "chat", "computers", "apps"),
                savedOrders.last(),
            )
            onNodeWithTag(RaftMobilePlacesPopoverTags.tabTile("members"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.moreTile("chat"), useUnmergedTree = true).assertIsDisplayed()

            // Tap + on Computers: moves computers to slot 5, bumps members to More[0].
            onNodeWithTag(RaftMobilePlacesPopoverTags.addBadge("computers")).performClick()
            waitForIdle()
            assertEquals(
                listOf("activity", "tasks", "search", "artifacts", "computers", "members", "chat", "apps"),
                savedOrders.last(),
            )
            onNodeWithTag(RaftMobilePlacesPopoverTags.tabTile("computers"), useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag(RaftMobilePlacesPopoverTags.moreTile("members"), useUnmergedTree = true).assertIsDisplayed()

            // Tap Reset: restores default order.
            onNodeWithTag(RaftMobilePlacesPopoverTags.EDIT_RESET_BUTTON).performClick()
            waitForIdle()
            assertEquals(1, resetCount)
            onNodeWithTag(RaftMobilePlacesPopoverTags.tabTile("chat"), useUnmergedTree = true).assertIsDisplayed()

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
