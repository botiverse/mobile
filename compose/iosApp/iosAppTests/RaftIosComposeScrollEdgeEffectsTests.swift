import ObjectiveC
import UIKit
import XCTest

final class RaftIosComposeScrollEdgeEffectsTests: XCTestCase {
    /// Runtime classes named like Kotlin/Native's exported Compose classes
    /// (`Sharedandroidx.compose.ui.window.ComposeContainerView3`, `...OverlayInputView9`).
    private static func composeClass(_ name: String, superclass: AnyClass) -> NSObject.Type {
        let fullName = "Sharedandroidx.compose.ui.window.\(name)"
        if let existing = NSClassFromString(fullName) as? NSObject.Type {
            return existing
        }
        guard let created = objc_allocateClassPair(superclass, fullName, 0) else {
            fatalError("could not create \(fullName)")
        }
        objc_registerClassPair(created)
        return created as! NSObject.Type
    }

    private static let containerClass = composeClass("RaftTestComposeContainerView", superclass: UIView.self)
    private static let overlayScrollClass = composeClass("RaftTestOverlayInputView", superclass: UIScrollView.self)

    private struct Hierarchy {
        let root: UIView
        let composeScroll: UIScrollView
        let interopScroll: UIScrollView
        let nativeScroll: UIScrollView
    }

    /// page root -> plain UIView -> ComposeContainerView -> OverlayInputView (Compose scroll view)
    ///                                                   -> interop wrapper -> UIScrollView (embedded native)
    ///           -> UIScrollView (native, outside Compose)
    private func makeHierarchy() -> Hierarchy {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        let content = UIView(frame: root.bounds)
        root.addSubview(content)
        let container = Self.containerClass.init() as! UIView
        container.frame = root.bounds
        content.addSubview(container)
        let composeScroll = Self.overlayScrollClass.init() as! UIScrollView
        composeScroll.frame = root.bounds
        container.addSubview(composeScroll)
        let interopWrapper = UIView(frame: CGRect(x: 0, y: 200, width: 402, height: 300))
        container.addSubview(interopWrapper)
        let interopScroll = UIScrollView(frame: interopWrapper.bounds)
        interopWrapper.addSubview(interopScroll)
        let nativeScroll = UIScrollView(frame: root.bounds)
        root.addSubview(nativeScroll)
        return Hierarchy(root: root, composeScroll: composeScroll, interopScroll: interopScroll, nativeScroll: nativeScroll)
    }

    func testRecognizesComposeClassesByRuntimeName() {
        let hierarchy = makeHierarchy()
        XCTAssertTrue(RaftIosComposeScrollEdgeEffects.isComposeView(hierarchy.composeScroll))
        XCTAssertFalse(RaftIosComposeScrollEdgeEffects.isComposeView(hierarchy.nativeScroll))
    }

    func testHidesTopAndBottomEdgeEffectsOnComposeScrollViewsOnly() throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("scroll edge effects are iOS 26+") }
        let hierarchy = makeHierarchy()
        XCTAssertFalse(hierarchy.composeScroll.topEdgeEffect.isHidden)

        RaftIosComposeScrollEdgeEffects.hide(in: hierarchy.root)

        XCTAssertTrue(hierarchy.composeScroll.topEdgeEffect.isHidden)
        XCTAssertTrue(hierarchy.composeScroll.bottomEdgeEffect.isHidden)
        XCTAssertFalse(hierarchy.interopScroll.topEdgeEffect.isHidden, "embedded native content keeps its effect")
        XCTAssertFalse(hierarchy.interopScroll.bottomEdgeEffect.isHidden)
        XCTAssertFalse(hierarchy.nativeScroll.topEdgeEffect.isHidden, "native scroll views outside Compose keep theirs")
        XCTAssertFalse(hierarchy.nativeScroll.bottomEdgeEffect.isHidden)

        // Idempotent: a second layout pass changes nothing.
        RaftIosComposeScrollEdgeEffects.hide(in: hierarchy.root)
        XCTAssertTrue(hierarchy.composeScroll.topEdgeEffect.isHidden)
        XCTAssertFalse(hierarchy.nativeScroll.topEdgeEffect.isHidden)
    }

    func testNoOpBeforeIOS26() {
        let hierarchy = makeHierarchy()
        // Must not crash on any OS; the effect API only exists on iOS 26+.
        RaftIosComposeScrollEdgeEffects.hide(in: hierarchy.root)
    }
}
