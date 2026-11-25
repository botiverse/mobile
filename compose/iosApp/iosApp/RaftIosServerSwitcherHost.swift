import Foundation
import shared
import UIKit

/// Bridges the shared `RaftNativeServerSwitcherBridge` to the iOS 26+ native Liquid Glass
/// Home server switcher overlay (`RaftIosServerSwitcherGlassOverlayView`) hosted on a
/// `RaftPageViewController` (#proj-mobile:#369).
@available(iOS 26.0, *)
final class RaftIosServerSwitcherHost: NSObject, RaftNativeServerSwitcherListener {
    private weak var controller: UIViewController?
    private let bridge: RaftNativeServerSwitcherBridge
    private let hostId: String
    private var overlayView: RaftIosServerSwitcherGlassOverlayView?

    init(controller: UIViewController, bridge: RaftNativeServerSwitcherBridge, hostId: String) {
        self.controller = controller
        self.bridge = bridge
        self.hostId = hostId
        super.init()
        bridge.listener = self
    }

    deinit {
        if bridge.listener === self {
            bridge.listener = nil
        }
        RaftNativeServerSwitcherRegistry.shared.unregister(hostId: hostId, bridge: bridge)
        let viewToRemove = overlayView
        if Thread.isMainThread {
            viewToRemove?.removeFromSuperview()
        } else if let viewToRemove {
            DispatchQueue.main.async { viewToRemove.removeFromSuperview() }
        }
    }

    func onNativeServerSwitcherStateChanged(state: RaftNativeServerSwitcherState) {
        let applyBlock = { [weak self] in
            self?.applyOnMain(state)
        }
        if Thread.isMainThread {
            applyBlock()
        } else {
            DispatchQueue.main.async(execute: applyBlock)
        }
    }

    private func applyOnMain(_ state: RaftNativeServerSwitcherState) {
        guard let controller, controller.isViewLoaded else {
            if !state.open {
                overlayView?.apply(state: .closed, animated: false)
            }
            return
        }
        let spec = Self.mapSpec(state)
        if !spec.open {
            overlayView?.apply(state: .closed, animated: true)
            return
        }
        let overlay = ensureOverlay(in: controller.view)
        controller.view.bringSubviewToFront(overlay)
        overlay.apply(state: spec, animated: true)
    }

    private func ensureOverlay(in hostView: UIView) -> RaftIosServerSwitcherGlassOverlayView {
        if let existing = overlayView {
            if existing.superview !== hostView {
                existing.frame = hostView.bounds
                existing.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                hostView.addSubview(existing)
            }
            return existing
        }
        let created = RaftIosServerSwitcherGlassOverlayView(frame: hostView.bounds)
        created.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        created.onSelectServer = { [weak self] serverId in
            _ = self?.bridge.selectServer(serverId: serverId)
        }
        created.onTapAction = { [weak self] actionId in
            _ = self?.bridge.tapAction(actionId: actionId)
        }
        created.onReorderServers = { [weak self] orderedIds in
            _ = self?.bridge.reorderServers(orderedIds: orderedIds)
        }
        created.onDismiss = { [weak self] in
            self?.bridge.dismiss()
        }
        hostView.addSubview(created)
        overlayView = created
        return created
    }

    private static func mapSpec(_ state: RaftNativeServerSwitcherState) -> RaftIosServerSwitcherStateSpec {
        guard state.open else { return .closed }
        let rows: [RaftIosServerSwitcherRowSpec] = state.rows.map { row in
            let avatarImage: UIImage? = row.avatar.flatMap { avatar in
                let count = Int(avatar.argb.size)
                guard count > 0 else { return nil }
                var pixels = [Int32](repeating: 0, count: count)
                for index in 0..<count {
                    pixels[index] = avatar.argb.get(index: Int32(index))
                }
                return RaftIosServerAvatarImage.image(argb: pixels, width: Int(avatar.width), height: Int(avatar.height))
            }
            return RaftIosServerSwitcherRowSpec(
                id: row.id,
                name: row.name,
                slug: row.slug,
                selected: row.selected,
                publicPageLive: row.publicPageLive,
                badgeLabel: row.badgeLabel,
                badgeMuted: row.badgeMuted,
                mutedAccessibilityLabel: row.mutedAccessibilityLabel,
                avatarImage: avatarImage,
                avatarFallbackLabel: row.avatarFallbackLabel
            )
        }
        let actions: [RaftIosServerSwitcherActionSpec] = state.actions.map { action in
            RaftIosServerSwitcherActionSpec(
                id: action.id,
                label: action.label,
                imageAssetName: action.imageAssetName,
                systemSymbol: action.systemSymbol
            )
        }
        return RaftIosServerSwitcherStateSpec(
            open: true,
            rows: rows,
            actions: actions,
            emptyLabel: state.emptyLabel,
            switchPending: state.switchPending,
            reorderable: state.reorderable,
            anchorTopPt: CGFloat(state.anchorTopPt),
            anchorStartPt: CGFloat(state.anchorStartPt),
            maxWidthPt: CGFloat(state.maxWidthPt)
        )
    }
}
