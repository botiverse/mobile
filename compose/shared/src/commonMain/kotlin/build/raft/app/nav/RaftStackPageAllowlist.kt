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

/**
 * The pages a mobile host opens on its stack (Android with [RaftNav3StackFlag] on). Step 1 had one
 * trivial test page, Settings > About; step 2 adds the simple pages in batches.
 *
 * Batch 1: every Settings detail page (each `settingsPage` value), Saved, Drafts and member detail.
 * None needs an Activity of its own: their host calls (share, permission prompt, system settings,
 * the agent avatar picker on member detail) answer through the shell Activity's bridge callback, and
 * a page they open that is not listed here (a chat, Administration, the Settings web pages) still
 * opens an Activity over the shell window. Kept as Activities for now: Account (avatar picker,
 * sign-out / delete replaces the window), Feedback (attachment picker, camera, image edit), the
 * Settings web pages (Billing, Administration, IM Bridges, Connected apps, web Feedback: the WebView
 * file chooser is an Activity result), Storage, Release notes and Computers (later batches).
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
            )
    )
