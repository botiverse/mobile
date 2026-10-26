@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package build.raft.app.network.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val CANONICAL_MESSAGE_V2_UNSUPPORTED_SCHEMA: String = "unsupported_canonical_message_schema"
const val CANONICAL_MESSAGE_V2_EXACT_KEYS: String = "canonical_message_exact_keys"
const val CANONICAL_MESSAGE_V2_REACTION_BOUND_EXCEEDED: String = "canonical_reaction_bound_exceeded"
const val CANONICAL_MESSAGE_V2_CROSS_SERVER_REF: String = "cross_server_embedded_ref"
const val CANONICAL_MESSAGE_V2_ILLEGAL_DISCUSSION_RELATION: String = "illegal_discussion_relation"
const val CANONICAL_MESSAGE_V2_SCOPE_POISONED: String = "canonical_v2_scope_poisoned"
const val VIEWER_OVERLAY_VERSION_OUT_OF_RANGE: String = "viewer_overlay_version_out_of_range"

sealed interface CanonicalMessageV2DecodeResult {
    data class Accepted(val envelope: CanonicalMessageEnvelopeV2) : CanonicalMessageV2DecodeResult
    data class Rejected(val diagnostic: String) : CanonicalMessageV2DecodeResult
}

private val canonicalMessageV2Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
    encodeDefaults = true
    prettyPrint = false
}

/** Strict full-v6 decode. Schema selection happens before structural decode, so v1/v5/future bytes share one gate. */
fun decodeCanonicalMessageV2(
    text: String,
    durablePartitionServerId: String? = null,
): CanonicalMessageV2DecodeResult =
    try {
        decodeCanonicalMessageV2(canonicalMessageV2Json.parseToJsonElement(text), durablePartitionServerId)
    } catch (_: SerializationException) {
        CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
    } catch (_: IllegalArgumentException) {
        CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
    }

fun decodeCanonicalMessageV2(
    element: JsonElement,
    durablePartitionServerId: String? = null,
): CanonicalMessageV2DecodeResult {
    val root = element as? JsonObject
        ?: return CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
    val schemaPrimitive = root["schemaVersion"] as? JsonPrimitive
    val schemaVersion = schemaPrimitive?.takeIf { !it.isString }?.intOrNull
    val kind = (root["kind"] as? JsonPrimitive)?.canonicalStringOrNull()
    if (
        schemaVersion != CANONICAL_MESSAGE_FULL_V2_SCHEMA_VERSION ||
        kind != CANONICAL_MESSAGE_FULL_V2_ENVELOPE_KIND
    ) {
        return CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_UNSUPPORTED_SCHEMA)
    }

    validateExactV2Shape(root)?.let { return CanonicalMessageV2DecodeResult.Rejected(it) }
    val envelope = try {
        canonicalMessageV2Json.decodeFromString<CanonicalMessageEnvelopeV2>(root.toString())
    } catch (_: SerializationException) {
        return CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
    } catch (_: IllegalArgumentException) {
        return CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS)
    }
    validateCanonicalMessageV2(envelope, durablePartitionServerId)?.let {
        return CanonicalMessageV2DecodeResult.Rejected(it)
    }
    return CanonicalMessageV2DecodeResult.Accepted(envelope.canonicalized())
}

/** Byte-stable canonical encoding. Callers cannot encode an invalid/cross-partition envelope. */
fun encodeCanonicalMessageV2(
    envelope: CanonicalMessageEnvelopeV2,
    durablePartitionServerId: String? = null,
): String {
    val canonical = envelope.canonicalized()
    val diagnostic = validateCanonicalMessageV2(canonical, durablePartitionServerId)
    require(diagnostic == null) { diagnostic ?: CANONICAL_MESSAGE_V2_EXACT_KEYS }
    return canonicalMessageV2Json.encodeToString(canonical)
}

