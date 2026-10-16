import Foundation
import shared

/// Thin iOS transport into the shared external-link coordinator. No native
/// route parser, auth generation, pending route or destination state lives here.
final class RaftIosExternalLinkHost {
    static let shared = RaftIosExternalLinkHost()

    private init() {}

    private let ingress = RaftIosExternalLinkIngress(
        offer: { value in
            _ = SlockExternalLinkCoordinator.shared.offer(
                rawURL: value.rawURL,
                activationId: value.activationId,
                sceneId: value.origin.sceneId,
                shellScopeId: value.origin.shellScopeId,
                requestId: nil
            )
        },
        disconnect: { sceneId in
            SlockExternalLinkCoordinator.shared.disconnect(sceneId: sceneId)
        }
    )

    @discardableResult
    func receive(_ activity: NSUserActivity, sceneId: String, shellScopeId: String) -> Bool {
        ingress.receive(activity, origin: .init(sceneId: sceneId, shellScopeId: shellScopeId))
    }

    func sceneDisconnected(_ sceneId: String) {
        ingress.sceneDisconnected(sceneId)
    }

    func loginStarted(origin: RaftIosExternalLinkOrigin, requestId: String) {
        precondition(Thread.isMainThread)
        _ = SlockExternalLinkCoordinator.shared.loginStarted(
            sceneId: origin.sceneId, shellScopeId: origin.shellScopeId, requestId: requestId
        )
    }

    func loginCancelled(origin: RaftIosExternalLinkOrigin, requestId: String) {
        cancel(origin: origin, requestId: requestId, reason: "login_cancelled")
    }

    func loginFailed(origin: RaftIosExternalLinkOrigin, requestId: String) {
        cancel(origin: origin, requestId: requestId, reason: "browser_failed")
    }

    func loginSuperseded(origin: RaftIosExternalLinkOrigin, requestId: String) {
        cancel(origin: origin, requestId: requestId, reason: "browser_superseded")
    }

    private func cancel(origin: RaftIosExternalLinkOrigin, requestId: String, reason: String) {
        precondition(Thread.isMainThread)
        _ = SlockExternalLinkCoordinator.shared.cancel(
            sceneId: origin.sceneId, shellScopeId: origin.shellScopeId,
            requestId: requestId, reason: reason
        )
    }
}
