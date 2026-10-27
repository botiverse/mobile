package build.raft.app.data

import build.raft.app.model.isExternalAgent
import build.raft.app.model.AgentToolStuck
import build.raft.app.model.AgentWakeCrashLoop
import build.raft.app.model.MemberId
import build.raft.app.data.realtime.ToolStuckDto
import build.raft.app.data.realtime.WakeCrashLoopDto
import build.raft.app.data.realtime.toAgentToolStuck
import build.raft.app.data.realtime.toAgentWakeCrashLoop
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.runtimeform.v2.runtimeFormV2ServerError
import build.raft.app.data.runtimeform.v2.withRuntimeFormV2
import build.raft.app.model.RuntimeProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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
/**
 * Workspace disposition when archiving an agent (web `AgentArchiveWorkspaceChoice`
 * in `packages/shared/src/agentArchive.ts` — "keep" | "delete"). `Keep` is the
 * server default and matches what mobile delete always did.
 */
enum class AgentArchiveWorkspaceChoice(val wireValue: String) {
    Keep("keep"),
    Delete("delete");

    /**
     * `DELETE /agents/:id` request body, e.g. `{"workspace":"delete"}`. Web sends
     * the field for every archive (default "keep"), so serializing always is the
     * contract, not an optimization.
     */
    fun toRequestBody(): String = "{\"workspace\":\"$wireValue\"}"
}

// task #517 — post-archive state, mirroring `AgentArchiveView` in
// `packages/shared/src/agentArchive.ts`. Field names are the wire names;
// unknown `workspaceState` values collapse to `Unknown` rather than failing.

/** Where the archived agent's workspace still exists (`workspaceState`). */
internal enum class AgentArchiveWorkspaceState(val wireValue: String) {
    OnComputer("on_computer"),
    Deleted("deleted"),
    Unknown("unknown");

    companion object {
        fun fromWire(raw: String?): AgentArchiveWorkspaceState =
            entries.firstOrNull { it.wireValue == raw } ?: Unknown
    }
}

/** Workspace operation queued/running on the Computer (`pendingOp`). */
internal enum class AgentArchivePendingOp {
    Delete;

    companion object {
        // Any non-null wire value is a pending workspace op; today only
        // "delete" exists, so unrecognized values still read as pending.
        fun fromWire(raw: String?): AgentArchivePendingOp? = raw?.let { Delete }
    }
}

internal data class RaftAgentArchive(
    val agentId: String,
    val archivedAt: String?,
    val archivedByUserId: String?,
    val machineId: String?,
    val machineName: String?,
    val workspaceState: AgentArchiveWorkspaceState,
    val pendingOp: AgentArchivePendingOp?,
    val waitingForComputer: Boolean,
    val lastErrorCode: String?,
    val lastErrorAt: String?
)

@Serializable
internal data class AgentArchiveEnvelopeDto(
    val archive: AgentArchiveWireDto? = null
)

@Serializable
internal data class AgentArchiveWireDto(
    val agentId: String? = null,
    val archivedAt: String? = null,
    val archivedByUserId: String? = null,
    val machineId: String? = null,
    val machineName: String? = null,
    val workspaceState: String? = null,
    val pendingOp: String? = null,
    val waitingForComputer: Boolean = false,
    val lastErrorCode: String? = null,
    val lastErrorAt: String? = null
) {
    fun toAgentArchive(): RaftAgentArchive? {
        val id = agentId?.takeIf { it.isNotBlank() } ?: return null
        return RaftAgentArchive(
            agentId = id,
            archivedAt = archivedAt?.takeIf { it.isNotBlank() },
            archivedByUserId = archivedByUserId?.takeIf { it.isNotBlank() },
            machineId = machineId?.takeIf { it.isNotBlank() },
            machineName = machineName?.takeIf { it.isNotBlank() },
            workspaceState = AgentArchiveWorkspaceState.fromWire(workspaceState),
            pendingOp = AgentArchivePendingOp.fromWire(pendingOp),
            waitingForComputer = waitingForComputer,
            lastErrorCode = lastErrorCode?.takeIf { it.isNotBlank() },
            lastErrorAt = lastErrorAt?.takeIf { it.isNotBlank() }
        )
    }
}

@Serializable
internal data class AgentArchiveWorkspaceActionDto(
    val action: String = "delete"
)

