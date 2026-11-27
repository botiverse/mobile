package build.raft.app

import build.raft.app.data.runtimeform.v2.ParsedRuntimeFormV2
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `agent:create` `suggestedEnvVars` parity tests (botiverse/slock #16341):
 * `isConfigDirectoryEnvVarName`, `proposedEnvVarEntries`, `proposedEnvVarsForSubmit`,
 * and `createAgentDialogSubmit` merging proposed variables under form `envVars`.
 */
class CreateAgentProposedEnvVarsTest {
    @Test
    fun configDirectoryNamesMatchCaseInsensitivelyAndExcludeOrdinaryNames() {
        for (name in listOf(
            "CODEX_HOME",
            "codex_home",
            "CLAUDE_CONFIG_DIR",
            "Claude_Config_Dir",
            "PI_CODING_AGENT_DIR",
            "XDG_CONFIG_HOME",
        )) {
            assertTrue(isConfigDirectoryEnvVarName(name), name)
        }
        for (name in listOf("NODE_ENV", "LOG_LEVEL", "HOME", "PATH", "CODEX_HOME_EXTRA", "")) {
            assertFalse(isConfigDirectoryEnvVarName(name), name)
        }
    }

    @Test
    fun proposedEnvVarEntriesSplitsOrdinaryRowsAndUntickedConfigDirectories() {
        val proposal =
            proposedEnvVarEntries(
                linkedMapOf(
                    "NODE_ENV" to "production",
                    "CODEX_HOME" to "/shared/codex",
                    "TZ" to "UTC",
                    "claude_config_dir" to "/shared/claude",
                )
            )
        assertEquals(listOf("NODE_ENV" to "production", "TZ" to "UTC"), proposal.rows)
        assertEquals(
            listOf(
                ProposedConfigDirectory("CODEX_HOME", "/shared/codex", ticked = false),
                ProposedConfigDirectory("claude_config_dir", "/shared/claude", ticked = false),
            ),
            proposal.configDirectories,
        )
        assertEquals(ProposedEnvVars(), proposedEnvVarEntries(null))
        assertEquals(ProposedEnvVars(), proposedEnvVarEntries(emptyMap()))
    }

    @Test
    fun proposedEnvVarsForSubmitKeepsEditedRowsTickedConfigDirsAndLetsFormWinOnSameKey() {
        val proposal =
            ProposedEnvVars(
                rows = listOf("NODE_ENV" to "staging", "  " to "dropped", " LOG_LEVEL " to "debug"),
                configDirectories =
                    listOf(
                        ProposedConfigDirectory("CODEX_HOME", "/shared/codex", ticked = false),
                        ProposedConfigDirectory("XDG_CONFIG_HOME", "/shared/xdg", ticked = true),
                    ),
            )
        val formEnvVars =
            buildJsonObject {
                put("NODE_ENV", "form-wins")
                put("EXTRA", "from-form")
            }
        val merged = proposedEnvVarsForSubmit(proposal, formEnvVars)
        assertEquals(
            buildJsonObject {
                put("NODE_ENV", "form-wins")
                put("LOG_LEVEL", "debug")
                put("XDG_CONFIG_HOME", "/shared/xdg")
                put("EXTRA", "from-form")
            },
            merged,
        )
    }

    @Test
    fun createAgentDialogSubmitAttachesMergedProposedEnvVarsOnComputerAgentsOnly() {
        val loaded =
            RuntimeFormV2Loaded(
                form =
                    ParsedRuntimeFormV2(
                        runtimeId = "codex",
                        schemaVersion = "2",
                        fields =
                            listOf(
                                RuntimeFormV2Field(
                                    key = "envVars",
                                    kind = RuntimeFormV2FieldKind.StringMap,
                                    required = false,
                                    advanced = true,
                                    label = "Environment variables",
                                )
                            ),
                        optionSources = emptyMap(),
                        blockingFieldKeys = emptyList(),
                    ),
                sources = emptyMap(),
            )
        val values =
            mapOf(
                "envVars" to buildJsonObject {
                    put("NODE_ENV", "override")
                    put("FROM_ADVANCED", "1")
                }
            )
        val proposal =
            ProposedEnvVars(
                rows = listOf("NODE_ENV" to "proposed", "APP_MODE" to "ci"),
                configDirectories =
                    listOf(
                        ProposedConfigDirectory("CODEX_HOME", "/shared/codex", ticked = false),
                        ProposedConfigDirectory("CLAUDE_CONFIG_DIR", "/shared/claude", ticked = true),
                    ),
            )
        val submit =
            assertNotNull(
                createAgentDialogSubmit(
                    name = "ci-bot",
                    description = "Runs CI",
                    machineId = "comp-1",
                    external = false,
                    runtime = "codex",
                    loaded = loaded,
                    values = values,
                    proposedEnvVars = proposal,
                )
            )
        val envVars = submit.runtimeFormV2?.formValues?.get("envVars") as? JsonObject
        assertEquals(
            buildJsonObject {
                put("NODE_ENV", JsonPrimitive("override"))
                put("APP_MODE", JsonPrimitive("ci"))
                put("CLAUDE_CONFIG_DIR", JsonPrimitive("/shared/claude"))
                put("FROM_ADVANCED", JsonPrimitive("1"))
            },
            envVars,
        )

        // Hosted and external agents do not emit runtimeFormV2
        val hosted =
            assertNotNull(
                createAgentDialogSubmit(
                    name = "cloud-bot",
                    description = "",
                    machineId = null,
                    external = false,
                    runtime = "codex",
                    loaded = loaded,
                    values = values,
                    hosted = true,
                    proposedEnvVars = proposal,
                )
            )
        assertNull(hosted.runtimeFormV2)
    }
}
