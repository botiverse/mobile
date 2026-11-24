package build.raft.app.cmp.ios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import build.raft.app.cmp.ProvideRaftTextToolbarGuard
import build.raft.app.cmp.RaftCmpRoot
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.cmp.RaftCmpToastWindow
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftHostEvents
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.cmp.host.RaftPageOpenTransition
import build.raft.app.cmp.host.RaftPageRouteIdentities
import build.raft.app.parseRaftJsonObjectOrNull
import build.raft.app.toRaftJsonString
import build.raft.designsystem.components.RaftPageCoverSignal
import build.raft.designsystem.components.RaftSystemBarsAppearance
import build.raft.designsystem.components.RaftToastHost
import build.raft.designsystem.components.RaftToastHostState
import build.raft.designsystem.gesture.RaftBackSwipeRegions
import build.raft.designsystem.theme.RaftTheme
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

/** Snapshot of the top page on iOS's `UINavigationController`, returned by [RaftCmpIosNavigator.topPage]. */
class RaftCmpIosTopPage(
    val pageName: String,
    val pageDataJson: String,
)

/**
 * Implemented by the iOS app (Swift, `RaftCmpPageNavigator`): page navigation through the app's
 * `UINavigationController`. [openPage] goes through the app's page router (`RaftPageRouter`).
 */
interface RaftCmpIosNavigator {
    fun openPage(pageName: String, pageDataJson: String)

    fun closePage()

    /**
     * Authoritative top page on the window's navigation stack, or null when detached.
     * Distinct name + `@ObjCName` avoids colliding with `RaftCmpNavigator.topPage` in Objective-C.
     */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName("currentTopPage")
    fun currentTopPage(): RaftCmpIosTopPage?
}

/** Implemented by the iOS page container (`RaftPageViewController`): page -> host notifications. */
interface RaftIosPageListener {
    /**
     * Whether the page paints a dark colour behind the status bar, after each change (status bar
     * style: light content when true). That is the frame under a head that claims the status bar
     * (dark in light mode for the Space and Quest themes), else the canvas; judged by luminance
     * (RaftCmpRoot's onSystemBarsResolved), not by the dark-mode flag.
     */
    fun onDarkThemeResolved(dark: Boolean)
}

/**
 * Implemented by the iOS scene-level toast overlay host (`RaftIosToastOverlayHost`): receives
 * toast visibility and window-point hit-test bounds from the scene's single [RaftToastHost].
 */
interface RaftIosToastOverlayListener {
    /**
     * Called on the main thread when the scene's toast stack transitions between empty and
     * non-empty. The host unhides its transparent overlay `UIWindow` while [hasToasts] is true
     * (without making it key) and hides it again when false.
     */
    fun onToastVisibilityChanged(hasToasts: Boolean)

    /**
     * Called on the main thread when the union bounding box of visible toast cards changes, in
     * UIKit points relative to the overlay window (`hasBounds = false` means pass every touch
     * through).
     */
    fun onToastBoundsChanged(
        hasBounds: Boolean,
        leftPt: Double,
        topPt: Double,
        rightPt: Double,
        bottomPt: Double,
    )
}

/**
 * One `UIWindowScene`'s window-level toast overlay on iOS: owns the single [RaftCmpToastWindow]
 * shared by every `RaftPageViewController` in that scene plus the transparent [viewController]
 * hosting [RaftToastHost] above the navigation stack.
 */
