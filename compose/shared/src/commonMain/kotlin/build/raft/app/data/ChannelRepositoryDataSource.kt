package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.strictMemberKindOrNull
import build.raft.app.toRaftJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val channelRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun RaftNetworkClient.channels(
    priority: DataRequestPriority,
    serverId: ServerId? = null
): HomeNetworkResponse =
    request(priority, "channels", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.directMessages(
    priority: DataRequestPriority,
    serverId: ServerId? = null
): HomeNetworkResponse =
    request(priority, "channels/dm", serverIdOverride = serverId)

private suspend fun RaftNetworkClient.channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "channels/unread")

private suspend fun RaftNetworkClient.serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "servers/unread-summary")

private suspend fun RaftNetworkClient.createChannel(priority: DataRequestPriority, bodyText: String): HomeNetworkResponse =
    request(priority, "channels", method = "POST", bodyText = bodyText)

private suspend fun RaftNetworkClient.joinChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/join", method = "POST")

private suspend fun RaftNetworkClient.leaveChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/leave", method = "POST")

private suspend fun RaftNetworkClient.archiveChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/archive", method = "POST")

private suspend fun RaftNetworkClient.deleteChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}", method = "DELETE")

internal suspend fun RaftNetworkClient.updateChannel(
    priority: DataRequestPriority,
    channelId: ChannelId,
    visibility: ChannelVisibility
): HomeNetworkResponse =
    request(
        priority,
        "channels/${channelId.value}",
        method = "PATCH",
        bodyText = buildJsonObject { put("visibility", visibility.wireValue) }.toRaftJsonString()
    )

private suspend fun RaftNetworkClient.updateChannelProfile(
    priority: DataRequestPriority,
    request: ChannelProfileUpdateRequest
): HomeNetworkResponse =
    request(
        priority,
        "channels/${request.channelId.value}",
        method = "PATCH",
        bodyText = buildChannelProfileUpdateBody(request)
    )

private suspend fun RaftNetworkClient.openDirectMessage(priority: DataRequestPriority, bodyText: String): HomeNetworkResponse =
    request(priority, "channels/dm", method = "POST", bodyText = bodyText)

