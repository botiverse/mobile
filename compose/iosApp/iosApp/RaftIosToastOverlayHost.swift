import Foundation
import shared
import UIKit

/// Container root controller inside `RaftIosToastOverlayWindow`: embeds the transparent Compose
/// toast controller and forwards status-bar / orientation appearance from the scene's main window
/// so unhiding the overlay window never flips status bar icons or orientation locks.
private final class RaftIosToastOverlayRootViewController: UIViewController {
    private let contentController: UIViewController
    private weak var mainWindow: UIWindow?

    init(content: UIViewController, mainWindow: UIWindow?) {
        self.contentController = content
        self.mainWindow = mainWindow
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        view.isOpaque = false
        addChild(contentController)
        contentController.view.frame = view.bounds
        contentController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentController.view.backgroundColor = .clear
        contentController.view.isOpaque = false
        view.addSubview(contentController.view)
        contentController.didMove(toParent: self)
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        topMainViewController()?.preferredStatusBarStyle ?? .default
    }

    override var prefersStatusBarHidden: Bool {
        if mainWindow?.rootViewController?.prefersStatusBarHidden == true {
            return true
        }
        return topMainViewController()?.prefersStatusBarHidden ?? false
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        topMainViewController()?.supportedInterfaceOrientations ?? .all
    }

    override var childForStatusBarStyle: UIViewController? {
        nil
    }

    override var childForStatusBarHidden: UIViewController? {
        nil
    }

    private func topMainViewController() -> UIViewController? {
        guard var current = mainWindow?.rootViewController else { return nil }
        while true {
            if let presented = current.presentedViewController, !presented.isBeingDismissed {
                current = presented
            } else if let nav = current as? UINavigationController, let top = nav.topViewController {
                current = top
            } else if let tab = current as? UITabBarController, let selected = tab.selectedViewController {
                current = selected
            } else if let hosting = current.children.last, type(of: current).description().contains("UIHostingController") {
                current = hosting
            } else {
                break
            }
        }
        return current
    }
}

/// Weak-forwarding bridge so the Kotlin `RaftIosToastOverlay` never holds a strong reference cycle
/// back to `RaftIosToastOverlayHost`.
private final class RaftIosToastOverlayListenerBridge: NSObject, RaftIosToastOverlayListener {
    weak var owner: RaftIosToastOverlayHost?

    func onToastVisibilityChanged(hasToasts: Bool) {
        onMain { [weak self] in
            self?.owner?.handleVisibilityChanged(hasToasts: hasToasts)
        }
    }

