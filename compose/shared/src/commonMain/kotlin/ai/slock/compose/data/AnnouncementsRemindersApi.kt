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

internal interface SlockAnnouncementsRemindersApi {
    suspend fun activeAnnouncements(): HomeNetworkResponse
    suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse
    suspend fun reminders(ownerAgentId: MemberId, status: String = "scheduled"): HomeNetworkResponse
}

internal class SlockAnnouncementsRemindersApiClient(
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
                }
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

internal data class AnnouncementsPage(
    val announcementCount: Int,
    val firstTitle: String?,
    val firstPageCount: Int?
)

internal data class AnnouncementCommandResult(val ok: Boolean)

internal data class RemindersPage(
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

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

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
