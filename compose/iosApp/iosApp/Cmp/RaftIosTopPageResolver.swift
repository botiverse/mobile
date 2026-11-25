import Foundation
import UIKit
import shared

/// Resolves the authoritative top page on an iOS page stack (`RaftCmpPageNavigator.currentTopPage`).
///
/// Pure UIKit + `shared` so both `iosApp` and `iosAppTests` compile and test it directly without
/// needing the full `RaftPageControllerFactory` / ObjC bridging graph.
enum RaftIosTopPageResolver {
    /// Serializes [object] to JSON text, falling back to the subset of JSON-valid string-keyed
    /// entries so route identity keys (`payload`, `instanceId`, `pageName`) are never dropped if a
    /// host context value is not JSON-serializable (e.g. NaN / Inf).
    static func jsonText(_ object: [AnyHashable: Any]) -> String {
        if JSONSerialization.isValidJSONObject(object),
           let data = try? JSONSerialization.data(withJSONObject: object),
           let text = String(data: data, encoding: .utf8) {
            return text
        }
        var safe: [String: Any] = [:]
        for (key, value) in object {
            guard let stringKey = key as? String else { continue }
            if JSONSerialization.isValidJSONObject([stringKey: value]) {
                safe[stringKey] = value
            }
        }
        guard !safe.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: safe),
              let text = String(data: data, encoding: .utf8)
        else {
            return "{}"
        }
        return text
    }

    /// Reads the topmost visible page for [controller]'s stack.
    ///
    /// Always hops synchronously onto the main thread when called off-main (instead of silently
    /// returning `nil`, which would bypass `already_top_page` deduplication after backgrounding).
    /// Walks any active modal presentation above the stack so a covering sheet or presented
    /// `UINavigationController` is reported accurately and never falls through to a covered page.
    static func resolveTopPage(
        from controller: UIViewController?,
        pageExtractor: @escaping (UIViewController) -> (pageName: String, pageData: [AnyHashable: Any])?
    ) -> RaftCmpIosTopPage? {
        if Thread.isMainThread {
            return resolveTopPageOnMain(from: controller, pageExtractor: pageExtractor)
        }
        return DispatchQueue.main.sync {
            resolveTopPageOnMain(from: controller, pageExtractor: pageExtractor)
        }
    }

    private static func resolveTopPageOnMain(
        from controller: UIViewController?,
        pageExtractor: (UIViewController) -> (pageName: String, pageData: [AnyHashable: Any])?
    ) -> RaftCmpIosTopPage? {
        guard let controller else { return nil }
        let baseNav = controller.navigationController
        let initial: UIViewController
        if let baseNav {
            initial = baseNav
        } else if let presented = activePresentedController(of: controller) {
            initial = presented
        } else {
            return nil
        }
        var current: UIViewController = initial
        while let next = activePresentedController(of: current) {
            current = next
        }
        let visibleTop: UIViewController
        if let nav = current as? UINavigationController {
            guard let top = nav.topViewController else { return nil }
            visibleTop = top
        } else {
            visibleTop = current
        }
        guard let extracted = pageExtractor(visibleTop) else {
            // A non-Raft controller sits on top of the stack or covers it modally: report a
            // non-thread top page so Kotlin does not fall back to a covered conversation's
            // visibleRouteIdentity.
            return RaftCmpIosTopPage(pageName: "", pageDataJson: "{}")
        }
        return RaftCmpIosTopPage(
            pageName: extracted.pageName,
            pageDataJson: jsonText(extracted.pageData)
        )
    }

    private static func activePresentedController(of controller: UIViewController) -> UIViewController? {
        guard let presented = controller.presentedViewController, !presented.isBeingDismissed else {
            return nil
        }
        return presented
    }
}