    func onToastBoundsChanged(
        hasBounds: Bool,
        leftPt: Double,
        topPt: Double,
        rightPt: Double,
        bottomPt: Double
    ) {
        onMain { [weak self] in
            self?.owner?.handleBoundsChanged(
                hasBounds: hasBounds,
                leftPt: leftPt,
                topPt: topPt,
                rightPt: rightPt,
                bottomPt: bottomPt
            )
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

/// Owns one `UIWindowScene`'s single `RaftIosToastOverlay` + transparent pass-through `UIWindow`.
final class RaftIosToastOverlayHost: NSObject {
    private static let statusBarAppearanceNotification = Notification.Name("RaftIosPageStatusBarAppearanceDidChange")

    let overlay: RaftIosToastOverlay
    let overlayWindow: RaftIosToastOverlayWindow
    private let windowController: RaftIosToastOverlayWindowController
    private weak var windowScene: UIWindowScene?
    private let listenerBridge: RaftIosToastOverlayListenerBridge
    private var statusBarObserver: NSObjectProtocol?
    private var sceneLifecycleObservers: [NSObjectProtocol] = []
    private var detached = false

    init(windowScene: UIWindowScene, mainWindow: UIWindow) {
        let bridge = RaftIosToastOverlayListenerBridge()
        let overlay = RaftCmpIos.shared.makeToastOverlay(listener: bridge)
        let overlayWindow = RaftIosToastOverlayWindow(windowScene: windowScene)
        overlayWindow.overrideUserInterfaceStyle = RaftIosAppearanceHost.currentStyle
        overlayWindow.rootViewController = RaftIosToastOverlayRootViewController(
            content: overlay.viewController,
            mainWindow: mainWindow
        )
        self.overlay = overlay
        self.overlayWindow = overlayWindow
        self.windowController = RaftIosToastOverlayWindowController(overlayWindow: overlayWindow)
        self.windowScene = windowScene
        self.listenerBridge = bridge
        super.init()
        bridge.owner = self
        self.statusBarObserver = NotificationCenter.default.addObserver(
            forName: Self.statusBarAppearanceNotification,
            object: windowScene,
            queue: .main
        ) { [weak self] _ in
            guard let self, !self.detached, !self.overlayWindow.isHidden else { return }
            self.overlayWindow.rootViewController?.setNeedsStatusBarAppearanceUpdate()
        }
        // Observe scene lifecycle notifications directly so SwiftUI WindowGroup scenes (where
        // RaftIosSceneDelegate is inert) still drive foreground/background overlay lifecycle.
        let center = NotificationCenter.default
        self.sceneLifecycleObservers = [
            center.addObserver(forName: UIScene.didActivateNotification, object: windowScene, queue: .main) { [weak self] _ in
                self?.sceneDidBecomeActive()
            },
            center.addObserver(forName: UIScene.willDeactivateNotification, object: windowScene, queue: .main) { [weak self] _ in
                self?.sceneWillResignActive()
            },
            center.addObserver(forName: UIScene.willEnterForegroundNotification, object: windowScene, queue: .main) { [weak self] _ in
                self?.sceneWillEnterForeground()
            },
            center.addObserver(forName: UIScene.didEnterBackgroundNotification, object: windowScene, queue: .main) { [weak self] _ in
                self?.sceneDidEnterBackground()
            },
        ]
        let active = windowScene.activationState == .foregroundActive
        let foreground = active || windowScene.activationState == .foregroundInactive
        overlay.setLifecycle(visible: foreground || windowScene.activationState == .unattached, resumed: active)
    }

    deinit {
        if let observer = statusBarObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        for observer in sceneLifecycleObservers {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func sceneDidBecomeActive() {
        guard !detached else { return }
        overlay.setLifecycle(visible: true, resumed: true)
    }

    func sceneWillResignActive() {
        guard !detached else { return }
        overlay.setLifecycle(visible: true, resumed: false)
    }

    func sceneWillEnterForeground() {
        guard !detached else { return }
        overlay.setLifecycle(visible: true, resumed: false)
    }

    func sceneDidEnterBackground() {
        guard !detached else { return }
        overlay.setLifecycle(visible: false, resumed: false)
    }

    func detach() {
        guard !detached else { return }
        detached = true
        if let observer = statusBarObserver {
            NotificationCenter.default.removeObserver(observer)
            statusBarObserver = nil
        }
        for observer in sceneLifecycleObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        sceneLifecycleObservers.removeAll()
        windowController.detach()
        overlayWindow.rootViewController = nil
        overlay.detach()
    }

    fileprivate func handleVisibilityChanged(hasToasts: Bool) {
        guard !detached else { return }
        windowController.handleVisibilityChanged(
            hasToasts: hasToasts,
            sceneBounds: windowScene?.coordinateSpace.bounds,
            userInterfaceStyle: RaftIosAppearanceHost.currentStyle
        )
    }

    fileprivate func handleBoundsChanged(
        hasBounds: Bool,
        leftPt: Double,
        topPt: Double,
        rightPt: Double,
        bottomPt: Double
    ) {
        guard !detached else { return }
        windowController.handleBoundsChanged(
            hasBounds: hasBounds,
            leftPt: leftPt,
            topPt: topPt,
            rightPt: rightPt,
            bottomPt: bottomPt
        )
    }
}

/// Process-wide registry mapping each connected `UIWindowScene` session id to its single
/// `RaftIosToastOverlayHost` (main-thread only). Keeps iPad multi-window scenes isolated.
final class RaftIosToastOverlayRegistry {
    private static var hostsBySessionId: [String: RaftIosToastOverlayHost] = [:]
    private static var disconnectObserverInstalled = false

    static func register(scene: UIWindowScene, host: RaftIosToastOverlayHost) {
        installDisconnectObserverIfNeeded()
        let key = scene.session.persistentIdentifier
        if let previous = hostsBySessionId[key], previous !== host {
            previous.detach()
        }
        hostsBySessionId[key] = host
    }

    /// Returns the scene's single window-level toast overlay, lazily creating and registering one
    /// when SwiftUI's default `WindowGroup` created the scene without `RaftIosSceneDelegate`.
    static func ensureOverlay(for scene: UIWindowScene, mainWindow: UIWindow?) -> RaftIosToastOverlay? {
        let key = scene.session.persistentIdentifier
        if let existing = hostsBySessionId[key] {
            return existing.overlay
        }
        let resolvedMainWindow =
            mainWindow
            ?? scene.windows.first(where: { $0.isKeyWindow && !($0 is RaftIosToastOverlayWindow) })
            ?? scene.windows.first(where: { !($0 is RaftIosToastOverlayWindow) })
        guard let resolvedMainWindow else {
            return nil
        }
        let host = RaftIosToastOverlayHost(windowScene: scene, mainWindow: resolvedMainWindow)
        register(scene: scene, host: host)
        return host.overlay
    }

    private static func installDisconnectObserverIfNeeded() {
        guard !disconnectObserverInstalled else { return }
        disconnectObserverInstalled = true
        NotificationCenter.default.addObserver(
            forName: UIScene.didDisconnectNotification,
            object: nil,
            queue: .main
        ) { notification in
            guard let scene = notification.object as? UIScene else { return }
            unregister(session: scene.session)
        }
    }

    static func unregister(session: UISceneSession) {
        let key = session.persistentIdentifier
        if let host = hostsBySessionId.removeValue(forKey: key) {
            host.detach()
        }
    }

    /// Returns the toast overlay for [scene], or falls back through `.foregroundActive` ->
    /// `.foregroundInactive` -> any registered host when a page controller is created before
    /// attaching to a window (`nil` only when no scene host is registered, e.g. isolated tests).
    static func overlay(for scene: UIWindowScene?) -> RaftIosToastOverlay? {
        if let scene {
            return hostsBySessionId[scene.session.persistentIdentifier]?.overlay
        }
        for state in [UIScene.ActivationState.foregroundActive, .foregroundInactive] {
            for connected in UIApplication.shared.connectedScenes {
                guard let windowScene = connected as? UIWindowScene,
                      windowScene.activationState == state,
                      let host = hostsBySessionId[windowScene.session.persistentIdentifier]
                else {
                    continue
                }
                return host.overlay
            }
        }
        return hostsBySessionId.values.first?.overlay
    }
}
