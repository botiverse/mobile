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
                RaftStackPageRule(pageName = build.raft.app.SETTINGS_STORAGE_PAGE),
                RaftStackPageRule(pageName = build.raft.app.RELEASE_NOTES_PAGE),
                RaftStackPageRule(pageName = build.raft.app.SETTINGS_COMPUTERS_PAGE),
            ) +
            RaftStackSettingsWebSurfaces.map { surface ->
                RaftStackPageRule(
                    pageName = build.raft.app.SETTINGS_WEB_VIEW_PAGE,
                    field = build.raft.app.SETTINGS_WEB_VIEW_SURFACE,
                    value = surface.diagnosticsName,
                )
            }
    )