class RaftIosToastOverlay internal constructor(
    internal val window: RaftCmpToastWindow,
    val viewController: UIViewController,
    private val observerScope: CoroutineScope,
) {
    /**
     * Updates the scene's foreground/active lifecycle in [RaftCmpToasts].
     * Leaving the foreground hands off live toasts to another foreground scene if one exists.
     */
    fun setLifecycle(visible: Boolean, resumed: Boolean) {
        window.setLifecycle(visible = visible, resumed = resumed)
    }

    /** Detaches this scene's toast host (called when the `UIWindowScene` disconnects). */
    fun detach() {
        observerScope.cancel()
        window.detach()
    }
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
    private val coverSignal: RaftPageCoverSignal,
    private val toastOverlayState: MutableStateFlow<RaftIosToastOverlay?>,
    private val toastAvoidanceActiveState: MutableStateFlow<Boolean>,
    content: (RaftIosPage) -> UIViewController,
) {
    private val sticky = LinkedHashMap<String, JsonObject>()

    val viewController: UIViewController = content(this)

    /** Rebinds this page to the [RaftIosToastOverlay] of the `UIWindowScene` currently hosting it. */
    fun setToastOverlay(overlay: RaftIosToastOverlay?) {
        toastOverlayState.value = overlay
    }

    /**
     * Controls whether this page's `raftToastAvoid`, `RaftToastAvoidBottom`, and IME bottom inset
     * report into the scene's shared [RaftToastHostState]. False while covered or presented as a
     * non-fullscreen sheet whose view coordinates do not match the full overlay window.
     */
    fun setToastAvoidanceActive(active: Boolean) {
        toastAvoidanceActiveState.value = active
    }

    /**
     * Main thread. True when a back-direction drag starting at ([x], [y]) (points in
     * [viewController]'s view) would be consumed by the page's horizontal content (a tab pager past
     * its first page, a scroller away from its start): the host's full-page interactive pop must
     * then not begin. Content registers through `rememberRaftBackSwipeScroll` /
     * `raftBackSwipeRegion` / `raftKeepsBackSwipe` / `RaftBlockBackSwipe`.
     */
    fun contentConsumesBackSwipe(x: Double, y: Double): Boolean =
        backSwipeRegions.contentConsumesBackSwipeAtPoint(x, y)

    /**
     * Main thread. True when a touch starting at ([x], [y]) (points in [viewController]'s view) is
     * on content no edge back gesture may hold (the app rail, `raftKeepsEdgeTouches`).
     */
    fun keepsEdgeTouches(x: Double, y: Double): Boolean =
        backSwipeRegions.keepsEdgeTouchesAtPoint(x, y)

    /**
     * The page is about to be covered (its container's `viewWillDisappear`): closes the page's
     * dialogs, menus and popovers ([RaftPageCoverSignal]) while it still composes. The page's own
     * `openPage` already did that for every push made from Kotlin; this backstops pushes and
     * presentations the native side makes itself (HRBridgeModule, notification routing). Safe from
     * any thread: it always runs on the main thread.
     */
    fun pageWillBeCovered() {
        if (NSThread.isMainThread) {
            coverSignal.fire()
        } else {
            dispatch_async(dispatch_get_main_queue()) { coverSignal.fire() }
        }
    }

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
     * Whether two opens show the same page ([RaftPageRouteIdentities]: the thread page by thread and
     * message anchor, other pages by pageData minus per-open facts). The router's push rules
     * (`RaftIosPageOpenGate.swift`, task #151) compare the page on top with an open through it.
     */
    fun sameRoute(pageName: String, pageDataJson: String, otherPageName: String, otherPageDataJson: String): Boolean {
        val data = parseRaftJsonObjectOrNull(pageDataJson) ?: return false
        val otherData = parseRaftJsonObjectOrNull(otherPageDataJson) ?: return false
        return RaftPageRouteIdentities.of(pageName, data) == RaftPageRouteIdentities.of(otherPageName, otherData)
    }

    /**
     * Creates one scene-level transparent [RaftIosToastOverlay] that renders [RaftToastHost]
     * above every `RaftPageViewController` in that `UIWindowScene` and reports hit-test bounds
     * and visibility transitions to [listener].
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun makeToastOverlay(listener: RaftIosToastOverlayListener): RaftIosToastOverlay {
        val state = RaftToastHostState()
        val toastWindow = RaftCmpToasts.attach(state)
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var lastHasToasts: Boolean? = null
        observerScope.launch {
            state.toasts.collect { list ->
                val hasToasts = list.isNotEmpty()
                if (hasToasts != lastHasToasts) {
                    lastHasToasts = hasToasts
                    listener.onToastVisibilityChanged(hasToasts)
                }
            }
        }
        val viewController =
            ComposeUIViewController(
                configure = {
                    opaque = false
                    onFocusBehavior = OnFocusBehavior.DoNothing
                },
            ) {
                val controller = LocalUIViewController.current
                val density = LocalDensity.current.density
                val systemDark = isSystemInDarkTheme()
                SideEffect {
                    controller.view.opaque = false
                    controller.view.backgroundColor = UIColor.clearColor
                }
                LaunchedEffect(systemDark) {
                    controller.view.opaque = false
                    controller.view.backgroundColor = UIColor.clearColor
                }
                RaftTheme(systemDark = systemDark) {
                    RaftToastHost(
                        state = state,
                        onToastBoundsChange = { rect ->
                            if (rect == null || rect.isEmpty || density <= 0f) {
                                listener.onToastBoundsChanged(false, 0.0, 0.0, 0.0, 0.0)
                            } else {
                                val scale = density.toDouble()
                                listener.onToastBoundsChanged(
                                    true,
                                    rect.left.toDouble() / scale,
                                    rect.top.toDouble() / scale,
                                    rect.right.toDouble() / scale,
                                    rect.bottom.toDouble() / scale,
                                )
                            }
                        },
                    )
                }
            }
        return RaftIosToastOverlay(
            window = toastWindow,
            viewController = viewController,
            observerScope = observerScope,
        )
    }

    /**
     * A page rendering [pageName] with [pageDataJson] (the page's pageData as JSON text). The host
     * embeds [RaftIosPage.viewController] in its page container and pushes the container. When
     * [toastOverlay] is non-null, the page shares that scene's single window-level toast host
     * instead of mounting a per-page [RaftToastHost].
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun makePage(
        pageName: String,
        pageDataJson: String,
        bridge: RaftCmpIosBridge,
        navigator: RaftCmpIosNavigator,
        listener: RaftIosPageListener?,
        toastOverlay: RaftIosToastOverlay? = null,
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
        val onSystemBarsResolved: ((RaftSystemBarsAppearance) -> Unit)? =
            listener?.let { target -> { bars -> target.onDarkThemeResolved(!bars.lightStatusBars) } }
        val backSwipeRegions = RaftBackSwipeRegions()
        // One per page, shared by the page's navigator (RaftCmpRoot) and its container's
        // viewWillDisappear (RaftIosPage.pageWillBeCovered).
        val coverSignal = RaftPageCoverSignal()
        val toastOverlayState = MutableStateFlow(toastOverlay)
        val toastAvoidanceActiveState = MutableStateFlow(true)
        return RaftIosPage(
            events = events,
            backSwipeRegions = backSwipeRegions,
            coverSignal = coverSignal,
            toastOverlayState = toastOverlayState,
            toastAvoidanceActiveState = toastAvoidanceActiveState,
        ) { page ->
            ComposeUIViewController(
                configure = {
                    // Not opaque: an opaque CMP view clears its canvas, and fills its view, with
                    // black or white chosen from the SYSTEM trait (ComposeView/MetalView in CMP),
                    // not from the app theme. Every pixel Compose does not draw (the content a
                    // keyboard focus offset moves up, a frame before the first composition) then
                    // flashed black under a light app on a dark-mode phone. Transparent, it shows
                    // the canvas colour painted behind it (raftPaintHostCanvas).
                    opaque = false
                    // One owner for the keyboard's bottom inset: the page. Every shared page and
                    // modal pads for WindowInsets.ime itself (thread composer:
                    // raftThreadComposerLiveKeyboardPadding; modals: keyboardAvoidanceEnabled), as
                    // on Android, where the edge-to-edge window never resizes for the IME. CMP's
                    // default FocusableAboveKeyboard additionally shifts the whole view up by the
                    // part of the focused field it thinks the keyboard covers. It measures that
                    // against the layout of the moment of the keyboard event, so a keyboard that
                    // comes back without a frame-by-frame animation (the composer with the
                    // keyboard up -> add image -> PHPicker sheet) is measured before the page's
                    // own IME padding lands: the view moves up by the keyboard height and the
                    // padding then adds it again, lifting the page by twice the keyboard.
                    onFocusBehavior = OnFocusBehavior.DoNothing
                },
            ) {
                // Read from composition, not captured in this lambda: a Kotlin closure holding the
                // controller that holds this content would be an ObjC/Kotlin retain cycle.
                val controller = LocalUIViewController.current
                val focusManager = LocalFocusManager.current
                val currentToastOverlay by toastOverlayState.collectAsState()
                val currentToastAvoidanceActive by toastAvoidanceActiveState.collectAsState()
                // Clear Compose focus when another iOS page covers this UIViewController so stale
                // non-text region/window focus cannot linger in the shared focus coordinator.
                DisposableEffect(coverSignal, focusManager) {
                    val registration = coverSignal.register { focusManager.clearFocus(force = true) }
                    onDispose { registration.unregister() }
                }
                // Text fields hide their edit menu through the guard, so an empty caret menu
                // cannot come back as a lone "Select All" once text arrives (RaftTextToolbarGuard).
                ProvideRaftTextToolbarGuard {
                    RaftCmpRoot(
                        args = args,
                        host = host,
                        onSystemBarsResolved = onSystemBarsResolved,
                        onCanvasColorResolved = { color -> controller.raftPaintHostCanvas(color) },
                        backSwipeRegions = backSwipeRegions,
                        coverSignal = coverSignal,
                        toastWindow = currentToastOverlay?.window,
                        toastAvoidanceActive = currentToastAvoidanceActive,
                    )
                }
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

    // The router holds pushes itself for as long as UIKit's transition runs (RaftIosPageOpenGate).
    override fun openTransition(pageName: String, pageData: JsonObject) = RaftPageOpenTransition.Untimed

    override fun closePage() = navigator.closePage()

    override fun topPage(): RaftCmpPageArgs? =
        navigator.currentTopPage()?.let { RaftCmpPageArgs.fromJsonText(it.pageName, it.pageDataJson) }
}
