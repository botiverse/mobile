package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * "Changed by your assistant" (web `components/settings/assistantSettingChangesData.ts`): what the
 * owner's personal assistant changed for them, newest first, as
 * `GET servers/:id/assistant/setting-changes` reports it (the server keeps the last 10):
 * `{ changes: [{ id, assistantName, setting, serverId?, serverName?, targetRef?, confirmed?,
 * previousValue, nextValue, createdAt }] }`, values `string | boolean | null`. A change of a
 * setting this build does not know is left out (web `readChanges`), as is one without an id or a
 * time; any failure reads as no changes.
 */

private val assistantSettingChangesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** Web `AssistantChangedSetting`; [wireName] is the audit's `setting`. */
enum class AssistantChangedSetting(val wireName: String) {
    Theme("theme"),
    ThemeDecor("themeDecor"),
    DisplayLanguage("displayLanguage"),
    PreferredTimezone("preferredTimezone"),

    /** What the assistant did as the owner in this server (muted a conversation, set a level). */
    ConversationMute("conversationMute"),
    NotificationLevel("notificationLevel"),

    /** The owner's sidebar in this server, shown with the theme. */
    SidebarMove("sidebarMove"),
    SidebarPin("sidebarPin"),
    SidebarArrange("sidebarArrange"),

    /** Confirmed on a card by the owner, shown with the assistant's own settings. */
    AgentMove("agentMove"),

    /** A message the assistant saved or unsaved for the owner, shown with the assistant's own settings. */
    MessageSave("messageSave"),
    ;

    companion object {
        /** The setting [wire] names; null for one this build does not know. */
        fun fromWire(wire: String?): AssistantChangedSetting? = entries.firstOrNull { it.wireName == wire }
    }
}

/** Settings › Theme (web ServerThemeSection `THEME_SETTINGS`). */
val ASSISTANT_THEME_SETTINGS: Set<AssistantChangedSetting> =
    setOf(
        AssistantChangedSetting.Theme,
        AssistantChangedSetting.ThemeDecor,
        AssistantChangedSetting.SidebarMove,
        AssistantChangedSetting.SidebarPin,
        AssistantChangedSetting.SidebarArrange,
    )

/** Settings › Language & region (web LanguageRegionSection `LANGUAGE_REGION_ASSISTANT_SETTINGS`). */
val ASSISTANT_LANGUAGE_REGION_SETTINGS: Set<AssistantChangedSetting> =
    setOf(AssistantChangedSetting.DisplayLanguage, AssistantChangedSetting.PreferredTimezone)

/** Settings › Notifications (web NotificationSettingsSection `NOTIFICATION_ASSISTANT_SETTINGS`). */
val ASSISTANT_NOTIFICATION_SETTINGS: Set<AssistantChangedSetting> =
    setOf(AssistantChangedSetting.ConversationMute, AssistantChangedSetting.NotificationLevel)

/** What the assistant did on the owner's say-so, shown with its own settings (web `ASSISTANT_ACTION_SETTINGS`). */
val ASSISTANT_ACTION_SETTINGS: Set<AssistantChangedSetting> =
    setOf(AssistantChangedSetting.AgentMove, AssistantChangedSetting.MessageSave)

/** A recorded value: the audit's `string | boolean`; a missing or null value is a null [AssistantSettingValue]. */
sealed interface AssistantSettingValue {
    data class Text(val value: String) : AssistantSettingValue

    data class Flag(val value: Boolean) : AssistantSettingValue
}

/** One change (web `AssistantSettingChange`). */
data class AssistantSettingChange(
    val id: String,
    val assistantName: String?,
    val setting: AssistantChangedSetting,
    /**
     * The audit says which server it was changed in (web `serverId !== undefined`): then [serverId]
     * is that server, null once it is gone.
     */
    val serverReported: Boolean = false,
    val serverId: String? = null,
    val serverName: String? = null,
    /** The conversation as the owner names it (`#design`, `dm:@peer`), `"server"`, the agent, or null once gone. */
    val targetRef: String? = null,
    /** The owner confirmed it on a card. */
    val confirmed: Boolean? = null,
    val previousValue: AssistantSettingValue? = null,
    val nextValue: AssistantSettingValue? = null,
    val createdAt: String,
) {
    /** Changed in a server other than [currentServerId] (web `serverId !== undefined && serverId !== current`). */
    fun inOtherServer(currentServerId: String?): Boolean = serverReported && serverId != currentServerId
}

@Serializable
private data class WireAssistantSettingChange(
    val id: String? = null,
    val assistantName: String? = null,
    val setting: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val targetRef: String? = null,
    val confirmed: Boolean? = null,
    val previousValue: JsonElement? = null,
    val nextValue: JsonElement? = null,
    val createdAt: String? = null,
)

private fun JsonElement?.toAssistantSettingValue(): AssistantSettingValue? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    if (primitive.isString) return AssistantSettingValue.Text(primitive.content)
    primitive.booleanOrNull?.let { return AssistantSettingValue.Flag(it) }
    // Not in the contract; shown as text, as web's `String(value)` would.
    return primitive.contentOrNull?.let(AssistantSettingValue::Text)
}

/** Parses a `GET servers/:id/assistant/setting-changes` body; empty for one that is not that shape. */
fun parseAssistantSettingChanges(body: String): List<AssistantSettingChange> {
    val root = runCatching { assistantSettingChangesJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
    val changes = root["changes"] as? JsonArray ?: return emptyList()
    return changes.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val wire =
            runCatching { assistantSettingChangesJson.decodeFromJsonElement(WireAssistantSettingChange.serializer(), entry) }
                .getOrNull() ?: return@mapNotNull null
        val id = wire.id ?: return@mapNotNull null
        val createdAt = wire.createdAt ?: return@mapNotNull null
        val setting = AssistantChangedSetting.fromWire(wire.setting) ?: return@mapNotNull null
        AssistantSettingChange(
            id = id,
            assistantName = wire.assistantName,
            setting = setting,
            serverReported = entry.containsKey("serverId"),
            serverId = wire.serverId,
            serverName = wire.serverName,
            targetRef = wire.targetRef,
            confirmed = wire.confirmed,
            previousValue = wire.previousValue.toAssistantSettingValue(),
            nextValue = wire.nextValue.toAssistantSettingValue(),
            createdAt = createdAt,
        )
    }
}

/** The changes of [settings] among [changes], in their order (web `useAssistantSettingChanges` filter). */
fun List<AssistantSettingChange>.ofSettings(settings: Set<AssistantChangedSetting>): List<AssistantSettingChange> =
    filter { it.setting in settings }

interface RaftAssistantSettingChangesApi {
    /** `GET servers/:id/assistant/setting-changes`; null when it could not be read. */
    suspend fun fetch(serverId: ServerId): List<AssistantSettingChange>?
}

@RaftCoreInternalApi fun assistantSettingChangesResultOf(code: Int, bodyText: String?): List<AssistantSettingChange>? =
    if (code in 200..299 && bodyText != null) parseAssistantSettingChanges(bodyText) else null

@RaftCoreInternalApi class RaftAssistantSettingChangesNetworkApi(
    private val networkClient: RaftNetworkClient,
) : RaftAssistantSettingChangesApi {
    override suspend fun fetch(serverId: ServerId): List<AssistantSettingChange>? {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Visible,
                    path = "servers/${serverId.value}/assistant/setting-changes",
                    serverIdOverride = serverId,
                )
            }.getOrElse { return null }
        return assistantSettingChangesResultOf(response.code, response.bodyText)
    }
}
