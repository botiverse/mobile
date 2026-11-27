import Foundation
import QuartzCore
import UIKit

/// One-shot cancellable display-link barrier so UIKit overlays wait on real rendered vsync frames
/// (e.g. ensuring Skia/Metal has presented its transparent clear drawable, or pacing modal
/// dismissal checks) rather than guessing with `DispatchQueue.main.async` or hardcoded ms timers.
@objc final class RaftIosDisplayLinkFrameWaiter: NSObject {
    private var remainingFrames: Int
    private var action: (() -> Void)?
    private var displayLink: CADisplayLink?

    private init(frames: Int, action: @escaping () -> Void) {
        self.remainingFrames = max(1, frames)
        self.action = action
        super.init()
    }

    @objc(scheduleAfterFrames:action:)
    @discardableResult
    static func schedule(afterFrames frames: Int, _ action: @escaping () -> Void) -> RaftIosDisplayLinkFrameWaiter {
        let waiter = RaftIosDisplayLinkFrameWaiter(frames: frames, action: action)
        let link = CADisplayLink(target: waiter, selector: #selector(handleTick))
        waiter.displayLink = link
        link.add(to: .main, forMode: .common)
        return waiter
    }

    @objc func cancel() {
        displayLink?.invalidate()
        displayLink = nil
        action = nil
    }

    @objc private func handleTick() {
        remainingFrames -= 1
        guard remainingFrames <= 0 else { return }
        let work = action
        cancel()
        work?()
    }
}

/// Pass-through transparent window that hosts one `UIWindowScene`'s single `RaftToastHost`
/// above its navigation stack (#proj-mobile:#351). Never becomes key; hidden whenever no toast
/// is visible; forwards touches to Compose only inside `toastHitRect`.
final class RaftIosToastOverlayWindow: UIWindow {
    /// Bounding union of all visible toast cards in this window's point coordinate space.
    var toastHitRect: CGRect?

    override init(windowScene: UIWindowScene) {
        super.init(windowScene: windowScene)
        configureAppearance()
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        configureAppearance()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func configureAppearance() {
        backgroundColor = .clear
        isOpaque = false
        windowLevel = UIWindow.Level(rawValue: UIWindow.Level.normal.rawValue + 1)
        isHidden = true
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard !isHidden,
              let rect = toastHitRect,
              !rect.isEmpty,
              rect.contains(point)
        else {
            return nil
        }
        return super.hitTest(point, with: event)
    }
}

/// Pure-UIKit visibility and hit-rect state machine for `RaftIosToastOverlayWindow`:
/// - Clears `toastHitRect` immediately when `hasToasts` flips false so touches pass through
///   on the same frame without waiting for Compose.
/// - Ignores stale non-empty bounds callbacks while `!hasVisibleToasts` or hidden.
/// - Defers `isHidden = true` until Compose reports empty bounds (plus a short next-frame
///   fallback if Compose never emitted non-empty bounds) so `CAMetalLayer` presents its
///   transparent clear frame before the window hides and never flashes a stale toast later.
final class RaftIosToastOverlayWindowController {
    let overlayWindow: RaftIosToastOverlayWindow
    private var scheduleNextFrameHide: (@escaping () -> Void) -> Void
    private var scheduleFallbackHide: (@escaping () -> Void) -> Void
    private var hasVisibleToasts = false
    private var pendingHideToken: UInt64 = 0
    private var activeWaiters: [RaftIosDisplayLinkFrameWaiter] = []
    private var detached = false

    init(
        overlayWindow: RaftIosToastOverlayWindow,
        scheduleNextFrameHide: ((@escaping () -> Void) -> Void)? = nil,
        scheduleFallbackHide: ((@escaping () -> Void) -> Void)? = nil
    ) {
        self.overlayWindow = overlayWindow
        self.scheduleNextFrameHide = scheduleNextFrameHide ?? { _ in }
        self.scheduleFallbackHide = scheduleFallbackHide ?? { _ in }
        if scheduleNextFrameHide == nil {
            self.scheduleNextFrameHide = { [weak self] work in
                // 2 display-link ticks: frame 1 commits Compose/Metal's transparent clear drawable;
                // frame 2 guarantees it has presented before hiding the window.
                let waiter = RaftIosDisplayLinkFrameWaiter.schedule(afterFrames: 2, work)
                self?.activeWaiters.append(waiter)
            }
        }
        if scheduleFallbackHide == nil {
            self.scheduleFallbackHide = { [weak self] work in
                // 4 live display-link ticks if Compose never emitted non-empty bounds (zero hardcoded ms).
                let waiter = RaftIosDisplayLinkFrameWaiter.schedule(afterFrames: 4, work)
                self?.activeWaiters.append(waiter)
            }
        }
    }

    private func cancelActiveWaiters() {
        for waiter in activeWaiters {
            waiter.cancel()
        }
        activeWaiters.removeAll()
    }

    func handleVisibilityChanged(
        hasToasts: Bool,
        sceneBounds: CGRect?,
        userInterfaceStyle: UIUserInterfaceStyle
    ) {
        guard !detached else { return }
        if hasToasts {
            hasVisibleToasts = true
            pendingHideToken &+= 1
            cancelActiveWaiters()
            if let bounds = sceneBounds, overlayWindow.frame != bounds {
                overlayWindow.frame = bounds
            }
            overlayWindow.overrideUserInterfaceStyle = userInterfaceStyle
            overlayWindow.isHidden = false
            overlayWindow.rootViewController?.setNeedsStatusBarAppearanceUpdate()
        } else {
            hasVisibleToasts = false
            overlayWindow.toastHitRect = nil
            pendingHideToken &+= 1
            cancelActiveWaiters()
            let token = pendingHideToken
            guard !overlayWindow.isHidden else { return }
            scheduleFallbackHide { [weak self] in
                self?.finishPendingHideIfCurrent(token: token)
            }
        }
    }

    func handleBoundsChanged(
        hasBounds: Bool,
        leftPt: Double,
        topPt: Double,
        rightPt: Double,
        bottomPt: Double
    ) {
        guard !detached else { return }
        if !hasBounds || rightPt <= leftPt || bottomPt <= topPt {
            overlayWindow.toastHitRect = nil
            if !hasVisibleToasts && !overlayWindow.isHidden {
                let token = pendingHideToken
                scheduleNextFrameHide { [weak self] in
                    self?.finishPendingHideIfCurrent(token: token)
                }
            }
            return
        }
        guard hasVisibleToasts && !overlayWindow.isHidden else {
            overlayWindow.toastHitRect = nil
            return
        }
        overlayWindow.toastHitRect = CGRect(
            x: leftPt,
            y: topPt,
            width: rightPt - leftPt,
            height: bottomPt - topPt
        )
    }

    func detach() {
        guard !detached else { return }
        detached = true
        hasVisibleToasts = false
        pendingHideToken &+= 1
        cancelActiveWaiters()
        overlayWindow.toastHitRect = nil
        overlayWindow.isHidden = true
    }

    private func finishPendingHideIfCurrent(token: UInt64) {
        guard !detached, !hasVisibleToasts, pendingHideToken == token else { return }
        cancelActiveWaiters()
        overlayWindow.toastHitRect = nil
        overlayWindow.isHidden = true
    }
}
