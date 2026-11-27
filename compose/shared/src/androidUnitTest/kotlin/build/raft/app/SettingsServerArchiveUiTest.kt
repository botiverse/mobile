package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ArchivedAgent
import build.raft.app.data.ArchivedAgentWorkspace
import build.raft.app.data.ArchivedChannel
import build.raft.app.data.AppLocale
import build.raft.app.data.SharedServerArchiveIntent
import build.raft.app.data.SharedServerArchiveState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.settings.SettingsArchivedAgentsSection
import build.raft.app.settings.SettingsArchivedChannelsSection
import build.raft.app.settings.SettingsServerArchiveUi
import build.raft.app.settings.settingsArchivedAgentWorkspaceTag
import build.raft.app.settings.settingsArchivedChannelRowTag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Server's Archived channels and Archived agents open like the web's: a channel's row
 * opens the channel (`nav.toChannel`), an agent's row its profile (`nav.toAgent`), and View
 * workspace, shown only for a workspace on a computer, the agent's workspace (`nav.toAgentWorkspace`).
 * Unarchive stays its own button.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp")
class SettingsServerArchiveUiTest {
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

    private val openedChannels = mutableListOf<String>()
    private val openedAgents = mutableListOf<String>()
    private val openedWorkspaces = mutableListOf<String>()
    private val intents = mutableListOf<SharedServerArchiveIntent>()

    private fun ComposeUiTest.show() {
        val ui =
            SettingsServerArchiveUi(
                state = archiveUiState,
                canArchiveChannels = true,
                dispatch = { intents += it },
                onOpenChannel = { openedChannels += it.id },
                onOpenAgent = { openedAgents += it },
                onOpenAgentWorkspace = { openedWorkspaces += it },
            )
        setContent {
            MigrationFixtureHost {
                Column {
                    SettingsArchivedChannelsSection(ui)
                    SettingsArchivedAgentsSection(ui)
                }
            }
        }
    }

    @Test
    fun channelRowOpensTheChannelAndUnarchiveStaysApart() = runRobolectricComposeUiTest {
        show()
        onNodeWithTag(settingsArchivedChannelRowTag("c-old")).performClick()
        onNode(hasText("Unarchive") and hasAnyAncestor(hasTestTag(settingsArchivedChannelRowTag("c-new")))).performClick()
        runOnIdle {
            assertEquals(listOf("c-old"), openedChannels)
            assertEquals(listOf<SharedServerArchiveIntent>(SharedServerArchiveIntent.Unarchive("c-new")), intents)
        }
    }

    @Test
    fun viewWorkspaceOnlyForAWorkspaceOnAComputer() = runRobolectricComposeUiTest {
        show()
        onAllNodesWithTag(settingsArchivedAgentWorkspaceTag("a-2")).assertCountEquals(0)
        onAllNodesWithTag(settingsArchivedAgentWorkspaceTag("a-3")).assertCountEquals(0)
        onNodeWithText("View workspace").assertIsDisplayed()
        onNodeWithTag(settingsArchivedAgentWorkspaceTag("a-1")).performClick()
        runOnIdle {
            assertEquals(listOf("a-1"), openedWorkspaces)
            assertEquals(emptyList<String>(), openedAgents)
        }
    }
}

private val archiveUiState =
    SharedServerArchiveState(
        serverId = "server-1",
        channels =
            listOf(
                ArchivedChannel("c-new", "launch", "channel", "2026-10-01T08:00:00.000Z", hasExternalRelation = false),
                ArchivedChannel("c-old", "old-plans", "private", "2026-03-01T08:00:00.000Z", hasExternalRelation = false),
            ),
        agents =
            listOf(
                ArchivedAgent("a-1", "Scout", null, "2026-11-01T08:00:00.000Z", ArchivedAgentWorkspace.OnComputer, "Studio Mac"),
                ArchivedAgent("a-2", "helper", null, "2026-10-01T08:00:00.000Z", ArchivedAgentWorkspace.Deleting, "Studio Mac"),
                ArchivedAgent("a-3", "gone", null, "2026-09-01T08:00:00.000Z", ArchivedAgentWorkspace.Deleted, null),
            ),
    )
