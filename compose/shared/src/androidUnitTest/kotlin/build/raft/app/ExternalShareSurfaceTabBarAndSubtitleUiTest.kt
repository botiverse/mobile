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
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ExternalShareEnvelope
import build.raft.app.data.ExternalShareItem
import build.raft.app.data.ExternalShareState
import build.raft.app.shell.LocalRaftNativeTabBarBridge
import build.raft.app.shell.RaftNativeTabBarBridge
import build.raft.app.shell.RaftNativeTabBarState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression checks for Hands 8db74660 (cs25):
 * 1. [ExternalShareSurface] hides the host-drawn native iOS tab bar via [RaftNativeTabBarCover]
 *    while any external-share overlay is active and restores it when returning to [ExternalShareState.Idle].
 * 2. [ExternalShareTargetPickerSurface] renders item-count wording ("1 item selected" /
 *    "N items selected") instead of reusing the message-forward subtitle ("N messages selected").
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalShareSurfaceTabBarAndSubtitleUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun sampleItem(name: String): ExternalShareItem =
        ExternalShareItem(
            localRef = "ref-$name",
            displayName = name,
            mimeType = "application/pdf",
            sizeBytes = 1024L
        )

    private fun selectingState(items: List<ExternalShareItem>, text: String = ""): ExternalShareState.SelectingTargets =
        ExternalShareState.SelectingTargets(
            envelope =
                ExternalShareEnvelope(
                    token = "tok-cs25",
                    createdAtEpochMillis = 1_700_000_000_000L,
                    sourcePlatform = "ios",
                    text = text,
                    items = items
                ),
            accountId = "acct-1",
            serverId = "srv-1"
        )

    @Test
    fun externalShareSurfaceCoversNativeTabBarWhileActiveAndUncoversWhenIdle() =
        runRobolectricComposeUiTest {
            val bridge = RaftNativeTabBarBridge()
            bridge.publish(
                token = Any(),
                state =
                    RaftNativeTabBarState(
                        items = emptyList(),
                        selectedKey = "home",
                        visible = true
                    ),
                onSelect = {}
            )
            assertTrue(bridge.state.value.visible)

            var shareState by mutableStateOf<ExternalShareState>(
                selectingState(items = listOf(sampleItem("meeting.pdf")))
            )

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                RaftDesignSystemTheme {
                    CompositionLocalProvider(
                        LocalActivity provides RaftPageActivity(pager),
                        LocalRaftNativeTabBarBridge provides bridge,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            ExternalShareSurface(
                                state = shareState,
                                onIntent = {},
                                onOpenComposer = { true }
                            )
                        }
                    }
                }
            }
            waitForIdle()
            assertFalse(
                bridge.state.value.visible,
                "Native tab bar must be covered while SelectingTargets is open"
            )

            shareState = ExternalShareState.LoadingEnvelope(token = "tok-cs25")
            waitForIdle()
            assertFalse(
                bridge.state.value.visible,
                "Native tab bar must remain covered while LoadingEnvelope is shown"
            )

            shareState = ExternalShareState.Idle
            waitForIdle()
            assertTrue(
                bridge.state.value.visible,
                "Native tab bar must become visible again once ExternalShare returns to Idle"
            )
        }

    @Test
    fun externalSharePickerUsesItemCountSubtitleInsteadOfMessageCount() =
        runRobolectricComposeUiTest {
            var shareState by mutableStateOf(
                selectingState(items = listOf(sampleItem("meeting.pdf")))
            )

            setContent {
                val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                        Box(Modifier.fillMaxSize()) {
                            ExternalShareTargetPickerSurface(
                                state = shareState,
                                onIntent = {}
                            )
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("1 item selected").assertExists()
            onNodeWithText("1 messages selected").assertDoesNotExist()

            shareState =
                selectingState(
                    items = listOf(sampleItem("meeting.pdf"), sampleItem("notes.txt"))
                )
            waitForIdle()

            onNodeWithText("2 items selected").assertExists()
            onNodeWithText("2 messages selected").assertDoesNotExist()
        }
}
