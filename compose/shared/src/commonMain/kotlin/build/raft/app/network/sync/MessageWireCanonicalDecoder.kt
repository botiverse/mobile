package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalAttachmentWire
import build.raft.app.canonical.message.CanonicalCommentHostSourceWire
import build.raft.app.canonical.message.CanonicalCommentRefWire
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalExternalAuthorWire
import build.raft.app.canonical.message.CanonicalMentionWire
import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.CanonicalReactionWire
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.canonical.message.TaskDomainFields
import build.raft.app.MemberKindWireContract
import build.raft.app.model.MemberKind
import build.raft.app.parsedMemberKindOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The three server producer faces covered by canonical-message manifest v4. */
enum class ProductionFace {
    MessageNew,
    EnrichedUpdated,
    TaskStatusUpdated,
}

/** Closed diagnostic data only: the raw message payload is deliberately never retained. */
data class MessageWireCanonicalViolation(
    val face: ProductionFace,
    val missingOrInvalidRequiredFields: Set<String>,
)

private val REQUIRED_WIRE_KEYS = setOf(
    "id",
    "seq",
    "channelId",
    "senderType",
    "senderId",
    "content",
    "messageType",
    "randomId",
    "threadId",
    "createdAt",
)

private val AGGREGATE_WIRE_KEYS = mapOf(
    "senderName" to MessageAggregateField.SenderName,
    "senderDescription" to MessageAggregateField.SenderDescription,
    "senderMembershipStatus" to MessageAggregateField.SenderMembershipStatus,
    "mentions" to MessageAggregateField.Mentions,
    "attachments" to MessageAggregateField.Attachments,
    "reactions" to MessageAggregateField.Reactions,
    "commentRef" to MessageAggregateField.CommentRef,
    "actionMetadata" to MessageAggregateField.ActionMetadata,
    "conversationContext" to MessageAggregateField.ConversationContext,
    "externalAuthor" to MessageAggregateField.ExternalAuthor,
)

private val TASK_WIRE_KEYS = setOf(
    "taskStatus",
    "taskNumber",
    "taskAssigneeType",
    "taskAssigneeId",
    "taskClaimedAt",
    "taskCompletedAt",
)

/**
 * Decode a raw socket message into the wire-shaped canonical row.
 *
 * Presence is intentionally computed before value decoding and only with [JsonObject.containsKey].
 * Therefore an explicit JSON null is present, while an omitted key is absent. A malformed or missing
 * canonical-required field fails closed and reports a bounded, payload-free diagnostic.
 */
fun decodeCanonicalMessageWire(
    rawJson: JsonObject,
    face: ProductionFace,
    onViolation: (MessageWireCanonicalViolation) -> Unit = {},
): CanonicalMessageCarry? {
    val presentAggregates = AGGREGATE_WIRE_KEYS
        .filterKeys(rawJson::containsKey)
        .values
        .toSet()

    // An External sender (Slack bridge, `external_projection` / `third_party_app`) may carry no
    // sender id; the row is kept with a blank one and projected with a synthetic, non-navigable key.
    // User/agent senders still require a string id.
    val externalSender =
        rawJson.strictString("senderType").parsedMemberKindOrNull(MemberKindWireContract.MessageSender) ==
            MemberKind.External
    val externalSenderIdMissing = externalSender && rawJson.strictString("senderId") == null
    val invalidRequired = buildSet {
        REQUIRED_WIRE_KEYS.filterTo(this) { !rawJson.containsKey(it) && !(it == "senderId" && externalSenderIdMissing) }
        if (rawJson.strictString("id") == null) add("id")
        if (rawJson.strictLong("seq") == null) add("seq")
        if (rawJson.strictString("channelId") == null) add("channelId")
        if (rawJson.strictString("senderType") == null) add("senderType")
        if (rawJson.strictString("senderId") == null && !externalSenderIdMissing) add("senderId")
        if (rawJson.strictString("content") == null) add("content")
        if (rawJson.strictString("messageType") == null) add("messageType")
        if (!rawJson.isNullableString("randomId")) add("randomId")
        if (!rawJson.isNullableString("threadId")) add("threadId")
        if (rawJson.strictString("createdAt") == null) add("createdAt")
    }
    if (invalidRequired.isNotEmpty()) {
        onViolation(MessageWireCanonicalViolation(face, invalidRequired))
        return null
    }

    val row = CanonicalMessageRow(
        id = rawJson.strictString("id")!!,
        channelId = rawJson.strictString("channelId")!!,
        seq = rawJson.strictLong("seq")!!,
        senderType = rawJson.strictString("senderType")!!,
        senderId = rawJson.strictString("senderId") ?: "",
        content = rawJson.strictString("content")!!,
        messageType = rawJson.strictString("messageType")!!,
        createdAt = rawJson.strictString("createdAt")!!,
        randomId = rawJson.nullableString("randomId"),
        threadId = rawJson.nullableString("threadId"),
        senderName = rawJson.strictString("senderName").orEmpty(),
        senderDescription = rawJson.nullableString("senderDescription"),
        senderMembershipStatus = rawJson.nullableString("senderMembershipStatus"),
        mentions = rawJson.objectArray("mentions").mapNotNull(JsonObject::toCanonicalMention),
        attachments = rawJson.objectArray("attachments").mapNotNull(JsonObject::toCanonicalAttachment),
        reactions = rawJson.objectArray("reactions").mapNotNull(JsonObject::toCanonicalReaction),
        commentRef = (rawJson["commentRef"] as? JsonObject)?.toCanonicalCommentRef(),
        actionMetadata = rawJson["actionMetadata"].takeUnless { it == null || it is JsonNull },
        conversationContext = (rawJson["conversationContext"] as? JsonObject)?.toCanonicalConversationContext(),
        // RFC 077: `null` = live, absent on the task-status projector; neither is a retraction.
        deletedAt = normalizeCanonicalRetraction(rawJson.nullableString("deletedAt")),
        // `null` for every non-external sender and for a non-object value. A partial object is kept:
        // absent fields are stored blank and the projection fills the display fallback.
        externalAuthor = (rawJson["externalAuthor"] as? JsonObject)?.toCanonicalExternalAuthor(),
    )

    return CanonicalMessageCarry(
        row = row,
        presentAggregates = presentAggregates,
        taskFields = rawJson.toTaskDomainFieldsOrNull(),
    )
}

