package build.raft.app.data

import build.raft.app.network.RaftUserAgentDevice
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
 *    `POST /api/product-events/batch` with `{ source, app_version?, device_class?, events: [{ uuid,
 *    event, timestamp, client_session_id?, properties? }] }` (strict, max 100 events) ->
 *    `202 { accepted }`, `400` for a malformed body. Both need sign-in and `X-Server-Id`.
 *    `device_class` comes from botiverse/slock #10790 @ [RAFT_PRODUCT_EVENTS_DEVICE_CLASS_SLOCK_SHA].
 *    The batch never sends `platform`: the server derives it from `source`.
 *
 * Properties are registry enums/booleans only: no ids, paths or user-entered text.
 */
const val RAFT_PRODUCT_EVENTS_SLOCK_SHA: String = "9fdf630244561c770e7223ed190b9920c35da29a"

/**
 * The entry-level events (`channel_tab_selected`, `message_action_selected`, `activity_item_open`,
 * `activity_mark`, `agent_create_opened`) and their values: botiverse/slock #11064 head, which
 * builds on #11045 (merged on staging as [RAFT_PRODUCT_ENTRY_EVENTS_BASE_SLOCK_SHA]) and adds the
 * native sources, the long-press message actions and the `members_page` / `composer_mention`
 * entries. The server validates names and values strictly; a native app build that sends these
 * must not ship before server v1.21.31 is in production.
 */
const val RAFT_PRODUCT_ENTRY_EVENTS_SLOCK_SHA: String = "36b076b0ca196bbeccd695c05d4df5a587e6602e"

/**
 * botiverse/slock #11070 (staging @ 86f1a6a3477068ca53946c0578cd65889a8ffedf, live in server v1.21.32):
 * `CHANNEL_TAB_SURFACES` gained `header_swipe`.
 */
const val RAFT_PRODUCT_EVENTS_HEADER_SWIPE_SLOCK_SHA: String = "86f1a6a3477068ca53946c0578cd65889a8ffedf"

/**
 * botiverse/slock #11467 head (Kirby, the touch Select Text sheet): `MESSAGE_ACTIONS` gained
 * `select_text`. The server validates values strictly, so a build that sends it must not ship
 * before a server with #11467 is in production.
 */
const val RAFT_PRODUCT_EVENTS_SELECT_TEXT_SLOCK_SHA: String = "27f555f166a5f51e8758d8e10549751db15f4429"

/** botiverse/slock #11045 merge commit on staging: `channel_tab_selected`, `message_action_selected`. */
const val RAFT_PRODUCT_ENTRY_EVENTS_BASE_SLOCK_SHA: String = "3e959613eeeaa80607e826792c1f72bd17459191"

/**
 * botiverse/slock #13636 head (gated features with no server record; live in server v1.21.61):
 * `drafts_page_viewed` (`DRAFT_COUNT_BUCKETS`), `drafts_item_opened` (`DRAFT_PLACES`) and
 * `agent_read_progress_shown` (`AGENT_READ_PROGRESS_PARTS`, `AGENT_READ_PROGRESS_CONVERSATIONS`),
 * all registered for `APP_CLIENTS`. Web emitters: `packages/web/src/analytics/gatedFeatures.ts`,
 * re-read on staging @ 987c47fa6ca2eb03fcace5777f807cd17070cea9.
 */
const val RAFT_PRODUCT_EVENTS_GATED_FEATURES_SLOCK_SHA: String = "a5a145f9fe3ce4da49cba8e7d2ec39293c1f1270"

/**
 * botiverse/slock #14522 (merged on staging as this commit): `app_rating_prompt_requested` with one
 * required property `trigger` (`task_result_viewed` | `action_card_approved`), sources `ios` and
 * `android`. The server validates names and values strictly; a build that sends it must not ship
 * before a server with #14522 is in production.
 */
const val RAFT_PRODUCT_EVENTS_APP_RATING_SLOCK_SHA: String = "9da0de7879d8c8290363e6ab3719d73f2d7bb483"

