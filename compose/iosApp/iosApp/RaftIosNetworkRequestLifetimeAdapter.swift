import Foundation
import UIKit

/// Process-level visibility adapter for the shared NetworkKMM request-lifetime owner.
///
/// This intentionally observes only the two process-boundary notifications. Realtime's
/// active/resign-active signals and SwiftUI scenePhase are page/presentation signals and must not
/// retire process-owned HTTP attempts.
final class RaftIosNetworkRequestLifetimeAdapter {
    typealias ApplicationStateProvider = () -> UIApplication.State
    typealias VisibilitySink = (Bool) -> Void

    private let notificationCenter: NotificationCenter
    private let applicationState: ApplicationStateProvider
    private let visibilitySink: VisibilitySink

    // Every access below is serialized through the main queue by install() or by observers whose
    // delivery queue is OperationQueue.main.
    private var installed = false
    private var lastForeground: Bool?
    private var observerTokens: [NSObjectProtocol] = []

    init(
        notificationCenter: NotificationCenter,
        applicationState: @escaping ApplicationStateProvider,
        visibilitySink: @escaping VisibilitySink
    ) {
        self.notificationCenter = notificationCenter
        self.applicationState = applicationState
        self.visibilitySink = visibilitySink
    }

    func install() {
        runOnMainSync {
            guard !installed else {
                return
            }
            installed = true

            observerTokens = [
                notificationCenter.addObserver(
                    forName: UIApplication.didEnterBackgroundNotification,
                    object: nil,
                    queue: .main
                ) { [weak self] _ in
                    self?.publish(foreground: false)
                },
                notificationCenter.addObserver(
                    forName: UIApplication.willEnterForegroundNotification,
                    object: nil,
                    queue: .main
                ) { [weak self] _ in
                    self?.publish(foreground: true)
                },
            ]

            publish(foreground: applicationState() != .background)
        }
    }

    deinit {
        observerTokens.forEach(notificationCenter.removeObserver)
    }

    private func publish(foreground: Bool) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard lastForeground != foreground else {
            return
        }
        lastForeground = foreground
        visibilitySink(foreground)
    }

    private func runOnMainSync(_ block: () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.sync(execute: block)
        }
    }
}
