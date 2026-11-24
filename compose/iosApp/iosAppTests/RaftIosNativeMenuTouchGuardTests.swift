import ObjectiveC
import UIKit
import XCTest

final class RaftIosNativeMenuTouchGuardTests: XCTestCase {
    private static func dynamicClass(_ fullName: String, superclass: AnyClass) -> NSObject.Type {
        if let existing = NSClassFromString(fullName) as? NSObject.Type {
            return existing
        }
        guard let created = objc_allocateClassPair(superclass, fullName, 0) else {
            fatalError("could not create \(fullName)")
        }
        objc_registerClassPair(created)
        return created as! NSObject.Type
    }

    private static let composeContainerClass = dynamicClass(
        "Sharedandroidx.compose.ui.window.RaftMenuTestComposeContainerView",
        superclass: UIView.self
    )
    private static let composeOverlayScrollClass = dynamicClass(
        "Sharedandroidx.compose.ui.window.RaftMenuTestOverlayInputView",
        superclass: UIScrollView.self
    )
    private static let composeTouchesRecognizerClass = dynamicClass(
        "Sharedandroidx.compose.ui.window.RaftMenuTestTouchesGestureRecognizer",
        superclass: UIGestureRecognizer.self
    )
    private static let contextMenuContainerClass = dynamicClass(
        "_UIContextMenuContainerViewRaftTest",
        superclass: UIView.self
    )
    private static let contextMenuPlatterClass = dynamicClass(
        "_UIContextMenuViewRaftTest",
        superclass: UIView.self
    )
    private static let popoverViewClass = dynamicClass(
        "_UIPopoverViewRaftTest",
        superclass: UIView.self
    )
    private static let editMenuContainerClass = dynamicClass(
        "_UIEditMenuContainerViewRaftTest",
        superclass: UIView.self
    )

    private struct SceneFixture {
        let window: RaftIosWindow
        let navView: UIView
        let navBar: UINavigationBar
        let pageView: UIView
        let composeContainer: UIView
        let overlayInput: UIScrollView
        let interopView: UIView
        let composeTouchesRecognizer: UIGestureRecognizer
        let disabledComposeRecognizer: UIGestureRecognizer
        let interopTapRecognizer: UITapGestureRecognizer
        let navPopRecognizer: UIPanGestureRecognizer
        let windowDismissRecognizer: UITapGestureRecognizer
    }

    private func makeScene() -> SceneFixture {
        let frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        let window = RaftIosWindow(frame: frame)
        let navView = UIView(frame: frame)
        window.addSubview(navView)

        let pageView = UIView(frame: frame)
        navView.addSubview(pageView)

        let composeContainer = Self.composeContainerClass.init() as! UIView
        composeContainer.frame = frame
        pageView.addSubview(composeContainer)

        let overlayInput = Self.composeOverlayScrollClass.init() as! UIScrollView
        overlayInput.frame = frame
        composeContainer.addSubview(overlayInput)

        let interopView = UIView(frame: CGRect(x: 16, y: 200, width: 358, height: 120))
        composeContainer.addSubview(interopView)

        let navBar = UINavigationBar(frame: CGRect(x: 0, y: 44, width: 390, height: 44))
        navView.addSubview(navBar)

        let composeTouchesRecognizer = Self.composeTouchesRecognizerClass.init() as! UIGestureRecognizer
        overlayInput.addGestureRecognizer(composeTouchesRecognizer)

        let disabledComposeRecognizer = Self.composeTouchesRecognizerClass.init() as! UIGestureRecognizer
        disabledComposeRecognizer.isEnabled = false
        overlayInput.addGestureRecognizer(disabledComposeRecognizer)

        let interopTapRecognizer = UITapGestureRecognizer()
        interopView.addGestureRecognizer(interopTapRecognizer)

        let navPopRecognizer = UIPanGestureRecognizer()
        navView.addGestureRecognizer(navPopRecognizer)

        let windowDismissRecognizer = UITapGestureRecognizer()
        window.addGestureRecognizer(windowDismissRecognizer)

        return SceneFixture(
            window: window,
            navView: navView,
            navBar: navBar,
            pageView: pageView,
            composeContainer: composeContainer,
            overlayInput: overlayInput,
            interopView: interopView,
            composeTouchesRecognizer: composeTouchesRecognizer,
            disabledComposeRecognizer: disabledComposeRecognizer,
            interopTapRecognizer: interopTapRecognizer,
            navPopRecognizer: navPopRecognizer,
            windowDismissRecognizer: windowDismissRecognizer
        )
    }

    private func makeVisibleContextMenuContainer(frame: CGRect) -> (container: UIView, platter: UIView) {
        let container = Self.contextMenuContainerClass.init() as! UIView
        container.frame = frame
        let platter = Self.contextMenuPlatterClass.init() as! UIView
        platter.frame = CGRect(x: 170, y: 92, width: 204, height: 180)
        container.addSubview(platter)
        return (container, platter)
    }

