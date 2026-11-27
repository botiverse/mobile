package build.raft.app

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.cmp.RaftCmpToastLayer
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.thread.ThreadMessageSelectTextSheet
import build.raft.app.thread.rememberRaftMessageBodyTextStyle
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for `#proj-mobile:#408`: on Android, every [RaftWindowDialog] (`RaftSheet`,
 * `RaftDialog`, `RaftDialogSheet`, `RaftSideDrawer`, `Modal`) opens a separate OS `Dialog` window
 * above the host Activity window. Without a toast overlay inside [RaftWindowDialog], toasts posted
 * while a sheet/dialog stays open (such as tapping Copy inside [ThreadMessageSelectTextSheet]) were
 * obscured behind the dialog window.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftWindowDialogToastOverlayUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(250)
            waitForIdle()
        }
    }

    private fun ComposeUiTest.assertToastOnlyInWindowContaining(tag: String, toastText: String, otherTags: List<String>) {
        onAllNodesWithText(toastText).assertCountEquals(1)
        onAllNodes(
            isRoot() and hasAnyDescendant(hasTestTag(tag)) and hasAnyDescendant(hasText(toastText)),
        ).assertCountEquals(1)
        otherTags.forEach { other ->
            onAllNodes(
                isRoot() and hasAnyDescendant(hasTestTag(other)) and hasAnyDescendant(hasText(toastText)),
            ).assertCountEquals(0)
        }
    }

    @Test
    fun toastRendersInsideOpenDialogWindowWithoutDuplicationAndHandsBackOnDismiss() = runRobolectricComposeUiTest {
        val origin = BridgeWireHostCapabilities(HostBridgeTransport { _, _, _ -> })
        var dialogOpen by mutableStateOf(false)
        var activityRoot: View? = null
        var dialogRoot: View? = null

        mainClock.autoAdvance = false
        setContent {
            RaftCmpToastLayer(origin = origin) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    activityRoot = LocalView.current.rootView
                    Box(Modifier.fillMaxSize().testTag("activity-content"))
                    if (dialogOpen) {
                        RaftWindowDialog(onDismissRequest = { dialogOpen = false }) {
                            dialogRoot = LocalView.current.rootView
                            Box(Modifier.fillMaxSize().testTag("dialog-content"))
                        }
                    }
                }
            }
        }
        settle()

        dialogOpen = true
        settle()

        val hostView = assertNotNull(activityRoot, "activity root view")
        val modalView = assertNotNull(dialogRoot, "dialog root view")
        assertTrue(modalView !== hostView, "RaftWindowDialog must render in its own Android window")

        assertTrue(RaftCmpToasts.post("Copied", origin, durationMillis = 10_000L))
        settle()

        assertToastOnlyInWindowContaining(
            tag = "dialog-content",
            toastText = "Copied",
            otherTags = listOf("activity-content"),
        )

        // Dismissing the dialog hands the still-live toast seamlessly back to the Activity window.
        dialogOpen = false
        settle()

        assertToastOnlyInWindowContaining(
            tag = "activity-content",
            toastText = "Copied",
            otherTags = emptyList(),
        )
    }

    @Test
    fun copyInsideSelectTextSheetRendersToastInSheetWindowAndSurvivesSheetDismiss() = runRobolectricComposeUiTest {
        val origin = BridgeWireHostCapabilities(HostBridgeTransport { _, _, _ -> })
        var sheetOpen by mutableStateOf(true)
        var copiedPosted = false

        mainClock.autoAdvance = false
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftCmpToastLayer(origin = origin) {
                    RaftTheme(appearance = RaftAppearance.Light) {
                        Box(Modifier.fillMaxSize().testTag("activity-content"))
                        if (sheetOpen) {
                            ThreadMessageSelectTextSheet(
                                markdown = "Hello from Select Text",
                                senderName = "Kirby",
                                timeLabel = "14:53",
                                textStyle = rememberRaftMessageBodyTextStyle(null),
                                onDismiss = { sheetOpen = false },
                                onCopy = {
                                    copiedPosted = RaftCmpToasts.post("Copied", origin, durationMillis = 10_000L)
                                },
                                onQuote = null,
                            )
                        }
                    }
                }
            }
        }
        settle()

        onNodeWithText("Copy").performClick()
        settle()

        assertTrue(copiedPosted, "Copy action should post toast to RaftCmpToasts")
        assertToastOnlyInWindowContaining(
            tag = "thread_message_selection_text",
            toastText = "Copied",
            otherTags = listOf("activity-content"),
        )

        sheetOpen = false
        settle()

        assertToastOnlyInWindowContaining(
            tag = "activity-content",
            toastText = "Copied",
            otherTags = emptyList(),
        )
    }

    @Test
    fun stackedDialogsRenderToastOnlyInTopmostDialogWindowAndHandBackInLifoOrder() = runRobolectricComposeUiTest {
        var dialog1Open by mutableStateOf(true)
        var dialog2Open by mutableStateOf(false)
        var toastState: RaftToastHostState? = null
        var activityRoot: View? = null
        var dialog1Root: View? = null
        var dialog2Root: View? = null

        mainClock.autoAdvance = false
        setContent {
            RaftCmpToastLayer {
                RaftTheme(appearance = RaftAppearance.Light) {
                    toastState = LocalRaftToastHostState.current
                    activityRoot = LocalView.current.rootView
                    Box(Modifier.fillMaxSize().testTag("activity-content"))
                    if (dialog1Open) {
                        RaftWindowDialog(onDismissRequest = { dialog1Open = false }) {
                            dialog1Root = LocalView.current.rootView
                            Box(Modifier.fillMaxSize().testTag("dialog-1-content"))
                            if (dialog2Open) {
                                RaftWindowDialog(onDismissRequest = { dialog2Open = false }) {
                                    dialog2Root = LocalView.current.rootView
                                    Box(Modifier.fillMaxSize().testTag("dialog-2-content"))
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()

        dialog2Open = true
        settle()

        val rootActivity = assertNotNull(activityRoot)
        val rootDialog1 = assertNotNull(dialog1Root)
        val rootDialog2 = assertNotNull(dialog2Root)
        assertTrue(rootDialog1 !== rootActivity)
        assertTrue(rootDialog2 !== rootDialog1 && rootDialog2 !== rootActivity)

        assertNotNull(toastState).show("Stacked", durationMillis = 10_000L)
        settle()

        assertToastOnlyInWindowContaining(
            tag = "dialog-2-content",
            toastText = "Stacked",
            otherTags = listOf("dialog-1-content", "activity-content"),
        )

        // Closing the topmost dialog hands the toast back to dialog 1.
        dialog2Open = false
        settle()

        assertToastOnlyInWindowContaining(
            tag = "dialog-1-content",
            toastText = "Stacked",
            otherTags = listOf("activity-content"),
        )

        // Closing dialog 1 hands the toast back to the Activity window.
        dialog1Open = false
        settle()

        assertToastOnlyInWindowContaining(
            tag = "activity-content",
            toastText = "Stacked",
            otherTags = emptyList(),
        )
    }

    @Test
    fun dialogAlreadyOpenOnFirstFrameStillOwnsToastOverlayOverActivity() = runRobolectricComposeUiTest {
        val origin = BridgeWireHostCapabilities(HostBridgeTransport { _, _, _ -> })

        mainClock.autoAdvance = false
        setContent {
            RaftCmpToastLayer(origin = origin) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    Box(Modifier.fillMaxSize().testTag("activity-content"))
                    RaftWindowDialog(onDismissRequest = {}) {
                        Box(Modifier.fillMaxSize().testTag("frame-zero-dialog"))
                    }
                }
            }
        }
        settle()

        assertTrue(RaftCmpToasts.post("FrameZero", origin, durationMillis = 10_000L))
        settle()

        assertToastOnlyInWindowContaining(
            tag = "frame-zero-dialog",
            toastText = "FrameZero",
            otherTags = listOf("activity-content"),
        )
    }
}