/**
 * botiverse/slock #10790 head, where `clientEventBatchSchema` gained the optional batch-level
 * `device_class: "phone" | "tablet"` (`packages/server/src/services/productEventIngest.ts`).
 */
const val RAFT_PRODUCT_EVENTS_DEVICE_CLASS_SLOCK_SHA: String = "9a30dba7deb45b2f7d3154a8a57ace55fb45b35c"

/**
 * The batch `device_class`: whether this app runs on a phone or a tablet, as the app decides it.
 * The server prefers it over the User-Agent for `$device_type` (phone -> `mobile`, tablet -> `tablet`).
 */
@Serializable
enum class RaftProductEventDeviceClass(val wire: String) {
    @SerialName("phone") Phone("phone"),
    @SerialName("tablet") Tablet("tablet"),
}

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

/** `CHANNEL_TABS`. */
@Serializable
enum class RaftChannelTab(val wire: String) {
    @SerialName("chat") Chat("chat"),
    @SerialName("tasks") Tasks("tasks"),
    @SerialName("files") Files("files"),
}

/** `CHANNEL_TAB_SURFACES` the app has: the header's Chat / Tasks / Files strip, tapped or swiped. */
@Serializable
enum class RaftChannelTabSurface(val wire: String) {
    @SerialName("header_tabs") HeaderTabs("header_tabs"),
    @SerialName("header_swipe") HeaderSwipe("header_swipe"),
}

/** `MESSAGE_ACTION_SURFACES` the app has: the long-press message menu. */
@Serializable
enum class RaftMessageActionSurface(val wire: String) {
    @SerialName("long_press") LongPress("long_press"),
}

/**
 * `MESSAGE_ACTIONS` the app's long-press menu can send. The registry also has `copy_text`, `pin`,
 * `unpin`, `mark_unread`, `edit`, `delete`, `report` for the native menu; the app's message menu
 * has none of those items today, so they have no entry here. A reply thread's "Mark Thread as
 * Unread" (botiverse/slock #11687) marks the thread, not the message, and web sends no
 * `message_action_selected` for it either, so it is not `mark_unread`.
 */
@Serializable
enum class RaftMessageAction(val wire: String) {
    @SerialName("open_menu") OpenMenu("open_menu"),
    @SerialName("open_reaction_picker") OpenReactionPicker("open_reaction_picker"),
    @SerialName("react_quick") ReactQuick("react_quick"),
    @SerialName("reply_in_thread") ReplyInThread("reply_in_thread"),
    @SerialName("follow_thread") FollowThread("follow_thread"),
    @SerialName("unfollow_thread") UnfollowThread("unfollow_thread"),
    @SerialName("task_done") TaskDone("task_done"),
    @SerialName("task_reopen") TaskReopen("task_reopen"),
    @SerialName("convert_to_task") ConvertToTask("convert_to_task"),
    @SerialName("copy_link") CopyLink("copy_link"),
    @SerialName("copy_markdown") CopyMarkdown("copy_markdown"),
    /** botiverse/slock #11467 @ [RAFT_PRODUCT_EVENTS_SELECT_TEXT_SLOCK_SHA]: opens the Select Text sheet. */
    @SerialName("select_text") SelectText("select_text"),
    @SerialName("forward") Forward("forward"),
    @SerialName("select") Select("select"),
    @SerialName("save") Save("save"),
    @SerialName("unsave") Unsave("unsave"),
    @SerialName("translate") Translate("translate"),
}

/** `activity_item_open.item_kind`. */
@Serializable
enum class RaftActivityItemKind(val wire: String) {
    @SerialName("channel") Channel("channel"),
    @SerialName("dm") Dm("dm"),
    @SerialName("thread") Thread("thread"),
    @SerialName("mention_action") MentionAction("mention_action"),
}

/** `activity_mark.action`. */
@Serializable
enum class RaftActivityMarkAction(val wire: String) {
    @SerialName("read") Read("read"),
    @SerialName("unread") Unread("unread"),
    @SerialName("read_all") ReadAll("read_all"),
    @SerialName("done") Done("done"),
}

