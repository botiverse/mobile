import Foundation
import UIKit
import UIKit.UIGestureRecognizerSubclass

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
/// How this fixes it on EVERY `UIWindow` (including SwiftUI `WindowGroup`'s default `UIWindow`,
/// without requiring a custom `UIWindow` subclass or modifying the `UIScene` lifecycle, and with
/// zero private API calls or swizzling):
/// - `RaftIosNativeMenuTouchGuardInstaller.installIfNeeded(in:)` attaches a single
///   `RaftIosNativeMenuDismissalGuardRecognizer` to the page's live `UIWindow`.
/// - During UIKit's `gestureRecognizer(_:shouldReceive:)` (which runs before `touchesBegan` on any
///   recognizer, while the menu container is still visible), if a touch lands inside Compose while a
///   native menu / popover is active, the guard disables Compose-subtree gesture recognizers for
///   that dismissal touch, claims the touch with `cancelsTouchesInView = true`, runs simultaneously
///   with UIKit's own dismissal gate so the menu closes cleanly on the first tap, and re-enables
///   Compose's recognizers on the next main runloop tick once the touch ends or cancels.
/// - `RaftIosWindow` remains supported as a fallback when a scene instantiates it without the
///   window-attached recognizer.
enum RaftIosNativeMenuTouchGuard {
    static var diagnosticLogger: ((String) -> Void)?

    static func logDiagnostic(_ message: String) {
        diagnosticLogger?(message)
    }

    static func isComposeView(_ view: UIView) -> Bool {
        NSStringFromClass(type(of: view)).contains("androidx.compose.")
    }

    static func isComposeGestureRecognizer(_ recognizer: UIGestureRecognizer) -> Bool {
        if recognizer is RaftIosNativeMenuDismissalGuardRecognizer {
            return false
        }
        let className = NSStringFromClass(type(of: recognizer))
        return className.contains("androidx.compose.") || className.contains("TouchesGestureRecognizer")
    }

    static func isComposeGestureRecognizerOrView(_ recognizer: UIGestureRecognizer) -> Bool {
        if recognizer is RaftIosNativeMenuDismissalGuardRecognizer {
            return false
        }
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
        guard !className.contains("EditMenu") else {
            return false
        }
        return className.contains("ContextMenu")
            || className.contains("PopoverView")
            || className.contains("PlatterMenu")
            || className.contains("MenuPresentation")
    }

    static func hasActiveNativeMenuPresentation(in window: UIWindow) -> Bool {
        activeNativeMenuPresentationClassName(in: window) != nil
    }

    static func hasActiveNativeMenuPresentation(in windows: [UIWindow]) -> Bool {
        activeNativeMenuPresentationClassName(in: windows) != nil
    }

    static func activeNativeMenuPresentationClassName(in window: UIWindow) -> String? {
        let windows = window.windowScene?.windows ?? [window]
        return activeNativeMenuPresentationClassName(in: windows)
    }

    static func activeNativeMenuPresentationClassName(in windows: [UIWindow]) -> String? {
        for candidate in windows {
            guard !candidate.isHidden, candidate.alpha > 0.01, candidate.isUserInteractionEnabled else {
                continue
            }
            if let menuViewClass = activeNativeMenuViewClassName(in: candidate) {
                return menuViewClass
            }
            if let popoverClass = activePresentedPopoverClassName(in: candidate) {
                return popoverClass
            }
        }
        return nil
    }

    static func containsActiveNativeMenuView(in view: UIView) -> Bool {
        activeNativeMenuViewClassName(in: view) != nil
    }

    static func activeNativeMenuViewClassName(in view: UIView) -> String? {
        guard !view.isHidden, view.alpha > 0.01, view.isUserInteractionEnabled else {
            return nil
        }
        // Native UIKit menus never mount inside Compose's own view hierarchy.
        if isComposeView(view) {
            return nil
        }
        let className = NSStringFromClass(type(of: view))
        if isNativeMenuViewClassName(className) && !(view is UIControl) {
            // A container view (`_UIContextMenuContainerView`) counts as active only while it still
            // holds visible menu content (guards against an empty or fading-out dismissal shell).
            if className.contains("Container") {
                return hasVisibleContent(in: view) ? className : nil
            }
            return view.bounds.isEmpty ? nil : className
        }
        for subview in view.subviews {
            if let match = activeNativeMenuViewClassName(in: subview) {
                return match
            }
        }
        return nil
    }

