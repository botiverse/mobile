package ai.slock.compose.data

import ai.slock.compose.model.MemberId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val agentsApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Typed Agents boundary.
 *
 * Server contracts:
 *  - `packages/server/src/services/agentService.ts` (Agent record shape, with
 *    canonical `name` + nullable `displayName`).
 *  - `packages/server/src/routes/agents.ts` (per-endpoint payload shapes).
 *
 * Field names are exact, camelCase, matching the server payload. No snake_case
 * fallbacks, no speculative aliases.
 */
internal interface SlockAgentsApi {
    suspend fun agents(): HomeNetworkResponse
    suspend fun agent(agentId: MemberId): HomeNetworkResponse
    suspend fun createAgent(body: String): HomeNetworkResponse
    suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun startAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun deleteAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse
    suspend fun agentActivityLog(agentId: MemberId, limit: Int = 50): HomeNetworkResponse
    suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String? = null): HomeNetworkResponse
    suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse
    suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse
    suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse
    suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse
}

internal class SlockAgentsApiClient(
    private val transport: SlockAgentsApi
) {
    suspend fun agents(): DataResult<AgentsPage> =
        transport.agents().mapAgentsBody(
            "agents_list_contract_mismatch",
            ListSerializer(AgentWireDto.serializer())
        ) { wire -> AgentsPage(agentCount = wire.size) }

    suspend fun agent(agentId: MemberId): DataResult<AgentSummary> =
        transport.agent(agentId).mapAgentsBody(
            "agent_detail_contract_mismatch",
            AgentWireDto.serializer()
        ) { it.toSummary() }

    suspend fun createAgent(body: String): DataResult<AgentSummary> =
        transport.createAgent(body).mapAgentsBody(
            "agent_create_contract_mismatch",
            AgentWireDto.serializer()
        ) { it.toSummary() }

    suspend fun updateAgent(agentId: MemberId, body: String): DataResult<AgentSummary> =
        transport.updateAgent(agentId, body).mapAgentsBody(
            "agent_update_contract_mismatch",
            AgentWireDto.serializer()
        ) { it.toSummary() }

    suspend fun startAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.startAgent(agentId).mapAgentsCommand("agent_start_contract_mismatch")

    suspend fun stopAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.stopAgent(agentId).mapAgentsCommand("agent_stop_contract_mismatch")

    suspend fun resetAgent(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.resetAgent(agentId, body).mapAgentsCommand("agent_reset_contract_mismatch")

    suspend fun deleteAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.deleteAgent(agentId).mapAgentsCommand("agent_delete_contract_mismatch")

    suspend fun assignMachine(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.assignMachine(agentId, body).mapAgentsCommand("agent_assign_machine_contract_mismatch")

    suspend fun agentDirectMessages(agentId: MemberId): DataResult<AgentDmPage> =
        transport.agentDirectMessages(agentId).mapAgentsBody(
            "agent_dms_contract_mismatch",
            ListSerializer(AgentDmWireDto.serializer())
        ) { wire -> AgentDmPage(itemCount = wire.size) }

    suspend fun agentActivityLog(agentId: MemberId, limit: Int = 50): DataResult<AgentActivityPage> =
        transport.agentActivityLog(agentId, limit).mapAgentsBody(
            "agent_activity_log_contract_mismatch",
            ListSerializer(AgentActivityWireDto.serializer())
        ) { wire -> AgentActivityPage(entryCount = wire.size) }

    suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String? = null): DataResult<AgentWorkspaceFilePage> =
        transport.agentWorkspaceFiles(agentId, dirPath).mapAgentsBody(
            "agent_workspace_files_contract_mismatch",
            AgentWorkspaceFilesResponseDto.serializer()
        ) { AgentWorkspaceFilePage(fileCount = it.files.size) }

    suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): DataResult<AgentWorkspaceFileRead> =
        transport.readAgentWorkspaceFile(agentId, path).mapAgentsBody(
            "agent_workspace_file_read_contract_mismatch",
            AgentWorkspaceFileReadDto.serializer()
        ) { wire ->
            val resolvedPath = wire.path.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            AgentWorkspaceFileRead(
                path = resolvedPath,
                content = wire.content ?: "",
                binary = wire.binary ?: false,
                size = wire.size,
            )
        }

    suspend fun agentSkills(agentId: MemberId): DataResult<AgentSkillsSummary> =
        transport.agentSkills(agentId).mapAgentsBody(
            "agent_skills_contract_mismatch",
            AgentSkillsResponseDto.serializer()
        ) { AgentSkillsSummary(globalCount = it.global.size, workspaceCount = it.workspace.size) }

    suspend fun agentScopes(agentId: MemberId): DataResult<AgentScopesSummary> =
        transport.agentScopes(agentId).mapAgentsBody(
            "agent_scopes_contract_mismatch",
            AgentScopesResponseDto.serializer()
        ) { wire ->
            val id = wire.agentId?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            AgentScopesSummary(agentId = id, grantedCount = wire.granted.size, revision = wire.revision)
        }

    suspend fun updateAgentScopes(agentId: MemberId, body: String): DataResult<AgentScopesSummary> =
        transport.updateAgentScopes(agentId, body).mapAgentsBody(
            "agent_scopes_update_contract_mismatch",
            AgentScopesResponseDto.serializer()
        ) { wire ->
            val id = wire.agentId?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            AgentScopesSummary(agentId = id, grantedCount = wire.granted.size, revision = wire.revision)
        }

    suspend fun issueBootstrapToken(agentId: MemberId, body: String): DataResult<AgentBootstrapTokenSummary> =
        transport.issueBootstrapToken(agentId, body).mapAgentsBody(
            "agent_bootstrap_token_contract_mismatch",
            AgentBootstrapTokenResponseDto.serializer()
        ) { wire ->
            val tokenId = wire.tokenId?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            val prefix = wire.tokenPrefix?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            AgentBootstrapTokenSummary(
                tokenId = tokenId,
                tokenPrefix = prefix,
                bootstrapTokenPresent = !wire.bootstrapToken.isNullOrBlank(),
                scopeCount = wire.scopes.size,
            )
        }
}

