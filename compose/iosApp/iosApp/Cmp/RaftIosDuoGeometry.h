#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// iPhone Duo window facts the host reports to shared (the adaptive-pane contract).
///
/// Apple's HIG for iPhone Duo moves the tool bar, the tab bar and the navigation buttons into one
/// vertical strip on one edge of the window: with the device folded (outer display) and with the
/// inner display held landscape. The inner display held portrait goes back to a horizontal top
/// bar. Shared decides what to do with that; the host only reports the facts.

/// The edge the system places a vertical bar on, as the name shared expects
/// ("left", "right" or "none").
///
/// The name is a *physical* side. The system's `verticalBarEdge` is a logical edge, and in a
/// right-to-left language its "trailing" is the left of the screen; the strip is laid out by
/// physical side, so the host converts the logical edge using the window's effective layout
/// direction.
FOUNDATION_EXTERN NSString *RaftIosSideColumnEdgeName(UIWindow * _Nullable window);

/// Which of the iPhone Duo's displays the window is on: "duoOuter", "duoInner" or "other".
FOUNDATION_EXTERN NSString *RaftIosDuoDisplayName(UIWindow * _Nullable window);

/// Whether the system is in a shape where it places a vertical bar (the side column).
FOUNDATION_EXTERN BOOL RaftIosSideColumnIsActive(UIWindow * _Nullable window);

/// The width of the vertical strip, in points (0 when there is no strip).
FOUNDATION_EXTERN CGFloat RaftIosSideColumnWidthPoints(UIWindow * _Nullable window);

/// The native tab bar in the window's hierarchy, if it has one.
FOUNDATION_EXTERN UIView * _Nullable RaftIosNativeTabBarView(UIWindow * _Nullable window);

/// The height the native tab bar takes at the bottom of the vertical strip, in points.
FOUNDATION_EXTERN CGFloat RaftIosNativeTabBarHeightPoints(UIWindow * _Nullable window);

/// The camera (occlusion) and fold (division) regions the content must stay out of. Each entry is
/// a dictionary in window points: `leftPt`, `topPt`, `widthPt`, `heightPt`, and `kind`
/// ("occlusion", "division" or "unknown"), so the camera and the fold can be told apart.
FOUNDATION_EXTERN NSArray<NSDictionary<NSString *, id> *> *RaftIosReservedRegions(
    UIWindow * _Nullable window);

/// Runtime access to the UIKit APIs that exist only in the iOS 27.1 SDK.

/// `UITraitCollection.verticalBarEdge` as a number, or nil when the SDK/runtime has no such
/// property.
FOUNDATION_EXTERN NSNumber * _Nullable RaftIosVerticalBarEdgeValue(
    UITraitCollection * _Nullable traits);

/// The reserved-region kinds by name: "occlusion" (the camera) and "division" (the fold). A kind
/// the runtime does not have is absent, so the names stay paired with the right kinds.
FOUNDATION_EXTERN NSDictionary<NSString *, id> *RaftIosReservedRegionKindsByName(void);

/// The active reserved regions of one kind, as `UIViewReservedRegion` objects.
FOUNDATION_EXTERN NSArray *RaftIosReservedRegionsForKind(UIView * _Nullable view, id _Nullable kind);

/// A reserved region's frame in its owner's coordinate space.
FOUNDATION_EXTERN CGRect RaftIosReservedRegionFrame(id _Nullable region);

NS_ASSUME_NONNULL_END
