package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceNode
import ai.slock.compose.network.KmpHttpResponse
import ai.slock.compose.network.MultipartFile
import ai.slock.compose.network.SlockApiClient
import ai.slock.compose.network.SlockRequestPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val memberRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

interface SlockNetworkClient {
    suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        serverIdOverride: ServerId? = null,
        bodyText: String? = null
    ): HomeNetworkResponse

    suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse =
        HomeNetworkResponse(code = 501, bodyText = null)
}

class KtorSlockNetworkClient(
    private val client: SlockApiClient
) : SlockNetworkClient {
    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse =
        client.authenticatedRequest(
            priority = priority.toNetworkPriority(),
            method = method,
            path = path,
            query = query,
        body = bodyText
    ).toHomeNetworkResponse()

    override suspend fun uploadAttachments(
        priority: DataRequestPriority,
        channelId: ChannelId,
        files: List<MultipartFile>
    ): HomeNetworkResponse =
        client.uploadAttachments(
            priority = priority.toNetworkPriority(),
            channelId = channelId.value,
            files = files
        ).toHomeNetworkResponse()
}

private suspend fun SlockNetworkClient.serverMembers(priority: DataRequestPriority, serverId: ServerId): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/members", serverIdOverride = serverId)

private suspend fun SlockNetworkClient.settingsAgents(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "agents")

private suspend fun SlockNetworkClient.settingsMachines(priority: DataRequestPriority, serverId: ServerId): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/machines", serverIdOverride = serverId)

private suspend fun SlockNetworkClient.updateAgent(priority: DataRequestPriority, agentId: MemberId, bodyText: String): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}", method = "PATCH", bodyText = bodyText)

private suspend fun SlockNetworkClient.memberProfile(
    priority: DataRequestPriority,
    serverId: ServerId,
    memberId: MemberId
): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/members/${memberId.value}/profile", serverIdOverride = serverId)

private suspend fun SlockNetworkClient.agentDirectMessages(priority: DataRequestPriority, agentId: MemberId): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/agent-dms")

private suspend fun SlockNetworkClient.reminders(
    priority: DataRequestPriority,
    ownerAgentId: MemberId,
    status: String = "scheduled"
): HomeNetworkResponse =
    request(priority, "reminders", query = listOf("ownerAgentId" to ownerAgentId.value, "status" to status))

private suspend fun SlockNetworkClient.agentActivityLog(
    priority: DataRequestPriority,
    agentId: MemberId,
    limit: Int = 50
): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/activity-log", query = listOf("limit" to limit.toString()))

private suspend fun SlockNetworkClient.settingsMachineWorkspaces(
    priority: DataRequestPriority,
    serverId: ServerId,
    machineId: String
): HomeNetworkResponse =
    request(priority, "servers/${serverId.value}/machines/$machineId/workspaces", serverIdOverride = serverId)

private suspend fun SlockNetworkClient.agentWorkspaceFiles(
    priority: DataRequestPriority,
    agentId: MemberId,
    dirPath: String? = null
): HomeNetworkResponse =
    request(
        priority,
        "agents/${agentId.value}/workspace-files",
        query = listOfNotNull(dirPath?.let { "dirPath" to it })
    )

private suspend fun SlockNetworkClient.readAgentWorkspaceFile(
    priority: DataRequestPriority,
    agentId: MemberId,
    path: String
): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/workspace-files/read", query = listOf("path" to path))

private suspend fun SlockNetworkClient.agentSkills(priority: DataRequestPriority, agentId: MemberId): HomeNetworkResponse =
    request(priority, "agents/${agentId.value}/skills")

