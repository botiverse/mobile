package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract parity tests for botiverse/slock #16692 (`runtimeConfig.codexAccess` parsing and
 * preservation across Computer bulk model changes, web `bulkChangeModelPlan.ts` +
 * `bulkChangeModelTier.test.ts`).
 */
class SettingsComputerBulkAgentsTest {
    @Test
    fun codexAccessPrimitivesMatchSlock16692() {
        assertEquals(setOf("auto", "inherit"), RAFT_CODEX_ACCESS_VALUES)
        assertTrue(isRaftCodexAccess("auto"))
        assertTrue(isRaftCodexAccess("inherit"))
        for (bad in listOf(null, "", "full", "danger-full-access", "AUTO")) {
            assertFalse(isRaftCodexAccess(bad), "expected invalid codexAccess: $bad")
        }

        assertEquals("auto", raftRuntimeConfigCodexAccess(SettingsComputerAgentRuntimeConfig(runtime = "codex", codexAccess = "auto")))
        assertEquals("inherit", raftRuntimeConfigCodexAccess(SettingsComputerAgentRuntimeConfig(runtime = "codex", codexAccess = "inherit")))
        assertNull(raftRuntimeConfigCodexAccess(SettingsComputerAgentRuntimeConfig(runtime = "codex", codexAccess = null)))
        assertNull(raftRuntimeConfigCodexAccess(SettingsComputerAgentRuntimeConfig(runtime = "codex", codexAccess = "full")))
        assertNull(raftRuntimeConfigCodexAccess(SettingsComputerAgentRuntimeConfig(runtime = "claude", codexAccess = "auto")))
        assertNull(raftRuntimeConfigCodexAccess(null))
    }

    @Test
    fun parseSharedSettingsComputerAgentsHydratesValidCodexAccessAndDropsInvalidOrNonCodex() {
        val body =
            """
            [
              {
                "id": "a-auto",
                "name": "AutoCodex",
                "machineId": "m-1",
                "runtime": "codex",
                "model": "gpt-5.5",
                "runtimeConfig": {
                  "version": 1,
                  "runtime": "codex",
                  "model": { "kind": "preset", "id": "gpt-5.5" },
                  "mode": { "kind": "fast" },
                  "codexAccess": "auto"
                }
              },
              {
                "id": "a-inherit",
                "name": "InheritCodex",
                "machineId": "m-1",
                "runtime": "codex",
                "model": "gpt-5.5",
                "runtimeConfig": {
                  "version": 1,
                  "runtime": "codex",
                  "model": { "kind": "preset", "id": "gpt-5.5" },
                  "mode": { "kind": "default" },
                  "codexAccess": "inherit"
                }
              },
              {
                "id": "a-all-access",
                "name": "FullCodex",
                "machineId": "m-1",
                "runtime": "codex",
                "model": "gpt-5.5",
                "runtimeConfig": {
                  "version": 1,
                  "runtime": "codex",
                  "model": { "kind": "preset", "id": "gpt-5.5" },
                  "mode": { "kind": "default" }
                }
              },
              {
                "id": "a-null",
                "name": "NullCodex",
                "machineId": "m-1",
                "runtime": "codex",
                "model": "gpt-5.5",
                "runtimeConfig": {
                  "version": 1,
                  "runtime": "codex",
                  "model": { "kind": "preset", "id": "gpt-5.5" },
                  "mode": { "kind": "default" },
                  "codexAccess": null
                }
              },
              {
                "id": "a-bad-string",
                "name": "BadStringCodex",
                "machineId": "m-1",
                "runtime": "codex",
                "model": "gpt-5.5",
                "runtimeConfig": {
                  "version": 1,
                  "runtime": "codex",
                  "model": { "kind": "preset", "id": "gpt-5.5" },
                  "mode": { "kind": "default" },
                  "codexAccess": "full"
                }
              },
              {
                "id": "a-bad-type",
                "name": "BadTypeCodex",
                "machineId": "m-1",
                "runtime": "codex",
                "model": "gpt-5.5",
                "runtimeConfig": {
                  "version": 1,
                  "runtime": "codex",
                  "model": { "kind": "preset", "id": "gpt-5.5" },
                  "mode": { "kind": "default" },
                  "codexAccess": true
                }
              },
              {
                "id": "a-claude",
                "name": "ClaudeAgent",
                "machineId": "m-1",
                "runtime": "claude",
                "model": "sonnet",
                "runtimeConfig": {
                  "version": 1,
                  "runtime": "claude",
                  "provider": { "kind": "default" },
                  "model": { "kind": "preset", "id": "sonnet" },
                  "mode": { "kind": "default" },
                  "codexAccess": "auto"
                }
              }
            ]
            """.trimIndent()

        val parsed = requireNotNull(parseSharedSettingsComputerAgents(body, machineId = "m-1")).associateBy { it.id }
        assertEquals("auto", parsed.getValue("a-auto").runtimeConfig?.codexAccess)
        assertTrue(raftBulkAgentHasOnlyRebuildableSettings(parsed.getValue("a-auto")))
        assertEquals("inherit", parsed.getValue("a-inherit").runtimeConfig?.codexAccess)
        assertTrue(raftBulkAgentHasOnlyRebuildableSettings(parsed.getValue("a-inherit")))
        assertNull(parsed.getValue("a-all-access").runtimeConfig?.codexAccess)
        assertNull(parsed.getValue("a-null").runtimeConfig?.codexAccess)
        assertNull(parsed.getValue("a-bad-string").runtimeConfig?.codexAccess)
        assertNull(parsed.getValue("a-bad-type").runtimeConfig?.codexAccess)
        assertNull(parsed.getValue("a-claude").runtimeConfig?.codexAccess)
    }

