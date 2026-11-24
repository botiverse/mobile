import CoreGraphics
import UIKit
import XCTest

final class RaftIosAdaptivePanePublisherTests: XCTestCase {
    func testPublishesPixelFactsAndFlatPosture() {
        let publisher = RaftIosAdaptivePanePublisher()

        let payload = publisher.nextPayload(
            widthPoints: 1024,
            heightPoints: 1366,
            scale: 2,
            sideColumn: false,
            stripEdge: "none",
            stripWidthPoints: 0,
            nativeTabBarHeightPoints: 0,
            reservedRegions: [],
            display: "other"
        ) as? [String: Any]

        XCTAssertEqual(payload?["widthPx"] as? Int, 2048)
        XCTAssertEqual(payload?["heightPx"] as? Int, 2732)
        XCTAssertEqual(payload?["density"] as? CGFloat, 2)
        XCTAssertEqual(payload?["posture"] as? String, "flat")
        XCTAssertEqual(
            Set(payload?.keys.map { $0 } ?? []),
            Set([
                "widthPx", "heightPx", "density", "posture",
                "sideColumn", "stripEdge", "stripWidthPt", "nativeTabBarHeightPt",
                "reservedRegions", "display",
            ])
        )
    }

    func testDeduplicatesIdenticalFactsButPublishesRotationAndResize() {
        let publisher = RaftIosAdaptivePanePublisher()

        XCTAssertNotNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: [],
            display: "other"
        ))
        XCTAssertNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: [],
            display: "other"
        ))

        let landscape = publisher.nextPayload(
            widthPoints: 1366,
            heightPoints: 1024,
            scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: [],
            display: "other"
        ) as? [String: Any]
        XCTAssertEqual(landscape?["widthPx"] as? Int, 2732)
        XCTAssertEqual(landscape?["heightPx"] as? Int, 2048)

        let splitView = publisher.nextPayload(
            widthPoints: 683,
            heightPoints: 1024,
            scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: [],
            display: "other"
        ) as? [String: Any]
        XCTAssertEqual(splitView?["widthPx"] as? Int, 1366)
        XCTAssertEqual(splitView?["heightPx"] as? Int, 2048)
    }

    func testRejectsInvalidWindowFactsWithoutPoisoningDeduplication() {
        let publisher = RaftIosAdaptivePanePublisher()

        let plain: (
            sideColumn: Bool, stripEdge: String,
            stripWidthPoints: CGFloat, nativeTabBarHeightPoints: CGFloat,
            reservedRegions: [[String: NSNumber]]
        ) = (
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: []
        )
        XCTAssertNil(publisher.nextPayload(
            widthPoints: 0, heightPoints: 1366, scale: 2,
            sideColumn: plain.sideColumn,
            stripEdge: plain.stripEdge, stripWidthPoints: plain.stripWidthPoints,
            nativeTabBarHeightPoints: plain.nativeTabBarHeightPoints,
            reservedRegions: plain.reservedRegions,
            display: "other"
        ))
        XCTAssertNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: .nan, scale: 2,
            sideColumn: plain.sideColumn,
            stripEdge: plain.stripEdge, stripWidthPoints: plain.stripWidthPoints,
            nativeTabBarHeightPoints: plain.nativeTabBarHeightPoints,
            reservedRegions: plain.reservedRegions,
            display: "other"
        ))
        XCTAssertNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 0,
            sideColumn: plain.sideColumn,
            stripEdge: plain.stripEdge, stripWidthPoints: plain.stripWidthPoints,
            nativeTabBarHeightPoints: plain.nativeTabBarHeightPoints,
            reservedRegions: plain.reservedRegions,
            display: "other"
        ))
        XCTAssertNotNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 2,
            sideColumn: plain.sideColumn,
            stripEdge: plain.stripEdge, stripWidthPoints: plain.stripWidthPoints,
            nativeTabBarHeightPoints: plain.nativeTabBarHeightPoints,
            reservedRegions: plain.reservedRegions,
            display: "other"
        ))
    }
}

/// The page host's four-edge window-inset mapping (shared `RaftAppSafeArea.kt` contract).
final class RaftIosWindowInsetsPublisherTests: XCTestCase {
    private func payload(
        _ insets: UIEdgeInsets,
        scale: CGFloat = 3,
        statusBarHidden: Bool = false,
        presentedAsSheet: Bool = false
    ) -> [String: Any] {
        RaftIosWindowInsetsPublisher.payload(
            insets: insets,
            scale: scale,
            statusBarHidden: statusBarHidden,
            presentedAsSheet: presentedAsSheet,
            windowId: "ios:page:test"
        ) as? [String: Any] ?? [:]
    }

    func testPublishesAllFourEdgesInPixels() {
        let values = payload(UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0))

        XCTAssertEqual(values["statusBarHeightPx"] as? String, "177.000")
        XCTAssertEqual(values["navigationBarHeightPx"] as? String, "102.000")
        XCTAssertEqual(values["safeAreaLeftPx"] as? String, "0.000")
        XCTAssertEqual(values["safeAreaRightPx"] as? String, "0.000")
        XCTAssertEqual(values["statusBarHidden"] as? Bool, false)
        XCTAssertEqual(values["presentedAsSheet"] as? Bool, false)
        XCTAssertEqual(values["slockWindowId"] as? String, "ios:page:test")
        XCTAssertEqual(
            Set(values.keys),
            Set([
                "statusBarHeightPx", "navigationBarHeightPx", "safeAreaLeftPx", "safeAreaRightPx",
                "statusBarHidden", "presentedAsSheet", "slockWindowId",
            ])
        )
    }

    func testSideActionsKeepPageBottomSafeArea() {
        let resolved = RaftIosWindowInsetsPublisher.resolvedInsets(
            page: UIEdgeInsets(top: 12, left: 0, bottom: 34, right: 84),
            navigationController: UIEdgeInsets(top: 12, left: 0, bottom: 0, right: 84),
            sideActionsShown: true
        )

        XCTAssertEqual(resolved.top, 12)
        XCTAssertEqual(resolved.bottom, 34)
        XCTAssertEqual(resolved.right, 84)

        let withoutSideActions = RaftIosWindowInsetsPublisher.resolvedInsets(
            page: UIEdgeInsets(top: 0, left: 0, bottom: 34, right: 0),
            navigationController: UIEdgeInsets(),
            sideActionsShown: false
        )
        XCTAssertEqual(withoutSideActions.bottom, 34)
    }

    func testLandscapeLeftAndRightKeepTheSensorHousingOnItsPhysicalEdge() {
        // Dynamic Island iPhone, rotated with the island on the left (device landscape right).
        let islandLeft = payload(UIEdgeInsets(top: 0, left: 59, bottom: 21, right: 0), statusBarHidden: true)
        XCTAssertEqual(islandLeft["safeAreaLeftPx"] as? String, "177.000")
        XCTAssertEqual(islandLeft["safeAreaRightPx"] as? String, "0.000")
        XCTAssertEqual(islandLeft["statusBarHeightPx"] as? String, "0.000")
        XCTAssertEqual(islandLeft["statusBarHidden"] as? Bool, true)

        // Island on the right.
        let islandRight = payload(UIEdgeInsets(top: 0, left: 0, bottom: 21, right: 59), statusBarHidden: true)
        XCTAssertEqual(islandRight["safeAreaLeftPx"] as? String, "0.000")
        XCTAssertEqual(islandRight["safeAreaRightPx"] as? String, "177.000")
        XCTAssertEqual(islandRight["navigationBarHeightPx"] as? String, "63.000")
    }
}