private suspend fun SlockNetworkClient.channelMembers(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members")

private suspend fun SlockNetworkClient.addChannelMember(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.removeChannelMember(
    priority: DataRequestPriority,
    channelId: ChannelId,
    kind: MemberKind,
    memberId: MemberId
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members/${kind.channelMemberPathSegment}/${memberId.value}", method = "DELETE")

class RealMemberRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val warningLogger: (String) -> Unit = {},
    private val logger: (String) -> Unit = {}
) : MemberRepository {
    private val agentActivityCacheMutex = Mutex()
    private val agentActivityCache = mutableMapOf<MemberId, List<AgentActivityEntry>>()

    override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
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
                block = { networkClient.settingsAgents(priority) },
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
                    val machineNames = (machineNameResult as? DataResult.Content)?.data.orEmpty()
                    val agents = (agentResult as? DataResult.Content)?.data.orEmpty().resolveComputerLabels(machineNames)
                    val mergedMembers = (result.data.members + agents).distinctBy { it.memberKey }
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
                "agentsCount=${(agentResult as? DataResult.Content)?.data?.count { it.kind == MemberKind.Agent } ?: 0}"
        )
        return mergedResult
    }

    override suspend fun getMemberProfile(priority: DataRequestPriority, memberId: MemberId): DataResult<MemberProfile> {
        currentContext() ?: return authRequired()
        return getHumanProfile(priority, memberId, source = "member_profile")
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

    override suspend fun getAgentDetail(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentDetailSnapshot> {
        currentContext() ?: return authRequired()
        val directMessages = getAgentDirectMessages(priority, agentId)
        val reminders = getAgentReminders(priority, agentId)
        val activity = getAgentActivity(priority, agentId)
        val skills = getAgentSkills(priority, agentId)
        return when {
            directMessages is DataResult.Content &&
                reminders is DataResult.Content &&
                activity is DataResult.Content &&
                skills is DataResult.Content ->
                DataResult.Content(
                    AgentDetailSnapshot(
                        agentId = agentId,
                        directMessages = directMessages.data.directMessages,
                        reminders = reminders.data.reminders,
                        activity = activity.data.activity,
                        skills = skills.data
                    )
                )
            directMessages is DataResult.NoPermission -> directMessages
            reminders is DataResult.NoPermission -> reminders
            activity is DataResult.NoPermission -> activity
            skills is DataResult.NoPermission -> skills
            directMessages is DataResult.Error -> directMessages
            reminders is DataResult.Error -> reminders
            activity is DataResult.Error -> activity
            skills is DataResult.Error -> skills
            directMessages is DataResult.Empty &&
                reminders is DataResult.Empty &&
                activity is DataResult.Empty &&
                skills is DataResult.Empty -> DataResult.Empty(EmptyReason.NoMembers)
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

    override suspend fun getAgentActivity(priority: DataRequestPriority, agentId: MemberId): DataResult<AgentActivitySnapshot> {
        currentContext() ?: return authRequired()
        val logResult =
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.agentActivityLog(priority, agentId, limit = 50) },
                source = "agent_activity",
                mapper = { bodyText ->
                    val snapshot = parseSharedAgentActivity(bodyText, agentId)
                        ?: throw IllegalStateException("Agent activity response missing log array")
                    logger("agent_activity_log_fetch agentId=${agentId.value} mappedCount=${snapshot.activity.size}")
                    snapshot
                }
            )
        val fallbackEntries = fetchAgentCurrentActivityFallback(priority, agentId)
        return when (logResult) {
            is DataResult.Content -> {
                DataResult.Content(logResult.data.copy(activity = mergeAgentActivity(agentId, fallbackEntries + logResult.data.activity)))
            }
            is DataResult.Empty -> {
                if (fallbackEntries.isNotEmpty()) {
                    DataResult.Content(AgentActivitySnapshot(agentId = agentId, activity = mergeAgentActivity(agentId, fallbackEntries)))
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
                    root = SlockWorkspaceNode(
                        id = "/",
                        name = "workspace",
                        path = "/",
                        kind = SlockWorkspaceFileKind.Folder,
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

    override suspend fun getChannelMembers(priority: DataRequestPriority, channelId: ChannelId): DataResult<ChannelMemberSnapshot> {
        currentContext() ?: return authRequired()
        val result =
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.channelMembers(priority, channelId) },
                emptyWhen = { it.members.isEmpty() },
                source = "channel_members",
                mapper = { bodyText ->
                    parseSharedChannelMemberSnapshot(bodyText, channelId)
                        ?: throw IllegalStateException("Channel members response missing members")
                }
            )
        result.logChannelMemberCounts(source = "channel_members", channelId = channelId.value, logger = logger)
        return result
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

    private suspend fun mergeAgentActivity(agentId: MemberId, incoming: List<AgentActivityEntry>): List<AgentActivityEntry> =
        agentActivityCacheMutex.withLock {
            val merged = mergeAgentActivityEntries(agentActivityCache[agentId].orEmpty(), incoming)
            agentActivityCache[agentId] = merged
            merged
        }

    private suspend fun fetchAgentCurrentActivityFallback(priority: DataRequestPriority, agentId: MemberId): List<AgentActivityEntry> {
        val result =
            executeMember<List<AgentActivityEntry>>(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.settingsAgents(priority) },
                source = "agent_activity_current_fallback",
                mapper = { bodyText ->
                    val entries = parseSharedCurrentAgentActivityEntries(bodyText, agentId)
                        ?: throw IllegalStateException("Current agent activity response missing agent array")
                    logger("agent_activity_fallback_fetch agentId=${agentId.value} mappedCount=${entries.size}")
                    entries
                }
            )
        return (result as? DataResult.Content)?.data.orEmpty()
    }

    private suspend fun getHumanProfile(
        priority: DataRequestPriority,
        memberId: MemberId,
        source: String
    ): DataResult<MemberProfile> =
        currentContext()?.let { context ->
            executeMember(
                emptyReason = EmptyReason.NoMembers,
                block = { networkClient.memberProfile(priority, context.server.id, memberId) },
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
            val result = mapper(body)
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

@Serializable
data class AgentProfileUpdateRequestBody(
    val name: String,
    val displayName: String,
    val description: String? = null
)

fun buildSharedAgentProfileUpdateBody(request: AgentProfileUpdateRequest): String {
    val displayName = request.displayName.trim()
    val description = request.description?.trim()?.takeIf { it.isNotBlank() }
    return memberRepositoryJson.encodeToString(
        AgentProfileUpdateRequestBody(
            name = displayName,
            displayName = displayName,
            description = description
        )
    )
}

@Serializable
data class AddAgentChannelMemberRequestBody(val agentId: String)

@Serializable
data class AddUserChannelMemberRequestBody(val userId: String)

private fun AddChannelMemberRequest.toBodyText(): String =
    when (kind) {
        MemberKind.Agent -> memberRepositoryJson.encodeToString(AddAgentChannelMemberRequestBody(memberId.value))
        MemberKind.Human -> memberRepositoryJson.encodeToString(AddUserChannelMemberRequestBody(memberId.value))
        MemberKind.System -> memberRepositoryJson.encodeToString(AddUserChannelMemberRequestBody(memberId.value))
    }

private data class SharedNetworkServerContext(val session: StoredAuthSession, val server: ServerSummary)

private fun StoredAuthSession.toNetworkServerContext(serverId: ServerId): SharedNetworkServerContext? {
    val selectedId = currentServerId ?: return null
    if (selectedId != serverId.value) return null
    val server = servers.firstOrNull { it.id == selectedId }?.toServerSummary() ?: return null
    return SharedNetworkServerContext(session = this, server = server)
}

private fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))

private fun <T> HomeNetworkResponse.toDataError(): DataResult<T> =
    when (code) {
        401 -> authRequired()
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404))
    }

private fun DataRequestPriority.toNetworkPriority(): SlockRequestPriority =
    when (this) {
        DataRequestPriority.Foreground -> SlockRequestPriority.Foreground
        DataRequestPriority.Visible -> SlockRequestPriority.Visible
        DataRequestPriority.Background -> SlockRequestPriority.Background
    }

private fun KmpHttpResponse.toHomeNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(code = code, bodyText = bodyText, errorBodyText = errorBodyText)

private fun mergeAgentActivityEntries(existing: List<AgentActivityEntry>, incoming: List<AgentActivityEntry>): List<AgentActivityEntry> {
    val merged = LinkedHashMap<String, AgentActivityEntry>()
    for (entry in existing) {
        merged[entry.activityCacheKey] = entry
    }
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

private fun List<MemberProfile>.resolveComputerLabels(machineNames: Map<String, String>): List<MemberProfile> =
    map { it.resolveComputerLabel(machineNames) }

private fun MemberProfile.resolveComputerLabel(machineNames: Map<String, String>): MemberProfile {
    val badgesWithoutIds = permissionBadges.filterNot { it.startsWith(MemberComputerIdBadgePrefix) }
    val currentComputerLabel = permissionBadges.firstNotNullOfOrNull { it.memberComputerLabelOrNull() }
    if (!currentComputerLabel.isNullOrBlank()) {
        return if (badgesWithoutIds == permissionBadges) this else copy(permissionBadges = badgesWithoutIds)
    }
    val machineId = permissionBadges.firstNotNullOfOrNull { it.memberComputerIdOrNull() } ?: return this
    val resolvedName = machineNames[machineId]?.takeIf { it.isNotBlank() }
    return if (resolvedName != null) {
        copy(permissionBadges = badgesWithoutIds + resolvedName.toMemberComputerBadge())
    } else if (badgesWithoutIds == permissionBadges) {
        this
    } else {
        copy(permissionBadges = badgesWithoutIds)
    }
}

private val MemberProfile.memberKey: String
    get() = "${kind.name}:${id.value}"

private val MemberKind.channelMemberPathSegment: String
    get() =
        when (this) {
            MemberKind.Agent -> "agent"
            MemberKind.Human -> "user"
            MemberKind.System -> "system"
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