fun validateCanonicalMessageV2(
    envelope: CanonicalMessageEnvelopeV2,
    durablePartitionServerId: String? = null,
): String? {
    if (
        envelope.schemaVersion != CANONICAL_MESSAGE_FULL_V2_SCHEMA_VERSION ||
        envelope.kind != CANONICAL_MESSAGE_FULL_V2_ENVELOPE_KIND
    ) return CANONICAL_MESSAGE_V2_UNSUPPORTED_SCHEMA

    val deletedAt = envelope.fact.deletedAt
    if (deletedAt != null && normalizeCanonicalRetraction(deletedAt) == null) return CANONICAL_MESSAGE_V2_EXACT_KEYS

    (envelope.fact.actionMetadata as? CanonicalActionMetadataV2.ActionCard)?.let { card ->
        if (
            card.sourceServerId?.isNotCanonicalString() == true ||
            card.targetServerName?.isNotCanonicalString() == true ||
            (card.confirmationVersion ?: 0) < 0
        ) return CANONICAL_MESSAGE_V2_EXACT_KEYS
    }

    val reactions = envelope.fact.reactions
    if (
        reactions.size > CANONICAL_REACTION_LIMIT ||
        reactions.any { reaction ->
            reaction.emoji.isNotCanonicalString() ||
                reaction.count < 0 ||
                reaction.previewK.size > CANONICAL_REACTION_PREVIEW_LIMIT ||
                reaction.previewK.size > reaction.count ||
                reaction.previewK.any { it.id.isNotCanonicalString() || it.displayName.isNotCanonicalString() } ||
                !reaction.previewK.map(ReactionActorRef::id).isStrictlyIncreasing()
        } ||
        !reactions.map(ReactionFact::emoji).isStrictlyIncreasing()
    ) return CANONICAL_MESSAGE_V2_REACTION_BOUND_EXCEEDED

    if (durablePartitionServerId != null) {
        if (durablePartitionServerId.isBlank()) return CANONICAL_MESSAGE_V2_CROSS_SERVER_REF
        if (envelope.embeddedServerIds().any { it != durablePartitionServerId }) {
            return CANONICAL_MESSAGE_V2_CROSS_SERVER_REF
        }
    }

    when (val context = envelope.context) {
        is CanonicalConversationContextV2.TopLevel -> {
            if (envelope.replyMembership != null) return CANONICAL_MESSAGE_V2_ILLEGAL_DISCUSSION_RELATION
            if (context.scope.scopeId.isNotCanonicalString()) return CANONICAL_MESSAGE_V2_EXACT_KEYS
        }
        is CanonicalConversationContextV2.Thread -> {
            val membership = envelope.replyMembership
                ?: return CANONICAL_MESSAGE_V2_ILLEGAL_DISCUSSION_RELATION
            if (context.replyScope != membership.parentScope || context.rootMessageRef != membership.root) {
                return CANONICAL_MESSAGE_V2_CONTEXT_MEMBERSHIP_MISMATCH
            }
            if (envelope.childReplyScope != null) return CANONICAL_MESSAGE_V2_ILLEGAL_DISCUSSION_RELATION
        }
    }

    return null
}

/** Exact context/relation decoder used by restore and normalization gates before persistence. */
fun decodeCanonicalMessageV2Context(
    context: JsonElement,
    childReplyScope: JsonElement = JsonNull,
    replyMembership: JsonElement = JsonNull,
    durablePartitionServerId: String? = null,
): CanonicalMessageV2DecodeResult {
    val placeholder = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(CANONICAL_MESSAGE_FULL_V2_SCHEMA_VERSION),
            "kind" to JsonPrimitive(CANONICAL_MESSAGE_FULL_V2_ENVELOPE_KIND),
            "fact" to canonicalMessageV2Json.parseToJsonElement(
                """{"id":"context-probe","seq":0,"senderType":"system","senderId":"system","content":"","messageType":"chat","createdAt":"1970-01-01T00:00:00.000Z","randomId":null,"senderName":"system","senderDescription":null,"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"actionMetadata":null,"replyCount":0}""",
            ),
            "context" to context,
            "childReplyScope" to childReplyScope,
            "replyMembership" to replyMembership,
        ),
    )
    return decodeCanonicalMessageV2(placeholder, durablePartitionServerId)
}

