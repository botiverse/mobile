import XCTest
import UIKit

final class RaftIosToastOverlayTests: XCTestCase {
    func testPassThroughWindowOnlyCapturesHitsInsideToastRectWhileVisible() {
        let window = RaftIosToastOverlayWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let rootVC = UIViewController()
        rootVC.view.frame = window.bounds
        let contentView = UIView(frame: window.bounds)
        rootVC.view.addSubview(contentView)
        window.rootViewController = rootVC
        window.layoutIfNeeded()

        XCTAssertTrue(window.isHidden, "overlay window starts hidden when idle")
        XCTAssertEqual(window.backgroundColor, .clear)
        XCTAssertFalse(window.isOpaque)
        XCTAssertEqual(window.windowLevel.rawValue, UIWindow.Level.normal.rawValue + 1)

        // Hidden window passes every touch through even if a rect is set.
        window.toastHitRect = CGRect(x: 16, y: 740, width: 358, height: 56)
        XCTAssertNil(window.hitTest(CGPoint(x: 100, y: 760), with: nil))

        // Visible window with nil or empty rect passes every touch through.
        window.isHidden = false
        window.toastHitRect = nil
        XCTAssertNil(window.hitTest(CGPoint(x: 100, y: 760), with: nil))
        window.toastHitRect = .zero
        XCTAssertNil(window.hitTest(CGPoint(x: 100, y: 760), with: nil))

        // Visible window with active toast rect passes touches outside through and captures inside.
        window.toastHitRect = CGRect(x: 16, y: 740, width: 358, height: 56)
        XCTAssertNil(window.hitTest(CGPoint(x: 100, y: 300), with: nil))
        XCTAssertNil(window.hitTest(CGPoint(x: 10, y: 760), with: nil))
        XCTAssertNotNil(window.hitTest(CGPoint(x: 100, y: 760), with: nil))

        // Clearing rect returns immediately to pass-through.
        window.toastHitRect = nil
        XCTAssertNil(window.hitTest(CGPoint(x: 100, y: 760), with: nil))
    }

    func testControllerDefersHideUntilClearFrameAndRejectsStaleBounds() {
        let window = RaftIosToastOverlayWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        var nextFrameHides: [() -> Void] = []
        var fallbackHides: [() -> Void] = []
        let controller = RaftIosToastOverlayWindowController(
            overlayWindow: window,
            scheduleNextFrameHide: { nextFrameHides.append($0) },
            scheduleFallbackHide: { fallbackHides.append($0) }
        )

        // Bounds arriving while hidden / empty must be ignored.
        controller.handleBoundsChanged(hasBounds: true, leftPt: 16, topPt: 740, rightPt: 374, bottomPt: 796)
        XCTAssertNil(window.toastHitRect)
        XCTAssertTrue(window.isHidden)

        // Showing a toast unhides immediately and accepts non-empty bounds.
        controller.handleVisibilityChanged(
            hasToasts: true,
            sceneBounds: CGRect(x: 0, y: 0, width: 390, height: 844),
            userInterfaceStyle: .dark
        )
        XCTAssertFalse(window.isHidden)
        XCTAssertEqual(window.overrideUserInterfaceStyle, .dark)
        controller.handleBoundsChanged(hasBounds: true, leftPt: 16, topPt: 740, rightPt: 374, bottomPt: 796)
        XCTAssertEqual(window.toastHitRect, CGRect(x: 16, y: 740, width: 358, height: 56))

        // Hiding clears hit rect immediately (instant pass-through) but keeps window unhidden
        // until Compose emits its empty clear-frame bounds callback.
        controller.handleVisibilityChanged(
            hasToasts: false,
            sceneBounds: CGRect(x: 0, y: 0, width: 390, height: 844),
            userInterfaceStyle: .dark
        )
        XCTAssertNil(window.toastHitRect, "touches must pass through immediately when toast list empties")
        XCTAssertFalse(window.isHidden, "window waits for Compose clear frame before hiding")
        XCTAssertEqual(fallbackHides.count, 1)

        // Late stale non-empty bounds arriving after hasToasts=false must be rejected.
        controller.handleBoundsChanged(hasBounds: true, leftPt: 16, topPt: 740, rightPt: 374, bottomPt: 796)
        XCTAssertNil(window.toastHitRect)

        // Empty clear-frame bounds schedules the next-frame hide.
        controller.handleBoundsChanged(hasBounds: false, leftPt: 0, topPt: 0, rightPt: 0, bottomPt: 0)
        XCTAssertEqual(nextFrameHides.count, 1)
        XCTAssertFalse(window.isHidden)
        nextFrameHides.removeFirst()()
        XCTAssertTrue(window.isHidden)

        // A new toast arriving before a pending hide runs cancels the stale hide closure.
        controller.handleVisibilityChanged(hasToasts: true, sceneBounds: nil, userInterfaceStyle: .light)
        controller.handleVisibilityChanged(hasToasts: false, sceneBounds: nil, userInterfaceStyle: .light)
        controller.handleBoundsChanged(hasBounds: false, leftPt: 0, topPt: 0, rightPt: 0, bottomPt: 0)
        XCTAssertEqual(nextFrameHides.count, 1)
        controller.handleVisibilityChanged(hasToasts: true, sceneBounds: nil, userInterfaceStyle: .light)
        nextFrameHides.removeFirst()()
        XCTAssertFalse(window.isHidden, "stale hide closure must not hide a newly shown toast")
        for fallback in fallbackHides {
            fallback()
        }
        XCTAssertFalse(window.isHidden, "stale fallback hide closure must not hide a newly shown toast")
    }
}
