package ai.slock.compose.data

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.toGravatarAvatarUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val homeJson = Json { ignoreUnknownKeys = true }

fun parseSlockHome(
    payload: HomeNetworkPayload,
    session: StoredAuthSession,
    warningLogger: (String) -> Unit = {}
): DataResult<HomeSnapshot> {
    payload.failedResponse()?.let { response ->
        return response.toHomeFailure()
    }

    val currentServer = session.servers.firstOrNull { it.id == payload.serverId.value }?.toServerSummary()
        ?: session.currentServerId?.let { id ->
            session.servers.firstOrNull { it.id == id }?.toServerSummary()
        }
        ?: return DataResult.Empty(EmptyReason.NoChannels)
    val currentMember = session.user.toMemberProfile()

    val channels =
        payload.channels.bodyText
            ?.parseJsonElementOrNull()
            ?.homeConversationItems(
                source = "home_channels",
                names = arrayOf("channels", "items", "data"),
                warningLogger = warningLogger
            )
            .orEmpty()
            .mapNotNull { item ->
                item.asObjectOrNull()?.toConversationOrNull(
                    kind = ConversationKind.Channel,
                    source = "home_channels",
                    warningLogger = warningLogger
                )?.takeUnless { it.archived }
            }
            .dedupeHomeConversations()

    val directMessages =
        payload.directMessages.bodyText
            ?.parseJsonElementOrNull()
            ?.homeConversationItems(
                source = "home_direct_messages",
                names = arrayOf("directMessages", "dmChannels", "dms", "channels", "items", "data"),
                warningLogger = warningLogger
            )
            .orEmpty()
            .mapNotNull { item ->
                item.asObjectOrNull()?.toConversationOrNull(
                    kind = ConversationKind.DirectMessage,
                    source = "home_direct_messages",
                    warningLogger = warningLogger
                )
            }
            .dedupeHomeConversations()

    if (channels.isEmpty() && directMessages.isEmpty()) {
        return DataResult.Empty(EmptyReason.NoChannels)
    }

    val unreadCounts = payload.unreadCounts.bodyText?.parseJsonElementOrNull()?.asObjectOrNull()?.toChannelUnreadCounts().orEmpty()
    val channelsWithUnread = channels.applyUnreadCounts(unreadCounts)
    val directMessagesWithUnread = directMessages.applyUnreadCounts(unreadCounts)
    val unreadTotal = channelsWithUnread.sumOf { it.unreadCount } + directMessagesWithUnread.sumOf { it.unreadCount }

    return DataResult.Content(
        HomeSnapshot(
            server = currentServer,
            currentMember = currentMember,
            channels = channelsWithUnread,
            directMessages = directMessagesWithUnread,
            unreadTotal = unreadTotal
        )
    )
}

private fun HomeNetworkPayload.failedResponse(): HomeNetworkResponse? =
    listOf(channels, directMessages, unreadCounts, serverUnreadSummary).firstOrNull { !it.isSuccessful }

private fun HomeNetworkResponse.toHomeFailure(): DataResult<HomeSnapshot> =
    if (code == 401 || code == 403) {
        DataResult.NoPermission(PermissionBlocker(code = "home_http_$code"))
    } else {
        DataResult.Error(ScreenError(code = "home_http_$code", retryable = true))
    }

private fun String.parseJsonElementOrNull(): JsonElement? =
    runCatching { homeJson.parseToJsonElement(this) }.getOrNull()

