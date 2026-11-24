package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/*
 * The Free plan's 30-day history window, as a message page reports it (botiverse/slock staging
 * e99c01c4df, `packages/server/src/routes/messages.ts` `GET /messages/channel/:channelId`,
 * `services/messageService.ts` `getHiddenHistoryPreview`; web `store/hiddenHistoryStore.ts`):
 *
 * - `historyLimited: boolean`: the plan's cutoff left older messages out of this conversation
 *   (only on a page with no `after` cursor).
 * - `hiddenHistory?: { hiddenCount, hiddenCountCapped, parentHidden, latest[] }`: only on a page
 *   that reached the start of the visible window (fewer messages than the limit). `hiddenCount`
 *   is what Pro would show there (chat, system and retracted rows; replies in a thread), capped
 *   at 1,000 (`hiddenCountCapped`); `parentHidden`: a thread whose first message is hidden too;
 *   `latest`: the newest two hidden chat messages with text, oldest first (a hidden first
 *   message leads), text cut to 40 characters by the server.
 */

/** One of the newest messages the cutoff hides, as the paywall's faded glimpse shows it. */
data class HiddenHistoryMessage(
    val id: String,
    /** `user`, `agent` or `external_projection`. */
    val senderType: String,
    val senderId: String,
    val senderName: String?,
    val senderAvatarUrl: String?,
    val senderGravatarHash: String?,
    val createdAt: String,
    /** One line, at most 40 characters (cut by the server); null for a message with no text. */
    val preview: String?,
)

/** What the cutoff hides in one channel or thread (see the file comment). */
data class HiddenHistory(
    val hiddenCount: Int,
    val hiddenCountCapped: Boolean,
    val parentHidden: Boolean,
    val latest: List<HiddenHistoryMessage>,
)

private val HiddenHistorySenderTypes = setOf("user", "agent", "external_projection")

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.booleanTrue(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun parseHiddenHistoryMessage(element: JsonElement): HiddenHistoryMessage? {
    val row = element as? JsonObject ?: return null
    val id = row.stringOrNull("id") ?: return null
    val senderId = row.stringOrNull("senderId") ?: return null
    val createdAt = row.stringOrNull("createdAt") ?: return null
    val senderType = row.stringOrNull("senderType")?.takeIf { it in HiddenHistorySenderTypes } ?: return null
    return HiddenHistoryMessage(
        id = id,
        senderType = senderType,
        senderId = senderId,
        senderName = row.stringOrNull("senderName"),
        senderAvatarUrl = row.stringOrNull("senderAvatarUrl"),
        senderGravatarHash = row.stringOrNull("senderGravatarHash"),
        createdAt = createdAt,
        preview = row.stringOrNull("preview"),
    )
}

/**
 * A page's `hiddenHistory`, or null when it is absent or unusable (web `readHiddenHistory`): a
 * missing or negative count drops it; a malformed preview row drops only that row.
 */
fun parseHiddenHistory(element: JsonElement?): HiddenHistory? {
    val raw = element as? JsonObject ?: return null
    val count = (raw["hiddenCount"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
    if (count < 0) return null
    val latest =
        runCatching { raw["latest"]?.jsonArray }.getOrNull()
            ?.mapNotNull(::parseHiddenHistoryMessage)
            .orEmpty()
    return HiddenHistory(
        hiddenCount = count,
        hiddenCountCapped = raw.booleanTrue("hiddenCountCapped"),
        parentHidden = raw.booleanTrue("parentHidden"),
        latest = latest,
    )
}

/**
 * What a Free server's history window hides, per channel or thread id (the id the message page
 * was read from), for the history paywall at the start of the conversation. Process-wide like the
 * web's `useHiddenHistoryStore`: the pages are read in several data paths (the thread store's API
 * and the conversation repository), and channel and thread ids are unique across servers.
 */
object RaftHiddenHistoryStore {
    private val mutableByChannel = MutableStateFlow<Map<String, HiddenHistory>>(emptyMap())

    val byChannel: StateFlow<Map<String, HiddenHistory>> = mutableByChannel.asStateFlow()

    /**
     * Keep what a page with no `after` cursor says about the cutoff (web `recordHiddenHistory`): a
     * page that is not limited clears it; a limited page without `hiddenHistory` (one that did not
     * reach the start of the window) leaves the last answer in place.
     */
    fun record(channelId: String, historyLimited: Boolean, hiddenHistory: HiddenHistory?) {
        val key = channelId.trim().takeIf { it.isNotEmpty() } ?: return
        if (!historyLimited) {
            if (key in mutableByChannel.value) mutableByChannel.update { it - key }
            return
        }
        hiddenHistory ?: return
        if (mutableByChannel.value[key] == hiddenHistory) return
        mutableByChannel.update { it + (key to hiddenHistory) }
    }

    /** Forget everything (sign-out; tests share one process). */
    fun clear() {
        mutableByChannel.value = emptyMap()
    }
}