internal interface RaftAgentsApi {
    suspend fun agents(): HomeNetworkResponse
    suspend fun agent(agentId: MemberId): HomeNetworkResponse
    suspend fun createAgent(body: String): HomeNetworkResponse
    suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun startAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse
    /**
     * `DELETE /agents/:id` — the server's "archive" endpoint (the former delete).
     * Body `{ workspace: "keep" | "delete" }` chooses what happens to the agent's
     * workspace on its Computer; web sends the field unconditionally
     * (`store/agentStore.ts` `deleteAgent`), so the client does the same.
     */
    suspend fun deleteAgent(agentId: MemberId, body: String): HomeNetworkResponse
    /**
     * task #517 (web `archiveApi.ts` parity): `GET /agents/:id/archive` —
     * returns `{ archive: AgentArchiveView | null }`. Defaulted like
     * `issueAgentCredential` so fakes that don't exercise archiving don't
     * need a stub.
     */
    suspend fun agentArchive(agentId: MemberId): HomeNetworkResponse = error("unused")
    /**
     * task #517: `POST /agents/:id/archive/workspace` body
     * `{"action":"delete"}` — queues the post-archive workspace delete and
     * returns the updated archive view.
     */
    suspend fun deleteAgentArchiveWorkspace(agentId: MemberId, body: String): HomeNetworkResponse =
        error("unused")
    suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse
    suspend fun agentActivityLog(agentId: MemberId, limit: Int = 50): HomeNetworkResponse
    suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String? = null): HomeNetworkResponse
    suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse
    suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse
    /** `GET /agents/:id/external-status` — external agents only (400 otherwise), permission-gated (403). */
    suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse
    // #89 React parity: agent permissions (scope grants) per React
    // `AgentScopesPanel.tsx`. Mirrors `GET / PUT /api/agents/:id/scopes`.
    // `updateAgentScopes` body is `{ scopes: AgentGrantableScope[] }`;
    // `resetAgentScopes` sends `{ mode: "default" }` and returns the
    // server's updated `AgentScopeSet` (granted list empty + mode "default").
    suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse
    suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse
    suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse
    /** External-agent login credential mint; unlike bootstrap tokens this is the user-facing login surface. */
    suspend fun issueAgentCredential(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    /** Runtime form protocol v2 for editing: `GET /agents/:id/runtime-form`, the create form plus `values`. */
    suspend fun agentRuntimeFormV2(agentId: MemberId): HomeNetworkResponse =
        error("agentRuntimeFormV2 is not implemented")
}

internal class RaftAgentsNetworkApi(
    private val networkClient: RaftNetworkClient,
    private val runtimeFormLanguageTag: () -> String? = { runtimeFormRequestLanguageTag() }
) : RaftAgentsApi {
    override suspend fun agents(): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents"
        )

    override suspend fun agent(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}"
        )

    override suspend fun createAgent(body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents",
            method = "POST",
            bodyText = body
        )

    override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}",
            method = "PATCH",
            bodyText = body
        )

    override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/start",
            method = "POST"
        )

    override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/stop",
            method = "POST"
        )

    override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/reset",
            method = "POST",
            bodyText = body
        )

    override suspend fun deleteAgent(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}",
            method = "DELETE",
            bodyText = body
        )

    override suspend fun agentArchive(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/archive"
        )

    override suspend fun deleteAgentArchiveWorkspace(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/archive/workspace",
            method = "POST",
            bodyText = body
        )

    override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/assign-machine",
            method = "POST",
            bodyText = body
        )

    override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/agent-dms"
        )

    override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/activity-log",
            query = listOf("limit" to limit.toString())
        )

    override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/workspace-files",
            query = listOfNotNull(dirPath?.let { "dirPath" to it })
        )

    override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/workspace-files/read",
            query = listOf("path" to path)
        )

    override suspend fun agentRuntimeFormV2(agentId: MemberId): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/runtime-form",
            headers = runtimeFormLanguageHeaders(runtimeFormLanguageTag())
        )

    override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/skills"
        )

    override suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/external-status"
        )

    // #89 React parity: agent scope grants endpoints.
    override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/scopes"
        )

    override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/scopes",
            method = "PUT",
            bodyText = body
        )

    override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/scopes",
            method = "PUT",
            bodyText = """{"mode":"default"}"""
        )

    override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/bootstrap-tokens",
            method = "POST",
            bodyText = body
        )

    override suspend fun issueAgentCredential(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/credentials",
            method = "POST",
            bodyText = body
        )
}

