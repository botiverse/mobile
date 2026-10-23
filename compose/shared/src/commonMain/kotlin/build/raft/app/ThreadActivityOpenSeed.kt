package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.InboxItemKind
import build.raft.app.data.SharedActivityState
import build.raft.app.model.MemberKind
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.network.sync.ReplyPreview

/**
 * What the Activity list already knows, read by the thread page when it opens (artin 10-23: "Activity
 * has the data, the page I tap into shows none of it").
 *
 * This is a candidate snapshot, not a route payload: the route still carries only identity and intent
 * (AGENTS.md, Route / PageData MVI Contract Rule). The thread store looks the conversation up here by
 * its own route identity, keeps only rows of the same owner (server + signed-in user) and the same
 * message stream, and shows those messages until the canonical load replaces them.
 */
internal data class ThreadActivityOpenSeedSource(
    val ownerServerId: String?,
    val ownerUserId: String?,
    val items: List<InboxItem>,
)

internal fun SharedActivityState.toThreadActivityOpenSeedSource(): ThreadActivityOpenSeedSource =
    ThreadActivityOpenSeedSource(
        ownerServerId = ownerServerId ?: snapshot?.server?.id?.value,
        ownerUserId = ownerUserId,
        items = (listOfNotNull(snapshot) + filterSnapshots.values).flatMap { it.items },
    )

/**
 * The messages Activity holds for exactly the conversation [routeIdentity] names, oldest first, or
 * empty. Every row kind is covered: a channel, DM (people or agents) or mention row gives its latest
 * message; a thread row gives its latest replies window and its latest reply. Rows of another server or
 * user, another stream, or a route that has no resolved stream yet give nothing.
 */
internal fun threadActivityOpenSeedMessages(
    source: ThreadActivityOpenSeedSource,
    routeIdentity: ThreadRouteIdentity,
    serverId: String,
    principalId: String,
): List<RaftThreadMessageItem> {
    if (serverId.isBlank() || source.ownerServerId != serverId) return emptyList()
    if (source.ownerUserId != null && source.ownerUserId != principalId) return emptyList()
    val stream = routeIdentity.timelineChannelId ?: return emptyList()
    if (!routeIdentity.canRenderTimelineCandidates) return emptyList()
    val byId = LinkedHashMap<String, RaftThreadMessageItem>()
    source.items
        .filter { item -> item.activitySeedStreamId(routeIdentity) == stream }
        .forEach { item ->
            item.activitySeedMessages(principalId).forEach { message ->
                val existing = byId[message.id]
                // A reply window entry carries the server seq; it wins over a row-derived copy.
                if (existing == null || (existing.seq == null && message.seq != null)) {
                    byId[message.id] = message
                }
            }
        }
    return byId.values.toList().sortedByThreadTimeline()
}

/**
 * Adds the Activity messages to a first-frame candidate that is not canonical yet: nothing (the
 * loading frame), the durable last-known frame, or an older memory copy. The seed only fills what the
 * candidate lacks: a message already on the frame is kept as it is, and a seed message is added only
 * when it is newer than everything the frame holds. Paging stays closed on the seeded part
 * ([RaftThreadScreenPayload.durableLastKnown] marks it a stand-in), and the canonical load replaces the
 * whole timeline, so a seed message never doubles a canonical one.
 */
internal fun RaftThreadScreenPayload.withActivityOpenSeed(
    seed: List<RaftThreadMessageItem>,
): RaftThreadScreenPayload {
    if (seed.isEmpty()) return this
    val standInEmpty = messages.isEmpty() && (status == "loading" || status == "empty")
    if (standInEmpty) {
        return copy(
            status = "content",
            messages = seed,
            durableLastKnown = true,
            durableReachedOldest = false,
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            errorCode = null,
        )
    }
    val knownIds = messages.mapTo(mutableSetOf()) { it.id }
    val newestSeq = newestServerSeqOrNull()
    val newestCreatedAt = messages.mapNotNull { it.createdAtEpochMillis }.maxOrNull()
    val newer =
        seed.filter { message ->
            message.id !in knownIds &&
                when {
                    message.seq != null && newestSeq != null -> message.seq > newestSeq
                    message.createdAtEpochMillis != null && newestCreatedAt != null ->
                        message.createdAtEpochMillis > newestCreatedAt
                    else -> false
                }
        }
    if (newer.isEmpty()) return this
    return copy(
        messages = (messages + newer).sortedByThreadTimeline(),
        durableLastKnown = true,
        canLoadMoreAfter = false,
    )
}

