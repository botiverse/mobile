package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val threadActionJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireThreadActionResult(
    val ok: Boolean = true,
    val threadChannelId: String? = null,
    val replyCount: Int = 0,
)

fun parseSharedThreadActionResult(
    bodyText: String,
    fallbackThreadChannelId: ChannelId?,
    fallbackParentMessageId: MessageId?,
): ThreadActionResult? {
    if (bodyText.isBlank()) return null
    return runCatching {
        threadActionJson.decodeFromString(WireThreadActionResult.serializer(), bodyText)
    }.getOrNull()?.let { wire ->
        val threadChannelId = wire.threadChannelId ?: fallbackThreadChannelId?.value ?: return null
        ThreadActionResult(
            threadChannelId = ChannelId(threadChannelId),
            changed = wire.ok,
            parentMessageId = fallbackParentMessageId,
            replyCount = wire.replyCount,
        )
    }
}