private fun JsonElement.homeConversationItems(
    source: String,
    names: Array<String>,
    warningLogger: (String) -> Unit
): List<JsonElement> {
    if (this is JsonArray) return this
    val root = asObjectOrNull() ?: return emptyList()
    val items = root.items(*names)
    if (items.isNotEmpty()) return items
    val nestedItems = root.objectOrNull("data")?.items(*names).orEmpty()
    if (nestedItems.isNotEmpty()) return nestedItems
    warningLogger("empty_items source=$source stage=list_extract fieldPath=${names.joinToString("|")}")
    return emptyList()
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.items(vararg names: String): List<JsonElement> {
    for (name in names) {
        when (val value = this[name]) {
            is JsonArray -> return value
            is JsonObject -> {
                val nested = value.items(*names)
                if (nested.isNotEmpty()) return nested
            }
            else -> Unit
        }
    }
    return emptyList()
}

private fun JsonObject.toConversationOrNull(
    kind: ConversationKind,
    source: String,
    warningLogger: (String) -> Unit
): ConversationSummary? {
    val id = string("id", "channelId", "threadChannelId", "_id")
    if (id == null) {
        warningLogger("skip_item source=$source stage=item_map fieldPath=id|channelId|threadChannelId|_id reason=missing_id")
        return null
    }
    return toConversation(kind, id, source, warningLogger)
}

private fun JsonObject.toConversation(
    kind: ConversationKind,
    id: String,
    source: String,
    warningLogger: (String) -> Unit
): ConversationSummary {
    val presenceSource = firstObject("peer", "agent", "user", "member", "profile")
    val presenceMemberKind = explicitMemberKindOrNull() ?: presenceSource?.explicitMemberKindOrNull()
    val name =
        if (kind == ConversationKind.DirectMessage) {
            directMessagePeerName(presenceSource)
        } else {
            string("name", "channelName", "slug", "channelSlug", "title")
                ?: presenceSource?.string("displayName", "name", "username", "email")
                ?: id
        }
    val joined = if (kind == ConversationKind.DirectMessage) true else channelJoined()
    val canSend = boolean("canSend", "canWrite", "canMessage") ?: joined
    val avatarWireValue = conversationAvatarWireValue()
    if (kind == ConversationKind.DirectMessage && avatarWireValue.isNullOrBlank()) {
        warningLogger(
            "dm_avatar_missing source=$source peerId=${presenceSource?.string("id", "agentId", "userId", "memberId") ?: id} peerName=$name finalAvatar=missing rootAvatarKeys=${avatarDebugKeys()} rootAvatarTypes=${avatarDebugTypes()} nestedAvatarKeys=${presenceSource?.avatarDebugKeys() ?: "none"} nestedAvatarTypes=${presenceSource?.avatarDebugTypes() ?: "none"}"
        )
    }
    val unreadCount = int("unreadCount", "unread")
    val mentionCount = int("mentionCount", "mentions")
    val hasDraft = boolean("hasDraft", "draft", "has_draft")
    val agentStatus =
        string(
            "agentStatus",
            "agent_status",
            "activity",
            "agentActivity",
            "agent_activity",
            "agentStatusLabel",
            "agent_status_label",
            "connectionStatus",
            "connection_status",
            "state",
            "agentState",
            "agent_state",
            "agentPresence"
        ).toAgentStatusOrNull()
            ?: presenceSource?.string(
                "agentStatus",
                "agent_status",
                "activity",
                "status",
                "presence",
                "availability",
                "connectionStatus",
                "state"
            ).toAgentStatusOrNull()
    val presenceMemberId =
        string("agentId", "userId", "memberId", "peerId")
            ?: presenceSource?.string("id", "agentId", "userId", "memberId")
    val presence =
        when {
            kind == ConversationKind.DirectMessage && presenceMemberKind == ParsedMemberKind.Agent ->
                agentStatus?.presence
                    ?: string("presence", "agentPresence").toPresenceOrNull()
                    ?: presenceSource?.string("presence", "agentPresence", "status").toPresenceOrNull()
            kind == ConversationKind.DirectMessage && agentStatus != null -> agentStatus.presence
            else -> null
        }
    return ConversationSummary(
        threadId = ThreadId(id),
        channelId = ChannelId(id),
        kind = kind,
        name = name,
        subtitle = string("description", "subtitle", "topic", "purpose"),
        avatarUrl = avatarWireValue?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        unreadCount = unreadCount ?: 0,
        unreadCountKnown = unreadCount != null,
        mentionCount = mentionCount ?: 0,
        mentionCountKnown = mentionCount != null,
        lastMessageId = string("lastMessageId", "latestMessageId")?.let(::MessageId),
        firstUnreadMessageId = string("firstUnreadMessageId")?.let(::MessageId),
        lastMessagePreview = string("lastMessagePreview", "latestActivityPreview", "preview", "lastMessage"),
        lastMessageAt = string("lastMessageAt", "latestActivityAt", "updatedAt", "lastActivityAt"),
        lastMessageSenderName = string("lastMessageSenderName", "latestActivitySenderName", "senderName"),
        presence = presence,
        agentStatus = agentStatus,
        presenceMemberId = presenceMemberId?.let(::MemberId),
        hasDraft = hasDraft ?: false,
        hasDraftKnown = hasDraft != null,
        joined = joined,
        archived = boolean("archived", "isArchived", "deleted", "isDeleted") ?: false,
        memberCount = int("memberCount", "membersCount", "participantCount"),
        permissions =
            ConversationPermissions(
                canRead = boolean("canRead") ?: true,
                canSend = canSend,
                canAttach = boolean("canAttach", "canUpload") ?: canSend,
                canCreateTask = boolean("canCreateTask") ?: canSend
            )
    )
}

private fun JsonObject.toChannelUnreadCounts(): Map<ChannelId, Int> {
    val unwrapped = objectOrNull("data") ?: objectOrNull("unreadCounts") ?: objectOrNull("counts") ?: this
    val directEntries =
        unwrapped.entries.mapNotNull { (key, value) ->
            val count =
                when (value) {
                    is JsonPrimitive -> value.contentOrNull?.toIntOrNull()
                    is JsonObject -> value.int("unreadCount", "unread", "count")
                    else -> null
                }
            if (count == null) null else ChannelId(key) to count
        }
    if (directEntries.isNotEmpty()) return directEntries.toMap()
    return unwrapped.items("channels", "items", "counts")
        .mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val channelId = item.string("channelId", "id", "threadChannelId") ?: return@mapNotNull null
            ChannelId(channelId) to (item.int("unreadCount", "unread", "count") ?: 0)
        }
        .toMap()
}

