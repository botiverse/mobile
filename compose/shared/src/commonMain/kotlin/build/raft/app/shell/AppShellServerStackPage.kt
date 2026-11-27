package build.raft.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import build.raft.app.RaftCenteredLoading
import build.raft.app.RaftTimestampDisplayScope
import build.raft.app.RaftUnifiedDialogHost
import build.raft.app.RaftUnifiedDialogHostProvider
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.app.data.RaftProductPageView
import build.raft.app.optString
import build.raft.app.raftPlatformSupportsAppShellServerStackPage
import build.raft.app.rememberRaftUnifiedDialogHostState
import build.raft.app.settings.AppShellChildTabEntry
import build.raft.app.settings.AppShellChildTabRegistry
import build.raft.app.settings.AppShellChildTabRenderer

/**
 * The phone server panel's Members and Settings as pages on the navigation stack (Android's
 * Navigation 3 stack via [LocalRaftStackShellTabs], and iOS's `UINavigationController` via
 * [AppShellChildTabRegistry]): artin 2026-11-02 / 2026-11-24, they slide in and support
 * interactive swipe-back like every other page instead of swapping the shell's tab in place.
 * The page key carries the shell tab key ([RAFT_APP_SHELL_SERVER_PAGE_TAB]: `members` or `more`),
 * plus [RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID] on hosts without [LocalRaftStackShellTabs]; the page
 * draws the shell's own live content for that tab, with Back closing the page
 * ([LocalAppShellServerStackPageClose]) and its own dialog host over it.
 *
 * On the wide rail (or Android with the stack switch off) the panel keeps selecting the shell tab.
 */
internal const val RAFT_APP_SHELL_SERVER_PAGE = "compose_app_shell_server_page"

/** pageData key of [RAFT_APP_SHELL_SERVER_PAGE]: the shell tab key it shows. */
internal const val RAFT_APP_SHELL_SERVER_PAGE_TAB = "tab"

/**
 * Optional pageData key of [RAFT_APP_SHELL_SERVER_PAGE]: the backing shell's
 * [AppShellChildTabRegistry] id on multi-controller hosts (iOS) that do not share
 * [LocalRaftStackShellTabs] across pushed view controllers.
 */
internal const val RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID = "shellId"

/** The shell tab a [RAFT_APP_SHELL_SERVER_PAGE] key names, or null when it names none of the server pages. */
internal fun appShellServerStackPageTab(raw: String?): String? = raw?.trim()?.takeIf { it in AppShellMoreChildTabKeys }

/**
 * Whether the server panel opens Members / Settings as a pushed page on the stack: on a shell that
 * is a stack root ([stackRoot], [LocalRaftStackShellTabs] provided) or on a host whose native
 * navigation stack supports it ([platformSupportsServerStackPage], iOS), and only on the phone
 * layout (the wide rail lists them as rail roots).
 */
internal fun appShellServerPagePushes(
    stackRoot: Boolean,
    navRailActive: Boolean,
    platformSupportsServerStackPage: Boolean = raftPlatformSupportsAppShellServerStackPage(),
): Boolean = (stackRoot || platformSupportsServerStackPage) && !navRailActive

internal fun appShellServerStackPageData(tab: String, shellId: String? = null): JSONObject =
    JSONObject().apply {
        put(RAFT_APP_SHELL_SERVER_PAGE_TAB, tab)
        shellId?.trim()?.takeIf { it.isNotEmpty() }?.let { put(RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID, it) }
    }

internal fun Pager.openAppShellServerStackPage(tab: String, shellId: String? = null) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        RAFT_APP_SHELL_SERVER_PAGE,
        appShellServerStackPageData(tab, shellId),
    )
}