internal data class AgentsPage(val agentCount: Int)
internal data class AgentSummary(
    val id: String,
    val name: String,
    val displayName: String?,
    val status: String?,
    val model: String?,
    val runtime: String?,
    val machineId: String?,
    val activity: String?,
    val activityDetail: String?,
    val deletedAt: String?
)
internal data class AgentCommandResult(val ok: Boolean)
internal data class AgentDmPage(val itemCount: Int)
internal data class AgentActivityPage(val entryCount: Int)
internal data class AgentWorkspaceFilePage(val fileCount: Int)
internal data class AgentWorkspaceFileRead(val path: String, val content: String, val binary: Boolean, val size: Long?)
internal data class AgentSkillsSummary(val globalCount: Int, val workspaceCount: Int)
internal data class AgentScopesSummary(val agentId: String, val grantedCount: Int, val revision: Long?)
internal data class AgentBootstrapTokenSummary(
    val tokenId: String,
    val tokenPrefix: String,
    val bootstrapTokenPresent: Boolean,
    val scopeCount: Int
)

private inline fun <Wire, T> HomeNetworkResponse.mapAgentsBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val wire = try {
        agentsApiJson.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    val value = transform(wire)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun HomeNetworkResponse.mapAgentsCommand(contractCode: String): DataResult<AgentCommandResult> =
    mapAgentsBody(contractCode, AgentCommandResponseDto.serializer()) { wire ->
        if (!wire.ok) null else AgentCommandResult(ok = true)
    }

// ── Wire DTOs ──
// Source of truth: packages/server/src/services/agentService.ts and the
// routes/agents.ts handlers. `name` is required; `displayName` may be null but
// is not used as a fallback for `name` — the server always emits `name`.

@Serializable
private data class AgentWireDto(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val status: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val machineId: String? = null,
    val activity: String? = null,
    val activityDetail: String? = null,
    val deletedAt: String? = null,
)

private fun AgentWireDto.toSummary(): AgentSummary? {
    if (id.isBlank() || name.isBlank()) return null
    return AgentSummary(
        id = id,
        name = name,
        displayName = displayName,
        status = status,
        model = model,
        runtime = runtime,
        machineId = machineId,
        activity = activity,
        activityDetail = activityDetail,
        deletedAt = deletedAt
    )
}

@Serializable
private data class AgentCommandResponseDto(val ok: Boolean)

@Serializable
private data class AgentDmWireDto(val id: String)

@Serializable
private data class AgentActivityWireDto(val id: String)

@Serializable
private data class AgentWorkspaceFilesResponseDto(
    val files: List<AgentWorkspaceFileEntryDto>,
)

@Serializable
private data class AgentWorkspaceFileEntryDto(val path: String)

@Serializable
private data class AgentWorkspaceFileReadDto(
    val path: String,
    val content: String? = null,
    val binary: Boolean? = null,
    val size: Long? = null,
)

@Serializable
private data class AgentSkillsResponseDto(
    val global: List<AgentSkillEntryDto>,
    val workspace: List<AgentSkillEntryDto>,
)

@Serializable
private data class AgentSkillEntryDto(val name: String? = null)

@Serializable
private data class AgentScopesResponseDto(
    val agentId: String? = null,
    val granted: List<String>,
    val revision: Long? = null,
)

@Serializable
private data class AgentBootstrapTokenResponseDto(
    val tokenId: String? = null,
    val tokenPrefix: String? = null,
    val bootstrapToken: String? = null,
    val scopes: List<String> = emptyList(),
)

// ── Request bodies ──

@Serializable
internal data class CreateAgentBody(
    val name: String,
    val description: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val reasoningEffort: String? = null,
    val machineId: String? = null,
    val envVars: Map<String, String>? = null,
    val avatarUrl: String? = null,
    val onboarding: Boolean? = null
)

@Serializable
internal data class UpdateAgentBody(
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val reasoningEffort: String? = null,
    val envVars: Map<String, String>? = null,
    val restartMode: String? = null
)

@Serializable
internal data class ResetAgentBody(val mode: String)

@Serializable
internal data class AssignAgentMachineBody(val machineId: String?)

@Serializable
internal data class UpdateAgentScopesBody(val scopes: List<String>? = null, val mode: String? = null)

@Serializable
internal data class IssueAgentBootstrapTokenBody(val scopes: List<String>? = null, val ttlMs: Long? = null)

internal fun buildCreateAgentBody(body: CreateAgentBody): String = agentsApiJson.encodeToString(body)
internal fun buildUpdateAgentBody(body: UpdateAgentBody): String = agentsApiJson.encodeToString(body)
internal fun buildResetAgentBody(mode: String): String = agentsApiJson.encodeToString(ResetAgentBody(mode))
internal fun buildAssignAgentMachineBody(machineId: String?): String =
    agentsApiJson.encodeToString(AssignAgentMachineBody(machineId = machineId))
internal fun buildUpdateAgentScopesBody(body: UpdateAgentScopesBody): String = agentsApiJson.encodeToString(body)
internal fun buildIssueAgentBootstrapTokenBody(body: IssueAgentBootstrapTokenBody): String =
    agentsApiJson.encodeToString(body)