private fun List<ConversationSummary>.applyUnreadCounts(counts: Map<ChannelId, Int>): List<ConversationSummary> =
    map { conversation ->
        val channelId = conversation.channelId
        val count = if (channelId == null) null else counts[channelId]
        if (count == null) conversation else conversation.copy(unreadCount = count, unreadCountKnown = true)
    }

private fun List<ConversationSummary>.dedupeHomeConversations(): List<ConversationSummary> {
    val deduped = LinkedHashMap<String, ConversationSummary>()
    for (conversation in this) {
        val key = if (conversation.kind == ConversationKind.Thread) {
            conversation.threadId.value
        } else {
            conversation.channelId?.value ?: conversation.threadId.value
        }
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}

private fun JsonObject.string(vararg names: String): String? {
    for (name in names) {
        val value = this[name]
        if (value is JsonPrimitive) {
            value.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return null
}

private fun JsonObject.int(vararg names: String): Int? {
    for (name in names) {
        val primitive = this[name] as? JsonPrimitive ?: continue
        primitive.intOrNull?.let { return it }
        primitive.contentOrNull?.toIntOrNull()?.let { return it }
    }
    return null
}

private fun JsonObject.boolean(vararg names: String): Boolean? {
    for (name in names) {
        val primitive = this[name] as? JsonPrimitive ?: continue
        primitive.booleanOrNull?.let { return it }
        primitive.contentOrNull?.lowercase()?.let { value ->
            when (value) {
                "true", "1", "yes" -> return true
                "false", "0", "no" -> return false
            }
        }
    }
    return null
}

private fun JsonObject.firstObject(vararg names: String): JsonObject? =
    names.firstNotNullOfOrNull { name -> this[name] as? JsonObject }

private enum class ParsedMemberKind { Agent, Human, System }

private fun JsonObject.explicitMemberKindOrNull(): ParsedMemberKind? =
    string("kind", "memberKind", "type", "memberType", "senderType")?.lowercase()?.let { value ->
        when {
            value.contains("agent") || value.contains("bot") -> ParsedMemberKind.Agent
            value.contains("human") || value.contains("user") -> ParsedMemberKind.Human
            value.contains("system") -> ParsedMemberKind.System
            else -> null
        }
    }

private fun JsonObject.channelJoined(): Boolean =
    boolean("joined", "isJoined", "member", "isMember", "participating") ?: true

private fun JsonObject.directMessagePeerName(presenceSource: JsonObject?): String =
    string(
        "peerDisplayName",
        "peerName",
        "peerUsername"
    )
        ?: presenceSource?.string("displayName", "name", "username", "email")
        ?: string("id", "channelId", "threadChannelId", "_id")
        ?: "Direct message"

private fun JsonObject.conversationAvatarWireValue(): String? =
    avatarWireValue(
        "avatar",
        "avatarUrl",
        "avatar_url",
        "image",
        "imageUrl",
        "image_url",
        "photo",
        "photoUrl",
        "photo_url",
        "picture",
        "pictureUrl",
        "picture_url",
        "profileImage",
        "profileImageUrl",
        "profile_image_url",
        "icon",
        "iconUrl",
        "icon_url",
        "peerAvatar",
        "peerAvatarUrl",
        "peerImageUrl",
        "peerProfileImageUrl",
        "agentAvatar",
        "agentAvatarUrl",
        "agentImageUrl",
        "agentProfileImageUrl",
        "userAvatar",
        "userAvatarUrl",
        "userImageUrl",
        "userProfileImageUrl"
    )
        ?: firstObject("peer", "agent", "user", "member", "profile")?.avatarWireValue(
            "avatar",
            "avatarUrl",
            "avatar_url",
            "image",
            "imageUrl",
            "image_url",
            "photo",
            "photoUrl",
            "photo_url",
            "picture",
            "pictureUrl",
            "picture_url",
            "profileImage",
            "profileImageUrl",
            "profile_image_url",
            "icon",
            "iconUrl",
            "icon_url"
        )
        ?: (string(
            "gravatarHash",
            "gravatar_hash",
            "gravatar",
            "gravatarUrl",
            "gravatar_url",
            "emailHash",
            "email_hash",
            "emailMd5",
            "email_md5",
            "peerGravatarHash",
            "peerGravatar",
            "peerEmailHash",
            "peerEmailMd5",
            "agentGravatarHash",
            "agentGravatar",
            "agentEmailHash",
            "agentEmailMd5",
            "userGravatarHash",
            "userGravatar",
            "userEmailHash",
            "userEmailMd5"
        )
            ?: firstObject("peer", "agent", "user", "member", "profile")?.string(
                "gravatarHash",
                "gravatar_hash",
                "gravatar",
                "gravatarUrl",
                "gravatar_url",
                "emailHash",
                "email_hash",
                "emailMd5",
                "email_md5"
            ))?.toGravatarWireValue()

private fun JsonObject.avatarWireValue(vararg names: String): String? {
    for (name in names) {
        val value = this[name]?.toAvatarWireValue()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun JsonObject.avatarDebugKeys(): String =
    keys
        .filter { key ->
            val normalized = key.lowercase()
            "avatar" in normalized ||
                "gravatar" in normalized ||
                "image" in normalized ||
                "photo" in normalized ||
                "picture" in normalized ||
                "icon" in normalized ||
                "emailhash" in normalized ||
                "email_hash" in normalized
        }
        .sorted()
        .joinToString("|")
        .ifBlank { "none" }

private fun JsonObject.avatarDebugTypes(): String =
    keys
        .filter { key ->
            val normalized = key.lowercase()
            "avatar" in normalized ||
                "gravatar" in normalized ||
                "image" in normalized ||
                "photo" in normalized ||
                "picture" in normalized ||
                "icon" in normalized ||
                "emailhash" in normalized ||
                "email_hash" in normalized
        }
        .sorted()
        .joinToString("|") { key -> "$key=${this[key].debugShape()}" }
        .ifBlank { "none" }

private fun JsonElement?.debugShape(): String =
    when (this) {
        null -> "null"
        is JsonArray -> "array"
        is JsonObject -> "object(${keys.sorted().joinToString(",")})"
        is JsonPrimitive ->
            when {
                isString -> "string"
                booleanOrNull != null -> "boolean"
                intOrNull != null -> "number"
                else -> "primitive"
            }
    }

private fun JsonElement.toAvatarWireValue(): String? {
    if (this is JsonPrimitive) {
        return contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.toAvatarWireValueString()
    }
    val avatarObject = asObjectOrNull() ?: return null
    val type = avatarObject.string("type", "kind", "mode")?.lowercase()
    val typedValue =
        avatarObject.string(
            "wireValue",
            "value",
            "id",
            "name",
            "slug",
            "key",
            "hash",
            "md5",
            "gravatarHash",
            "gravatar_hash",
            "gravatar"
        )
    when (type) {
        "pixel" -> return "pixel:${typedValue?.removePrefix("pixel:") ?: "robot"}"
        "random" -> return "random:${typedValue?.removePrefix("random:") ?: "default"}"
        "gravatar", "gravatar_hash", "gravatarhash" ->
            return typedValue?.toGravatarWireValue()
        "url", "image", "photo" -> return typedValue
    }
    avatarObject.string(
        "wireValue",
        "value",
        "url",
        "href",
        "src",
        "avatarUrl",
        "avatar_url",
        "imageUrl",
        "image_url",
        "photoUrl",
        "photo_url",
        "picture",
        "pictureUrl",
        "picture_url",
        "profileImage",
        "profileImageUrl",
        "profile_image_url",
        "iconUrl",
        "icon_url"
    )?.let { return it.toAvatarWireValueString() }
    avatarObject.avatarWireValue("data", "profile")?.let { return it }
    avatarObject.string(
        "gravatarHash",
        "gravatar_hash",
        "gravatar",
        "hash",
        "md5"
    )?.let { return it.toGravatarWireValue() }
    avatarObject.string("pixel", "pixelKey", "key", "icon")?.let { key ->
        return "pixel:${key.removePrefix("pixel:")}"
    }
    avatarObject.string("random", "randomSeed", "seed")?.let { seed ->
        return "random:${seed.removePrefix("random:")}"
    }
    return null
}

private fun String.toAvatarWireValueString(): String =
    if (startsWith("gravatar:", ignoreCase = true)) {
        substringAfter(":").toGravatarWireValue()
    } else {
        this
    }

private fun String.toGravatarWireValue(): String =
    when {
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true) -> this
        startsWith("gravatar:", ignoreCase = true) -> substringAfter(":").toGravatarAvatarUrl()
        else ->
            trim()
                .takeIf { it.isNotBlank() }
                ?.toGravatarAvatarUrl()
                .orEmpty()
    }

private fun String?.toPresenceOrNull(): Presence? =
    when (this?.lowercase()) {
        "active", "online", "available" -> Presence.Active
        "away", "idle", "busy" -> Presence.Away
        "offline", "disconnected", "inactive" -> Presence.Offline
        else -> null
    }

private fun String?.toAgentStatusOrNull(): AgentStatus? =
    when (this?.lowercase()) {
        "online", "active", "available" -> AgentStatus.Online
        "working", "busy", "running", "in_progress" -> AgentStatus.Working
        "thinking", "reasoning" -> AgentStatus.Thinking
        "idle", "away" -> AgentStatus.Idle
        "disconnected" -> AgentStatus.Disconnected
        "offline" -> AgentStatus.Offline
        "error", "failed" -> AgentStatus.Error
        else -> null
    }
