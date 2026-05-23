package ai.slock.compose.data

import ai.slock.compose.model.MemberId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val announcementsRemindersJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Typed Announcements/Reminders boundary.
 *
 * Active announcements parse `{ announcements: [...] }`, dismiss requires an
 * `{ ok: true }` body, and reminders parse `{ reminders: [...] }`. There is no
 * Android-product-only guessed reminder write route in this shared boundary.
 */
interface SlockAnnouncementsRemindersApi {
    suspend fun activeAnnouncements(): HomeNetworkResponse
    suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse
    suspend fun reminders(ownerAgentId: MemberId, status: String = "scheduled"): HomeNetworkResponse
}

class SlockAnnouncementsRemindersApiClient(
    private val transport: SlockAnnouncementsRemindersApi
) {
    suspend fun activeAnnouncements(): DataResult<AnnouncementsPage> =
        transport.activeAnnouncements().mapAnnouncementsBody("announcements_active_contract_mismatch") { root ->
            val announcements = root["announcements"] as? JsonArray ?: return@mapAnnouncementsBody null
            AnnouncementsPage(
                announcementCount = announcements.size,
                firstTitle = announcements.firstOrNullObject()?.string("title"),
                firstPageCount = announcements.firstOrNullObject()?.let { announcement ->
                    (announcement["pages"] as? JsonArray)?.size
                },
                items = announcements.mapNotNull { (it as? JsonObject)?.toAnnouncementItem() }
            )
        }

    suspend fun dismissAnnouncement(announcementId: String): DataResult<AnnouncementCommandResult> =
        transport.dismissAnnouncement(announcementId).mapAnnouncementsBody("announcement_dismiss_contract_mismatch") { root ->
            AnnouncementCommandResult(ok = root.boolean("ok")?.takeIf { it } ?: return@mapAnnouncementsBody null)
        }

    suspend fun reminders(ownerAgentId: MemberId, status: String = "scheduled"): DataResult<RemindersPage> =
        transport.reminders(ownerAgentId, status).mapAnnouncementsBody("reminders_list_contract_mismatch") { root ->
            val reminders = root["reminders"] as? JsonArray ?: return@mapAnnouncementsBody null
            RemindersPage(
                reminderCount = reminders.size,
                firstTitle = reminders.firstOrNullObject()?.string("title"),
                firstStatus = reminders.firstOrNullObject()?.string("status")
            )
        }
}

data class AnnouncementsPage(
    val announcementCount: Int,
    val firstTitle: String?,
    val firstPageCount: Int?,
    val items: List<AnnouncementItem> = emptyList()
)

data class AnnouncementItem(
    val id: String,
    val title: String,
    val body: String,
    val actionLabel: String = "View",
    val targetId: String? = null,
    val kind: AnnouncementKind = AnnouncementKind.Info
)

enum class AnnouncementKind {
    Warning,
    Info,
    Error
}

data class AnnouncementCommandResult(val ok: Boolean)

data class RemindersPage(
    val reminderCount: Int,
    val firstTitle: String?,
    val firstStatus: String?
)

private inline fun <T> HomeNetworkResponse.mapAnnouncementsBody(
    contractCode: String,
    mapper: (JsonObject) -> T?
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val root = runCatching { announcementsRemindersJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = mapper(root)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun JsonArray.firstOrNullObject(): JsonObject? = firstOrNull() as? JsonObject

private fun JsonObject.toAnnouncementItem(): AnnouncementItem? {
    val id = string("id", "_id", "announcementId", "announcement_id") ?: return null
    val firstPage = (this["pages"] as? JsonArray)?.firstOrNullObject()
    val title = string("title", "name")
        ?: firstPage?.string("title", "name")
        ?: return null
    val body = string("body", "message", "description", "content", "subtitle")
        ?: firstPage?.string("body", "message", "description", "content", "subtitle")
        ?: title
    val actionLabel = string("actionLabel", "action_label", "primaryActionLabel", "ctaLabel")
        ?: actionObject()?.string("label", "title")
        ?: "View"
    val targetId = string("targetId", "target_id", "url", "href", "link")
        ?: actionObject()?.string("targetId", "target_id", "url", "href", "link")
    return AnnouncementItem(
        id = id,
        title = title,
        body = body,
        actionLabel = actionLabel,
        targetId = targetId,
        kind = announcementKind()
    )
}

private fun JsonObject.actionObject(): JsonObject? =
    (this["action"] as? JsonObject)
        ?: (this["primaryAction"] as? JsonObject)
        ?: ((this["actions"] as? JsonArray)?.firstOrNull() as? JsonObject)

private fun JsonObject.announcementKind(): AnnouncementKind {
    val raw = string("kind", "type", "severity", "level", "variant").orEmpty().lowercase()
    return when {
        raw.contains("error") || raw.contains("danger") || raw.contains("critical") -> AnnouncementKind.Error
        raw.contains("warn") || raw.contains("outdated") || raw.contains("upgrade") || raw.contains("update") -> AnnouncementKind.Warning
        else -> AnnouncementKind.Info
    }
}

private fun JsonObject.string(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }

private fun JsonObject.string(key: String): String? =
    string(*arrayOf(key))

private fun JsonObject.boolean(key: String): Boolean? =
    when (val primitive = this[key] as? JsonPrimitive) {
        null -> null
        else -> primitive.contentOrNull?.trim()?.lowercase()?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
    }
