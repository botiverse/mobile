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
    /** Activity's own sender names by member id (what its rows show), humans and agents alike. */
    val senderNames: Map<String, String> = emptyMap(),
)

internal fun SharedActivityState.toThreadActivityOpenSeedSource(): ThreadActivityOpenSeedSource =
    ThreadActivityOpenSeedSource(
        ownerServerId = ownerServerId ?: snapshot?.server?.id?.value,
        ownerUserId = ownerUserId,
        items = (listOfNotNull(snapshot) + filterSnapshots.values).flatMap { it.items },
        senderNames = senderProfiles.mapValues { (_, profile) -> profile.displayName },
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
    memberNames: Map<String, String> = emptyMap(),
): List<RaftThreadMessageItem> {
    if (serverId.isBlank() || source.ownerServerId != serverId) return emptyList()
    if (source.ownerUserId != null && source.ownerUserId != principalId) return emptyList()
    val stream = routeIdentity.timelineChannelId ?: return emptyList()
    if (!routeIdentity.canRenderTimelineCandidates) return emptyList()
    val byId = LinkedHashMap<String, RaftThreadMessageItem>()
    val names = ActivitySeedNames(memberNames = memberNames, activityNames = source.senderNames)
    source.items
        .filter { item -> item.activitySeedStreamId(routeIdentity) == stream }
        .forEach { item ->
            item.activitySeedMessages(principalId, names).forEach { message ->
                val existing = byId[message.id]
                // A reply window entry carries the server seq; it wins over a row-derived copy, and a
                // full body wins over a cut preview of the same message.
                if (
                    existing == null ||
                        (existing.seq == null && message.seq != null) ||
                        (existing.activityPreviewTruncated && !message.activityPreviewTruncated)
                ) {
                    byId[message.id] = message
                }
            }
        }
    return byId.values.toList().sortedByThreadTimeline()
}

/**
 * artin 10-26: a seed message whose Activity preview was cut takes the full copy of the same message
 * when this device already holds one. [localMessages] are the open path's other candidate frames (the
 * superseded memory copy, the durable last-known frame), each already matched to the route identity
 * and owner; only a message with the same id is taken, and never another cut stand-in.
 */
internal fun List<RaftThreadMessageItem>.withLocalFullMessages(
    localMessages: Collection<RaftThreadMessageItem>,
): List<RaftThreadMessageItem> {
    if (none { it.activityPreviewTruncated } || localMessages.isEmpty()) return this
    val fullById =
        localMessages
            .filter { local -> !local.activityPreviewTruncated && local.body.isNotBlank() }
            .associateBy { it.id }
    return map { seed -> if (seed.activityPreviewTruncated) fullById[seed.id] ?: seed else seed }
}

/** The server's preview ellipsis (`truncatePreview`, `historyLimitedPreview`). */
internal const val ActivityPreviewEllipsis = "…"

/** `inboxPolicyModel.truncatePreview(…, 140)`: the first 140 UTF-16 units, then "…". */
internal const val ActivityPreviewMaxLength = 140

/**
 * Whether an Activity row's preview is the server's cut of a longer message. The inbox row carries no
 * "truncated" field, so this reads the cut the server makes on every row (slock packages/server
 * `inboxPolicyModel.truncatePreview`): exactly 140 UTF-16 units plus "…" (both sides count UTF-16
 * units). The server's other cut, the 40-character glimpse of a message behind a plan's history
 * window (`historyPreview.historyLimitedPreview`), is not flagged: neither the row nor the inbox page
 * says a row is behind the window, and a real 40-character message ending in "…" (the client's own
 * realtime preview keeps the full body) must not get a loading indicator.
 */
internal fun activitySeedPreviewIsTruncated(preview: String): Boolean =
    preview.endsWith(ActivityPreviewEllipsis) &&
        preview.length == ActivityPreviewMaxLength + ActivityPreviewEllipsis.length

/**
 * A cut seed message whose full copy did not arrive (the main load failed, or the page is offline):
 * no loading indicator that would spin forever. The body still ends in the server's "…", so the cut
 * text does not read as the whole message.
 */
internal fun RaftThreadMessageItem.withActivityPreviewSettled(): RaftThreadMessageItem =
    if (activityPreviewTruncated) copy(activityPreviewTruncated = false) else this

internal fun RaftThreadScreenPayload.withActivityPreviewsSettled(): RaftThreadScreenPayload =
    if (messages.none { it.activityPreviewTruncated }) {
        this
    } else {
        copy(messages = messages.map { it.withActivityPreviewSettled() })
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
        InboxItemKind.System, InboxItemKind.Object -> null
    }?.takeIf { it.isNotBlank() }

