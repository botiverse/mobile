package ai.slock.compose.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ActionCardMetadataPrefix = "actionCard."

private val threadActionCardJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
internal data class ThreadActionCard(
    val type: String,
    val state: String = "prepared",
    val name: String = "",
    val description: String? = null,
    val visibility: String? = null,
    val channel: String? = null,
    val humans: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val initialHumans: List<String> = emptyList(),
    val initialAgents: List<String> = emptyList(),
    val draftHint: String? = null,
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    val resultKind: String? = null,
    val resultId: String? = null,
    val resultName: String? = null,
    val resultChannelName: String? = null,
    val resultAddedHumanIds: List<String> = emptyList(),
    val resultAddedAgentIds: List<String> = emptyList(),
) {
    val isExecuted: Boolean
        get() = state.equals("executed", ignoreCase = true)
}

@Serializable
internal data class WireActionCardMetadata(
    val kind: String? = null,
    val action: WireActionCardAction? = null,
    val state: String = "prepared",
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    val result: WireActionCardResult? = null,
)

@Serializable
internal data class WireActionCardAction(
    val type: String = "",
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val channel: String? = null,
    val humans: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val initialHumans: List<String> = emptyList(),
    val initialAgents: List<String> = emptyList(),
    val draftHint: String? = null,
)

@Serializable
internal data class WireActionCardResult(
    val kind: String = "",
    val id: String? = null,
    val name: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val addedHumanIds: List<String> = emptyList(),
    val addedAgentIds: List<String> = emptyList(),
)

internal fun WireActionCardMetadata?.toThreadActionCard(): ThreadActionCard? {
    val meta = this ?: return null
    if (meta.kind != "action-card") return null
    val action = meta.action ?: return null
    val type = action.type.takeIf { it.isNotBlank() } ?: return null
    return ThreadActionCard(
        type = type,
        state = meta.state.ifBlank { "prepared" },
        name = action.name.orEmpty(),
        description = action.description?.takeIf { it.isNotBlank() },
        visibility = action.visibility?.takeIf { it.isNotBlank() },
        channel = action.channel?.takeIf { it.isNotBlank() },
        humans = action.humans,
        agents = action.agents,
        initialHumans = action.initialHumans,
        initialAgents = action.initialAgents,
        draftHint = action.draftHint?.takeIf { it.isNotBlank() },
        executedAt = meta.executedAt?.takeIf { it.isNotBlank() },
        executedByUserName = meta.executedByUserName?.takeIf { it.isNotBlank() },
        resultKind = meta.result?.kind?.takeIf { it.isNotBlank() },
        resultId = firstNonBlank(meta.result?.id, meta.result?.channelId),
        resultName = meta.result?.name?.takeIf { it.isNotBlank() },
        resultChannelName = meta.result?.channelName?.takeIf { it.isNotBlank() },
        resultAddedHumanIds = meta.result?.addedHumanIds.orEmpty(),
        resultAddedAgentIds = meta.result?.addedAgentIds.orEmpty(),
    )
}

internal fun ThreadActionCard.toMessageMetadata(): Map<String, String> =
    buildMap {
        putString("kind", "action-card")
        putString("type", type)
        putString("state", state)
        putString("name", name)
        putString("description", description)
        putString("visibility", visibility)
        putString("channel", channel)
        putList("humans", humans)
        putList("agents", agents)
        putList("initialHumans", initialHumans)
        putList("initialAgents", initialAgents)
        putString("draftHint", draftHint)
        putString("executedAt", executedAt)
        putString("executedByUserName", executedByUserName)
        putString("resultKind", resultKind)
        putString("resultId", resultId)
        putString("resultName", resultName)
        putString("resultChannelName", resultChannelName)
        putList("resultAddedHumanIds", resultAddedHumanIds)
        putList("resultAddedAgentIds", resultAddedAgentIds)
    }

