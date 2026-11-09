package build.raft.app.nav

import build.raft.app.RAFT_APP_SHELL_PAGE
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One kind of page a mobile host opens on its window's [RaftPageStack] instead of in a platform page
 * of its own (an Android Activity): [pageName], and when set, the pageData string [field] equal to
 * [value] (the settings detail pages share one page name, `settingsPage` tells them apart).
 */
data class RaftStackPageRule(
    val pageName: String,
    val field: String? = null,
    val value: String? = null,
) {
    fun matches(pageName: String, pageData: JsonObject): Boolean =
        pageName == this.pageName &&
            (field == null || (pageData[field] as? JsonPrimitive)?.contentOrNull?.trim() == value)
}

/** The pages moved onto the stack so far (Navigation 3 plan, step 2 adds them in batches). */
class RaftStackPageAllowlist(val rules: List<RaftStackPageRule>) {
    fun allows(pageName: String, pageData: JsonObject): Boolean = rules.any { it.matches(pageName, pageData) }
}

/** Where a page a mobile host is asked to open goes. */
enum class RaftStackOpenRoute {
    /** Onto the window's page stack ([RaftPageStack.open]). */
    Stack,

    /** The host's own way, unchanged (Android: a new Activity, or the app shell / login paths). */
    Host,
}

/**
 * The open routing of a mobile host with the shared stack: with the stack off ([stackEnabled]
 * false) everything keeps the host's way. With it on only [allowlist] pages go onto the stack; the
 * app shell never does (it is the root, a shell launch keeps the host's launch path), and neither
 * does a `slockReplaceCurrentPage` open from the root page (that replaces the shell window itself).
 */
fun raftStackOpenRoute(
    stackEnabled: Boolean,
    allowlist: RaftStackPageAllowlist,
    pageName: String,
    pageData: JsonObject,
    fromRoot: Boolean,
): RaftStackOpenRoute {
    if (!stackEnabled || pageName == RAFT_APP_SHELL_PAGE) return RaftStackOpenRoute.Host
    val replace = (pageData[RAFT_REPLACE_CURRENT_PAGE] as? JsonPrimitive)?.booleanOrNull == true
    if (replace && fromRoot) return RaftStackOpenRoute.Host
    return if (allowlist.allows(pageName, pageData)) RaftStackOpenRoute.Stack else RaftStackOpenRoute.Host
}

internal const val RAFT_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"

/** Batch 3: the chat, its pages, the file pages and Search (any pageData). */
internal val RaftStackChatFilesBrowsePages: List<String> =
    listOf(
        build.raft.app.THREAD_PAGE,
        build.raft.app.THREAD_MEMBERS_PAGE,
        build.raft.app.CHANNEL_SETTINGS_PAGE,
        build.raft.app.THREAD_SETTINGS_PAGE,
        build.raft.app.JOINT_CHANNEL_CONNECTIONS_PAGE,
        build.raft.app.THREAD_FORWARD_BUNDLE_PAGE,
        build.raft.app.RAFT_FILE_PREVIEW_PAGE,
        build.raft.app.RAFT_ARTIFACT_VIEWER_PAGE,
        build.raft.app.HOME_SEARCH_PAGE,
    )

/** The shell tabs the phone server panel opens as stack pages (RAFT_APP_SHELL_SERVER_PAGE): Members, Settings. */
internal val RaftStackServerPanelTabs: List<String> = listOf("members", build.raft.app.AppShellServerTabKey)

/** The Settings web page surfaces on the stack (the others keep the host's way). */
internal val RaftStackSettingsWebSurfaces: List<build.raft.app.SettingsWebViewSurface> =
    listOf(
        build.raft.app.SettingsWebViewSurface.Billing,
        build.raft.app.SettingsWebViewSurface.Administration,
        build.raft.app.SettingsWebViewSurface.ImBridges,
        build.raft.app.SettingsWebViewSurface.Marketplace,
        build.raft.app.SettingsWebViewSurface.Feedback,
    )

