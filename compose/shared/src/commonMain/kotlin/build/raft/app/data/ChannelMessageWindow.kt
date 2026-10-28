package build.raft.app.data

import build.raft.app.network.sync.CanonicalMessageV2Coverage
import build.raft.app.network.sync.CanonicalMessageV2SegmentSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The server's `messageWindow` on a `GET /api/messages/channel/:channelId` page (server routes/messages.ts,
 * messageService.listMessagesWithCoverage; schemaVersion 1, domain `receiver_visible_messages_v1`). The page
 * rows and these bounds come from one repeatable-read snapshot:
 * - [coveredAfterSeq]: the channel's newest seq below the page (0 = none; the high-water when the page is empty);
 * - [coveredFromSeq] / [coveredThroughSeq]: the page's first / last seq (empty page: high-water + 1 / high-water);
 * - [completeThroughLatest]: a `latest` page that reached the channel's newest message;
 * - [hasGap] / [hasNewer]: only `direction != latest`, not data-derived.
 * Every field is optional here so an older or drifted server never breaks the page decode; [toCoverage] says
 * whether the window proves a range. `messages/context` pages have no window.
 */
@Serializable
data class ChannelMessageWindow(
    val schemaVersion: Int? = null,
    val domain: String? = null,
    val serverId: String? = null,
    val receiverKind: String? = null,
    val receiverId: String? = null,
    val scopeId: String? = null,
    val coveredAfterSeq: Long? = null,
    val coveredFromSeq: Long? = null,
    val coveredThroughSeq: Long? = null,
    val remoteHighWaterSeq: Long? = null,
    val hasGap: Boolean? = null,
    val hasNewer: Boolean? = null,
    val completeThroughLatest: Boolean? = null,
) {
    /**
     * The range this page proves for the scope [scopeId] (see [CanonicalMessageV2Coverage.fromMessageWindow]),
     * or `null`: an unknown schema, a missing bound, or an empty page that is not the whole channel.
     */
    fun toCoverage(historyLimited: Boolean, source: CanonicalMessageV2SegmentSource): CanonicalMessageV2Coverage? {
        if (schemaVersion != CHANNEL_MESSAGE_WINDOW_SCHEMA_VERSION) return null
        return CanonicalMessageV2Coverage.fromMessageWindow(
            coveredAfterSeq = coveredAfterSeq ?: return null,
            coveredThroughSeq = coveredThroughSeq ?: return null,
            completeThroughLatest = completeThroughLatest ?: return null,
            historyLimited = historyLimited,
            source = source,
        )
    }
}

const val CHANNEL_MESSAGE_WINDOW_SCHEMA_VERSION: Int = 1

/** Lenient decode of a page's `messageWindow` value: absent or malformed = `null`, never a page failure. */
fun decodeChannelMessageWindow(element: JsonElement?): ChannelMessageWindow? {
    val window = element as? JsonObject ?: return null
    return runCatching { channelMessageWindowJson.decodeFromJsonElement(ChannelMessageWindow.serializer(), window) }.getOrNull()
}

private val channelMessageWindowJson = Json { ignoreUnknownKeys = true }