private suspend fun RaftNetworkClient.setChannelActivityMuted(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/notification-settings", method = "PATCH", bodyText = bodyText)

class RealChannelRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val warningLogger: (String) -> Unit = {}
) : ChannelRepository {
    private val homeDataSource =
        SharedHomeDataSource(
            sessionStore = sessionStore,
            api = NetworkRaftHomeApi(networkClient),
            parseHome = { payload, session ->
                parseRaftHome(payload, session, warningLogger)
            },
            diagnosticsLogger = { message -> warningLogger("home_data_source $message") }
        )

    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> =
        homeDataSource.loadHome(serverId, DataRequestPriority.Visible)

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        return fetchChannels(context)
    }

    override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        return fetchDirectMessages(context)
    }

    override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> =
        openDirectMessage(
            memberId = agentId,
            expectedPeerKind = MemberKind.Agent,
            bodyText = buildOpenAgentDirectMessageBody(agentId),
            source = "dm_open_agent"
        )

    override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> =
        openDirectMessage(
            memberId = userId,
            expectedPeerKind = MemberKind.Human,
            bodyText = buildOpenUserDirectMessageBody(userId),
            source = "dm_open_user"
        )

    override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> {
        currentContext() ?: return authRequired()
        val name = request.name.trim()
        if (name.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        if (!name.isSubmittableChannelName()) {
            return DataResult.Error(ScreenError(code = "channel_name_invalid", retryable = false))
        }
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.createChannel(DataRequestPriority.Foreground, buildCreateChannelBody(request)) },
            source = "channel_create",
            mapper = { bodyText ->
                val result =
                    parseSharedCreateChannelResult(bodyText)
                        ?: throw IllegalStateException("Create channel response missing channel")
                result.copy(
                    addedAgentMemberIds = request.agentMemberIds.cleanCreateChannelIds(),
                    addedUserMemberIds = request.userMemberIds.cleanCreateChannelIds()
                )
            }
        )
    }

    override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> {
        currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.joinChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_join",
            mapper = { bodyText ->
                parseSharedChannelJoinResult(bodyText, channelId)
                    ?: throw IllegalStateException("Join channel response missing ok")
            }
        )
    }

    override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> {
        currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.leaveChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_leave",
            mapper = { bodyText ->
                parseSharedChannelLeaveResult(bodyText, channelId)
                    ?: throw IllegalStateException("Leave channel response missing ok")
            }
        )
    }

    override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> {
        currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.archiveChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_archive",
            mapper = { bodyText ->
                parseSharedChannelArchiveResult(bodyText, channelId)
                    ?: throw IllegalStateException("Archive channel response missing channel")
            }
        )
    }

    override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> {
        currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.deleteChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_delete",
            mapper = { bodyText ->
                parseSharedChannelDeleteResult(bodyText, channelId)
                    ?: throw IllegalStateException("Delete channel response missing ok")
            }
        )
    }

    override suspend fun updateChannel(channelId: ChannelId, visibility: ChannelVisibility): DataResult<ChannelUpdateResult> {
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.updateChannel(DataRequestPriority.Foreground, channelId, visibility) },
            source = "channel_update_visibility",
            mapper = { ChannelUpdateResult(channelId) }
        )
    }

    override suspend fun updateChannelProfile(
        request: ChannelProfileUpdateRequest
    ): DataResult<ChannelProfileUpdateResult> {
        currentContext() ?: return authRequired()
        val channelId = request.channelId
        val name = request.name.trim()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        if (!name.isSubmittableChannelName()) {
            return DataResult.Error(ScreenError(code = "channel_name_invalid", retryable = false))
        }
        val canonicalRequest = request.copy(name = name, description = request.description.trim())
        if (canonicalRequest.description.length > 500) {
            return DataResult.Error(ScreenError(code = "channel_description_too_long", retryable = false))
        }
        val result = executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = {
                networkClient.updateChannelProfile(
                    DataRequestPriority.Foreground,
                    canonicalRequest
                )
            },
            source = "channel_update_profile",
            mapper = { bodyText ->
                parseSharedChannelProfileUpdateResult(bodyText)
                    ?: throw IllegalStateException("Update channel response missing channel")
            }
        )
        val content = result as? DataResult.Content<ChannelProfileUpdateResult> ?: return result
        if (content.data.channelId != channelId) {
            warningLogger(
                "channel_update_profile_identity_mismatch requested=${channelId.value} " +
                    "response=${content.data.channelId.value}"
            )
            return DataResult.Error(
                ScreenError(code = "channel_update_identity_mismatch", retryable = false)
            )
        }
        return content
    }

    override suspend fun setChannelActivityMuted(
        channelId: ChannelId,
        muted: Boolean
    ): DataResult<ChannelActivityMuteSettings> {
        currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = {
                networkClient.setChannelActivityMuted(
                    DataRequestPriority.Foreground,
                    channelId,
                    buildChannelActivityMuteBody(muted)
                )
            },
            source = "channel_activity_mute",
            mapper = { bodyText ->
                channelRepositoryJson
                    .decodeFromString(ChannelActivityMuteSettingsResponse.serializer(), bodyText)
                    .toSettings(channelId)
            }
        )
    }

    private suspend fun fetchChannels(context: SharedChannelServerContext): DataResult<ChannelSnapshot> =
        executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.channels(DataRequestPriority.Visible, context.server.id) },
            emptyWhen = { it.channels.isEmpty() },
            source = "home_channels",
            mapper = { bodyText ->
                val parsed =
                    parseSharedChannelSnapshot(bodyText, context.server)
                        ?: throw IllegalStateException("Channels response missing channel array")
                parsed.copy(channels = parsed.channels.dedupeChannelRows())
            }
        )

    private suspend fun fetchDirectMessages(context: SharedChannelServerContext): DataResult<DirectMessageSnapshot> =
        executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.directMessages(DataRequestPriority.Visible, context.server.id) },
            emptyWhen = { it.directMessages.isEmpty() },
            source = "home_direct_messages",
            mapper = { bodyText ->
                val parsed =
                    parseSharedDirectMessageSnapshot(bodyText, context.server, warningLogger)
                        ?: throw IllegalStateException("Direct messages response missing dm array")
                parsed.copy(directMessages = parsed.directMessages.dedupeDirectMessageRows())
            }
        )

    private suspend fun openDirectMessage(
        memberId: MemberId,
        expectedPeerKind: MemberKind,
        bodyText: String,
        source: String
    ): DataResult<OpenDirectMessageResult> {
        currentContext() ?: return authRequired()
        if (memberId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.openDirectMessage(DataRequestPriority.Foreground, bodyText) },
            source = source,
            mapper = { responseBody ->
                val result =
                    parseSharedOpenDirectMessageResult(responseBody)
                        ?: throw IllegalStateException("Open DM response missing channel")
                val requestedPeerId = memberId.value.trim()
                val actualPeerId = result.conversation.presenceMemberId?.value?.trim()
                val actualPeerKind =
                    result.conversation.directMessagePeerType.strictMemberKindOrNull(
                        source = source,
                        diagnostics = warningLogger,
                        contract = MemberKindWireContract.InternalActor
                    )
                val channelId = result.conversation.channelId.value.trim()
                if (
                    channelId.isBlank() ||
                    actualPeerId != requestedPeerId ||
                    actualPeerKind != expectedPeerKind
                ) {
                    warningLogger(
                        "dm_open_identity_mismatch source=$source " +
                            "requestedPeerId=$requestedPeerId requestedPeerKind=${expectedPeerKind.name} " +
                            "actualPeerId=${actualPeerId ?: "none"} actualPeerKind=${actualPeerKind?.name ?: "none"} " +
                            "channelId=${channelId.ifBlank { "none" }}"
                    )
                    throw IllegalStateException("Open DM response peer identity mismatch")
                }
                result
            }
        )
    }

    private suspend fun serverContext(serverId: ServerId): SharedChannelServerContext? =
        sessionStore.readSession()?.toChannelServerContext(serverId)

    private suspend fun currentContext(): SharedChannelServerContext? {
        val session = sessionStore.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toChannelServerContext(ServerId(selectedId))
    }

    private suspend fun <T> executeChannel(
        emptyReason: EmptyReason,
        block: suspend () -> HomeNetworkResponse,
        emptyWhen: (T) -> Boolean = { false },
        source: String,
        mapper: suspend (String) -> T
    ): DataResult<T> {
        var statusCode: Int? = null
        return try {
            val response = block()
            statusCode = response.code
            if (!response.isSuccessful) return response.toChannelDataError()
            val body = response.bodyText.orEmpty()
            if (body.isBlank()) return DataResult.Empty(emptyReason)
            val result = mapper(body)
            if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
            )
            DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
        }
    }

}

