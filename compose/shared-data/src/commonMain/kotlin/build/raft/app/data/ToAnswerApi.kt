package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Activity's "To answer" list (`GET /api/actions/to-answer`, botiverse/slock #17252 / #17261,
 * `packages/shared/src/toAnswer.ts`, `docs/contracts/to-answer.md`): open question, confirm, and
 * request action cards waiting on the signed-in reader in one Server, behind `to_answer_list`.
 */
@RaftCoreInternalApi enum class ToAnswerCardKind(val wireValue: String) {
    Question("question"),
    Confirm("confirm"),
    Request("request");

    companion object {
        fun fromWire(value: String): ToAnswerCardKind? =
            entries.firstOrNull { it.wireValue == value }
    }
}

@RaftCoreInternalApi enum class ToAnswerAddressee(val wireValue: String) {
    You("you"),
    Anyone("anyone");

    companion object {
        fun fromWire(value: String): ToAnswerAddressee? =
            entries.firstOrNull { it.wireValue == value }
    }
}

@RaftCoreInternalApi enum class ToAnswerRequesterType(val wireValue: String) {
    Agent("agent"),
    User("user");

    companion object {
        fun fromWire(value: String): ToAnswerRequesterType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

@RaftCoreInternalApi data class ToAnswerConversationParent(
    val id: String,
    val type: String,
    val name: String,
)

@RaftCoreInternalApi data class ToAnswerConversation(
    val id: String,
    val type: String,
    val name: String,
    val parent: ToAnswerConversationParent? = null,
    val parentMessageId: String? = null,
)

@RaftCoreInternalApi data class ToAnswerRequester(
    val type: ToAnswerRequesterType,
    val id: String,
    val name: String,
)

@RaftCoreInternalApi data class ToAnswerItem(
    val messageId: String,
    val kind: ToAnswerCardKind,
    val addressee: ToAnswerAddressee,
    val conversation: ToAnswerConversation,
    val requester: ToAnswerRequester? = null,
    val text: String? = null,
    val moreQuestions: Int = 0,
    val requestTitle: ActionCardSpecMsg? = null,
    val createdAt: String,
    val expiresAt: String? = null,
)

@RaftCoreInternalApi data class ToAnswerListResponse(
    val items: List<ToAnswerItem>,
    val count: Int,
    val capped: Boolean = false,
)

@RaftCoreInternalApi const val TO_ANSWER_LIST_LIMIT: Int = 100
@RaftCoreInternalApi const val TO_ANSWER_TEXT_MAX: Int = 280

private val toAnswerJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Parses a `GET /api/actions/to-answer` JSON payload into [ToAnswerListResponse], or `null` when
 * the top-level structure is malformed.
 */
@RaftCoreInternalApi fun parseToAnswerListResponse(bodyText: String): ToAnswerListResponse? {
    val root = runCatching { toAnswerJson.parseToJsonElement(bodyText) as? JsonObject }.getOrNull() ?: return null
    val rawItems = root["items"] as? JsonArray ?: return null
    val items = rawItems.mapNotNull { element -> (element as? JsonObject)?.let(::parseToAnswerItem) }
    val count =
        (root["count"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.intOrNull
            ?.coerceAtLeast(0)
            ?: items.size
    val capped =
        (root["capped"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull == true
    return ToAnswerListResponse(
        items = items,
        count = count,
        capped = capped,
    )
}

private fun parseToAnswerItem(obj: JsonObject): ToAnswerItem? {
    val messageId = obj.nonBlankString("messageId") ?: return null
    val kind = obj.nonBlankString("kind")?.let(ToAnswerCardKind::fromWire) ?: return null
    val addressee = obj.nonBlankString("addressee")?.let(ToAnswerAddressee::fromWire) ?: return null
    val conversation = (obj["conversation"] as? JsonObject)?.let(::parseToAnswerConversation) ?: return null
    val createdAt = obj.nonBlankString("createdAt") ?: return null
    val requester = (obj["requester"] as? JsonObject)?.let(::parseToAnswerRequester)
    val text =
        (obj["text"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    val moreQuestions =
        (obj["moreQuestions"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.intOrNull
            ?.coerceAtLeast(0)
            ?: 0
    val requestTitle =
        (obj["request"] as? JsonObject)
            ?.get("title")
            ?.let(::parseToAnswerRequestTitle)
    val expiresAt = obj.nonBlankString("expiresAt")
    return ToAnswerItem(
        messageId = messageId,
        kind = kind,
        addressee = addressee,
        conversation = conversation,
        requester = requester,
        text = text,
        moreQuestions = moreQuestions,
        requestTitle = requestTitle,
        createdAt = createdAt,
        expiresAt = expiresAt,
    )
}

private fun parseToAnswerConversation(obj: JsonObject): ToAnswerConversation? {
    val id = obj.nonBlankString("id") ?: return null
    val type = obj.nonBlankString("type") ?: return null
    val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    val parent =
        (obj["parent"] as? JsonObject)?.let { parentObj ->
            val parentId = parentObj.nonBlankString("id") ?: return@let null
            val parentType = parentObj.nonBlankString("type") ?: return@let null
            val parentName = (parentObj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            ToAnswerConversationParent(id = parentId, type = parentType, name = parentName)
        }
    val parentMessageId = obj.nonBlankString("parentMessageId")
    return ToAnswerConversation(
        id = id,
        type = type,
        name = name,
        parent = parent,
        parentMessageId = parentMessageId,
    )
}

private fun parseToAnswerRequester(obj: JsonObject): ToAnswerRequester? {
    val type = obj.nonBlankString("type")?.let(ToAnswerRequesterType::fromWire) ?: return null
    val id = obj.nonBlankString("id") ?: return null
    val name = obj.nonBlankString("name") ?: return null
    return ToAnswerRequester(type = type, id = id, name = name)
}

private fun parseToAnswerRequestTitle(raw: JsonElement): ActionCardSpecMsg? {
    if (raw is JsonNull) return null
    parseActionCardSpecMsg(raw)?.let { return it }
    // Lenient fallback for request titles whose key namespace is not `actionCard.*` (e.g. fixtures
    // or future server namespaces), keeping the exact same template + params shape.
    val obj = raw as? JsonObject ?: return null
    val key = obj.nonBlankString("key") ?: return null
    val textObj = obj["text"] as? JsonObject ?: return null
    val english = (textObj["en"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
    val text = LinkedHashMap<String, String>()
    text["en"] = english
    for ((locale, value) in textObj) {
        val normalizedLocale = locale.lowercase()
        if (normalizedLocale == "en") continue
        val template = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: continue
        text[normalizedLocale] = template
    }
    val params =
        (obj["params"] as? JsonObject)?.let { rawParams ->
            val parsed = LinkedHashMap<String, ActionCardSpecMsgParam>()
            for ((name, value) in rawParams) {
                parseLenientMsgParam(value)?.let { parsed[name] = it }
            }
            parsed.takeIf { it.isNotEmpty() }
        }
    return ActionCardSpecMsg(key = key, text = text, params = params)
}

private fun parseLenientMsgParam(raw: JsonElement): ActionCardSpecMsgParam? =
    when (raw) {
        is JsonObject -> {
            val kindWire = raw.nonBlankString("kind") ?: return null
            val kind = ActionCardSpecEntityKind.entries.firstOrNull { it.wire == kindWire } ?: return null
            val id = raw.nonBlankString("id") ?: return null
            val fallback = raw.nonBlankString("fallback") ?: return null
            ActionCardSpecMsgParam.Entity(
                ActionCardSpecEntityRef(
                    kind = kind,
                    id = id,
                    serverId = raw.nonBlankString("serverId"),
                    fallback = fallback,
                )
            )
        }
        is JsonPrimitive ->
            when {
                raw.isString -> ActionCardSpecMsgParam.Text(raw.content)
                raw.longOrNull != null -> ActionCardSpecMsgParam.Integer(raw.longOrNull!!)
                else -> null
            }
        else -> null
    }

private fun JsonObject.nonBlankString(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

@RaftCoreInternalApi interface RaftToAnswerApi {
    suspend fun listToAnswer(serverId: ServerId): DataResult<ToAnswerListResponse>
}

@RaftCoreInternalApi class NetworkRaftToAnswerApi(
    private val networkClient: RaftNetworkClient,
) : RaftToAnswerApi {
    override suspend fun listToAnswer(serverId: ServerId): DataResult<ToAnswerListResponse> {
        val response =
            try {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "actions/to-answer",
                    method = "GET",
                    serverIdOverride = serverId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "network", retryable = true, traceId = null))
            }
        if (response.isSuccessful) {
            val body = response.bodyText ?: return DataResult.Error(ScreenError(code = "malformed_body", traceId = response.traceId))
            val parsed = parseToAnswerListResponse(body)
                ?: return DataResult.Error(ScreenError(code = "malformed_body", traceId = response.traceId))
            return DataResult.Content(parsed)
        }
        val envelope = response.parseHttpErrorEnvelope()
        return DataResult.Error(
            ScreenError(
                code = "http_${response.code}",
                retryable = response.code >= 500 || response.code == 429,
                serverMessage = envelope?.serverMessage,
                serverCode = envelope?.serverCode,
                traceId = response.traceId,
            )
        )
    }
}
