package build.raft.app.data

import build.raft.app.raftAvatarDiagnosticsHash

import build.raft.app.data.runtimeform.v2.runtimeFormV2ServerError
import build.raft.app.data.runtimeform.v2.withRuntimeFormV2
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.RaftWorkspaceFileKind
import build.raft.app.model.RaftWorkspaceNode
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

private val memberRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// task #152: the PATCH `runtimeConfig` object must match the web/create wire shape —
// absent optional fields are OMITTED, never serialized as explicit `null`. The server
// validates any non-undefined value (e.g. `loadLocalPlugins !== undefined` must be a
// boolean on builtin and is rejected on every other runtime), so `"loadLocalPlugins":null`
// 400'd every non-Built-in runtime save. Mirrors AgentsApi's create-body encoder.
private val agentRuntimeConfigUpdateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private fun PlatformFile.toAgentAvatarMultipartFile(): MultipartFile =
    MultipartFile(
        name = "avatar",
        fileName = fileName,
        mimeType = mimeType ?: "application/octet-stream",
        bytes = bytes,
        fileRef = fileRef,
        sizeBytes = sizeBytes
    )

interface RaftNetworkClient {
    suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        serverIdOverride: ServerId? = null,
        bodyText: String? = null
    ): HomeNetworkResponse

    /**
     * [request] with extra request headers (e.g. `Accept-Language`). Auth and server-binding headers
     * still come from the client and win over a same-named extra header. Clients that cannot send
     * extra headers fall back to the plain request.
     */
    suspend fun requestWithHeaders(
        priority: DataRequestPriority,
        path: String,
        headers: Map<String, String>,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        serverIdOverride: ServerId? = null,
        bodyText: String? = null
    ): HomeNetworkResponse =
        request(
            priority = priority,
            path = path,
            method = method,
            query = query,
            serverIdOverride = serverIdOverride,
            bodyText = bodyText
        )

    suspend fun requestRawAbsoluteUrl(
        priority: DataRequestPriority,
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        bodyText: String? = null
    ): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    /** Server-authoritative direct attachment-session operations. */
    suspend fun createAttachmentUploadSession(
        priority: DataRequestPriority,
        request: AttachmentUploadSessionCreateRequest,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    suspend fun completeAttachmentUploadSession(
        priority: DataRequestPriority,
        uploadId: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    suspend fun cancelAttachmentUploadSession(
        priority: DataRequestPriority,
        uploadId: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    suspend fun statusAttachmentUploadSession(
        priority: DataRequestPriority,
        uploadId: String,
        serverIdOverride: ServerId? = null,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    suspend fun putAttachmentUploadBytes(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        bytes: ByteArray,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    suspend fun putAttachmentUploadFile(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): HomeNetworkResponse = authorityBindingRequiredResponse()

    /**
     * Resolve the authoritative local length before a direct-session create request.  This is a
     * local platform operation, but it remains on the same bound network-client seam so a route
     * or account transition cannot race file admission.
     */
    suspend fun prepareAttachmentUploadFile(
        file: PlatformFile,
    ): RaftPlatformFilePreparationResult =
        RaftPlatformFilePreparationResult.Rejected("auth_authority_binding_required")

    suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun uploadAccountAvatar(
        priority: DataRequestPriority,
        file: MultipartFile
    ): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        agentId: MemberId,
        file: MultipartFile
    ): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun uploadConnectedAppLogo(
        priority: DataRequestPriority,
        clientRecordId: String,
        file: MultipartFile
    ): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
}

internal fun interface ProgressiveAttachmentNetworkClient {
    suspend fun uploadAttachmentsWithProgress(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>,
        onProgress: (build.raft.app.network.RaftTransferProgress) -> Unit
    ): HomeNetworkResponse
}

internal interface AuthorityBoundRaftNetworkClient {
    suspend fun requestBound(
        priority: DataRequestPriority,
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        serverIdOverride: ServerId? = null,
        bodyText: String? = null,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse

    /** [requestBound] with extra request headers; see [RaftNetworkClient.requestWithHeaders]. */
    suspend fun requestBoundWithHeaders(
        priority: DataRequestPriority,
        path: String,
        headers: Map<String, String>,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        serverIdOverride: ServerId? = null,
        bodyText: String? = null,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse =
        requestBound(
            priority = priority,
            path = path,
            method = method,
            query = query,
            serverIdOverride = serverIdOverride,
            bodyText = bodyText,
            expectedAuthority = expectedAuthority
        )

    suspend fun uploadAttachmentsBound(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>,
        expectedAuthority: RaftExpectedAuthBinding
    ): HomeNetworkResponse

    suspend fun createAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        request: AttachmentUploadSessionCreateRequest,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun completeAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        uploadId: String,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun cancelAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        uploadId: String,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun statusAttachmentUploadSessionBound(
        priority: DataRequestPriority,
        uploadId: String,
        expectedAuthority: RaftExpectedAuthBinding,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    /** Presigned PUTs carry no bearer token, but are still fenced to the captured route authority. */
    suspend fun putAttachmentUploadBytesBound(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        bytes: ByteArray,
        expectedAuthority: RaftExpectedAuthBinding,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun putAttachmentUploadFileBound(
        priority: DataRequestPriority,
        upload: AttachmentUploadSessionPut,
        file: PlatformFile,
        expectedAuthority: RaftExpectedAuthBinding,
        onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)? = null,
    ): HomeNetworkResponse = HomeNetworkResponse(code = 501, bodyText = null)

    suspend fun prepareAttachmentUploadFileBound(
        file: PlatformFile,
        expectedAuthority: RaftExpectedAuthBinding,
    ): RaftPlatformFilePreparationResult =
        RaftPlatformFilePreparationResult.Rejected("auth_authority_binding_unsupported")
}

/**
 * A request-only repository client pinned to one captured authenticated route
 * receipt. Every JSON request reaches the auth middleware with the same
 * account/token/server witness; unsupported carriers fail closed before I/O.
 */
internal fun RaftNetworkClient.boundTo(expectedAuthority: RaftExpectedAuthBinding): RaftNetworkClient {
    val bound = this as? AuthorityBoundRaftNetworkClient
    return object : RaftNetworkClient {
        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse =
            bound?.requestBound(
                priority = priority,
                path = path,
                method = method,
                query = query,
                serverIdOverride = serverIdOverride,
                bodyText = bodyText,
                expectedAuthority = expectedAuthority
            ) ?: authorityBindingUnsupportedResponse()

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse =
            bound?.requestBoundWithHeaders(
                priority = priority,
                path = path,
                headers = headers,
                method = method,
                query = query,
                serverIdOverride = serverIdOverride,
                bodyText = bodyText,
                expectedAuthority = expectedAuthority
            ) ?: authorityBindingUnsupportedResponse()

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse =
            bound?.uploadAttachmentsBound(
                priority = priority,
                channelId = channelId,
                files = files,
                expectedAuthority = expectedAuthority
            ) ?: authorityBindingUnsupportedResponse()

        override suspend fun createAttachmentUploadSession(
            priority: DataRequestPriority,
            request: AttachmentUploadSessionCreateRequest,
            serverIdOverride: ServerId?,
        ): HomeNetworkResponse =
            bound?.createAttachmentUploadSessionBound(priority, request, expectedAuthority)
                ?: authorityBindingUnsupportedResponse()

        override suspend fun completeAttachmentUploadSession(
            priority: DataRequestPriority,
            uploadId: String,
            serverIdOverride: ServerId?,
        ): HomeNetworkResponse =
            bound?.completeAttachmentUploadSessionBound(priority, uploadId, expectedAuthority)
                ?: authorityBindingUnsupportedResponse()

        override suspend fun cancelAttachmentUploadSession(
            priority: DataRequestPriority,
            uploadId: String,
            serverIdOverride: ServerId?,
        ): HomeNetworkResponse =
            bound?.cancelAttachmentUploadSessionBound(priority, uploadId, expectedAuthority)
                ?: authorityBindingUnsupportedResponse()

        override suspend fun statusAttachmentUploadSession(
            priority: DataRequestPriority,
            uploadId: String,
            serverIdOverride: ServerId?,
        ): HomeNetworkResponse =
            bound?.statusAttachmentUploadSessionBound(priority, uploadId, expectedAuthority)
                ?: authorityBindingUnsupportedResponse()

        override suspend fun putAttachmentUploadBytes(
            priority: DataRequestPriority,
            upload: AttachmentUploadSessionPut,
            bytes: ByteArray,
            onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
        ): HomeNetworkResponse =
            bound?.putAttachmentUploadBytesBound(priority, upload, bytes, expectedAuthority, onUploadProgress)
                ?: authorityBindingUnsupportedResponse()

        override suspend fun putAttachmentUploadFile(
            priority: DataRequestPriority,
            upload: AttachmentUploadSessionPut,
            file: PlatformFile,
            onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
        ): HomeNetworkResponse =
            bound?.putAttachmentUploadFileBound(priority, upload, file, expectedAuthority, onUploadProgress)
                ?: authorityBindingUnsupportedResponse()

        override suspend fun prepareAttachmentUploadFile(
            file: PlatformFile,
        ): RaftPlatformFilePreparationResult =
            bound?.prepareAttachmentUploadFileBound(file, expectedAuthority)
                ?: RaftPlatformFilePreparationResult.Rejected("auth_authority_binding_unsupported")
    }
}

internal fun authorityBindingUnsupportedResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 409,
        bodyText = null,
        errorBodyText = """{"error":"auth_authority_binding_unsupported"}"""
    )

/** Direct upload sessions are reservation-bearing mutations and must never use a live route
 * selector without the captured account/server/environment witness. */
internal fun authorityBindingRequiredResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 409,
        bodyText = null,
        errorBodyText = """{"error":"auth_authority_binding_required"}"""
    )

private suspend fun RaftNetworkClient.serverMembers(priority: DataRequestPriority, serverId: ServerId): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/members", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.settingsAgents(
    priority: DataRequestPriority,
    serverId: ServerId? = null
): HomeNetworkResponse =
    request(priority, "agents", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.settingsMachines(priority: DataRequestPriority, serverId: ServerId): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/machines", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.updateAgent(priority: DataRequestPriority, agentId: MemberId, bodyText: String): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}", method = "PATCH", bodyText = bodyText)

private suspend fun RaftNetworkClient.memberProfile(
    priority: DataRequestPriority,
    serverId: ServerId,
    memberId: MemberId
): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/members/${memberId.value}/profile", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.agentDirectMessages(priority: DataRequestPriority, agentId: MemberId): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/agent-dms")

private suspend fun RaftNetworkClient.agentChannels(priority: DataRequestPriority, agentId: MemberId): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/channels")

private suspend fun RaftNetworkClient.reminders(
    priority: DataRequestPriority,
    ownerAgentId: MemberId,
    status: String = "scheduled"
): HomeNetworkResponse =
    request(priority, "reminders", query = listOf("ownerAgentId" to ownerAgentId.value, "status" to status))

private suspend fun RaftNetworkClient.agentActivityLog(
    priority: DataRequestPriority,
    agentId: MemberId,
    limit: Int = 50
): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/activity-log", query = listOf("limit" to limit.toString()))

private suspend fun RaftNetworkClient.settingsMachineWorkspaces(
    priority: DataRequestPriority,
    serverId: ServerId,
    machineId: String
): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/machines/$machineId/workspaces", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.agentWorkspaceFiles(
    priority: DataRequestPriority,
    agentId: MemberId,
    dirPath: String? = null
): HomeNetworkResponse =
    request(
        priority,
        "agents/${agentId.value}/workspace-files",
        query = listOfNotNull(dirPath?.let { "dirPath" to it })
    )

private suspend fun RaftNetworkClient.readAgentWorkspaceFile(
    priority: DataRequestPriority,
    agentId: MemberId,
    path: String
): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/workspace-files/read", query = listOf("path" to path))

private suspend fun RaftNetworkClient.agentSkills(priority: DataRequestPriority, agentId: MemberId): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/skills")

private suspend fun RaftNetworkClient.channelMembers(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members")

private suspend fun RaftNetworkClient.addChannelMember(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members", method = "POST", bodyText = bodyText)

private suspend fun RaftNetworkClient.addChannelMembersBatch(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members/batch", method = "POST", bodyText = bodyText)

private suspend fun RaftNetworkClient.removeChannelMember(
    priority: DataRequestPriority,
    channelId: ChannelId,
    kind: MemberKind,
    memberId: MemberId
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members/${kind.channelMemberPathSegment}/${memberId.value}", method = "DELETE")

class RealMemberRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {},
    private val logger: (String) -> Unit = {}
) : MemberRepository {
    private val agentsApiClient = RaftAgentsApiClient(
        transport = RaftAgentsNetworkApi(networkClient),
        diagnosticsLogger = { message -> logger("agents_api $message") }
    )
    private val integrationsApiClient = RaftIntegrationsActionsPushApiClient(
        transport = RaftIntegrationsActionsPushNetworkApi(networkClient)
    )
    private val agentLifecycleActionMutex = Mutex()
    private val agentLifecycleActionsInFlight = mutableSetOf<MemberId>()

    override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
        val startedAt = TimeSource.Monotonic.markNow()
        val context = sessionStore.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        val result =
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.serverMembers(priority, serverId) },
                source = "server_members",
                mapper = { bodyText ->
                    parseSharedServerMemberSnapshot(bodyText, context.server)
                        ?: throw IllegalStateException("Server members response missing member array")
                }
            )
        val agentResult =
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.settingsAgents(priority, serverId) },
                source = "server_agents",
                mapper = { bodyText ->
                    parseSharedSettingsAgentsAsMembers(bodyText)
                        ?: throw IllegalStateException("Agents response missing agent array")
                }
            )
        val machineNameResult =
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.settingsMachines(priority, serverId) },
                source = "server_agent_machines",
                mapper = { bodyText ->
                    parseSharedSettingsAgentMachineNames(bodyText)
                        ?: throw IllegalStateException("Machines response missing machines")
                }
            )
        val mergedResult =
            when (result) {
                is DataResult.Content -> {
                    val mergeStartedAt = TimeSource.Monotonic.markNow()
                    val machineNames = (machineNameResult as? DataResult.Content)?.data.orEmpty()
                    val agents = (agentResult as? DataResult.Content)?.data.orEmpty().resolveComputerLabels(machineNames)
                    logAgentsWithoutComputer(logger, serverId, agents)
                    val mergedMembers = (result.data.members + agents).distinctBy { it.memberKey }
                    val mergeMs = mergeStartedAt.elapsedNow().inWholeMilliseconds
                    logger(
                        "repository_merge domain=members source=server_members serverId=${serverId.value} " +
                            "humans=${result.data.members.size} agents=${agents.size} machines=${machineNames.size} " +
                            "merged=${mergedMembers.size} mergeMs=$mergeMs " +
                            "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                    )
                    if (mergedMembers.isEmpty()) {
                        DataResult.Empty(EmptyReason.NoMembers)
                    } else {
                        DataResult.Content(result.data.copy(members = mergedMembers))
                    }
                }
                else -> result
            }
        mergedResult.logMemberCounts(source = "server_members", serverId = serverId.value, logger = logger)
        logger(
            "repository_agents_merge source=server_agents serverId=${serverId.value} result=${agentResult.memberResultName()} " +
                "agentsCount=${(agentResult as? DataResult.Content)?.data?.count { it.kind == MemberKind.Agent } ?: 0} " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        return mergedResult
    }

    override suspend fun getMemberProfile(
        priority: DataRequestPriority,
        memberId: MemberId,
        serverId: ServerId?
    ): DataResult<MemberProfile> {
        val context = currentContext() ?: return authRequired()
        return getHumanProfile(
            priority = priority,
            memberId = memberId,
            serverId = serverId ?: context.server.id,
            source = "member_profile"
        )
    }

    override suspend fun updateAgentProfile(
        priority: DataRequestPriority,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> {
        currentContext() ?: return authRequired()
        val displayName = request.displayName.trim()
        val description = request.description?.trim()?.takeIf { it.isNotBlank() }
        if (displayName.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = {
                networkClient.updateAgent(
                    priority,
                    request.agentId,
                    buildSharedAgentProfileUpdateBody(
                        request.copy(displayName = displayName, description = description)
                    )
                )
            },
            source = "agent_profile_update",
            mapper = { bodyText ->
                parseSharedAgentProfile(bodyText)
                    ?: throw IllegalStateException("Agent profile update response missing agent")
            }
        )
    }

    override suspend fun uploadAgentAvatar(
        priority: DataRequestPriority,
        request: AgentAvatarUploadRequest
    ): DataResult<MemberProfile> {
        currentContext() ?: return authRequired()
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = {
                networkClient.uploadAgentAvatar(
                    priority,
                    request.agentId,
                    request.file.toAgentAvatarMultipartFile()
                )
            },
            source = "agent_avatar_upload",
            mapper = { bodyText ->
                parseSharedAgentProfile(bodyText)
                    ?: throw IllegalStateException("Agent avatar upload response missing agent")
            }
        )
    }

    override suspend fun getAgentDetail(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDetailSnapshot> = coroutineScope {
        currentContext() ?: return@coroutineScope authRequired()
        val summaryRequest = async { agentsApiClient.agent(agentId) }
        val directMessagesRequest = async { getAgentDirectMessages(priority, agentId) }
        val channelsRequest = async { getAgentChannels(priority, agentId) }
        val remindersRequest = async { getAgentReminders(priority, agentId) }
        val activityLogRequest = async { getAgentActivityLog(priority, agentId) }
        val skillsRequest = async { getAgentSkills(priority, agentId) }
        val appLoginsRequest = async {
            getAgentAppLogins(priority, agentId).toAgentDetailAppLogins(agentId)
        }

        val summary = summaryRequest.await()
        // React AgentDetailPanel parity: external setup/activity status is a
        // separate, per-agent, permission-gated request. Only external agents
        // are asked; 400/403/failure collapse to null (= not provided), never
        // to a "waiting for login" reading.
        val externalStatus =
            summary.contentOrNull()
                ?.takeIf { it.isExternal }
                ?.let { agentsApiClient.agentExternalStatus(agentId).contentOrNull() }
        val directMessages = directMessagesRequest.await()
        val channels = channelsRequest.await()
        val reminders = remindersRequest.await()
        val activity = mergeAgentActivity(
            agentId = agentId,
            logResult = activityLogRequest.await(),
            summary = summary.contentOrNull()
        )
        val skills = skillsRequest.await()
        val appLogins = appLoginsRequest.await()
        when {
            summary is DataResult.Content ->
                DataResult.Content(
                    AgentDetailSnapshot(
                        agentId = agentId,
                        summary = summary.data.toAgentDetailSummary(),
                        directMessages = directMessages.contentOrNull()?.directMessages.orEmpty(),
                        channels = channels.contentOrNull()?.channels.orEmpty(),
                        reminders = reminders.contentOrNull()?.reminders.orEmpty(),
                        activity = activity.contentOrNull()?.activity.orEmpty(),
                        skills = skills.contentOrNull() ?: AgentSkillsSnapshot(agentId = agentId),
                        appLogins = appLogins,
                        externalStatus = externalStatus
                    )
                )
            summary is DataResult.NoPermission -> summary
            summary is DataResult.Error -> summary
            summary is DataResult.Empty -> summary
            else -> DataResult.Empty(EmptyReason.NoMembers)
        }
    }

    override suspend fun getAgentDirectMessages(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDmSnapshot> {
        currentContext() ?: return authRequired()
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.agentDirectMessages(priority, agentId) },
            source = "agent_direct_messages",
            mapper = { bodyText ->
                parseSharedAgentDirectMessages(bodyText, agentId)
                    ?: throw IllegalStateException("Agent DM response missing dm array")
            }
        )
    }

    override suspend fun getAgentChannels(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentChannelSnapshot> {
        currentContext() ?: return authRequired()
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.agentChannels(priority, agentId) },
            source = "agent_channels",
            mapper = { bodyText ->
                parseSharedAgentChannels(bodyText, agentId)
                    ?: throw IllegalStateException("Agent channels response missing channel array")
            }
        )
    }

    override suspend fun getAgentReminders(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentReminderSnapshot> {
        currentContext() ?: return authRequired()
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.reminders(priority, agentId) },
            source = "agent_reminders",
            mapper = { bodyText ->
                parseSharedAgentReminders(bodyText, agentId)
                    ?: throw IllegalStateException("Agent reminders response missing reminders")
            }
        )
    }

    override suspend fun getAgentActivity(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentActivitySnapshot> = coroutineScope {
        currentContext() ?: return@coroutineScope authRequired()
        val summaryRequest = async { agentsApiClient.agent(agentId) }
        val activityLogRequest = async { getAgentActivityLog(priority, agentId) }
        mergeAgentActivity(
            agentId = agentId,
            logResult = activityLogRequest.await(),
            summary = summaryRequest.await().contentOrNull()
        )
    }

    private suspend fun getAgentActivityLog(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentActivitySnapshot> {
        currentContext() ?: return authRequired()
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.agentActivityLog(priority, agentId, limit = 50) },
            source = "agent_activity",
            mapper = { bodyText ->
                parseSharedAgentActivity(bodyText, agentId)
                    ?: throw IllegalStateException("Agent activity response missing log array")
            }
        ).also { result ->
            (result as? DataResult.Content)?.data?.let { snapshot ->
                logger("agent_activity_log_fetch agentId=${agentId.value} mappedCount=${snapshot.activity.size}")
            }
        }
    }

    private fun mergeAgentActivity(
        agentId: MemberId,
        logResult: DataResult<AgentActivitySnapshot>,
        summary: AgentSummary?
    ): DataResult<AgentActivitySnapshot> {
        val currentEntry =
            summary?.let {
                currentAgentActivityEntry(
                    agentId = agentId,
                    activity = it.activity,
                    status = it.status,
                    activityDetail = it.activityDetail
                )
            }
        val currentEntries = listOfNotNull(currentEntry)
        return when (logResult) {
            is DataResult.Content -> {
                val activity = mergeAgentActivityEntries(currentEntries + logResult.data.activity)
                logger(
                    "repository_merge domain=agent_activity source=response_merge agentId=${agentId.value} " +
                        "current=${currentEntries.size} log=${logResult.data.activity.size} merged=${activity.size}"
                )
                DataResult.Content(logResult.data.copy(activity = activity))
            }
            is DataResult.Empty -> {
                if (currentEntries.isNotEmpty()) {
                    DataResult.Content(
                        AgentActivitySnapshot(
                            agentId = agentId,
                            activity = mergeAgentActivityEntries(currentEntries)
                        )
                    )
                } else {
                    logResult
                }
            }
            else -> logResult
        }
    }

    override suspend fun getAgentWorkspaces(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentWorkspaceSnapshot> {
        val context = currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        val lookupResult =
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.settingsAgents(priority) },
                source = "agent_workspaces_lookup",
                mapper = { bodyText ->
                    parseSharedAgentWorkspaceLookup(bodyText, agentId)
                        ?: throw IllegalStateException("Agent workspace lookup response missing agent")
                }
            )
        return when (lookupResult) {
            is DataResult.Content -> {
                val lookup = lookupResult.data
                val baseSnapshot =
                    AgentWorkspaceSnapshot(agentId = agentId, computerId = lookup.computerId, computerName = lookup.computerName)
                when {
                    lookup.directWorkspaces.isNotEmpty() -> DataResult.Content(baseSnapshot.copy(workspaces = lookup.directWorkspaces))
                    !lookup.computerId.isNullOrBlank() -> {
                        val machineResult =
                            executeMember(
                                emptyReason = EmptyReason.NoMembers,
                                block = { networkClient.settingsMachineWorkspaces(priority, context.server.id, lookup.computerId) },
                                source = "agent_workspaces_machine",
                                mapper = { bodyText ->
                                    parseSharedSettingsMachineWorkspaces(bodyText)
                                        ?: throw IllegalStateException("Machine workspaces response missing workspace array")
                                }
                            )
                        when (machineResult) {
                            is DataResult.Content -> DataResult.Content(baseSnapshot.copy(workspaces = machineResult.data))
                            is DataResult.Empty, is DataResult.Error, is DataResult.NoPermission ->
                                if (lookup.fallbackWorkspaces.isNotEmpty()) {
                                    DataResult.Content(baseSnapshot.copy(workspaces = lookup.fallbackWorkspaces))
                                } else {
                                    machineResult
                                }
                        }
                    }
                    lookup.fallbackWorkspaces.isNotEmpty() -> DataResult.Content(baseSnapshot.copy(workspaces = lookup.fallbackWorkspaces))
                    else -> DataResult.Empty(EmptyReason.NoMembers)
                }
            }
            is DataResult.Empty -> lookupResult
            is DataResult.Error -> lookupResult
            is DataResult.NoPermission -> lookupResult
        }
    }

    override suspend fun getAgentWorkspaceTree(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentWorkspaceTreeSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.agentWorkspaceFiles(priority, agentId) },
            source = "agent_workspace_root",
            mapper = { bodyText ->
                val children = parseSharedWorkspaceFileNodesOrNull(bodyText)
                    ?: throw IllegalStateException("Workspace file response missing files")
                AgentWorkspaceTreeSnapshot(
                    agentId = agentId,
                    rootPath = "Workspace",
                    root = RaftWorkspaceNode(
                        id = "/",
                        name = "workspace",
                        path = "/",
                        kind = RaftWorkspaceFileKind.Folder,
                        children = children
                    )
                )
            }
        )
    }

    override suspend fun getAgentWorkspaceChildren(
        priority: DataRequestPriority,
        agentId: MemberId,
        dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank() || dirPath.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.agentWorkspaceFiles(priority, agentId, dirPath) },
            source = "agent_workspace_children",
            mapper = { bodyText ->
                val children = parseSharedWorkspaceFileNodesOrNull(bodyText)
                    ?: throw IllegalStateException("Workspace file response missing files")
                AgentWorkspaceChildrenSnapshot(agentId = agentId, dirPath = dirPath, children = children)
            }
        )
    }

    override suspend fun readAgentWorkspaceFile(
        priority: DataRequestPriority,
        agentId: MemberId,
        path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank() || path.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.readAgentWorkspaceFile(priority, agentId, path) },
            source = "agent_workspace_file_read",
            mapper = { bodyText -> parseSharedAgentWorkspaceFilePreviewSnapshot(bodyText, agentId, path) }
        )
    }

    override suspend fun getAgentSkills(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentSkillsSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.agentSkills(priority, agentId) },
            source = "agent_skills",
            mapper = { bodyText ->
                parseSharedAgentSkillsSnapshot(bodyText, agentId)
                    ?: throw IllegalStateException("Agent skills response missing skill groups")
            }
        )
    }

    override suspend fun getAgentAppLogins(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentAppLoginSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return integrationsApiClient.agentIntegrations(agentId).map { it.toSnapshot() }
    }

    override suspend fun startAgent(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentLifecycleActionResult> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeAgentLifecycleAction(agentId, "start") {
            agentsApiClient.startAgent(agentId).toLifecycleActionResult(agentId = agentId, action = "start")
        }
    }

    override suspend fun stopAgent(
        priority: DataRequestPriority,
        agentId: MemberId
    ): DataResult<AgentLifecycleActionResult> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeAgentLifecycleAction(agentId, "stop") {
            agentsApiClient.stopAgent(agentId).toLifecycleActionResult(agentId = agentId, action = "stop")
        }
    }

    override suspend fun resetAgent(
        priority: DataRequestPriority,
        request: AgentResetRequest
    ): DataResult<AgentLifecycleActionResult> {
        currentContext() ?: return authRequired()
        if (request.agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeAgentLifecycleAction(request.agentId, "reset") {
            agentsApiClient.resetAgent(
                request.agentId,
                buildResetAgentBody(request.mode.wireValue)
            ).toLifecycleActionResult(
                agentId = request.agentId,
                action = "reset",
                resetMode = request.mode
            )
        }
    }

    override suspend fun deleteAgent(
        priority: DataRequestPriority,
        agentId: MemberId,
        workspaceChoice: AgentArchiveWorkspaceChoice
    ): DataResult<AgentLifecycleActionResult> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return executeAgentLifecycleAction(agentId, "delete") {
            agentsApiClient.deleteAgent(agentId, workspaceChoice.toRequestBody())
                .toLifecycleActionResult(agentId = agentId, action = "delete")
        }
    }

    override suspend fun assignAgentMachine(
        priority: DataRequestPriority,
        request: AgentMachineAssignmentRequest
    ): DataResult<AgentLifecycleActionResult> {
        currentContext() ?: return authRequired()
        if (request.agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        val machineId = request.machineId?.trim()?.takeIf { it.isNotBlank() }
        return executeAgentLifecycleAction(request.agentId, "assign_machine") {
            agentsApiClient.assignMachine(
                request.agentId,
                buildAssignAgentMachineBody(machineId)
            ).toLifecycleActionResult(
                agentId = request.agentId,
                action = "assign_machine",
                machineId = machineId
            )
        }
    }

    override suspend fun getChannelMembers(priority: DataRequestPriority, channelId: ChannelId): DataResult<ChannelMemberSnapshot> {
        currentContext() ?: return authRequired()
        val result =
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.channelMembers(priority, channelId) },
                source = "channel_members",
                mapper = { bodyText ->
                    parseSharedChannelMemberSnapshot(bodyText, channelId)
                        ?: throw IllegalStateException("Channel members response missing members")
                }
            )
        result.logChannelMemberCounts(source = "channel_members", channelId = channelId.value, logger = logger)
        if (result !is DataResult.Content) return result
        // Member capabilities belong to the channel response, never the roster.
        // Missing/failed detail keeps the readable roster and denies mutations.
        val detail = executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.request(priority, "channels/${channelId.value}") },
            source = "channel_member_capabilities",
            mapper = { body -> parseRaftChannelDetail(body)
                ?: throw IllegalStateException("Channel detail missing") },
        )
        val channel = (detail as? DataResult.Content)?.data
            ?.takeIf { it.channelId == channelId }
        val mutable = channel != null && !channel.archived &&
            !(channel.channelType == "channel" && channel.name.removePrefix("#") == "all")
        return DataResult.Content(result.data.copy(
            canAddMembers = mutable && channel?.channelCapabilities?.addChannelMembers == true,
            canRemoveMembers = mutable && channel?.channelCapabilities?.removeChannelMembers == true,
        ))
    }

    override suspend fun addChannelMember(
        priority: DataRequestPriority,
        request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> {
        currentContext() ?: return authRequired()
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.addChannelMember(priority, request.channelId, request.toBodyText()) },
            source = "channel_member_add",
            mapper = { bodyText ->
                parseSharedChannelMemberActionResult(bodyText, request.channelId, request.memberId, added = true)
                    ?: throw IllegalStateException("Channel member add response missing ok")
            }
        )
    }

    override suspend fun addChannelMembersBatch(
        priority: DataRequestPriority,
        request: AddChannelMembersBatchRequest
    ): DataResult<AddChannelMembersBatchResult> {
        currentContext() ?: return authRequired()
        if (request.userIds.isEmpty() && request.agentIds.isEmpty()) {
            return DataResult.Error(ScreenError(code = "empty_member_batch", retryable = false))
        }
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.addChannelMembersBatch(priority, request.channelId, request.toBodyText()) },
            source = "channel_member_batch_add",
            mapper = { bodyText ->
                parseSharedChannelMembersBatchResult(bodyText)
                    ?: throw IllegalStateException("Channel member batch response missing ok")
            }
        )
    }

    override suspend fun removeChannelMember(
        priority: DataRequestPriority,
        request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> {
        currentContext() ?: return authRequired()
        return executeMember(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.removeChannelMember(priority, request.channelId, request.kind, request.memberId) },
            source = "channel_member_remove",
            mapper = { bodyText ->
                parseSharedChannelMemberActionResult(bodyText, request.channelId, request.memberId, added = false)
                    ?: throw IllegalStateException("Channel member remove response missing ok")
            }
        )
    }

    private suspend fun currentContext(): SharedNetworkServerContext? {
        val session = sessionStore.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
    }

    private suspend fun executeAgentLifecycleAction(
        agentId: MemberId,
        action: String,
        block: suspend () -> DataResult<AgentLifecycleActionResult>
    ): DataResult<AgentLifecycleActionResult> {
        val accepted =
            agentLifecycleActionMutex.withLock {
                agentLifecycleActionsInFlight.add(agentId)
            }
        if (!accepted) {
            return DataResult.Error(ScreenError("agent_${action}_in_flight"))
        }
        return try {
            block()
        } finally {
            agentLifecycleActionMutex.withLock {
                agentLifecycleActionsInFlight.remove(agentId)
            }
        }
    }

    private suspend fun getHumanProfile(
        priority: DataRequestPriority,
        memberId: MemberId,
        serverId: ServerId,
        source: String
    ): DataResult<MemberProfile> =
        currentContext()?.let { context ->
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.memberProfile(priority, serverId, memberId) },
                source = source,
                mapper = { bodyText ->
                    parseSharedMemberProfile(bodyText)
                        ?: throw IllegalStateException("Member profile response missing member")
                }
            )
        } ?: authRequired()

    private suspend fun <T> executeMember(
        emptyReason: EmptyReason,
        block: suspend () -> HomeNetworkResponse,
        emptyWhen: (T) -> Boolean = { false },
        source: String? = null,
        mapper: (String) -> T
    ): DataResult<T> {
        var statusCode: Int? = null
        return try {
            val response = block()
            statusCode = response.code
            if (!response.isSuccessful) return response.toDataError()
            val body = response.bodyText.orEmpty()
            if (body.isBlank()) return DataResult.Empty(emptyReason)
            val result =
                parseResponseBodyWithTrace(
                    source = source ?: "member_response",
                    markerPrefix = "members",
                    body = body,
                    code = response.code,
                    hasBody = response.bodyText != null,
                    diagnosticsLogger = if (source != null) logger else ({ _: String -> }),
                    resultLabel = { parsed -> if (emptyWhen(parsed)) "empty" else "content" },
                    parser = { mapper(body) }
                )
            if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            if (source != null) {
                warningLogger(
                    "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
                )
            }
            DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
        }
    }
}

