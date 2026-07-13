package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.messageStreamChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

private val forwardTargetJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

enum class ForwardMessageTargetKind {
    RecentChannel,
    RecentDirectMessage,
    PublicChannelUnjoined,
    HumanDirectMessageCandidate,
    AgentDirectMessageCandidate
}

enum class ForwardMessageTargetRequiredAction(val wireValue: String?) {
    None(null),
    JoinChannel("join_channel"),
    CreateDirectMessage("create_dm")
}

enum class ForwardMessageSendMode(val wireValue: String) {
    Merged("merged"),
    Individual("individual")
}

data class ForwardMessageTarget(
    val kind: ForwardMessageTargetKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.take(1).uppercase(),
    val channelId: ChannelId? = null,
    val memberId: MemberId? = null,
    val joined: Boolean? = null,
    val dmExists: Boolean? = null,
    val canForwardNow: Boolean = channelId != null,
    val requiredAction: ForwardMessageTargetRequiredAction = ForwardMessageTargetRequiredAction.None
)

data class ForwardMessageExecuteRequest(
    val sourceChannelId: ChannelId,
    val targetChannelId: ChannelId,
    val messageIds: List<String>,
    val mode: ForwardMessageSendMode,
    val note: String = ""
)

interface ForwardMessageTargetRepository {
    suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>>
    suspend fun searchTargets(serverId: ServerId, query: String, limit: Int): DataResult<List<ForwardMessageTarget>>
    suspend fun resolveTargetChannel(target: ForwardMessageTarget, allowJoin: Boolean = false): DataResult<ChannelId>
    suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<Unit>
}

class RealForwardMessageTargetRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val channelRepository: ChannelRepository,
    private val memberRepository: MemberRepository,
    private val warningLogger: (String) -> Unit = {}
) : ForwardMessageTargetRepository {
    override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> {
        currentSession() ?: return forwardAuthRequired()
        val directMessages = channelRepository.getDirectMessages(serverId).contentOrEmpty { it.directMessages }
        val channels = channelRepository.getChannels(serverId).contentOrEmpty { it.channels }
        val items =
            (directMessages.mapNotNull { it.toRecentForwardTarget() } +
                channels.mapNotNull { it.toRecentForwardTarget() })
                .distinctBy { it.kind.name + ":" + it.id }
                .take(limit.coerceAtLeast(1))
        return DataResult.Content(items)
    }

    override suspend fun searchTargets(serverId: ServerId, query: String, limit: Int): DataResult<List<ForwardMessageTarget>> {
        currentSession() ?: return forwardAuthRequired()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return DataResult.Content(emptyList())
        return localSearchResult(serverId, cleanQuery, limit)
    }

    override suspend fun resolveTargetChannel(target: ForwardMessageTarget, allowJoin: Boolean): DataResult<ChannelId> {
        val directChannelId = target.channelId
        if (directChannelId != null && target.requiredAction != ForwardMessageTargetRequiredAction.JoinChannel) {
            return DataResult.Content(directChannelId)
        }
        return when (target.requiredAction) {
            ForwardMessageTargetRequiredAction.None ->
                directChannelId?.let { DataResult.Content(it) }
                    ?: DataResult.Error(ScreenError(code = "forward_target_missing_channel", retryable = false))
            ForwardMessageTargetRequiredAction.JoinChannel -> {
                if (!allowJoin) {
                    return DataResult.Error(ScreenError(code = "forward_join_confirmation_required", retryable = false))
                }
                val channelId = directChannelId
                    ?: return DataResult.Error(ScreenError(code = "forward_target_missing_channel", retryable = false))
                channelRepository.joinChannel(channelId).mapContent { it.channelId }
            }
            ForwardMessageTargetRequiredAction.CreateDirectMessage -> {
                val memberId = target.memberId
                    ?: return DataResult.Error(ScreenError(code = "forward_target_missing_member", retryable = false))
                val result =
                    when (target.kind) {
                        ForwardMessageTargetKind.AgentDirectMessageCandidate ->
                            channelRepository.openAgentDirectMessage(memberId)
                        ForwardMessageTargetKind.HumanDirectMessageCandidate ->
                            channelRepository.openUserDirectMessage(memberId)
                        else ->
                            DataResult.Error(ScreenError(code = "forward_target_cannot_create_dm", retryable = false))
                    }
                result.mapContent { it.conversation.toConversationSummary().messageStreamChannelId() }
            }
        }
    }

    override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<Unit> {
        currentSession() ?: return forwardAuthRequired()
        if (request.messageIds.isEmpty()) {
            return DataResult.Empty(EmptyReason.NoMessages)
        }
        return executeForwardTargets(
            source = "forward_messages",
            block = {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "messages/forward",
                    method = "POST",
                    bodyText = forwardTargetJson.encodeToString(request.toWireBody())
                )
            },
            mapper = { Unit }
        )
    }

    private suspend fun currentSession(): StoredAuthSession? =
        sessionStore.readSession()

    private suspend fun localSearchResult(
        serverId: ServerId,
        query: String,
        limit: Int
    ): DataResult<List<ForwardMessageTarget>> =
        DataResult.Content(localSearchTargets(serverId, query, limit))

    private suspend fun localSearchTargets(
        serverId: ServerId,
        query: String,
        limit: Int
    ): List<ForwardMessageTarget> {
        val max = limit.coerceAtLeast(1)
        val directMessages = channelRepository.getDirectMessages(serverId).contentOrEmpty { it.directMessages }
        val channels = channelRepository.getChannels(serverId).contentOrEmpty { it.channels }
        val members = memberRepository.getMembers(DataRequestPriority.Foreground, serverId).contentOrEmpty { it.members }

        return fallbackForwardMessageTargets(
            directMessages = directMessages,
            channels = channels,
            members = members,
            query = query,
            limit = max
        )
    }

    private suspend fun <T> executeForwardTargets(
        source: String,
        block: suspend () -> HomeNetworkResponse,
        mapper: suspend (String) -> T
    ): DataResult<T> {
        var statusCode: Int? = null
        return try {
            val response = block()
            statusCode = response.code
            if (!response.isSuccessful) return response.toForwardTargetDataError()
            val body = response.bodyText.orEmpty()
            DataResult.Content(mapper(body))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} " +
                    "exception=${exception::class.simpleName}"
            )
            DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
        }
    }
}

private inline fun <T, R> DataResult<T>.contentOrEmpty(selector: (T) -> List<R>): List<R> =
    when (this) {
        is DataResult.Content -> selector(data)
        else -> emptyList()
    }

private inline fun <T, R> DataResult<T>.mapContent(mapper: (T) -> R): DataResult<R> =
    when (this) {
        is DataResult.Content -> DataResult.Content(mapper(data))
        is DataResult.Empty -> DataResult.Empty(reason)
        is DataResult.Error -> DataResult.Error(error)
        is DataResult.NoPermission -> DataResult.NoPermission(blocker)
    }

private fun HomeDirectMessageRow.toRecentForwardTarget(): ForwardMessageTarget? {
    val channelId = toConversationSummary().messageStreamChannelId()
    val title = name.trim().takeIf { it.isNotBlank() } ?: return null
    return ForwardMessageTarget(
        kind = ForwardMessageTargetKind.RecentDirectMessage,
        id = channelId.value,
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() } ?: "Recent DM",
        avatarLabel = title.take(1).uppercase(),
        channelId = channelId,
        memberId = presenceMemberId,
        dmExists = true,
        canForwardNow = true
    )
}

private fun HomeChannelRow.toRecentForwardTarget(): ForwardMessageTarget? {
    if (!joined || archived) return null
    val channelId = toConversationSummary().messageStreamChannelId()
    val title = name.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: return null
    return ForwardMessageTarget(
        kind = ForwardMessageTargetKind.RecentChannel,
        id = channelId.value,
        title = "#$title",
        subtitle = subtitle?.takeIf { it.isNotBlank() } ?: "Joined channel",
        avatarLabel = "#",
        channelId = channelId,
        joined = true,
        canForwardNow = true
    )
}

private fun HomeChannelRow.toSearchForwardTarget(): ForwardMessageTarget? {
    if (!joined || archived) return null
    val channelId = toConversationSummary().messageStreamChannelId()
    val title = name.trim().removePrefix("#").takeIf { it.isNotBlank() } ?: return null
    return ForwardMessageTarget(
        kind = ForwardMessageTargetKind.RecentChannel,
        id = channelId.value,
        title = "#$title",
        subtitle = subtitle?.takeIf { it.isNotBlank() } ?: "Joined channel",
        avatarLabel = "#",
        channelId = channelId,
        joined = true,
        canForwardNow = true
    )
}

internal fun fallbackForwardMessageTargets(
    directMessages: List<HomeDirectMessageRow>,
    channels: List<HomeChannelRow>,
    members: List<MemberProfile> = emptyList(),
    query: String,
    limit: Int
): List<ForwardMessageTarget> {
    val existingDmMemberIds = directMessages.mapNotNull { it.presenceMemberId }.toSet()
    return (
        directMessages.mapNotNull { it.toRecentForwardTarget() } +
            channels.mapNotNull { it.toSearchForwardTarget() } +
            members
                .filterNot { it.id in existingDmMemberIds }
                .mapNotNull { it.toForwardMemberCandidate() }
        )
        .filter { it.matchesForwardQuery(query) }
        .dedupeForwardTargets()
        .take(limit.coerceAtLeast(1))
}

private fun MemberProfile.toForwardMemberCandidate(): ForwardMessageTarget? {
    val targetKind =
        when (kind) {
            MemberKind.Human -> ForwardMessageTargetKind.HumanDirectMessageCandidate
            MemberKind.Agent -> ForwardMessageTargetKind.AgentDirectMessageCandidate
            MemberKind.System -> return null
        }
    val title = displayName.trim().takeIf { it.isNotBlank() } ?: handle.trim().takeIf { it.isNotBlank() } ?: return null
    return ForwardMessageTarget(
        kind = targetKind,
        id = id.value,
        title = title,
        subtitle = handle.trim().takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
        avatarWireValue = avatar?.wireValue ?: avatarUrl,
        avatarLabel = avatarLabel.ifBlank { title.take(1).uppercase() },
        memberId = id,
        dmExists = false,
        canForwardNow = false,
        requiredAction = ForwardMessageTargetRequiredAction.CreateDirectMessage
    )
}

private fun ForwardMessageTarget.matchesForwardQuery(query: String): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return true
    val identityValues = listOf(title, id, memberId?.value.orEmpty(), channelId?.value.orEmpty())
    val searchableValues =
        when (kind) {
            ForwardMessageTargetKind.HumanDirectMessageCandidate,
            ForwardMessageTargetKind.AgentDirectMessageCandidate -> identityValues + subtitle
            else -> identityValues
        }
    return searchableValues
        .any { value -> value.contains(cleanQuery, ignoreCase = true) }
}

private fun List<ForwardMessageTarget>.dedupeForwardTargets(): List<ForwardMessageTarget> =
    distinctBy { it.kind.name + ":" + it.id }

internal fun parseForwardMessageTargetSearchResults(bodyText: String): List<ForwardMessageTarget> =
    parseForwardTargetSearchItems(bodyText)
        .mapNotNull { it.toForwardMessageTarget() }
        .dedupeForwardTargets()

@Serializable
internal data class ForwardTargetSearchResponseDto(
    val items: List<ForwardTargetSearchItemDto> = emptyList()
)

@Serializable
internal data class ForwardTargetSearchItemDto(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val avatarUrl: String? = null,
    val avatarLabel: String? = null,
    val channelId: String? = null,
    val joined: Boolean? = null,
    val dmExists: Boolean? = null,
    val canForwardNow: Boolean = false,
    val requiredAction: String? = null
)

internal fun parseForwardTargetSearchItems(bodyText: String): List<ForwardTargetSearchItemDto> {
    if (bodyText.isBlank()) return emptyList()
    val element = forwardTargetJson.parseToJsonElement(bodyText)
    return when (element) {
        is JsonArray ->
            forwardTargetJson.decodeFromJsonElement(
                ListSerializer(ForwardTargetSearchItemDto.serializer()),
                element
            )
        is JsonObject ->
            element["items"]?.jsonArray?.let { items ->
                forwardTargetJson.decodeFromJsonElement(
                    ListSerializer(ForwardTargetSearchItemDto.serializer()),
                    items
                )
            } ?: forwardTargetJson.decodeFromJsonElement(ForwardTargetSearchResponseDto.serializer(), element).items
        else -> emptyList()
    }
}

