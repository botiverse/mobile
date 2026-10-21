package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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
    val suggestedComputer: String? = null,
    val requiredComputer: String? = null,
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
    val requestId: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val scopes: List<String> = emptyList(),
    val metadataItems: List<ThreadActionCardMetadataItem> = emptyList(),
    val presentationTitle: String? = null,
    val presentationConfirmLabel: String? = null,
    val presentationWarning: String? = null,
    val genericApprovalAllowed: Boolean = false,
    /**
     * The card's own server (wire `sourceServerId`, fixed at prepare time). In a joint channel it
     * can differ from the server the card is viewed in.
     */
    val sourceServerId: String? = null,
) {
    val isExecuted: Boolean
        get() = state.equals("executed", ignoreCase = true)
}

@Serializable
internal data class ThreadActionCardMetadataItem(
    val key: String,
    val value: String,
    val redacted: Boolean = false,
)

@Serializable
internal data class WireActionCardPresentation(
    val title: String? = null,
    val confirmLabel: String? = null,
    val warning: String? = null,
    val genericApprovalAllowed: Boolean = false,
    val displayItems: List<ThreadActionCardMetadataItem> = emptyList(),
)

@Serializable
internal data class WireActionCardMetadata(
    val kind: String? = null,
    val version: Int? = null,
    val sourceServerId: String? = null,
    val forwardedItems: List<WireForwardedBundleItem> = emptyList(),
    /**
     * Keep the canonical action object intact at the wire boundary. Mobile has
     * specialized renderers for a few action types, but a newer server can add
     * fields or action types before the app ships another release. Decoding
     * directly into [WireActionCardAction] would silently discard those fields
     * under `ignoreUnknownKeys`, leaving the fallback card blank.
     */
    val action: JsonObject? = null,
    val state: String = "prepared",
    val executedAt: String? = null,
    val executedByUserName: String? = null,
    val result: WireActionCardResult? = null,
    val presentation: WireActionCardPresentation? = null,
)

@Serializable
internal data class WireActionCardAction(
    val type: String = "",
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val channel: String? = null,
    val suggestedComputer: String? = null,
    val requiredComputer: String? = null,
    val humans: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val initialHumans: List<String> = emptyList(),
    val initialAgents: List<String> = emptyList(),
    val draftHint: String? = null,
    val requestId: String? = null,
    val agentId: String? = null,
    val agentName: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val scopes: List<String> = emptyList(),
)

@Serializable
internal data class WireActionCardResult(
    val kind: String = "",
    val id: String? = null,
    val name: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    val clientId: String? = null,
    val clientKey: String? = null,
    val clientName: String? = null,
    val ownerAgentId: String? = null,
    val ownerAgentName: String? = null,
    val addedHumanIds: List<String> = emptyList(),
    val addedAgentIds: List<String> = emptyList(),
)

internal fun WireActionCardMetadata?.toThreadActionCard(): ThreadActionCard? {
    val meta = this ?: return null
    if (meta.kind != "action-card") return null
    val actionObject = meta.action ?: return null
    val action =
        runCatching {
            threadActionCardJson.decodeFromJsonElement(WireActionCardAction.serializer(), actionObject)
        }.getOrNull() ?: return null
    val type = action.type.takeIf { it.isNotBlank() } ?: return null
    return ThreadActionCard(
        type = type,
        state = meta.state.ifBlank { "prepared" },
        name = action.name.orEmpty(),
        description = action.description?.takeIf { it.isNotBlank() },
        visibility = action.visibility?.takeIf { it.isNotBlank() },
        channel = action.channel?.takeIf { it.isNotBlank() },
        suggestedComputer = action.suggestedComputer?.takeIf { it.isNotBlank() },
        requiredComputer = action.requiredComputer?.takeIf { it.isNotBlank() },
        humans = action.humans,
        agents = action.agents,
        initialHumans = action.initialHumans,
        initialAgents = action.initialAgents,
        draftHint = action.draftHint?.takeIf { it.isNotBlank() },
        executedAt = meta.executedAt?.takeIf { it.isNotBlank() },
        executedByUserName = meta.executedByUserName?.takeIf { it.isNotBlank() },
        resultKind = meta.result?.kind?.takeIf { it.isNotBlank() },
        resultId = firstNonBlank(meta.result?.id, meta.result?.channelId, meta.result?.clientId, meta.result?.ownerAgentId),
        resultName = firstNonBlank(meta.result?.name, meta.result?.clientName, meta.result?.ownerAgentName),
        resultChannelName = meta.result?.channelName?.takeIf { it.isNotBlank() },
        resultAddedHumanIds = meta.result?.addedHumanIds.orEmpty(),
        resultAddedAgentIds = meta.result?.addedAgentIds.orEmpty(),
        requestId = action.requestId?.takeIf { it.isNotBlank() },
        agentId = action.agentId?.takeIf { it.isNotBlank() },
        agentName = action.agentName?.takeIf { it.isNotBlank() },
        clientId = action.clientId?.takeIf { it.isNotBlank() },
        clientKey = action.clientKey?.takeIf { it.isNotBlank() },
        clientName = action.clientName?.takeIf { it.isNotBlank() },
        scopes = action.scopes,
        metadataItems = meta.presentation?.displayItems?.toSafeServerPresentationItems()?.takeIf { it.isNotEmpty() }
            ?: actionObject.toSafeActionCardMetadataItems(),
        presentationTitle = meta.presentation?.title?.takeIf { it.isNotBlank() },
        presentationConfirmLabel = meta.presentation?.confirmLabel?.takeIf { it.isNotBlank() },
        presentationWarning = meta.presentation?.warning?.takeIf { it.isNotBlank() },
        genericApprovalAllowed = meta.presentation?.genericApprovalAllowed == true,
        sourceServerId = meta.sourceServerId?.takeIf { it.isNotBlank() },
    )
}

