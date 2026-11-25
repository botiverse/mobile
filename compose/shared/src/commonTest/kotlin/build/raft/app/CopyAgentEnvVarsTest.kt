package build.raft.app

import build.raft.app.data.AgentSummary
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.model.MemberId
import build.raft.app.model.RUNTIME_CONFIG_VERSION
import build.raft.app.model.ServerId
import build.raft.app.model.isControlledRuntimeEnvKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Mirrors web `copyAgentEnvVars.test.ts` (botiverse/slock #16606, `#proj-runtime:#99`):
 * candidate filtering, permission checks, controlled runtime env key stripping, insertion order,
 * case-insensitive non-overwriting merge, blank-row cleanup, and `GET /agents` runtimeConfig.envVars parsing.
 */
class CopyAgentEnvVarsTest {
    private fun makeAgent(
        id: String = "agent-1",
        name: String = "atlas",
        displayName: String? = "Atlas",
        avatarUrl: String? = "pixel:atlas",
        runtime: String? = "codex",
        machineId: String? = "machine-1",
        creatorType: String? = "user",
        creatorId: String? = "user-1",
        deletedAt: String? = null,
        isExternal: Boolean = false,
        managedByAppInstallId: String? = null,
        profileProjection: String? = null,
        envVars: Map<String, String> = emptyMap(),
        orderedEnvVars: Map<String, String> = envVars,
        runtimeConfigVersion: Int? = null,
        runtimeConfigEnvVars: Map<String, String>? = null,
    ): AgentSummary =
        AgentSummary(
            id = id,
            name = name,
            displayName = displayName,
            description = null,
            serverRole = "member",
            status = "active",
            model = "gpt-5.6-sol",
            runtimeModelKind = "preset",
            runtimeProvider = null,
            runtime = runtime,
            reasoning = null,
            executionMode = "byoc",
            machineId = machineId,
            machineName = "Mia's MacBook Pro",
            daemonVersion = "1.25.0",
            avatarUrl = avatarUrl,
            creatorId = creatorId,
            creatorType = creatorType,
            creator = null,
            createdAt = "2026-11-24T00:00:00.000Z",
            envVarNames = envVars.keys.sorted(),
            envVars = envVars,
            createdAgents = emptyList(),
            activity = null,
            activityDetail = null,
            deletedAt = deletedAt,
            managedByAppInstallId = managedByAppInstallId,
            profileProjection = profileProjection,
            isExternal = isExternal,
            runtimeConfigVersion = runtimeConfigVersion,
            runtimeConfigEnvVars = runtimeConfigEnvVars,
            orderedEnvVars = orderedEnvVars,
        )

    @Test
    fun controlledRuntimeEnvKeysMatchSharedRulesCaseInsensitively() {
        assertTrue(isControlledRuntimeEnvKey("claude", "ANTHROPIC_BASE_URL"))
        assertTrue(isControlledRuntimeEnvKey("claude", "anthropic_api_key"))
        assertTrue(isControlledRuntimeEnvKey("claude", "ANTHROPIC_CUSTOM_MODEL_OPTION"))
        assertFalse(isControlledRuntimeEnvKey("claude", "OPENAI_API_KEY"))

        assertTrue(isControlledRuntimeEnvKey("codex", "OPENAI_BASE_URL"))
        assertFalse(isControlledRuntimeEnvKey("codex", "OPENAI_API_KEY"))
        assertFalse(isControlledRuntimeEnvKey("codex", "ANTHROPIC_BASE_URL"))

        assertTrue(isControlledRuntimeEnvKey("pi", "DEEPSEEK_API_KEY"))
        assertFalse(isControlledRuntimeEnvKey("pi", "OPENAI_API_KEY"))

        assertTrue(isControlledRuntimeEnvKey("builtin", "OPENAI_API_KEY"))
        assertTrue(isControlledRuntimeEnvKey("builtin", "ANTHROPIC_OAUTH_TOKEN"))
        assertTrue(isControlledRuntimeEnvKey("builtin", "QWEN_TOKEN_PLAN_TEAM_API_KEY"))
        assertTrue(isControlledRuntimeEnvKey("builtin", "OPENAI_BASE_URL"))
        assertFalse(isControlledRuntimeEnvKey("builtin", "HTTP_PROXY"))
    }

    @Test
    fun copyableSameComputerEnvVarAgentsListsOnlyEditableSameComputerAgentsWithValidEnvVars() {
        val agents =
            listOf(
                makeAgent(
                    id = "self",
                    name = "self",
                    displayName = "Self",
                    runtimeConfigVersion = RUNTIME_CONFIG_VERSION,
                    runtimeConfigEnvVars = linkedMapOf("HTTP_PROXY" to "http://proxy:8080"),
                ),
                makeAgent(
                    id = "atlas",
                    name = "atlas",
                    displayName = "Atlas",
                    runtimeConfigVersion = RUNTIME_CONFIG_VERSION,
                    runtimeConfigEnvVars =
                        linkedMapOf(
                            "HTTP_PROXY" to "http://proxy.corp.internal:8080",
                            "CODEX_HOME" to "/Users/mia/.codex-work",
                            "OPENAI_API_KEY" to "sk-user-entered-key",
                            "SLOCK_ONBOARDING_MEMORY_SEED" to "first-cindy",
                        ),
                ),
                makeAgent(
                    id = "other-machine",
                    name = "remote",
                    displayName = "Remote",
                    machineId = "machine-2",
                    envVars = linkedMapOf("HTTP_PROXY" to "http://other:8080"),
                ),
                makeAgent(
                    id = "other-owner",
                    name = "stranger",
                    displayName = "Stranger",
                    creatorId = "user-2",
                    envVars = linkedMapOf("HTTP_PROXY" to "http://stranger:8080"),
                ),
                makeAgent(
                    id = "deleted",
                    name = "gone",
                    displayName = "Gone",
                    deletedAt = "2026-11-24T01:00:00.000Z",
                    envVars = linkedMapOf("HTTP_PROXY" to "http://gone:8080"),
                ),
                makeAgent(
                    id = "external",
                    name = "ext",
                    displayName = "External",
                    isExternal = true,
                    envVars = linkedMapOf("HTTP_PROXY" to "http://ext:8080"),
                ),
                makeAgent(
                    id = "app-bot",
                    name = "bot",
                    displayName = "Bot",
                    managedByAppInstallId = "install-1",
                    envVars = linkedMapOf("HTTP_PROXY" to "http://bot:8080"),
                ),
                makeAgent(
                    id = "seed-only",
                    name = "cindy",
                    displayName = "Cindy",
                    envVars = linkedMapOf("SLOCK_ONBOARDING_MEMORY_SEED" to "first-cindy"),
                ),
                makeAgent(
                    id = "external-runtime",
                    name = "ext-runtime-bot",
                    displayName = "External Runtime Bot",
                    runtime = "external",
                    envVars = linkedMapOf("HTTP_PROXY" to "http://external-runtime:8080"),
                ),
                makeAgent(
                    id = "bounded-summary",
                    name = "summary-only",
                    displayName = "Summary Only",
                    profileProjection = "channel_summary",
                    envVars = linkedMapOf("HTTP_PROXY" to "http://summary:8080"),
                ),
                makeAgent(
                    id = "bolt",
                    name = "bolt",
                    displayName = "Bolt",
                    envVars =
                        linkedMapOf(
                            "ANTHROPIC_BASE_URL" to "https://relay.example.com",
                            "PLAYWRIGHT_BROWSERS_PATH" to "/opt/pw",
                            " playwright_browsers_path " to "/opt/pw-dup",
                        ),
                ),
            )

        // Member who created self, atlas, bolt (cannot edit user-2's stranger).
        val forClaude =
            copyableSameComputerEnvVarAgents(
                agents = agents,
                machineId = "machine-1",
                targetRuntimeId = "claude",
                excludeAgentId = "self",
                currentUserId = "user-1",
                canEditAgents = false,
            )
        assertEquals(listOf("atlas", "bolt"), forClaude.map { it.agentId })
        assertEquals(listOf("HTTP_PROXY", "CODEX_HOME", "OPENAI_API_KEY"), forClaude[0].entries.map { it.key })
        assertEquals(listOf("PLAYWRIGHT_BROWSERS_PATH"), forClaude[1].entries.map { it.key })

        // Admin (canEditAgents = true) also sees user-2's agent; on Codex, ANTHROPIC_BASE_URL is kept.
        val forAdminOnCodex =
            copyableSameComputerEnvVarAgents(
                agents = agents,
                machineId = "machine-1",
                targetRuntimeId = "codex",
                excludeAgentId = "self",
                currentUserId = "user-1",
                canEditAgents = true,
            )
        assertEquals(listOf("atlas", "bolt", "other-owner"), forAdminOnCodex.map { it.agentId })
        assertEquals(
            listOf("ANTHROPIC_BASE_URL", "PLAYWRIGHT_BROWSERS_PATH"),
            forAdminOnCodex.first { it.agentId == "bolt" }.entries.map { it.key },
        )
    }

    @Test
    fun mergeCopiedEnvVarsAppendsWithoutOverwritingAndRemovesUntouchedBlankRows() {
        val merged =
            mergeCopiedEnvVars(
                current =
                    listOf(
                        CopyableEnvVarEntry("LOG_LEVEL", "debug"),
                        CopyableEnvVarEntry("", ""),
                    ),
                source =
                    listOf(
                        CopyableEnvVarEntry("log_level", "info"),
                        CopyableEnvVarEntry("LOG_LEVEL", "warn"),
                        CopyableEnvVarEntry("HTTP_PROXY", "http://proxy:8080"),
                        CopyableEnvVarEntry("http_proxy", "http://duplicate-ignored:8080"),
                        CopyableEnvVarEntry("CODEX_HOME", "/Users/mia/.codex-work"),
                    ),
            )
        assertEquals(
            MergedCopiedEnvVars(
                next =
                    listOf(
                        CopyableEnvVarEntry("LOG_LEVEL", "debug"),
                        CopyableEnvVarEntry("HTTP_PROXY", "http://proxy:8080"),
                        CopyableEnvVarEntry("CODEX_HOME", "/Users/mia/.codex-work"),
                    ),
                addedCount = 2,
                keptKeys = listOf("LOG_LEVEL"),
            ),
            merged,
        )

        val repeat =
            mergeCopiedEnvVars(
                current = merged.next,
                source =
                    listOf(
                        CopyableEnvVarEntry("HTTP_PROXY", "http://other:9090"),
                        CopyableEnvVarEntry("CODEX_HOME", "/other"),
                    ),
            )
        assertEquals(
            MergedCopiedEnvVars(
                next = merged.next,
                addedCount = 0,
                keptKeys = listOf("HTTP_PROXY", "CODEX_HOME"),
            ),
            repeat,
        )
    }

    @Test
    fun agentsApiParsesRuntimeConfigEnvVarsInInsertionOrder() =
        runPortableCoroutineTest {
            val api =
                object : RaftAgentsApi {
                    override suspend fun agents(): HomeNetworkResponse =
                        HomeNetworkResponse(
                            code = 200,
                            bodyText =
                                """
                                [
                                  {
                                    "id": "atlas",
                                    "name": "atlas",
                                    "displayName": "Atlas",
                                    "status": "active",
                                    "runtime": "codex",
                                    "machineId": "machine-1",
                                    "creatorType": "user",
                                    "creatorId": "user-1",
                                    "runtimeConfig": {
                                      "version": 1,
                                      "runtime": "codex",
                                      "model": { "kind": "preset", "id": "gpt-5.6-sol" },
                                      "mode": { "kind": "default" },
                                      "envVars": {
                                        "HTTP_PROXY": "http://proxy:8080",
                                        "CODEX_HOME": "/Users/mia/.codex"
                                      }
                                    }
                                  }
                                ]
                                """.trimIndent(),
                        )

                    override suspend fun agent(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun createAgent(body: String): HomeNetworkResponse = error("unused")
                    override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
                    override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
                    override suspend fun deleteAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
                    override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
                    override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse = error("unused")
                    override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse = error("unused")
                    override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse = error("unused")
                    override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
                    override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
                    override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
                }
            val result = RaftAgentsApiClient(api).agents(ServerId("server-1"))
            val parsed = assertIs<DataResult.Content<build.raft.app.data.AgentsPage>>(result).data.agents.single()
            assertEquals(RUNTIME_CONFIG_VERSION, parsed.runtimeConfigVersion)
            assertEquals(listOf("HTTP_PROXY", "CODEX_HOME"), parsed.runtimeConfigEnvVars?.keys?.toList())
            val candidates =
                copyableSameComputerEnvVarAgents(
                    agents = listOf(parsed),
                    machineId = "machine-1",
                    targetRuntimeId = "claude",
                    currentUserId = "user-1",
                    canEditAgents = false,
                )
            assertEquals(listOf("HTTP_PROXY", "CODEX_HOME"), candidates.single().entries.map { it.key })
        }

    @Test
    fun filterCopyableAgentEnvVarsMatchesNameAndKeysCaseInsensitively() {
        val candidates =
            listOf(
                CopyableAgentEnvVars("a1", "Atlas", null, listOf(CopyableEnvVarEntry("HTTP_PROXY", "1"))),
                CopyableAgentEnvVars("a2", "Zephyr", null, listOf(CopyableEnvVarEntry("ZEPHYR_FLAG", "1"))),
            )
        assertEquals(listOf("a2"), filterCopyableAgentEnvVars(candidates, "zephyr_flag").map { it.agentId })
        assertEquals(listOf("a1"), filterCopyableAgentEnvVars(candidates, "atla").map { it.agentId })
        assertTrue(filterCopyableAgentEnvVars(candidates, "missing").isEmpty())
    }
}
