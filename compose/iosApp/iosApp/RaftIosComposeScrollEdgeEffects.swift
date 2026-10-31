import UIKit

/// Hides the iOS 26+ system scroll edge effects on Compose Multiplatform's own internal
/// `UIScrollView`s.
///
/// Compose's `OverlayInputView` (inside `ComposeContainerView`, full screen) is a `UIScrollView`,
/// so UIKit attaches a `ScrollEdgeEffectView` to it at the top and bottom safe-area edges. With
/// Reduce Transparency on, that effect draws a `Keyline` layer: a 1px grey line under the status
/// bar on every page (diagnostics tag `ios-topband`). Compose paints its own bars, so the system
/// effect is never wanted there.
///
/// Only views whose class is Compose's (`androidx.compose.` in the runtime class name) are
/// touched. Native views embedded in Compose (web views, PDF, text views, video) and native
/// scroll views elsewhere in the page keep their system behavior: the walk does not descend
/// into them. Idempotent and cheap (a few dozen views); safe to call on every layout.
@objc final class RaftIosComposeScrollEdgeEffects: NSObject {
    @objc(hideInView:)
    static func hide(in root: UIView) {
        guard #available(iOS 26.0, *) else { return }
        visit(root, insideCompose: false)
    }

    static func isComposeView(_ view: UIView) -> Bool {
        NSStringFromClass(type(of: view)).contains("androidx.compose.")
    }

    @available(iOS 26.0, *)
    private static func visit(_ view: UIView, insideCompose: Bool) {
        let compose = isComposeView(view)
        // Inside Compose, anything not Compose's own is UIKit internals or embedded native content.
        if insideCompose && !compose { return }
        if let scrollView = view as? UIScrollView {
            // A native scroll view outside Compose keeps its edge effects, and so does its content.
            guard compose else { return }
            if !scrollView.topEdgeEffect.isHidden { scrollView.topEdgeEffect.isHidden = true }
            if !scrollView.bottomEdgeEffect.isHidden { scrollView.bottomEdgeEffect.isHidden = true }
        }
        for sub in view.subviews {
            visit(sub, insideCompose: insideCompose || compose)
        }
    }
}
