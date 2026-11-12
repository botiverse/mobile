import Foundation
import UIKit
import shared

/// The iPhone Duo side column of one page (`RaftSideActionHostBridge`) as the page's navigation bar
/// buttons (RaftIosSideActionItems): iOS 27.1 draws them in the vertical bar on the camera's side,
/// as the system's own glass buttons, grouped like the tab bar; taps go back to the page by id. The
/// page keeps the column's width and draws nothing in it.
///
/// The navigation bar is shown while it holds the buttons (`raftSideActionsShown`): the system
/// draws no vertical bar items of a hidden bar. Its top band is not the page's, so the page keeps
/// the navigation controller's safe area (RaftPageViewController `raftPublishWindowInsets`).
@available(iOS 27.1, *)
final class RaftIosSideActionHost: NSObject, RaftSideActionHostListener {
    private weak var controller: RaftPageViewController?
    private let bridge: RaftSideActionHostBridge
    private let hostId: String

    /// Registers [bridge] under a new host id in [pageData] (before the page is created).
    static func register(pageData: inout [AnyHashable: Any]) -> (hostId: String, bridge: RaftSideActionHostBridge) {
        let hostId = UUID().uuidString
        let bridge = RaftSideActionHostBridge()
        RaftSideActionHostRegistry.shared.register(hostId: hostId, bridge: bridge)
        pageData[RaftSideActionHostBridgeKt.RaftSideActionHostIdPageDataKey] = hostId
        return (hostId, bridge)
    }

    init(controller: RaftPageViewController, bridge: RaftSideActionHostBridge, hostId: String) {
        self.controller = controller
        self.bridge = bridge
        self.hostId = hostId
        super.init()
        bridge.attachListener(listener: self)
        // Only where the build's SDK and the system have vertical bar items; else the page keeps
        // drawing its own column.
        bridge.setAvailable(available: RaftIosVerticalBarItemsSupported())
    }

    deinit {
        bridge.detachListener()
        RaftSideActionHostRegistry.shared.unregister(hostId: hostId, bridge: bridge)
    }

    func onSideActionColumnChanged(json: String) {
        guard let controller,
              let items = RaftIosSideActionItems.make(json: json, onTap: { [weak self] id in _ = self?.bridge.sideActionTapped(id: id) })
        else { return }
        guard RaftIosMakeBarButtonItemsVerticalPreferred(items.leading + items.trailing) else { return }
        let navigationItem = controller.navigationItem
        navigationItem.hidesBackButton = true
        navigationItem.leftBarButtonItems = items.leading
        // rightBarButtonItems run from the trailing edge in: reversed, they read in listed order.
        navigationItem.rightBarButtonItems = items.trailing.reversed()
        controller.raftSideActionsShown = true
    }

    func onSideActionColumnCleared() {
        guard let controller else { return }
        controller.navigationItem.leftBarButtonItems = nil
        controller.navigationItem.rightBarButtonItems = nil
        controller.navigationItem.hidesBackButton = false
        controller.raftSideActionsShown = false
    }
}
