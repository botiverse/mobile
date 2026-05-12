package ai.slock.android.feature.thread

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MessageId
import java.net.URI

data class SlockWebLinkTarget(
        val channelId: ChannelId,
        val messageId: MessageId? = null
)

fun String.toSlockWebLinkTarget(): SlockWebLinkTarget? {
    val uri = runCatching { URI(this) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && scheme != "http") return null
    if (uri.host?.lowercase() != "app.slock.ai") return null

    val segments = uri.path
            ?.split("/")
            ?.filter { it.isNotBlank() }
            .orEmpty()
    val channelIndex = segments.indexOf("channel")
    val channelId = segments.getOrNull(channelIndex + 1)?.takeIf { it.isNotBlank() }
            ?: return null
    val messageId = uri.rawQuery
            ?.split("&")
            ?.firstNotNullOfOrNull { part ->
                val pieces = part.split("=", limit = 2)
                pieces.getOrNull(1)?.takeIf { pieces.firstOrNull() == "msg" && it.isNotBlank() }
            }

    return SlockWebLinkTarget(
            channelId = ChannelId(channelId),
            messageId = messageId?.let(::MessageId)
    )
}
