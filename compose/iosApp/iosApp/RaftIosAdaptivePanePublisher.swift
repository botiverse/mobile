import CoreGraphics
import Foundation
import UIKit

/// Converts UIKit window geometry into the cross-platform adaptive-pane event contract.
/// The host owns only facts; shared code remains the sole owner of compact vs dual-pane policy.
@objcMembers
final class RaftIosAdaptivePanePublisher: NSObject {
    private struct Snapshot: Equatable {
        let widthPx: Int
        let heightPx: Int
        let density: CGFloat
    }

    private var latest: Snapshot?

    @objc(nextPayloadWithWidthPoints:heightPoints:scale:)
    func nextPayload(
        widthPoints: CGFloat,
        heightPoints: CGFloat,
        scale: CGFloat
    ) -> NSDictionary? {
        guard widthPoints.isFinite,
              heightPoints.isFinite,
              scale.isFinite,
              widthPoints > 0,
              heightPoints > 0,
              scale > 0 else {
            return nil
        }

        let widthPixels = (widthPoints * scale).rounded(.toNearestOrAwayFromZero)
        let heightPixels = (heightPoints * scale).rounded(.toNearestOrAwayFromZero)
        guard widthPixels > 0,
              heightPixels > 0,
              widthPixels <= CGFloat(Int.max),
              heightPixels <= CGFloat(Int.max) else {
            return nil
        }

        let snapshot = Snapshot(
            widthPx: Int(widthPixels),
            heightPx: Int(heightPixels),
            density: scale
        )
        guard snapshot != latest else { return nil }
        latest = snapshot

        return [
            "widthPx": snapshot.widthPx,
            "heightPx": snapshot.heightPx,
            "density": snapshot.density,
            "posture": "flat",
        ]
    }
}

/// Converts a page's UIKit safe area into the cross-platform `slockWindowInsetsChanged` contract
/// (shared `RaftAppSafeArea.kt`): all four edges in pixels, left/right on physical edges.
///
/// The insets are the page view's `safeAreaInsets`, which already include the status bar on
/// whichever edge it sits. `statusBarFrame` is not used for any inset: with the status bar along
/// the right edge (iPhone Duo inner display) its height can be close to the window height.
/// `statusBarHidden` is reported as a fact only.
@objcMembers
final class RaftIosWindowInsetsPublisher: NSObject {
    private var latest: NSDictionary?

    /// The payload for [insets], or nil when it equals the last one returned (deduplicated).
    @objc(nextPayloadWithInsets:scale:statusBarHidden:presentedAsSheet:windowId:)
    func nextPayload(
        insets: UIEdgeInsets,
        scale: CGFloat,
        statusBarHidden: Bool,
        presentedAsSheet: Bool,
        windowId: String
    ) -> NSDictionary? {
        let payload = Self.payload(
            insets: insets,
            scale: scale,
            statusBarHidden: statusBarHidden,
            presentedAsSheet: presentedAsSheet,
            windowId: windowId
        )
        guard payload != latest else { return nil }
        latest = payload
        return payload
    }

    /// Payload keys: `statusBarHeightPx` (top), `navigationBarHeightPx` (bottom),
    /// `safeAreaLeftPx`, `safeAreaRightPx`, `statusBarHidden`, `presentedAsSheet`,
    /// `slockWindowId`. `presentedAsSheet` is a fact too: the page is the content of a sheet
    /// (page or form sheet), whose own top safe area is about 0; shared keeps the sheet header
    /// off the sheet's top edge.
    static func payload(
        insets: UIEdgeInsets,
        scale: CGFloat,
        statusBarHidden: Bool,
        presentedAsSheet: Bool = false,
        windowId: String
    ) -> NSDictionary {
        [
            "statusBarHeightPx": pixelString(insets.top, scale: scale),
            "navigationBarHeightPx": pixelString(insets.bottom, scale: scale),
            "safeAreaLeftPx": pixelString(insets.left, scale: scale),
            "safeAreaRightPx": pixelString(insets.right, scale: scale),
            "statusBarHidden": statusBarHidden,
            "presentedAsSheet": presentedAsSheet,
            "slockWindowId": windowId,
        ]
    }

    /// Whether a controller presented with [style] shows as a sheet over its presenter (and not
    /// full screen). `.automatic` resolves to a page sheet for the pages the router presents.
    static func isSheetStyle(_ style: UIModalPresentationStyle) -> Bool {
        switch style {
        case .pageSheet, .formSheet, .automatic:
            return true
        default:
            return false
        }
    }

    /// The attach-time pageData seed (`safeAreaTop` / `safeAreaBottom` / `safeAreaLeft` /
    /// `safeAreaRight`, pixels). Provisional: the page's first live payload replaces it.
    @objc(pageDataSeedWithInsets:scale:)
    static func pageDataSeed(insets: UIEdgeInsets, scale: CGFloat) -> NSDictionary {
        [
            "safeAreaTop": pixelString(insets.top, scale: scale),
            "safeAreaBottom": pixelString(insets.bottom, scale: scale),
            "safeAreaLeft": pixelString(insets.left, scale: scale),
            "safeAreaRight": pixelString(insets.right, scale: scale),
        ]
    }

    /// Points to a pixel string ("%.3f"); negative or non-finite points read as 0, a scale
    /// below 1 (or non-finite) as 1.
    static func pixelString(_ points: CGFloat, scale: CGFloat) -> String {
        let safePoints = points.isFinite ? max(0, points) : 0
        let safeScale = scale.isFinite ? max(1, scale) : 1
        return String(format: "%.3f", Double(safePoints * safeScale))
    }
}
