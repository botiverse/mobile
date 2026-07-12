package build.raft.app.data

import build.raft.app.MemberDetailIdentity
import build.raft.app.memberDetailIdentity
import build.raft.app.parsedMemberKindOrNull
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.toKuiklySurfaceKind
import build.raft.app.toWireChannelKindOrNull
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.conversationDisplayTitleCandidate
import build.raft.app.model.conversationIdentityLikeTitle
import build.raft.app.model.conversationSafeDisplayTitleOrNull
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
    val conversationKind: ConversationKind,
    val surfaceKind: String,
    val channelKind: String,
    val title: String,
    val subtitle: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.take(2).uppercase(),
    val senderMessageIds: Set<String> = emptySet(),
    val senderMemberId: String? = null,
    val senderAvatarWireValue: String? = null,
    val senderAvatarLabel: String? = null,
    val peerIdentity: MemberDetailIdentity? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val joined: Boolean = true,
    val archived: Boolean = false,
    val activityNotificationPreference: ChannelActivityNotificationPreference =
        ChannelActivityNotificationPreference.Unsupported,
    val permissions: ConversationPermissions = ConversationPermissions()
) {
    val activityMuted: Boolean
        get() = activityNotificationPreference.activityMuted
    val muteFromSeq: Long?
        get() = activityNotificationPreference.muteFromSeq
    val activityMuteSupported: Boolean
        get() = activityNotificationPreference.activityMuteSupported
}

