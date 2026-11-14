import Foundation
import ObjectiveC
import UIKit
import shared

/// The route of a page pushed through `RaftIosPageOpenGate`: its page name and the pageData it was
/// opened with (identity only, before the host adds its per-page context), as JSON text.
@objc final class RaftIosPageRoute: NSObject {
    let pageName: String
    let pageDataJson: String

    init(pageName: String, pageDataJson: String) {
        self.pageName = pageName
        self.pageDataJson = pageDataJson
    }

    convenience init(pageName: String, pageData: [AnyHashable: Any]) {
        self.init(pageName: pageName, pageDataJson: RaftIosPageRoute.jsonText(pageData))
    }

    static func jsonText(_ object: [AnyHashable: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8)
        else {
            return "{}"
        }
        return text
    }
}

/// Push rules of one `UINavigationController` (task #151 follow-up; the Kotlin
/// `RaftNativePageOpenGate` applies the same rules to Android's Activity path). Every push of a
/// page by `RaftPageRouter` and `HRBridgeModule` goes through it, the native navigation (Beta)
/// pages and the shell's notification, banner and shortcut opens included:
///
/// - an open of the route on top is dropped (a second tap, a notification for the page shown);
/// - while the navigation controller runs a transition (a push still sliding in, a back swipe), an
///   open of the route already queued is dropped and another one queued, only the latest kept; it
///   is opened when the transition completes, checked against the top again. A push during a push
///   of the same route is the top route already (UIKit puts the pushed page on top at once);
/// - a replace (`slockReplaceCurrentPage`), the app shell and login are not gated.
///
/// The hold lasts exactly as long as UIKit's transition (its transition coordinator), so no fixed
/// timer delays the next page, and a push without animation holds nothing. Routes compare by the
/// shared route identity (`RaftCmpIos.sameRoute`: the thread page by thread and anchor). Main thread only.
@objc final class RaftIosPageOpenGate: NSObject {
    /// Route equality; the shared route identity in production, replaceable in tests.
    static var sameRoute: (RaftIosPageRoute, RaftIosPageRoute) -> Bool = { a, b in
        RaftCmpIos.shared.sameRoute(
            pageName: a.pageName,
            pageDataJson: a.pageDataJson,
            otherPageName: b.pageName,
            otherPageDataJson: b.pageDataJson
        )
    }

    /// Where the gate's drop / queue lines go (the diagnostics log: `RaftPageControllerFactory.configure`).
    static var log: (String) -> Void = { line in NSLog("[RaftPageRouter] %@", line) }

    /// Pages whose open is never gated: opening them resets or replaces the stack's meaning.
    static let ungatedPageNames: Set<String> = ["compose_slock_app_shell", "compose_login"]

    private static var gateKey: UInt8 = 0
    private static var routeKey: UInt8 = 0

    private weak var navigationController: UINavigationController?
    private var pending: (route: RaftIosPageRoute, push: () -> UIViewController?)?
    private var waitingForTransition = false

    private init(navigationController: UINavigationController) {
        self.navigationController = navigationController
    }

    /// The gate of [navigationController] (one per navigation stack).
    static func gate(for navigationController: UINavigationController) -> RaftIosPageOpenGate {
        if let gate = objc_getAssociatedObject(navigationController, &gateKey) as? RaftIosPageOpenGate {
            return gate
        }
        let gate = RaftIosPageOpenGate(navigationController: navigationController)
        objc_setAssociatedObject(navigationController, &gateKey, gate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return gate
    }

    /// The route [controller] was pushed with through a gate, if any.
    static func route(of controller: UIViewController?) -> RaftIosPageRoute? {
        guard let controller else { return nil }
        return objc_getAssociatedObject(controller, &routeKey) as? RaftIosPageRoute
    }

    static func setRoute(_ route: RaftIosPageRoute, on controller: UIViewController) {
        objc_setAssociatedObject(controller, &routeKey, route, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }

    /// Pushes [pageName] onto [navigationController] with [push] (creates the page, pushes it,
    /// returns it), or drops / queues it (see the type). True when [push] ran now.
    @objc(openPageWithName:pageData:navigationController:push:)
    @discardableResult
    static func open(
        pageName: String,
        pageData: [AnyHashable: Any],
        navigationController: UINavigationController,
        push: @escaping () -> UIViewController?
    ) -> Bool {
        if ungatedPageNames.contains(pageName) {
            _ = push()
            return true
        }
        return gate(for: navigationController).open(RaftIosPageRoute(pageName: pageName, pageData: pageData), push: push)
    }

    private func open(_ route: RaftIosPageRoute, push: @escaping () -> UIViewController?) -> Bool {
        guard let navigationController else { return false }
        if let top = RaftIosPageOpenGate.route(of: navigationController.topViewController),
           RaftIosPageOpenGate.sameRoute(top, route) {
            log("open_dropped page=\(route.pageName) reason=same_route_on_top")
            return false
        }
        if let coordinator = navigationController.transitionCoordinator {
            if let queued = pending, RaftIosPageOpenGate.sameRoute(queued.route, route) {
                log("open_dropped page=\(route.pageName) reason=same_route_queued")
                return false
            }
            if let superseded = pending {
                log("open_dropped page=\(superseded.route.pageName) reason=superseded_in_transition")
            }
            pending = (route, push)
            log("open_queued page=\(route.pageName)")
            if !waitingForTransition {
                waitingForTransition = true
                coordinator.animate(alongsideTransition: nil) { [weak self] _ in
                    self?.transitionEnded()
                }
            }
            return false
        }
        if let pushed = push() {
            RaftIosPageOpenGate.setRoute(route, on: pushed)
        }
        return true
    }

    /// The transition completed (or was cancelled): opens the queued open, checked again.
    func transitionEnded() {
        waitingForTransition = false
        guard let queued = pending else { return }
        pending = nil
        _ = open(queued.route, push: queued.push)
    }

    private func log(_ line: String) {
        RaftIosPageOpenGate.log(line)
    }
}
