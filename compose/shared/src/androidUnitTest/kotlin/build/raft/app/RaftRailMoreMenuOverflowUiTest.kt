package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.RaftRailPlace
import build.raft.app.data.RaftRailPlaceApp
import build.raft.app.data.RaftRailPlacesSnapshot
import build.raft.app.data.RailPlacesDataSource
import build.raft.app.data.RailPlacesSaveWireResult
import build.raft.app.data.SharedRailPlacesStore
import build.raft.app.shell.RaftBottomTabItemPayload
import build.raft.app.shell.RaftDesktopRailPlacesHost
import build.raft.app.shell.RaftNavigationRailTabBar
import build.raft.app.shell.RaftRailMoreEditTag
import build.raft.app.shell.RaftRailMoreItemKey
import build.raft.app.shell.RaftRailMoreMenuTag
import build.raft.app.shell.RaftRailMorePlaceTagPrefix
import build.raft.app.shell.RaftRailMoreScrollTag
import build.raft.app.shell.RaftRailReorderableTabTagPrefix
import build.raft.designsystem.haptics.rememberRaftHaptics
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Desktop / wide rail More overflow menu scrolling (botiverse/slock #16999 + #17070):
 * - When many places overflow a short window, the places list scrolls within the available height
 *   while "Edit navigation bar" stays pinned and reachable below the scroller.
 * - When only a few places exist in a tall window, the menu hugs its content instead of stretching.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h700dp-mdpi")
class RaftRailMoreMenuOverflowUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class StubRailPlacesDataSource : RailPlacesDataSource {
        override suspend fun readServerPlaces(serverId: String): DataResult<RaftRailPlacesSnapshot?> =
            DataResult.Content(null)

        override suspend fun saveServerPlaces(
            serverId: String,
            places: List<String>?,
            placesVersion: Int,
        ): RailPlacesSaveWireResult = RailPlacesSaveWireResult.Saved(RaftRailPlacesSnapshot(emptyList(), placesVersion + 1, emptyList()))
    }

    private fun sampleApps(count: Int): List<RaftRailPlaceApp> =
        (1..count).map { index ->
            RaftRailPlaceApp(
                clientId = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                name = "Place App $index",
                iconUrl = null,
                homepageUrl = "https://example.test/app/$index",
            )
        }

    @Composable
    private fun RailMoreHarness(
        containerHeight: Dp,
        apps: List<RaftRailPlaceApp>,
        onOpenEdit: () -> Unit,
    ) {
        val scope = rememberCoroutineScope()
        val haptics = rememberRaftHaptics()
        val baseItems =
            remember {
                listOf(
                    RaftBottomTabItemPayload("home", "Chat", RaftLucideIconName.Circle, selected = true),
                    RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Circle, selected = false),
                    RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.Circle, selected = false),
                    RaftBottomTabItemPayload("artifacts", "Artifacts", RaftLucideIconName.Circle, selected = false),
                    RaftBottomTabItemPayload("members", "Members", RaftLucideIconName.Circle, selected = false),
                )
            }
        val snapshot =
            remember(apps) {
                RaftRailPlacesSnapshot(
                    places = listOf(RaftRailPlace.builtIn("chat"), RaftRailPlace.builtIn("activity")),
                    placesVersion = 1,
                    placeableApps = apps,
                )
            }
        val host =
            remember(scope, haptics, snapshot) {
                RaftDesktopRailPlacesHost(
                    railPlacesStore = SharedRailPlacesStore(StubRailPlacesDataSource(), scope),
                    bridge = BridgeModule(),
                    scope = scope,
                    haptics = haptics,
                    initialServerId = "srv-1",
                    initialSnapshot = snapshot,
                    initialBaseRailItems = baseItems,
                ).apply {
                    this.onOpenEdit = onOpenEdit
                }
            }
        RaftDesignSystemTheme {
            Box(Modifier.size(800.dp, containerHeight)) {
                Row(Modifier.fillMaxSize()) {
                    RaftNavigationRailTabBar(
                        items = baseItems,
                        onSelect = {},
                        modifier = Modifier.fillMaxHeight(),
                        desktopPlaces = host,
                    )
                }
                host.Overlays()
            }
        }
    }

    @Test
    fun shortWindowScrollsOverflowPlacesWhileKeepingEditNavigationPinned() =
        runRobolectricComposeUiTest {
            val apps = sampleApps(15)
            var editClicks = 0
            setContent {
                RailMoreHarness(
                    containerHeight = 320.dp,
                    apps = apps,
                    onOpenEdit = { editClicks += 1 },
                )
            }
            waitForIdle()

            onNodeWithTag(RaftRailReorderableTabTagPrefix + RaftRailMoreItemKey).performClick()
            waitForIdle()

            val menuBounds = onNodeWithTag(RaftRailMoreMenuTag).getBoundsInRoot()
            val scrollBounds = onNodeWithTag(RaftRailMoreScrollTag, useUnmergedTree = true).getBoundsInRoot()
            val editBounds = onNodeWithTag(RaftRailMoreEditTag).getBoundsInRoot()

            assertTrue(menuBounds.top.value >= 7f, "menu top (${menuBounds.top}) must stay inside container padding")
            assertTrue(menuBounds.bottom.value <= 313f, "menu bottom (${menuBounds.bottom}) must stay within 320dp container")
            assertTrue(editBounds.top.value >= scrollBounds.bottom.value - 1f, "Edit navigation bar must sit below the scroller")
            assertTrue(editBounds.bottom.value <= menuBounds.bottom.value + 1f, "Edit navigation bar must stay inside the menu")
            onNodeWithTag(RaftRailMoreEditTag).assertIsDisplayed()

            // Scrolling to the bottom-most app brings it into view while keeping Edit navigation bar pinned.
            val lastAppTag = "${RaftRailMorePlaceTagPrefix}app:${apps.last().clientId}"
            onNodeWithTag(RaftRailMoreScrollTag, useUnmergedTree = true).performScrollToNode(hasTestTag(lastAppTag))
            waitForIdle()
            onNodeWithTag(lastAppTag).assertIsDisplayed()

            val editBoundsAfterScroll = onNodeWithTag(RaftRailMoreEditTag).getBoundsInRoot()
            assertTrue(
                abs(editBoundsAfterScroll.top.value - editBounds.top.value) <= 1f,
                "Edit navigation bar must stay pinned while places scroll (before=${editBounds.top}, after=${editBoundsAfterScroll.top})",
            )

            onNodeWithTag(RaftRailMoreEditTag).performClick()
            waitForIdle()
            assertEquals(1, editClicks)
        }

    @Test
    fun shortListHugsContentHeightInsteadOfStretchingToAvailableHeight() =
        runRobolectricComposeUiTest {
            val apps = sampleApps(1)
            setContent {
                RailMoreHarness(
                    containerHeight = 640.dp,
                    apps = apps,
                    onOpenEdit = {},
                )
            }
            waitForIdle()

            onNodeWithTag(RaftRailReorderableTabTagPrefix + RaftRailMoreItemKey).performClick()
            waitForIdle()

            val menuBounds = onNodeWithTag(RaftRailMoreMenuTag).getBoundsInRoot()
            val menuHeight = menuBounds.bottom.value - menuBounds.top.value
            assertTrue(
                menuHeight < 420f,
                "few places must hug content instead of stretching to the 640dp container (height=$menuHeight)",
            )
            onNodeWithTag(RaftRailMoreEditTag).assertIsDisplayed()
        }
}
