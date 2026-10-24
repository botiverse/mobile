import XCTest
import UIKit

final class RaftIosRealtimeLifecyclePolicyTests: XCTestCase {
    func testResignActiveAloneHidesShellButKeepsSocketConnected() {
        let notificationCenter = NotificationCenter()
        let socket = FakeRealtimeSocket()
        let policy = RaftIosRealtimeLifecyclePolicy(notificationCenter: notificationCenter, target: socket)
        policy.install()

        notificationCenter.post(name: UIApplication.willResignActiveNotification, object: nil)

        XCTAssertTrue(socket.connected, "Control Center / banners must not drop the socket")
        XCTAssertEqual(socket.disconnects, 0)
        XCTAssertEqual(socket.visibility, [false])
        XCTAssertEqual(socket.diagnostics, ["resign_active"])

        // Overlay dismissed without leaving the foreground: shown again, no reconnect needed.
        notificationCenter.post(name: UIApplication.didBecomeActiveNotification, object: nil)

        XCTAssertTrue(socket.connected)
        XCTAssertEqual(socket.connects, 0)
        XCTAssertEqual(socket.visibility, [false, true])
    }

    func testEnterBackgroundDisconnectsAndBecomeActiveReconnects() {
        let notificationCenter = NotificationCenter()
        let socket = FakeRealtimeSocket()
        let policy = RaftIosRealtimeLifecyclePolicy(notificationCenter: notificationCenter, target: socket)
        policy.install()
        policy.install()

        notificationCenter.post(name: UIApplication.willResignActiveNotification, object: nil)
        notificationCenter.post(name: UIApplication.didEnterBackgroundNotification, object: nil)

        XCTAssertFalse(socket.connected, "entering the background closes the socket")
        XCTAssertEqual(socket.disconnects, 1)
        XCTAssertEqual(socket.diagnostics, ["resign_active", "enter_background"])

        notificationCenter.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        notificationCenter.post(name: UIApplication.didBecomeActiveNotification, object: nil)

        XCTAssertTrue(socket.connected, "becoming active reconnects the background-closed socket")
        XCTAssertEqual(socket.connects, 1)
        XCTAssertEqual(socket.visibility, [false, true])
    }
}

/// One socket with a kept session: disconnect closes it, reconnect reopens only a closed one.
private final class FakeRealtimeSocket: RaftIosRealtimeLifecycleTarget {
    var connected = true
    var connects = 0
    var disconnects = 0
    var visibility: [Bool] = []
    var diagnostics: [String] = []

    func recordRealtimeLifecycle(_ event: String) {
        diagnostics.append(event)
    }

    func setRealtimeShellVisible(_ visible: Bool) {
        visibility.append(visible)
    }

    func disconnectRealtimeForBackground() {
        guard connected else { return }
        connected = false
        disconnects += 1
    }

    func reconnectDisconnectedRealtime() {
        guard !connected else { return }
        connected = true
        connects += 1
    }
}