private fun InboxItem.activitySeedMessages(
    principalId: String,
    names: ActivitySeedNames,
): List<RaftThreadMessageItem> =
    when (kind) {
        InboxItemKind.Thread ->
            latestReplies.mapNotNull { reply ->
                reply.toActivitySeedMessage(principalId, names)?.withActivityRetraction(this)
            } +
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
                        names = names,
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
                    names = names,
                )
            )
        InboxItemKind.System, InboxItemKind.Object -> emptyList()
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
    names: ActivitySeedNames,
): RaftThreadMessageItem? {
    val messageId = id?.takeIf { it.isNotBlank() } ?: return null
    val preview = body?.takeIf { it.isNotBlank() } ?: return null
    // artin 10-26: a cut preview keeps its "…" (it says the text is cut) and gets a loading indicator
    // below it until the full message arrives.
    val truncated = activitySeedPreviewIsTruncated(preview)
    val at = createdAt?.takeIf { it.isNotBlank() } ?: return null
    val author = senderId?.takeIf { it.isNotBlank() } ?: return null
    // Web `isSystemSender`: a membership note is stored as sender type `user` with sender id `system`,
    // so the sender kind alone does not tell (task #80, "@Jasper joined this channel." as a chat row).
    val system = senderKind == MemberKind.System || author == ActivitySeedSystemSenderId
    val name = if (system) ActivitySeedSystemAuthorName else names.resolve(author, senderName)
    return RaftThreadMessageItem(
        id = messageId,
        authorId = author,
        authorName = name,
        authorHandle = name,
        authorKind = if (system) "system" else senderKind.activitySeedAuthorKind(),
        isOwn = !system && author == principalId,
        avatarLabel = name.toRaftAvatarLabel(),
        avatarWireValue = senderAvatar?.takeIf { it.isNotBlank() && !system },
        delegatedBy = latestDelegatedBy.takeIf { messageId == (latestActivityMessageId ?: lastMessageId)?.value },
        timestampLabel = at,
        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(at),
        messageType = if (system) "System" else "Chat",
        body = preview,
        activityPreviewTruncated = truncated,
    ).withActivityRetraction(this)
}

/**
 * RFC 077 PR 2b: a seeded copy of the row's latest message is retracted when the row says so
 * (wire `latestRetracted`; it names the thread row's `latestActivityMessageId`, else
 * `lastMessageId`). The row carries only the flag, so the item gets [RetractedAtUnknown].
 */
private fun RaftThreadMessageItem.withActivityRetraction(row: InboxItem): RaftThreadMessageItem {
    val latestId = if (row.kind == InboxItemKind.Thread) row.latestActivityMessageId else row.lastMessageId
    return if (row.latestRetracted && id == latestId?.value) {
        copy(deletedAt = RetractedAtUnknown, activityPreviewTruncated = false)
    } else {
        this
    }
}

private fun ReplyPreview.toActivitySeedMessage(
    principalId: String,
    names: ActivitySeedNames,
): RaftThreadMessageItem? {
    if (messageId.isBlank() || content.isBlank()) return null
    val kind =
        when (senderType.trim().lowercase()) {
            "user", "human" -> "human"
            "agent" -> "agent"
            "system" -> "system"
            else -> return null
        }.let { projected -> if (senderId.trim() == ActivitySeedSystemSenderId) "system" else projected }
    val name =
        if (kind == "system") {
            ActivitySeedSystemAuthorName
        } else {
            names.resolve(senderId, senderDisplayName, senderName)
        }
    return RaftThreadMessageItem(
        id = messageId,
        authorId = senderId,
        authorName = name,
        authorHandle = senderName,
        authorKind = kind,
        isOwn = kind != "system" && senderId == principalId,
        avatarLabel = name.toRaftAvatarLabel(),
        avatarWireValue = senderAvatarUrl?.takeIf { it.isNotBlank() && kind != "system" },
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

/** The sender id the server stores on membership and other system notes (web `isSystemSender`). */
internal const val ActivitySeedSystemSenderId = "system"

/** A seeded system note's author: the system row shows no author, but the data stays a name, not an id. */
internal const val ActivitySeedSystemAuthorName = "System"

/**
 * Names a seeded row may show for its sender (task #80: a human sender showed as a raw id until the
 * load finished). Most specific first: the server member directory, then the names the Activity rows
 * themselves show, then whatever the row carried. A raw id is never a name; with nothing known the
 * name stays blank and the row shows its neutral "Unknown" until the canonical load names it.
 */
internal class ActivitySeedNames(
    private val memberNames: Map<String, String> = emptyMap(),
    private val activityNames: Map<String, String> = emptyMap(),
) {
    fun resolve(senderId: String, vararg carried: String?): String =
        activitySeedAuthorName(senderId, memberNames, activityNames, carried.toList())
}

/** See [ActivitySeedNames]; pure so the precedence is unit-tested. */
internal fun activitySeedAuthorName(
    senderId: String,
    memberNames: Map<String, String>,
    activityNames: Map<String, String>,
    carried: List<String?>,
): String {
    val id = senderId.trim()
    return (sequenceOf(memberNames[id], activityNames[id]) + carried.asSequence())
        .mapNotNull { candidate -> candidate?.trim()?.takeIf { it.isNotBlank() && it != id } }
        .firstOrNull()
        .orEmpty()
}
