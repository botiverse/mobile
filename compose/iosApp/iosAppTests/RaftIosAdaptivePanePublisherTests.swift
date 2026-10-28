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
            reservedRegions: []
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
                "reservedRegions",
            ])
        )
    }

    func testDeduplicatesIdenticalFactsButPublishesRotationAndResize() {
        let publisher = RaftIosAdaptivePanePublisher()

        XCTAssertNotNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: []
        ))
        XCTAssertNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: []
        ))

        let landscape = publisher.nextPayload(
            widthPoints: 1366,
            heightPoints: 1024,
            scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: []
        ) as? [String: Any]
        XCTAssertEqual(landscape?["widthPx"] as? Int, 2732)
        XCTAssertEqual(landscape?["heightPx"] as? Int, 2048)

        let splitView = publisher.nextPayload(
            widthPoints: 683,
            heightPoints: 1024,
            scale: 2,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: []
        ) as? [String: Any]
        XCTAssertEqual(splitView?["widthPx"] as? Int, 1366)
        XCTAssertEqual(splitView?["heightPx"] as? Int, 2048)
    }

    func testSideColumnFactsArePublishedAsGiven() {
        let publisher = RaftIosAdaptivePanePublisher()

        // No side column: the window uses a horizontal top bar.
        let none = publisher.nextPayload(
            widthPoints: 402, heightPoints: 874, scale: 3,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0, reservedRegions: []
        ) as? [String: Any]
        XCTAssertEqual(none?["sideColumn"] as? Bool, false)
        XCTAssertEqual(none?["stripEdge"] as? String, "none")
        XCTAssertEqual(none?["stripWidthPt"] as? CGFloat, 0)

        // A side column: the strip's edge, width and the tab bar's height are published.
        let duo = publisher.nextPayload(
            widthPoints: 466, heightPoints: 678, scale: 3,
            sideColumn: true, stripEdge: "trailing",
            stripWidthPoints: 54, nativeTabBarHeightPoints: 120, reservedRegions: []
        ) as? [String: Any]
        XCTAssertEqual(duo?["sideColumn"] as? Bool, true)
        XCTAssertEqual(duo?["stripEdge"] as? String, "trailing")
        XCTAssertEqual(duo?["stripWidthPt"] as? CGFloat, 54)
        XCTAssertEqual(duo?["nativeTabBarHeightPt"] as? CGFloat, 120)
    }

    func testReservedRegionsArePublishedAsWindowPointRects() {
        let publisher = RaftIosAdaptivePanePublisher()

        let payload = publisher.nextPayload(
            widthPoints: 669, heightPoints: 951, scale: 3,
            sideColumn: false, stripEdge: "none",
            stripWidthPoints: 0, nativeTabBarHeightPoints: 0,
            reservedRegions: [
                ["leftPt": 645.0, "topPt": 0.0, "widthPt": 24.0, "heightPt": 951.0],
                ["leftPt": 0.0, "topPt": 0.0, "widthPt": 669.0, "heightPt": 20.0],
            ]
        ) as? [String: Any]

        let regions = payload?["reservedRegions"] as? [[String: NSNumber]]
        XCTAssertEqual(regions?.count, 2)
        XCTAssertEqual(regions?.first?["leftPt"] as? CGFloat, 645.0)
        XCTAssertEqual(regions?.first?["heightPt"] as? CGFloat, 951.0)
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
            reservedRegions: plain.reservedRegions
        ))
        XCTAssertNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: .nan, scale: 2,
            sideColumn: plain.sideColumn,
            stripEdge: plain.stripEdge, stripWidthPoints: plain.stripWidthPoints,
            nativeTabBarHeightPoints: plain.nativeTabBarHeightPoints,
            reservedRegions: plain.reservedRegions
        ))
        XCTAssertNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 0,
            sideColumn: plain.sideColumn,
            stripEdge: plain.stripEdge, stripWidthPoints: plain.stripWidthPoints,
            nativeTabBarHeightPoints: plain.nativeTabBarHeightPoints,
            reservedRegions: plain.reservedRegions
        ))
        XCTAssertNotNil(publisher.nextPayload(
            widthPoints: 1024, heightPoints: 1366, scale: 2,
            sideColumn: plain.sideColumn,
            stripEdge: plain.stripEdge, stripWidthPoints: plain.stripWidthPoints,
            nativeTabBarHeightPoints: plain.nativeTabBarHeightPoints,
            reservedRegions: plain.reservedRegions
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

    func testSheetPageReportsItsOwnZeroTopAndTheSheetFact() {
        // A page sheet's content: the sheet's own top safe area is 0, not the status bar.
        let values = payload(UIEdgeInsets(top: 0, left: 0, bottom: 34, right: 0), presentedAsSheet: true)

        XCTAssertEqual(values["statusBarHeightPx"] as? String, "0.000")
        XCTAssertEqual(values["presentedAsSheet"] as? Bool, true)
        XCTAssertTrue(RaftIosWindowInsetsPublisher.isSheetStyle(.pageSheet))
        XCTAssertTrue(RaftIosWindowInsetsPublisher.isSheetStyle(.formSheet))
        XCTAssertFalse(RaftIosWindowInsetsPublisher.isSheetStyle(.fullScreen))
        XCTAssertFalse(RaftIosWindowInsetsPublisher.isSheetStyle(.overFullScreen))
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

    func testRightEdgeStatusBarGivesSmallTopAndLargeRight() {
        // iPhone Duo inner display: the status bar runs down the right edge. Only the safe area
        // is published, so the top stays small whatever the status bar frame's height is.
        let values = payload(UIEdgeInsets(top: 12, left: 0, bottom: 20, right: 54), scale: 3)

        XCTAssertEqual(values["statusBarHeightPx"] as? String, "36.000")
        XCTAssertEqual(values["safeAreaRightPx"] as? String, "162.000")
        XCTAssertEqual(values["safeAreaLeftPx"] as? String, "0.000")
    }

    func testInvalidValuesClampToZeroAndScaleToOne() {
        XCTAssertEqual(RaftIosWindowInsetsPublisher.pixelString(-4, scale: 2), "0.000")
        XCTAssertEqual(RaftIosWindowInsetsPublisher.pixelString(.nan, scale: 2), "0.000")
        XCTAssertEqual(RaftIosWindowInsetsPublisher.pixelString(10, scale: 0), "10.000")
        XCTAssertEqual(RaftIosWindowInsetsPublisher.pixelString(10, scale: .infinity), "10.000")
    }

    func testDeduplicatesUnchangedPayloads() {
        let publisher = RaftIosWindowInsetsPublisher()
        let insets = UIEdgeInsets(top: 0, left: 59, bottom: 21, right: 0)

        XCTAssertNotNil(publisher.nextPayload(insets: insets, scale: 3, statusBarHidden: true, presentedAsSheet: false, windowId: "w"))
        XCTAssertNil(publisher.nextPayload(insets: insets, scale: 3, statusBarHidden: true, presentedAsSheet: false, windowId: "w"))
        // Rotating to the other landscape moves the inset to the right edge: a new payload.
        let rotated = UIEdgeInsets(top: 0, left: 0, bottom: 21, right: 59)
        let payload = publisher.nextPayload(insets: rotated, scale: 3, statusBarHidden: true, presentedAsSheet: false, windowId: "w")
            as? [String: Any]
        XCTAssertEqual(payload?["safeAreaRightPx"] as? String, "177.000")
    }

    func testPageDataSeedCarriesAllFourEdges() {
        let seed = RaftIosWindowInsetsPublisher.pageDataSeed(
            insets: UIEdgeInsets(top: 12, left: 0, bottom: 20, right: 54),
            scale: 2
        ) as? [String: Any]

        XCTAssertEqual(seed?["safeAreaTop"] as? String, "24.000")
        XCTAssertEqual(seed?["safeAreaBottom"] as? String, "40.000")
        XCTAssertEqual(seed?["safeAreaLeft"] as? String, "0.000")
        XCTAssertEqual(seed?["safeAreaRight"] as? String, "108.000")
        XCTAssertNil(seed?["statusBarHeight"])
    }
}