private fun <T> DataResult<T>.contentOrNull(): T? =
    (this as? DataResult.Content)?.data

private fun DataResult<AgentAppLoginSnapshot>.toAgentDetailAppLogins(agentId: MemberId): AgentAppLoginSnapshot =
    when (this) {
        is DataResult.Content -> data
        is DataResult.Empty ->
            AgentAppLoginSnapshot(
                agentId = agentId,
                loadState = AgentAppLoginLoadState.Empty
            )
        is DataResult.NoPermission ->
            AgentAppLoginSnapshot(
                agentId = agentId,
                loadState = AgentAppLoginLoadState.NoPermission,
                errorCode = blocker.code,
                permissionBlocker = blocker
            )
        is DataResult.Error ->
            AgentAppLoginSnapshot(
                agentId = agentId,
                loadState = AgentAppLoginLoadState.Error,
                errorCode = error.code,
                screenError = error
            )
    }

fun buildSharedAgentProfileUpdateBody(request: AgentProfileUpdateRequest): String {
    val displayName = request.displayName.trim()
    val description = request.description?.trim()?.takeIf { it.isNotBlank() }
    val body =
        buildJsonObject {
            put("name", displayName)
            put("displayName", displayName)
            if (description == null) {
                put("description", JsonNull)
            } else {
                put("description", description)
            }
            if (request.includeAvatarUrl) {
                val avatarUrl = request.avatarUrl?.trim()?.takeIf { it.isNotBlank() }
                if (avatarUrl == null) {
                    put("avatarUrl", JsonNull)
                } else {
                    put("avatarUrl", avatarUrl)
                }
            }
            if (request.includeServerRole) {
                val role = request.serverRole?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                if (role == null) {
                    put("serverRole", JsonNull)
                } else {
                    put("serverRole", role)
                }
            }
            if (request.includeRuntimeConfig) {
                val runtimeConfig = request.runtimeConfig
                if (runtimeConfig == null) {
                    put("runtimeConfig", JsonNull)
                    put("envVars", JsonNull)
                } else {
                    put(
                        "runtimeConfig",
                        runtimeConfig.toAgentUpdateJson(request.retainOmittedBuiltInProviderSecret)
                    )
                    put("runtime", runtimeConfig.runtime)
                    put("model", runtimeConfig.model.toAgentModelWireValue())
                    if (runtimeConfig.reasoningEffort == null) {
                        put("reasoningEffort", JsonNull)
                    } else {
                        put("reasoningEffort", runtimeConfig.reasoningEffort)
                    }
                    val envVars = runtimeConfig.envVars?.takeIf { it.isNotEmpty() }
                    if (envVars == null) {
                        put("envVars", JsonNull)
                    } else {
                        put("envVars", memberRepositoryJson.encodeToJsonElement(envVars))
                    }
                    // kimi-sdk (task #275): top-level exact form ref, mirroring
                    // the create wire. Never emitted without a runtimeConfig.
                    request.formDefinitionRef?.let { ref ->
                        put("formDefinitionRef", memberRepositoryJson.encodeToJsonElement(ref))
                    }
                }
                request.runtimeResetMode?.let { put("restartMode", it.wireValue) }
            } else if (request.runtimeFormV2 != null) {
                request.runtimeResetMode?.let { put("restartMode", it.wireValue) }
            }
        }
    // Runtime form v2 edit: the server assembles the runtimeConfig from field
    // values, so only the ref and formValues travel (never runtimeConfig).
    return request.runtimeFormV2?.let { body.withRuntimeFormV2(it) }?.toString() ?: body.toString()
}

