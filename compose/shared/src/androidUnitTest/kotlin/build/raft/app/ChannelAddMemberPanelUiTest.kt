package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.members.MembersCreateAgentMode
import build.raft.app.resources.StringDesc
import build.raft.app.thread.RaftThreadMemberItem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Add Member (web ChannelMembers add view): ticking candidates only selects them and counts them
 * in "Add selected (N)", which is disabled at 0 and sends the selection as one batch; the
 * one "Create agent" row opens a menu (Create agent, Create external agent) whose items each ask
 * to create that kind of agent, which joins this channel (the row named after a search that found
 * no one); the row is disabled with the reason, and opens no menu, without permission; members already in the channel are not listed.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ChannelAddMemberPanelUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    private class Calls {
        val batches = mutableListOf<List<String>>()
        val creates = mutableListOf<Pair<String, MembersCreateAgentMode>>()
    }

    private val candidates =
        listOf(
            RaftThreadMemberItem("agent-cindy", "Cindy", handle = "cindy", kind = "agent", description = "Chief of Staff"),
            RaftThreadMemberItem("agent-ux", "Product UX Designer", handle = "ux", kind = "agent"),
            RaftThreadMemberItem("user-artin", "artin", handle = "artin", description = "Owner"),
            RaftThreadMemberItem("user-designer", "Designer", handle = "designer"),
        )

    private fun ComposeUiTest.show(
        calls: Calls,
        members: List<RaftThreadMemberItem> = emptyList(),
        canCreateAgents: Boolean = true,
    ) = setContent {
        RaftDesignSystemTheme {
            Box(Modifier.fillMaxSize()) {
                ThreadAddMemberPanel(
                    members = members,
                    candidates = candidates,
                    canAddMembers = true,
                    channelName = "design",
                    addMemberStatus = "idle",
                    addMemberErrorCode = null,
                    onSubmitBatch = { picked -> calls.batches += picked.map { it.id } },
                    canCreateAgents = canCreateAgents,
                    onCreateAgent = { name, mode -> calls.creates += name to mode },
                )
            }
        }
    }

    /** A candidate's checkbox row (a selected one also shows as a badge with the same name). */
    private fun ComposeUiTest.row(name: String): SemanticsNodeInteraction = onNode(hasText(name) and isToggleable())

    @Test
    fun tappingRowsSelectsAndCountsWithoutAdding() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            show(calls)
            onNodeWithText("Add selected (0)").assertIsNotEnabled()

            row("Cindy").performClick()
            row("Designer").performClick()
            assertTrue(calls.batches.isEmpty(), "a tap on a row added members straight away")
            onNodeWithText("Add selected (2)").assertIsEnabled()

            // Unticking takes it out of the count again.
            row("Designer").performClick()
            onNodeWithText("Add selected (1)").assertIsEnabled()
            row("Designer").performClick()

            onNodeWithText("Add selected (2)").performClick()
            assertEquals(listOf(listOf("agent-cindy", "user-designer")), calls.batches)
        }

    @Test
    fun theOneAddAgentRowOpensAChooserDialogWithDirectEntryRows() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            show(calls)
            // One "Add agent" row, one auto-join line; chooser rows are only in the modal.
            onAllNodesWithText("Joins #design automatically after creation").assertCountEquals(1)
            onNodeWithText("Add agent").assertExists()
            onNodeWithText("Create agent").assertDoesNotExist()
            onNodeWithText("Connect an external agent").assertDoesNotExist()

            // Clicking the row opens the centered chooser dialog (no radios, no Next button).
            onNodeWithTag("add-member-create-agent-entry").performClick()
            onNodeWithTag("add-member-create-agent-chooser").assertExists()
            onNodeWithText("Create agent").assertExists()
            onNodeWithText("Runs on your computer or cloud").assertExists()
            onNodeWithText("Connect an external agent").assertExists()
            onNodeWithText("Runs elsewhere, connects to Raft").assertExists()
            onNodeWithText("Next").assertDoesNotExist()
            onAllNodes(isSelectable()).assertCountEquals(0)

            // Direct entry 1: Create agent closes chooser and opens Managed create.
            onNodeWithTag("add-member-create-agent-chooser-hosted").performClick()
            assertEquals(listOf("" to MembersCreateAgentMode.Managed), calls.creates)
            onNodeWithTag("add-member-create-agent-chooser").assertDoesNotExist()

            // Direct entry 2: Connect an external agent closes chooser and opens External create.
            onNodeWithTag("add-member-create-agent-entry").performClick()
            onNodeWithTag("add-member-create-agent-chooser-external").performClick()
            assertEquals(
                listOf("" to MembersCreateAgentMode.Managed, "" to MembersCreateAgentMode.External),
                calls.creates,
            )
            onNodeWithTag("add-member-create-agent-chooser").assertDoesNotExist()
        }

    @Test
    fun addAgentRowIsHiddenWithoutCreatePermission() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            show(calls, canCreateAgents = false)
            onNodeWithTag("add-member-create-agent-entry").assertDoesNotExist()
            onNodeWithText("Add agent").assertDoesNotExist()
        }

    @Test
    fun promotedSearchNameCarriesOnlyIntoManagedCreate() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            show(calls)
            onNodeWithText("Name").performTextInput("scout")
            onNodeWithText("Add agent “scout”").assertExists()

            onNodeWithTag("add-member-create-agent-entry").performClick()
            onNodeWithTag("add-member-create-agent-chooser-hosted").performClick()
            assertEquals(listOf("scout" to MembersCreateAgentMode.Managed), calls.creates)

            onNodeWithTag("add-member-create-agent-entry").performClick()
            onNodeWithTag("add-member-create-agent-chooser-external").performClick()
            assertEquals(
                listOf("scout" to MembersCreateAgentMode.Managed, "" to MembersCreateAgentMode.External),
                calls.creates,
            )
        }
}