internal fun actionCardFromMessageMetadata(metadata: Map<String, String>): ThreadActionCard? {
    if (metadata["${ActionCardMetadataPrefix}kind"] != "action-card") return null
    val type = metadata["${ActionCardMetadataPrefix}type"]?.takeIf { it.isNotBlank() } ?: return null
    return ThreadActionCard(
        type = type,
        state = metadata["${ActionCardMetadataPrefix}state"]?.takeIf { it.isNotBlank() } ?: "prepared",
        name = metadata["${ActionCardMetadataPrefix}name"].orEmpty(),
        description = metadata["${ActionCardMetadataPrefix}description"]?.takeIf { it.isNotBlank() },
        visibility = metadata["${ActionCardMetadataPrefix}visibility"]?.takeIf { it.isNotBlank() },
        channel = metadata["${ActionCardMetadataPrefix}channel"]?.takeIf { it.isNotBlank() },
        humans = metadata.decodeList("humans"),
        agents = metadata.decodeList("agents"),
        initialHumans = metadata.decodeList("initialHumans"),
        initialAgents = metadata.decodeList("initialAgents"),
        draftHint = metadata["${ActionCardMetadataPrefix}draftHint"]?.takeIf { it.isNotBlank() },
        executedAt = metadata["${ActionCardMetadataPrefix}executedAt"]?.takeIf { it.isNotBlank() },
        executedByUserName = metadata["${ActionCardMetadataPrefix}executedByUserName"]?.takeIf { it.isNotBlank() },
        resultKind = metadata["${ActionCardMetadataPrefix}resultKind"]?.takeIf { it.isNotBlank() },
        resultId = metadata["${ActionCardMetadataPrefix}resultId"]?.takeIf { it.isNotBlank() },
        resultName = metadata["${ActionCardMetadataPrefix}resultName"]?.takeIf { it.isNotBlank() },
        resultChannelName = metadata["${ActionCardMetadataPrefix}resultChannelName"]?.takeIf { it.isNotBlank() },
        resultAddedHumanIds = metadata.decodeList("resultAddedHumanIds"),
        resultAddedAgentIds = metadata.decodeList("resultAddedAgentIds"),
    )
}

internal fun ThreadActionCard.withExecutedAgent(id: String, name: String, executedByUserName: String? = null): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "agent",
        resultId = id,
        resultName = name
    )

internal fun ThreadActionCard.withExecutedChannel(id: String, name: String, executedByUserName: String? = null): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "channel",
        resultId = id,
        resultName = name
    )

internal fun ThreadActionCard.withExecutedChannelMembers(
    channelId: String,
    channelName: String,
    addedHumanIds: List<String>,
    addedAgentIds: List<String>,
    executedByUserName: String? = null
): ThreadActionCard =
    copy(
        state = "executed",
        executedByUserName = executedByUserName ?: this.executedByUserName,
        resultKind = "channel-members",
        resultId = channelId,
        resultChannelName = channelName,
        resultAddedHumanIds = addedHumanIds,
        resultAddedAgentIds = addedAgentIds
    )

internal fun ThreadActionCard.isSupportedCommitAction(): Boolean =
    type.equals("agent:create", ignoreCase = true) ||
        type.equals("channel:create", ignoreCase = true) ||
        type.equals("channel:add_member", ignoreCase = true)

private fun MutableMap<String, String>.putString(key: String, value: String?) {
    val normalized = value?.takeIf { it.isNotBlank() } ?: return
    put("$ActionCardMetadataPrefix$key", normalized)
}

private fun MutableMap<String, String>.putList(key: String, value: List<String>) {
    if (value.isEmpty()) return
    put("$ActionCardMetadataPrefix$key", threadActionCardJson.encodeToString(ListSerializer(String.serializer()), value))
}

private fun Map<String, String>.decodeList(key: String): List<String> {
    val raw = this["$ActionCardMetadataPrefix$key"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        threadActionCardJson.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (_: SerializationException) {
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
