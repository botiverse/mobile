package build.raft.app

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.AddComputerStep
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.designsystem.components.RaftDialogSheetDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Add Computer sheet parity with web `AddMachineDialog.tsx` (botiverse/slock #14244/#14251):
 * - Under limit and allowed to add: opens straight on `AddComputerStep.Waiting` with
 *   `ComputerAccessNotice`, the own-computer hint above the command, and the connect command ready.
 * - At plan limit (or without add permission): stays on `AddComputerStep.Type` with the warning
 *   banner, single-column `RaftChoiceList` ("Which computer?" / "Your own computer"), no
 *   "Cloud computer" / "Coming soon" option, and `Next` disabled.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class AddComputerDialogUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showSheet(state: AddComputerDialogState) {
        setContent {
            RaftDesignSystemTheme {
                AddComputerSheetSurfaceForFixture(
                    state = state,
                    modifier = Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction),
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun waitingStepShowsAccessNoticeOwnComputerHintAndCommandWithoutCloudOption() = runRobolectricComposeUiTest {
        showSheet(
            AddComputerDialogState(
                step = AddComputerStep.Waiting,
                commandContext = SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "production"),
            )
        )

        onNodeWithText("Connect computer").assertIsDisplayed()
        onNodeWithTag(ComputerAccessNoticeTag).assertIsDisplayed()
        onNodeWithTag(AddComputerOwnComputerHintTag).assertIsDisplayed()
        onNodeWithText("A laptop, a desktop or a cloud server (VPS): run this command on it.").assertIsDisplayed()
        onNodeWithText("curl -fsSL https://cdn.raft.build/computer/install.sh | sh -s -- --setup /acme").assertIsDisplayed()
        onNodeWithText("Done").assertIsNotEnabled()
        assertEquals(0, count("Cloud Computer", substring = true))
        assertEquals(0, count("Cloud computer", substring = true))
        assertEquals(0, count("Coming soon", substring = true))
    }

    @Test
    fun atLimitStaysOnTypeStepWithLimitWarningSingleChoiceAndNextDisabled() = runRobolectricComposeUiTest {
        showSheet(
            AddComputerDialogState(
                step = AddComputerStep.Type,
                canManage = true,
                atLimit = true,
                computerCount = 1,
                maxComputers = 1,
                planDisplayName = "Free",
            )
        )

        onNodeWithText("Add computer").assertIsDisplayed()
        onNodeWithText("Computer limit reached (1/1 on Free plan).").assertIsDisplayed()
        onNodeWithText("Which computer?").assertIsDisplayed()
        onNodeWithText("Your own computer").assertIsDisplayed()
        onNodeWithText("Next").assertIsNotEnabled()
        assertEquals(0, onAllNodes(hasText("Agents work on this computer", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, count("Cloud Computer", substring = true))
        assertEquals(0, count("Cloud computer", substring = true))
        assertEquals(0, count("Coming soon", substring = true))
    }

    @Test
    fun withoutManagePermissionStaysOnTypeStepWithWarningAndNextDisabled() = runRobolectricComposeUiTest {
        showSheet(
            AddComputerDialogState(
                step = AddComputerStep.Type,
                canManage = false,
                atLimit = false,
            )
        )

        onNodeWithText("Add computer").assertIsDisplayed()
        onNodeWithText("You do not have permission to add computers.").assertIsDisplayed()
        onNodeWithText("Which computer?").assertIsDisplayed()
        onNodeWithText("Your own computer").assertIsDisplayed()
        onNodeWithText("Next").assertIsNotEnabled()
        assertEquals(0, count("Cloud Computer", substring = true))
        assertEquals(0, count("Coming soon", substring = true))
    }

    private fun ComposeUiTest.count(text: String, substring: Boolean = false): Int =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}
