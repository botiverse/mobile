package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-mdpi")
class RaftArtifactViewerFrameOverlayUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun overlaidHeaderKeepsContentHeightInvariantAcrossPageBarHandoff() =
        runRobolectricComposeUiTest {
            var pageDrawsBar by mutableStateOf(false)
            var overlayHeader by mutableStateOf(true)
            var disposals = 0

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.size(390.dp, 600.dp)) {
                        RaftArtifactViewerFrame(
                            title = "q4-launch-plan.html",
                            onBack = {},
                            pageDrawsBar = pageDrawsBar,
                            topSafeAreaOverride = 24.dp,
                            overlayHeaderOverContent = overlayHeader,
                        ) {
                            DisposableEffect(Unit) {
                                onDispose { disposals += 1 }
                            }
                            Box(Modifier.fillMaxSize().testTag(CONTENT_TAG))
                        }
                    }
                }
            }
            waitForIdle()

            fun boundsDp(): Pair<Float, Float> {
                val node = onAllNodesWithTag(CONTENT_TAG, useUnmergedTree = true)[0].fetchSemanticsNode()
                val top = with(density) { node.boundsInRoot.top.toDp().value }
                val height = with(density) { node.size.height.toDp().value }
                return top to height
            }

            val (initialTop, initialHeight) = boundsDp()
            assertNear(24f, initialTop, "overlaid content starts directly below status bar")
            assertNear(576f, initialHeight, "overlaid content has full final height from frame 0")

            runOnIdle { pageDrawsBar = true }
            waitForIdle()
            val (handoffTop, handoffHeight) = boundsDp()
            assertNear(initialTop, handoffTop, "content top must not shift on hostToolbar=1")
            assertNear(initialHeight, handoffHeight, "content height must not resize on hostToolbar=1")
            assertEquals(0, disposals, "content slot must not recreate WebView across bar handoff")

            // Error / Unavailable fallback turns overlay off so content sits cleanly below header.
            runOnIdle {
                pageDrawsBar = false
                overlayHeader = false
            }
            waitForIdle()
            val (belowHeaderTop, belowHeaderHeight) = boundsDp()
            assertTrue(
                belowHeaderTop > initialTop + 40f,
                "non-overlaid content should start below native header: top=$belowHeaderTop",
            )
            assertTrue(
                belowHeaderHeight < initialHeight - 40f,
                "non-overlaid content height should shrink by header height: height=$belowHeaderHeight",
            )
            assertEquals(0, disposals, "toggling overlayHeaderOverContent must preserve content slot")
        }

    @Test
    fun overlaidHeaderInColumnKeepsFullHeightAcrossPageBarHandoff() =
        runRobolectricComposeUiTest {
            var pageDrawsBar by mutableStateOf(false)
            var disposals = 0

            setContent {
                RaftDesignSystemTheme {
                    Box(Modifier.size(390.dp, 600.dp)) {
                        RaftArtifactViewerFrame(
                            title = "q4-launch-plan.html",
                            onBack = {},
                            pageDrawsBar = pageDrawsBar,
                            inColumn = true,
                            overlayHeaderOverContent = true,
                        ) {
                            DisposableEffect(Unit) {
                                onDispose { disposals += 1 }
                            }
                            Box(Modifier.fillMaxSize().testTag(CONTENT_TAG))
                        }
                    }
                }
            }
            waitForIdle()

            fun boundsDp(): Pair<Float, Float> {
                val node = onAllNodesWithTag(CONTENT_TAG, useUnmergedTree = true)[0].fetchSemanticsNode()
                val top = with(density) { node.boundsInRoot.top.toDp().value }
                val height = with(density) { node.size.height.toDp().value }
                return top to height
            }

            val (initialTop, initialHeight) = boundsDp()
            assertNear(0f, initialTop, "overlaid column content starts at y=0")
            assertNear(600f, initialHeight, "overlaid column content spans full column height")

            runOnIdle { pageDrawsBar = true }
            waitForIdle()
            val (handoffTop, handoffHeight) = boundsDp()
            assertNear(initialTop, handoffTop, "column content top stays invariant on hostToolbar=1")
            assertNear(initialHeight, handoffHeight, "column content height stays invariant on hostToolbar=1")
            assertEquals(0, disposals, "column content slot must not recreate WebView")
        }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= 1f, "$message: expected $expected, was $actual")
    }

    private companion object {
        const val CONTENT_TAG = "artifact-viewer-frame-content"
    }
}
