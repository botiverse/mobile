import CoreGraphics
import Foundation

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