internal class NetworkRaftHomeApi(
    private val networkClient: RaftNetworkClient
) : RaftHomeApi {
    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.channels(priority)

    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.directMessages(priority)

    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.channelUnreadCounts(priority)

    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.serverUnreadSummary(priority)

    override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "channels", serverIdOverride = serverId)

    override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "channels/dm", serverIdOverride = serverId)

    override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "channels/unread", serverIdOverride = serverId)

    override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "servers/unread-summary", serverIdOverride = serverId)
}

@Serializable
data class CreateChannelRequestBody(
    val name: String,
    val description: String? = null,
    val visibility: String = "public",
    val agentIds: List<String> = emptyList(),
    val userIds: List<String> = emptyList(),
    val targetServerSlug: String? = null,
    val invitedPeople: List<String> = emptyList()
)

private fun buildCreateChannelBody(request: CreateChannelRequest): String =
    channelRepositoryJson.encodeToString(
        CreateChannelRequestBody(
            name = request.name.trim(),
            description = request.description?.trim()?.takeIf { it.isNotBlank() },
            visibility = request.visibility.trim().ifBlank { "public" },
            agentIds = request.agentMemberIds.cleanCreateChannelIds(),
            userIds = request.userMemberIds.cleanCreateChannelIds(),
            targetServerSlug = request.targetServerSlug?.trim()?.takeIf { it.isNotBlank() },
            invitedPeople = request.invitedPeople.cleanCreateChannelIds()
        )
    )

@Serializable
private data class ChannelProfileUpdateRequestBody(
    val name: String,
    val description: String
)

private fun buildChannelProfileUpdateBody(request: ChannelProfileUpdateRequest): String =
    channelRepositoryJson.encodeToString(
        ChannelProfileUpdateRequestBody(
            name = request.name.trim(),
            description = request.description.trim()
        )
    )

