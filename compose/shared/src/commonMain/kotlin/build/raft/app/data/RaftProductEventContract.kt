package build.raft.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Product behavior events (RFC-067) for the native apps.
 *
 * Wire contract, verified against botiverse/slock @ [RAFT_PRODUCT_EVENTS_SLOCK_SHA]:
 *  - `packages/shared/src/productEvents.ts`: event names, property names and enum values
 *    (`PRODUCT_EVENT_REGISTRY`, `NAV_DESTINATIONS`, `NAV_SURFACES`, `PAGE_VIEW_ROUTES`,
 *    `SETTINGS_PAGE_TABS` + `NATIVE_SETTINGS_PAGE_TABS`, `PRODUCT_EVENT_SOURCES`).
 *  - `packages/server/src/routes/productEvents.ts` + `services/productEventIngest.ts`:
 *    `GET /api/product-events/config` -> `{ clientEventsAllowed }`;
 *    `POST /api/product-events/batch` with `{ source, app_version?, events: [{ uuid, event,
 *    timestamp, client_session_id?, properties? }] }` (strict, max 100 events) -> `202 { accepted }`,
 *    `400` for a malformed body. Both need sign-in and `X-Server-Id`.
 *
 * Properties are registry enums/booleans only: no ids, paths or user-entered text.
 */
const val RAFT_PRODUCT_EVENTS_SLOCK_SHA: String = "9fdf630244561c770e7223ed190b9920c35da29a"

/** `PRODUCT_EVENT_SOURCES` values a native app may send (the batch `source`). */
@Serializable
enum class RaftProductEventSource(val wire: String) {
    @SerialName("android") Android("android"),
    @SerialName("ios") Ios("ios"),
    @SerialName("harmonyos") HarmonyOs("harmonyos"),
}

/** `NAV_DESTINATIONS`. */
@Serializable
enum class RaftNavDestination(val wire: String) {
    @SerialName("home") Home("home"),
    @SerialName("activity") Activity("activity"),
    @SerialName("search") Search("search"),
    @SerialName("saved") Saved("saved"),
    @SerialName("tasks") Tasks("tasks"),
    @SerialName("members") Members("members"),
    @SerialName("computers") Computers("computers"),
    @SerialName("settings") Settings("settings"),
    @SerialName("more") More("more"),
}

/** `NAV_SURFACES`. */
@Serializable
enum class RaftNavSurface(val wire: String) {
    @SerialName("mobile_tab_bar") MobileTabBar("mobile_tab_bar"),
    @SerialName("rail") Rail("rail"),
    @SerialName("top_bar") TopBar("top_bar"),
    @SerialName("mobile_home_shortcuts") MobileHomeShortcuts("mobile_home_shortcuts"),
    @SerialName("sidebar") Sidebar("sidebar"),
    @SerialName("keyboard_shortcut") KeyboardShortcut("keyboard_shortcut"),
    @SerialName("mobile_more") MobileMore("mobile_more"),
}

/** `PAGE_VIEW_ROUTES`. */
@Serializable
enum class RaftPageRoute(val wire: String) {
    @SerialName("channel") Channel("channel"),
    @SerialName("dm") Dm("dm"),
    @SerialName("activity") Activity("activity"),
    @SerialName("tasks") Tasks("tasks"),
    @SerialName("saved") Saved("saved"),
    @SerialName("search") Search("search"),
    @SerialName("members") Members("members"),
    @SerialName("members_graph") MembersGraph("members_graph"),
    @SerialName("agent") Agent("agent"),
    @SerialName("human") Human("human"),
    @SerialName("computer") Computer("computer"),
    @SerialName("computers") Computers("computers"),
    @SerialName("settings") Settings("settings"),
    @SerialName("release_notes") ReleaseNotes("release_notes"),
    @SerialName("home") Home("home"),
    @SerialName("more") More("more"),
    @SerialName("thread") Thread("thread"),
    @SerialName("task") Task("task"),
    @SerialName("conversation_search") ConversationSearch("conversation_search"),
}

/** `page_viewed.settings_tab`: `SETTINGS_PAGE_TABS` + `NATIVE_SETTINGS_PAGE_TABS`. */
@Serializable
enum class RaftSettingsTab(val wire: String) {
    @SerialName("account") Account("account"),
    @SerialName("language-region") LanguageRegion("language-region"),
    @SerialName("appearance") Appearance("appearance"),
    @SerialName("notifications") Notifications("notifications"),
    @SerialName("server") Server("server"),
    @SerialName("billing") Billing("billing"),
    @SerialName("administration") Administration("administration"),
    @SerialName("im-bridges") ImBridges("im-bridges"),
    @SerialName("integrations") Integrations("integrations"),
    @SerialName("labs") Labs("labs"),
    @SerialName("mcp") Mcp("mcp"),
    @SerialName("providers") Providers("providers"),
    @SerialName("about") About("about"),
    @SerialName("feedback") Feedback("feedback"),
    @SerialName("mobile") Mobile("mobile"),
    @SerialName("storage") Storage("storage"),
}

/** `app_opened.start`. */
@Serializable
enum class RaftAppOpenStart(val wire: String) {
    @SerialName("cold") Cold("cold"),
    @SerialName("foreground") Foreground("foreground"),
}

/** `app_opened.via`. */
@Serializable
enum class RaftAppOpenVia(val wire: String) {
    @SerialName("launcher") Launcher("launcher"),
    @SerialName("notification") Notification("notification"),
    @SerialName("link") Link("link"),
    @SerialName("other") Other("other"),
}

@Serializable
data class RaftNavEntrySelectedProperties(
    val destination: RaftNavDestination,
    val surface: RaftNavSurface,
    @SerialName("was_active") val wasActive: Boolean,
)

