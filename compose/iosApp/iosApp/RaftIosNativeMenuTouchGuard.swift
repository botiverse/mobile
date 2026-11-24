import Foundation
import UIKit

/// Prevents an outside tap that dismisses a native UIKit pull-down `UIMenu` (such as a navigation
/// bar `⋯` button, title menu, or side-action overflow menu) from passing through to Compose
/// Multiplatform's `TouchesGestureRecognizer` and activating an underlying message, chip, or button.
///
/// Why this happens:
/// A non-modal `UIBarButtonItem(menu:)` presents via UIKit's context-menu container
/// (`_UIContextMenuContainerView`) and a passthrough dismissal gate
/// (`_UIPassthroughGateGestureRecognizer`). Because Compose's `OverlayInputView` is a `UIScrollView`
/// whose `TouchesGestureRecognizer` is not cancelled by UIKit's passthrough dismissal gate, tapping
/// outside the menu both dismisses the menu and delivers a full press+release tap to Compose.
///
/// How this fixes it without disturbing iOS 26/27 Liquid Glass bar items or UIKit hit-testing:
/// - `RaftIosWindow` leaves `hitTest(_:with:)` return values unmodified so UIKit's menu and
///   passthrough dismissal run natively, and records only when a top-level touch hit-test started
///   with a visible native menu open and resolved inside Compose.
/// - In `sendEvent(_:)`, when a `.began` touch lands inside Compose while a native menu is active
///   (or matched that hit-test), enabled Compose gesture recognizers on that touch's ancestor chain
///   are temporarily disabled across `super.sendEvent(event)` and immediately restored in `defer`.
/// - UIKit therefore never attaches Compose's `TouchesGestureRecognizer` to the dismissal touch,
///   while UIKit's own dismissal recognizer on the window/container stays enabled and dismisses the
///   menu cleanly on the first tap. Subsequent taps work immediately with zero sticky state.
enum RaftIosNativeMenuTouchGuard {
    static func isComposeView(_ view: UIView) -> Bool {
        NSStringFromClass(type(of: view)).contains("androidx.compose.")
    }

    static func isComposeGestureRecognizer(_ recognizer: UIGestureRecognizer) -> Bool {
        let className = NSStringFromClass(type(of: recognizer))
        return className.contains("androidx.compose.") || className.contains("TouchesGestureRecognizer")
    }

    static func isComposeGestureRecognizerOrView(_ recognizer: UIGestureRecognizer) -> Bool {
        if isComposeGestureRecognizer(recognizer) {
            return true
        }
        if let view = recognizer.view, isComposeView(view) {
            return true
        }
        return false
    }

    /// True when [view] itself or any of its ancestors is a Compose Multiplatform view.
    /// Native menus (`_UIContextMenuContainerView`), navigation bars, and tab bars live outside
    /// Compose's view subtree, so taps inside them always return false.
    static func isInsideComposeView(_ view: UIView?) -> Bool {
        var current = view
        while let candidate = current {
            if isComposeView(candidate) {
                return true
            }
            current = candidate.superview
        }
        return false
    }

    /// Class-name match for UIKit's native menu / popover presentation views (excludes text edit
    /// callout menus like `_UIEditMenuContainerView` so caret taps keep working).
    static func isNativeMenuViewClassName(_ className: String) -> Bool {
        className.contains("ContextMenu") || className.contains("PopoverView")
    }

    static func hasActiveNativeMenuPresentation(in window: UIWindow) -> Bool {
        let windows = window.windowScene?.windows ?? [window]
        return hasActiveNativeMenuPresentation(in: windows)
    }

    static func hasActiveNativeMenuPresentation(in windows: [UIWindow]) -> Bool {
        for candidate in windows {
            guard !candidate.isHidden, candidate.alpha > 0.01, candidate.isUserInteractionEnabled else {
                continue
            }
            if containsActiveNativeMenuView(in: candidate) {
                return true
            }
        }
        return false
    }

