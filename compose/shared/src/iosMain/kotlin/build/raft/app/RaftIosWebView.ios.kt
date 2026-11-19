@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package build.raft.app

import kotlinx.cinterop.CValue
import platform.CoreGraphics.CGRect
import platform.UIKit.UIView
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

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
 * WKWebView, so this override is the hook WebKit leaves for apps; no private class is touched.
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

    override fun inputAccessoryView(): UIView? = if (showsFormAccessoryBar) super.inputAccessoryView() else null
}