internal fun WireActionCardAction.toWireJsonObject(): JsonObject =
    threadActionCardJson.encodeToJsonElement(WireActionCardAction.serializer(), this).jsonObject

internal fun ThreadActionCard.toMessageMetadata(): Map<String, String> =
    buildMap {
        putString("kind", "action-card")
        putString("type", type)
        putString("state", state)
        putString("name", name)
        putString("description", description)
        putString("visibility", visibility)
        putString("channel", channel)
        putString("suggestedComputer", suggestedComputer)
        putString("requiredComputer", requiredComputer)
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
        putString("requestId", requestId)
        putString("agentId", agentId)
        putString("agentName", agentName)
        putString("clientId", clientId)
        putString("clientKey", clientKey)
        putString("clientName", clientName)
        putList("scopes", scopes)
        putMetadataItems("metadataItems", metadataItems)
        putString("presentationTitle", presentationTitle)
        putString("presentationConfirmLabel", presentationConfirmLabel)
        putString("presentationWarning", presentationWarning)
        putBoolean("genericApprovalAllowed", genericApprovalAllowed)
        putString("sourceServerId", sourceServerId)
    }

/** True when the projected message metadata carries an action card (any type). */
internal fun messageMetadataHasActionCard(metadata: Map<String, String>): Boolean =
    metadata["${ActionCardMetadataPrefix}kind"] == "action-card"

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
        suggestedComputer = metadata["${ActionCardMetadataPrefix}suggestedComputer"]?.takeIf { it.isNotBlank() },
        requiredComputer = metadata["${ActionCardMetadataPrefix}requiredComputer"]?.takeIf { it.isNotBlank() },
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
        requestId = metadata["${ActionCardMetadataPrefix}requestId"]?.takeIf { it.isNotBlank() },
        agentId = metadata["${ActionCardMetadataPrefix}agentId"]?.takeIf { it.isNotBlank() },
        agentName = metadata["${ActionCardMetadataPrefix}agentName"]?.takeIf { it.isNotBlank() },
        clientId = metadata["${ActionCardMetadataPrefix}clientId"]?.takeIf { it.isNotBlank() },
        clientKey = metadata["${ActionCardMetadataPrefix}clientKey"]?.takeIf { it.isNotBlank() },
        clientName = metadata["${ActionCardMetadataPrefix}clientName"]?.takeIf { it.isNotBlank() },
        scopes = metadata.decodeList("scopes"),
        metadataItems = metadata.decodeMetadataItems("metadataItems"),
        presentationTitle = metadata["${ActionCardMetadataPrefix}presentationTitle"]?.takeIf { it.isNotBlank() },
        presentationConfirmLabel = metadata["${ActionCardMetadataPrefix}presentationConfirmLabel"]?.takeIf { it.isNotBlank() },
        presentationWarning = metadata["${ActionCardMetadataPrefix}presentationWarning"]?.takeIf { it.isNotBlank() },
        genericApprovalAllowed =
            metadata["${ActionCardMetadataPrefix}genericApprovalAllowed"]?.equals("true", ignoreCase = true) == true,
        sourceServerId = metadata["${ActionCardMetadataPrefix}sourceServerId"]?.takeIf { it.isNotBlank() },
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

/**
 * `feedback:submit`: never executed inline (the server refuses a generic execute); it is sent from
 * the feedback form, which on mobile is the Feedback WebView (see `raftFeedbackCardEntryOrNull`).
 */
internal fun ThreadActionCard.isFeedbackSubmitAction(): Boolean =
    type.equals(FeedbackSubmitActionCardType, ignoreCase = true)

internal const val FeedbackSubmitActionCardType = "feedback:submit"

internal fun ThreadActionCard.isSupportedCommitAction(): Boolean =
    type.equals("agent:create", ignoreCase = true) ||
        type.equals("channel:create", ignoreCase = true) ||
        type.equals("channel:add_member", ignoreCase = true) ||
        type.equals("integration:approve_agent_login", ignoreCase = true) ||
        isGenericInlineCommitAction()