fun validateCanonicalActionMetadataKind(element: JsonElement): String? {
    if (element is JsonNull) return null
    val kind = ((element as? JsonObject)?.get("kind") as? JsonPrimitive)?.canonicalStringOrNull()
    return if (kind == "action-card" || kind == "forwarded-bundle") null
    else CANONICAL_MESSAGE_V2_UNSUPPORTED_ACTION_METADATA_KIND
}

private fun validateExactV2Shape(root: JsonObject): String? {
    if (!root.hasExactKeys("schemaVersion", "kind", "fact", "context", "childReplyScope", "replyMembership")) {
        return CANONICAL_MESSAGE_V2_EXACT_KEYS
    }
    val fact = root["fact"] as? JsonObject ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    if (!fact.hasExactKeys(*CANONICAL_MESSAGE_V2_FACT_KEYS) &&
        !fact.hasExactKeys(*CANONICAL_MESSAGE_V2_FACT_KEYS, CANONICAL_MESSAGE_V2_RETRACTION_KEY)
    ) return CANONICAL_MESSAGE_V2_EXACT_KEYS
    // RFC 077: when present, `deletedAt` is a canonical timestamp string — never null (omit the key).
    fact[CANONICAL_MESSAGE_V2_RETRACTION_KEY]?.let { deletedAt ->
        val value = (deletedAt as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (normalizeCanonicalRetraction(value) == null) return CANONICAL_MESSAGE_V2_EXACT_KEYS
    }

    val mentions = fact["mentions"] as? JsonArray ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    if (mentions.any { (it as? JsonObject)?.hasExactKeys("id", "name", "type") != true }) {
        return CANONICAL_MESSAGE_V2_EXACT_KEYS
    }
    val attachments = fact["attachments"] as? JsonArray ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    if (attachments.any {
            (it as? JsonObject)?.hasExactKeys("id", "filename", "mimeType", "sizeBytes", "width", "height", "thumbnailUrl") != true
        }
    ) return CANONICAL_MESSAGE_V2_EXACT_KEYS
    val reactions = fact["reactions"] as? JsonArray ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    if (reactions.any { reactionElement ->
            val reaction = reactionElement as? JsonObject ?: return@any true
            if (!reaction.hasExactKeys("emoji", "count", "previewK")) return@any true
            val preview = reaction["previewK"] as? JsonArray ?: return@any true
            preview.any { (it as? JsonObject)?.hasExactKeys("id", "displayName") != true }
        }
    ) return CANONICAL_MESSAGE_V2_EXACT_KEYS

    validateContextShape(root["context"])?.let { return it }
    validateNullableReplyScope(root["childReplyScope"])?.let { return it }
    validateMembershipShape(root["replyMembership"])?.let { return it }
    validateActionMetadataShape(fact["actionMetadata"])?.let { return it }
    return null
}

private val CANONICAL_MESSAGE_V2_FACT_KEYS = arrayOf(
    "id", "seq", "senderType", "senderId", "content", "messageType", "createdAt", "randomId",
    "senderName", "senderDescription", "senderMembershipStatus", "mentions", "attachments", "reactions",
    "actionMetadata", "replyCount",
)

private val CANONICAL_ACTION_CARD_KEYS = setOf(
    "kind", "version", "action", "state", "executedAt", "executedByUserName", "result",
)

/** Manifest v8: the additive optional action-card keys (absent = not known; never `null`). */
private val CANONICAL_ACTION_CARD_OPTIONAL_KEYS = setOf(
    "sourceServerId", "targetServerName", "confirmationVersion", "presentation",
)

/** The one optional `$.fact` key (RFC 077): present only on a retracted message. */
private const val CANONICAL_MESSAGE_V2_RETRACTION_KEY = "deletedAt"

private fun validateContextShape(element: JsonElement?): String? {
    val context = element as? JsonObject ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    return when ((context["kind"] as? JsonPrimitive)?.canonicalStringOrNull()) {
        "top-level" -> {
            if (!context.hasExactKeys("kind", "scope")) CANONICAL_MESSAGE_V2_EXACT_KEYS
            else validateTopLevelScope(context["scope"])
        }
        "thread" -> {
            if (!context.hasExactKeys("kind", "replyScope", "parentTopLevelScope", "rootMessageRef")) {
                CANONICAL_MESSAGE_V2_EXACT_KEYS
            } else {
                validateReplyScope(context["replyScope"])
                    ?: validateTopLevelScope(context["parentTopLevelScope"])
                    ?: validateMessageRef(context["rootMessageRef"])
            }
        }
        else -> CANONICAL_MESSAGE_V2_EXACT_KEYS
    }
}

private fun validateTopLevelScope(element: JsonElement?): String? {
    val scope = element as? JsonObject ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    if (!scope.hasExactKeys("kind", "serverId", "scopeId")) return CANONICAL_MESSAGE_V2_EXACT_KEYS
    return if ((scope["kind"] as? JsonPrimitive)?.canonicalStringOrNull() in setOf("channel", "private", "dm", "joint")) {
        null
    } else {
        CANONICAL_MESSAGE_V2_EXACT_KEYS
    }
}

private fun validateNullableReplyScope(element: JsonElement?): String? =
    if (element == null || element is JsonNull) null else validateReplyScope(element)

private fun validateReplyScope(element: JsonElement?): String? =
    if ((element as? JsonObject)?.hasExactKeys("serverId", "scopeId") == true) null
    else CANONICAL_MESSAGE_V2_EXACT_KEYS

private fun validateMessageRef(element: JsonElement?): String? =
    if ((element as? JsonObject)?.hasExactKeys("serverId", "messageId") == true) null
    else CANONICAL_MESSAGE_V2_EXACT_KEYS

private fun validateMembershipShape(element: JsonElement?): String? {
    if (element == null || element is JsonNull) return null
    val membership = element as? JsonObject ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    if (!membership.hasExactKeys("root", "parentScope")) return CANONICAL_MESSAGE_V2_EXACT_KEYS
    return validateMessageRef(membership["root"]) ?: validateReplyScope(membership["parentScope"])
}

private fun validateActionMetadataShape(element: JsonElement?): String? {
    if (element == null || element is JsonNull) return null
    val action = element as? JsonObject ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
    return when ((action["kind"] as? JsonPrimitive)?.canonicalStringOrNull()) {
        "action-card" -> {
            if (!action.keys.containsAll(CANONICAL_ACTION_CARD_KEYS) ||
                !(CANONICAL_ACTION_CARD_KEYS + CANONICAL_ACTION_CARD_OPTIONAL_KEYS).containsAll(action.keys)
            ) {
                return CANONICAL_MESSAGE_V2_EXACT_KEYS
            }
            // Manifest v8 optional keys: present only with a value, never `null`.
            if (CANONICAL_ACTION_CARD_OPTIONAL_KEYS.any { action[it] is JsonNull }) return CANONICAL_MESSAGE_V2_EXACT_KEYS
            action["presentation"]?.let { if (it !is JsonObject) return CANONICAL_MESSAGE_V2_EXACT_KEYS }
            val body = action["action"] as? JsonObject ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
            if (!body.hasExactKeys(
                    "type", "name", "description", "visibility", "channel", "suggestedComputer", "requiredComputer",
                    "humans", "agents", "initialHumans", "initialAgents", "draftHint", "requestId", "agentId",
                    "agentName", "clientId", "clientKey", "clientName", "scopes",
                )
            ) return CANONICAL_MESSAGE_V2_EXACT_KEYS
            val result = action["result"]
            if (result != null && result !is JsonNull &&
                (result as? JsonObject)?.hasExactKeys(
                    "kind", "id", "name", "channelId", "channelName", "addedHumanIds", "addedAgentIds",
                ) != true
            ) CANONICAL_MESSAGE_V2_EXACT_KEYS else null
        }
        "forwarded-bundle" -> {
            if (!action.hasExactKeys("kind", "version", "items")) return CANONICAL_MESSAGE_V2_EXACT_KEYS
            val items = action["items"] as? JsonArray ?: return CANONICAL_MESSAGE_V2_EXACT_KEYS
            if (items.any { itemElement ->
                    val item = itemElement as? JsonObject ?: return@any true
                    if (!item.hasExactKeys("index", "author", "createdAt", "target", "content", "attachments")) return@any true
                    val author = item["author"]
                    if (author != null && author !is JsonNull &&
                        (author as? JsonObject)?.hasExactKeys("type", "name", "uniqueName") != true
                    ) return@any true
                    val target = item["target"]
                    if (target != null && target !is JsonNull &&
                        (target as? JsonObject)?.hasExactKeys("type", "label", "labelVisibility") != true
                    ) return@any true
                    val itemAttachments = item["attachments"] as? JsonArray ?: return@any true
                    itemAttachments.any { (it as? JsonObject)?.hasExactKeys("filename", "mimeType") != true }
                }
            ) CANONICAL_MESSAGE_V2_EXACT_KEYS else null
        }
        else -> CANONICAL_MESSAGE_V2_UNSUPPORTED_ACTION_METADATA_KIND
    }
}

private fun CanonicalMessageEnvelopeV2.canonicalized(): CanonicalMessageEnvelopeV2 =
    copy(
        fact = fact.copy(
            reactions = fact.reactions.sortedBy(ReactionFact::emoji).map { reaction ->
                reaction.copy(previewK = reaction.previewK.sortedBy(ReactionActorRef::id))
            },
            actionMetadata = fact.actionMetadata?.canonicalized(),
        ),
    )

private fun CanonicalActionMetadataV2.canonicalized(): CanonicalActionMetadataV2 =
    when (this) {
        is CanonicalActionMetadataV2.ActionCard -> copy(
            result = result?.copy(
                addedHumanIds = result.addedHumanIds.sorted(),
                addedAgentIds = result.addedAgentIds.sorted(),
            ),
        )
        is CanonicalActionMetadataV2.ForwardedBundle -> copy(items = items.sortedBy(CanonicalForwardedBundleItemV2::index))
    }

private fun CanonicalMessageEnvelopeV2.embeddedServerIds(): List<String> = buildList {
    when (val context = context) {
        is CanonicalConversationContextV2.TopLevel -> add(context.scope.serverId)
        is CanonicalConversationContextV2.Thread -> {
            add(context.replyScope.serverId)
            add(context.parentTopLevelScope.serverId)
            add(context.rootMessageRef.serverId)
        }
    }
    childReplyScope?.let { add(it.serverId) }
    replyMembership?.let {
        add(it.root.serverId)
        add(it.parentScope.serverId)
    }
}

private fun JsonObject.hasExactKeys(vararg expected: String): Boolean =
    keys.size == expected.size && keys == expected.toSet()

private fun JsonPrimitive.canonicalStringOrNull(): String? =
    content.takeIf { isString && it.isNotBlank() && it == it.trim() }

private fun String.isNotCanonicalString(): Boolean = isBlank() || this != trim()

private fun List<String>.isStrictlyIncreasing(): Boolean =
    indices.all { index -> index == 0 || this[index - 1] < this[index] }
