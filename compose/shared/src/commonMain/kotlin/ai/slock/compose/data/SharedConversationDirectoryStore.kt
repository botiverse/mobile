package ai.slock.compose.data

import ai.slock.compose.KuiklyThreadSurfaceKind
import ai.slock.compose.resolveThreadRouteIdentity
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.avatarSlotResolution
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.safeDisplayNameOrNull
import ai.slock.shared.data.local.ChannelEntity
import ai.slock.shared.data.local.ConversationAliasEntity
import app.cash.sqldelight.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class SharedConversationDirectorySource(val rank: Int) {
    RouteSeed(0),
    DenormalizedSeed(1),
    LocalCache(2),
    FreshApi(3),
    RealtimeMutation(4)
}

internal enum class SharedConversationAliasKind(val resolvesUniqueConversation: Boolean) {
    Channel(true),
    Thread(true),
    Stream(true),
    ParentChannel(false),
    ParentMessage(false),
    PresenceMember(true),
    Member(true)
}

internal data class SharedConversationDirectoryProjection(
    val serverId: String,
    val canonicalChannelId: String,
    val aliases: Set<String>,
    val surfaceKind: String,
    val channelKind: String,
    val title: String,
    val subtitle: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.take(2).uppercase(),
    val presence: Presence? = null,
    val agentStatus: AgentStatus? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val joined: Boolean = true,
    val archived: Boolean = false
)

internal data class SharedConversationDirectoryState(
    val projectionsByCanonicalId: Map<String, SharedConversationDirectoryProjection> = emptyMap(),
    val aliasesById: Map<String, Set<String>> = emptyMap(),
    val duplicateAliases: Map<String, Set<String>> = emptyMap()
) {
    fun resolve(id: String?): SharedConversationDirectoryProjection? {
        val normalized = id?.takeIf { it.isNotBlank() } ?: return null
        val canonicalIds = aliasesById[normalized].orEmpty()
        return when {
            canonicalIds.size == 1 -> projectionsByCanonicalId[canonicalIds.single()]
            normalized in projectionsByCanonicalId -> projectionsByCanonicalId[normalized]
            else -> null
        }
    }
}