internal data class SharedConversationDirectoryState(
    val projectionsByCanonicalId: Map<String, SharedConversationDirectoryProjection> = emptyMap(),
    val aliasesById: Map<String, Set<String>> = emptyMap(),
    val duplicateAliases: Map<String, Set<String>> = emptyMap()
) {
    fun resolve(id: String?): SharedConversationDirectoryProjection? {
        val normalized = id?.takeIf { it.isNotBlank() } ?: return null
        // A canonical id is the projection's self-attested identity. Aliases
        // may help find that identity, but they must never override it.
        projectionsByCanonicalId[normalized]?.let { return it }
        val canonicalIds = aliasesById[normalized].orEmpty()
        return when {
            // Observation point (t432 family ③): a single alias claim wins by default —
            // e.g. a thread stream id can resolve to a related DM row while the thread's
            // own projection is absent. Reply-like consumers defer/drop upstream, so this
            // stays acceptable; revisit if a non-deferred consumer surfaces.
            canonicalIds.size == 1 -> projectionsByCanonicalId[canonicalIds.single()]
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

internal fun InboxSnapshot.toSharedConversationDirectoryState(
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryStateFromProjections(
        projections = items.flatMap { item -> item.toSharedConversationDirectoryProjections(server.id, logger) },
        logger = logger
    )

internal fun Collection<SharedConversationDirectoryProjection>.toSharedConversationDirectoryState(
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryStateFromProjections(
        projections = this,
        logger = logger
    )

internal fun Iterable<SharedConversationDirectoryState>.mergedSharedConversationDirectoryState(
    logger: (String) -> Unit = {}
): SharedConversationDirectoryState =
    sharedConversationDirectoryStateFromProjections(
        projections = flatMap { state -> state.projectionsByCanonicalId.values },
        logger = logger
    )

private fun sharedConversationDirectoryState(
    serverId: ServerId,
    conversations: List<HomeConversationRow>,
    logger: (String) -> Unit
): SharedConversationDirectoryState =
    // One registration rule for every directory source (t432 PR-C): this Home-row builder
    // previously skipped the parent-id alias registration the projection builder performs,
    // so the same projection set produced different alias tables depending on entry point.
    // Home-source directories now also resolve by parent ids — a safe gain under
    // canonical-first resolution (#281) and the parent-never-claims-child rule.
    sharedConversationDirectoryStateFromProjections(
        projections = conversations.mapNotNull { it.toSharedConversationDirectoryProjection(serverId) },
        logger = logger
    )

private fun sharedConversationDirectoryStateFromProjections(
    projections: Iterable<SharedConversationDirectoryProjection>,
    logger: (String) -> Unit
): SharedConversationDirectoryState {
    val claimed = projections.filter { it.canonicalChannelId.isNotBlank() }
    val byCanonicalId = claimed.associateBy { it.canonicalChannelId }
    if (claimed.size != byCanonicalId.size) {
        // Two projections self-attesting the SAME canonical id is an identity conflict the
        // associateBy silently resolves last-wins. Log it explicitly (t432 family ③) —
        // adjudication stays last-wins until a real case motivates provenance rules.
        claimed.groupBy { it.canonicalChannelId }
            .filterValues { it.size > 1 }
            .forEach { (canonicalId, claims) ->
                logger(
                    "conversation_identity_canonical_conflict canonicalId=$canonicalId " +
                        "claims=${claims.joinToString(",") { it.conversationKind.name }} lastWins=true"
                )
            }
    }
    val aliasesById =
        buildMap<String, MutableSet<String>> {
            byCanonicalId.values.forEach { projection ->
                putAlias(projection.canonicalChannelId, projection.canonicalChannelId)
                projection.aliases.forEach { alias -> putAlias(alias, projection.canonicalChannelId) }
                projection.parentChannelId?.let { alias -> putAlias(alias, projection.canonicalChannelId) }
                projection.parentMessageId?.let { alias -> putAlias(alias, projection.canonicalChannelId) }
            }
        }.mapValues { (_, values) -> values.toSet() }
    val duplicateAliases = aliasesById.filterValues { it.size > 1 }
    duplicateAliases.forEach { (alias, canonicalIds) ->
        logger("conversation_identity_alias_conflict alias=$alias canonicalIds=${canonicalIds.joinToString(",")}")
    }
    return SharedConversationDirectoryState(
        projectionsByCanonicalId = byCanonicalId,
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
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = canonicalChannelId,
        aliases = aliases(canonicalChannelId).map { it.id }.filter { it.isNotBlank() }.toSet() + canonicalChannelId,
        conversationKind = kind,
        surfaceKind = kind.toKuiklySurfaceKind(),
        channelKind = toDirectoryChannelType(),
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = null,
        avatarLabel = title.take(2).uppercase(),
        peerIdentity =
            memberDetailIdentity(
                memberId = presenceMemberId?.value,
                kind = directMessagePeerType.parsedMemberKindOrNull(),
                sourceServerId = serverId.value
            ),
        parentChannelId = if (kind == ConversationKind.Thread) thread?.parentChannelId?.value else null,
        parentMessageId = if (kind == ConversationKind.Thread) thread?.parentMessageId?.value else null,
        joined = joined && permissions.canRead,
        archived = archived,
        activityNotificationPreference = activityNotificationPreference,
        permissions = permissions
    )
}

private fun InboxItem.toSharedConversationDirectoryProjections(
    serverId: ServerId,
    logger: (String) -> Unit
): List<SharedConversationDirectoryProjection> {
    val parentProjection =
        if (kind == InboxItemKind.Thread) {
            parentChannelProjection(serverId, logger)
        } else {
            null
        }
    val itemProjection = toSharedConversationDirectoryProjection(serverId)
    return listOfNotNull(parentProjection, itemProjection)
}

private fun InboxItem.parentChannelProjection(
    serverId: ServerId,
    logger: (String) -> Unit
): SharedConversationDirectoryProjection? {
    val parentId = parentChannelId?.value?.takeIf { it.isNotBlank() } ?: return null
    val title =
        parentChannelName
            .safeInboxDisplayNameOrNull(additionalIdentityIds = setOf(parentId))
            ?: parentId
    val conversationKind =
        parentChannelKind
            ?: run {
                logger(
                    "conversation_directory_parent_projection_drop " +
                        "reason=missing_parent_channel_kind serverId=${serverId.value} " +
                        "threadChannelId=${threadChannelId?.value.orEmpty()} parentChannelId=$parentId"
                )
                return null
            }
    val channelType = conversationKind.toDirectoryChannelType()
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = parentId,
        // The parent projection proves only the parent conversation. A thread
        // stream id belongs to the child ReplyThread projection and must not
        // be claimed as a parent alias.
        aliases = setOf(parentId),
        conversationKind = conversationKind,
        surfaceKind = conversationKind.toKuiklySurfaceKind(),
        channelKind = channelType,
        title = title,
        subtitle = null,
        parentChannelId = null,
        parentMessageId = null
    )
}

internal fun InboxItem.toSharedConversationDirectoryProjection(
    serverId: ServerId
): SharedConversationDirectoryProjection? {
    val canonicalChannelId =
        when (kind) {
            InboxItemKind.Thread -> threadChannelId ?: channelId
            else -> channelId
        }.value.takeIf { it.isNotBlank() } ?: return null
    val parentId = parentChannelId?.value?.takeIf { it.isNotBlank() }
    val parentMessage = parentMessageId?.value?.takeIf { it.isNotBlank() }
    val identityIds =
        buildSet {
            add(canonicalChannelId)
            channelId.value.takeIf { it.isNotBlank() }?.let(::add)
            threadChannelId?.value?.takeIf { it.isNotBlank() }?.let(::add)
            parentId?.let(::add)
            parentMessage?.let(::add)
        }
    val titleSource =
        when (kind) {
            InboxItemKind.Thread -> parentChannelName ?: channelName
            else -> channelName
        }
    val title =
        titleSource.safeInboxDisplayNameOrNull(additionalIdentityIds = identityIds)
            ?: canonicalChannelId
    val conversationKind = directoryConversationKind()
    val sender = toSharedConversationDirectorySender()
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = canonicalChannelId,
        aliases =
            buildSet {
                add(canonicalChannelId)
                channelId.value.takeIf { it.isNotBlank() }?.let(::add)
                threadChannelId?.value?.takeIf { it.isNotBlank() }?.let(::add)
                parentId?.let(::add)
                parentMessage?.let(::add)
                firstUnreadMessageId?.value?.takeIf { it.isNotBlank() }?.let(::add)
                lastMessageId?.value?.takeIf { it.isNotBlank() }?.let(::add)
                latestActivityMessageId?.value?.takeIf { it.isNotBlank() }?.let(::add)
            },
        conversationKind = conversationKind,
        surfaceKind = conversationKind.toKuiklySurfaceKind(),
        channelKind =
            when (kind) {
                InboxItemKind.Thread -> "thread"
                else -> channelKind.toDirectoryChannelType()
            },
        title = title,
        subtitle =
            if (kind == InboxItemKind.Thread) {
                parentMessagePreview?.takeIf { it.isNotBlank() }
            } else {
                lastMessagePreview?.takeIf { it.isNotBlank() }
            },
        avatarWireValue = null,
        avatarLabel = title.take(2).uppercase(),
        senderMessageIds = sender.messageIds,
        senderMemberId = sender.memberId,
        senderAvatarWireValue = sender.avatarWireValue,
        senderAvatarLabel = sender.label,
        peerIdentity = null,
        parentChannelId = parentId,
        parentMessageId = parentMessage
    )
}

private data class SharedConversationDirectorySender(
    val messageIds: Set<String>,
    val memberId: String?,
    val avatarWireValue: String?,
    val label: String?
)

private fun InboxItem.toSharedConversationDirectorySender(): SharedConversationDirectorySender {
    val latestMessageId = latestActivityMessageId?.value?.takeIf { it.isNotBlank() }
    val lastMessageId = lastMessageId?.value?.takeIf { it.isNotBlank() }
    val latest =
        sharedConversationDirectorySenderCandidate(
            messageIds = setOfNotNull(latestMessageId),
            kind = latestActivitySenderKind,
            id = latestActivitySenderId,
            avatarWireValue = latestActivitySenderAvatarWireValue,
            label = latestActivitySenderName
        )
    if (latest.memberId != null || latest.avatarWireValue != null || latest.label != null) return latest
    return sharedConversationDirectorySenderCandidate(
        messageIds = setOfNotNull(lastMessageId),
        kind = lastMessageSenderKind,
        id = lastMessageSenderId,
        avatarWireValue = lastMessageSenderAvatarWireValue,
        label = lastMessageSenderName
    )
}

private fun sharedConversationDirectorySenderCandidate(
    messageIds: Set<String>,
    kind: MemberKind?,
    id: String?,
    avatarWireValue: String?,
    label: String?
): SharedConversationDirectorySender {
    val cleanId = id?.takeIf { it.isNotBlank() }
    return SharedConversationDirectorySender(
        messageIds = messageIds,
        memberId = cleanId,
        avatarWireValue = avatarWireValue?.takeIf { it.isNotBlank() },
        label = label?.takeIf { it.isNotBlank() }
    )
}

private data class ConversationDirectoryAlias(
    val id: String,
    val kind: SharedConversationAliasKind
)

private fun HomeConversationRow.aliases(canonicalChannelId: String): Set<ConversationDirectoryAlias> =
    buildSet {
        val routeIdentity = resolveThreadRouteIdentity(
            surfaceKind = toDirectoryConversationKind().toKuiklySurfaceKind(),
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

private fun InboxItem.directoryConversationKind(): ConversationKind =
    when (kind) {
        InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
        InboxItemKind.Thread -> ConversationKind.Thread
        InboxItemKind.MentionAction -> channelKind ?: ConversationKind.Channel
        InboxItemKind.System -> ConversationKind.System
        InboxItemKind.Channel ->
            when (channelKind) {
                ConversationKind.Joint -> ConversationKind.Joint
                else -> ConversationKind.Channel
            }
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

private fun ConversationKind?.toDirectoryChannelType(): String =
    when (this) {
        ConversationKind.DirectMessage -> toWireChannelKindOrNull() ?: "dm"
        ConversationKind.Joint -> toWireChannelKindOrNull() ?: "joint"
        ConversationKind.Thread -> "thread"
        ConversationKind.Channel,
        ConversationKind.Inbox,
        ConversationKind.System,
        null -> "channel"
    }

private fun String?.safeInboxDisplayNameOrNull(
    additionalIdentityIds: Set<String> = emptySet()
): String? =
    conversationSafeDisplayTitleOrNull(this, additionalIdentityIds)
        ?.takeUnless { candidate -> candidate.isGenericInboxDisplayName(additionalIdentityIds) }

private fun String.isGenericInboxDisplayName(additionalIdentityIds: Set<String>): Boolean {
    val candidate = conversationDisplayTitleCandidate(this) ?: return true
    if (conversationIdentityLikeTitle(candidate, additionalIdentityIds)) return true
    return candidate.equals("Channel", ignoreCase = true) ||
        candidate.equals("Thread", ignoreCase = true) ||
        candidate.equals("Direct Message", ignoreCase = true)
}