private fun InboxItem.activitySeedStreamId(routeIdentity: ThreadRouteIdentity): String? =
    when (kind) {
        InboxItemKind.Thread ->
            threadChannelId?.value?.takeIf { routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread }
        InboxItemKind.Channel,
        InboxItemKind.DirectMessage,
        InboxItemKind.MentionAction -> channelId.value
        InboxItemKind.System -> null
    }?.takeIf { it.isNotBlank() }

private fun InboxItem.activitySeedMessages(principalId: String): List<RaftThreadMessageItem> =
    when (kind) {
        InboxItemKind.Thread ->
            latestReplies.mapNotNull { reply -> reply.toActivitySeedMessage(principalId) } +
                listOfNotNull(
                    activitySeedMessage(
                        id = latestActivityMessageId?.value,
                        body = latestActivityPreview,
                        createdAt = latestActivityAt,
                        senderId = latestActivitySenderId,
                        senderName = latestActivitySenderName,
                        senderKind = latestActivitySenderKind,
                        senderAvatar = latestActivitySenderAvatarWireValue,
                        principalId = principalId,
                    )
                )
        InboxItemKind.Channel,
        InboxItemKind.DirectMessage,
        InboxItemKind.MentionAction ->
            listOfNotNull(
                activitySeedMessage(
                    id = lastMessageId?.value,
                    body = lastMessagePreview,
                    createdAt = lastMessageAt,
                    senderId = lastMessageSenderId,
                    senderName = lastMessageSenderName,
                    senderKind = lastMessageSenderKind,
                    senderAvatar = lastMessageSenderAvatarWireValue,
                    principalId = principalId,
                )
            )
        InboxItemKind.System -> emptyList()
    }

private fun InboxItem.activitySeedMessage(
    id: String?,
    body: String?,
    createdAt: String?,
    senderId: String?,
    senderName: String?,
    senderKind: MemberKind?,
    senderAvatar: String?,
    principalId: String,
): RaftThreadMessageItem? {
    val messageId = id?.takeIf { it.isNotBlank() } ?: return null
    val text = body?.takeIf { it.isNotBlank() } ?: return null
    val at = createdAt?.takeIf { it.isNotBlank() } ?: return null
    val author = senderId?.takeIf { it.isNotBlank() } ?: return null
    val name = senderName?.takeIf { it.isNotBlank() } ?: author
    return RaftThreadMessageItem(
        id = messageId,
        authorId = author,
        authorName = name,
        authorHandle = name,
        authorKind = senderKind.activitySeedAuthorKind(),
        isOwn = author == principalId,
        avatarLabel = name.toRaftAvatarLabel(),
        avatarWireValue = senderAvatar?.takeIf { it.isNotBlank() },
        delegatedBy = latestDelegatedBy.takeIf { messageId == (latestActivityMessageId ?: lastMessageId)?.value },
        timestampLabel = at,
        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(at),
        messageType = if (senderKind == MemberKind.System) "System" else "Chat",
        body = text,
    )
}

private fun ReplyPreview.toActivitySeedMessage(principalId: String): RaftThreadMessageItem? {
    if (messageId.isBlank() || content.isBlank()) return null
    val kind =
        when (senderType.trim().lowercase()) {
            "user", "human" -> "human"
            "agent" -> "agent"
            "system" -> "system"
            else -> return null
        }
    val name =
        senderDisplayName?.trim()?.takeIf { it.isNotBlank() }
            ?: senderName.trim().takeIf { it.isNotBlank() }
            ?: senderId
    return RaftThreadMessageItem(
        id = messageId,
        authorId = senderId,
        authorName = name,
        authorHandle = senderName,
        authorKind = kind,
        isOwn = senderId == principalId,
        avatarLabel = name.toRaftAvatarLabel(),
        avatarWireValue = senderAvatarUrl?.takeIf { it.isNotBlank() },
        timestampLabel = createdAt,
        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(createdAt),
        seq = seq,
        messageType = if (kind == "system") "System" else "Chat",
        body = content,
    )
}

private fun MemberKind?.activitySeedAuthorKind(): String =
    when (this) {
        MemberKind.Agent -> "agent"
        MemberKind.System -> "system"
        MemberKind.External -> "external"
        else -> "human"
    }
