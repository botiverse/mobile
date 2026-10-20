package build.raft.app.cmp.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import build.raft.app.cmp.RaftCmpRoot
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftHostEvents
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.parseRaftJsonObjectOrNull
import build.raft.app.toRaftJsonString
import build.raft.designsystem.gesture.RaftBackSwipeRegions
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSThread
import platform.UIKit.UIColor
import platform.UIKit.UIViewController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Implemented by the iOS app (Objective-C, `RaftCmpBridgeHost`): forwards one bridge call to the
 * page's `HRBridgeModule`. [paramsJson] is the method's param object; the host hands the result
 * payload (JSON text, or nil) to [resultSink], possibly from a background thread and, for streaming
 * methods, more than once.
 */
interface RaftCmpIosBridge {
    /**
     * Exported to Objective-C as `callMethod:paramsJson:resultSink:`. The parameter name must stay
     * distinct from `HostBridgeTransport.call`'s `callback`: two exported methods with the same
     * selector but different signatures make Kotlin/Native mangle one of them (it used to export
     * `callMethod:paramsJson:callback_:`, which the ObjC host then had to guess). `@ObjCName` pins
     * the selector so a parameter rename cannot silently change it; iosAppTests'
     * RaftCmpBridgeSelectorTests checks the host still answers every selector of the protocol.
     */
    @OptIn(ExperimentalObjCName::class)
    fun call(
        method: String,
        paramsJson: String,
        @ObjCName("resultSink") resultSink: RaftCmpIosBridgeCallback?,
    )

    /**
     * Synchronous bridge call (Kuikly `syncCall = true`): the method's return value as text, or
     * null when the host has no synchronous path for [method]. Answered on the calling thread, so
     * the host only serves cheap, thread-safe methods here (urlEncode, urlDecode, currentTimestamp,
     * dateFormatter).
     */
    fun callSync(method: String, paramsJson: String): String?
}

/** Result sink for [RaftCmpIosBridge.call]. Implemented in Kotlin; the host only invokes it. */
interface RaftCmpIosBridgeCallback {
    fun deliver(json: String?)
}

/**
 * Implemented by the iOS app (Swift, `RaftCmpPageNavigator`): page navigation through the app's
 * `UINavigationController`. [openPage] goes through the app's page router (`RaftPageRouter`).
 */