/**
 * Server-prepared operation cards are canonical, human-commit requests. A
 * newer server action can therefore use the generic execute endpoint before
 * mobile has a bespoke renderer. The one historical update-card type is
 * deliberately terminal on the server (HTTP 410), so an old carrier must not
 * regain an enabled button through this fallback.
 */
internal fun ThreadActionCard.isGenericInlineCommitAction(): Boolean =
    type.isNotBlank() &&
        !type.equals("agent:create", ignoreCase = true) &&
        !type.equals("channel:create", ignoreCase = true) &&
        !type.equals("channel:add_member", ignoreCase = true) &&
        !type.equals("integration:approve_agent_login", ignoreCase = true) &&
        !type.equals("integration:update_app_registration", ignoreCase = true) &&
        (
            genericApprovalAllowed ||
                type.equals("integration:register_app", ignoreCase = true) ||
                type.equals("integration:recover_app_owner", ignoreCase = true)
        )

private fun MutableMap<String, String>.putString(key: String, value: String?) {
    val normalized = value?.takeIf { it.isNotBlank() } ?: return
    put("$ActionCardMetadataPrefix$key", normalized)
}

private fun MutableMap<String, String>.putList(key: String, value: List<String>) {
    if (value.isEmpty()) return
    put("$ActionCardMetadataPrefix$key", threadActionCardJson.encodeToString(ListSerializer(String.serializer()), value))
}

private fun MutableMap<String, String>.putBoolean(key: String, value: Boolean) {
    if (value) put("$ActionCardMetadataPrefix$key", "true")
}

private fun MutableMap<String, String>.putMetadataItems(key: String, value: List<ThreadActionCardMetadataItem>) {
    if (value.isEmpty()) return
    put(
        "$ActionCardMetadataPrefix$key",
        threadActionCardJson.encodeToString(ListSerializer(ThreadActionCardMetadataItem.serializer()), value)
    )
}

private fun Map<String, String>.decodeList(key: String): List<String> {
    val raw = this["$ActionCardMetadataPrefix$key"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        threadActionCardJson.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (_: SerializationException) {
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}

private fun Map<String, String>.decodeMetadataItems(key: String): List<ThreadActionCardMetadataItem> {
    val raw = this["$ActionCardMetadataPrefix$key"]?.takeIf { it.isNotBlank() } ?: return emptyList()
    return try {
        threadActionCardJson.decodeFromString(ListSerializer(ThreadActionCardMetadataItem.serializer()), raw)
    } catch (_: SerializationException) {
        emptyList()
    }
}

private val sensitiveActionCardFieldFragments =
    listOf(
        "secret",
        "token",
        "password",
        "credential",
        "authorization",
        "privatekey",
        "private_key",
        "accesstoken",
        "access_token",
        "refreshtoken",
        "refresh_token",
    )

private fun JsonObject.toSafeActionCardMetadataItems(): List<ThreadActionCardMetadataItem> =
    entries
        .asSequence()
        .filterNot { (key, _) -> key.equals("type", ignoreCase = true) || key.equals("draftHint", ignoreCase = true) }
        .take(24)
        .map { (key, element) ->
            val sensitive = key.isSensitiveActionCardField()
            ThreadActionCardMetadataItem(
                key = key,
                value = if (sensitive) "Hidden" else element.toSafeActionCardDisplayValue(),
                redacted = sensitive,
            )
        }
        .filter { item -> item.value.isNotBlank() }
        .toList()

private fun List<ThreadActionCardMetadataItem>.toSafeServerPresentationItems(): List<ThreadActionCardMetadataItem> =
    asSequence()
        .filter { item -> item.key.isNotBlank() }
        .take(24)
        .map { item ->
            val sensitive = item.redacted || item.key.isSensitiveActionCardField()
            item.copy(
                value = if (sensitive) "Hidden" else item.value.take(500),
                redacted = sensitive,
            )
        }
        .filter { item -> item.value.isNotBlank() }
        .toList()

private fun JsonElement.toSafeActionCardDisplayValue(): String {
    val rendered =
        when (this) {
            JsonNull -> "-"
            is JsonPrimitive -> content
            is JsonArray -> joinToString(", ") { child -> child.toSafeActionCardDisplayValue() }
            is JsonObject ->
                entries.joinToString(", ") { (key, value) ->
                    "$key: ${if (key.isSensitiveActionCardField()) "Hidden" else value.toSafeActionCardDisplayValue()}"
                }
        }
    return if (rendered.length <= 500) rendered else rendered.take(497) + "..."
}

private fun String.isSensitiveActionCardField(): Boolean {
    val normalized = lowercase().replace("-", "").replace(".", "")
    return sensitiveActionCardFieldFragments.any { fragment -> normalized.contains(fragment) }
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()
