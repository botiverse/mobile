package build.raft.app.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftMobileStackPages
import build.raft.app.nav.RaftNav3StackFlag
import build.raft.app.nav.RaftPageKey
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.RaftPageStackSavedState
import build.raft.app.nav.RaftStackOpenRoute
import build.raft.app.nav.RaftStackPage
import build.raft.app.nav.RaftStackPageAllowlist
import build.raft.app.nav.raftAppShellPageDataWithTab
import build.raft.app.nav.raftStackOpenRoute
import build.raft.designsystem.components.RaftSystemBarsAppearance
import kotlinx.serialization.json.JsonObject

/*
 * Navigation 3 on Android, plan step 1: the app shell Activity draws the shared page stack
 * (RaftMobilePageStackDisplay) with the shell as its root page, behind mobile_nav3_stack_v1 (default
 * off, read once per process, RaftNav3StackFlag). Only RaftMobileStackPages open on it; every other
 * page still opens an Activity, so the two mix: an Activity opened from a stack page covers the
 * whole shell window, Back finishes it and the stack is where it was.
 *
 * - Back: the top page's own back handlers, then the stack pops its top page, then (shell alone)
 *   today's shell behaviour. Root close rule: Back or closePage on the shell root does what it does
 *   today, the shell task goes to the background (RaftHostCloseAction.MoveTaskToBack); the stack
 *   never closes its root.
 * - New Intent / deep link / notification: as today. The shell recreates, its stack is not saved
 *   (pages over the shell are dropped, as singleTask cleared the Activities above it), and the
 *   window navigation coordinator routes the intent.
 * - Process death and recreation: the page keys (identity only) are saved in onSaveInstanceState and
 *   put back over the shell, with the shell tab under them (raftAndroidNav3ShellArgs); anything
 *   unusable (over the size guard, a page no longer on the allowlist, a key with content) brings the
 *   window back on the shell alone.
 */

/** The stack is drawn by the app shell window only, and only with the switch on for this process. */
internal fun raftAndroidNav3StackEnabled(pageName: String, store: () -> SharedPreferenceStore): Boolean =
    pageName == RaftPages.APP_SHELL && RaftNav3StackFlag.launchDecision(store)

/**
 * Where an open from a page of the shell window goes: [stack] null (switch off, or not the shell)
 * keeps every open on the Activity path, as before the stack.
 */
internal fun raftAndroidNav3OpenRoute(
    stack: RaftPageStack?,
    pageName: String,
    pageData: JsonObject,
    from: RaftStackPage?,
    allowlist: RaftStackPageAllowlist = RaftMobileStackPages,
): RaftStackOpenRoute =
    raftStackOpenRoute(
        stackEnabled = stack != null,
        allowlist = allowlist,
        pageName = pageName,
        pageData = pageData,
        fromRoot = from == null || from === stack?.pages?.firstOrNull(),
    )

/** The saved page keys to put back over the shell, or null (none, or not usable: shell alone). */
internal fun raftAndroidNav3RestoredKeys(
    text: String?,
    allowlist: RaftStackPageAllowlist = RaftMobileStackPages,
): List<RaftPageKey>? =
    RaftPageStackSavedState.decode(text) { key ->
        key.pageName != RaftPages.APP_SHELL && allowlist.allows(key.pageName, key.pageData)
    }

/** onSaveInstanceState key of the stack's page keys. */
internal const val RAFT_NAV3_STACK_STATE_KEY = "raftNav3StackKeys"

/** onSaveInstanceState key of the shell tab under the stack's pages ([RaftStackRootTab]). */
internal const val RAFT_NAV3_STACK_ROOT_TAB_KEY = "raftNav3StackRootTab"

/**
 * The shell's args after a recreation: on the tab it showed under the restored pages (the phone
 * Settings is a shell tab, so Settings > About comes back over Settings, not Home). With nothing
 * restored over the shell its args are unchanged (as today, it opens on its launch tab).
 */
internal fun raftAndroidNav3ShellArgs(args: RaftCmpPageArgs, stack: RaftPageStack?, savedRootTab: String?): RaftCmpPageArgs {
    if (stack == null || stack.pages.size <= 1 || savedRootTab == null) return args
    val pageData = raftAppShellPageDataWithTab(args.pageData, savedRootTab)
    return if (pageData === args.pageData) args else args.copy(pageData = pageData)
}

/**
 * A stack page's bridge wire: the Activity's, except `closePage`, which closes this page on the
 * stack instead of finishing the shell window.
 */
internal class RaftStackPageBridgeTransport(
    private val delegate: HostBridgeTransport,
    private val onClosePage: () -> Unit,
) : HostBridgeTransport {
    override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
        if (method == "closePage") {
            onClosePage()
            return
        }
        delegate.call(method, paramsJson, callback)
    }

    override fun callSync(method: String, paramsJson: String): String? = delegate.callSync(method, paramsJson)
}

/**
 * The shell window's content with the stack on: [stack] drawn by [RaftMobilePageStackDisplay], the
 * shell (root page) by [shell], every other page by [page]. Each page reports its system bar icons
 * and canvas colour; only the top page's reach the window ([onSystemBars] / [onCanvas]), and a page
 * coming back on top re-applies its last ones.
 */
@Composable
internal fun RaftAndroidNav3StackContent(
    stack: RaftPageStack,
    onSystemBars: (RaftSystemBarsAppearance) -> Unit,
    onCanvas: (Color) -> Unit,
    shell: @Composable (onBars: (RaftSystemBarsAppearance) -> Unit, onCanvas: (Color) -> Unit) -> Unit,
    page: @Composable (RaftStackPage, onBars: (RaftSystemBarsAppearance) -> Unit, onCanvas: (Color) -> Unit) -> Unit,
) {
    val bars = remember { HashMap<String, RaftSystemBarsAppearance>() }
    val canvases = remember { HashMap<String, Color>() }
    RaftMobilePageStackDisplay(stack) { stackPage ->
        val key = stackPage.contentKey
        val onTop = stack.pages.lastOrNull() === stackPage
        val reportBars =
            remember(stackPage) {
                { appearance: RaftSystemBarsAppearance ->
                    bars[key] = appearance
                    if (stack.pages.lastOrNull() === stackPage) onSystemBars(appearance)
                }
            }
        val reportCanvas =
            remember(stackPage) {
                { color: Color ->
                    canvases[key] = color
                    if (stack.pages.lastOrNull() === stackPage) onCanvas(color)
                }
            }
        LaunchedEffect(onTop) {
            if (onTop) {
                bars[key]?.let(onSystemBars)
                canvases[key]?.let(onCanvas)
            }
        }
        DisposableEffect(stackPage) {
            onDispose {
                bars.remove(key)
                canvases.remove(key)
            }
        }
        if (stackPage === stack.pages.firstOrNull()) shell(reportBars, reportCanvas) else page(stackPage, reportBars, reportCanvas)
    }
}
