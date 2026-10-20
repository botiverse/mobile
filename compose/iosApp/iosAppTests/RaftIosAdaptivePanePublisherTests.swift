import CoreGraphics
import XCTest

final class RaftIosAdaptivePanePublisherTests: XCTestCase {
    func testPublishesPixelFactsAndFlatPosture() {
        let publisher = RaftIosAdaptivePanePublisher()

        let payload = publisher.nextPayload(
            widthPoints: 1024,
            heightPoints: 1366,
            scale: 2
        ) as? [String: Any]

        XCTAssertEqual(payload?["widthPx"] as? Int, 2048)
        XCTAssertEqual(payload?["heightPx"] as? Int, 2732)
        XCTAssertEqual(payload?["density"] as? CGFloat, 2)
        XCTAssertEqual(payload?["posture"] as? String, "flat")
        XCTAssertEqual(
            Set(payload?.keys.map { $0 } ?? []),
            Set(["widthPx", "heightPx", "density", "posture"])
        )
    }

    func testDeduplicatesIdenticalFactsButPublishesRotationAndResize() {
        let publisher = RaftIosAdaptivePanePublisher()

        XCTAssertNotNil(publisher.nextPayload(widthPoints: 1024, heightPoints: 1366, scale: 2))
        XCTAssertNil(publisher.nextPayload(widthPoints: 1024, heightPoints: 1366, scale: 2))

        let landscape = publisher.nextPayload(
            widthPoints: 1366,
            heightPoints: 1024,
            scale: 2
        ) as? [String: Any]
        XCTAssertEqual(landscape?["widthPx"] as? Int, 2732)
        XCTAssertEqual(landscape?["heightPx"] as? Int, 2048)

        let splitView = publisher.nextPayload(
            widthPoints: 683,
            heightPoints: 1024,
            scale: 2
        ) as? [String: Any]
        XCTAssertEqual(splitView?["widthPx"] as? Int, 1366)
        XCTAssertEqual(splitView?["heightPx"] as? Int, 2048)
    }

    func testRejectsInvalidWindowFactsWithoutPoisoningDeduplication() {
        let publisher = RaftIosAdaptivePanePublisher()

        XCTAssertNil(publisher.nextPayload(widthPoints: 0, heightPoints: 1366, scale: 2))
        XCTAssertNil(publisher.nextPayload(widthPoints: 1024, heightPoints: .nan, scale: 2))
        XCTAssertNil(publisher.nextPayload(widthPoints: 1024, heightPoints: 1366, scale: 0))
        XCTAssertNotNil(publisher.nextPayload(widthPoints: 1024, heightPoints: 1366, scale: 2))
    }
}