/** `AGENT_CREATE_ENTRIES` the app has. */
@Serializable
enum class RaftAgentCreateEntry(val wire: String) {
    @SerialName("channel_members") ChannelMembers("channel_members"),
    @SerialName("members_page") MembersPage("members_page"),
}

/** `DRAFT_COUNT_BUCKETS`: how many drafts the Drafts page listed when it opened. */
@Serializable
enum class RaftDraftCountBucket(val wire: String) {
    @SerialName("0") None("0"),
    @SerialName("1") One("1"),
    @SerialName("2-5") TwoToFive("2-5"),
    @SerialName("6+") SixOrMore("6+"),
}

/** Web `draftCountBucket`: the bucket for [count] listed drafts. */
fun raftDraftCountBucket(count: Int): RaftDraftCountBucket =
    when {
        count <= 0 -> RaftDraftCountBucket.None
        count == 1 -> RaftDraftCountBucket.One
        count <= 5 -> RaftDraftCountBucket.TwoToFive
        else -> RaftDraftCountBucket.SixOrMore
    }

/** `DRAFT_PLACES`: the kind of conversation a draft was started in. */
@Serializable
enum class RaftDraftPlaceKind(val wire: String) {
    @SerialName("channel") Channel("channel"),
    @SerialName("dm") Dm("dm"),
    @SerialName("thread") Thread("thread"),
    @SerialName("task") Task("task"),
}

/** `AGENT_READ_PROGRESS_PARTS`: the agents' faces, or the line saying a message will not reach an agent. */
@Serializable
enum class RaftAgentReadProgressPart(val wire: String) {
    @SerialName("faces") Faces("faces"),
    @SerialName("not_reaching_line") NotReachingLine("not_reaching_line"),
}

/** `AGENT_READ_PROGRESS_CONVERSATIONS`: a DM never shows agent read progress. */
@Serializable
enum class RaftAgentReadProgressConversation(val wire: String) {
    @SerialName("channel") Channel("channel"),
    @SerialName("thread") Thread("thread"),
}

/** `app_rating_prompt_requested.trigger`: the moment after which the app asked for a store rating. */
@Serializable
enum class RaftAppRatingPromptTrigger(val wire: String) {
    /** The person went back to the list from a finished task they had given to an agent. */
    @SerialName("task_result_viewed") TaskResultViewed("task_result_viewed"),
    /** The person approved / confirmed an agent's action card and it ran. */
    @SerialName("action_card_approved") ActionCardApproved("action_card_approved"),
}

@Serializable
data class RaftAppRatingPromptRequestedProperties(
    val trigger: RaftAppRatingPromptTrigger,
)

@Serializable
data class RaftDraftsPageViewedProperties(
    val drafts: RaftDraftCountBucket,
)

@Serializable
data class RaftDraftsItemOpenedProperties(
    val place: RaftDraftPlaceKind,
)

@Serializable
data class RaftAgentReadProgressShownProperties(
    val part: RaftAgentReadProgressPart,
    val conversation: RaftAgentReadProgressConversation,
)

@Serializable
data class RaftChannelTabSelectedProperties(
    val tab: RaftChannelTab,
    val surface: RaftChannelTabSurface,
    @SerialName("was_active") val wasActive: Boolean,
)

@Serializable
data class RaftMessageActionSelectedProperties(
    val action: RaftMessageAction,
    val surface: RaftMessageActionSurface,
    /** True for a reply inside a thread, false for any other message (including a thread's parent). */
    @SerialName("in_thread") val inThread: Boolean? = null,
)

@Serializable
data class RaftActivityItemOpenProperties(
    @SerialName("item_kind") val itemKind: RaftActivityItemKind,
)

@Serializable
data class RaftActivityMarkProperties(
    val action: RaftActivityMarkAction,
)

