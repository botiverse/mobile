package build.raft.app.data

import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ServerId
import build.raft.app.model.conversationDisplayTitleCandidate
import build.raft.app.model.conversationIdentityLikeTitle
import build.raft.app.model.conversationSafeDisplayTitleOrNull
import build.raft.app.model.isGenericDisplayName
import kotlinx.coroutines.flow.Flow

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
    val archived: Boolean = false,
    val permissions: ConversationPermissions = ConversationPermissions()
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

internal interface SharedConversationIdentityProvider {
    fun observeServer(serverId: ServerId): Flow<SharedConversationDirectoryState>

    fun projectionFlow(
        serverId: ServerId,
        ids: Collection<String>
    ): Flow<Map<String, SharedConversationDirectoryProjection>>

    fun resolveProjection(
        serverId: ServerId,
        id: String
    ): SharedConversationDirectoryProjection?
}

internal fun HomeSnapshot.toSharedConversationDirectoryState(
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryState(
        serverId = server.id,
        conversations = channels + directMessages,
        logger = logger
    )

private fun sharedConversationDirectoryState(
    serverId: ServerId,
    conversations: List<HomeConversationRow>,
    logger: (String) -> Unit
): SharedConversationDirectoryState {
    val projections =
        conversations
            .mapNotNull { conversation -> conversation.toSharedConversationDirectoryProjection(serverId) }
            .associateBy { it.canonicalChannelId }
    val aliasesById =
        buildMap<String, MutableSet<String>> {
            projections.values.forEach { projection ->
                putAlias(projection.canonicalChannelId, projection.canonicalChannelId)
                projection.aliases.forEach { alias -> putAlias(alias, projection.canonicalChannelId) }
            }
        }.mapValues { (_, values) -> values.toSet() }
    val duplicateAliases = aliasesById.filterValues { it.size > 1 }
    duplicateAliases.forEach { (alias, canonicalIds) ->
        logger("conversation_identity_alias_conflict alias=$alias canonicalIds=${canonicalIds.joinToString(",")}")
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

internal fun HomeConversationRow.toSharedConversationDirectoryProjection(
    serverId: ServerId
): SharedConversationDirectoryProjection? {
    val canonicalChannelId = directoryCanonicalChannelId().value.takeIf { it.isNotBlank() } ?: return null
    val title =
        safeDisplayNameOrNull(additionalIdentityIds = setOf(canonicalChannelId))
            ?: canonicalChannelId
    val kind = toDirectoryConversationKind()
    val surfaceKind =
        when (kind) {
            ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
            else -> KuiklyThreadSurfaceKind.Channel
        }
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = canonicalChannelId,
        aliases = aliases(canonicalChannelId).map { it.id }.filter { it.isNotBlank() }.toSet() + canonicalChannelId,
        surfaceKind = surfaceKind,
        channelKind = toDirectoryChannelType(),
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = null,
        avatarLabel = title.take(2).uppercase(),
        peerMemberId = presenceMemberId?.value?.takeIf { it.isNotBlank() },
        parentChannelId = if (kind == ConversationKind.Thread) thread?.parentChannelId?.value else null,
        parentMessageId = if (kind == ConversationKind.Thread) thread?.parentMessageId?.value else null,
        joined = joined && permissions.canRead,
        archived = archived,
        permissions = permissions
    )
}

private data class ConversationDirectoryAlias(
    val id: String,
    val kind: SharedConversationAliasKind
)

private fun HomeConversationRow.aliases(canonicalChannelId: String): Set<ConversationDirectoryAlias> =
    buildSet {
        val routeIdentity = resolveThreadRouteIdentity(
            surfaceKind =
                when (toDirectoryConversationKind()) {
                    ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
                    ConversationKind.Thread -> KuiklyThreadSurfaceKind.ReplyThread
                    else -> KuiklyThreadSurfaceKind.Channel
                },
            channelKind = toDirectoryChannelType(),
            instanceId = null,
            sourceThreadId = threadId.value,
            streamChannelId = directoryCanonicalChannelId().value,
            parentChannelId = if (kind == ConversationKind.Thread) thread?.parentChannelId?.value else null,
            parentMessageId = if (kind == ConversationKind.Thread) thread?.parentMessageId?.value else null
        )
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

private fun HomeConversationRow.directoryCanonicalChannelId(): ChannelId =
    if (kind == ConversationKind.Thread) {
        thread?.streamChannelId ?: ChannelId(threadId.value)
    } else {
        channelId ?: ChannelId(threadId.value)
    }

private fun HomeConversationRow.toDirectoryChannelType(): String =
    when (kind) {
        ConversationKind.Thread -> "thread"
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> channelType?.takeIf { it.isNotBlank() } ?: "channel"
        ConversationKind.DirectMessage -> "dm"
        ConversationKind.Inbox,
        ConversationKind.System -> "channel"
    }

private fun HomeConversationRow.toDirectoryConversationKind(): ConversationKind =
    when (kind) {
        ConversationKind.Thread -> ConversationKind.Thread
        ConversationKind.Joint -> ConversationKind.Joint
        ConversationKind.DirectMessage -> ConversationKind.DirectMessage
        ConversationKind.Channel,
        ConversationKind.Inbox,
        ConversationKind.System -> ConversationKind.Channel
    }

private fun HomeConversationRow.safeDisplayNameOrNull(
    additionalIdentityIds: Set<String> = emptySet()
): String? =
    conversationSafeDisplayTitleOrNull(name, headerIdentityIds() + additionalIdentityIds)
        ?.takeUnless { isGenericDisplayName(it, additionalIdentityIds) }

private fun HomeConversationRow.isGenericDisplayName(
    rawName: String = name,
    additionalIdentityIds: Set<String> = emptySet()
): Boolean {
    val candidate = conversationDisplayTitleCandidate(rawName) ?: return true
    if (conversationIdentityLikeTitle(candidate, headerIdentityIds() + additionalIdentityIds)) return true
    return candidate.equals(genericDisplayTitleFallback(), ignoreCase = true)
}

private fun HomeConversationRow.genericDisplayTitleFallback(): String =
    when (kind) {
        ConversationKind.DirectMessage -> "Direct Message"
        ConversationKind.Thread -> "Thread"
        ConversationKind.Channel,
        ConversationKind.Joint,
        ConversationKind.Inbox,
        ConversationKind.System -> "Channel"
    }

private fun HomeConversationRow.headerIdentityIds(): Set<String> =
    listOfNotNull(
        threadId.value,
        channelId?.value,
        thread?.streamChannelId?.value
    ).filter { it.isNotBlank() }.toSet()
