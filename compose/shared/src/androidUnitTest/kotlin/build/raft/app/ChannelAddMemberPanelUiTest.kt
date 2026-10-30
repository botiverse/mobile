package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.SemanticsNodeInteraction
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
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
 * in "Add Selected (N)", which is disabled at 0 and sends the selection as one batch; the
 * Create Agent row asks to create an agent that joins this channel (named after a search that
 * found no one); members already in the channel are not listed.
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
        val creates = mutableListOf<String>()
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
                    onCreateAgent = { name -> calls.creates += name },
                )
            }
        }
    }

    /** A candidate's checkbox row (a selected one also shows as a badge with the same name). */
    private fun ComposeUiTest.row(name: String): SemanticsNodeInteraction = onNode(hasText(name) and isToggleable())

    private fun ComposeUiTest.badge(name: String): SemanticsNodeInteraction =
        onNode(hasText(name) and hasClickAction() and isToggleable().not())

    @Test
    fun tappingRowsSelectsAndCountsWithoutAdding() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            show(calls)
            onNodeWithText("Add Selected (0)").assertIsNotEnabled()

            row("Cindy").performClick()
            row("Designer").performClick()
            assertTrue(calls.batches.isEmpty(), "a tap on a row added members straight away")
            onNodeWithText("Add Selected (2)").assertIsEnabled()

            // Unticking takes it out of the count again.
            row("Designer").performClick()
            onNodeWithText("Add Selected (1)").assertIsEnabled()
            row("Designer").performClick()

            onNodeWithText("Add Selected (2)").performClick()
            assertEquals(listOf(listOf("agent-cindy", "user-designer")), calls.batches)
        }

    @Test
    fun rowsAreCheckboxesAndSelectedPeopleShowAsBadges() =
        runRobolectricComposeUiTest {
            show(Calls())
            row("artin").assertIsOff()
            badge("artin").assertDoesNotExist()
            row("artin").performClick()
            row("artin").assertIsOn()
            onNodeWithText("Add Selected (1)").assertIsEnabled()
            // The removable badge above the list deselects.
            badge("artin").performClick()
            row("artin").assertIsOff()
            onNodeWithText("Add Selected (0)").assertIsNotEnabled()
        }

    @Test
    fun membersAlreadyInTheChannelAreNotListed() =
        runRobolectricComposeUiTest {
            show(Calls(), members = listOf(RaftThreadMemberItem("agent-cindy", "Cindy", handle = "cindy", kind = "agent")))
            row("Cindy").assertDoesNotExist()
            row("Product UX Designer").assertExists()
            onNodeWithText("Agents").assertExists()
            onNodeWithText("Humans").assertExists()
        }

    @Test
    fun createAgentRowOpensCreateForThisChannel() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            show(calls)
            onNodeWithText("Joins #design automatically after creation").assertExists()
            onNodeWithText("Create Agent").performClick()
            assertEquals(listOf(""), calls.creates)

            // A search that finds no one names the new agent after it.
            onNode(hasSetTextAction()).performTextInput("@mia")
            onNodeWithText("No members match \"@mia\"").assertExists()
            onNodeWithText("Create Agent “mia”").performClick()
            assertEquals(listOf("", "mia"), calls.creates)
        }

    @Test
    fun withoutCreatePermissionTheRowStaysDisabledWithTheReason() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            show(calls, canCreateAgents = false)
            onNodeWithText("Create Agent").performClick()
            assertTrue(calls.creates.isEmpty())
            onNodeWithText("creating Agents on this server needs an owner or admin", substring = true).assertExists()
        }
}
