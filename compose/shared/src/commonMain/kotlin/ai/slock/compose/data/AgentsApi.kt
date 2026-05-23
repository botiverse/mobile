package ai.slock.compose.data

import ai.slock.compose.model.MemberId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val agentsApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Typed Agents boundary.
 *
 * Keep shape notes beside parsers: list-like endpoints that are arrays are
 * parsed as root arrays, workspace files require a `files` array, workspace
 * file reads require `path` and expose optional `binary`/`size`, and bootstrap
 * token responses require a non-empty token field.
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
        transport.agents().mapAgentsArray("agents_list_contract_mismatch") { agents ->
            AgentsPage(agentCount = agents.size)
        }

    suspend fun agent(agentId: MemberId): DataResult<AgentSummary> =
        transport.agent(agentId).toAgentSummary("agent_detail_contract_mismatch")

    suspend fun createAgent(body: String): DataResult<AgentSummary> =
        transport.createAgent(body).toAgentSummary("agent_create_contract_mismatch")

    suspend fun updateAgent(agentId: MemberId, body: String): DataResult<AgentSummary> =
        transport.updateAgent(agentId, body).toAgentSummary("agent_update_contract_mismatch")

    suspend fun startAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.startAgent(agentId).toAgentCommand("agent_start_contract_mismatch")

    suspend fun stopAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.stopAgent(agentId).toAgentCommand("agent_stop_contract_mismatch")

    suspend fun resetAgent(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.resetAgent(agentId, body).toAgentCommand("agent_reset_contract_mismatch")

    suspend fun deleteAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.deleteAgent(agentId).toAgentCommand("agent_delete_contract_mismatch")

    suspend fun assignMachine(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.assignMachine(agentId, body).toAgentCommand("agent_assign_machine_contract_mismatch")

    suspend fun agentDirectMessages(agentId: MemberId): DataResult<AgentDmPage> =
        transport.agentDirectMessages(agentId).mapAgentsArray("agent_dms_contract_mismatch") { dms ->
            AgentDmPage(itemCount = dms.size)
        }

    suspend fun agentActivityLog(agentId: MemberId, limit: Int = 50): DataResult<AgentActivityPage> =
        transport.agentActivityLog(agentId, limit).mapAgentsArray("agent_activity_log_contract_mismatch") { entries ->
            AgentActivityPage(entryCount = entries.size)
        }

    suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String? = null): DataResult<AgentWorkspaceFilePage> =
        transport.agentWorkspaceFiles(agentId, dirPath).mapAgentsBody("agent_workspace_files_contract_mismatch") { root ->
            AgentWorkspaceFilePage(fileCount = (root["files"] as? JsonArray)?.size ?: return@mapAgentsBody null)
        }

    suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): DataResult<AgentWorkspaceFileRead> =
        transport.readAgentWorkspaceFile(agentId, path).mapAgentsBody("agent_workspace_file_read_contract_mismatch") { root ->
            AgentWorkspaceFileRead(
                path = root.string("path")?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null,
                content = root.string("content") ?: "",
                binary = root.boolean("binary") ?: false,
                size = root.long("size")
            )
        }

    suspend fun agentSkills(agentId: MemberId): DataResult<AgentSkillsSummary> =
        transport.agentSkills(agentId).mapAgentsBody("agent_skills_contract_mismatch") { root ->
            AgentSkillsSummary(
                globalCount = (root["global"] as? JsonArray)?.size ?: return@mapAgentsBody null,
                workspaceCount = (root["workspace"] as? JsonArray)?.size ?: return@mapAgentsBody null
            )
        }

    suspend fun agentScopes(agentId: MemberId): DataResult<AgentScopesSummary> =
        transport.agentScopes(agentId).toAgentScopes("agent_scopes_contract_mismatch")

    suspend fun updateAgentScopes(agentId: MemberId, body: String): DataResult<AgentScopesSummary> =
        transport.updateAgentScopes(agentId, body).toAgentScopes("agent_scopes_update_contract_mismatch")

    suspend fun issueBootstrapToken(agentId: MemberId, body: String): DataResult<AgentBootstrapTokenSummary> =
        transport.issueBootstrapToken(agentId, body).mapAgentsBody("agent_bootstrap_token_contract_mismatch") { root ->
            AgentBootstrapTokenSummary(
                tokenId = root.string("tokenId")?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null,
                tokenPrefix = root.string("tokenPrefix")?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null,
                bootstrapTokenPresent = !root.string("bootstrapToken").isNullOrBlank(),
                scopeCount = (root["scopes"] as? JsonArray)?.size ?: return@mapAgentsBody null
            )
        }

    private fun HomeNetworkResponse.toAgentSummary(contractCode: String): DataResult<AgentSummary> =
        mapAgentsBody(contractCode) { root ->
            AgentSummary(
                id = root.string("id")?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null,
                name = root.string("name") ?: root.string("displayName") ?: return@mapAgentsBody null,
                status = root.string("status")
            )
        }

    private fun HomeNetworkResponse.toAgentCommand(contractCode: String): DataResult<AgentCommandResult> =
        mapAgentsBody(contractCode) { root ->
            AgentCommandResult(ok = root.boolean("ok")?.takeIf { it } ?: return@mapAgentsBody null)
        }

    private fun HomeNetworkResponse.toAgentScopes(contractCode: String): DataResult<AgentScopesSummary> =
        mapAgentsBody(contractCode) { root ->
            AgentScopesSummary(
                agentId = root.string("agentId")?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null,
                grantedCount = (root["granted"] as? JsonArray)?.size ?: return@mapAgentsBody null,
                revision = root.long("revision")
            )
        }
}

