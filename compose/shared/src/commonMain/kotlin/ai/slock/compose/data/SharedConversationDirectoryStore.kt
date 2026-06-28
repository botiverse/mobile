package ai.slock.compose.data

import ai.slock.compose.KuiklyThreadSurfaceKind
import ai.slock.compose.resolveThreadRouteIdentity
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.isGenericDisplayName
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.safeDisplayNameOrNull
import ai.slock.shared.data.local.ChannelEntity
import ai.slock.shared.data.local.ConversationAliasEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val peerMemberId: String? = null,
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
    @Suppress("UNUSED_PARAMETER") database: SlockSqlDatabase,
    private val clock: () -> Long = { defaultNowMillis() },
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val channelsByServer = mutableMapOf<String, Map<String, ChannelEntity>>()
    private val aliasesByServer = mutableMapOf<String, List<ConversationAliasEntity>>()
    private val states = MutableStateFlow<Map<String, SharedConversationDirectoryState>>(emptyMap())

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
        states.value[serverId.value]?.resolve(id)

    suspend fun upsertConversation(
        serverId: ServerId,
        conversation: ConversationSummary,
        source: SharedConversationDirectorySource
    ) {
        val canonicalChannelId = conversation.directoryCanonicalChannelId().value
        val now = clock()
        mutex.withLock {
            val serverKey = serverId.value
            val existingChannels = channelsByServer[serverKey].orEmpty()
            val existingAliases = aliasesByServer[serverKey].orEmpty()
            val existing = existingChannels[canonicalChannelId]
            val merged = mergeConversation(existing, conversation, source, canonicalChannelId)
            val nextChannel = merged.toChannelEntity(
                serverId = serverKey,
                channelId = canonicalChannelId,
                lastSyncedAtMs = now
            )
            val nextAliases =
                existingAliases.filterNot { alias -> alias.canonicalChannelId == canonicalChannelId } +
                    conversation.aliases(canonicalChannelId).map { alias ->
                        ConversationAliasEntity(
                            serverId = serverKey,
                            aliasId = alias.id,
                            aliasKind = alias.kind.name,
                            canonicalChannelId = canonicalChannelId,
                            lastSyncedAtMs = now
                        )
                    }
            val nextChannels = existingChannels + (canonicalChannelId to nextChannel)
            if (nextChannels == existingChannels && nextAliases == existingAliases) {
                return
            }
            channelsByServer[serverKey] = nextChannels
            aliasesByServer[serverKey] = nextAliases
            states.value =
                states.value +
                    (
                        serverKey to buildState(
                            channels = nextChannels.values.toList(),
                            aliases = nextAliases
                        )
                    )
        }
    }

    private fun observeDirectory(serverId: ServerId): Flow<SharedConversationDirectoryState> =
        states
            .map { byServer -> byServer[serverId.value] ?: SharedConversationDirectoryState() }
            .distinctUntilChanged()

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
        if (existing == null) {
            return incomingDraft
        }
        val shouldKeepExistingName =
            incomingDraft.name.isGenericDirectoryTitle(incoming, canonicalChannelId) &&
                !existing.name.isGenericDirectoryTitle(incoming, canonicalChannelId)
        val shouldKeepExistingType =
            incomingDraft.type.isGenericDirectoryChannelType() &&
                !existing.type.isGenericDirectoryChannelType()
        val shouldUseIncomingType =
            !incomingDraft.type.isGenericDirectoryChannelType() &&
                existing.type.isGenericDirectoryChannelType()
        if (source.rank >= SharedConversationDirectorySource.FreshApi.rank) {
            return incomingDraft.copy(
                type = if (shouldKeepExistingType) existing.type else incomingDraft.type,
                name = if (shouldKeepExistingName) existing.name else incomingDraft.name
            )
        }
        return incomingDraft.copy(
            type =
                when {
                    shouldKeepExistingType -> existing.type
                    shouldUseIncomingType -> incomingDraft.type
                    else -> existing.type.ifBlank { incomingDraft.type }
                },
            name = if (shouldKeepExistingName) existing.name else incomingDraft.name,
            description = incomingDraft.description ?: existing.description,
            parentChannelId = incomingDraft.parentChannelId ?: existing.parentChannelId,
            parentMessageId = incomingDraft.parentMessageId ?: existing.parentMessageId,
            avatarUrl = null,
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

private fun ChannelEntityDraft.toChannelEntity(
    serverId: String,
    channelId: String,
    lastSyncedAtMs: Long
): ChannelEntity =
    ChannelEntity(
        serverId = serverId,
        channelId = channelId,
        type = type,
        name = name,
        description = description,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        avatarUrl = avatarUrl,
        presenceMemberId = presenceMemberId,
        joined = joined,
        archived = archived,
        memberCount = memberCount,
        unreadCount = unreadCount,
        mentionCount = mentionCount,
        lastMessageId = lastMessageId,
        firstUnreadMessageId = firstUnreadMessageId,
        lastMessagePreview = lastMessagePreview,
        lastMessageSenderName = lastMessageSenderName,
        hasDraft = hasDraft,
        replyCount = replyCount,
        jointChannelId = jointChannelId,
        jointRole = jointRole,
        jointPeerServerId = jointPeerServerId,
        jointPeerServerName = jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus,
        capabilities = capabilities,
        updatedAtLabel = updatedAtLabel,
        lastSyncedAtMs = lastSyncedAtMs
    )

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
        name =
            safeDisplayNameOrNull()
                ?.takeUnless { isGenericDisplayName(it, additionalIdentityIds = setOf(canonicalChannelId)) }
                ?: canonicalChannelId,
        description = subtitle?.takeIf { it.isNotBlank() },
        parentChannelId = if (kind == ConversationKind.Thread) thread?.parentChannelId?.value else null,
        parentMessageId = if (kind == ConversationKind.Thread) thread?.parentMessageId?.value else null,
        avatarUrl = null,
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
        val isReplyThread = kind == ConversationKind.Thread
        add(ConversationDirectoryAlias(canonicalChannelId, SharedConversationAliasKind.Stream))
        channelId
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { kind != ConversationKind.Thread || it == routeIdentity.timelineChannelId }
            ?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Channel)) }
        threadId.value.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Thread)) }
        if (isReplyThread) {
            thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.Stream)) }
            thread?.parentChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.ParentChannel)) }
            thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.ParentMessage)) }
        }
        presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let { add(ConversationDirectoryAlias(it, SharedConversationAliasKind.PresenceMember)) }
    }

private fun ConversationSummary.directoryCanonicalChannelId(): ChannelId =
    if (kind == ConversationKind.Thread) {
        messageStreamChannelId()
    } else {
        channelId ?: ChannelId(threadId.value)
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
        avatarWireValue = null,
        avatarLabel = title.take(2).uppercase(),
        peerMemberId = presenceMemberId?.takeIf { it.isNotBlank() },
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        joined = joined != 0L,
        archived = archived != 0L
    )
}

private fun ConversationSummary.toDirectoryChannelType(): String =
    when (kind) {
        ConversationKind.Thread -> "thread"
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> channelType?.takeIf { it.isNotBlank() } ?: "channel"
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
        conversation.isGenericDisplayName(this, additionalIdentityIds = setOf(canonicalChannelId)) ||
        this == canonicalChannelId ||
        this == conversation.threadId.value ||
        this == conversation.channelId?.value ||
        (conversation.kind == ConversationKind.Thread && this == conversation.thread?.streamChannelId?.value)

private fun String.isGenericDirectoryChannelType(): Boolean =
    isBlank() || equals("channel", ignoreCase = true)
