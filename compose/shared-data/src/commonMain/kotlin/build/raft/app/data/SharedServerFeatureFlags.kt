package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Server feature-flag evaluation — the KMP mirror of web `packages/web/src/store/serverFeatureFlags.ts`.
 * Both ends consume the SAME batch endpoint and registry so a flag is gated identically cross-platform.
 *
 * Wire contract (server-verified): POST `feature-flags/evaluate` with `{serverId, keys, platform}` (batch,
 * per-server; userId is derived server-side from auth, NOT in the body), returning `{evaluations:[{key,
 * enabled, reason}]}`. `platform` (slock #186, landed) is a `"web" | "mobile"` rule stage — evaluated between the
 * user and server stages — so a rollout can target mobile-only. This client
 * always sends `"mobile"` ([FEATURE_FLAG_PLATFORM_MOBILE]); the server treats every KMP surface as mobile.
 */
const val SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY: String = "sync_core_notification_prefs_v0"
const val SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY: String = "sync_core_read_state_v1"
const val MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY: String = "mobile_thread_page_size_v0"
/**
 * Server-driven phone conversation header primary action (`#wg-mobile-sync:#13`): per server,
 * default OFF. When enabled, its `variant` holds a comma-separated priority list of slot tokens
 * (`search`, `tasks`, `files`, `details`/`settings`, `call`, `none`); the first available action
 * becomes the single exposed button beside `⋯` and remaining actions fold into `⋯` in canonical
 * head order. Off, unresolved, or blank keeps the default (`search`).
 */
const val MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY: String = "mobile_phone_header_action_v0"
const val MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY: String = "mobile_activity_stable_order_v1"
/**
 * App switch for the server setup (onboarding) WebView. On: setup shows when the server's
 * setup-projection says blocksChat. Off / unresolved: no setup check (see serverSetupGateEnabled).
 * New key, so builds that read the old `enable_webview_onboarding_v0` are unaffected.
 */
const val MOBILE_SERVER_SETUP_V1_FEATURE_FLAG_KEY: String = "mobile_server_setup_v1"
/**
 * #367 Thread first-frame render: single-paragraph light markdown path.
 * Default OFF everywhere (server-unresolved evaluates false); local A/B goes
 * through the diagnostics "Local Feature Overrides" ForceOn/ForceOff latch.
 */
const val THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY: String =
    "thread_markdown_single_paragraph_light_v0"

// Retired with the Ktor 3 switch (artin 2026-10-23), no longer read by the app; server flag rows can be
// cleaned up: android_reused_h2_recovery_v0 (NetworkKMM reused-HTTP/2 watchdog), mobile_doh_fallback_v0
// (curl DNS-over-HTTPS fallback), mobile_curl_socketio_v0 (realtime over curl Socket.IO).
// sync_core_messages_v1 is no longer read either (2026-10-30): the sync-core message line is always on.
// product_feedback_multi_app is no longer read from 1.22 (artin): Settings > Feedback and feedback cards
// are native on every platform (1.24, desktop included).
// Retired on the server (always on, #raft-architecture:#32), no longer read by the app; their behaviour is
// the former "on" path everywhere: read_receipts_v0, message_cards, interactive_link_cards,
// account_deletion_v0, action_card_spec, mobile_sync_changes_v0, activity_overview_web_v0,
// thread_unread_badge_v0, sidebar_mark_all_read_v0. Their Feature Flag Admin rows stay until older
// app versions that still evaluate them are gone.
// Gogo removes the server flag row after old clients are checked.
// mobile_thread_reverse_layout_v0 is no longer read (artin 2026-11-14): the thread list is always
// bottom-up and its Settings > Mobile switch is gone; the flag row can go once older app versions are.
// external_channels_v1 is no longer read (retired on the server, botiverse/slock #15939): external
// channels are always on, and every surface that read it takes its former "on" path.
// task_v2_conversations_v0 is no longer read (stdrc, #raft-feature-flag): task v2 is always on in the
// app and every surface that read it takes its former "on" path. Its flag row stays only because app
// versions up to 1.28 still ask for it (the server answers it as always on, #wg-task-v2:#32).

/**
 * Keys that are ON unless the server explicitly decides otherwise (artin 2026-10-10: DoH defaults on,
 * the server can push it off). For these keys "no decision" reads ON: no evaluation yet, an evaluate
 * failure or malformed body, no stored last-known value, the key missing from the response, or the
 * server reporting `missing_flag` (no flag row exists). Every other server reason (`kill_switch`,
 * `flag_disabled`, any rule, `default`) is an explicit decision and is followed as sent.
 */
// runtime_form_v2_web (default on from 2026-10-21) is no longer read: runtime form v2 is the only form
// (task #8, retire runtime form v1), so the server flag row can go once no client reads it.
val SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS: Set<String> =
    setOf(MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY)

/** Server evaluation reason for a key that has no flag row at all (slock featureFlagService). */
@RaftCoreInternalApi const val FEATURE_FLAG_REASON_MISSING_FLAG: String = "missing_flag"

/** Server evaluation reason for a globally killed flag (slock featureFlagService). */
@RaftCoreInternalApi const val FEATURE_FLAG_REASON_KILL_SWITCH: String = "kill_switch"

// agent_migration_v0 is no longer read (2026-11-05): agent migration is always shown, as on web
// (slock #13059). The server flag row can go once app versions that still read it are gone.

/** Moving an agent to the cloud (slock `AGENT_MIGRATION_HOSTED_FEATURE_FLAG_KEY`). */
const val AGENT_MIGRATION_HOSTED_FEATURE_FLAG_KEY: String = "agent_migration_hosted_v0"

/** Cloud agents in the create dialog (slock `ANTIPROTON_HOSTED_RUNTIME_FEATURE_FLAG_KEY`). */
const val ANTIPROTON_HOSTED_RUNTIME_FEATURE_FLAG_KEY: String = "antiproton_hosted_runtime"

/**
 * Workspace Artifacts (slock `packages/shared/src/featureFlags.ts` `WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY`):
 * per server, default off, no rules. Off (or unresolved), Artifact links stay plain links and nothing
 * new is shown: no chips, no item cards, no Activity object rows.
 */
const val WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY: String = "workspace_artifacts_v0"

/**
 * Inbox refresh step 4 (#triage 6db2bd32): a live `message:new` / `thread:updated` for a row the
 * Activity list already shows patches its preview, unread and mention in place instead of
 * re-reading the list; a hidden list reads nothing until it shows again (`visibility`); while
 * visible, patched rows are re-read at most once per [ActivityInPlaceReconcileIntervalMillis]
 * (`inplace_reconcile`); and the Activity badge follows the loaded All list instead of its own
 * summary read. Mobile-only key, default OFF (server-unresolved evaluates false): off, every read
 * is today's.
 */
const val MOBILE_INBOX_INPLACE_FEATURE_FLAG_KEY: String = "mobile_inbox_inplace_v0"

/**
 * Inbox redesign step 2 (#triage:#244; slock web `INBOX_ROW_POSITION_FLAG_KEY`, shared with web): with
 * [MOBILE_INBOX_INPLACE_FEATURE_FLAG_KEY] on too, each listed Activity row is kept by the shared per-row
 * position rule (`reduceActivityRow`, `activityLiveUnread`): a late older answer never regresses a row,
 * a live message the rule cannot judge re-reads only its row (`GET /channels/inbox?ids=`), and the
 * loaded All list is the badge's only writer. Per server, default OFF: off, every path is as before.
 */
const val INBOX_ROW_POSITION_FEATURE_FLAG_KEY: String = "inbox_row_position_v1"

/**
 * 1.30 web-aligned Activity in-place + per-row position path (#proj-mobile:#163, Tenny d229571c):
 * client default ON (in [SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS]), so `missing_flag` /
 * unresolved evaluates ON everywhere without a DB migration, while an explicit server rule,
 * `flag_disabled`, or `kill_switch` turns it off per server or globally
 * ([SharedServerFeatureFlags.isActivityInPlaceEnabled]).
 */
const val MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY: String = "mobile_activity_inplace_v1"

/**
 * Activity rows in incremental sync (#proj-mobile:#167; botiverse/slock #15312, the server's
 * `SYNC_INBOX_ROWS_FEATURE_FLAG_KEY`, shared with web): per server, default off. On, the sync pull asks
 * for `capabilities=inbox_row` and applies each changed Activity row it gets (`inbox_row`) in place,
 * through the same merge as a `GET /channels/inbox?ids=` answer, instead of reading the list again.
 * Off (or unresolved), the pull asks for nothing new and the response is exactly as before.
 */
const val SYNC_INBOX_ROWS_FEATURE_FLAG_KEY: String = "sync_inbox_rows_v0"

/**
 * Personal assistant (botiverse/slock `PERSONAL_ASSISTANT_FEATURE_FLAG_KEY` in
 * `packages/server/src/services/featureFlagService.ts`, web `PERSONAL_ASSISTANT_FLAG_KEY`): per
 * server, default off. A pre-check before asking `GET servers/:id/assistant`, whose `enabled` then
 * decides; off (or unresolved), nothing is asked and the composer has no otter.
 */
const val PERSONAL_ASSISTANT_FEATURE_FLAG_KEY: String = "personal_assistant"

/**
 * Navigation 3 page stack on Android (plan step 1): the app shell window draws the shared page stack
 * and opens the allowlisted pages on it instead of in Activities of their own. Mobile-only key,
 * default OFF (server-unresolved evaluates false). Unlike the other flags it is not read per
 * server at use: the last evaluation is kept on the device ([build.raft.app.nav.RaftNav3StackFlag])
 * and the app reads it once at launch, so a change takes effect on the next app start. Local test
 * switch: Settings > Developer > "Navigation 3 page stack".
 */
const val MOBILE_NAV3_STACK_FEATURE_FLAG_KEY: String = "mobile_nav3_stack_v1"

/**
 * iOS native navigation (iPhone only): the opted-in pages' heads on the real UINavigationBar and the
 * system swipe-back instead of the Compose heads and the full-page pan. Mobile-only key, default
 * OFF (server-unresolved evaluates false). Read like [MOBILE_NAV3_STACK_FEATURE_FLAG_KEY]: the last
 * evaluation is kept on the device ([build.raft.app.nav.RaftIosNativeNavFlag]) and the app reads it
 * once at launch, so a change takes effect on the next app start. Local test switch: Settings >
 * Developer > "iOS native navigation" (iPhone only).
 */
const val MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY: String = "mobile_ios_native_nav_v1"

/**
 * Agent Lounge v0 (botiverse/slock `packages/shared/src/featureFlags.ts` `AGENT_LOUNGE_FEATURE_FLAG_KEY`, web
 * `AGENT_LOUNGE_FLAG_KEY`): per server, default off. On, and not a Guest ([agentLoungeEnabled]), an agent's
 * profile and DM header open its Lounge, and a Lounge you joined or were mentioned in has a row under the
 * agent's DM. Off (or unresolved), no Lounge is offered; `POST /agents/:id/lounge` answers 404.
 */
const val AGENT_LOUNGE_FEATURE_FLAG_KEY: String = "agent_lounge_v0"

/**
 * Public servers (botiverse/slock `packages/shared/src/featureFlags.ts` `PUBLIC_SERVER_FEATURE_FLAG_KEY`):
 * per server, default off. Read by Settings search, which finds Server > Public access only for the
 * owner while it is on (the section itself shows once `GET /servers/:id/public-visibility` answers).
 */
const val PUBLIC_SERVER_FEATURE_FLAG_KEY: String = "public_server_v0"

/**
 * Server SSO v1 (botiverse/slock `packages/server/src/services/featureFlagService.ts`
 * `SERVER_SSO_FEATURE_FLAG_KEY`, rfcs/083-server-sso.md §8): per server, default off. Read by
 * Settings search, which finds Administration > Single sign-on for an admin only while it is on
 * (web `useServerSsoShown`: `GET /servers/:id/sso` answers 404 while off).
 */
const val SERVER_SSO_FEATURE_FLAG_KEY: String = "server_sso_v1"

/**
 * Channel announcements (botiverse/slock `packages/shared/src/featureFlags.ts`
 * `CHANNEL_ANNOUNCEMENT_FEATURE_FLAG_KEY`, web `serverFeatureFlags.ts` `channelAnnouncement`): per server,
 * default off. On, a public, private or joint channel shows its announcement strip above the timeline and
 * an Announcement row in its details (ChannelAnnouncementApi.kt). Off (or unresolved), neither shows and
 * no announcement is read.
 */
const val CHANNEL_ANNOUNCEMENT_FEATURE_FLAG_KEY: String = "channel_announcement_v0"

/**
 * Server Labs UI (botiverse/slock `packages/shared/src/featureFlags.ts` `SERVER_LABS_UI_FEATURE_FLAG_KEY`,
 * drizzle 0192): per server, default off. On, Settings shows Labs (web `labsUiEnabled`); the Labs
 * routes stay the authority on who may change what.
 */
const val SERVER_LABS_UI_FEATURE_FLAG_KEY: String = "server_labs_ui_v0"

/**
 * Claude limit resets on the Computer usage card (botiverse/slock `packages/shared/src/featureFlags.ts`
 * `CLAUDE_LIMIT_RESET_FEATURE_FLAG_KEY`, web `serverFeatureFlags.ts` `claudeLimitReset`; #15564): per server,
 * default off. On, and the server's `GET …/runtime-auth` saying the Computer runs them
 * (`claudeLimitResets: ok`, sent only while the server's evaluation of the same key is on), the card offers
 * "Use a limit reset" (ComputerRuntimeClaudeLimitReset.kt). Off, nothing about Claude resets is drawn and
 * the server refuses both routes (404 `claude_limit_reset_disabled`). The first key, `claude_limit_reset`,
 * was retired (artin, #proj-raft-computer:4e327d5c); the app never read it.
 */
const val CLAUDE_LIMIT_RESET_FEATURE_FLAG_KEY: String = "claude_limit_reset_v2"

/**
 * Mobile in-app browser App entries (`Raft-Host`) and native "Log in with Raft" catch
 * (#proj-workspace:#356): per server, default off, read live from server flags. Off (or unresolved),
 * entries still open in-app with no `Raft-Host` header and no authorize catch.
 */
const val MOBILE_RAFT_SIGN_IN_FEATURE_FLAG_KEY: String = "mobile_raft_sign_in_v0"

/**
 * Screenshot -> quick feedback sheet (#wg-mobile-feedback:#276): per-server allowlist for
 * production builds. Default OFF everywhere when unresolved or missing; local developer override
 * is registered in [build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry].
 */
const val MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY: String = "mobile_screenshot_feedback_v0"

/**
 * Standalone cross-platform Hybrid WebView Container (`compose/web-container`, `dm:@artin:#6`):
 * routes `RaftSandboxWebView` / `RaftWebView` surfaces through `WebContainerView` with the pure
 * Kotlin JSB runtime (`window.raftBridge`) and unified navigation/watchdog/isolation pipeline.
 * Default OFF everywhere (unresolved or missing evaluates false); local developer/QA override is
 * registered in [build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry].
 */
const val MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY: String = "mobile_web_container_v1"

/**
 * Android dual-engine push: end-to-end silent FCM reachability probe + automatic direct-APK
 * foreground-service fallback/recovery + probed-reachability local notification gating + Play
 * dismissible notice (`#proj-mobile:#379`). Per server, default OFF (unresolved evaluates false).
 * When off or when the server answers 404 on the probe route, Android behavior stays identical to
 * pre-probe behavior (zero probes sent, zero state transitions, zero prompts).
 */
const val MOBILE_FCM_PROBE_FEATURE_FLAG_KEY: String = "mobile_fcm_probe_v0"

/**
 * Long-lived UI visibility gate for Early access enrollment in Settings › About
 * (`#proj-mobile:e48030bc`, artin 2026-11-26). Per server, default OFF (unresolved evaluates false).
 * Enabled on any joined server surfaces the Early access row (Android direct-install `alpha` toggle
 * or Google Play / iOS TestFlight enrollment link).
 */
const val MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY: String = "mobile_early_access_visible_v0"

/**
 * Long-lived default-channel gate for Early access (`#proj-mobile:e48030bc`, artin 2026-11-26).
 * Per server, default OFF (unresolved evaluates false). When [MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY]
 * is also enabled and the user has not explicitly toggled the local channel override, Android
 * direct-install defaults to the Hands `alpha` channel.
 */
const val MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY: String = "mobile_early_access_default_on_v0"

/**
 * Per-server wide rail places (botiverse/slock #16715 `packages/web/src/store/serverFeatureFlags.ts`
 * `RAIL_PLACES_FLAG_KEY`): per server, default OFF (unresolved or missing evaluates false).
 * When ON, the wide rail uses per-server places (`GET/PATCH /servers/:id/sidebar-order`, installed
 * App places, and Edit navigation bar). When OFF or loading, the wide rail keeps the standard
 * built-in tabs in the account's order (`users.rail_tab_order`) and never reads or writes per-server
 * rail places.
 */
const val RAIL_PLACES_V0_FEATURE_FLAG_KEY: String = "rail_places_v0"

/**
 * Per-server phone & native tablet/foldable navigation places (botiverse/slock #16964,
 * `#proj-mobile:#329`): per server, default OFF (unresolved or missing evaluates false).
 * When ON on Android and iOS, the phone bottom tab bar (first 1..4 visible built-in places +
 * Server tab) and the native tablet/foldable navigation rail project from per-server `mobilePlaces`
 * (`GET/PATCH /servers/:id/sidebar-order` and `mobile_places:updated`). Desktop Compose never reads
 * `mobilePlaces`.
 */
const val TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY: String = "tab_bar_places_v0"

/**
 * Raft Agent Voice (`#wg-mobile-sync:#95`, botiverse/slock `packages/shared/src/featureFlags.ts`
 * `AGENT_VOICE_FEATURE_FLAG_KEY`): per server, default OFF (unresolved or missing evaluates false).
 * When ON, an agent's profile offers the Voice settings section (`voice-settings` / `voice-questions`,
 * hidden on 404) and eligible agents offer Call entry points in Agent DM and Agent Profile headers.
 */
const val AGENT_VOICE_FEATURE_FLAG_KEY: String = "agent_voice_v1"

/**
 * Agent View Context v0 & Context Access grants (botiverse/slock `packages/shared/src/featureFlags.ts`
 * `AGENT_CONTEXT_VIEW_FEATURE_FLAG_KEY`, #12980 / #13548): per server, default OFF (unresolved or
 * missing evaluates false). When ON, server endpoints serve context status, context view, viewed-by
 * records, and owner/admin context access grants.
 */
const val AGENT_CONTEXT_VIEW_FEATURE_FLAG_KEY: String = "agent_context_view_v0"

/**
 * Workers v1 (botiverse/slock `packages/shared/src/featureFlags.ts` `WORKER_V1_FEATURE_FLAG_KEY`,
 * `#wg-mobile-sync:#284`): per server, default OFF (unresolved or missing evaluates false).
 * Gates the Computer detail page's "Runtimes for Workers" section and subsequent Worker v1 surfaces.
 */
@RaftCoreInternalApi const val WORKER_V1_FEATURE_FLAG_KEY: String = "worker_v1"

/**
 * DMs without threads, a Lab (botiverse/slock #16664 `packages/shared/src/featureFlags.ts`
 * `DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY`, web `serverFeatureFlags.ts` `dmMainTimelineLab`,
 * `#wg-mobile-sync:#305`): per server, default OFF (unresolved or missing evaluates false).
 * When ON, DMs with an agent hide/disable every entry that starts a new thread reply (hover
 * toolbar, long-press / context / More menu, single-tap on a message row, and the Compact menu's
 * Reply tool — disabled in its fixed 6-slot primary row and hidden in partial text selection);
 * existing threads still open from their replies badge or inline replies, and human DMs and
 * channels are unaffected.
 */
const val DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY: String = "dm_main_timeline_lab_v0"

/**
 * People Activity rows full rule (`botiverse/slock` #17095 / #17123, `PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY`):
 * per server, default OFF (unresolved or missing evaluates false). When ON, a person's thread,
 * channel, and DM rows follow the full people predicate (`peopleRowMessageMoves(fullRule = true)`,
 * `ActivityUnreadRules(peopleActivityRows = true)`), so history-only, agent-only, and noise lines
 * leave those rows untouched.
 */
@RaftCoreInternalApi const val PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY: String = "people_activity_rows_v0"

/** Effective Early access policy across a signed-in account's joined servers. */
data class RaftEarlyAccessFlagPolicy(
    val visible: Boolean = false,
    val defaultOn: Boolean = false,
)

/**
 * Lightweight host listener bridging server-driven Early access flags (`#proj-mobile:e48030bc`) to
 * Android's Hands update channel store. Null on iOS, desktop, and tests that do not install it.
 */
interface RaftEarlyAccessPolicyHostListener {
    fun onPolicyChanged(policy: RaftEarlyAccessFlagPolicy)
    fun onSignedOut()
}

object RaftEarlyAccessPolicyHost {
    @Volatile
    var listener: RaftEarlyAccessPolicyHostListener? = null

    fun onPolicyChanged(policy: RaftEarlyAccessFlagPolicy) {
        runCatching { listener?.onPolicyChanged(policy) }
    }

    fun onSignedOut() {
        runCatching { listener?.onSignedOut() }
    }
}

/** Web-shared product flags plus mobile-only runtime safety flags evaluated in the same prefetch batch. */
val REGISTERED_SERVER_FEATURE_FLAG_KEYS: List<String> = listOf(
    SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY,
    SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY,
    MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY,
    MOBILE_PHONE_HEADER_ACTION_FEATURE_FLAG_KEY,
    MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
    MOBILE_SERVER_SETUP_V1_FEATURE_FLAG_KEY,
    THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY,
    AGENT_MIGRATION_HOSTED_FEATURE_FLAG_KEY,
    ANTIPROTON_HOSTED_RUNTIME_FEATURE_FLAG_KEY,
    WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY,
    MOBILE_INBOX_INPLACE_FEATURE_FLAG_KEY,
    INBOX_ROW_POSITION_FEATURE_FLAG_KEY,
    MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY,
    SYNC_INBOX_ROWS_FEATURE_FLAG_KEY,
    PERSONAL_ASSISTANT_FEATURE_FLAG_KEY,
    MOBILE_NAV3_STACK_FEATURE_FLAG_KEY,
    MOBILE_IOS_NATIVE_NAV_FEATURE_FLAG_KEY,
    // Slack Bridge (slock `SLACK_BRIDGE_FEATURE_FLAG_KEYS.master`): the Settings IM Bridges row.
    SLACK_BRIDGE_FEATURE_FLAG_KEY,
    // Server Labs UI: the Settings Labs row and page.
    SERVER_LABS_UI_FEATURE_FLAG_KEY,
    AGENT_LOUNGE_FEATURE_FLAG_KEY,
    // Settings search: who finds Public access and Single sign-on.
    PUBLIC_SERVER_FEATURE_FLAG_KEY,
    SERVER_SSO_FEATURE_FLAG_KEY,
    CHANNEL_ANNOUNCEMENT_FEATURE_FLAG_KEY,
    // The Computer usage card's "Use a limit reset" for Claude Code.
    CLAUDE_LIMIT_RESET_FEATURE_FLAG_KEY,
    // Mobile in-app browser App entries (`Raft-Host`) and native Log in with Raft (#proj-workspace:#356).
    MOBILE_RAFT_SIGN_IN_FEATURE_FLAG_KEY,
    // Screenshot -> quick feedback sheet allowlist (#wg-mobile-feedback:#276).
    MOBILE_SCREENSHOT_FEEDBACK_FEATURE_FLAG_KEY,
    // Standalone cross-platform Hybrid WebView Container (`compose/web-container`, default OFF).
    MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY,
    // Android silent FCM reachability probe + dual-engine fallback (#proj-mobile:#379, default OFF).
    MOBILE_FCM_PROBE_FEATURE_FLAG_KEY,
    // Long-lived Early access visibility & default-channel gates (#proj-mobile:e48030bc, default OFF).
    MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY,
    MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY,
    // Per-server wide rail places (botiverse/slock #16715, default OFF).
    RAIL_PLACES_V0_FEATURE_FLAG_KEY,
    // Per-server phone & native tablet/foldable navigation places (botiverse/slock #16964, default OFF).
    TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY,
    // Raft Agent Voice settings and live voice calls (#wg-mobile-sync:#95, default OFF).
    AGENT_VOICE_FEATURE_FLAG_KEY,
    // Agent View Context v0 & Context Access grants (botiverse/slock #12980 / #13548, default OFF).
    AGENT_CONTEXT_VIEW_FEATURE_FLAG_KEY,
    // Workers v1 (#wg-mobile-sync:#284, default OFF).
    WORKER_V1_FEATURE_FLAG_KEY,
    // DMs without threads Lab (#wg-mobile-sync:#305, botiverse/slock #16664, default OFF).
    DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY,
    // People Activity rows full rule (botiverse/slock #17095 / #17123, default OFF).
    PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY,
    // Minimum supported build (RaftMinBuildGate): the threshold is the flag's variant.
    MOBILE_MIN_BUILD_ANDROID_V1_FEATURE_FLAG_KEY,
    MOBILE_MIN_BUILD_IOS_V1_FEATURE_FLAG_KEY,
    // Trace sampling (slock docs/observability/trace-sampling.md): the rate is the variant, the most
    // specific key with a flag decides (RaftTraceSampling.kt).
) + (
    traceSampleKeys(MOBILE_THREAD_OPEN_TRACE_NAME) +
        traceSampleKeys(MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME) +
        traceSampleKeys(MOBILE_FRAMES_RESUME_TRACE_NAME) +
        traceSampleKeys(MOBILE_FRAMES_SCROLL_TRACE_NAME) +
        traceSampleKeys(MOBILE_HTTP_ERROR_TRACE_NAME) +
        traceSampleKeys(MOBILE_UI_ERROR_TRACE_NAME) +
        traceSampleKeys(MOBILE_WEBVIEW_TRACE_NAME) +
        traceSampleKeys(MOBILE_WEB_CONTAINER_TRACE_NAME)
).distinct()

/**
 * The `platform` value this client reports to feature-flag evaluation. The server's `FeatureFlagPlatform`
 * is coarse (`"web" | "mobile"`), so every KMP surface (Android / iOS) reports `"mobile"`.
 */
const val FEATURE_FLAG_PLATFORM_MOBILE: String = "mobile"

private val featureFlagsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

private val featureFlagRequestGeneration = atomic(0L)

@Serializable
private data class FeatureFlagEvaluateRequestBody(
    val serverId: String,
    val keys: List<String>,
    // Sent so the server can apply platform-scoped rules (slock #186). `featureFlagsJson` has
    // encodeDefaults=true, so the default is emitted on the wire; the server treats an omitted platform as
    // all-platform, so sending "mobile" is what makes a mobile-only rollout target this client.
    val platform: String = FEATURE_FLAG_PLATFORM_MOBILE,
    // task #1144: lets server rules require an OS and a minimum build. Null = unknown; the server treats
    // it as "rule does not match".
    val os: String? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val buildType: String? = null,
)

@Serializable
private data class FeatureFlagEvaluation(
    val key: String,
    val enabled: Boolean = false,
    val reason: String? = null,
    val variant: String? = null,
)

@Serializable
private data class FeatureFlagEvaluateResponseBody(
    val evaluations: List<FeatureFlagEvaluation> = emptyList(),
)

/** A resolved per-server feature-flag snapshot. `resolved=false` = not yet fetched (all keys default off). */
data class ServerFeatureFlagSnapshot(
    val serverId: ServerId?,
    val resolved: Boolean,
    val values: Map<String, Boolean>,
    val variants: Map<String, String> = emptyMap(),
    /**
     * The server's `reason` per evaluated key (`missing_flag`, `default`, `platform_rule`,
     * `kill_switch`, ...); a key the answer left out has none. Trace sampling needs it to tell "no flag"
     * from "flag off" ([traceSampleEvaluations]).
     */
    val reasons: Map<String, String> = emptyMap(),
) {
    fun isEnabled(key: String): Boolean = values[key] == true
    fun variant(key: String): String? = variants[key]

    companion object {
        val Unresolved: ServerFeatureFlagSnapshot = ServerFeatureFlagSnapshot(null, resolved = false, values = emptyMap())
    }
}

interface RaftFeatureFlagsApi {
    suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse
}

@RaftCoreInternalApi class RaftFeatureFlagsNetworkApi(
    private val networkClient: RaftNetworkClient,
    private val clientContext: () -> FeatureFlagClientContext = ::currentFeatureFlagClientContext,
) : RaftFeatureFlagsApi {
    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse {
        val client = clientContext()
        return networkClient.request(
            priority = DataRequestPriority.Background,
            path = "feature-flags/evaluate",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = featureFlagsJson.encodeToString(
                FeatureFlagEvaluateRequestBody(
                    serverId = serverId.value,
                    keys = keys,
                    os = client.os,
                    appVersion = client.appVersion,
                    buildNumber = client.buildNumber,
                    buildType = client.buildType,
                ),
            ),
        )
    }
}

/**
 * Caches the registered flag evaluation per server. [prefetch] issues the batch request to
 * `feature-flags/evaluate` and updates the snapshot;
 * [snapshotFor]/[isEnabled] are safe defaults-off reads before/without a resolve. A failed fetch leaves
 * the prior snapshot (or Unresolved) — flags default OFF, so the gated feature stays dark, never broken.
 */
/**
 * Process-local diagnostic override seam for the boolean read ([SharedServerFeatureFlags.isEnabled]). It
 * receives the server-effective value and returns
 * the final one, so the diagnostics "Local Feature Overrides" page can still show server / override / final
 * side by side. `null` (the default, and every host that never entitles the override registry) reads the raw
 * snapshot; [SharedServerFeatureFlags.snapshotFor] is always raw. `serverId` is null when the caller reads
 * without a server.
 */
fun interface ServerFeatureFlagDiagnosticOverride {
    fun finalEffective(serverId: ServerId?, key: String, serverEffective: Boolean): Boolean
}

class SharedServerFeatureFlags(
    private val api: RaftFeatureFlagsApi,
    private val keys: List<String> = REGISTERED_SERVER_FEATURE_FLAG_KEYS,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    private val diagnosticOverride: ServerFeatureFlagDiagnosticOverride? = null,
    /**
     * Durable last-known values (task #1133). Consulted by [isEnabled] only while this process has no
     * online evaluation for the server yet; every accepted evaluation overwrites it.
     */
    private val lastKnown: ServerFeatureFlagLastKnownStore? = null,
    private val clientDefaultOnKeys: Set<String> = SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS,
    // Kept last so existing trailing-lambda constructions keep binding here.
    private val onEvaluation: ((ServerId, String, Boolean, String?, String?, String?, Long) -> Unit)? = null,
) {
    private val stateLock = SynchronizedObject()
    private val nextRequestGenerationByServer = mutableMapOf<String, Long>()
    private val acceptedGenerationByServer = mutableMapOf<String, Long>()
    /**
     * Feature flags are read by more than one startup owner (the selected-server
     * collector and the Home admission host).  Keep the transport request
     * process-owned so a lifecycle cancellation of one caller cannot leave the
     * other caller with a second request or a permanently unresolved gate.
     */
    private val inFlightByServer = mutableMapOf<String, InFlightFeatureFlagRequest>()
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Written under [stateLock], read via isEnabled on the UI thread. @Volatile + copy-on-write lets reads
    // observe a complete immutable map without taking the writer lock; the lock prevents concurrent
    // per-server prefetches from losing one another's snapshot updates.
    @Volatile
    private var snapshots: Map<String, ServerFeatureFlagSnapshot> = emptyMap()

    private val snapshotRevisionState = MutableStateFlow(0L)

    /**
     * Bumped every time an evaluation is accepted into the per-server snapshots, and once when the first
     * evaluation attempt of a server without a snapshot settles as a failure (that ends
     * [isDecisionPending]). UI that must follow a flag live (web `useServerFeatureFlag` re-renders when the
     * snapshot lands) keys its [isEnabled] / [isDecisionPending] reads on this.
     */
    val snapshotRevision: StateFlow<Long> = snapshotRevisionState.asStateFlow()

    /** Servers whose evaluation attempt settled without a snapshot (failed / malformed). Under [stateLock]. */
    private val settledWithoutSnapshot = mutableSetOf<String>()

    /**
     * True while [isEnabled] for [key] on [serverId] is only the before-evaluation default: no online
     * evaluation in this process, no stored last-known value, and no evaluation attempt has settled yet.
     * A layout that must not paint one way and then switch (Activity's conversation list) waits on this;
     * a failed attempt ends the wait (the flag then reads its default, off), so the wait is bounded by
     * one evaluation request.
     */
    fun isDecisionPending(serverId: ServerId, key: String): Boolean {
        if (serverId.value.isBlank()) return false
        if (snapshots[serverId.value] != null) return false
        if (lastKnown?.read(serverId, key) != null) return false
        return synchronized(stateLock) { serverId.value !in settledWithoutSnapshot }
    }

    /**
     * True when the evaluation for [serverId] settled without a snapshot (failed / malformed) and no
     * stored last-known value decides [key] either: [isEnabled] reads the default (off), but the
     * flag's real value is unknown (web `useServerFeatureFlag` `state: "undetermined"`). A remembered
     * answer (the phone Home's Assistant row) must not learn "off" from it.
     */
    fun isDecisionUndetermined(serverId: ServerId, key: String): Boolean {
        if (serverId.value.isBlank()) return false
        if (snapshots[serverId.value] != null) return false
        if (lastKnown?.read(serverId, key) != null) return false
        return synchronized(stateLock) { serverId.value in settledWithoutSnapshot }
    }

    /**
     * The (environment, account) the last-known values are read for changed, e.g. the binding became
     * known after the first frames of a cold start.
     */
    fun onLastKnownBindingChanged() {
        // Readers keyed on snapshotRevision (the server setup gate) re-read isEnabled, which now
        // finds the stored value for the bound account.
        if (lastKnown != null) snapshotRevisionState.value += 1
    }

    private fun markAttemptSettledWithoutSnapshot(serverId: ServerId) {
        val changed =
            synchronized(stateLock) {
                snapshots[serverId.value] == null && settledWithoutSnapshot.add(serverId.value)
            }
        if (changed) snapshotRevisionState.value += 1
    }

    fun snapshotFor(serverId: ServerId?): ServerFeatureFlagSnapshot =
        serverId?.let { snapshots[it.value] } ?: ServerFeatureFlagSnapshot.Unresolved

    fun isEnabled(serverId: ServerId?, key: String): Boolean =
        applyDiagnosticOverride(serverId, key, serverEffective(serverId, key))

    /**
     * 1.30 web-aligned Activity in-place + per-row position gate (#proj-mobile:#163, Tenny cb559277):
     * ON by default across all servers via [MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY] (default-on
     * when unresolved or `missing_flag`), OFF when `mobile_activity_inplace_v1` explicitly evaluates
     * false (`flag_disabled` / `kill_switch` / rule / local diagnostic override).
     */
    @RaftCoreInternalApi fun isActivityInPlaceEnabled(serverId: ServerId): Boolean =
        isEnabled(serverId, MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY)

    /**
     * Workers v1 gate (`#wg-mobile-sync:#284`, botiverse/slock `WORKER_V1_FEATURE_FLAG_KEY`):
     * per server, default OFF (unresolved or missing evaluates false).
     */
    @RaftCoreInternalApi fun isWorkerV1Enabled(serverId: ServerId?): Boolean =
        isEnabled(serverId, WORKER_V1_FEATURE_FLAG_KEY)

    /**
     * DMs without threads Lab gate (`#wg-mobile-sync:#305`, botiverse/slock #16664
     * `DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY`): per server, default OFF (unresolved or missing
     * evaluates false).
     */
    fun isDmMainTimelineLabEnabled(serverId: ServerId?): Boolean =
        isEnabled(serverId, DM_MAIN_TIMELINE_LAB_FEATURE_FLAG_KEY)

    /**
     * People Activity rows full rule (`botiverse/slock` #17123, `PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY`):
     * per server, default OFF (unresolved or missing evaluates false).
     */
    @RaftCoreInternalApi fun isPeopleActivityRowsEnabled(serverId: ServerId?): Boolean =
        isEnabled(serverId, PEOPLE_ACTIVITY_ROWS_FEATURE_FLAG_KEY)

    /**
     * Resolves the account-wide Early access policy across [serverIds] (`#proj-mobile:e48030bc`):
     * visible iff any joined server enables [MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY];
     * defaultOn iff visible and any joined server enables [MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY].
     */
    fun earlyAccessPolicy(serverIds: Collection<String>): RaftEarlyAccessFlagPolicy {
        val ids = serverIds.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
        if (ids.isEmpty()) return RaftEarlyAccessFlagPolicy()
        val visible = ids.any { isEnabled(ServerId(it), MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY) }
        val defaultOn = visible && ids.any { isEnabled(ServerId(it), MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY) }
        return RaftEarlyAccessFlagPolicy(visible = visible, defaultOn = defaultOn)
    }

    /**
     * An online evaluation from this process always decides. Before the first one lands (offline cold
     * start, or the short window before evaluate returns) a stored last-known value decides instead.
     */
    private fun serverEffective(serverId: ServerId?, key: String): Boolean {
        val online = serverId?.let { snapshots[it.value] }
        if (online != null) return online.isEnabled(key)
        if (serverId == null) return key in clientDefaultOnKeys
        val stored = lastKnown?.read(serverId, key) ?: return key in clientDefaultOnKeys
        logLastKnownUse(serverId, key, stored)
        return stored
    }

    private val loggedLastKnownUses = mutableSetOf<String>()

    private fun logLastKnownUse(serverId: ServerId, key: String, value: Boolean) {
        val logger = diagnosticsLogger ?: return
        val first = synchronized(stateLock) { loggedLastKnownUses.add("${serverId.value}|$key|$value") }
        if (first) logger("feature_flags last_known_used server=${serverId.value} key=$key enabled=$value")
    }

    private fun applyDiagnosticOverride(serverId: ServerId?, key: String, serverEffective: Boolean): Boolean =
        diagnosticOverride?.finalEffective(serverId, key, serverEffective) ?: serverEffective

    /** Evaluate the registered flags for [serverId] and cache the snapshot. Returns the resolved snapshot. */
    suspend fun prefetch(
        serverId: ServerId,
        evaluationContext: String? = null,
        forceRefresh: Boolean = false,
    ): ServerFeatureFlagSnapshot {
        if (forceRefresh) {
            return prefetchUnshared(serverId, evaluationContext)
        }
        val selection = synchronized(stateLock) {
            val current = inFlightByServer[serverId.value]
            if (current != null && !current.result.isCompleted) {
                // A collector may know the environment while the admission
                // host does not. Preserve the richer context if the owner has
                // not started yet; the request itself is still one per server.
                if (current.evaluationContext == null && evaluationContext != null) {
                    current.evaluationContext = evaluationContext
                }
                current.waiters += 1
                FeatureFlagFlightSelection.Join(current)
            } else {
                val created =
                    InFlightFeatureFlagRequest(
                        result = CompletableDeferred(),
                        waiters = 1,
                        evaluationContext = evaluationContext,
                    )
                inFlightByServer[serverId.value] = created
                FeatureFlagFlightSelection.Owner(created)
            }
        }
        val flight = selection.flight

        if (selection is FeatureFlagFlightSelection.Owner) {
            // The transport owner is independent from any one Compose
            // lifecycle. A cancelled caller only relinquishes its waiter slot;
            // a surviving admission/collector caller can still receive the
            // same result.
            val ownerJob =
                requestScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val context = synchronized(stateLock) { flight.evaluationContext }
                        val snapshot =
                            prefetchUnshared(
                                serverId = serverId,
                                evaluationContext = context,
                                shouldPublish = {
                                    synchronized(stateLock) {
                                        flight.waiters > 0 && inFlightByServer[serverId.value] === flight
                                    }
                                },
                            )
                        flight.result.complete(snapshot)
                    } catch (cancellation: CancellationException) {
                        flight.result.cancel(cancellation)
                    } catch (error: Throwable) {
                        flight.result.completeExceptionally(error)
                    } finally {
                        synchronized(stateLock) {
                            if (inFlightByServer[serverId.value] === flight) {
                                inFlightByServer.remove(serverId.value)
                            }
                        }
                    }
                }
            synchronized(stateLock) {
                flight.ownerJob = ownerJob
                if (flight.cancelWhenStarted) {
                    ownerJob.cancel()
                } else {
                    ownerJob.start()
                }
            }
        } else {
            diagnosticsLogger?.invoke("feature_flags evaluate_join server=${serverId.value}")
        }

        return try {
            flight.result.await()
        } finally {
            synchronized(stateLock) {
                flight.waiters = (flight.waiters - 1).coerceAtLeast(0)
                if (
                    flight.waiters == 0 &&
                    !flight.result.isCompleted &&
                    inFlightByServer[serverId.value] === flight
                ) {
                    inFlightByServer.remove(serverId.value)
                    flight.ownerJob?.cancel() ?: run { flight.cancelWhenStarted = true }
                }
            }
        }
    }

    /** Performs one network evaluation. Callers normally enter through [prefetch]. */
    private suspend fun prefetchUnshared(
        serverId: ServerId,
        evaluationContext: String?,
        shouldPublish: () -> Boolean = { true },
    ): ServerFeatureFlagSnapshot {
        val globalRequestGeneration = featureFlagRequestGeneration.incrementAndGet()
        // Captured before the request: the result is stored as last-known only for the binding that asked.
        val lastKnownScope = lastKnown?.scopeFor(serverId)
        val requestGeneration = synchronized(stateLock) {
            (nextRequestGenerationByServer[serverId.value] ?: 0L).plus(1L).also { generation ->
                nextRequestGenerationByServer[serverId.value] = generation
            }
        }
        val response =
            try {
                api.evaluate(serverId, keys)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                markAttemptSettledWithoutSnapshot(serverId)
                throw error
            }
        currentCoroutineContext().ensureActive()
        val body = response.bodyText
        if (!response.isSuccessful || body == null) {
            diagnosticsLogger?.invoke("feature_flags evaluate failed server=${serverId.value} code=${response.code}")
            markAttemptSettledWithoutSnapshot(serverId)
            return snapshotFor(serverId)
        }
        val decoded = try {
            featureFlagsJson.decodeFromString<FeatureFlagEvaluateResponseBody>(body)
        } catch (_: SerializationException) {
            diagnosticsLogger?.invoke("feature_flags evaluate malformed body server=${serverId.value}")
            markAttemptSettledWithoutSnapshot(serverId)
            return snapshotFor(serverId)
        }
        val values = keys.associateWith { it in clientDefaultOnKeys }.toMutableMap()
        val variants = linkedMapOf<String, String>()
        val reasons = linkedMapOf<String, String>()
        val observedEvaluations = linkedMapOf<String, FeatureFlagEvaluation>()
        for (evaluation in decoded.evaluations) {
            if (values.containsKey(evaluation.key)) {
                values[evaluation.key] =
                    if (evaluation.key in clientDefaultOnKeys && evaluation.reason == FEATURE_FLAG_REASON_MISSING_FLAG) {
                        true
                    } else {
                        evaluation.enabled
                    }
                evaluation.variant
                    ?.trim()
                    ?.takeIf { evaluation.enabled && it.isNotEmpty() }
                    ?.let { variants[evaluation.key] = it }
                evaluation.reason?.let { reasons[evaluation.key] = it }
                observedEvaluations[evaluation.key] = evaluation
            }
        }
        val snapshot =
            ServerFeatureFlagSnapshot(
                serverId = serverId,
                resolved = true,
                values = values.toMap(),
                variants = variants.toMap(),
                reasons = reasons.toMap(),
            )
        val accepted = synchronized(stateLock) {
            if (!shouldPublish()) return@synchronized null
            val latestAcceptedGeneration = acceptedGenerationByServer[serverId.value] ?: 0L
            if (requestGeneration < latestAcceptedGeneration) {
                false
            } else {
                acceptedGenerationByServer[serverId.value] = requestGeneration
                snapshots = snapshots + (serverId.value to snapshot)
                snapshotRevisionState.value += 1
                true
            }
        }
        // The snapshot uses a per-server latest-success fence. The optional observer may own a broader
        // global fact (Android's environment-wide transport kill), so it receives every decoded success
        // with the global request generation and performs its own ordering/active-selection checks.
        if (accepted == null) return snapshotFor(serverId)
        if (accepted && lastKnown != null) {
            val stored = lastKnownScope?.let { scope -> lastKnown.writeIfCurrent(scope, snapshot.values) } == true
            val persistedKeys = snapshot.values.keys.filter { it in lastKnown.keys }
            if (persistedKeys.isNotEmpty()) {
                diagnosticsLogger?.invoke(
                    "feature_flags last_known_${if (stored) "stored" else "skipped"} server=${serverId.value} " +
                        persistedKeys.joinToString(" ") { "$it=${snapshot.values[it] == true}" }
                )
            }
        }
        for (evaluation in observedEvaluations.values) {
            runCatching {
                onEvaluation?.invoke(
                    serverId,
                    evaluation.key,
                    evaluation.enabled,
                    evaluation.reason,
                    evaluation.variant,
                    evaluationContext,
                    globalRequestGeneration
                )
            }.onFailure { error ->
                diagnosticsLogger?.invoke(
                    "feature_flags evaluation_observer_failed server=${serverId.value} " +
                        "key=${evaluation.key} reason=${error::class.simpleName ?: "unknown"}"
                )
            }
        }
        if (!accepted) {
            diagnosticsLogger?.invoke(
                "feature_flags evaluate stale_ignored server=${serverId.value} generation=$requestGeneration"
            )
            return snapshotFor(serverId)
        }
        return snapshot
    }

    private class InFlightFeatureFlagRequest(
        val result: CompletableDeferred<ServerFeatureFlagSnapshot>,
        var waiters: Int,
        var evaluationContext: String?,
        var ownerJob: Job? = null,
        var cancelWhenStarted: Boolean = false,
    )

    private sealed interface FeatureFlagFlightSelection {
        val flight: InFlightFeatureFlagRequest

        data class Owner(override val flight: InFlightFeatureFlagRequest) : FeatureFlagFlightSelection

        data class Join(override val flight: InFlightFeatureFlagRequest) : FeatureFlagFlightSelection
    }
}