internal class SharedConversationDirectoryStore(
    private val database: SlockSqlDatabase,
    private val clock: () -> Long = { defaultNowMillis() },
    private val logger: (String) -> Unit = {}
) {
    fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState> =
        observeDirectory(serverId)

    fun projectionFlow(
        serverId: ServerId,
        ids: Collection<String>
    ): Flow<Map<String, SharedConversationDirectoryProjection>> =
        observeDirectory(serverId)
            .map { state ->
                ids
                    .filter { it.isNotBlank() }
                    .associateWith { id -> state.resolve(id) }
                    .filterValues { it != null }
                    .mapValues { (_, value) -> value!! }
            }
            .distinctUntilChanged()

    fun resolveProjection(
        serverId: ServerId,
        id: String
    ): SharedConversationDirectoryProjection? =
        buildState(
            channels = database.queries.selectConversationDirectory(serverId.value).executeAsList(),
            aliases = database.queries.selectConversationAliasesForServer(serverId.value).executeAsList()
        ).resolve(id)

    suspend fun upsertConversation(
        serverId: ServerId,
        conversation: ConversationSummary,
        source: SharedConversationDirectorySource
    ) {
        val canonicalChannelId = conversation.messageStreamChannelId().value
        val now = clock()
        val existing = database.queries.selectChannel(serverId.value, canonicalChannelId).executeAsOneOrNull()
        val merged = mergeConversation(existing, conversation, source, canonicalChannelId)
        database.transaction {
            database.queries.upsertChannel(
                serverId = serverId.value,
                channelId = canonicalChannelId,
                type = merged.type,
                name = merged.name,
                description = merged.description,
                parentChannelId = merged.parentChannelId,
                parentMessageId = merged.parentMessageId,
                avatarUrl = merged.avatarUrl,
                presence = merged.presence,
                agentStatus = merged.agentStatus,
                presenceMemberId = merged.presenceMemberId,
                joined = merged.joined,
                archived = merged.archived,
                memberCount = merged.memberCount,
                unreadCount = merged.unreadCount,
                mentionCount = merged.mentionCount,
                lastMessageId = merged.lastMessageId,
                firstUnreadMessageId = merged.firstUnreadMessageId,
                lastMessagePreview = merged.lastMessagePreview,
                lastMessageSenderName = merged.lastMessageSenderName,
                hasDraft = merged.hasDraft,
                replyCount = merged.replyCount,
                jointChannelId = merged.jointChannelId,
                jointRole = merged.jointRole,
                jointPeerServerId = merged.jointPeerServerId,
                jointPeerServerName = merged.jointPeerServerName,
                jointPeerServerSlug = merged.jointPeerServerSlug,
                jointPeerStatus = merged.jointPeerStatus,
                capabilities = merged.capabilities,
                updatedAtLabel = merged.updatedAtLabel,
                lastSyncedAtMs = now
            )
            conversation.aliases(canonicalChannelId).forEach { alias ->
                database.queries.upsertConversationAlias(
                    serverId = serverId.value,
                    aliasId = alias.id,
                    aliasKind = alias.kind.name,
                    canonicalChannelId = canonicalChannelId,
                    lastSyncedAtMs = now
                )
            }
        }
    }

    private fun observeDirectory(serverId: ServerId): Flow<SharedConversationDirectoryState> =
        callbackFlow {
            val channelsQuery = database.queries.selectConversationDirectory(serverId.value)
            val aliasesQuery = database.queries.selectConversationAliasesForServer(serverId.value)
            fun emitCurrent() {
                trySend(
                    buildState(
                        channels = channelsQuery.executeAsList(),
                        aliases = aliasesQuery.executeAsList()
                    )
                )
            }
            val listener = Query.Listener { emitCurrent() }
            channelsQuery.addListener(listener)
            aliasesQuery.addListener(listener)
            emitCurrent()
            awaitClose {
                channelsQuery.removeListener(listener)
                aliasesQuery.removeListener(listener)
            }
        }.distinctUntilChanged()

    private fun buildState(
        channels: List<ChannelEntity>,
        aliases: List<ConversationAliasEntity>
    ): SharedConversationDirectoryState {
        val projections =
            channels
                .mapNotNull { channel -> channel.toProjection(aliases.filter { it.canonicalChannelId == channel.channelId }) }
                .associateBy { it.canonicalChannelId }
        val aliasesById =
            buildMap<String, MutableSet<String>> {
                projections.values.forEach { projection ->
                    putAlias(projection.canonicalChannelId, projection.canonicalChannelId)
                }
                aliases.forEach { alias ->
                    val aliasKind = alias.aliasKind.toConversationAliasKindOrNull()
                    if (aliasKind?.resolvesUniqueConversation == true) {
                        putAlias(alias.aliasId, alias.canonicalChannelId)
                    }
                }
            }.mapValues { (_, values) -> values.toSet() }
        val duplicateAliases = aliasesById.filterValues { it.size > 1 }
        duplicateAliases.forEach { (alias, canonicalIds) ->
            logger("conversation_directory_alias_conflict alias=$alias canonicalIds=${canonicalIds.joinToString(",")}")
        }
        return SharedConversationDirectoryState(
            projectionsByCanonicalId = projections,
            aliasesById = aliasesById,
            duplicateAliases = duplicateAliases
        )
    }

    private fun MutableMap<String, MutableSet<String>>.putAlias(aliasId: String, canonicalChannelId: String) {
        if (aliasId.isBlank() || canonicalChannelId.isBlank()) return
        getOrPut(aliasId) { linkedSetOf() }.add(canonicalChannelId)
    }

    private fun mergeConversation(
        existing: ChannelEntity?,
        incoming: ConversationSummary,
        source: SharedConversationDirectorySource,
        canonicalChannelId: String
    ): ChannelEntityDraft {
        val incomingDraft = incoming.toChannelEntityDraft(canonicalChannelId)
        if (existing == null || source.rank >= SharedConversationDirectorySource.FreshApi.rank) {
            return incomingDraft
        }
        return incomingDraft.copy(
            type = existing.type.ifBlank { incomingDraft.type },
            name = incomingDraft.name.takeUnless { it.isGenericDirectoryTitle(incoming, canonicalChannelId) } ?: existing.name,
            description = incomingDraft.description ?: existing.description,
            parentChannelId = incomingDraft.parentChannelId ?: existing.parentChannelId,
            parentMessageId = incomingDraft.parentMessageId ?: existing.parentMessageId,
            avatarUrl = incomingDraft.avatarUrl ?: existing.avatarUrl,
            presence = incomingDraft.presence ?: existing.presence,
            agentStatus = incomingDraft.agentStatus ?: existing.agentStatus,
            presenceMemberId = incomingDraft.presenceMemberId ?: existing.presenceMemberId,
            joined = if (incoming.joined) existing.joined else incomingDraft.joined,
            archived = if (incoming.archived) incomingDraft.archived else existing.archived,
            memberCount = incomingDraft.memberCount ?: existing.memberCount,
            unreadCount = maxOf(existing.unreadCount, incomingDraft.unreadCount),
            mentionCount = maxOf(existing.mentionCount, incomingDraft.mentionCount),
            lastMessageId = incomingDraft.lastMessageId ?: existing.lastMessageId,
            firstUnreadMessageId = incomingDraft.firstUnreadMessageId ?: existing.firstUnreadMessageId,
            lastMessagePreview = incomingDraft.lastMessagePreview ?: existing.lastMessagePreview,
            lastMessageSenderName = incomingDraft.lastMessageSenderName ?: existing.lastMessageSenderName,
            hasDraft = if (incoming.hasDraft) incomingDraft.hasDraft else existing.hasDraft,
            replyCount = maxOf(existing.replyCount, incomingDraft.replyCount),
            jointChannelId = incomingDraft.jointChannelId ?: existing.jointChannelId,
            jointRole = incomingDraft.jointRole ?: existing.jointRole,
            jointPeerServerId = incomingDraft.jointPeerServerId ?: existing.jointPeerServerId,
            jointPeerServerName = incomingDraft.jointPeerServerName ?: existing.jointPeerServerName,
            jointPeerServerSlug = incomingDraft.jointPeerServerSlug ?: existing.jointPeerServerSlug,
            jointPeerStatus = incomingDraft.jointPeerStatus ?: existing.jointPeerStatus,
            capabilities = if (incoming.permissions == ConversationPermissions()) existing.capabilities else incomingDraft.capabilities,
            updatedAtLabel = incomingDraft.updatedAtLabel ?: existing.updatedAtLabel
        )
    }
}

