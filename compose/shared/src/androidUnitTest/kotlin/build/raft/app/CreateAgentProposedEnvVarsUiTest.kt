package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI test for `agent:create` `suggestedEnvVars` (botiverse/slock #16341):
 * shows ordinary proposed vars open for editing/removal and config-directory vars unticked with a warning.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h900dp")
class CreateAgentProposedEnvVarsUiTest {
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

    @Test
    fun rendersProposedRowsAndUntickedConfigDirWithProposerAndUpdatesOnEditAndTick() =
        runRobolectricComposeUiTest {
            var proposal by mutableStateOf(
                proposedEnvVarEntries(
                    linkedMapOf(
                        "NODE_ENV" to "production",
                        "CODEX_HOME" to "/shared/codex",
                    )
                )
            )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        CreateAgentProposedEnvVarsField(
                            proposal = proposal,
                            onChange = { proposal = it },
                            proposer = "Cindy",
                            enabled = true,
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithTag(CreateAgentProposedEnvVarsTestTag).assertIsDisplayed()
            onNodeWithText("Proposed by Cindy. Check each name and value, and edit or remove any before you create the agent.").assertIsDisplayed()
            onNodeWithTag(CreateAgentProposedConfigDirTestTag).assertIsDisplayed()
            onNodeWithText("CODEX_HOME").assertIsDisplayed()
            onNodeWithText(
                "Cindy proposes this config directory for the new agent. Anything configured there, such as MCP servers and hooks, runs on this Computer as the new agent. It is sent only if you tick it."
            ).assertIsDisplayed()

            // Unticked by default: omitted from submit
            assertFalse(proposal.configDirectories.single().ticked)
            assertEquals(
                buildJsonObject { put("NODE_ENV", "production") },
                proposedEnvVarsForSubmit(proposal, null),
            )

            // Edit config dir value and tick it
            onNodeWithContentDescription("Value of CODEX_HOME").performTextReplacement("/trusted/codex")
            onNodeWithText("CODEX_HOME").performClick()
            waitForIdle()

            assertTrue(proposal.configDirectories.single().ticked)
            assertEquals("/trusted/codex", proposal.configDirectories.single().value)
            assertEquals(
                buildJsonObject {
                    put("NODE_ENV", "production")
                    put("CODEX_HOME", "/trusted/codex")
                },
                proposedEnvVarsForSubmit(proposal, null),
            )

            // Remove ordinary row
            onNodeWithContentDescription("Remove").performClick()
            waitForIdle()
            assertTrue(proposal.rows.isEmpty())
            assertEquals(
                buildJsonObject { put("CODEX_HOME", "/trusted/codex") },
                proposedEnvVarsForSubmit(proposal, null),
            )
        }

    @Test
    fun rendersGenericProposerCopyWhenProposerIsNull() =
        runRobolectricComposeUiTest {
            var proposal by mutableStateOf(
                proposedEnvVarEntries(
                    linkedMapOf(
                        "TZ" to "UTC",
                        "XDG_CONFIG_HOME" to "/tmp/xdg",
                    )
                )
            )
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize()) {
                        CreateAgentProposedEnvVarsField(
                            proposal = proposal,
                            onChange = { proposal = it },
                            proposer = null,
                            enabled = true,
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithText("Proposed by the agent that sent this card. Check each name and value, and edit or remove any before you create the agent.").assertIsDisplayed()
            onNodeWithText(
                "The agent that sent this card proposes this config directory for the new agent. Anything configured there, such as MCP servers and hooks, runs on this Computer as the new agent. It is sent only if you tick it."
            ).assertIsDisplayed()
        }
}