    static func containsActiveNativeMenuView(in view: UIView) -> Bool {
        guard !view.isHidden, view.alpha > 0.01, view.isUserInteractionEnabled else {
            return false
        }
        // Native UIKit menus never mount inside Compose's own view hierarchy.
        if isComposeView(view) {
            return false
        }
        let className = NSStringFromClass(type(of: view))
        if isNativeMenuViewClassName(className) && !(view is UIControl) {
            // A container view (`_UIContextMenuContainerView`) counts as active only while it still
            // holds visible menu content (guards against an empty or fading-out dismissal shell).
            if className.contains("Container") {
                return hasVisibleContent(in: view)
            }
            return !view.bounds.isEmpty
        }
        for subview in view.subviews {
            if containsActiveNativeMenuView(in: subview) {
                return true
            }
        }
        return false
    }

    /// Enabled Compose-subtree gesture recognizers along [view]'s ancestor chain.
    /// Recognizers above the outermost Compose view (such as `UINavigationController` pop gestures
    /// and UIKit's window-level `_UIPassthroughGateGestureRecognizer`) are left untouched unless
    /// the recognizer itself is a Compose recognizer.
    static func composeGestureRecognizersToSuppress(for view: UIView?) -> [UIGestureRecognizer] {
        guard let outermostCompose = outermostComposeAncestor(of: view) else {
            return []
        }
        var result = [UIGestureRecognizer]()
        var seen = Set<ObjectIdentifier>()
        var insideComposeSubtree = true
        var current = view
        while let candidate = current {
            for recognizer in candidate.gestureRecognizers ?? [] {
                guard recognizer.isEnabled else { continue }
                guard insideComposeSubtree || isComposeGestureRecognizer(recognizer) else { continue }
                if seen.insert(ObjectIdentifier(recognizer)).inserted {
                    result.append(recognizer)
                }
            }
            if candidate === outermostCompose {
                insideComposeSubtree = false
            }
            current = candidate.superview
        }
        return result
    }

    private static func outermostComposeAncestor(of view: UIView?) -> UIView? {
        var outermost: UIView?
        var current = view
        while let candidate = current {
            if isComposeView(candidate) {
                outermost = candidate
            }
            current = candidate.superview
        }
        return outermost
    }

    private static func hasVisibleContent(in container: UIView) -> Bool {
        for subview in container.subviews {
            guard !subview.isHidden, subview.alpha > 0.01 else { continue }
            if !subview.bounds.isEmpty {
                return true
            }
            if hasVisibleContent(in: subview) {
                return true
            }
        }
        return false
    }
}

/// Value representation of one `UITouch` for `RaftIosWindow` dispatch logic (keeps unit tests free
/// of private `UITouch` ivar subclassing).
struct RaftIosWindowTouchSnapshot {
    let id: ObjectIdentifier
    let view: UIView?
    let phase: UITouch.Phase
    let gestureRecognizers: [UIGestureRecognizer]

    init(
        id: ObjectIdentifier,
        view: UIView?,
        phase: UITouch.Phase,
        gestureRecognizers: [UIGestureRecognizer] = []
    ) {
        self.id = id
        self.view = view
        self.phase = phase
        self.gestureRecognizers = gestureRecognizers
    }

    init(touch: UITouch) {
        self.id = ObjectIdentifier(touch)
        self.view = touch.view
        self.phase = touch.phase
        self.gestureRecognizers = touch.gestureRecognizers ?? []
    }
}

