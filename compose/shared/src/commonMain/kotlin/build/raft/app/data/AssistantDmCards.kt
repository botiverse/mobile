package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * The two cards of the owner's DM with their personal assistant (web parity, 1.25):
 *
 * 1. The delegated-send receipt (RFC 077, botiverse/slock `packages/shared/src/delegatedReceipt.ts`
 *    `DelegatedReceiptMetadata`; web `assistant/delegatedReceipt.ts`, `DelegatedReceiptCard.tsx`):
 *    a system message whose `actionMetadata.kind` is `delegated-receipt`, one line per message the
 *    assistant sent as its owner. Retract: `DELETE servers/:id/assistant/delegated/:messageId` →
 *    `{ messageId, deletedAt }` (packages/server/src/routes/agents.ts `myAssistantRouter`; anyone
 *    but the author gets 404 `message_not_found`).
 * 2. The WeChat connected notice (web `assistant/wechatNotice.ts`, `WeChatConnectedNotice.tsx`): a
 *    system message with randomId `wechat-connected:<binding id>`. Status:
 *    `GET servers/:id/assistant/wechat/status` → `{ available, binding: null | { bindingId, state,
 *    connectedAt } }`; disconnect: `DELETE servers/:id/assistant/wechat/binding?bindingId=` →
 *    `{ binding: null }`, 404 `wechat_not_connected`, 409 `wechat_binding_changed`
 *    (packages/server/src/routes/assistantWeChat.ts). Errors are `{ error, code }`.
 */

/** `DELEGATED_RECEIPT_KIND`: the receipt's `actionMetadata.kind` (the stored spelling). */
const val DELEGATED_RECEIPT_KIND: String = "delegated-receipt"

/** Web `WECHAT_CONNECTED_NOTICE_PREFIX`: the connected notice's randomId prefix. */
const val WECHAT_CONNECTED_NOTICE_PREFIX: String = "wechat-connected:"

private val assistantDmCardsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** `DelegatedReceiptEntry`, field for field (camelCase as the server writes it). */
@Serializable
internal data class WireDelegatedReceiptEntry(
    val messageId: String? = null,
    val channelId: String? = null,
    val deletedAt: String? = null,
    val conversationRef: String? = null,
    val conversationType: String? = null,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val preview: String? = null,
    val sentAt: String? = null,
    val retractable: Boolean? = null,
)

/** `{ channelId, name }` of `joinedChannels` / `joinedNotForwarded`. */
@Serializable
internal data class WireDelegatedReceiptChannel(
    val channelId: String? = null,
    val name: String? = null,
)

/** One line of a receipt (web `DelegatedReceipt`). */
data class DelegatedReceiptLine(
    val messageId: String,
    /** The message's own conversation (a thread reply: the thread's own channel id). */
    val channelId: String,
    val deletedAt: String?,
    /** Computed by the server when it serves the receipt; Retract shows only when true. */
    val retractable: Boolean,
    /** `#channel` or `dm:@peer` (a thread: its parent's). */
    val conversationRef: String?,
    val conversationType: String?,
    val parentChannelId: String?,
    val parentMessageId: String?,
    val preview: String?,
) {
    val retracted: Boolean get() = deletedAt != null
}

/** A public channel the assistant joined for its owner (`joinedChannels` / `joinedNotForwarded`). */
data class DelegatedReceiptChannel(val channelId: String, val name: String)

/** A receipt's lines and the channels it names (web `delegatedReceiptsOf`, `joinedChannelsOf`, `joinedNotForwardedOf`). */
data class DelegatedReceipt(
    val lines: List<DelegatedReceiptLine>,
    val joinedChannels: List<DelegatedReceiptChannel> = emptyList(),
    val joinedNotForwarded: List<DelegatedReceiptChannel> = emptyList(),
)

private fun String?.nonEmpty(): String? = this?.takeIf { it.isNotEmpty() }

private fun WireDelegatedReceiptEntry.toLine(): DelegatedReceiptLine? {
    val messageId = messageId ?: return null
    val channelId = channelId ?: return null
    return DelegatedReceiptLine(
        messageId = messageId,
        channelId = channelId,
        deletedAt = deletedAt.nonEmpty(),
        retractable = retractable == true,
        conversationRef = conversationRef.nonEmpty(),
        conversationType = conversationType.nonEmpty(),
        parentChannelId = parentChannelId.nonEmpty(),
        parentMessageId = parentMessageId.nonEmpty(),
        preview = preview.nonEmpty(),
    )
}

private fun JsonElement?.entryOrNull(): WireDelegatedReceiptEntry? {
    val element = this as? JsonObject ?: return null
    return runCatching { assistantDmCardsJson.decodeFromJsonElement(WireDelegatedReceiptEntry.serializer(), element) }.getOrNull()
}