private fun RuntimeModelConfig.toAgentModelWireValue(): String =
    when (this) {
        is RuntimeModelConfig.Preset -> id
        is RuntimeModelConfig.Custom -> name
    }

private fun build.raft.app.model.RuntimeConfig.toAgentUpdateJson(
    retainOmittedBuiltInProviderSecret: Boolean
): JsonElement {
    val encoded = agentRuntimeConfigUpdateJson.encodeToJsonElement(this).jsonObject
    if (!retainOmittedBuiltInProviderSecret || runtime != "builtin") return encoded
    val provider = encoded["provider"] as? JsonObject ?: return encoded
    val providerWithoutSecret =
        buildJsonObject {
            provider.forEach { (key, value) ->
                if (key != "apiKey") put(key, value)
            }
        }
    return buildJsonObject {
        encoded.forEach { (key, value) ->
            put(key, if (key == "provider") providerWithoutSecret else value)
        }
    }
}

@Serializable
data class AddAgentChannelMemberRequestBody(val agentId: String)

@Serializable
data class AddUserChannelMemberRequestBody(val userId: String)

private fun AddChannelMembersBatchRequest.toBodyText(): String =
    memberRepositoryJson.encodeToString(
        AddChannelMembersBatchRequestBody(
            userIds = userIds.map { it.value },
            agentIds = agentIds.map { it.value }
        )
    )

/**
 * Parse the atomic batch-add response. Returns null only when the envelope is
 * malformed (missing `ok`), mirroring [parseSharedChannelMemberActionResult].
 * The server dedupes and is idempotent, so `alreadyMembers`-only is a success.
 */
internal fun parseSharedChannelMembersBatchResult(bodyText: String): AddChannelMembersBatchResult? {
    val body = runCatching { memberRepositoryJson.decodeFromString<AddChannelMembersBatchResponseBody>(bodyText) }
        .getOrNull()
        ?: return null
    if (!body.ok) return null
    return AddChannelMembersBatchResult(
        addedUserIds = body.added?.userIds.orEmpty(),
        addedAgentIds = body.added?.agentIds.orEmpty(),
        alreadyMemberUserIds = body.alreadyMembers?.userIds.orEmpty(),
        alreadyMemberAgentIds = body.alreadyMembers?.agentIds.orEmpty()
    )
}

@Serializable
private data class AddChannelMembersBatchRequestBody(
    val userIds: List<String>,
    val agentIds: List<String>
)

@Serializable
private data class AddChannelMembersBatchResponseBody(
    val ok: Boolean = false,
    val added: AddChannelMembersBatchIds? = null,
    val alreadyMembers: AddChannelMembersBatchIds? = null
)