    func testDetectsActiveContextMenuAndPopoverAndIgnoresDismissingOrEditMenus() {
        let scene = makeScene()
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]))

        // Text edit menu (Copy/Paste callout) must not trigger menu-dismissal suppression.
        let editMenu = Self.editMenuContainerClass.init() as! UIView
        editMenu.frame = CGRect(x: 40, y: 120, width: 160, height: 40)
        let editContent = UIView(frame: editMenu.bounds)
        editMenu.addSubview(editContent)
        scene.window.addSubview(editMenu)
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]))
        editMenu.removeFromSuperview()

        // Active pull-down UIMenu container with visible platter.
        let (menuContainer, platter) = makeVisibleContextMenuContainer(frame: scene.window.bounds)
        scene.window.addSubview(menuContainer)
        XCTAssertTrue(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]))

        // Dismissing container (interaction disabled) stops suppressing immediately.
        menuContainer.isUserInteractionEnabled = false
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]))
        menuContainer.isUserInteractionEnabled = true

        // Fading-out platter (model alpha = 0) stops suppressing immediately.
        platter.alpha = 0
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]))
        platter.alpha = 1

        // Hidden container stops suppressing immediately.
        menuContainer.isHidden = true
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]))
        menuContainer.removeFromSuperview()

        // Context menu in a sibling overlay window is also detected.
        let overlayWindow = UIWindow(frame: scene.window.bounds)
        let (overlayMenu, _) = makeVisibleContextMenuContainer(frame: overlayWindow.bounds)
        overlayWindow.addSubview(overlayMenu)
        XCTAssertTrue(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window, overlayWindow]))

        // Native iPad popover view is detected.
        let popover = Self.popoverViewClass.init() as! UIView
        popover.frame = CGRect(x: 80, y: 100, width: 320, height: 400)
        scene.window.addSubview(popover)
        XCTAssertTrue(RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]))
    }

    func testComposeAncestryAndGestureRecognizerFilteringKeepUiKitRecognizersEnabled() {
        let scene = makeScene()
        let (menuContainer, platter) = makeVisibleContextMenuContainer(frame: scene.window.bounds)
        scene.window.addSubview(menuContainer)

        XCTAssertTrue(RaftIosNativeMenuTouchGuard.isInsideComposeView(scene.overlayInput))
        XCTAssertTrue(RaftIosNativeMenuTouchGuard.isInsideComposeView(scene.interopView))
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.isInsideComposeView(scene.navBar))
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.isInsideComposeView(platter))
        XCTAssertFalse(RaftIosNativeMenuTouchGuard.isInsideComposeView(nil))

        let overlayRecognizers = RaftIosNativeMenuTouchGuard.composeGestureRecognizersToSuppress(for: scene.overlayInput)
        XCTAssertTrue(overlayRecognizers.contains(where: { $0 === scene.composeTouchesRecognizer }))
        XCTAssertFalse(overlayRecognizers.contains(where: { $0 === scene.disabledComposeRecognizer }))
        XCTAssertFalse(overlayRecognizers.contains(where: { $0 === scene.navPopRecognizer }))
        XCTAssertFalse(overlayRecognizers.contains(where: { $0 === scene.windowDismissRecognizer }))

        let interopRecognizers = RaftIosNativeMenuTouchGuard.composeGestureRecognizersToSuppress(for: scene.interopView)
        XCTAssertTrue(interopRecognizers.contains(where: { $0 === scene.interopTapRecognizer }))
        XCTAssertFalse(interopRecognizers.contains(where: { $0 === scene.navPopRecognizer }))
        XCTAssertFalse(interopRecognizers.contains(where: { $0 === scene.windowDismissRecognizer }))

        XCTAssertTrue(RaftIosNativeMenuTouchGuard.composeGestureRecognizersToSuppress(for: platter).isEmpty)
        XCTAssertTrue(RaftIosNativeMenuTouchGuard.composeGestureRecognizersToSuppress(for: scene.navBar).isEmpty)
    }

    func testWindowSuppressesComposeOnlyDuringOutsideMenuDismissalAndRestoresImmediately() {
        let scene = makeScene()
        let (menuContainer, platter) = makeVisibleContextMenuContainer(frame: scene.window.bounds)
        scene.window.addSubview(menuContainer)

        let event = UIEvent()
        let outsideTouchToken = NSObject()
        let nextTouchToken = NSObject()
        let platterTouchToken = NSObject()
        let outsideTouchId = ObjectIdentifier(outsideTouchToken)
        var composeEnabledDuringForward: Bool?
        var windowDismissEnabledDuringForward: Bool?

        // Outside tap (.began on Compose while menu is open) suppresses Compose during forward,
        // keeps UIKit's dismissal recognizer enabled, and restores Compose immediately afterward.
        scene.window.dispatchTouchSnapshots(
            [RaftIosWindowTouchSnapshot(id: outsideTouchId, view: scene.overlayInput, phase: .began)],
            event: event,
            timestamp: 100.0,
            activeMenuCheck: { RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]) },
            forward: {
                composeEnabledDuringForward = scene.composeTouchesRecognizer.isEnabled
                windowDismissEnabledDuringForward = scene.windowDismissRecognizer.isEnabled
            }
        )
        XCTAssertEqual(composeEnabledDuringForward, false)
        XCTAssertEqual(windowDismissEnabledDuringForward, true)
        XCTAssertTrue(scene.composeTouchesRecognizer.isEnabled, "re-enabled immediately in defer")
        XCTAssertFalse(scene.disabledComposeRecognizer.isEnabled, "previously disabled recognizer stays disabled")

        // Belt-and-suspenders on .ended if the suppressed touch still reports a Compose recognizer.
        menuContainer.removeFromSuperview()
        var composeEnabledDuringEnded: Bool?
        scene.window.dispatchTouchSnapshots(
            [
                RaftIosWindowTouchSnapshot(
                    id: outsideTouchId,
                    view: scene.overlayInput,
                    phase: .ended,
                    gestureRecognizers: [scene.composeTouchesRecognizer, scene.windowDismissRecognizer]
                )
            ],
            event: event,
            timestamp: 100.05,
            activeMenuCheck: { RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]) },
            forward: {
                composeEnabledDuringEnded = scene.composeTouchesRecognizer.isEnabled
                windowDismissEnabledDuringForward = scene.windowDismissRecognizer.isEnabled
            }
        )
        XCTAssertEqual(composeEnabledDuringEnded, false)
        XCTAssertEqual(windowDismissEnabledDuringForward, true)
        XCTAssertTrue(scene.composeTouchesRecognizer.isEnabled)

        // Next tap after menu dismissal goes straight to Compose untouched.
        let nextTouchId = ObjectIdentifier(nextTouchToken)
        var composeEnabledOnNextTap: Bool?
        scene.window.dispatchTouchSnapshots(
            [RaftIosWindowTouchSnapshot(id: nextTouchId, view: scene.overlayInput, phase: .began)],
            event: event,
            timestamp: 101.0,
            activeMenuCheck: { RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]) },
            forward: { composeEnabledOnNextTap = scene.composeTouchesRecognizer.isEnabled }
        )
        XCTAssertEqual(composeEnabledOnNextTap, true)

        // Tap inside the open menu platter leaves Compose untouched too.
        scene.window.addSubview(menuContainer)
        let platterTouchId = ObjectIdentifier(platterTouchToken)
        var composeEnabledOnPlatterTap: Bool?
        scene.window.dispatchTouchSnapshots(
            [RaftIosWindowTouchSnapshot(id: platterTouchId, view: platter, phase: .began)],
            event: event,
            timestamp: 102.0,
            activeMenuCheck: { RaftIosNativeMenuTouchGuard.hasActiveNativeMenuPresentation(in: [scene.window]) },
            forward: { composeEnabledOnPlatterTap = scene.composeTouchesRecognizer.isEnabled }
        )
        XCTAssertEqual(composeEnabledOnPlatterTap, true)
        withExtendedLifetime((outsideTouchToken, nextTouchToken, platterTouchToken)) {}
    }

    func testHitTestCapturesMenuActiveBeforeSynchronousDismissalAndIgnoresStaleTimestamp() {
        let scene = makeScene()
        let event = UIEvent()
        let firstTouchToken = NSObject()
        let secondTouchToken = NSObject()

        // Simulate UIKit dismissing the menu during hitTest so activeMenuCheck() is already false
        // when sendEvent runs for the exact same event + timestamp.
        scene.window.recordTouchHitTest(
            event: event,
            timestamp: 200.0,
            menuWasActive: true,
            hitView: scene.overlayInput
        )
        var composeEnabledDuringForward: Bool?
        scene.window.dispatchTouchSnapshots(
            [RaftIosWindowTouchSnapshot(id: ObjectIdentifier(firstTouchToken), view: scene.overlayInput, phase: .began)],
            event: event,
            timestamp: 200.0,
            activeMenuCheck: { false },
            forward: { composeEnabledDuringForward = scene.composeTouchesRecognizer.isEnabled }
        )
        XCTAssertEqual(composeEnabledDuringForward, false)
        XCTAssertTrue(scene.composeTouchesRecognizer.isEnabled)

        // A later tap reusing the same singleton UIEvent with a different timestamp is never suppressed.
        scene.window.recordTouchHitTest(
            event: event,
            timestamp: 201.0,
            menuWasActive: true,
            hitView: scene.overlayInput
        )
        var composeEnabledOnDifferentTimestamp: Bool?
        scene.window.dispatchTouchSnapshots(
            [RaftIosWindowTouchSnapshot(id: ObjectIdentifier(secondTouchToken), view: scene.overlayInput, phase: .began)],
            event: event,
            timestamp: 202.0,
            activeMenuCheck: { false },
            forward: { composeEnabledOnDifferentTimestamp = scene.composeTouchesRecognizer.isEnabled }
        )
        XCTAssertEqual(composeEnabledOnDifferentTimestamp, true)
        withExtendedLifetime((firstTouchToken, secondTouchToken)) {}
    }
}
