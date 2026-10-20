import Foundation
import UIKit
import shared

/// Creates the view controller for a page name on iOS. Every page renders through Compose
/// Multiplatform: a `RaftPageViewController` (page host: navigation, host events, adaptive panes,
/// gestures) embedding the CMP page's controller (`RaftCmpIos.makePage`). Every place that
/// opens a page (`RaftPageRouter`, `HRBridgeModule` openPage, the SwiftUI root) asks this
/// factory. Page names and pageData are passed through untouched.
@objc final class RaftPageControllerFactory: NSObject {
    /// Call once at launch, before the first page is created.
    @objc static func configure() {
        #if DEBUG
        RaftCmpIos.shared.configure(debugBuild: true)
        #else
        RaftCmpIos.shared.configure(debugBuild: false)
        #endif
    }

    @objc(controllerForPageName:pageData:)
    static func controller(pageName: String, pageData: [AnyHashable: Any]) -> UIViewController {
        if !RaftCmpIos.shared.rendersOnCmp(pageName: pageName) {
            // RaftCmpRoot renders the unknown-page surface; keep a diagnostic trail.
            RaftIosDiagnosticsRecord("warn", "SlockCmpHost", "cmp_host_unknown_page page=\(pageName)")
        }
        let container = RaftPageViewController(pageName: pageName, pageData: pageData)
        container.raftInitialBackgroundColor = RaftCmpIos.shared.lastCanvasColor()
        let bridge = RaftCmpBridgeHost()
        bridge.hostController = container
        let navigator = RaftCmpPageNavigator()
        navigator.controller = container
        let listener = RaftPageListener()
        listener.controller = container
        let page = RaftCmpIos.shared.makePage(
            pageName: pageName,
            pageDataJson: jsonText(container.raftPageData),
            bridge: bridge,
            navigator: navigator,
            listener: listener
        )
        container.attachContent(page.viewController) { event, data in
            page.sendEvent(event: event, dataJson: RaftPageControllerFactory.jsonText(data))
        }
        // Full-page back swipe: Compose horizontal content reports back-direction consumption.
        container.raftBackSwipeContentProbe = { point in
            page.contentConsumesBackSwipe(x: Double(point.x), y: Double(point.y))
        }
        return container
    }

    fileprivate static func jsonText(_ object: [AnyHashable: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8)
        else {
            return "{}"
        }
        return text
    }
}

/// Host navigation for one CMP page (Kotlin `RaftCmpIosNavigator`): `RouterModule.openPage` /
/// `closePage` through `RaftPageRouter` (the former Kuikly router handler: identity-only pageData,
/// `slockReplaceCurrentPage`, login replacement dedupe; push, or present when there is no
/// navigation controller; close pops, or dismisses a presented stack).
private final class RaftCmpPageNavigator: NSObject, RaftCmpIosNavigator {
    /// Weak: the page owns the Kotlin host that owns this navigator.
    weak var controller: UIViewController?
    private let router = RaftPageRouter()

    func openPage(pageName: String, pageDataJson: String) {
        let pageData =
            (try? JSONSerialization.jsonObject(with: Data(pageDataJson.utf8))) as? [AnyHashable: Any] ?? [:]
        onMain { [weak self] in
            guard let self, let controller = self.controller else { return }
            self.router.openPage(withName: pageName, pageData: pageData, controller: controller)
        }
    }

    func closePage() {
        onMain { [weak self] in
            guard let self, let controller = self.controller else { return }
            self.router.closePage(controller)
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

/// Page -> host notifications for one CMP page (Kotlin `RaftIosPageListener`).
private final class RaftPageListener: NSObject, RaftIosPageListener {
    /// Weak: the page owns the Kotlin host that owns this listener.
    weak var controller: RaftPageViewController?

    func onDarkThemeResolved(dark: Bool) {
        controller?.raftSetDarkTheme(dark)
    }
}