@Serializable
data class RaftAppOpenedProperties(
    val start: RaftAppOpenStart,
    val via: RaftAppOpenVia? = null,
)

@Serializable
data class RaftPageViewedProperties(
    val route: RaftPageRoute,
    @SerialName("settings_tab") val settingsTab: RaftSettingsTab? = null,
)

/** One typed registry event. [name] is the registry key; properties are encoded from the typed DTO. */
sealed class RaftProductEvent<P>(val name: String, private val serializer: KSerializer<P>) {
    abstract val properties: P

    fun encodeProperties(): JsonObject =
        RaftProductEventJson.encodeToJsonElement(serializer, properties).jsonObject

    data class NavEntrySelected(override val properties: RaftNavEntrySelectedProperties) :
        RaftProductEvent<RaftNavEntrySelectedProperties>("nav_entry_selected", RaftNavEntrySelectedProperties.serializer())

    data class AppOpened(override val properties: RaftAppOpenedProperties) :
        RaftProductEvent<RaftAppOpenedProperties>("app_opened", RaftAppOpenedProperties.serializer())

    data class PageViewed(override val properties: RaftPageViewedProperties) :
        RaftProductEvent<RaftPageViewedProperties>("page_viewed", RaftPageViewedProperties.serializer())
}

/** One page identity as `page_viewed` reports it. */
data class RaftProductPageView(
    val route: RaftPageRoute,
    val settingsTab: RaftSettingsTab? = null,
) {
    fun toEvent(): RaftProductEvent.PageViewed =
        RaftProductEvent.PageViewed(RaftPageViewedProperties(route = route, settingsTab = settingsTab))
}

/** One event in a batch (`clientEventBatchSchema.events[]`, strict). */
@Serializable
data class RaftProductEventWire(
    val uuid: String,
    val event: String,
    val timestamp: String,
    @SerialName("client_session_id") val clientSessionId: String? = null,
    val properties: JsonObject,
)

/** `POST /api/product-events/batch` body (`clientEventBatchSchema`, strict). */
@Serializable
data class RaftProductEventBatchRequest(
    val source: RaftProductEventSource,
    @SerialName("app_version") val appVersion: String? = null,
    val events: List<RaftProductEventWire>,
)

/** `GET /api/product-events/config` response. */
@Serializable
data class RaftProductEventConfigResponse(
    val clientEventsAllowed: Boolean = false,
)

/** `POST /api/product-events/batch` 202 response. */
@Serializable
data class RaftProductEventBatchResponse(
    val accepted: Int = 0,
)

internal val RaftProductEventJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/** Server ingest caps (`MAX_CLIENT_EVENTS_PER_BATCH`, `app_version` regex/length). */
internal const val RaftProductEventMaxBatchEvents: Int = 100
private val RaftProductEventAppVersionPattern = Regex("^[0-9A-Za-z._+-]{1,64}$")

/** The `app_version` the batch may carry, or null when the host value would fail ingest. */
internal fun raftProductEventAppVersion(raw: String?): String? =
    raw?.trim()?.takeIf { RaftProductEventAppVersionPattern.matches(it) }

internal fun raftProductEventSourceForOs(os: String): RaftProductEventSource? =
    when (os.trim().lowercase()) {
        "android" -> RaftProductEventSource.Android
        "ios" -> RaftProductEventSource.Ios
        "harmonyos", "ohos" -> RaftProductEventSource.HarmonyOs
        else -> null
    }

internal fun encodeRaftProductEventBatch(batch: RaftProductEventBatchRequest): String =
    RaftProductEventJson.encodeToString(RaftProductEventBatchRequest.serializer(), batch)

/** Consent answer for one server. */
sealed interface RaftProductEventsConfigResult {
    data class Answered(val clientEventsAllowed: Boolean) : RaftProductEventsConfigResult

    /** No answer (offline, 5xx, 429): keep events and ask again later. */
    data object Unavailable : RaftProductEventsConfigResult
}

sealed interface RaftProductEventsSendResult {
    data class Accepted(val accepted: Int) : RaftProductEventsSendResult

    /** The server refused this batch for good (400 malformed, 401/403/404/413): drop it. */
    data class Rejected(val code: Int) : RaftProductEventsSendResult

    /** Offline, timeout, 408/429 or 5xx: retry with backoff. */
    data class Retryable(val code: Int) : RaftProductEventsSendResult
}

internal fun parseRaftProductEventsConfig(response: HomeNetworkResponse): RaftProductEventsConfigResult =
    when {
        response.isSuccessful ->
            RaftProductEventsConfigResult.Answered(
                runCatching {
                    RaftProductEventJson
                        .decodeFromString(RaftProductEventConfigResponse.serializer(), response.bodyText.orEmpty())
                        .clientEventsAllowed
                }.getOrDefault(false)
            )
        response.isRaftProductEventRetryable() -> RaftProductEventsConfigResult.Unavailable
        // Web treats any non-OK config answer as "not allowed" (track.ts askAllowed).
        else -> RaftProductEventsConfigResult.Answered(false)
    }

internal fun parseRaftProductEventsSend(response: HomeNetworkResponse): RaftProductEventsSendResult =
    when {
        response.isSuccessful ->
            RaftProductEventsSendResult.Accepted(
                runCatching {
                    RaftProductEventJson
                        .decodeFromString(RaftProductEventBatchResponse.serializer(), response.bodyText.orEmpty())
                        .accepted
                }.getOrDefault(0)
            )
        response.isRaftProductEventRetryable() -> RaftProductEventsSendResult.Retryable(response.code)
        else -> RaftProductEventsSendResult.Rejected(response.code)
    }

private fun HomeNetworkResponse.isRaftProductEventRetryable(): Boolean =
    code <= 0 || code == 408 || code == 429 || code >= 500