@Serializable
private data class AddChannelMembersBatchIds(
    val userIds: List<String> = emptyList(),
    val agentIds: List<String> = emptyList()
)

private fun AddChannelMemberRequest.toBodyText(): String =
    when (kind) {
        MemberKind.Agent -> memberRepositoryJson.encodeToString(AddAgentChannelMemberRequestBody(memberId.value))
        MemberKind.Human -> memberRepositoryJson.encodeToString(AddUserChannelMemberRequestBody(memberId.value))
        MemberKind.System -> memberRepositoryJson.encodeToString(AddUserChannelMemberRequestBody(memberId.value))
        // Unreachable: external projections can never be added as channel members.
        MemberKind.External -> memberRepositoryJson.encodeToString(AddUserChannelMemberRequestBody(memberId.value))
    }

private data class SharedNetworkServerContext(val session: StoredAuthSession, val server: ServerSummary)

@Serializable
private data class MemberErrorBody(val error: String? = null)

private fun StoredAuthSession.toNetworkServerContext(serverId: ServerId): SharedNetworkServerContext? {
    val requestedId = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val server = servers.firstOrNull { it.id.trim() == requestedId }
        ?.toServerSummary()
        ?.let { summary -> summary.copy(id = ServerId(summary.id.value.trim())) }
        ?: return null
    return SharedNetworkServerContext(session = this, server = server)
}