@Serializable
data class RaftAgentCreateOpenedProperties(
    val entry: RaftAgentCreateEntry,
)

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

    data class ChannelTabSelected(override val properties: RaftChannelTabSelectedProperties) :
        RaftProductEvent<RaftChannelTabSelectedProperties>("channel_tab_selected", RaftChannelTabSelectedProperties.serializer())

    data class MessageActionSelected(override val properties: RaftMessageActionSelectedProperties) :
        RaftProductEvent<RaftMessageActionSelectedProperties>("message_action_selected", RaftMessageActionSelectedProperties.serializer())

    data class ActivityItemOpen(override val properties: RaftActivityItemOpenProperties) :
        RaftProductEvent<RaftActivityItemOpenProperties>("activity_item_open", RaftActivityItemOpenProperties.serializer())

    data class ActivityMark(override val properties: RaftActivityMarkProperties) :
        RaftProductEvent<RaftActivityMarkProperties>("activity_mark", RaftActivityMarkProperties.serializer())

    data class AgentCreateOpened(override val properties: RaftAgentCreateOpenedProperties) :
        RaftProductEvent<RaftAgentCreateOpenedProperties>("agent_create_opened", RaftAgentCreateOpenedProperties.serializer())

    data class DraftsPageViewed(override val properties: RaftDraftsPageViewedProperties) :
        RaftProductEvent<RaftDraftsPageViewedProperties>("drafts_page_viewed", RaftDraftsPageViewedProperties.serializer())

    data class DraftsItemOpened(override val properties: RaftDraftsItemOpenedProperties) :
        RaftProductEvent<RaftDraftsItemOpenedProperties>("drafts_item_opened", RaftDraftsItemOpenedProperties.serializer())

    data class AgentReadProgressShown(override val properties: RaftAgentReadProgressShownProperties) :
        RaftProductEvent<RaftAgentReadProgressShownProperties>("agent_read_progress_shown", RaftAgentReadProgressShownProperties.serializer())

    data class AppRatingPromptRequested(override val properties: RaftAppRatingPromptRequestedProperties) :
        RaftProductEvent<RaftAppRatingPromptRequestedProperties>(
            "app_rating_prompt_requested",
            RaftAppRatingPromptRequestedProperties.serializer(),
        )

    /** Android: where this install came from (store + validated utm_*), once per install after first sign-in. */
    data class AppInstallSourceRead(override val properties: RaftAppInstallSourceReadProperties) :
        RaftProductEvent<RaftAppInstallSourceReadProperties>(
            "app_install_source_read",
            RaftAppInstallSourceReadProperties.serializer(),
        )

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
    /** Optional; left out when the platform has not said. */
    @SerialName("device_class") val deviceClass: RaftProductEventDeviceClass? = null,
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
    /** Events this server replica had already queued (same uuid); absent when 0. */
    val duplicates: Int = 0,
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

/**
 * The `device_class` for the phone/tablet line the platform already draws for the User-Agent
 * (Android: smallest width >= 600dp; iOS: `userInterfaceIdiom == .pad`). Null when not installed yet.
 */
internal fun raftProductEventDeviceClass(device: RaftUserAgentDevice?): RaftProductEventDeviceClass? =
    when (device) {
        RaftUserAgentDevice.AndroidPhone, RaftUserAgentDevice.IPhone -> RaftProductEventDeviceClass.Phone
        RaftUserAgentDevice.AndroidTablet, RaftUserAgentDevice.IPad -> RaftProductEventDeviceClass.Tablet
        null -> null
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
    data class Accepted(val accepted: Int, val duplicates: Int = 0) : RaftProductEventsSendResult

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
            runCatching {
                RaftProductEventJson
                    .decodeFromString(RaftProductEventBatchResponse.serializer(), response.bodyText.orEmpty())
            }.getOrNull().let { body ->
                RaftProductEventsSendResult.Accepted(accepted = body?.accepted ?: 0, duplicates = body?.duplicates ?: 0)
            }
        response.isRaftProductEventRetryable() -> RaftProductEventsSendResult.Retryable(response.code)
        else -> RaftProductEventsSendResult.Rejected(response.code)
    }

private fun HomeNetworkResponse.isRaftProductEventRetryable(): Boolean =
    code <= 0 || code == 408 || code == 429 || code >= 500
