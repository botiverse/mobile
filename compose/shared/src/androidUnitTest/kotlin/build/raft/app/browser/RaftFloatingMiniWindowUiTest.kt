package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.text.RaftText
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h844dp")
class RaftFloatingMiniWindowUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun sampleAppTab(tabId: Long = 1L): RaftAppBrowserTab =
        RaftAppBrowserTab(
            tabId = tabId,
            launchUrl = "https://stamp.build/pulls/42",
            currentUrl = "https://stamp.build/pulls/42",
            pageTitle = "PR #42",
            appEntryToken = "tok-$tabId",
            scopeOrigin = "https://stamp.build",
            appName = "Stamp",
            isLive = true,
            lastUsedSeq = 2L,
        )

    private fun sampleArtifact(index: Int = 1): RaftFloatingArtifactWindow {
        val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c$index"
        val scope = RaftArtifactViewerScope("https://app.raft.build", "https://api.raft.build/api", "acct-1", "srv-1")
        val target = ArtifactLinkTarget(id)
        return RaftFloatingArtifactWindow(
            artifactId = id,
            canonicalId = target.canonicalId,
            version = null,
            title = "Q4 Launch Plan",
            kind = ArtifactKind.Html,
            scope = scope,
            keepAliveKey = scope.artifactKeepAliveKey(target),
            embeddedPageUrl = raftArtifactViewerEmbeddedPageUrl(scope.webOrigin, target),
            lastUsedSeq = 5L,
        )
    }

    @Test
    fun miniWindowRendersControlsAllowsHeaderDragBothCornerResizesImeClampingAndOutsideTouchPassThrough() =
        runRobolectricComposeUiTest {
            var placement by mutableStateOf(
                RaftFloatingMiniWindowPlacement(
                    preferredWidthDp = 280f,
                    preferredHeightDp = 300f,
                    xFraction = 0.5f,
                    yFraction = 0.12f,
                ),
            )
            var underlyingClicks = 0
            val actions = mutableListOf<String>()
            val hostIme = RaftHostImeInsets()

            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftHostImeInsets provides hostIme) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Underlying interactive chat/composer control near bottom-start outside mini-window bounds.
                            RaftButton(
                                text = "Send Reply",
                                onClick = { underlyingClicks += 1 },
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 16.dp, bottom = 48.dp)
                                        .testTag("underlying_chat_button"),
                            )
                            RaftFloatingMiniWindowOverlay(
                                identity =
                                    RaftMiniWindowHeaderIdentity.Artifact(
                                        title = "Q4 Launch Plan",
                                        kind = ArtifactKind.Html,
                                    ),
                                placement = placement,
                                onPlacementChange = { placement = it },
                                onCollapseToPill = { actions += "collapse" },
                                onExpandToFullScreen = { actions += "expand" },
                                onClose = { actions += "close" },
                                topSafeArea = 44.dp,
                                bottomSafeArea = 34.dp,
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    RaftText("Mini body")
                                }
                            }
                        }
                    }
                }
            }

            // 1. Header controls, title, and both corner resize handles are displayed.
            onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).assertIsDisplayed()
            onNodeWithTag(RAFT_MINI_WINDOW_HEADER_TAG).assertIsDisplayed()
            onNodeWithTag(RAFT_MINI_WINDOW_TITLE_TAG).assertIsDisplayed()
            onNodeWithText("Q4 Launch Plan").assertIsDisplayed()
            onNodeWithTag(RAFT_MINI_WINDOW_COLLAPSE_BUTTON_TAG).assertIsDisplayed()
            onNodeWithTag(RAFT_MINI_WINDOW_EXPAND_BUTTON_TAG).assertIsDisplayed()
            onNodeWithTag(RAFT_MINI_WINDOW_CLOSE_BUTTON_TAG).assertIsDisplayed()
            onNodeWithTag(RAFT_MINI_WINDOW_RESIZE_SE_TAG).assertIsDisplayed()
            onNodeWithTag(RAFT_MINI_WINDOW_RESIZE_SW_TAG).assertIsDisplayed()

            // 2. Touches outside mini-window bounds pass straight through to underlying UI.
            onNodeWithTag("underlying_chat_button").performClick()
            assertEquals(1, underlyingClicks)

            // 3. Dragging header moves mini-window down and right cleanly.
            val beforeMove = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            val stepPx = 10f * density.density
            onNodeWithTag(RAFT_MINI_WINDOW_HEADER_TAG).performTouchInput {
                val dragStart = Offset(width * 0.25f, centerY)
                down(dragStart)
                repeat(12) { moveBy(Offset(0f, stepPx)) }
                up()
            }
            waitForIdle()
            val afterMove = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            assertTrue(
                afterMove.top > beforeMove.top + 80.dp,
                "Expected header drag to move window downward: before=${beforeMove.top}, after=${afterMove.top}",
            )

            // 4. Dragging Bottom-Right resize handle grows width & height while anchoring left & top edges.
            val beforeSe = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            onNodeWithTag(RAFT_MINI_WINDOW_RESIZE_SE_TAG).performTouchInput {
                down(center)
                repeat(6) { moveBy(Offset(stepPx, stepPx)) }
                up()
            }
            waitForIdle()
            val afterSe = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            assertTrue(
                afterSe.width > beforeSe.width + 30.dp,
                "Expected SE resize to grow width: before=${beforeSe.width}, after=${afterSe.width}",
            )
            assertTrue(
                afterSe.height > beforeSe.height + 30.dp,
                "Expected SE resize to grow height: before=${beforeSe.height}, after=${afterSe.height}",
            )
            assertTrue(
                abs(afterSe.left.value - beforeSe.left.value) <= 2f,
                "Expected SE resize to anchor left edge: before=${beforeSe.left}, after=${afterSe.left}",
            )

            // 5. Dragging Bottom-Left resize handle rightward shrinks width while anchoring right edge.
            val beforeSw = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            onNodeWithTag(RAFT_MINI_WINDOW_RESIZE_SW_TAG).performTouchInput {
                down(center)
                repeat(6) { moveBy(Offset(stepPx, -stepPx)) }
                up()
            }
            waitForIdle()
            val afterSw = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            assertTrue(
                afterSw.width < beforeSw.width - 20.dp,
                "Expected SW inward drag to shrink width: before=${beforeSw.width}, after=${afterSw.width}",
            )
            assertTrue(
                abs(afterSw.right.value - beforeSw.right.value) <= 2f,
                "Expected SW resize to anchor right edge: before=${beforeSw.right}, after=${afterSw.right}",
            )

            // 6. Live IME lifts mini-window cleanly above the keyboard.
            runOnIdle {
                placement = placement.copy(yFraction = 1f)
            }
            waitForIdle()
            val atBottomNoIme = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            runOnIdle { hostIme.imeBottomPx = (320f * density.density).roundToInt() }
            waitForIdle()
            val liftedAboveIme = onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).getBoundsInRoot()
            assertTrue(
                liftedAboveIme.bottom < atBottomNoIme.bottom - 200.dp,
                "Expected live IME to lift mini-window: noIme=${atBottomNoIme.bottom}, ime=${liftedAboveIme.bottom}",
            )

            // 7. All three header controls fire their respective callbacks.
            onNodeWithTag(RAFT_MINI_WINDOW_COLLAPSE_BUTTON_TAG).performClick()
            onNodeWithTag(RAFT_MINI_WINDOW_EXPAND_BUTTON_TAG).performClick()
            onNodeWithTag(RAFT_MINI_WINDOW_CLOSE_BUTTON_TAG).performClick()
            assertEquals(listOf("collapse", "expand", "close"), actions)
        }

    @Test
    fun collapseTransitionsMiniWindowToEdgePillAndUnifiedSwitcherShowsAppsAndArtifacts() =
        runRobolectricComposeUiTest {
            val tab = sampleAppTab(1L)
            val artifact = sampleArtifact(1)
            var miniWindowOpen by mutableStateOf(true)
            var switcherOpen by mutableStateOf(false)
            var pillEdge by mutableStateOf(RaftFloatingPillEdge.Right)
            var pillY by mutableFloatStateOf(RAFT_FLOATING_PILL_DEFAULT_Y_FRACTION)
            val events = mutableListOf<String>()

            setContent {
                RaftDesignSystemTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (miniWindowOpen) {
                            RaftFloatingMiniWindowOverlay(
                                identity =
                                    RaftMiniWindowHeaderIdentity.Artifact(
                                        title = artifact.title,
                                        kind = artifact.kind,
                                    ),
                                placement = RaftFloatingMiniWindowPlacement(),
                                onPlacementChange = {},
                                onCollapseToPill = {
                                    miniWindowOpen = false
                                    events += "collapsed_to_pill"
                                },
                                onExpandToFullScreen = { events += "expand_mini" },
                                onClose = { events += "close_mini" },
                                topSafeArea = 44.dp,
                                bottomSafeArea = 34.dp,
                            ) {
                                Box(Modifier.fillMaxSize())
                            }
                        } else {
                            RaftFloatingBrowserTabPillOverlay(
                                tabs = listOf(tab),
                                artifacts = listOf(artifact),
                                pillYFraction = pillY,
                                onPillYFractionChange = { pillY = it },
                                pillEdge = pillEdge,
                                onPillDockPositionChange = { edge, y ->
                                    pillEdge = edge
                                    pillY = y
                                },
                                onClick = { switcherOpen = true },
                                topSafeArea = 44.dp,
                                bottomSafeArea = 34.dp,
                            )
                        }
                        if (switcherOpen) {
                            RaftAppBrowserSwitcherSheet(
                                tabs = listOf(tab),
                                activeTabId = null,
                                onSelectTab = { events += "mini_app:${it.tabId}" },
                                onCloseTab = { events += "close_app:$it" },
                                onCloseAll = {
                                    events += "close_all"
                                    switcherOpen = false
                                },
                                onDismiss = { switcherOpen = false },
                                artifacts = listOf(artifact),
                                onSelectArtifact = { events += "mini_artifact:${it.windowKey}" },
                                onExpandTab = { events += "expand_app:${it.tabId}" },
                                onExpandArtifact = { events += "expand_artifact:${it.windowKey}" },
                                onCloseArtifact = { events += "close_artifact:$it" },
                            )
                        }
                    }
                }
            }

            // Tap Collapse -> Mini-Window hides, unified Edge Pill displays combined count [2].
            onNodeWithTag(RAFT_MINI_WINDOW_COLLAPSE_BUTTON_TAG).performClick()
            waitForIdle()
            onNodeWithTag(RAFT_MINI_WINDOW_CONTAINER_TAG).assertDoesNotExist()
            onNodeWithTag(RAFT_FLOATING_TAB_PILL_TAG).assertIsDisplayed()
            onNodeWithTag(RAFT_FLOATING_TAB_PILL_COUNT_TAG, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()

            // Tap Edge Pill -> unified Switcher Sheet renders BOTH App and Artifact rows + Expand/Close actions.
            onNodeWithTag(RAFT_FLOATING_TAB_PILL_TAG).performClick()
            waitForIdle()
            onNodeWithTag(RAFT_APP_BROWSER_SWITCHER_SHEET_TAG).assertIsDisplayed()
            onNodeWithText("PR #42").assertIsDisplayed()
            onNodeWithText("Q4 Launch Plan").assertIsDisplayed()

            onNodeWithTag(raftFloatingSwitcherExpandTag("artifact_${artifact.windowKey}")).performClick()
            onNodeWithTag(raftFloatingArtifactSwitcherCloseTag(artifact.windowKey)).performClick()
            onNodeWithTag(RAFT_APP_BROWSER_SWITCHER_CLOSE_ALL_TAG).performClick()

            assertEquals(
                listOf(
                    "collapsed_to_pill",
                    "expand_artifact:${artifact.windowKey}",
                    "close_artifact:${artifact.windowKey}",
                    "close_all",
                ),
                events,
            )
        }

    @Test
    fun artifactViewerBarMenuShowsFloatWindowOnlyWhenActionProvided() = runRobolectricComposeUiTest {
        var floated = 0

        setContent {
            RaftDesignSystemTheme {
                RaftArtifactViewerFrame(
                    title = "Q4 Launch Plan",
                    onBack = {},
                    actions =
                        RaftArtifactViewerBarActions(
                            onToggleDesktopMode = {},
                            onMinimizeToFloat = { floated += 1 },
                        ),
                    desktopMode = false,
                    topSafeAreaOverride = 0.dp,
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }

        onNodeWithContentDescription("More actions").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Desktop mode").assertIsDisplayed()
        onNodeWithText("Float window").assertIsDisplayed().performClick()
        assertEquals(1, floated)
    }
}