internal fun resolveAppShellServerStackContent(
    shellTabs: RaftStackShellTabs?,
    shellId: String?,
    @Suppress("UNUSED_PARAMETER") registryRevision: Long = 0L,
): Pair<AppShellChildTabEntry, AppShellChildTabRenderer>? {
    if (shellTabs != null) {
        val stackEntry = shellTabs.entry
        val stackRenderer = shellTabs.renderer
        return if (stackEntry != null && stackRenderer != null) stackEntry to stackRenderer else null
    }
    val normalizedShellId = shellId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val entry = AppShellChildTabRegistry.entry(normalizedShellId) ?: return null
    return entry to entry.serverStackRenderer
}

/**
 * The window's app shell tabs for the pages over it: a host that draws the shell as the root of a
 * page stack provides one ([LocalRaftStackShellTabs]) around the whole stack; the shell publishes
 * its child tab entry in it. Null (no provider) means the host does not share a single Compose
 * tree across pushed pages (iOS falls back to [AppShellChildTabRegistry] via `shellId`).
 */
class RaftStackShellTabs {
    /** The shell's child tab entry (timestamp display state), null until the shell has composed its tabs. */
    internal var entry: AppShellChildTabEntry? by mutableStateOf(null)

    /** The shell's current tab renderer (the shell's live state), updated as the shell recomposes. */
    internal var renderer: AppShellChildTabRenderer? by mutableStateOf(null)
}

val LocalRaftStackShellTabs = staticCompositionLocalOf<RaftStackShellTabs?> { null }

/** Set by [RAFT_APP_SHELL_SERVER_PAGE] around the shell's tab content: its Back closes the page. */
internal val LocalAppShellServerStackPageClose = compositionLocalOf<(() -> Unit)?> { null }

@Page(RAFT_APP_SHELL_SERVER_PAGE, supportInLocal = true)
internal class ComposeAppShellServerStackPage : build.raft.app.base.BasePager() {
    override fun productPageView(): RaftProductPageView? =
        appShellServerStackPageTab(pageData.params.optString(RAFT_APP_SHELL_SERVER_PAGE_TAB))?.let(::appShellTabProductPageView)

    override fun willInit() {
        super.willInit()
        setContent { AppShellServerStackPageContent() }
    }
}

@Composable
private fun AppShellServerStackPageContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val tab = appShellServerStackPageTab(pager.pageData.params.optString(RAFT_APP_SHELL_SERVER_PAGE_TAB))
    val shellId = pager.pageData.params.optString(RAFT_APP_SHELL_SERVER_PAGE_SHELL_ID)
    val shellTabs = LocalRaftStackShellTabs.current
    val registryRevision =
        if (shellTabs == null) AppShellChildTabRegistry.revision.collectAsState().value else 0L
    val resolved = resolveAppShellServerStackContent(shellTabs = shellTabs, shellId = shellId, registryRevision = registryRevision)
    val entry = resolved?.first
    val renderer = resolved?.second
    LaunchedEffect(tab, entry?.debugId) {
        bridge.log("app_shell_server_stack_page tab=${tab.orEmpty()} entry=${entry?.debugId ?: 0}")
    }
    val close = remember(pager) { { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
    if (tab == null) {
        // Not a server page key: nothing to show, leave.
        LaunchedEffect(Unit) { close() }
        return
    }
    if (entry == null || renderer == null) {
        if (shellTabs != null) {
            // The shell under the stack has not published its tabs yet (a restored stack): wait for it.
            RaftCenteredLoading()
        } else {
            // Backing shell is gone on a multi-controller host (e.g. iOS): leave instead of spinning forever.
            LaunchedEffect(Unit) { close() }
        }
        return
    }
    val modalHostState = rememberRaftUnifiedDialogHostState()
    val active = LocalRaftPageActive.current
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalAppShellServerStackPageClose provides close) {
            RaftTimestampDisplayScope(entry.timestampDisplayState) {
                RaftUnifiedDialogHostProvider(modalHostState) {
                    renderer(tab, active)
                }
            }
        }
        RaftUnifiedDialogHost(state = modalHostState, modifier = Modifier.fillMaxSize())
    }
}