interface RaftCmpIosNavigator {
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
    private val backSwipeRegions: RaftBackSwipeRegions,
    content: (RaftIosPage) -> UIViewController,
) {
    private val sticky = LinkedHashMap<String, JsonObject>()

    val viewController: UIViewController = content(this)

    /**
     * Main thread. True when a back-direction drag starting at ([x], [y]) (points in
     * [viewController]'s view) would be consumed by the page's horizontal content (a tab pager past
     * its first page, a scroller away from its start): the host's full-page interactive pop must
     * then not begin. Content registers through `rememberRaftBackSwipeScroll` /
     * `rememberRaftBackSwipeRegion`.
     */
    fun contentConsumesBackSwipe(x: Double, y: Double): Boolean =
        backSwipeRegions.contentConsumesBackSwipeAtPoint(x, y)

    /**
     * Delivers [event] with [dataJson] (a JSON object as text; blank or malformed -> `{}`) to the
     * page as a pager event. Safe from any thread: delivery always happens on the main thread.
     */
    fun sendEvent(event: String, dataJson: String) {
        val data = parseRaftJsonObjectOrNull(dataJson.takeIf { it.isNotBlank() }) ?: JsonObject(emptyMap())
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
object RaftCmpIos {
    /** Call once at app start, before any routing decision. */
    fun configure(debugBuild: Boolean) {
        RaftCmpRouter.configure(debugBuild = debugBuild)
    }

    /**
     * The canvas colour the last composed page painted (light/dark aware), or null before any.
     * `RaftPageViewController` starts with it so a pushed page's container does not flash white
     * (or the system colour) under a dark (or light) app theme before its first composition.
     */
    fun lastCanvasColor(): UIColor? = raftLastCanvasColor

    /** True when [pageName] is a registered page (anything else renders the unknown-page surface). */
    fun rendersOnCmp(pageName: String): Boolean = RaftCmpRouter.rendersOnCmp(pageName)

    /**
     * A page rendering [pageName] with [pageDataJson] (the page's pageData as JSON text). The host
     * embeds [RaftIosPage.viewController] in its page container and pushes the container.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun makePage(
        pageName: String,
        pageDataJson: String,
        bridge: RaftCmpIosBridge,
        navigator: RaftCmpIosNavigator,
        listener: RaftIosPageListener?,
    ): RaftIosPage {
        val transport = IosBridgeTransport(bridge)
        val events = RaftHostEvents()
        val host =
            RaftCmpHost(
                capabilities = BridgeWireHostCapabilities(transport),
                navigator = IosNavigator(navigator),
                bridgeTransport = transport,
                events = events,
            )
        val args = RaftCmpPageArgs.fromJsonText(pageName, pageDataJson)
        val onDarkThemeResolved: ((Boolean) -> Unit)? = listener?.let { target -> { dark -> target.onDarkThemeResolved(dark) } }
        val backSwipeRegions = RaftBackSwipeRegions()
        return RaftIosPage(events, backSwipeRegions) { page ->
            ComposeUIViewController(
                configure = {
                    // Not opaque: an opaque CMP view clears its canvas, and fills its view, with
                    // black or white chosen from the SYSTEM trait (ComposeView/MetalView in CMP),
                    // not from the app theme. Every pixel Compose does not draw (the content a
                    // keyboard focus offset moves up, a frame before the first composition) then
                    // flashed black under a light app on a dark-mode phone. Transparent, it shows
                    // the canvas colour painted behind it (raftPaintHostCanvas).
                    opaque = false
                },
            ) {
                // Read from composition, not captured in this lambda: a Kotlin closure holding the
                // controller that holds this content would be an ObjC/Kotlin retain cycle.
                val controller = LocalUIViewController.current
                RaftCmpRoot(
                    args = args,
                    host = host,
                    onDarkThemeResolved = onDarkThemeResolved,
                    onCanvasColorResolved = { color -> controller.raftPaintHostCanvas(color) },
                    backSwipeRegions = backSwipeRegions,
                )
                // Runs after the first composition is applied, i.e. after RaftPagerHost subscribed.
                LaunchedEffect(Unit) { page.replayStickyEvents() }
            }
        }
    }
}

/** Main thread only (composition effects). */
private var raftLastCanvasColor: UIColor? = null

/**
 * Gives the Compose view and its host container (`RaftPageViewController`'s view, which starts
 * with the last canvas colour or `systemBackgroundColor`) the app's canvas colour, light/dark
 * aware. CMP resets the transparent Compose view's own background on trait changes; the container
 * keeps the colour then.
 */

private fun UIViewController.raftPaintHostCanvas(color: Color) {
    val uiColor =
        UIColor.colorWithRed(
            red = color.red.toDouble(),
            green = color.green.toDouble(),
            blue = color.blue.toDouble(),
            alpha = color.alpha.toDouble(),
        )
    raftLastCanvasColor = uiColor
    view.backgroundColor = uiColor
    view.superview?.backgroundColor = uiColor
}

private class IosBridgeTransport(private val bridge: RaftCmpIosBridge) : HostBridgeTransport {
    override fun call(method: String, paramsJson: String, callback: ((String?) -> Unit)?) {
        bridge.call(method, paramsJson, resultSink = callback?.let(::BridgeCallback))
    }

    override fun callSync(method: String, paramsJson: String): String? = bridge.callSync(method, paramsJson)
}

private class BridgeCallback(private val onResult: (String?) -> Unit) : RaftCmpIosBridgeCallback {
    override fun deliver(json: String?) = onResult(json)
}

private class IosNavigator(private val navigator: RaftCmpIosNavigator) : RaftCmpNavigator {
    override fun openPage(pageName: String, pageData: JsonObject) =
        navigator.openPage(pageName, pageData.toRaftJsonString())

    override fun closePage() = navigator.closePage()
}
