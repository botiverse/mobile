package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/channels/:id` read as a message-link access probe: only the id and the channel type.
 * botiverse/slock@175cf23e9 packages/server/src/routes/channels.ts `channelRouter.get("/:id")`
 * answers the channel row itself (`{ id, type, name, … }`, `type` one of channel / private / dm /
 * thread / joint …), and 404 `{ error: "Channel not found" }` for a channel of another server or
 * one this reader may not see.
 */
@Serializable
private data class RaftMessageLinkChannelProbeWire(
    val id: String = "",
    val type: String = "",
)

internal data class RaftMessageLinkChannelProbe(
    val id: String,
    val type: String,
)

private val messageLinkProbeJson = Json { ignoreUnknownKeys = true }

internal fun parseRaftMessageLinkChannelProbe(bodyText: String?): RaftMessageLinkChannelProbe? {
    val text = bodyText?.takeIf(String::isNotBlank) ?: return null
    val wire =
        runCatching { messageLinkProbeJson.decodeFromString(RaftMessageLinkChannelProbeWire.serializer(), text) }
            .getOrNull() ?: return null
    if (wire.id.isBlank() || wire.type.isBlank()) return null
    return RaftMessageLinkChannelProbe(id = wire.id, type = wire.type.lowercase())
}

/**
 * Whether a 404 from `GET /api/messages/context/:messageId` is the history-window answer
 * (botiverse/slock@175cf23e9 packages/server/src/routes/messages.ts: a target this reader may read
 * but that sits behind the plan's history window still answers 404, with `hiddenTarget`). The
 * conversation opens on its paywall then; any other 404 is "not found".
 */
internal fun isRaftMessageContextHistoryLimited(bodyText: String?): Boolean {
    val text = bodyText?.takeIf(String::isNotBlank) ?: return false
    val root = runCatching { messageLinkProbeJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
    return root["hiddenTarget"] is JsonObject
}
