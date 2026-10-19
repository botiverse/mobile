package build.raft.app.cmp.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import build.raft.app.cmp.SlockCmpRoot
import build.raft.app.cmp.SlockCmpRouter
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftHostEvents
import build.raft.app.cmp.host.SlockCmpHost
import build.raft.app.cmp.host.SlockCmpNavigator
import build.raft.app.cmp.host.SlockCmpPageArgs
import build.raft.app.parseSlockJsonObjectOrNull
import build.raft.app.toSlockJsonString
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSThread
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Implemented by the iOS app (Objective-C, `SlockCmpBridgeHost`): forwards one bridge call to the
 * page's `HRBridgeModule`. [paramsJson] is the method's param object; the host hands the result
 * payload (JSON text, or nil) to [callback], possibly from a background thread and, for streaming
 * methods, more than once.
 */
interface SlockCmpIosBridge {
    fun call(method: String, paramsJson: String, callback: SlockCmpIosBridgeCallback?)

    /**
     * Synchronous bridge call (Kuikly `syncCall = true`): the method's return value as text, or
     * null when the host has no synchronous path for [method]. Answered on the calling thread, so
     * the host only serves cheap, thread-safe methods here (urlEncode, urlDecode, currentTimestamp,
     * dateFormatter).
     */
    fun callSync(method: String, paramsJson: String): String?
}

/** Result sink for [SlockCmpIosBridge.call]. Implemented in Kotlin; the host only invokes it. */
interface SlockCmpIosBridgeCallback {
    fun deliver(json: String?)
}

/**
 * Implemented by the iOS app (Swift, `SlockCmpPageNavigator`): page navigation through the app's
 * `UINavigationController`. [openPage] goes through the app's page router (`RaftPageRouter`).
 */
interface SlockCmpIosNavigator {
    fun openPage(pageName: String, pageDataJson: String)

    fun closePage()
}

/** Implemented by the iOS page container (`RaftPageViewController`): page -> host notifications. */
interface RaftIosPageListener {
    /** The page's resolved appearance after each change (status bar style). */
    fun onDarkThemeResolved(dark: Boolean)
}

/**
 * One CMP page created for the iOS host: the Compose view controller the host embeds, plus the
 * host -> page event channel (the Kuikly `sendWithEvent` counterpart: adaptive panes, window
 * insets, theme changes, adaptive navigation receipts, conversation forward requests).
 *
 * The host may publish window facts before the page has composed (and subscribed). The latest
 * [stickyEvents] are therefore replayed once the page's first composition is applied; they carry
 * replace-semantics snapshots, so a duplicate is harmless.
 */
class RaftIosPage internal constructor(
    private val events: RaftHostEvents,
    content: (RaftIosPage) -> UIViewController,
) {
    private val sticky = LinkedHashMap<String, JsonObject>()

    val viewController: UIViewController = content(this)

    /**
     * Delivers [event] with [dataJson] (a JSON object as text; blank or malformed -> `{}`) to the
     * page as a pager event. Safe from any thread: delivery always happens on the main thread.
     */
    fun sendEvent(event: String, dataJson: String) {
        val data = parseSlockJsonObjectOrNull(dataJson.takeIf { it.isNotBlank() }) ?: JsonObject(emptyMap())
        if (NSThread.isMainThread) {
            deliver(event, data)
        } else {
            dispatch_async(dispatch_get_main_queue()) { deliver(event, data) }
        }
    }

    private fun deliver(event: String, data: JsonObject) {
        if (event in stickyEvents) sticky[event] = data
        events.send(event, data)
    }

    /** Main thread: re-sends the latest sticky snapshots (after the first composition). */
    internal fun replayStickyEvents() {
        sticky.entries.toList().forEach { (event, data) -> events.send(event, data) }
    }

    private companion object {
        val stickyEvents = setOf("slockAdaptivePaneChanged", "slockWindowInsetsChanged")
    }
}

/** iOS entry points of the CMP layer, exported through the `shared` framework. */
object SlockCmpIos {
    /** Call once at app start, before any routing decision. */
    fun configure(debugBuild: Boolean) {
        SlockCmpRouter.configure(debugBuild = debugBuild)
    }

    /** True when [pageName] is a registered page (anything else renders the unknown-page surface). */
    fun rendersOnCmp(pageName: String): Boolean = SlockCmpRouter.rendersOnCmp(pageName)

    /**
     * A page rendering [pageName] with [pageDataJson] (the page's pageData as JSON text). The host
     * embeds [RaftIosPage.viewController] in its page container and pushes the container.
     */
    fun makePage(
        pageName: String,
        pageDataJson: String,
        bridge: SlockCmpIosBridge,
        navigator: SlockCmpIosNavigator,
        listener: RaftIosPageListener?,
    ): RaftIosPage {
        val transport = IosBridgeTransport(bridge)
        val events = RaftHostEvents()
        val host =
            SlockCmpHost(
                capabilities = BridgeWireHostCapabilities(transport),
                navigator = IosNavigator(navigator),
                bridgeTransport = transport,
                events = events,
            )
        val args = SlockCmpPageArgs.fromJsonText(pageName, pageDataJson)
        val onDarkThemeResolved: ((Boolean) -> Unit)? = listener?.let { target -> { dark -> target.onDarkThemeResolved(dark) } }
        return RaftIosPage(events) { page ->
            ComposeUIViewController {
                SlockCmpRoot(args, host, onDarkThemeResolved)
                // Runs after the first composition is applied, i.e. after RaftPagerHost subscribed.
                LaunchedEffect(Unit) { page.replayStickyEvents() }
            }
        }
    }
}

private class IosBridgeTransport(private val bridge: SlockCmpIosBridge) : HostBridgeTransport {
    override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
        bridge.call(method, paramsJson, callback?.let(::BridgeCallback))
    }

    override fun callSync(method: String, paramsJson: String): String? = bridge.callSync(method, paramsJson)
}

private class BridgeCallback(private val onResult: (String?) -> Unit) : SlockCmpIosBridgeCallback {
    override fun deliver(json: String?) = onResult(json)
}

private class IosNavigator(private val navigator: SlockCmpIosNavigator) : SlockCmpNavigator {
    override fun openPage(pageName: String, pageData: JsonObject) =
        navigator.openPage(pageName, pageData.toSlockJsonString())

    override fun closePage() = navigator.closePage()
}
