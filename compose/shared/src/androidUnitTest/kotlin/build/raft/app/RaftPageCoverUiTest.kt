package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import build.raft.app.cmp.ProvideRaftPageCover
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMessageReactionTooltip
import build.raft.designsystem.components.RaftPageCoverSignal
import build.raft.designsystem.text.RaftText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A page pushed over a page closes the covered page's window-layer overlays as it is opened,
 * before the push: on iOS a covered page stops composing, so a dialog, menu or tooltip closed only
 * on `pageActive` stayed over the new page as a layer of the window, taking every tap.
 *
 * Each test shows the overlay on a page provided as RaftCmpRoot provides it
 * ([ProvideRaftPageCover]), opens a page through that page's host navigator, and checks the
 * overlay's owner was asked to close before the platform push ran and its window is gone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftPageCoverUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** The platform navigator: records each push and whether the overlay was still open then. */
    private class RecordingNavigator(private val overlayOpen: () -> Boolean) : RaftCmpNavigator {
        val pushes = mutableListOf<Pair<String, Boolean>>()

        override fun openPage(pageName: String, pageData: JsonObject) {
            pushes += pageName to overlayOpen()
        }

        override fun closePage() = Unit
    }

    private lateinit var pageNavigator: RaftCmpNavigator

    private fun ComposeUiTest.page(navigator: RaftCmpNavigator, content: @Composable () -> Unit) {
        val transport = HostBridgeTransport { _, _, _ -> }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        setContent {
            val signal = remember { RaftPageCoverSignal() }
            ProvideRaftPageCover(host, signal) {
                pageNavigator = LocalRaftHost.current.navigator
                RaftDesignSystemTheme { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    /** Opens a page from the covered page, as a tap on a thread ref does, and settles. */
    private fun ComposeUiTest.openPageFromThePage(navigator: RecordingNavigator) {
        runOnIdle { pageNavigator.openPage(PUSHED_PAGE) }
        waitForIdle()
        assertEquals(listOf(PUSHED_PAGE to false), navigator.pushes, "the overlay closed before the push")
    }

    @Test
    fun pushingAPageClosesADialog() =
        runRobolectricComposeUiTest {
            var open by mutableStateOf(true)
            val navigator = RecordingNavigator { open }
            page(navigator) {
                if (open) {
                    RaftDialog(onDismissRequest = { open = false }, title = "Rename channel") {
                        RaftText("dialog body", Modifier.testTag(OVERLAY))
                    }
                }
            }
            onNode(hasTestTag(OVERLAY)).assertExists()
            openPageFromThePage(navigator)
            assertFalse(open)
            onNode(hasTestTag(OVERLAY)).assertDoesNotExist()
        }

    @Test
    fun pushingAPageClosesADropdownMenu() =
        runRobolectricComposeUiTest {
            var expanded by mutableStateOf(true)
            val navigator = RecordingNavigator { expanded }
            page(navigator) {
                Box {
                    RaftText("⋯")
                    RaftDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        RaftMenuItem("Copy link", onClick = {}, modifier = Modifier.testTag(OVERLAY))
                    }
                }
            }
            onNode(hasTestTag(OVERLAY)).assertExists()
            openPageFromThePage(navigator)
            assertFalse(expanded)
            onNode(hasTestTag(OVERLAY)).assertDoesNotExist()
        }

    @Test
    fun pushingAPageClosesAReactionTooltip() =
        runRobolectricComposeUiTest {
            var open by mutableStateOf(true)
            val navigator = RecordingNavigator { open }
            page(navigator) {
                RaftMessageReactionTooltip(
                    names = listOf("Grace", "Antx"),
                    moreLabel = null,
                    open = open,
                    onDismissRequest = { open = false },
                ) {
                    RaftText("👍 2")
                }
            }
            onNode(hasText("Grace", substring = true)).assertExists()
            openPageFromThePage(navigator)
            assertFalse(open)
            // The bubble fades out, then its popup goes.
            mainClock.advanceTimeBy(1_000)
            onNode(hasText("Grace", substring = true)).assertDoesNotExist()
        }

    private companion object {
        const val OVERLAY = "overlay"
        const val PUSHED_PAGE = "compose_thread"
    }
}