private fun ForwardTargetSearchItemDto.toForwardMessageTarget(): ForwardMessageTarget? {
    val cleanId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanType = type.trim().lowercase()
    val cleanChannelId = channelId?.trim()?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val wireAction = requiredAction.toForwardRequiredAction()
    val action =
        when {
            wireAction != ForwardMessageTargetRequiredAction.None -> wireAction
            cleanType == "channel" && joined == false -> ForwardMessageTargetRequiredAction.JoinChannel
            cleanType in setOf("human", "agent") && cleanChannelId == null && dmExists != true ->
                ForwardMessageTargetRequiredAction.CreateDirectMessage
            else -> ForwardMessageTargetRequiredAction.None
        }
    val kind =
        when (cleanType) {
            "dm" -> ForwardMessageTargetKind.RecentDirectMessage
            "human" -> ForwardMessageTargetKind.HumanDirectMessageCandidate
            "agent" -> ForwardMessageTargetKind.AgentDirectMessageCandidate
            "channel" ->
                if (joined == false || action == ForwardMessageTargetRequiredAction.JoinChannel) {
                    ForwardMessageTargetKind.PublicChannelUnjoined
                } else {
                    ForwardMessageTargetKind.RecentChannel
                }
            else -> return null
        }
    return ForwardMessageTarget(
        kind = kind,
        id = cleanId,
        title = cleanTitle,
        subtitle = subtitle?.trim()?.takeIf { it.isNotBlank() }.orEmpty(),
        avatarWireValue = avatarUrl?.trim()?.takeIf { it.isNotBlank() },
        avatarLabel = avatarLabel?.trim()?.takeIf { it.isNotBlank() } ?: cleanTitle.take(1).uppercase(),
        channelId = cleanChannelId,
        memberId =
            when (kind) {
                ForwardMessageTargetKind.HumanDirectMessageCandidate,
                ForwardMessageTargetKind.AgentDirectMessageCandidate ->
                    MemberId(cleanId)
                else -> null
            },
        joined = joined,
        dmExists = dmExists,
        canForwardNow = canForwardNow || (cleanChannelId != null && action == ForwardMessageTargetRequiredAction.None),
        requiredAction = action
    )
}

private fun String?.toForwardRequiredAction(): ForwardMessageTargetRequiredAction =
    when (this?.trim()?.lowercase()) {
        ForwardMessageTargetRequiredAction.JoinChannel.wireValue -> ForwardMessageTargetRequiredAction.JoinChannel
        ForwardMessageTargetRequiredAction.CreateDirectMessage.wireValue -> ForwardMessageTargetRequiredAction.CreateDirectMessage
        else -> ForwardMessageTargetRequiredAction.None
    }

@Serializable
private data class ForwardMessagesRequestBody(
    val destinationChannelId: String,
    val sourceMessageIds: List<String>,
    val note: String = ""
)

private fun ForwardMessageExecuteRequest.toWireBody(): ForwardMessagesRequestBody =
    ForwardMessagesRequestBody(
        destinationChannelId = targetChannelId.value,
        sourceMessageIds = messageIds,
        note = note.trim()
    )

private fun <T> HomeNetworkResponse.toForwardTargetDataError(): DataResult<T> =
    when (code) {
        401 -> forwardAuthRequired()
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = forwardTargetErrorCode("server_forbidden"),
                    requiredCapability = "server_access"
                )
            )
        404 -> DataResult.Error(ScreenError(code = forwardTargetErrorCode("forward_endpoint_unavailable"), retryable = false))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = forwardTargetErrorCode("http_$code"), retryable = false))
    }

private fun HomeNetworkResponse.forwardTargetErrorCode(fallback: String): String {
    val payload = bodyText ?: errorBodyText ?: return fallback
    return runCatching {
        forwardTargetJson.decodeFromString(ForwardTargetErrorBody.serializer(), payload)
            .code
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: fallback
}

@Serializable
private data class ForwardTargetErrorBody(
    val code: String? = null
)

private fun <T> forwardAuthRequired(): DataResult<T> =
    authSessionRequiredResult()