private fun JsonElement?.channelsOrEmpty(): List<DelegatedReceiptChannel> {
    val element = this ?: return emptyList()
    val list =
        runCatching { assistantDmCardsJson.decodeFromJsonElement(ListSerializer(WireDelegatedReceiptChannel.serializer()), element) }
            .getOrNull() ?: return emptyList()
    return list.mapNotNull { channel ->
        val id = channel.channelId ?: return@mapNotNull null
        val name = channel.name ?: return@mapNotNull null
        DelegatedReceiptChannel(id, name)
    }
}

/**
 * Web `delegatedReceiptsOf` + the joined lists, read from a receipt's `actionMetadata` object: a
 * forward receipt's `delegatedReceipts` (one line per destination), else the single
 * `delegatedReceipt`. Null when [actionMetadata] is not a receipt.
 */
fun readDelegatedReceipt(actionMetadata: JsonElement?): DelegatedReceipt? {
    val metadata = actionMetadata as? JsonObject ?: return null
    val kind = (metadata["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (kind != DELEGATED_RECEIPT_KIND) return null
    val many = metadata["delegatedReceipts"]
    val lines =
        if (many is kotlinx.serialization.json.JsonArray) {
            many.mapNotNull { it.entryOrNull()?.toLine() }
        } else {
            listOfNotNull(metadata["delegatedReceipt"].entryOrNull()?.toLine())
        }
    return DelegatedReceipt(
        lines = lines,
        joinedChannels = metadata["joinedChannels"].channelsOrEmpty(),
        joinedNotForwarded = metadata["joinedNotForwarded"].channelsOrEmpty(),
    )
}

/*
 * The receipt travels on a projected message as one metadata string (its `actionMetadata` JSON),
 * like a card message's card, and is read back where the line is drawn.
 */
private const val DelegatedReceiptMetadataKey = "delegatedReceipt.json"

/** Whether [key] is the receipt's metadata string (left out of a system line's details). */
internal fun isDelegatedReceiptMetadataKey(key: String): Boolean = key == DelegatedReceiptMetadataKey

/** The metadata string a receipt `actionMetadata` projects to; empty for anything else. */
internal fun delegatedReceiptMessageMetadata(actionMetadata: JsonObject): Map<String, String> =
    if (readDelegatedReceipt(actionMetadata) != null) mapOf(DelegatedReceiptMetadataKey to actionMetadata.toString()) else emptyMap()

/** The receipt a projected message's metadata carries; null for any other message. */
fun delegatedReceiptOf(metadata: Map<String, String>): DelegatedReceipt? {
    val json = metadata[DelegatedReceiptMetadataKey] ?: return null
    return readDelegatedReceipt(runCatching { Json.parseToJsonElement(json) }.getOrNull())
}

/** Web `weChatNoticeBindingId`: the binding a connected notice names; null for any other message. */
fun weChatNoticeBindingId(randomId: String?): String? {
    if (randomId == null || !randomId.startsWith(WECHAT_CONNECTED_NOTICE_PREFIX)) return null
    return randomId.removePrefix(WECHAT_CONNECTED_NOTICE_PREFIX).takeIf { it.isNotEmpty() }
}

// ---- Wire answers -------------------------------------------------------------------------------

@Serializable
private data class WireDelegatedRetractResponse(
    val messageId: String? = null,
    val deletedAt: String? = null,
)

@Serializable
private data class WireWeChatBinding(
    val bindingId: String? = null,
    val state: String? = null,
    val connectedAt: String? = null,
)

@Serializable
private data class WireWeChatStatus(
    val available: Boolean = false,
    val binding: WireWeChatBinding? = null,
)

/** Web `WeChatBinding`: the person's connection (no WeChat account id or name is ever sent). */
data class WeChatBinding(val bindingId: String, val state: String, val connectedAt: String)

/** Web `WeChatStatus`. */
data class WeChatStatus(val available: Boolean, val binding: WeChatBinding?) {
    companion object {
        /** A server without the bridge's routes (404): not offered here. */
        val NotHere: WeChatStatus = WeChatStatus(available = false, binding = null)
    }
}

/** Web `normalizeStatus`: only an `active` / `reconnect_required` binding counts. */
fun parseSharedWeChatStatus(body: String): WeChatStatus? {
    val wire =
        try {
            assistantDmCardsJson.decodeFromString(WireWeChatStatus.serializer(), body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val binding =
        wire.binding
            ?.takeIf { it.state == "active" || it.state == "reconnect_required" }
            ?.let { WeChatBinding(it.bindingId.orEmpty(), it.state.orEmpty(), it.connectedAt.orEmpty()) }
    return WeChatStatus(available = wire.available, binding = binding)
}

/** The `deletedAt` of a retract answer; null when the body doesn't carry one. */
fun parseSharedDelegatedRetractDeletedAt(body: String?): String? {
    val text = body?.takeIf(String::isNotBlank) ?: return null
    return runCatching { assistantDmCardsJson.decodeFromString(WireDelegatedRetractResponse.serializer(), text) }
        .getOrNull()
        ?.deletedAt
        ?.takeIf(String::isNotEmpty)
}

/** The error body's `code` (`{ error, code }`). */
private fun assistantDmErrorCode(bodyText: String?): String? {
    val body =
        bodyText?.takeIf(String::isNotBlank)?.let { text ->
            runCatching { assistantDmCardsJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        } ?: return null
    return (body["code"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}

/** What one retract gave. */
sealed interface DelegatedRetractResult {
    /** Retracted; [deletedAt] is the server's, null when the answer didn't say. */
    data class Retracted(val deletedAt: String?) : DelegatedRetractResult

    /** Refused or failed; [traceId] is the response's `X-Trace-Id`, null without an answer. */
    data class Failed(val error: ScreenError) : DelegatedRetractResult
}

/** What one read of the WeChat status gave (web `WeChatStatusState`, without loading). */
sealed interface WeChatStatusResult {
    data class Ready(val status: WeChatStatus) : WeChatStatusResult

    /** Offline, 5xx, unreadable: nothing is offered, a disconnect still works. */
    data object Error : WeChatStatusResult
}

/** What one disconnect gave (web `disconnectWeChat`). */
sealed interface WeChatDisconnectResult {
    data object Disconnected : WeChatDisconnectResult

    /** 404 `wechat_not_connected` (gone already) or 409 `wechat_binding_changed` (a newer one stays). */
    data class AlreadyEnded(val bindingChanged: Boolean) : WeChatDisconnectResult

    data class Failed(val error: ScreenError) : WeChatDisconnectResult
}

interface RaftAssistantDmCardsApi {
    suspend fun retractDelegated(serverId: ServerId, messageId: String): DelegatedRetractResult

    suspend fun weChatStatus(serverId: ServerId): WeChatStatusResult

    suspend fun disconnectWeChat(serverId: ServerId, bindingId: String): WeChatDisconnectResult
}

internal fun delegatedRetractResultOf(code: Int, bodyText: String?, traceId: String?): DelegatedRetractResult =
    if (code in 200..299) {
        DelegatedRetractResult.Retracted(parseSharedDelegatedRetractDeletedAt(bodyText))
    } else {
        DelegatedRetractResult.Failed(
            ScreenError(code = assistantDmErrorCode(bodyText) ?: "delegated_retract_failed_$code", traceId = traceId)
        )
    }

internal fun weChatStatusResultOf(code: Int, bodyText: String?): WeChatStatusResult =
    when {
        code == 404 -> WeChatStatusResult.Ready(WeChatStatus.NotHere)
        code !in 200..299 || bodyText == null -> WeChatStatusResult.Error
        else -> parseSharedWeChatStatus(bodyText)?.let(WeChatStatusResult::Ready) ?: WeChatStatusResult.Error
    }

internal fun weChatDisconnectResultOf(code: Int, bodyText: String?, traceId: String?): WeChatDisconnectResult {
    if (code in 200..299) return WeChatDisconnectResult.Disconnected
    return when (val errorCode = assistantDmErrorCode(bodyText)) {
        "wechat_not_connected" -> WeChatDisconnectResult.AlreadyEnded(bindingChanged = false)
        "wechat_binding_changed" -> WeChatDisconnectResult.AlreadyEnded(bindingChanged = true)
        else -> WeChatDisconnectResult.Failed(ScreenError(code = errorCode ?: "wechat_disconnect_failed_$code", traceId = traceId))
    }
}

internal class RaftAssistantDmCardsNetworkApi(
    private val networkClient: RaftNetworkClient,
) : RaftAssistantDmCardsApi {
    override suspend fun retractDelegated(serverId: ServerId, messageId: String): DelegatedRetractResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/assistant/delegated/${encodeAssistantPathSegment(messageId)}",
                    method = "DELETE",
                    serverIdOverride = serverId,
                )
            }.getOrElse { return DelegatedRetractResult.Failed(ScreenError(code = "network", traceId = null)) }
        return delegatedRetractResultOf(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    }

    override suspend fun weChatStatus(serverId: ServerId): WeChatStatusResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "servers/${serverId.value}/assistant/wechat/status",
                    serverIdOverride = serverId,
                )
            }.getOrElse { return WeChatStatusResult.Error }
        return weChatStatusResultOf(response.code, response.bodyText)
    }

    override suspend fun disconnectWeChat(serverId: ServerId, bindingId: String): WeChatDisconnectResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/assistant/wechat/binding",
                    method = "DELETE",
                    query = listOf("bindingId" to bindingId),
                    serverIdOverride = serverId,
                )
            }.getOrElse { return WeChatDisconnectResult.Failed(ScreenError(code = "network", traceId = null)) }
        return weChatDisconnectResultOf(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    }
}

/** A path segment as `encodeURIComponent` writes it, for the ids these routes take (UUIDs). */
private fun encodeAssistantPathSegment(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c in "-_.!~*'()") append(c) else {
                append('%')
                append(((byte.toInt() shr 4) and 0xF).toString(16).uppercase())
                append((byte.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }
