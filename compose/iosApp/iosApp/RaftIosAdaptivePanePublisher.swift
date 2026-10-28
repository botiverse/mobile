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
        let sideColumn: Bool
        let stripEdge: String
        let stripWidthPt: CGFloat
        let nativeTabBarHeightPt: CGFloat
        let reservedRegions: [[String: NSNumber]]
    }

    private var latest: Snapshot?

    /// The physical side the vertical strip is drawn on, as the name shared expects:
    /// "left", "right" or "none".
    ///
    /// UIKit's `verticalBarEdge` is a *logical* edge - "leading" or "trailing" - and in a
    /// right-to-left language its trailing edge is the left of the screen. The side column is
    /// laid out by physical side (the design system's column is placed on the Left or the
    /// Right), so the host converts the logical edge into the physical side the strip is
    /// actually drawn on. The host is the right place to do this: it knows the window's
    /// effective layout direction.
    ///
    /// - Parameters:
    ///   - verticalBarEdge: `UIVerticalBarEdge` as a number: 0 unspecified, 1 leading,
    ///     2 trailing.
    ///   - isRightToLeft: whether the window's effective layout direction is right-to-left.
    @objc(physicalStripEdgeForVerticalBarEdge:rightToLeft:)
    static func physicalStripEdge(verticalBarEdge: Int, rightToLeft: Bool) -> String {
        // Only a leading or trailing edge places a bar; "unspecified" places none.
        guard verticalBarEdge == 1 || verticalBarEdge == 2 else { return "none" }
        let isLeading = verticalBarEdge == 1
        // A leading edge is the left in a left-to-right language and the right in a
        // right-to-left one.
        let isOnLeft = isLeading != rightToLeft
        return isOnLeft ? "left" : "right"
    }

    /// The window facts the host owns, read once per publish.
    ///
    /// `sideColumn` says the window is in the iPhone Duo side-column shape: the outer (folded)
    /// display, or the inner display held landscape. Apple's HIG for iPhone Duo moves the tool
    /// bar, tab bar and navigation buttons into one vertical strip on the right edge in that
    /// shape, and back to a horizontal top bar when the inner display is held portrait.
    ///
    /// The shape is decided from the window's size and the device, not from the hinge angle: the
    /// hinge APIs (`UIHinge`) are iOS 27.1+ and the app still has to build against the 26.x SDK.
    @objc(nextPayloadWithWidthPoints:heightPoints:scale:sideColumn:stripEdge:stripWidthPoints:nativeTabBarHeightPoints:reservedRegions:)
    func nextPayload(
        widthPoints: CGFloat,
        heightPoints: CGFloat,
        scale: CGFloat,
        sideColumn: Bool,
        stripEdge: String,
        stripWidthPoints: CGFloat,
        nativeTabBarHeightPoints: CGFloat,
        reservedRegions: [[String: NSNumber]]
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
            density: scale,
            sideColumn: sideColumn,
            stripEdge: stripEdge,
            stripWidthPt: stripWidthPoints.isFinite ? max(0, stripWidthPoints) : 0,
            nativeTabBarHeightPt: nativeTabBarHeightPoints.isFinite ? max(0, nativeTabBarHeightPoints) : 0,
            reservedRegions: reservedRegions
        )
        guard snapshot != latest else { return nil }
        latest = snapshot

        return [
            "widthPx": snapshot.widthPx,
            "heightPx": snapshot.heightPx,
            "density": snapshot.density,
            "posture": "flat",
            "sideColumn": snapshot.sideColumn,
            "stripEdge": snapshot.stripEdge,
            "stripWidthPt": snapshot.stripWidthPt,
            "nativeTabBarHeightPt": snapshot.nativeTabBarHeightPt,
            "reservedRegions": snapshot.reservedRegions,
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
///
/// TODO(iphone-duo, iOS 27.1 SDK): the safe area does not cover every reserved region. On iPhone
/// Duo the inner front-facing camera is an occlusion region only while the camera is active, and
/// the fold is a division region while the device is partially open. UIKit exposes them from the
/// iOS 27.1 SDK as `UIView.reservedRegions(kind:options:)` returning `[UIView.ReservedRegion]`
/// (`frame` in the view's points, margins included; `isActive`; kinds `.occlusion` and
/// `.division`). It is Swift-only and absent from the Xcode 26.6 / iOS 26.5 SDK we ship with, so
/// it is not referenced yet. When the opt-in Xcode 27.1 lane builds, publish active occlusion
/// frames here behind `if #available(iOS 27.1, *)` (and a compile-time guard while 26.x still
/// builds), and have shared keep text and buttons out of them. Under the 26.x SDK the app is
/// letterboxed clear of the camera and status bar, so nothing is lost until then.
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
