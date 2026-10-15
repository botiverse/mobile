package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val announcementsRemindersJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Typed Announcements/Reminders boundary.
 *
 * Server contracts (`packages/server/src/routes/announcements.ts`,
 * `packages/server/src/routes/reminders.ts`):
 *  - GET  /announcements/active     → `{ announcements: Announcement[] }`
 *  - POST /announcements/:id/dismiss → `{ ok: true }`
 *  - GET  /reminders                → `{ reminders: ReminderSummary[] }`
 *
 * Domain shapes are defined in `packages/shared/src/index.ts`:
 *  - `Announcement { id, title, pages: AnnouncementPage[], publishedAt }`
 *  - `AnnouncementPage { title?, body }`
 *  - `ReminderSummary { reminderId, title, status, fireAt, createdAt, ... }`
 *
 * Field names are canonical camelCase. Do not add snake_case fallbacks here.
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
        transport.activeAnnouncements()
            .mapAnnouncementsBody("announcements_active_contract_mismatch", AnnouncementsListResponseDto.serializer()) { dto ->
                val items = dto.announcements.mapNotNull { it.toDomain() }
                AnnouncementsPage(
                    announcementCount = dto.announcements.size,
                    firstTitle = dto.announcements.firstOrNull()?.title,
                    firstPageCount = dto.announcements.firstOrNull()?.pages?.size,
                    items = items
                )
            }

    suspend fun dismissAnnouncement(announcementId: String): DataResult<AnnouncementCommandResult> =
        transport.dismissAnnouncement(announcementId)
            .mapAnnouncementsBody("announcement_dismiss_contract_mismatch", DismissResponseDto.serializer()) { dto ->
                if (!dto.ok) return@mapAnnouncementsBody null
                AnnouncementCommandResult(ok = true)
            }

    suspend fun reminders(ownerAgentId: MemberId, status: String = "scheduled"): DataResult<RemindersPage> =
        transport.reminders(ownerAgentId, status)
            .mapAnnouncementsBody("reminders_list_contract_mismatch", RemindersListResponseDto.serializer()) { dto ->
                RemindersPage(
                    reminderCount = dto.reminders.size,
                    firstTitle = dto.reminders.firstOrNull()?.title,
                    firstStatus = dto.reminders.firstOrNull()?.status
                )
            }
}

class NetworkSlockAnnouncementsRemindersApi(
    private val networkClient: SlockNetworkClient
) : SlockAnnouncementsRemindersApi {
    override suspend fun activeAnnouncements(): HomeNetworkResponse =
        networkClient.request(DataRequestPriority.Visible, "announcements/active")

    override suspend fun dismissAnnouncement(announcementId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "announcements/${announcementId.trim()}/dismiss",
            method = "POST"
        )

    override suspend fun reminders(ownerAgentId: MemberId, status: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "reminders",
            query = listOf("ownerAgentId" to ownerAgentId.value, "status" to status)
        )
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
    val kind: AnnouncementKind = AnnouncementKind.Info,
    // Full multi-page content for the announcement modal (task #226). `body`
    // stays as the first page so the home-notification path keeps working.
    val pages: List<AnnouncementPageItem> = emptyList(),
    val label: String? = null,
    val image: AnnouncementImage? = null,
    val cta: AnnouncementCta? = null,
)

data class AnnouncementImage(val assetId: String, val alt: String)
data class AnnouncementCta(val label: String, val href: String)

data class AnnouncementPageItem(
    val title: String?,
    val body: String
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

/**
 * Single-shot, typed decode of the response body. See the realtime mapper for
 * the same pattern; a contract mismatch on any required field collapses to
 * [DataResult.Error] with the supplied code.
 */
private inline fun <Wire, T> HomeNetworkResponse.mapAnnouncementsBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = code >= 500))
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val wire = try {
        announcementsRemindersJson.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    val value = transform(wire)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

// ── Wire DTOs ──
// Source of truth: packages/shared/src/index.ts and the route files above.
// Field names are exact matches; no snake_case fallbacks.

@Serializable
private data class AnnouncementsListResponseDto(
    val announcements: List<AnnouncementWireDto>,
)

@Serializable
private data class AnnouncementWireDto(
    val id: String,
    val title: String,
    val pages: List<AnnouncementPageWireDto> = emptyList(),
    val publishedAt: String? = null,
    val label: String? = null,
    val image: AnnouncementImageWireDto? = null,
    val cta: AnnouncementCtaWireDto? = null,
)

@Serializable
private data class AnnouncementImageWireDto(val assetId: String, val alt: String)

@Serializable
private data class AnnouncementCtaWireDto(val label: String, val href: String)

@Serializable
private data class AnnouncementPageWireDto(
    val title: String? = null,
    val body: String,
)

@Serializable
private data class DismissResponseDto(
    val ok: Boolean,
)

@Serializable
private data class RemindersListResponseDto(
    val reminders: List<ReminderWireDto>,
)

@Serializable
private data class ReminderWireDto(
    val reminderId: String,
    val ownerAgentId: String? = null,
    val title: String,
    val status: String,
    val fireAt: String? = null,
    val firedAt: String? = null,
    val createdAt: String? = null,
)

private fun AnnouncementWireDto.toDomain(): AnnouncementItem? {
    // Server contract: pages[0].body is the inline body shown by the popup.
    val body = pages.firstOrNull()?.body ?: return null
    return AnnouncementItem(
        id = id,
        title = title,
        body = body,
        // actionLabel / targetId / kind are not part of the server Announcement
        // contract. Keep the data-class defaults; never sniff `kind`/`type`
        // strings to fabricate severity.
        pages = pages.map { page -> AnnouncementPageItem(title = page.title, body = page.body) },
        label = label?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 },
        image = image?.takeIf { UUID_PATTERN.matches(it.assetId) && it.alt.length <= 500 }
            ?.let { AnnouncementImage(it.assetId, it.alt) },
        cta = cta?.let { announcementCtaOrNull(it.label, it.href) },
    )
}

private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

private fun announcementCtaOrNull(label: String, rawHref: String): AnnouncementCta? {
    val cleanLabel = label.trim().takeIf { it.isNotEmpty() && it.length <= 100 } ?: return null
    val href = rawHref.trim().takeIf { it.isNotEmpty() && it.length <= 2048 } ?: return null
    if (href.any { it.code < 0x20 }) return null
    val internal = (href.startsWith("/") || href.startsWith("?")) &&
        !href.startsWith("//") && !href.contains('\\') && href.none { it.code <= 0x20 }
    val external = href.startsWith("https://") &&
        !href.substringAfter("https://").contains('@')
    return if (internal || external) AnnouncementCta(cleanLabel, href) else null
}
