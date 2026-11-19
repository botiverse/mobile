@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.app

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.CoreGraphics.CGRect
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.objc.class_addMethod
import platform.objc.class_getInstanceMethod
import platform.objc.class_getSuperclass
import platform.objc.method_getImplementation
import platform.objc.method_getTypeEncoding
import platform.objc.object_getClass
import platform.objc.sel_registerName

/**
 * The app's WKWebView. It differs from a plain one only in WebKit's form accessory bar (the "↑ ↓ ✓"
 * bar over the keyboard while a field has focus), which it leaves out unless
 * [showsFormAccessoryBar] (#proj-mobile:#194).
 *
 * On iOS 26 that bar floats over the page's bottom edge, and a plain WKWebView's visual viewport
 * ends at the keyboard, not at the bar. A page that keeps a bottom sheet above the keyboard by the
 * visual viewport (the Artifact comment composer) therefore had its input and its Save draft/Send
 * buttons under the bar. Safari leaves room for the bar; an app's WKWebView has no public API to do
 * the same. Without the bar the visual viewport and what the user sees agree again.
 *
 * WebKit's focused content view (WKContentView) answers `inputAccessoryView` by asking its
 * WKWebView, so overriding it on a WKWebView subclass is the hook WebKit leaves for apps; no
 * private class is touched. Kotlin/Native sees `inputAccessoryView` as a UIResponder category
 * member and cannot override it, so [RaftIosWebViewAccessoryHook] adds the override to this
 * subclass's Objective-C class at run time (this class only: other WKWebViews are untouched).
 */
internal class RaftIosWebView(
    frame: CValue<CGRect>,
    configuration: WKWebViewConfiguration,
) : WKWebView(frame = frame, configuration = configuration) {
    /**
     * [RaftWebViewConfig.showsFormAccessoryBar]. Applied the next time a field takes focus (the
     * host sets it before a page loads).
     */
    var showsFormAccessoryBar: Boolean = false

    init {
        RaftIosWebViewAccessoryHook.install(this)
    }

    /** What WebKit's focused field gets as its accessory view: null hides the bar. */
    internal fun formAccessoryView(): Any? = RaftIosWebViewAccessoryHook.accessoryViewOf(this)
}

private typealias RaftObjCGetter = CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>

/** The run-time `-[RaftIosWebView inputAccessoryView]` ([RaftIosWebView]). Main thread only (UIKit). */
internal object RaftIosWebViewAccessoryHook {
    private const val SELECTOR = "inputAccessoryView"
    private var installed = false

    /** WKWebView's own `inputAccessoryView` (WebKit's form bar), for a page that keeps the bar. */
    private var inherited: CPointer<RaftObjCGetter>? = null

    fun install(view: RaftIosWebView) {
        if (installed) return
        installed = true
        val selector = sel_registerName(SELECTOR)
        val ownClass = object_getClass(view)
        val inheritedMethod = class_getInstanceMethod(class_getSuperclass(ownClass), selector)
        inherited = method_getImplementation(inheritedMethod)?.reinterpret()
        val implementation =
            staticCFunction { self: COpaquePointer?, cmd: COpaquePointer? ->
                val webView = self?.let { interpretObjCPointerOrNull<RaftIosWebView>(it.rawValue) }
                if (webView?.showsFormAccessoryBar == true) inherited?.invoke(self, cmd) else null
            }
        val added =
            class_addMethod(
                ownClass,
                selector,
                implementation.reinterpret(),
                method_getTypeEncoding(inheritedMethod)?.toKString() ?: "@@:",
            )
        raftLogInfo("SlockWebView", "form_accessory_hook installed=$added")
    }

    /** Sends `inputAccessoryView` to [view], as WebKit's content view does. */
    fun accessoryViewOf(view: RaftIosWebView): Any? = view.performSelector(sel_registerName(SELECTOR))
}