internal class RaftAgentsApiClient(
    private val transport: RaftAgentsApi,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    suspend fun agents(): DataResult<AgentsPage> =
        transport.agents().mapAgentsBody(
            "agents_list_contract_mismatch",
            ListSerializer(AgentWireDto.serializer()),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> AgentsPage(agents = wire.mapNotNull { it.toSummary() }) }

    suspend fun agent(agentId: MemberId): DataResult<AgentSummary> =
        transport.agent(agentId).mapAgentsBody(
            "agent_detail_contract_mismatch",
            AgentWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { it.toSummary() }

    /** Public/profile-only projection; intentionally does not load private agent satellites. */
    suspend fun publicAgentDetail(agentId: MemberId): DataResult<AgentDetailSnapshot> =
        agent(agentId).map { summary ->
            AgentDetailSnapshot(
                agentId = agentId,
                summary = summary.toAgentDetailSummary(),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList()
            )
        }

    suspend fun createAgent(body: String): DataResult<AgentSummary> =
        transport.createAgent(body).mapAgentsBody(
            "agent_create_contract_mismatch",
            AgentWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { it.toSummary() }

    suspend fun updateAgent(agentId: MemberId, body: String): DataResult<AgentSummary> =
        transport.updateAgent(agentId, body).mapAgentsBody(
            "agent_update_contract_mismatch",
            AgentWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { it.toSummary() }

    suspend fun startAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.startAgent(agentId).mapAgentsCommand("agent_start_contract_mismatch", diagnosticsLogger)

    suspend fun stopAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.stopAgent(agentId).mapAgentsCommand("agent_stop_contract_mismatch", diagnosticsLogger)

    suspend fun resetAgent(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.resetAgent(agentId, body).mapAgentsCommand("agent_reset_contract_mismatch", diagnosticsLogger)

    suspend fun deleteAgent(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.deleteAgent(agentId, body).mapAgentsCommand("agent_delete_contract_mismatch", diagnosticsLogger)

    /**
     * task #517 (web `fetchAgentArchive` parity): `GET /agents/:id/archive`.
     * `Content(null)` = the agent has no archive record; non-archived agents
     * are expected to land here.
     */
    suspend fun agentArchive(agentId: MemberId): DataResult<RaftAgentArchive?> {
        val response = transport.agentArchive(agentId)
        if (!response.isSuccessful) return response.toAgentsDataError()
        val body = response.bodyText?.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError(code = "agent_archive_contract_mismatch", retryable = false))
        // `{archive:null}` is a valid Content — not a contract mismatch — so
        // this endpoint decodes by hand instead of through mapAgentsBody
        // (whose transform-null-equals-mismatch convention can't carry it).
        return try {
            val wire = agentsApiJson.decodeFromString(AgentArchiveEnvelopeDto.serializer(), body)
            val archive = wire.archive
            if (archive != null && archive.toAgentArchive() == null) {
                return DataResult.Error(ScreenError(code = "agent_archive_contract_mismatch", retryable = false))
            }
            DataResult.Content(archive?.toAgentArchive())
        } catch (_: SerializationException) {
            DataResult.Error(ScreenError(code = "agent_archive_contract_mismatch", retryable = false))
        }
    }

    /**
     * task #517 (web `requestAgentArchiveWorkspaceDelete` parity):
     * `POST /agents/:id/archive/workspace` `{"action":"delete"}` returns the
     * updated archive view (usually `pendingOp=delete` until the Computer
     * confirms). Refusals surface the server `code` (`archive_migration_active`
     * etc.) on the `ScreenError` so the UI can pick a localized reason instead
     * of raw server text.
     */
    suspend fun deleteAgentArchiveWorkspace(agentId: MemberId): DataResult<RaftAgentArchive> {
        val response = transport.deleteAgentArchiveWorkspace(
            agentId,
            agentsApiJson.encodeToString(AgentArchiveWorkspaceActionDto.serializer(), AgentArchiveWorkspaceActionDto())
        )
        val serverCode = response.serverErrorCodeOrNull()
        val mapped = response.mapAgentsBody<AgentArchiveEnvelopeDto, RaftAgentArchive>(
            "agent_archive_workspace_delete_contract_mismatch",
            AgentArchiveEnvelopeDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> wire.archive?.toAgentArchive() }
        return if (mapped is DataResult.Error && serverCode != null) {
            DataResult.Error(mapped.error.copy(code = serverCode))
        } else {
            mapped
        }
    }

    // #89 React parity: agent scope grants typed accessors. Mirror React
    // `AgentScopesPanel.tsx` load / save / reset flows.
    suspend fun agentScopes(agentId: MemberId): DataResult<AgentScopeSet> =
        transport.agentScopes(agentId).mapAgentsBody(
            "agent_scopes_contract_mismatch",
            AgentScopesWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> wire.toScopeSet(agentId) }

    suspend fun updateAgentScopes(agentId: MemberId, scopes: List<String>): DataResult<AgentScopeSet> {
        val body = agentsApiJson.encodeToString(AgentScopesUpdateDto.serializer(), AgentScopesUpdateDto(scopes = scopes))
        return transport.updateAgentScopes(agentId, body).mapAgentsBody(
            "agent_scopes_update_contract_mismatch",
            AgentScopesWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> wire.toScopeSet(agentId) }
    }

    suspend fun resetAgentScopes(agentId: MemberId): DataResult<AgentScopeSet> =
        transport.resetAgentScopes(agentId).mapAgentsBody(
            "agent_scopes_reset_contract_mismatch",
            AgentScopesWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> wire.toScopeSet(agentId) }

    suspend fun assignMachine(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.assignMachine(agentId, body).mapAgentsCommand("agent_assign_machine_contract_mismatch", diagnosticsLogger)

    suspend fun agentDirectMessages(agentId: MemberId): DataResult<AgentDmPage> =
        transport.agentDirectMessages(agentId).mapAgentsBody(
            "agent_dms_contract_mismatch",
            ListSerializer(AgentDmWireDto.serializer()),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> AgentDmPage(itemCount = wire.size) }

    suspend fun agentActivityLog(agentId: MemberId, limit: Int = 50): DataResult<AgentActivityPage> =
        transport.agentActivityLog(agentId, limit).mapAgentsBody(
            "agent_activity_log_contract_mismatch",
            ListSerializer(AgentActivityWireDto.serializer()),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> AgentActivityPage(entryCount = wire.size) }

    suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String? = null): DataResult<AgentWorkspaceFilePage> =
        transport.agentWorkspaceFiles(agentId, dirPath).mapAgentsBody(
            "agent_workspace_files_contract_mismatch",
            AgentWorkspaceFilesResponseDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { AgentWorkspaceFilePage(fileCount = it.files.size) }

    suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): DataResult<AgentWorkspaceFileRead> =
        transport.readAgentWorkspaceFile(agentId, path).mapAgentsBody(
            "agent_workspace_file_read_contract_mismatch",
            AgentWorkspaceFileReadDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
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
            AgentSkillsResponseDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { AgentSkillsSummary(globalCount = it.global.size, workspaceCount = it.workspace.size) }

    /**
     * React AgentDetailPanel parity (`fetchExternalAgentStatus`). Non-external
     * agents answer 400 and callers without `editAgents`/creator authority
     * answer 403; both surface as non-Content results, never as a status.
     */
    suspend fun agentExternalStatus(agentId: MemberId): DataResult<ExternalAgentStatusSnapshot> =
        transport.agentExternalStatus(agentId).mapAgentsBody(
            "agent_external_status_contract_mismatch",
            ExternalAgentStatusWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire ->
            ExternalAgentStatusSnapshot(
                setupState = ExternalAgentSetupState.fromWireValue(wire.setupState),
                credentialLastUsedAt = wire.credentialLastUsedAt?.takeIf { it.isNotBlank() },
                lastActivityAt = wire.lastActivityAt?.takeIf { it.isNotBlank() }
            )
        }

    suspend fun issueAgentLoginToken(agentId: MemberId, body: String = "{}"): DataResult<AgentLoginTokenSummary> =
        transport.issueAgentCredential(agentId, body).mapAgentsBody(
            "agent_login_token_contract_mismatch",
            AgentLoginTokenResponseDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire ->
            val credentialId = wire.credentialId?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            val apiKey = wire.apiKey?.takeIf { it.isNotBlank() && it.startsWith("sk_agent_") }
                ?: return@mapAgentsBody null
            AgentLoginTokenSummary(
                credentialId = credentialId,
                apiKey = apiKey,
                scopeCount = wire.scopes.size,
            )
        }

    suspend fun issueBootstrapToken(agentId: MemberId, body: String): DataResult<AgentBootstrapTokenSummary> =
        transport.issueBootstrapToken(agentId, body).mapAgentsBody(
            "agent_bootstrap_token_contract_mismatch",
            AgentBootstrapTokenResponseDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire ->
            val tokenId = wire.tokenId?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            val prefix = wire.tokenPrefix?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            AgentBootstrapTokenSummary(
                tokenId = tokenId,
                tokenPrefix = prefix,
                bootstrapToken = wire.bootstrapToken?.takeIf { it.isNotBlank() },
                bootstrapTokenPresent = !wire.bootstrapToken.isNullOrBlank(),
                scopeCount = wire.scopes.size,
            )
        }
}

internal data class AgentsPage(val agents: List<AgentSummary>) {
    val agentCount: Int
        get() = agents.size
}
internal data class AgentSummary(
    val id: String,
    val name: String,
    val displayName: String?,
    val description: String?,
    val serverRole: String?,
    val status: String?,
    val model: String?,
    val runtimeModelKind: String?,
    val runtimeProvider: RuntimeProviderConfig?,
    val runtime: String?,
    val reasoning: String?,
    val executionMode: String?,
    val machineId: String?,
    val machineName: String?,
    val daemonVersion: String?,
    val avatarUrl: String?,
    val creatorId: String?,
    val creatorType: String?,
    val creator: AgentCreatorSummary?,
    val createdAt: String?,
    val envVarNames: List<String>,
    val envVars: Map<String, String>,
    val createdAgents: List<AgentCreatedSummary>,
    val activity: String?,
    val activityDetail: String?,
    val deletedAt: String?,
    /** Web parity: `agent.external === true || runtime === "external"`. */
    val isExternal: Boolean = false,
    /** External agents only: `lastSeenAt` from `GET /agents*` (`agent:seen` updates it in the store). */
    val lastSeenAt: String? = null,
    /** The typed kind behind [activityDetail] (`activityDetailKind`); null from older servers. */
    val activityDetailKind: String? = null,
    /** Why automatic wakes are paused, with [activityDetailKind] `wake_crash_loop_blocked`. */
    val wakeCrashLoop: AgentWakeCrashLoop? = null,
    /** The agent is stuck on one tool call (`toolStuck`, served with any activity); null when not. */
    val toolStuck: AgentToolStuck? = null
)

internal fun AgentSummary.toAgentDetailSummary(): AgentDetailSummary =
    AgentDetailSummary(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        serverRole = serverRole,
        status = status,
        runtime = runtime,
        isExternal = isExternal,
        model = model,
        runtimeModelKind = runtimeModelKind,
        runtimeProvider = runtimeProvider,
        reasoning = reasoning,
        executionMode = executionMode,
        machineId = machineId,
        machineName = machineName,
        daemonVersion = daemonVersion,
        avatarUrl = avatarUrl,
        creatorId = creatorId,
        creatorType = creatorType,
        creator = creator,
        createdAt = createdAt,
        envVarNames = envVarNames,
        envVars = envVars,
        createdAgents = createdAgents,
        activity = activity,
        activityDetail = activityDetail,
        deletedAt = deletedAt,
        lastSeenAt = lastSeenAt,
        activityDetailKind = activityDetailKind,
        wakeCrashLoop = wakeCrashLoop,
        toolStuck = toolStuck
    )
internal data class AgentCommandResult(val ok: Boolean)
internal data class AgentDmPage(val itemCount: Int)
internal data class AgentActivityPage(val entryCount: Int)
internal data class AgentWorkspaceFilePage(val fileCount: Int)
internal data class AgentWorkspaceFileRead(val path: String, val content: String, val binary: Boolean, val size: Long?)
internal data class AgentSkillsSummary(val globalCount: Int, val workspaceCount: Int)
internal data class AgentLoginTokenSummary(
    val credentialId: String,
    /** Raw login token is returned once and must remain in UI-local state. */
    val apiKey: String,
    val scopeCount: Int
)

internal data class AgentBootstrapTokenSummary(
    val tokenId: String,
    val tokenPrefix: String,
    /** Raw token is returned once by the server and must remain in UI-local state. */
    val bootstrapToken: String? = null,
    val bootstrapTokenPresent: Boolean,
    val scopeCount: Int
)

private suspend inline fun <Wire, T> HomeNetworkResponse.mapAgentsBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    diagnosticsLogger: (String) -> Unit = {},
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return toAgentsDataError()
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = try {
        parseResponseBodyWithTrace(
            source = contractCode.removeSuffix("_contract_mismatch"),
            markerPrefix = "agents",
            body = body,
            code = code,
            hasBody = true,
            diagnosticsLogger = diagnosticsLogger,
            parser = {
                val wire = agentsApiJson.decodeFromString(serializer, body)
                transform(wire) ?: throw AgentsContractMismatchException()
            }
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    } catch (_: AgentsContractMismatchException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    return DataResult.Content(value)
}

private class AgentsContractMismatchException : IllegalStateException()

private fun <T> HomeNetworkResponse.toAgentsDataError(): DataResult<T> {
    val serverMessage = serverErrorTextOrNull()
    return when (code) {
        401 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "auth_required",
                    requiredCapability = "auth_session",
                    serverMessage = serverMessage
                )
            )
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    requiredCapability = "server_access",
                    serverMessage = serverMessage
                )
            )
        in 500..599 ->
            DataResult.Error(
                ScreenError(code = "server_unavailable", retryable = true, serverMessage = serverMessage)
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = runtimeFormIssueCodeOrNull() ?: agentNameConflictCodeOrNull() ?: "http_$code",
                    retryable = code != 404,
                    serverMessage = serverMessage,
                    formFieldKeys = runtimeFormV2ServerError(bodyText ?: errorBodyText)?.fieldKeys.orEmpty()
                )
            )
    }
}

private fun HomeNetworkResponse.runtimeFormIssueCodeOrNull(): String? {
    if (code != 409) return null
    // task #186: the production transport (KtorRaftHttpExecutor) delivers
    // every non-2xx body in errorBodyText and leaves bodyText null, so the 409
    // issues envelope must be read from both fields or the runtime-form
    // recovery never triggers on device.
    val body = (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        agentsApiJson.decodeFromString(AgentErrorEnvelopeDto.serializer(), body)
            .issues
            .firstOrNull { it.code in CREATE_AGENT_RUNTIME_FORM_REJECTION_CODES }
            ?.code
    }.getOrNull()
}

// task #193: the raw server `error` text travels with every mapped agents
// error so displays can fall back to the real reason (honest-states
// contract) instead of a bare http code.
private fun HomeNetworkResponse.serverErrorTextOrNull(): String? {
    val body = (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        agentsApiJson.decodeFromString(AgentErrorTextEnvelopeDto.serializer(), body).error
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** task #517: the top-level machine `code` some refused agent requests carry
 * (`archive_migration_active`, …) — the web reads it off `response.data.code`. */
private fun HomeNetworkResponse.serverErrorCodeOrNull(): String? {
    if (isSuccessful) return null
    val body = (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        agentsApiJson.decodeFromString(AgentErrorTextEnvelopeDto.serializer(), body).code
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun HomeNetworkResponse.agentNameConflictCodeOrNull(): String? {
    if (code != 409) return null
    val body = (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() } ?: return null
    // task #191: principal-handle conflicts carry only a human-readable
    // `error` string, no issues codes ("Agent name X is already taken" /
    // "Cindy agent already exists in this server"). Match the text until the
    // server exposes a canonical machine code; these conflicts stay outside
    // the runtime-form recovery allowlist so they are never auto-retried.
    val errorText =
        runCatching {
            agentsApiJson.decodeFromString(AgentErrorTextEnvelopeDto.serializer(), body).error
        }.getOrNull() ?: return null
    val normalized = errorText.lowercase()
    return AGENT_CREATE_NAME_TAKEN.takeIf {
        "already taken" in normalized || "already exists" in normalized
    }
}

private suspend fun HomeNetworkResponse.mapAgentsCommand(
    contractCode: String,
    diagnosticsLogger: (String) -> Unit = {}
): DataResult<AgentCommandResult> =
    mapAgentsBody(contractCode, AgentCommandResponseDto.serializer(), diagnosticsLogger) { wire ->
        if (!wire.ok) null else AgentCommandResult(ok = true)
    }

// ── Wire DTOs ──
// Source of truth: packages/server/src/services/agentService.ts and the
// routes/agents.ts handlers. `name` is required; `displayName` may be null but
// is not used as a fallback for `name` — the server always emits `name`.

@Serializable
private data class AgentErrorTextEnvelopeDto(
    val error: String? = null,
    // task #517: machine-readable refusal code (e.g. `archive_migration_active`)
    // rides next to the human text on the agents error envelope.
    val code: String? = null
)

@Serializable
private data class AgentErrorEnvelopeDto(
    val issues: List<AgentErrorIssueDto> = emptyList()
)

@Serializable
private data class AgentErrorIssueDto(
    val code: String,
    val pointer: String? = null
)

@Serializable
private data class AgentWireDto(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val serverRole: String? = null,
    val status: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val external: Boolean? = null,
    val runtimeConfig: AgentRuntimeConfigWireDto? = null,
    val reasoningEffort: String? = null,
    val executionMode: String? = null,
    val machineId: String? = null,
    val avatarUrl: String? = null,
    val creatorId: String? = null,
    val creatorType: String? = null,
    val creator: AgentCreatorWireDto? = null,
    val createdAt: String? = null,
    val daemonVersion: String? = null,
    val envVars: Map<String, JsonElement> = emptyMap(),
    val createdAgents: List<AgentCreatedWireDto> = emptyList(),
    val activity: String? = null,
    val activityDetail: String? = null,
    val deletedAt: String? = null,
    /**
     * External agents only: ISO-8601 `lastSeenAt` from `GET /agents*` — last
     * credential use; presence derives from it (`agent:seen` supersedes).
     */
    val lastSeenAt: String? = null,
    val runtimeProfile: AgentRuntimeProfileDto? = null,
    /** `GET /agents*` typed kind of [activityDetail] (shared `AgentActivityDetailKind`). */
    val activityDetailKind: String? = null,
    /** Shared `WakeCrashLoopActivityDiagnostic`, sent with `activityDetailKind` `wake_crash_loop_blocked`. */
    val wakeCrashLoop: WakeCrashLoopDto? = null,
    /** Shared `ToolStuckActivityDiagnostic`, served with any activity while the agent is stuck. */
    val toolStuck: ToolStuckDto? = null,
)

@Serializable
private data class AgentCreatorWireDto(
    val type: String,
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

private fun AgentWireDto.toSummary(): AgentSummary? {
    if (id.isBlank() || name.isBlank()) return null
    val currentProfile = runtimeProfile?.current
    val configuredModel = runtimeConfig?.model?.valueOrNull()
    return AgentSummary(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        serverRole = serverRole?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
        status = status,
        model = configuredModel ?: model ?: currentProfile?.model,
        runtimeModelKind = runtimeConfig?.model?.normalizedKindOrNull(),
        runtimeProvider = runtimeConfig?.provider?.toReadProjection(runtimeConfig.runtime),
        runtime = runtimeConfig?.runtime?.takeIf { it.isNotBlank() } ?: runtime ?: currentProfile?.runtime,
        isExternal =
            isExternalAgent(
                external = external,
                runtime = runtimeConfig?.runtime?.takeIf { it.isNotBlank() } ?: runtime ?: currentProfile?.runtime
            ),
        reasoning = reasoningEffort ?: currentProfile?.reasoningEffort,
        executionMode = executionMode ?: currentProfile?.executionMode,
        machineId = machineId ?: currentProfile?.machineId,
        machineName = currentProfile?.machineName,
        daemonVersion = daemonVersion ?: currentProfile?.daemonVersion,
        avatarUrl = avatarUrl,
        creatorId = creatorId,
        creatorType = creatorType,
        creator = creator?.toDomain(),
        createdAt = createdAt,
        envVarNames = envVars.keys.filter { it.isNotBlank() }.sorted(),
        envVars = envVars.toAgentEnvVars(),
        createdAgents = createdAgents.mapNotNull { it.toDomain() },
        activity = activity,
        activityDetail = activityDetail,
        deletedAt = deletedAt,
        lastSeenAt = lastSeenAt?.takeIf { it.isNotBlank() },
        activityDetailKind = activityDetailKind?.trim()?.takeIf { it.isNotEmpty() },
        wakeCrashLoop = wakeCrashLoop.toAgentWakeCrashLoop(activityDetailKind),
        toolStuck = toolStuck.toAgentToolStuck()
    )
}

@Serializable
private data class ExternalAgentStatusWireDto(
    val setupState: String? = null,
    val credentialLastUsedAt: String? = null,
    val lastActivityAt: String? = null
)

@Serializable
private data class AgentRuntimeConfigWireDto(
    val runtime: String? = null,
    val provider: AgentRuntimeProviderWireDto? = null,
    val model: AgentRuntimeModelWireDto? = null
)

@Serializable
private data class AgentRuntimeProviderWireDto(
    val kind: String? = null,
    val providerId: String? = null,
    val apiUrl: String? = null,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val supportsImageInput: Boolean = false
) {
    /**
     * Agent reads are projections, not secret-recovery surfaces. Built-in keys are
     * write-only on the server; even if a malformed/legacy response contains bytes,
     * retain only the non-secret provider identity and replace the key with blank.
     */
    fun toReadProjection(runtime: String?): RuntimeProviderConfig? {
        return when (kind?.trim()?.lowercase()) {
            "default" -> RuntimeProviderConfig.Default
            "custom" -> {
                val cleanUrl = apiUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
                RuntimeProviderConfig.Custom(cleanUrl, apiKey.orEmpty())
            }
            "preset" -> {
                if (runtime != "builtin") return null
                val cleanProviderId = providerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
                RuntimeProviderConfig.Preset(cleanProviderId, apiKey = "")
            }
            "gateway" -> {
                if (runtime != "builtin") return null
                val cleanProviderId = providerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
                val cleanBaseUrl = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
                RuntimeProviderConfig.Gateway(
                    providerId = cleanProviderId,
                    baseUrl = cleanBaseUrl,
                    apiKey = "",
                    supportsImageInput = supportsImageInput
                )
            }
            "pi-builtin" -> {
                if (runtime != "pi") return null
                val cleanProviderId = providerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
                RuntimeProviderConfig.PiBuiltin(cleanProviderId, apiKey.orEmpty())
            }
            else -> null
        }
    }
}

@Serializable
private data class AgentRuntimeModelWireDto(
    val kind: String? = null,
    val id: String? = null,
    val name: String? = null
) {
    fun normalizedKindOrNull(): String? =
        kind?.trim()?.lowercase()?.takeIf { it == "preset" || it == "custom" }

    fun valueOrNull(): String? =
        when (normalizedKindOrNull()) {
            "preset" -> id?.trim()?.takeIf { it.isNotBlank() }
            "custom" -> name?.trim()?.takeIf { it.isNotBlank() }
            else -> null
        }
}

private fun Map<String, JsonElement>.toAgentEnvVars(): Map<String, String> =
    entries
        .mapNotNull { (key, value) ->
            val cleanKey = key.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            cleanKey to ((value as? JsonPrimitive)?.contentOrNull ?: value.toString())
        }
        .sortedBy { it.first }
        .toMap()

private fun AgentCreatorWireDto.toDomain(): AgentCreatorSummary? {
    if (id.isBlank() || name.isBlank()) return null
    return AgentCreatorSummary(
        type = type,
        id = id,
        name = name,
        displayName = displayName,
        avatarUrl = avatarUrl
    )
}

@Serializable
private data class AgentCreatedWireDto(
    val id: String,
    val name: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val runtime: String? = null,
    val external: Boolean? = null,
    val status: String? = null
) {
    fun toDomain(): AgentCreatedSummary? {
        val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
        if (id.isBlank() || resolvedName.isNullOrBlank()) return null
        return AgentCreatedSummary(
            id = id,
            name = name ?: resolvedName,
            displayName = displayName,
            avatarUrl = avatarUrl,
            runtime = runtime,
            status = status,
            isExternal = isExternalAgent(external, runtime)
        )
    }
}

@Serializable
private data class AgentRuntimeProfileDto(
    val current: AgentRuntimeProfileCurrentDto? = null
)

@Serializable
private data class AgentRuntimeProfileCurrentDto(
    val machineId: String? = null,
    val machineName: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val executionMode: String? = null,
    val daemonVersion: String? = null
)

@Serializable
private data class AgentCommandResponseDto(val ok: Boolean)

@Serializable
private data class AgentScopesWireDto(
    val agentId: String,
    val granted: List<String>,
    val mode: String = "default",
    val revision: Long = 0L,
    val updatedAt: String? = null
)

@Serializable
private data class AgentScopesUpdateDto(
    val scopes: List<String> = emptyList()
)

private fun AgentScopesWireDto.toScopeSet(agentId: MemberId): AgentScopeSet =
    AgentScopeSet(
        agentId = agentId,
        granted = granted,
        mode = mode,
        revision = revision,
        updatedAt = updatedAt
    )

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
private data class AgentLoginTokenResponseDto(
    val credentialId: String? = null,
    val apiKey: String? = null,
    val scopes: List<String> = emptyList(),
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
    val onboarding: Boolean? = null,
    /**
     * Full RuntimeConfig object (mirrors `slock-ai/shared` `RuntimeConfig`).
     * When provided, the server picks up `model` / `runtime` / `mode` / `provider` /
     * `command` from this nested object instead of the legacy top-level fields above.
     * The legacy fields are kept so the Thread action card path
     * (which doesn't collect runtimeConfig yet) keeps working unchanged.
     */
    val runtimeConfig: build.raft.app.model.RuntimeConfig? = null,
    val formDefinitionRef: SharedRuntimeFormDefinitionRef? = null,
    /**
     * Indicates this create call comes from the external-agent flow (no Computer).
     * Mirrors React `CreateAgentDialog` `external` flag.
     */
    val external: Boolean? = null
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

// task #186: create requests must match the web wire shape — absent optional
// fields are OMITTED, never serialized as explicit `null`. The server treats an
// explicit `"formDefinitionRef":null` as present-but-invalid (routes/agents.ts
// validates any non-undefined value), which 409s every non-Built-in create and
// 400s external creates. Encoding-only; response decoding stays on agentsApiJson.
private val agentsApiCreateBodyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
}

internal fun buildCreateAgentBody(body: CreateAgentBody): String = agentsApiCreateBodyJson.encodeToString(body)

/** Runtime form v2 create: the create body plus `formDefinitionRef {protocolVersion:2}` and `formValues`. */
internal fun buildCreateAgentV2Body(body: CreateAgentBody, submit: RuntimeFormV2SubmitBody): String =
    agentsApiCreateBodyJson.encodeToJsonElement(CreateAgentBody.serializer(), body.copy(runtimeConfig = null, formDefinitionRef = null))
        .jsonObject
        .withRuntimeFormV2(submit)
        .toString()
internal fun buildUpdateAgentBody(body: UpdateAgentBody): String = agentsApiJson.encodeToString(body)
internal fun buildResetAgentBody(mode: String): String = agentsApiJson.encodeToString(ResetAgentBody(mode))
internal fun buildAssignAgentMachineBody(machineId: String?): String =
    agentsApiJson.encodeToString(AssignAgentMachineBody(machineId = machineId))
internal fun buildUpdateAgentScopesBody(body: UpdateAgentScopesBody): String = agentsApiJson.encodeToString(body)
internal fun buildIssueAgentBootstrapTokenBody(body: IssueAgentBootstrapTokenBody): String =
    agentsApiJson.encodeToString(body)
