package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val messageTranslationsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Typed message-translation boundary.
 *
 * Server contract (`packages/server/src/routes/messageTranslations.ts` +
 * `services/messageTranslationService.ts`):
 *  - `POST message-translations:batch` body
 *    `{ messageIds: string[] (≤200), targetLanguage, mode: "auto" | "manual" }`
 *    → `{ results: [{ messageId, status, translatedContent?, skipReason?,
 *        sourceLanguage?, targetLanguage?, ... }] }`
 *  - 403 when server translation is disabled; 404/501 when the surface is
 *    absent. Both mean "translation unavailable", not a per-message failure.
 *
 * Field names are exact camelCase. No snake_case fallbacks.
 */
internal const val MESSAGE_TRANSLATION_MAX_BATCH_SIZE = 200

internal const val MESSAGE_TRANSLATION_MODE_AUTO = "auto"
internal const val MESSAGE_TRANSLATION_MODE_MANUAL = "manual"

@Serializable
private data class WireMessageTranslationResult(
    val messageId: String,
    val status: String = "not_found",
    val translatedContent: String? = null,
    val skipReason: String? = null,
    val reason: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
)

@Serializable
private data class WireMessageTranslationBatchResponse(
    val results: List<WireMessageTranslationResult> = emptyList()
)

enum class SharedMessageTranslationStatus {
    Pending,
    Translated,
    Skipped,
    Failed,
    NotFound;

    companion object {
        fun fromWire(value: String?): SharedMessageTranslationStatus =
            when (value) {
                "pending" -> Pending
                "translated" -> Translated
                "skipped" -> Skipped
                "failed" -> Failed
                else -> NotFound
            }
    }
}

data class SharedMessageTranslationResult(
    val messageId: String,
    val status: SharedMessageTranslationStatus,
    val reason: String? = null,
    val translatedContent: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
)

internal fun normalizeSharedMessageTranslationReason(reason: String?): String? =
    when (reason) {
        "provider_failed" -> "provider"
        "placeholder_mismatch" -> "placeholder"
        else -> reason
    }

fun parseSharedMessageTranslationBatch(bodyText: String): List<SharedMessageTranslationResult>? = runCatching {
    val response =
        messageTranslationsJson.decodeFromString(WireMessageTranslationBatchResponse.serializer(), bodyText)
    response.results
        .filter { it.messageId.isNotBlank() }
        .map { wire ->
            SharedMessageTranslationResult(
                messageId = wire.messageId,
                status = SharedMessageTranslationStatus.fromWire(wire.status),
                reason = normalizeSharedMessageTranslationReason(wire.skipReason ?: wire.reason),
                translatedContent = wire.translatedContent,
                sourceLanguage = wire.sourceLanguage,
                targetLanguage = wire.targetLanguage,
            )
        }
}.getOrNull()

internal fun buildSharedMessageTranslationBatchBody(
    messageIds: List<String>,
    targetLanguage: String,
    mode: String,
): String =
    buildJsonObject {
        put("messageIds", JsonArray(messageIds.map(::JsonPrimitive)))
        put("targetLanguage", targetLanguage)
        put("mode", mode)
    }.let { body: JsonObject -> messageTranslationsJson.encodeToString(JsonObject.serializer(), body) }

internal interface RaftMessageTranslationApi {
    suspend fun translateBatch(
        messageIds: List<String>,
        targetLanguage: String,
        mode: String,
        priority: DataRequestPriority = DataRequestPriority.Visible,
    ): HomeNetworkResponse

    /** `GET servers/:id/translation-settings` — server toggle + provider availability. */
    suspend fun serverTranslationSettings(serverId: String): HomeNetworkResponse
}

internal class NetworkRaftMessageTranslationApi(
    private val networkClient: RaftNetworkClient
) : RaftMessageTranslationApi {
    override suspend fun translateBatch(
        messageIds: List<String>,
        targetLanguage: String,
        mode: String,
        priority: DataRequestPriority,
    ): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "message-translations:batch",
            method = "POST",
            bodyText =
                buildSharedMessageTranslationBatchBody(
                    messageIds = messageIds,
                    targetLanguage = targetLanguage,
                    mode = mode
                )
        )

    override suspend fun serverTranslationSettings(serverId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/$serverId/translation-settings",
            serverIdOverride = ServerId(serverId)
        )
}
