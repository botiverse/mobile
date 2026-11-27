package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.thread.ThreadDirectMessageSettingsDrawer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a phone a conversation's settings are a pushed page: Back at the start of the head, named
 * Back for screen readers, closes it; there is no × (artin 2026-11-04: a single page is a push).
 * The channel details in the wide column (a side pane, web layout-memory.md "Close and Back") have
 * the pane's Close at the end of the head instead, and a page pushed over them in the column (the
 * member list) has Back too.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadSettingsPageUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theDmSettingsPageHasBackAndNoClose() =
        runRobolectricComposeUiTest {
            var closed = 0
            setContent {
                Host {
                    ThreadDirectMessageSettingsDrawer(
                        name = "Friday",
                        activityMuteState = null,
                        section = null,
                        sectionEnabled = true,
                        longMessageCollapseSetting = null,
                        onDismiss = { closed += 1 },
                        onToggleActivityMute = null,
                        onMoveSection = {},
                        onCreateSection = { _, _ -> },
                        asPage = true,
                    )
                }
            }
            onNodeWithText("Friday").assertIsDisplayed()
            onNodeWithText("Direct message").assertIsDisplayed()
            onNodeWithContentDescription("Close conversation settings").assertDoesNotExist()
            onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1, closed) }
        }

    // On the wide layout they are a column beside the conversation whose head is Details and Close.
    @Test
    fun theChannelDetailsColumnHasCloseAndNoBack() =
        runRobolectricComposeUiTest {
            var closed = 0
            setContent {
                Host {
                    val pane = remember { RaftSidePanePage(depth = 1) { closed += 1 } }
                    CompositionLocalProvider(LocalRaftSidePanePage provides pane) {
                        ChannelSettingsScaffold(refreshing = false, onBack = { fail("the column's only page has no Back") }) {}
                    }
                }
            }
            onNodeWithText("Details").assertIsDisplayed()
            onNodeWithContentDescription("Back").assertDoesNotExist()
            onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1, closed) }
        }

    // The member list pushed over the details in the column: Back goes back to them, Close closes the column.
    @Test
    fun aPagePushedInTheColumnHasBackAndClose() =
        runRobolectricComposeUiTest {
            var backs = 0
            var closed = 0
            setContent {
                Host {
                    val pane = remember { RaftSidePanePage(depth = 2) { closed += 1 } }
                    CompositionLocalProvider(LocalRaftSidePanePage provides pane) {
                        ChannelMembersPageHeader(title = "Members", count = 3, refreshing = false, onBack = { backs += 1 })
                    }
                }
            }
            onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1 to 0, backs to closed, "Back goes back one page") }
            onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
            runOnIdle { assertEquals(1 to 1, backs to closed, "Close closes the column") }
        }
}

@androidx.compose.runtime.Composable
private fun Host(content: @androidx.compose.runtime.Composable () -> Unit) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
        RaftDesignSystemTheme { content() }
    }
}
