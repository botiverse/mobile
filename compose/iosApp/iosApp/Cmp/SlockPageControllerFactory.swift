import Foundation
import UIKit
import shared

/// Kuikly/CMP router split on iOS. Every place that used to create a
/// `KuiklyRenderViewController` for a page name (KRRouterHandler, HRBridgeModule
/// openPage, the SwiftUI root) asks this factory instead: pages in the Kotlin
/// allowlist (`SlockCmpRouter`) get a Compose Multiplatform view controller,
/// everything else the unchanged Kuikly controller. Page names and pageData are
/// passed through untouched.
@objc final class SlockPageControllerFactory: NSObject {
    /// Call once at launch, before the first page is created.
    @objc static func configure() {
        #if DEBUG
        SlockCmpIos.shared.configure(debugBuild: true)
        #else
        SlockCmpIos.shared.configure(debugBuild: false)
        #endif
    }

    @objc(controllerForPageName:pageData:)
    static func controller(pageName: String, pageData: [AnyHashable: Any]) -> UIViewController {
        guard SlockCmpIos.shared.rendersOnCmp(pageName: pageName) else {
            return KuiklyRenderViewController(pageName: pageName, pageData: pageData)
        }
        let navigator = SlockCmpPageNavigator()
        let controller = SlockCmpIos.shared.makeViewController(
            pageName: pageName,
            pageDataJson: jsonText(pageData),
            bridge: SlockCmpBridgeHost(),
            navigator: navigator
        )
        navigator.controller = controller
        return controller
    }

    private static func jsonText(_ pageData: [AnyHashable: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(pageData),
              let data = try? JSONSerialization.data(withJSONObject: pageData),
              let text = String(data: data, encoding: .utf8)
        else {
            return "{}"
        }
        return text
    }
}

/// Host navigation for one CMP page (Kotlin `SlockCmpIosNavigator`), mirroring
/// KRRouterHandler: push on the page's navigation controller (present one when
/// there is none); close pops, or dismisses a presented stack.
private final class SlockCmpPageNavigator: NSObject, SlockCmpIosNavigator {
    /// Weak: the Compose controller owns the Kotlin host that owns this navigator.
    weak var controller: UIViewController?

    func openPage(pageName: String, pageDataJson: String) {
        let pageData =
            (try? JSONSerialization.jsonObject(with: Data(pageDataJson.utf8))) as? [AnyHashable: Any] ?? [:]
        onMain { [weak self] in
            guard let controller = self?.controller else { return }
            let next = SlockPageControllerFactory.controller(pageName: pageName, pageData: pageData)
            if let navigationController = controller.navigationController {
                navigationController.pushViewController(next, animated: true)
            } else {
                controller.present(UINavigationController(rootViewController: next), animated: true)
            }
        }
    }

    func closePage() {
        onMain { [weak self] in
            guard let controller = self?.controller else { return }
            if let navigationController = controller.navigationController,
               navigationController.viewControllers.count > 1 {
                navigationController.popViewController(animated: true)
            } else if controller.presentingViewController != nil {
                controller.dismiss(animated: true)
            } else {
                NSLog("[SlockCmpHost][warn] closePage no-op: missing navigation stack")
            }
        }
    }

    private func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread {
            work()
        } else {
            DispatchQueue.main.async(execute: work)
        }
    }
}
