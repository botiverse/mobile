import Foundation
import XCTest

// The production logger is Objective-C/iOS. These transport tests only replace
// that sink, not the notification adapter, parser, or pending queue under test.
func SlockIosDiagnosticsRecord(_ level: String, _ tag: String, _ message: String) {}

final class SlockIosPermalinkSceneHostTests: XCTestCase {
    func testStringifyWireRoutesOnlyToOwningWindowAndDuplicateAckStopsRetry() {
        let center = NotificationCenter()
        let one = SlockIosPermalinkSceneHost(center: center)
        let two = SlockIosPermalinkSceneHost(center: center)
        let firstScope = one.bindRoot(revision: "root")
        let secondScope = two.bindRoot(revision: "root")
        XCTAssertNotEqual(firstScope, secondScope)
        var deliveries: [[String: Any]] = []
        let observer = center.addObserver(forName: Notification.Name("slockExternalPermalinkOpen"), object: nil, queue: nil) {
            deliveries.append($0.userInfo as? [String: Any] ?? [:])
        }
        defer { center.removeObserver(observer) }
        one.accept(activity())
        post(center, "Ready", ["shellScopeId": secondScope, "authGeneration": "u:1"])
        XCTAssertTrue(deliveries.isEmpty)
        post(center, "Ready", ["shellScopeId": firstScope, "authGeneration": "u:1"])
        XCTAssertEqual(deliveries.count, 1)
        let id = deliveries[0]["activationId"] as! String
        post(center, "Accepted", ["shellScopeId": secondScope, "authGeneration": "u:1",
                                 "activationId": id, "status": "accepted_duplicate"])
        one.resume()
        XCTAssertEqual(deliveries.count, 2)
        post(center, "Accepted", ["shellScopeId": firstScope, "authGeneration": "u:1",
                                 "activationId": id, "status": "accepted_duplicate"])
        one.resume()
        XCTAssertEqual(deliveries.count, 2)
        XCTAssertNil(deliveries[0]["messageId"])
        XCTAssertNotNil(deliveries[0]["receivedAtMillis"] as? String)
    }

    func testRepeatedActivityObjectReusesActivationButNewUserActivityDoesNot() {
        let center = NotificationCenter()
        let host = SlockIosPermalinkSceneHost(center: center)
        let scope = host.bindRoot(revision: "root")
        var ids: [String] = []
        let observer = center.addObserver(forName: Notification.Name("slockExternalPermalinkOpen"), object: nil, queue: nil) {
            ids.append($0.userInfo?["activationId"] as! String)
        }
        defer { center.removeObserver(observer) }
        post(center, "Ready", ["shellScopeId": scope, "authGeneration": "u:1"])
        let first = activity()
        host.accept(first)
        host.accept(first)
        XCTAssertEqual(ids.count, 2)
        XCTAssertEqual(ids[0], ids[1])
        post(center, "Accepted", ["shellScopeId": scope, "authGeneration": "u:1",
                                 "activationId": ids[0], "status": "accepted_first_time"])
        host.accept(activity())
        XCTAssertEqual(ids.count, 3)
        XCTAssertNotEqual(ids[0], ids[2])
    }

    func testVisibilityFollowsWindowAndRetiresReplacedRoot() {
        var events: [(String, Bool, Int)] = []
        let host = SlockIosPermalinkSceneHost(center: NotificationCenter(), visibilitySink: {
            events.append(($0, $1, $2))
        })
        let first = host.bindRoot(revision: "one")
        host.setVisible(true)
        host.setVisible(true)
        let second = host.bindRoot(revision: "two")
        host.setVisible(false)
        XCTAssertEqual(events.map { $0.0 }, [first, first, first, second, second])
        XCTAssertEqual(events.map { $0.1 }, [false, true, false, true, false])
        XCTAssertEqual(events.map { $0.2 }, [0, 1, 1, 1, 1])
    }

    func testExplicitLoginCancellationPreventsReplayAtLaterLogin() {
        let center = NotificationCenter()
        let host = SlockIosPermalinkSceneHost(center: center)
        let scope = host.bindRoot(revision: "root")
        var deliveries = 0
        let observer = center.addObserver(forName: Notification.Name("slockExternalPermalinkOpen"), object: nil, queue: nil) {
            _ in deliveries += 1
        }
        defer { center.removeObserver(observer) }
        host.accept(activity())
        post(center, "NotReady", ["shellScopeId": scope, "reason": "login_cancelled"])
        post(center, "Ready", ["shellScopeId": scope, "authGeneration": "next:1"])
        host.resume()
        XCTAssertEqual(deliveries, 0)
        host.accept(activity())
        XCTAssertEqual(deliveries, 1)
        post(center, "NotReady", ["shellScopeId": scope, "authGeneration": "next:1", "reason": "login_cancelled"])
        host.resume()
        XCTAssertEqual(deliveries, 1)
        post(center, "Ready", ["shellScopeId": scope, "authGeneration": "next:1"])
        XCTAssertEqual(deliveries, 2, "Bound ownership is retired by auth generation, not login cancellation")
    }

    private func activity() -> NSUserActivity {
        let activity = NSUserActivity(activityType: NSUserActivityTypeBrowsingWeb)
        activity.webpageURL = URL(string: "https://app.raft.build/s/test/channel/c")!
        return activity
    }
    private func post(_ center: NotificationCenter, _ suffix: String, _ payload: [String: String]) {
        let json = String(data: try! JSONSerialization.data(withJSONObject: payload), encoding: .utf8)!
        center.post(name: Notification.Name("slockExternalPermalink" + suffix), object: nil, userInfo: ["stringify": json])
    }
}