@Serializable
data class OpenAgentDirectMessageRequestBody(val agentId: String)

@Serializable
data class OpenUserDirectMessageRequestBody(val userId: String)

@Serializable
private data class ChannelActivityMuteRequestBody(val activityMuted: Boolean)

@Serializable
private data class ChannelActivityMuteSettingsResponse(
    val activityMuted: Boolean,
    val muteFromSeq: Long? = null,
    val activityMuteSupported: Boolean = false,
    val prefsVersion: Long? = null
) {
    fun toSettings(channelId: ChannelId): ChannelActivityMuteSettings =
        ChannelActivityMuteSettings(
            channelId = channelId,
            activityNotificationPreference =
                ChannelActivityNotificationPreference(
                    activityMuted = activityMuted,
                    muteFromSeq = muteFromSeq,
                    activityMuteSupported = activityMuteSupported
                ),
            prefsVersion = prefsVersion
        )
}

private fun buildOpenAgentDirectMessageBody(agentId: MemberId): String =
    channelRepositoryJson.encodeToString(OpenAgentDirectMessageRequestBody(agentId.value))

private fun buildOpenUserDirectMessageBody(userId: MemberId): String =
    channelRepositoryJson.encodeToString(OpenUserDirectMessageRequestBody(userId.value))

private fun buildChannelActivityMuteBody(muted: Boolean): String =
    channelRepositoryJson.encodeToString(ChannelActivityMuteRequestBody(muted))

@Serializable
private data class ChannelErrorBody(
    val code: String? = null,
    val error: String? = null
)

private data class SharedChannelServerContext(
    val session: StoredAuthSession,
    val server: ServerSummary
)

private fun StoredAuthSession.toChannelServerContext(serverId: ServerId): SharedChannelServerContext? {
    val requestedId = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val server = servers.firstOrNull { it.id == requestedId }?.toServerSummary() ?: return null
    return SharedChannelServerContext(session = this, server = server)
}

private fun authRequired(): DataResult.NoPermission =
    authSessionRequiredResult()

private fun authRequired(serverMessage: String?): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage)

private fun <T> HomeNetworkResponse.toChannelDataError(): DataResult<T> {
    val serverMessage = channelServerErrorTextOrNull()
    return when (code) {
        401 -> authRequired(serverMessage)
        403 -> DataResult.NoPermission(
            PermissionBlocker(
                code = channelErrorCode("server_forbidden"),
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
                    code = channelErrorCode("http_$code"),
                    retryable = false,
                    serverMessage = serverMessage
                )
            )
    }
}

private fun HomeNetworkResponse.channelErrorCode(fallback: String): String {
    val payload = bodyText ?: errorBodyText ?: return fallback
    return runCatching {
        val errorBody = channelRepositoryJson.decodeFromString(ChannelErrorBody.serializer(), payload)
        errorBody.code
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: errorBody.error.toChannelValidationErrorCode()
    }.getOrNull() ?: fallback
}

private fun HomeNetworkResponse.channelServerErrorTextOrNull(): String? {
    val payload = (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        channelRepositoryJson.decodeFromString(ChannelErrorBody.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun String?.toChannelValidationErrorCode(): String? {
    val normalized = this?.trim()?.lowercase().orEmpty()
    return when {
        "channel name must start with a letter" in normalized -> "channel_name_invalid"
        else -> null
    }
}

private fun List<String>.cleanCreateChannelIds(): List<String> =
    mapNotNull { it.trim().takeIf(String::isNotBlank) }.distinct()

private fun List<HomeChannelRow>.dedupeChannelRows(): List<HomeChannelRow> {
    val deduped = LinkedHashMap<String, HomeChannelRow>()
    for (conversation in this) {
        val key = conversation.homeCacheKey
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}

private fun List<HomeDirectMessageRow>.dedupeDirectMessageRows(): List<HomeDirectMessageRow> {
    val deduped = LinkedHashMap<String, HomeDirectMessageRow>()
    for (conversation in this) {
        val key = conversation.homeCacheKey
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}

private val HomeConversationRow.homeCacheKey: String
    get() =
        if (kind == ConversationKind.Thread) threadId.value
        else channelId?.value ?: threadId.value