/**
 * The pages a mobile host opens on its stack (Android with [RaftNav3StackFlag] on). Step 1 had one
 * trivial test page, Settings > About; step 2 adds the simple pages in batches.
 *
 * Batch 1: every Settings detail page (each `settingsPage` value), Saved, Drafts and member detail.
 * Their host calls (share, permission prompt, system settings) answer through the shell Activity's
 * bridge callback, and a page they open that is not listed here (a chat) still opens an Activity
 * over the shell window.
 *
 * Batch 2: the pages that need an Activity result or replace the window. Account (avatar picker;
 * sign-out replaces the window: the host clears the stack, then today's login replacement),
 * Feedback (attachment picker, camera), the Settings web pages (one rule per surface: Billing,
 * Administration, IM Bridges, Connected apps, web Feedback; WebView file chooser), Storage,
 * Release notes and Computers (list and one machine). The picker, camera and file chooser results
 * reach the page that asked through the host's result routing, keyed by the page's content key.
 *
 * Batch 3: the chat, files and browse pages. The conversation page (channel, DM, thread, task
 * conversation; its Chat / Tasks / Files tabs are the files list), what it opens over itself
 * (conversation members, channel settings, joint channel connections, a forwarded bundle), the
 * file page (preview or file detail of an attachment), the artifact viewer and Search. The
 * composer's picker and camera are `threadAttachmentUpload` (batch 2 routing); a notification,
 * share or deep link reaches the shell (new Intent) and the shell opens the chat on the stack.
 * Covered, the chat and the file pages keep the window size ([RaftStackCoveredKeepsSizePages]).
 * Still Activities: the in-app browser, message link pages, login / auth, onboarding web pages.
 *
 * Server panel pages (artin 2026-11-02): the phone server panel's Members and Settings
 * ([RaftStackServerPanelTabs], the shell's own tab content on a page of its own) slide in like
 * the rest; Computers was already a stack page (batch 2).
 */
val RaftMobileStackPages: RaftStackPageAllowlist =
    RaftStackPageAllowlist(
        build.raft.app.SettingsNativeDetailPage.entries.map { page ->
            RaftStackPageRule(
                pageName = build.raft.app.SETTINGS_NATIVE_DETAIL_PAGE,
                field = build.raft.app.SETTINGS_NATIVE_DETAIL_PAGE_KEY,
                value = page.key,
            )
        } +
            listOf(
                RaftStackPageRule(pageName = build.raft.app.HOME_SAVED_PAGE),
                RaftStackPageRule(pageName = build.raft.app.HOME_DRAFTS_PAGE),
                RaftStackPageRule(pageName = build.raft.app.MEMBER_DETAIL_PAGE),
                // Batch 2.
                RaftStackPageRule(pageName = build.raft.app.SETTINGS_ACCOUNT_PAGE),
                RaftStackPageRule(pageName = build.raft.app.SETTINGS_HANDS_FEEDBACK_PAGE),
                // The native ticket list (1.23); it opens the submit form above over itself.
                RaftStackPageRule(pageName = build.raft.app.SETTINGS_FEEDBACK_TICKETS_PAGE),
                RaftStackPageRule(pageName = build.raft.app.SETTINGS_STORAGE_PAGE),
                RaftStackPageRule(pageName = build.raft.app.RELEASE_NOTES_PAGE),
                RaftStackPageRule(pageName = build.raft.app.SETTINGS_COMPUTERS_PAGE),
                // The Artifacts home, from the server panel (task #proj-mobile:#62).
                RaftStackPageRule(pageName = build.raft.app.ARTIFACTS_HOME_PAGE),
            ) +
            RaftStackChatFilesBrowsePages.map { RaftStackPageRule(pageName = it) } +
            RaftStackServerPanelTabs.map { tab ->
                RaftStackPageRule(
                    pageName = build.raft.app.RAFT_APP_SHELL_SERVER_PAGE,
                    field = build.raft.app.RAFT_APP_SHELL_SERVER_PAGE_TAB,
                    value = tab,
                )
            } +
            RaftStackSettingsWebSurfaces.map { surface ->
                RaftStackPageRule(
                    pageName = build.raft.app.SETTINGS_WEB_VIEW_PAGE,
                    field = build.raft.app.SETTINGS_WEB_VIEW_SURFACE,
                    value = surface.diagnosticsName,
                )
            }
    )

/**
 * Pages that keep the window size while covered on the mobile stack (measured, not placed) rather
 * than 0x0: the chat (list viewport intents, scroll restore, read position) and the file pages
 * (image pager page, PDF / text scroll), whose viewports must not collapse and regrow.
 */
internal val RaftStackCoveredKeepsSizePages: Set<String> =
    setOf(
        build.raft.app.THREAD_PAGE,
        build.raft.app.RAFT_FILE_PREVIEW_PAGE,
        build.raft.app.RAFT_ARTIFACT_VIEWER_PAGE,
    )
