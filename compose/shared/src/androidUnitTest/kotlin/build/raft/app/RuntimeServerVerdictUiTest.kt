package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.ExistingAgentRuntimeOptions
import build.raft.app.data.RaftRuntimeCatalogNames
import build.raft.app.data.RuntimeCatalogSource
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.SharedCreateAgentRuntimeOption
import build.raft.app.data.runtimeDeprecatedForAgent
import build.raft.app.model.AgentProfile
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.ServerId
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #proj-runtime:#57 on a real composition (web botiverse/slock #14568 334246a2e6, "Create Agent offers
 * Antigravity where the server admits it (OAR flag on), and lets you pick it"; #14584 d759141c94):
 * the runtime picker offers what the server's runtime-options rows say, and the profile's
 * "(deprecated)" label and warning follow the agent's own row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeServerVerdictUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val selectableOption = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
    private val dropdown = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)

    @Test
    fun whenTheServerSaysAntigravityIsSelectableThePickerLetsYouPickIt() = runRobolectricComposeUiTest {
        // The bundled table (the fallback) deprecates Antigravity; this server's rows admit it.
        check(BundledRuntimeCatalog.entry("antigravity")?.deprecated == true)
        var runtime by mutableStateOf("claude")
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RuntimeFormV2RuntimeSection(
                    runtime = runtime,
                    onRuntimeChange = { runtime = it },
                    machineRuntimeIds = listOf("claude", "codex", "antigravity"),
                    runtimeAvailabilityError = null,
                    enabled = true,
                    catalog = BundledRuntimeCatalog,
                    options = listOf(row("claude"), row("codex"), row("antigravity"))
                ) {}
            }
        }
        onNode(dropdown).performClick()
        waitForIdle()
        onNode(hasText("Antigravity CLI") and selectableOption).performClick()
        waitForIdle()
        assertEquals("antigravity", runtime)
    }

    @Test
    fun whenTheServerDoesNotOfferAntigravityThePickerDoesNotListIt() = runRobolectricComposeUiTest {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RuntimeFormV2RuntimeSection(
                    runtime = "claude",
                    onRuntimeChange = {},
                    machineRuntimeIds = listOf("claude", "codex", "antigravity"),
                    runtimeAvailabilityError = null,
                    enabled = true,
                    catalog = BundledRuntimeCatalog,
                    options = listOf(row("claude"), row("codex"))
                ) {}
            }
        }
        onNode(dropdown).performClick()
        waitForIdle()
        assertTrue(onAllNodes(selectableOption).fetchSemanticsNodes().isNotEmpty(), "the list is open")
        assertTrue(onAllNodes(hasText("Antigravity", substring = true) and selectableOption).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theDeprecatedWarningFollowsTheAgentsOwnRowNotTheBundledTable() = runRobolectricComposeUiTest {
        // Antigravity revived on this server: its row has no admissionReason, so no warning.
        val revived = rows(current = "antigravity", deprecated = false)
        var shown by mutableStateOf(config("antigravity") to runtimeDeprecatedForAgent("antigravity", revived, serverId = null))
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Column {
                    MembersAgentRuntimeConfigRows(
                        resolution = shown.first,
                        runtimeModelsCatalog = null,
                        machineId = null,
                        serverId = null,
                        runtimeDeprecated = shown.second
                    )
                }
            }
        }
        onNodeWithTag(MEMBERS_RUNTIME_DEPRECATED_BANNER_TAG).assertDoesNotExist()
        onNodeWithText("Antigravity CLI", substring = true, useUnmergedTree = true).assertExists()
        assertTrue(onAllNodes(hasText("(deprecated)", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        // Copilot, deprecated here: its row says so, so the label and the warning do.
        runOnIdle { shown = config("copilot") to runtimeDeprecatedForAgent("copilot", rows(current = "copilot", deprecated = true), serverId = null) }
        waitForIdle()
        onNodeWithTag(MEMBERS_RUNTIME_DEPRECATED_BANNER_TAG).assertExists()
        onNodeWithText("This agent uses a deprecated runtime", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Copilot CLI\u00A0(deprecated)", substring = true, useUnmergedTree = true).assertExists()

        // Switched to Codex CLI with Copilot's rows still in hand: the old row says nothing about Codex.
        runOnIdle { shown = config("codex") to runtimeDeprecatedForAgent("codex", rows(current = "copilot", deprecated = true), serverId = null) }
        waitForIdle()
        onNodeWithTag(MEMBERS_RUNTIME_DEPRECATED_BANNER_TAG).assertDoesNotExist()
    }

    @AfterTest
    fun forgetCatalogs() = RaftRuntimeCatalogNames.clear()

    /**
     * Antigravity offered again on [RevivedServer] (`GET /servers/:id/runtimes` answers
     * `deprecated: false`, web `useRuntimeDeprecatedHere`): every label that names an agent's
     * runtime there drops "(deprecated)", without the agent's runtime-options row; another
     * server's labels (and a label with no server) keep the bundled answer.
     */
    @Test
    fun aServerThatOffersAntigravityAgainLabelsItWithoutDeprecated() = runRobolectricComposeUiTest {
        RaftRuntimeCatalogNames.publish(RevivedServer, revivedCatalog())
        var serverId by mutableStateOf<String?>(RevivedServer)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Column {
                    // A person's "Created agents" row.
                    MembersHumanCreatedAgentRow(
                        agent = AgentProfile(id = MemberId("agent-1"), userId = "agent-1", displayName = "Ruby", handle = "ruby", runtime = "antigravity"),
                        serverId = serverId,
                        divider = false
                    )
                    // The agent profile's runtime chip.
                    MembersAgentProfileHeader(
                        member = MemberProfile(id = MemberId("agent-1"), displayName = "Ruby", handle = "ruby", kind = MemberKind.Agent, serverId = serverId?.let(::ServerId)),
                        summary = AgentDetailSummary(id = "agent-1", name = "ruby", runtime = "antigravity"),
                        canEdit = false,
                        agentStatusObservedState = null,
                        agentStatusIndicatorState = null,
                        onChangeAvatar = {}
                    )
                    // The profile's Runtime row and its warning, before the agent's runtime-options rows load.
                    MembersAgentRuntimeConfigRows(
                        resolution = config("antigravity"),
                        runtimeModelsCatalog = null,
                        machineId = null,
                        serverId = serverId
                    )
                    // The mention / avatar preview card.
                    RaftProfilePreviewCardContent(
                        RaftProfilePreviewCardModel(
                            memberId = "agent-1",
                            kind = MemberKind.Agent,
                            displayName = "Ruby",
                            runtimeLabel = "Antigravity CLI",
                            runtimeId = "antigravity",
                            runtimeServerId = serverId
                        )
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(0, deprecatedLabels(), "revived here: no label says (deprecated)")
        assertTrue(onAllNodes(hasText("Antigravity CLI", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size >= 4)
        onNodeWithTag(MEMBERS_RUNTIME_DEPRECATED_BANNER_TAG).assertDoesNotExist()

        // Another server, whose catalog says nothing yet: the bundled table (Antigravity deprecated).
        runOnIdle { serverId = "other-server" }
        waitForIdle()
        assertEquals(4, deprecatedLabels(), "another server never reads the revived server's answer")
        onNodeWithTag(MEMBERS_RUNTIME_DEPRECATED_BANNER_TAG).assertExists()

        // No server at all: the bundled table too, never the catalog read last.
        runOnIdle { serverId = null }
        waitForIdle()
        assertEquals(4, deprecatedLabels())
    }

    private fun androidx.compose.ui.test.ComposeUiTest.deprecatedLabels(): Int =
        onAllNodes(hasText("Antigravity CLI\u00A0(deprecated)", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun revivedCatalog(): ServerRuntimeCatalog =
        ServerRuntimeCatalog(
            catalogVersion = 1,
            runtimes = BundledRuntimeCatalog.runtimes.map { if (it.id == "antigravity") it.copy(deprecated = false) else it },
            source = RuntimeCatalogSource.Server
        )

    private fun row(runtimeId: String) =
        SharedCreateAgentRuntimeOption(
            runtimeId = runtimeId,
            capabilityStatus = "available",
            admissionStatus = "available_for_new",
            admissionReason = null,
            current = false,
            availableForNew = true,
            manageableForCurrentAgent = false,
            canSelectInThisContext = true,
            runtimeFormV2 = true
        )

    private fun rows(current: String, deprecated: Boolean) =
        ExistingAgentRuntimeOptions(
            agentId = "agent-1",
            machineId = "m-1",
            options =
                listOf(
                    row("claude"),
                    row(current).copy(
                        current = true,
                        admissionStatus = if (deprecated) "grandfathered_current" else "available_for_new",
                        admissionReason = if (deprecated) "deprecated" else null,
                        availableForNew = !deprecated,
                        manageableForCurrentAgent = true
                    )
                )
        )

    private companion object {
        const val RevivedServer = "oar-server"
    }

    private fun config(runtime: String): MembersAgentRuntimeConfigResolution =
        MembersAgentRuntimeConfigResolution.Content(
            RuntimeConfig(
                runtime = runtime,
                provider = null,
                model = membersRuntimeModelConfig(runtime, "default", "preset"),
                mode = RuntimeModeConfig.Default,
                reasoningEffort = null,
                envVars = null
            )
        )
}