private fun JsonObject.toTaskDomainFieldsOrNull(): TaskDomainFields? {
    if (TASK_WIRE_KEYS.none(::containsKey)) return null
    return TaskDomainFields(
        taskStatus = nullableString("taskStatus"),
        taskNumber = strictInt("taskNumber"),
        taskAssigneeType = nullableString("taskAssigneeType"),
        taskAssigneeId = nullableString("taskAssigneeId"),
        taskClaimedAt = nullableString("taskClaimedAt"),
        taskCompletedAt = nullableString("taskCompletedAt"),
    )
}

private fun JsonObject.toCanonicalAttachment(): CanonicalAttachmentWire? {
    val id = strictString("id") ?: return null
    val filename = strictString("filename") ?: return null
    val mimeType = strictString("mimeType") ?: return null
    val sizeBytes = strictLong("sizeBytes") ?: return null
    return CanonicalAttachmentWire(
        id = id,
        filename = filename,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = strictInt("width"),
        height = strictInt("height"),
        thumbnailUrl = nullableString("thumbnailUrl"),
    )
}

private fun JsonObject.toCanonicalReaction(): CanonicalReactionWire? {
    val emoji = strictString("emoji") ?: return null
    val count = strictInt("count") ?: return null
    return CanonicalReactionWire(
        emoji = emoji,
        count = count,
        reactorIds = stringArray("reactorIds"),
        reactorNames = stringArray("reactorNames"),
    )
}

private fun JsonObject.toCanonicalMention(): CanonicalMentionWire? {
    val id = strictString("id") ?: return null
    val name = strictString("name") ?: return null
    val type = strictString("type") ?: return null
    return CanonicalMentionWire(id = id, name = name, type = type)
}

private fun JsonObject.toCanonicalCommentRef(): CanonicalCommentRefWire? {
    val attachmentId = strictString("attachmentId") ?: return null
    val filename = strictString("filename") ?: return null
    return CanonicalCommentRefWire(
        attachmentId = attachmentId,
        filename = filename,
        hostMessageId = nullableString("hostMessageId"),
        hostSource = (this["hostSource"] as? JsonObject)?.toCanonicalCommentHostSource(),
        anchorLabel = nullableString("anchorLabel"),
        anchorQuote = nullableString("anchorQuote"),
    )
}

private fun JsonObject.toCanonicalCommentHostSource(): CanonicalCommentHostSourceWire? {
    val type = strictString("type") ?: return null
    val routeKind = strictString("routeKind") ?: return null
    val channelId = strictString("channelId") ?: return null
    return CanonicalCommentHostSourceWire(
        type = type,
        routeKind = routeKind,
        channelId = channelId,
        parentMessageId = nullableString("parentMessageId"),
        threadChannelId = nullableString("threadChannelId"),
        rootThreadChannelId = nullableString("rootThreadChannelId"),
    )
}

private fun JsonObject.toCanonicalConversationContext(): CanonicalConversationContextWire? {
    val channelType = strictString("channelType") ?: return null
    return CanonicalConversationContextWire(
        channelType = channelType,
        parentChannelId = nullableString("parentChannelId"),
        parentChannelType = nullableString("parentChannelType"),
        parentMessageId = nullableString("parentMessageId"),
    )
}

/**
 * Keeps whatever the wire carried. The persisted row shape requires every identity string, so an
 * absent one is stored as `""` (and an absent revision as 0); the app projection treats blank as
 * absent and shows the fallback author rather than dropping the message.
 */
private fun JsonObject.toCanonicalExternalAuthor(): CanonicalExternalAuthorWire =
    CanonicalExternalAuthorWire(
        actorKind = strictString("actorKind").orEmpty(),
        actorProjectionRevision = strictLong("actorProjectionRevision") ?: 0L,
        appRegistrationId = strictString("appRegistrationId").orEmpty(),
        avatarDigest = nullableString("avatarDigest"),
        avatarUrl = nullableString("avatarUrl"),
        displayName = strictString("displayName").orEmpty(),
        externalActorId = strictString("externalActorId").orEmpty(),
        externalConversationId = strictString("externalConversationId").orEmpty(),
        externalMessageId = strictString("externalMessageId").orEmpty(),
        installId = strictString("installId").orEmpty(),
        projectionId = strictString("projectionId").orEmpty(),
        provider = strictString("provider").orEmpty(),
        workspaceId = strictString("workspaceId").orEmpty(),
        workspaceName = nullableString("workspaceName"),
    )

private fun JsonObject.objectArray(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }.orEmpty()

private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.nullableString(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
    else -> null
}

private fun JsonObject.isNullableString(key: String): Boolean = when (val value = this[key]) {
    JsonNull -> true
    is JsonPrimitive -> value.isString
    else -> false
}

private fun JsonObject.strictLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

private fun JsonObject.strictInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