    private static func activePresentedPopoverClassName(in window: UIWindow) -> String? {
        var presenter = window.rootViewController
        while let presented = presenter?.presentedViewController {
            if !presented.isBeingDismissed,
               presented.modalPresentationStyle == .popover,
               presented.popoverPresentationController != nil {
                return NSStringFromClass(type(of: presented))
            }
            presenter = presented
        }
        return nil
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
                guard !(recognizer is RaftIosNativeMenuDismissalGuardRecognizer) else { continue }
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

/// Installs `RaftIosNativeMenuDismissalGuardRecognizer` idempotently onto any live `UIWindow`
/// (including SwiftUI `WindowGroup`'s default `UIWindow`).
@objc final class RaftIosNativeMenuTouchGuardInstaller: NSObject {
    @objc(installIfNeededInWindow:)
    @discardableResult
    static func installIfNeeded(in window: UIWindow?) -> Bool {
        guard let window else {
            return false
        }
        if window.gestureRecognizers?.contains(where: { $0 is RaftIosNativeMenuDismissalGuardRecognizer }) == true {
            return false
        }
        let guardRecognizer = RaftIosNativeMenuDismissalGuardRecognizer()
        window.addGestureRecognizer(guardRecognizer)
        RaftIosNativeMenuTouchGuard.logDiagnostic(
            "native_menu_guard_installed windowClass=\(NSStringFromClass(type(of: window)))"
        )
        return true
    }
}

/// Window-attached gesture recognizer that intercepts outside taps targeting Compose while a
/// native UIKit `UIMenu` or popover is open, suppressing Compose's `TouchesGestureRecognizer`
/// without interfering with UIKit's own menu dismissal gate.
final class RaftIosNativeMenuDismissalGuardRecognizer: UIGestureRecognizer, UIGestureRecognizerDelegate {
    var activeMenuCheck: (UIWindow) -> String? = {
        RaftIosNativeMenuTouchGuard.activeNativeMenuPresentationClassName(in: $0)
    }
    var scheduleRestore: (@escaping () -> Void) -> Void = { block in
        DispatchQueue.main.async(execute: block)
    }

    private var suppressedRecognizersById: [ObjectIdentifier: UIGestureRecognizer] = [:]
    private var activeTouchIds = Set<ObjectIdentifier>()
    private var pendingShouldReceiveGeneration: UInt64 = 0

    var suppressedRecognizerCountForTesting: Int {
        suppressedRecognizersById.count
    }

    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = true
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool {
        false
    }

    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool {
        RaftIosNativeMenuTouchGuard.isComposeGestureRecognizerOrView(preventedGestureRecognizer)
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        !RaftIosNativeMenuTouchGuard.isComposeGestureRecognizerOrView(otherGestureRecognizer)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        let hostWindow = (view as? UIWindow) ?? touch.window
        return handleShouldReceive(
            touchId: ObjectIdentifier(touch),
            view: touch.view,
            window: hostWindow,
            touchRecognizers: touch.gestureRecognizers ?? []
        )
    }

    func handleShouldReceive(
        touchId: ObjectIdentifier,
        view: UIView?,
        window: UIWindow?,
        touchRecognizers: [UIGestureRecognizer] = []
    ) -> Bool {
        if activeTouchIds.contains(touchId) {
            return true
        }
        // If a previous dismissal tap already ended and a rapid next tap arrived before the
        // queued main-loop restore ran, restore immediately before evaluating the new tap.
        if !suppressedRecognizersById.isEmpty && activeTouchIds.isEmpty {
            restoreSuppressedRecognizers()
        }
        guard let window,
              let targetView = view,
              RaftIosNativeMenuTouchGuard.isInsideComposeView(targetView),
              let menuClass = activeMenuCheck(window) else {
            return false
        }
        let suppressedCount = suppressComposeRecognizers(for: targetView, touchRecognizers: touchRecognizers)
        RaftIosNativeMenuTouchGuard.logDiagnostic(
            "native_menu_guard_suppressed source=gesture menu=\(menuClass) recognizers=\(suppressedCount) view=\(NSStringFromClass(type(of: targetView)))"
        )
        pendingShouldReceiveGeneration &+= 1
        let generation = pendingShouldReceiveGeneration
        scheduleRestore { [weak self] in
            guard let self else { return }
            if self.pendingShouldReceiveGeneration == generation && self.activeTouchIds.isEmpty {
                self.restoreSuppressedRecognizers()
            }
        }
        return true
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        handleTouchesBegan(touches.map { RaftIosWindowTouchSnapshot(touch: $0) })
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        handleTouchesMoved(touches.map { RaftIosWindowTouchSnapshot(touch: $0) })
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        handleTouchesFinished(touches.map { RaftIosWindowTouchSnapshot(touch: $0) }, cancelled: false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        handleTouchesFinished(touches.map { RaftIosWindowTouchSnapshot(touch: $0) }, cancelled: true)
    }

    override func reset() {
        super.reset()
        activeTouchIds.removeAll()
        if !suppressedRecognizersById.isEmpty {
            scheduleRestoreIfIdle()
        }
    }

    func handleTouchesBegan(_ touches: [RaftIosWindowTouchSnapshot]) {
        guard !touches.isEmpty else { return }
        for touch in touches {
            activeTouchIds.insert(touch.id)
            _ = suppressComposeRecognizers(for: touch.view, touchRecognizers: touch.gestureRecognizers)
        }
        if state == .possible {
            state = .began
        }
    }

    func handleTouchesMoved(_ touches: [RaftIosWindowTouchSnapshot]) {
        for touch in touches where activeTouchIds.contains(touch.id) {
            _ = suppressComposeRecognizers(for: touch.view, touchRecognizers: touch.gestureRecognizers)
        }
        if state == .began {
            state = .changed
        }
    }

    func handleTouchesFinished(_ touches: [RaftIosWindowTouchSnapshot], cancelled: Bool) {
        for touch in touches {
            if activeTouchIds.contains(touch.id) {
                _ = suppressComposeRecognizers(for: touch.view, touchRecognizers: touch.gestureRecognizers)
                activeTouchIds.remove(touch.id)
            }
        }
        if activeTouchIds.isEmpty {
            if state == .began || state == .changed {
                state = cancelled ? .cancelled : .ended
            } else if state == .possible {
                state = .failed
            }
            scheduleRestoreIfIdle()
        }
    }

    private func suppressComposeRecognizers(
        for view: UIView?,
        touchRecognizers: [UIGestureRecognizer]
    ) -> Int {
        var newlySuppressed = 0
        let candidates =
            RaftIosNativeMenuTouchGuard.composeGestureRecognizersToSuppress(for: view)
            + touchRecognizers.filter {
                $0.isEnabled && RaftIosNativeMenuTouchGuard.isComposeGestureRecognizerOrView($0)
            }
        for recognizer in candidates {
            guard recognizer.isEnabled else { continue }
            let id = ObjectIdentifier(recognizer)
            if suppressedRecognizersById[id] == nil {
                recognizer.isEnabled = false
                suppressedRecognizersById[id] = recognizer
                newlySuppressed += 1
            }
        }
        return newlySuppressed
    }

    private func scheduleRestoreIfIdle() {
        scheduleRestore { [weak self] in
            guard let self else { return }
            if self.activeTouchIds.isEmpty {
                self.restoreSuppressedRecognizers()
            }
        }
    }

    private func restoreSuppressedRecognizers() {
        guard !suppressedRecognizersById.isEmpty else { return }
        let recognizers = Array(suppressedRecognizersById.values)
        suppressedRecognizersById.removeAll()
        for recognizer in recognizers {
            recognizer.isEnabled = true
        }
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
        // When the window-attached guard recognizer is active, defer suppression to it so the two
        // layers never fight over `recognizer.isEnabled` restoration.
        if gestureRecognizers?.contains(where: { $0 is RaftIosNativeMenuDismissalGuardRecognizer && $0.isEnabled }) == true {
            super.sendEvent(event)
            return
        }
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
        if !disabledRecognizers.isEmpty {
            RaftIosNativeMenuTouchGuard.logDiagnostic(
                "native_menu_guard_suppressed source=window recognizers=\(disabledRecognizers.count)"
            )
        }
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