internal data class AgentsPage(val agentCount: Int)
internal data class AgentSummary(val id: String, val name: String, val status: String?)
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

private inline fun <T> HomeNetworkResponse.mapAgentsBody(
    contractCode: String,
    mapper: (JsonObject) -> T?
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val root = runCatching { agentsApiJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = mapper(root)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private inline fun <T> HomeNetworkResponse.mapAgentsArray(
    contractCode: String,
    mapper: (JsonArray) -> T?
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val root = runCatching { agentsApiJson.parseToJsonElement(body) as? JsonArray }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = mapper(root)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun JsonObject.boolean(key: String): Boolean? =
    when (val primitive = this[key] as? JsonPrimitive) {
        null -> null
        else -> primitive.contentOrNull?.trim()?.lowercase()?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

@kotlinx.serialization.Serializable
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

@kotlinx.serialization.Serializable
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

@kotlinx.serialization.Serializable
internal data class ResetAgentBody(val mode: String)

@kotlinx.serialization.Serializable
internal data class AssignAgentMachineBody(val machineId: String?)

@kotlinx.serialization.Serializable
internal data class UpdateAgentScopesBody(val scopes: List<String>? = null, val mode: String? = null)

@kotlinx.serialization.Serializable
internal data class IssueAgentBootstrapTokenBody(val scopes: List<String>? = null, val ttlMs: Long? = null)

internal fun buildCreateAgentBody(body: CreateAgentBody): String = agentsApiJson.encodeToString(body)
internal fun buildUpdateAgentBody(body: UpdateAgentBody): String = agentsApiJson.encodeToString(body)
internal fun buildResetAgentBody(mode: String): String = agentsApiJson.encodeToString(ResetAgentBody(mode))
internal fun buildAssignAgentMachineBody(machineId: String?): String =
    agentsApiJson.encodeToString(AssignAgentMachineBody(machineId = machineId))
internal fun buildUpdateAgentScopesBody(body: UpdateAgentScopesBody): String = agentsApiJson.encodeToString(body)
internal fun buildIssueAgentBootstrapTokenBody(body: IssueAgentBootstrapTokenBody): String = agentsApiJson.encodeToString(body)