/// Scene window used by `RaftIosSceneDelegate` that suppresses Compose touch pass-through when an
/// outside tap dismisses an open native `UIMenu` or popover.
final class RaftIosWindow: UIWindow {
    private var hitTestDepth = 0
    private weak var pendingMenuDismissalEvent: UIEvent?
    private var pendingMenuDismissalTimestamp: TimeInterval?
    private var suppressedMenuDismissalTouchIds = Set<ObjectIdentifier>()

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let isTopLevelTouchHitTest = hitTestDepth == 0 && event?.type == .touches
        let menuWasActive =
            isTopLevelTouchHitTest && RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: self)
        hitTestDepth += 1
        let hit = super.hitTest(point, with: event)
        hitTestDepth -= 1
        if isTopLevelTouchHitTest, let event {
            recordTouchHitTest(
                event: event,
                timestamp: event.timestamp,
                menuWasActive: menuWasActive,
                hitView: hit
            )
        }
        return hit
    }

    override func sendEvent(_ event: UIEvent) {
        guard event.type == .touches, let touches = event.allTouches, !touches.isEmpty else {
            super.sendEvent(event)
            return
        }
        let snapshots = touches.map { RaftIosWindowTouchSnapshot(touch: $0) }
        let disabledRecognizers = prepareTouchSuppression(
            snapshots,
            event: event,
            timestamp: event.timestamp,
            activeMenuCheck: { RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: self) }
        )
        for recognizer in disabledRecognizers {
            recognizer.isEnabled = false
        }
        defer {
            for recognizer in disabledRecognizers {
                recognizer.isEnabled = true
            }
        }
        super.sendEvent(event)
    }

    func recordTouchHitTest(
        event: UIEvent,
        timestamp: TimeInterval,
        menuWasActive: Bool,
        hitView: UIView?
    ) {
        if menuWasActive && RaftIosNativeMenuTouchGuard.isInsideComposeView(hitView) {
            pendingMenuDismissalEvent = event
            pendingMenuDismissalTimestamp = timestamp
        } else {
            pendingMenuDismissalEvent = nil
            pendingMenuDismissalTimestamp = nil
        }
    }

    func dispatchTouchSnapshots(
        _ touches: [RaftIosWindowTouchSnapshot],
        event: UIEvent,
        timestamp: TimeInterval,
        activeMenuCheck: () -> Bool,
        forward: () -> Void
    ) {
        let disabledRecognizers = prepareTouchSuppression(
            touches,
            event: event,
            timestamp: timestamp,
            activeMenuCheck: activeMenuCheck
        )
        for recognizer in disabledRecognizers {
            recognizer.isEnabled = false
        }
        defer {
            for recognizer in disabledRecognizers {
                recognizer.isEnabled = true
            }
        }
        forward()
    }

    private func prepareTouchSuppression(
        _ touches: [RaftIosWindowTouchSnapshot],
        event: UIEvent,
        timestamp: TimeInterval,
        activeMenuCheck: () -> Bool
    ) -> [UIGestureRecognizer] {
        let activeTouchIds = Set(touches.map(\.id))
        suppressedMenuDismissalTouchIds.formIntersection(activeTouchIds)

        let matchedPendingHitTest =
            pendingMenuDismissalEvent === event && pendingMenuDismissalTimestamp == timestamp
        if touches.contains(where: { $0.phase == .began }) {
            pendingMenuDismissalEvent = nil
            pendingMenuDismissalTimestamp = nil
        }

        var suppressedActiveTouches = [RaftIosWindowTouchSnapshot]()
        var beganComposeTouches = [RaftIosWindowTouchSnapshot]()
        for touch in touches {
            if suppressedMenuDismissalTouchIds.contains(touch.id) {
                suppressedActiveTouches.append(touch)
                if touch.phase == .ended || touch.phase == .cancelled {
                    suppressedMenuDismissalTouchIds.remove(touch.id)
                }
            } else if touch.phase == .began && RaftIosNativeMenuTouchGuard.isInsideComposeView(touch.view) {
                beganComposeTouches.append(touch)
            }
        }

        var disabledRecognizers = [UIGestureRecognizer]()
        var seenRecognizerIds = Set<ObjectIdentifier>()

        if !beganComposeTouches.isEmpty, matchedPendingHitTest || activeMenuCheck() {
            for touch in beganComposeTouches {
                suppressedMenuDismissalTouchIds.insert(touch.id)
                for recognizer in RaftIosNativeMenuTouchGuard.composeGestureRecognizersToSuppress(for: touch.view) {
                    if seenRecognizerIds.insert(ObjectIdentifier(recognizer)).inserted {
                        disabledRecognizers.append(recognizer)
                    }
                }
            }
        }

        // Belt-and-suspenders: if a suppressed dismissal touch somehow still carries an enabled
        // Compose recognizer on .moved/.ended, cancel it so release never clicks through.
        for touch in suppressedActiveTouches {
            for recognizer in touch.gestureRecognizers {
                if recognizer.isEnabled,
                   RaftIosNativeMenuTouchGuard.isComposeGestureRecognizerOrView(recognizer),
                   seenRecognizerIds.insert(ObjectIdentifier(recognizer)).inserted {
                    disabledRecognizers.append(recognizer)
                }
            }
        }

        return disabledRecognizers
    }
}
