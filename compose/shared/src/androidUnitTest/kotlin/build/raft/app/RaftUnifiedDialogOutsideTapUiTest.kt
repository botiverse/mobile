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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * [RaftUnifiedDialog]'s `dismissOnOutsideTap` (web ConfirmDialog / FormDialog): with it off, a tap
 * on the scrim leaves the dialog open, while system Back and the header × still dismiss it; the
 * default keeps the scrim tap. `dismissEnabled = false` (the busy guard) still blocks Back.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RaftUnifiedDialogOutsideTapUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showDialog(
        dismissOnOutsideTap: Boolean,
        dismissEnabled: Boolean = true,
        onDismiss: () -> Unit,
    ) {
        setContent {
            Page {
                RaftUnifiedDialog(
                    title = "Delete",
                    onDismiss = onDismiss,
                    dismissEnabled = dismissEnabled,
                    dismissOnOutsideTap = dismissOnOutsideTap,
                    modifier = Modifier.testTag(CardTag),
                ) {
                    Box(Modifier.size(40.dp))
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    /** A tap on the scrim: above the centred card, in the dialog window. */
    private fun ComposeUiTest.tapScrim() {
        onNodeWithTag(CardTag).performTouchInput { click(Offset(centerX, -120f)) }
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
    fun aScrimTapDismissesByDefault() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showDialog(dismissOnOutsideTap = true) { dismissed++ }
            tapScrim()
            assertEquals(1, dismissed)
        }

    @Test
    fun aScrimTapDoesNothingWhenOutsideTapIsOff() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showDialog(dismissOnOutsideTap = false) { dismissed++ }
            tapScrim()
            assertEquals(0, dismissed, "a stray tap on the scrim must not close a confirm/form dialog")
        }

    @Test
    fun backStillDismissesWhenOutsideTapIsOff() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showDialog(dismissOnOutsideTap = false) { dismissed++ }
            pressBack()
            assertEquals(1, dismissed)
        }

    @Test
    fun theCloseButtonStillDismissesWhenOutsideTapIsOff() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showDialog(dismissOnOutsideTap = false) { dismissed++ }
            onNode(hasContentDescription("Close") and hasClickAction()).performClick()
            mainClock.advanceTimeBy(1_000)
            assertEquals(1, dismissed)
        }

    @Test
    fun busyDialogIgnoresBack() =
        runRobolectricComposeUiTest {
            var dismissed = 0
            showDialog(dismissOnOutsideTap = false, dismissEnabled = false) { dismissed++ }
            pressBack()
            tapScrim()
            assertEquals(0, dismissed)
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
        const val CardTag = "dialog-card"
    }
}