private data class ConversationDirectoryAlias(
    val id: String,
    val kind: SharedConversationAliasKind
)

private data class ChannelEntityDraft(
    val type: String,
    val name: String,
    val description: String?,
    val parentChannelId: String?,
    val parentMessageId: String?,
    val avatarUrl: String?,
    val presence: String?,
    val agentStatus: String?,
    val presenceMemberId: String?,
    val joined: Long,
    val archived: Long,
    val memberCount: Long?,
    val unreadCount: Long,
    val mentionCount: Long,
    val lastMessageId: String?,
    val firstUnreadMessageId: String?,
    val lastMessagePreview: String?,
    val lastMessageSenderName: String?,
    val hasDraft: Long,
    val replyCount: Long,
    val jointChannelId: String?,
    val jointRole: String?,
    val jointPeerServerId: String?,
    val jointPeerServerName: String?,
    val jointPeerServerSlug: String?,
    val jointPeerStatus: String?,
    val capabilities: Long,
    val updatedAtLabel: String?
)

private fun ConversationSummary.toChannelEntityDraft(canonicalChannelId: String): ChannelEntityDraft =
    ChannelEntityDraft(
        type = toDirectoryChannelType(),
        name = safeDisplayNameOrNull() ?: name.takeIf { it.isNotBlank() } ?: canonicalChannelId,
        description = subtitle?.takeIf { it.isNotBlank() },
        parentChannelId = thread?.parentChannelId?.value,
        parentMessageId = thread?.parentMessageId?.value,
        avatarUrl = avatarSlotResolution().effectiveAvatarWireValue ?: avatar?.wireValue ?: avatarUrl,
        presence = presence?.name,
        agentStatus = agentStatus?.name,
        presenceMemberId = presenceMemberId?.value,
        joined = joined.toLongFlag(),
        archived = archived.toLongFlag(),
        memberCount = memberCount?.toLong(),
        unreadCount = unreadCount.toLong(),
        mentionCount = mentionCount.toLong(),
        lastMessageId = lastMessageId?.value,
        firstUnreadMessageId = firstUnreadMessageId?.value,
        lastMessagePreview = lastMessagePreview,
        lastMessageSenderName = lastMessageSenderName,
        hasDraft = hasDraft.toLongFlag(),
        replyCount = 0L,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId?.value,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        capabilities = permissions.toDirectoryCapabilityMask(),
        updatedAtLabel = lastMessageAt
    )

