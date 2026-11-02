package build.raft.app

import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import build.raft.designsystem.components.RaftButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * [RaftUnifiedDialog] with [RaftDialogPresentation.Sheet] on a phone (web `DialogCard
 * narrow="sheet"`): the commit moves to the sheet's head and [RaftDialogFooter] draws nothing;
 * a tap on the scrim never closes it, × and Back do (after the sheet slides out), and a busy
 * dialog (`dismissEnabled = false`) ignores both. A wide window keeps the centred card and its
 * footer.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftUnifiedDialogSheetUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showSheet(
        dismissEnabled: Boolean = true,
        commitEnabled: Boolean = true,
        onCommit: () -> Unit = {},
        onDismiss: () -> Unit,
    ) {
        setContent {
            Page {
                RaftUnifiedDialog(
                    title = "Create Agent",
                    onDismiss = onDismiss,
                    dismissEnabled = dismissEnabled,
                    dismissOnOutsideTap = false,
                    modifier = Modifier.testTag(SheetTag),
                    presentation = RaftDialogPresentation.Sheet,
                    commitLabel = CommitLabel,
                    onCommit = onCommit,
                    commitEnabled = commitEnabled,
                ) {
                    Box(Modifier.size(40.dp))
                    RaftDialogFooter {
                        RaftButton(text = FooterLabel, onClick = {})
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    /** A tap on the scrim: above the sheet, in the dialog window. */
    private fun ComposeUiTest.tapScrim() {
        onNodeWithTag(SheetTag).performTouchInput { click(Offset(centerX, -40f)) }
        mainClock.advanceTimeBy(1_000)
    }

    private fun ComposeUiTest.pressBack() {
        runOnIdle {
            val window = assertNotNull(ShadowDialog.getLatestDialog() as? ComponentDialog, "the dialog's window")
            window.onBackPressedDispatcher.onBackPressed()
        }
        mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun theCommitIsInTheHeadAndTheFooterIsGone() =
        runRobolectricComposeUiTest {
            var committed = 0
            showSheet(onCommit = { committed++ }) {}
            onNodeWithText(FooterLabel).assertDoesNotExist()
            onNodeWithText(CommitLabel).performClick()
            mainClock.advanceTimeBy(1_000)
            assertEquals(1, committed)
        }

    @Test
    fun aDisabledCommitDoesNothing() =
        runRobolectricComposeUiTest {
            var committed = 0
            showSheet(commitEnabled = false, onCommit = { committed++ }) {}
            onNodeWithText(CommitLabel).performClick()
            mainClock.advanceTimeBy(1_000)
            assertEquals(0, committed)
        }

    @Test
    fun aScrimTapNeverClosesTheSheet() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showSheet { dismissed++ }
            tapScrim()
            assertEquals(0, dismissed, "a stray tap on the scrim must not close a form sheet")
        }

    @Test
    fun theCloseButtonDismissesAfterTheSlide() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showSheet { dismissed++ }
            onNode(hasContentDescription("Close") and hasClickAction()).performClick()
            mainClock.advanceTimeBy(1_000)
            assertEquals(1, dismissed)
        }

    @Test
    fun backDismisses() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showSheet { dismissed++ }
            pressBack()
            assertEquals(1, dismissed)
        }

    @Test
    fun aBusySheetIgnoresBackAndClose() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showSheet(dismissEnabled = false) { dismissed++ }
            pressBack()
            onNode(hasContentDescription("Close")).performClick()
            mainClock.advanceTimeBy(1_000)
            tapScrim()
            assertEquals(0, dismissed)
        }

    @Test
    @Config(qualifiers = "w1024dp-h768dp-xhdpi")
    fun aWideWindowKeepsTheCentredCardAndItsFooter() =
        runRobolectricComposeUiTest {
            showSheet {}
            onNodeWithText(FooterLabel).assertExists()
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    private companion object {
        const val SheetTag = "dialog-sheet"
        const val CommitLabel = "Create"
        const val FooterLabel = "Footer action"
    }
}
