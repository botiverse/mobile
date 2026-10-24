import Foundation
import UIKit

/// Socket-side primitives the realtime lifecycle policy drives. `RaftIosRealtimeHost` implements them
/// on its serial queue; tests use a fake socket.
protocol RaftIosRealtimeLifecycleTarget: AnyObject {
    /// Writes one lifecycle diagnostics line through the host's `record` path.
    func recordRealtimeLifecycle(_ event: String)
    /// Publishes shell visibility (`show` / `hide`) to shared.
    func setRealtimeShellVisible(_ visible: Bool)
    /// Closes every socket but keeps its session so it can reconnect on return.
    func disconnectRealtimeForBackground()
    /// Reopens every socket that is `.disconnected` and still has a session.
    func reconnectDisconnectedRealtime()
}

/// Maps UIApplication lifecycle notifications onto realtime host actions.
///
/// `willResignActive` also fires while the app stays in the foreground (Control Center, the app
/// switcher, call banners, permission alerts). It only hides the shell, so read position does not
/// advance while the app is covered; the socket stays up. The socket closes on
/// `didEnterBackground`. `didBecomeActive` reconnects closed sockets and shows the shell again.
final class RaftIosRealtimeLifecyclePolicy {
    static let resignActiveEvent = "resign_active"
    static let enterBackgroundEvent = "enter_background"

    private let notificationCenter: NotificationCenter
    private weak var target: RaftIosRealtimeLifecycleTarget?
    private var observerTokens: [NSObjectProtocol] = []

    init(notificationCenter: NotificationCenter, target: RaftIosRealtimeLifecycleTarget) {
        self.notificationCenter = notificationCenter
        self.target = target
    }

    /// Idempotent. Observers run synchronously on the posting thread; the target hops to its own queue.
    func install() {
        guard observerTokens.isEmpty else { return }
        observerTokens = [
            notificationCenter.addObserver(
                forName: UIApplication.willResignActiveNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.handleResignActive()
            },
            notificationCenter.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.handleEnterBackground()
            },
            notificationCenter.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: nil
            ) { [weak self] _ in
                self?.handleBecomeActive()
            },
        ]
    }

    deinit {
        observerTokens.forEach(notificationCenter.removeObserver)
    }

    private func handleResignActive() {
        guard let target else { return }
        target.recordRealtimeLifecycle(Self.resignActiveEvent)
        target.setRealtimeShellVisible(false)
    }

    private func handleEnterBackground() {
        guard let target else { return }
        target.recordRealtimeLifecycle(Self.enterBackgroundEvent)
        target.disconnectRealtimeForBackground()
    }

    private func handleBecomeActive() {
        guard let target else { return }
        target.reconnectDisconnectedRealtime()
        target.setRealtimeShellVisible(true)
    }
}