private fun ConversationSummary.aliases(canonicalChannelId: String): Set<ConversationDirectoryAlias> =
    buildSet {
        val routeIdentity = resolveThreadRouteIdentity()
        add(ConversationDirectoryAlias(canonicalChannelId, SharedConversationAliasKind.Stream))
        channelId
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { kind != ConversationKind.Thread || it == routeIdentity.timelineChannelId }
            ?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Channel)) }
        threadId.value.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Thread)) }
        thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Stream)) }
        thread?.parentChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.ParentChannel)) }
        thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.ParentMessage)) }
        presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.PresenceMember)) }
    }

private fun ChannelEntity.toProjection(
    aliases: List<ConversationAliasEntity>
): SharedConversationDirectoryProjection? {
    val kind = type.toDirectoryConversationKind()
    val title = name.takeIf { it.isNotBlank() } ?: channelId
    val surfaceKind =
        when (kind) {
            ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
            else -> KuiklyThreadSurfaceKind.Channel
        }
    return SharedConversationDirectoryProjection(
        serverId = serverId,
        canonicalChannelId = channelId,
        aliases =
            (aliases.map { it.aliasId } + channelId + listOfNotNull(parentChannelId, parentMessageId, presenceMemberId))
                .filter { it.isNotBlank() }
                .toSet(),
        surfaceKind = surfaceKind,
        channelKind = type,
        title = title,
        subtitle = description?.takeIf { it.isNotBlank() },
        avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() },
        avatarLabel = title.take(2).uppercase(),
        presence = presence?.let { runCatching { Presence.valueOf(it) }.getOrNull() },
        agentStatus = agentStatus?.let { runCatching { AgentStatus.valueOf(it) }.getOrNull() },
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        joined = joined != 0L,
        archived = archived != 0L
    )
}

private fun ConversationSummary.toDirectoryChannelType(): String =
    channelType?.takeIf { it.isNotBlank() } ?: when (kind) {
        ConversationKind.Thread -> "thread"
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> "channel"
        ConversationKind.DirectMessage -> "dm"
        ConversationKind.Inbox,
        ConversationKind.System -> "channel"
    }

private fun String.toDirectoryConversationKind(): ConversationKind =
    when (lowercase()) {
        "thread" -> ConversationKind.Thread
        "joint" -> ConversationKind.Joint
        "dm" -> ConversationKind.DirectMessage
        else -> ConversationKind.Channel
    }

private fun String.toConversationAliasKindOrNull(): SharedConversationAliasKind? =
    runCatching { SharedConversationAliasKind.valueOf(this) }.getOrNull()

private fun ConversationPermissions.toDirectoryCapabilityMask(): Long =
    (if (canRead) 1L else 0L) or
        (if (canSend) 2L else 0L) or
        (if (canAttach) 4L else 0L) or
        (if (canCreateTask) 8L else 0L)

private fun Boolean.toLongFlag(): Long = if (this) 1L else 0L

private fun String.isGenericDirectoryTitle(
    conversation: ConversationSummary,
    canonicalChannelId: String
): Boolean =
    isBlank() ||
        this == canonicalChannelId ||
        this == conversation.threadId.value ||
        this == conversation.channelId?.value ||
        this == conversation.thread?.streamChannelId?.value