private fun authRequired(): DataResult.NoPermission =
    authSessionRequiredResult()

private fun authRequired(serverMessage: String?): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage)

private fun <T> HomeNetworkResponse.toDataError(): DataResult<T> {
    val serverMessage = memberServerErrorTextOrNull()
    return when (code) {
        401 -> authRequired(serverMessage)
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
                ScreenError(
                    code = "server_unavailable",
                    retryable = true,
                    serverMessage = serverMessage
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = "http_$code",
                    retryable = code != 404,
                    serverMessage = serverMessage,
                    formFieldKeys = runtimeFormV2ServerError(bodyText ?: errorBodyText)?.fieldKeys.orEmpty()
                )
            )
    }
}

private fun HomeNetworkResponse.memberServerErrorTextOrNull(): String? {
    val payload = (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        memberRepositoryJson.decodeFromString(MemberErrorBody.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun DataResult<AgentCommandResult>.toLifecycleActionResult(
    agentId: MemberId,
    action: String,
    resetMode: AgentResetMode? = null,
    machineId: String? = null
): DataResult<AgentLifecycleActionResult> =
    when (this) {
        is DataResult.Content ->
            DataResult.Content(
                AgentLifecycleActionResult(
                    agentId = agentId,
                    success = data.ok,
                    action = action,
                    resetMode = resetMode,
                    machineId = machineId
                )
            )
        is DataResult.Empty -> DataResult.Empty(reason)
        is DataResult.Error -> DataResult.Error(error)
        is DataResult.NoPermission -> DataResult.NoPermission(blocker)
    }

private fun mergeAgentActivityEntries(incoming: List<AgentActivityEntry>): List<AgentActivityEntry> {
    val merged = LinkedHashMap<String, AgentActivityEntry>()
    for (entry in incoming) {
        merged[entry.activityCacheKey] = entry
    }
    return merged.values.sortedWith(compareBy<AgentActivityEntry> { it.timestamp.orEmpty() }.thenBy { it.id })
}

private val AgentActivityEntry.activityCacheKey: String
    get() = id.ifBlank { listOf(type, timestamp.orEmpty(), body.orEmpty()).joinToString(":") }

private fun String.toMemberComputerBadge(): String = "$MemberComputerBadgePrefix$this"
private fun String.memberComputerLabelOrNull(): String? =
    removePrefix(MemberComputerBadgePrefix).takeIf { startsWith(MemberComputerBadgePrefix) && it.isNotBlank() }
private fun String.memberComputerIdOrNull(): String? =
    removePrefix(MemberComputerIdBadgePrefix).takeIf { startsWith(MemberComputerIdBadgePrefix) && it.isNotBlank() }

private const val MemberComputerBadgePrefix = "computer:"
private const val MemberComputerIdBadgePrefix = "computer-id:"

/**
 * Task #148: names the agents whose list row reaches the members page without a computer id
 * (hashed), so a device run can tell whether the id is lost before or after this merge.
 */
private fun logAgentsWithoutComputer(logger: (String) -> Unit, serverId: ServerId, agents: List<MemberProfile>) {
    val missing = agents.filter { agent ->
        agent.kind == MemberKind.Agent && agent.permissionBadges.none { it.memberComputerIdOrNull() != null }
    }
    logger(
        "repository_agents_computer serverId=${serverId.value} agents=${agents.size} missingComputerId=${missing.size}" +
            missing.joinToString(separator = ",", prefix = " missing=[", postfix = "]") {
                it.id.value.raftAvatarDiagnosticsHash()
            }
    )
}

private fun List<MemberProfile>.resolveComputerLabels(machineNames: Map<String, String>): List<MemberProfile> =
    map { it.resolveComputerLabel(machineNames) }

private fun MemberProfile.resolveComputerLabel(machineNames: Map<String, String>): MemberProfile {
    val currentComputerLabel = permissionBadges.firstNotNullOfOrNull { it.memberComputerLabelOrNull() }
    if (!currentComputerLabel.isNullOrBlank()) return this
    val machineId = permissionBadges.firstNotNullOfOrNull { it.memberComputerIdOrNull() } ?: return this
    val resolvedName = machineNames[machineId]?.takeIf { it.isNotBlank() } ?: return this
    // Task #409: keep the `computer-id:` badge — the members page uses it as
    // the collapse-persistence key, stable across machine renames (same reason
    // web keys agent-machine groups by machineId). Before this fix the badge
    // was stripped on merge and the id never reached the list.
    return copy(permissionBadges = permissionBadges + resolvedName.toMemberComputerBadge())
}

private val MemberProfile.memberKey: String
    get() = "${kind.name}:${id.value}"

private val MemberKind.channelMemberPathSegment: String
    get() =
        when (this) {
            MemberKind.Agent -> "agent"
            MemberKind.Human -> "user"
            MemberKind.System -> "system"
            MemberKind.External -> "external"
        }

private fun DataResult<MemberSnapshot>.logMemberCounts(
    source: String,
    serverId: String,
    logger: (String) -> Unit
) {
    val members = (this as? DataResult.Content)?.data?.members.orEmpty()
    logger(
        "members_count source=$source serverId=$serverId result=${memberResultName()} " +
            "agentsCount=${members.count { it.kind == MemberKind.Agent }} humansCount=${members.count { it.kind == MemberKind.Human }}"
    )
}

private fun DataResult<ChannelMemberSnapshot>.logChannelMemberCounts(
    source: String,
    channelId: String,
    logger: (String) -> Unit
) {
    val members = (this as? DataResult.Content)?.data?.members.orEmpty()
    logger(
        "members_count source=$source channelId=$channelId result=${memberResultName()} " +
            "agentsCount=${members.count { it.kind == MemberKind.Agent }} humansCount=${members.count { it.kind == MemberKind.Human }}"
    )
}

private fun DataResult<*>.memberResultName(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "noPermission:${blocker.code}"
    }
