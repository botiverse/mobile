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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * - Activity results (picker, camera, WebView file chooser) asked for by a stack page: one router,
 *   RaftStackActivityResults, keyed by the page's content key; the result goes to the page that asked
 *   while it is open, a closed page's call ends as cancelled, and after a recreation (the callbacks
 *   are gone) the result is dropped as an Activity page's is today.
 * - Window replacement (sign-out opens the login page in place of the shell): today's login
 *   replacement finishes the window; the stack is cleared with it and not saved.
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
 * stack instead of finishing the shell window, and the calls answered by an Activity result
 * ([RAFT_STACK_ACTIVITY_RESULT_CALLS]: picker, camera), whose answer goes through [results] under
 * this page's [pageKey]: to this page while it is open, a cancelled answer once it has closed.
 */
internal class RaftStackPageBridgeTransport(
    private val delegate: HostBridgeTransport,
    private val onClosePage: () -> Unit,
    private val pageKey: String? = null,
    private val results: RaftStackActivityResults? = null,
) : HostBridgeTransport {
    override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
        if (method == "closePage") {
            onClosePage()
            return
        }
        val cancelled = RAFT_STACK_ACTIVITY_RESULT_CALLS[method]
        if (cancelled != null && callback != null && pageKey != null && results != null) {
            val requestId = results.begin(pageKey, method)
            delegate.call(method, paramsJson) { answer ->
                callback(if (results.complete(requestId)) answer else cancelled)
            }
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

/**
 * Bridge calls a stack page makes that the shell Activity answers with an Activity result, and the
 * answer the page gets instead when it closed before the result came (the call's "cancelled").
 * `threadAttachmentUpload` is the photo / file picker and the camera (Account avatar, Feedback
 * attachments, member detail agent avatar).
 */
internal val RAFT_STACK_ACTIVITY_RESULT_CALLS: Map<String, String> =
    mapOf(RAFT_STACK_RESULT_ATTACHMENT_PICKER to """{"code":204,"bodyText":""}""")

/** [RaftStackActivityResults] kind of the picker / camera bridge call (its method name). */
internal const val RAFT_STACK_RESULT_ATTACHMENT_PICKER = "threadAttachmentUpload"

/** [RaftStackActivityResults] kind of a WebView `<input type=file>` chooser. */
internal const val RAFT_STACK_RESULT_WEB_FILE_CHOOSER = "webViewFileChooser"

/** onSaveInstanceState key of the pending Activity results ([RaftStackActivityResults.encode]). */
internal const val RAFT_NAV3_STACK_RESULTS_KEY = "raftNav3StackResults"

/**
 * The shell window's Activity results requested by stack pages, keyed by the asking page's content
 * key (`pageName#id`, the key saved with the stack), so a result reaches the page that asked:
 *
 * - [begin] when a page launches a picker / camera / WebView file chooser; [complete] when the
 *   result comes back: true while that page is still on the stack ([isOpen]), false (drop it, the
 *   caller answers "cancelled") once it has closed, or for an unknown request.
 * - Process death or recreation: the pending requests are saved with the stack keys ([encode] /
 *   [restore]); their callbacks died with the old instance, so the Activity drops a result that
 *   comes back to the new one (as it does for an Activity page today) and calls [dropRecreated],
 *   which ends the request whether or not its page was restored. A result never goes to another page.
 * - [clear] when the window is replaced (sign-out): no page is left to receive anything.
 */
internal class RaftStackActivityResults(
    private val isOpen: (pageKey: String) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private class Request(val id: String, val pageKey: String, val kind: String, val recreated: Boolean)

    private val requests = LinkedHashMap<String, Request>()
    private var serial = 0L

    /** Requests waiting for their result (saved ones included). */
    val pendingCount: Int
        get() = requests.size

    /** A new request of [kind] by the page [pageKey]; its id goes to [complete]. */
    fun begin(pageKey: String, kind: String): String {
        val id = "r${serial++}"
        requests[id] = Request(id, pageKey, kind, recreated = false)
        log("nav3_result_begin id=$id kind=$kind page=$pageKey")
        return id
    }

    /** The result of [requestId] came back: true to hand it to its page, false to drop it. */
    fun complete(requestId: String): Boolean {
        val request = requests.remove(requestId) ?: return false.also { log("nav3_result_drop id=$requestId reason=unknown") }
        val open = isOpen(request.pageKey)
        log("nav3_result_${if (open) "deliver" else "drop"} id=$requestId kind=${request.kind} page=${request.pageKey}" + if (open) "" else " reason=page_closed")
        return open
    }

    /**
     * A result of [kind] came back to a recreated window, where nothing waits for it: ends the
     * oldest saved request of that kind and returns its page key (null when there was none).
     */
    fun dropRecreated(kind: String): String? {
        val request = requests.values.firstOrNull { it.recreated && it.kind == kind } ?: return null
        requests.remove(request.id)
        log("nav3_result_drop id=${request.id} kind=$kind page=${request.pageKey} reason=recreated pageRestored=${isOpen(request.pageKey)}")
        return request.pageKey
    }

    /** Ends every request (the window is being replaced). */
    fun clear() {
        if (requests.isNotEmpty()) log("nav3_result_clear count=${requests.size}")
        requests.clear()
    }

    /** The pending requests as saved text, or null with none. */
    fun encode(): String? {
        if (requests.isEmpty()) return null
        return JsonArray(
            requests.values.map { request ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(request.id),
                        "page" to JsonPrimitive(request.pageKey),
                        "kind" to JsonPrimitive(request.kind),
                    ),
                )
            },
        ).toString()
    }

    /** Puts back the requests saved by [encode] (unreadable text: none), as recreated requests. */
    fun restore(text: String?) {
        if (text.isNullOrBlank()) return
        val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
        array.take(RAFT_STACK_RESULTS_SAVED_MAX).forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            fun field(name: String) = (item[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
            val id = field("id") ?: return@forEach
            val pageKey = field("page") ?: return@forEach
            val kind = field("kind") ?: return@forEach
            requests[id] = Request(id, pageKey, kind, recreated = true)
            id.removePrefix("r").toLongOrNull()?.let { serial = maxOf(serial, it + 1) }
        }
        if (requests.isNotEmpty()) log("nav3_result_restore count=${requests.size}")
    }
}

/** Most saved pending results (one picker is on screen at a time; the rest is noise). */
private const val RAFT_STACK_RESULTS_SAVED_MAX = 8

/**
 * The window is being replaced (sign-out, account deletion's sign-out, an API environment switch
 * open the login page in place of the shell window): every page over the shell closes and no
 * result is waited for. The Activity itself is finished by today's login replacement.
 */
internal fun raftAndroidNav3ClearForWindowReplace(stack: RaftPageStack, results: RaftStackActivityResults?) {
    stack.pages.drop(1).asReversed().forEach(stack::close)
    results?.clear()
}