    @Test
    fun bulkModelChangePreservesCodexAccessOnCodexAndOmitsWhenLeavingCodexOrAbsent() {
        fun codexAgent(codexAccess: String?, fastMode: Boolean = false, envVars: Map<String, String>? = null) =
            SettingsComputerAgentSnapshot(
                id = "agent-1",
                name = "Codex",
                runtime = "codex",
                model = "gpt-5.5",
                runtimeConfig =
                    SettingsComputerAgentRuntimeConfig(
                        runtime = "codex",
                        modelKind = "preset",
                        fastMode = fastMode,
                        envVars = envVars,
                        codexAccess = codexAccess,
                    ),
            )

        fun runtimeConfigOf(body: String) =
            Json.parseToJsonElement(body).jsonObject.getValue("runtimeConfig").jsonObject

        // Codex -> Codex preserves "auto" alongside fast mode and envVars.
        val autoStay =
            runtimeConfigOf(
                buildRaftBulkAgentModelUpdateBody(
                    agent = codexAgent(codexAccess = "auto", fastMode = true, envVars = mapOf("FOO" to "bar")),
                    target = RaftBulkModelTarget(runtime = "codex", model = "gpt-6.1-sol", reasoningEffort = "high"),
                    restart = false,
                )
            )
        assertEquals("auto", autoStay["codexAccess"]?.jsonPrimitive?.content)
        assertEquals("fast", autoStay["mode"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
        assertEquals("bar", autoStay["envVars"]?.jsonObject?.get("FOO")?.jsonPrimitive?.content)

        // Codex -> Codex preserves "inherit".
        val inheritStay =
            runtimeConfigOf(
                buildRaftBulkAgentModelUpdateBody(
                    agent = codexAgent(codexAccess = "inherit"),
                    target = RaftBulkModelTarget(runtime = "codex", model = "gpt-6.1-sol", reasoningEffort = null),
                    restart = false,
                )
            )
        assertEquals("inherit", inheritStay["codexAccess"]?.jsonPrimitive?.content)

        // Codex -> Claude drops codexAccess completely (key omitted, never null).
        val switchOffCodex =
            runtimeConfigOf(
                buildRaftBulkAgentModelUpdateBody(
                    agent = codexAgent(codexAccess = "auto"),
                    target = RaftBulkModelTarget(runtime = "claude", model = "sonnet", reasoningEffort = null),
                    restart = false,
                )
            )
        assertFalse("codexAccess" in switchOffCodex, "non-Codex runtimeConfig must omit codexAccess: $switchOffCodex")

        // Codex with All Access (absent) -> Codex omits codexAccess completely.
        val allAccessStay =
            runtimeConfigOf(
                buildRaftBulkAgentModelUpdateBody(
                    agent = codexAgent(codexAccess = null),
                    target = RaftBulkModelTarget(runtime = "codex", model = "gpt-6.1-sol", reasoningEffort = null),
                    restart = false,
                )
            )
        assertFalse("codexAccess" in allAccessStay, "All Access must store no codexAccess key: $allAccessStay")

        // Codex with invalid in-memory value ("full") -> Codex omits codexAccess.
        val invalidAccess =
            runtimeConfigOf(
                buildRaftBulkAgentModelUpdateBody(
                    agent = codexAgent(codexAccess = "full"),
                    target = RaftBulkModelTarget(runtime = "codex", model = "gpt-6.1-sol", reasoningEffort = null),
                    restart = false,
                )
            )
        assertFalse("codexAccess" in invalidAccess, "invalid codexAccess must not be emitted: $invalidAccess")

        // Non-Codex -> Codex does not carry over any stray codexAccess.
        val claudeToCodex =
            runtimeConfigOf(
                buildRaftBulkAgentModelUpdateBody(
                    agent =
                        SettingsComputerAgentSnapshot(
                            id = "agent-2",
                            name = "Claude",
                            runtime = "claude",
                            model = "sonnet",
                            runtimeConfig =
                                SettingsComputerAgentRuntimeConfig(
                                    runtime = "claude",
                                    modelKind = "preset",
                                    providerKind = "default",
                                    codexAccess = "auto",
                                ),
                        ),
                    target = RaftBulkModelTarget(runtime = "codex", model = "gpt-6.1-sol", reasoningEffort = null),
                    restart = false,
                )
            )
        assertFalse("codexAccess" in claudeToCodex, "switching from Claude to Codex must omit codexAccess: $claudeToCodex")
    }
}
