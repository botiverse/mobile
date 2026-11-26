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
        RaftIosPageOpenGate.log = { line in RaftIosDiagnosticsRecord("info", "RaftPageRouter", line) }
    }

    @objc(controllerForPageName:pageData:)
    static func controller(pageName: String, pageData: [AnyHashable: Any]) -> UIViewController {
        if !RaftCmpIos.shared.rendersOnCmp(pageName: pageName) {
            // RaftCmpRoot renders the unknown-page surface; keep a diagnostic trail.
            RaftIosDiagnosticsRecord("warn", "SlockCmpHost", "cmp_host_unknown_page page=\(pageName)")
        }
        // The app shell's bottom tabs are drawn by a system UITabBarController
        // (RaftIosTabBarController); shared finds its bridge through the host id in pageData.
        // Any other page drops an inherited id so it never publishes into a shell's bar.
        var hostPageData = pageData
        hostPageData.removeValue(forKey: RaftIosTabBarController.hostIdPageDataKey)
        var nativeTabBar: (hostId: String, bridge: RaftNativeTabBarBridge)?
        hostPageData.removeValue(forKey: RaftNativeServerSwitcherKt.RaftNativeServerSwitcherHostIdPageDataKey)
        var nativeServerSwitcher: (hostId: String, bridge: RaftNativeServerSwitcherBridge)?
        if RaftIosTabBarController.hostsNativeTabBar(pageName: pageName) {
            let hostId = UUID().uuidString
            let bridge = RaftNativeTabBarBridge()
            RaftNativeTabBarRegistry.shared.register(hostId: hostId, bridge: bridge)
            hostPageData[RaftIosTabBarController.hostIdPageDataKey] = hostId
            nativeTabBar = (hostId, bridge)
            // iOS 26+ only (#proj-mobile:#369): default-on in-place Liquid Glass Home server switcher.
            if #available(iOS 26.0, *) {
                let switcherHostId = UUID().uuidString
                let switcherBridge = RaftNativeServerSwitcherBridge()
                RaftNativeServerSwitcherRegistry.shared.register(hostId: switcherHostId, bridge: switcherBridge)
                hostPageData[RaftNativeServerSwitcherKt.RaftNativeServerSwitcherHostIdPageDataKey] = switcherHostId
                nativeServerSwitcher = (switcherHostId, switcherBridge)
            }
        }
        // PROTOTYPE (native nav bar): pages whose head is the real UINavigationBar.
        hostPageData.removeValue(forKey: RaftNativeNavBarHostIdPageDataKeyName)
        var nativeNavBar: (hostId: String, bridge: RaftNativeNavBarBridge)?
        let usesNativeNavBar = RaftNativeNavProto.usesNativeNavBar(pageName: pageName)
        if usesNativeNavBar || RaftNativeNavProto.scriptsPage(pageName: pageName) {
            let hostId = UUID().uuidString
            let bridge = RaftNativeNavBarBridge()
            // Prototype off in a capture: the bridge only lets the script scroll the page's list.
            bridge.hostDrawsBar = usesNativeNavBar
            RaftNativeNavBarRegistry.shared.register(hostId: hostId, bridge: bridge)
            hostPageData[RaftNativeNavBarHostIdPageDataKeyName] = hostId
            nativeNavBar = (hostId, bridge)
        }
        // iPhone Duo: the side column's buttons are the page's bar buttons in the system's vertical
        // bar (RaftIosSideActionHost). Every page gets a bridge; off Duo the page never presents.
        // Not on a native-nav-bar prototype page: RaftNativeNavBarHost owns its bar buttons, and
        // the page keeps drawing its own column.
        hostPageData.removeValue(forKey: RaftSideActionHostBridgeKt.RaftSideActionHostIdPageDataKey)
        var sideActions: (hostId: String, bridge: RaftSideActionHostBridge)?
        if #available(iOS 27.1, *), RaftIosVerticalBarItemsSupported(), !usesNativeNavBar {
            sideActions = RaftIosSideActionHost.register(pageData: &hostPageData)
        }
        let container = RaftPageViewController(pageName: pageName, pageData: hostPageData)
        if #available(iOS 27.1, *), let sideActions {
            container.raftSideActionHost = RaftIosSideActionHost(
                controller: container, bridge: sideActions.bridge, hostId: sideActions.hostId
            )
        }
        if #available(iOS 26.0, *), let nativeServerSwitcher {
            container.raftNativeServerSwitcherHost = RaftIosServerSwitcherHost(
                controller: container,
                bridge: nativeServerSwitcher.bridge,
                hostId: nativeServerSwitcher.hostId
            )
        }
        if let nativeNavBar = nativeNavBar {
            container.raftUsesNativeNavBar = usesNativeNavBar
            let navHost = RaftNativeNavBarHost(
                controller: container,
                bridge: nativeNavBar.bridge,
                hostId: nativeNavBar.hostId,
                largeTitle: usesNativeNavBar && RaftNativeNavProto.usesLargeTitle(pageName: pageName)
            )
            container.raftNativeNavBarHost = navHost
            if usesNativeNavBar {
                navHost.attach()
                // #proj-mobile:#282: populate the navigationItem synchronously from the route
                // before pushViewController so the push animation already carries the destination's
                // title, channel glyph / DM avatar tile, and buttons instead of jumping ~0.25s later.
                RaftNativeNavBarPrefillKt.raftPrefillNativeNavBar(
                    bridge: nativeNavBar.bridge,
                    pageName: pageName,
                    pageDataJson: jsonText(container.raftPageData)
                )
            }
        }
        // Before the page's first composition: the last painted canvas, else the resolved app
        // theme's canvas (the stored Light/Dark/System choice), so nothing flashes white.
        container.raftInitialBackgroundColor =
            RaftCmpIos.shared.lastCanvasColor()
            ?? UIColor(raftArgb: RaftAppAppearanceRuntime.shared.canvasArgb)
        // Status bar icons from the first frame, not only after composition: by what the page
        // will paint under the status bar (the app shell's head paints the theme frame, other
        // pages mostly the canvas), judged by luminance like the page's own report.
        container.raftSetDarkTheme(
            RaftAppAppearanceRuntime.shared.statusBarBackdropDark(frameUnderStatusBar: container.raftIsAppShellPage)
        )
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
            listener: listener,
            toastOverlay: RaftIosToastOverlayRegistry.overlayOrEnsure(for: nil)
        )
        let content: UIViewController
        if let nativeTabBar = nativeTabBar {
            content = RaftIosTabBarController(
                content: page.viewController,
                bridge: nativeTabBar.bridge,
                hostId: nativeTabBar.hostId,
                keepsEdgeTouches: { point in page.keepsEdgeTouches(x: Double(point.x), y: Double(point.y)) }
            )
        } else {
            content = page.viewController
        }
        container.attachContent(content) { event, data in
            page.sendEvent(event: event, dataJson: RaftPageControllerFactory.jsonText(data))
        }
        // Full-page back swipe: Compose horizontal content reports back-direction consumption.
        container.raftBackSwipeContentProbe = { point in
            page.contentConsumesBackSwipe(x: Double(point.x), y: Double(point.y))
        }
        // Covered (a native push, a full-screen presentation): the page closes its overlays.
        container.raftPageWillBeCoveredHook = {
            page.pageWillBeCovered()
        }
        // Scene-level toast overlay: bind to the hosting UIWindowScene (lazily registering the
        // scene's host when SwiftUI's WindowGroup owns window creation) and suppress avoidance
        // whenever this page is covered or presented as a non-fullscreen sheet.
        container.raftToastOverlaySceneHook = { [weak container] windowScene in
            guard let windowScene,
                  let overlay = RaftIosToastOverlayRegistry.ensureOverlay(
                      for: windowScene,
                      mainWindow: container?.viewIfLoaded?.window
                  )
            else {
                return
            }
            page.setToastOverlay(overlay: overlay)
        }
        container.raftToastAvoidanceActiveHook = { active in
            page.setToastAvoidanceActive(active: active)
        }
        return container
    }

    fileprivate static func jsonText(_ object: [AnyHashable: Any]) -> String {
        RaftIosTopPageResolver.jsonText(object)
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

    func currentTopPage() -> RaftCmpIosTopPage? {
        RaftIosTopPageResolver.resolveTopPage(from: controller) { top in
            guard let page = top as? RaftPageViewController else { return nil }
            return (page.raftPageName, page.raftPageData)
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
