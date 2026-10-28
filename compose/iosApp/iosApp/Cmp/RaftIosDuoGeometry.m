#import <UIKit/UIKit.h>

#import "RaftIosDuoGeometry.h"

/// iPhone Duo window facts the host reports to shared (the adaptive-pane contract).
///
/// Apple's HIG for iPhone Duo moves the tool bar, the tab bar and the navigation buttons into one
/// vertical strip on one edge of the window: with the device folded (outer display) and with the
/// inner display held landscape. The inner display held portrait goes back to a horizontal top
/// bar. Shared decides what to do with that; the host only reports the facts.
///
/// Two of the facts come from UIKit APIs that exist only in the iOS 27.1 SDK
/// (`UITraitCollection.verticalBarEdge` and `UIView.reservedRegions(ofKind:)`). The app still
/// builds against the 26.x SDK for the default lane, so they are reached through the Objective-C
/// runtime instead of a direct call: on 26.x the selectors are absent, the lookups return nil, and
/// the facts read as "no side column, no reserved regions" - which is what 26.x does anyway,
/// because a 26.x build is letterboxed on the Duo.

NS_ASSUME_NONNULL_BEGIN

/// The edge the system places a vertical bar on, as the name shared expects.
NSString *RaftIosSideColumnEdgeName(UIWindow * _Nullable window) {
    NSNumber *edge = RaftIosVerticalBarEdgeValue(window.traitCollection);
    if (edge == nil) {
        return @"none";
    }
    // UIVerticalBarEdge: 0 unspecified, 1 leading, 2 trailing.
    if (edge.integerValue == 1) {
        return @"leading";
    }
    if (edge.integerValue == 2) {
        return @"trailing";
    }
    return @"none";
}

/// Whether the system is in a shape where it places a vertical bar (the side column).
BOOL RaftIosSideColumnIsActive(UIWindow * _Nullable window) {
    NSNumber *edge = RaftIosVerticalBarEdgeValue(window.traitCollection);
    return edge != nil && edge.integerValue != 0;
}

/// The width of the vertical strip, in points.
///
/// The system does not report the strip's width directly, so it is measured: the area of the
/// window the system leaves for the strip. The strip is the system chrome (status bar and
/// navigation) on the trailing edge, which the window's safe area reports; the native tab bar
/// inside it is reported separately.
CGFloat RaftIosSideColumnWidthPoints(UIWindow * _Nullable window) {
    if (!RaftIosSideColumnIsActive(window)) {
        return 0;
    }
    if ([RaftIosSideColumnEdgeName(window) isEqualToString:@"leading"]) {
        return window.safeAreaInsets.left;
    }
    return window.safeAreaInsets.right;
}

/// The native tab bar in the window's hierarchy, if it has one.
///
/// The host moves the real `UITabBarController` into the side column, so the bar is somewhere
/// under the window's root view rather than at a known place.
static UIView * _Nullable RaftIosFindTabBarInView(UIView * _Nullable view) {
    if (view == nil) {
        return nil;
    }
    if ([view isKindOfClass:UITabBar.class]) {
        return view;
    }
    for (UIView *subview in view.subviews) {
        UIView *found = RaftIosFindTabBarInView(subview);
        if (found != nil) {
            return found;
        }
    }
    return nil;
}

/// The native tab bar in the window's hierarchy, if it has one.
UIView * _Nullable RaftIosNativeTabBarView(UIWindow * _Nullable window) {
    return RaftIosFindTabBarInView(window);
}

/// The height the native tab bar takes at the bottom of the vertical strip, in points.
///
/// The tab bar is a real `UITabBarController` the host moves into the strip. Its height comes
/// from the bar's own frame in the window, not from an assumed size.
CGFloat RaftIosNativeTabBarHeightPoints(UIWindow * _Nullable window) {
    if (!RaftIosSideColumnIsActive(window)) {
        return 0;
    }
    UIView *tabBar = RaftIosNativeTabBarView(window);
    if (tabBar == nil || tabBar.hidden || tabBar.superview == nil) {
        return 0;
    }
    CGRect frame = [tabBar convertRect:tabBar.bounds toView:window];
    if (CGRectIsEmpty(frame) || frame.size.height <= 0) {
        return 0;
    }
    return frame.size.height;
}

/// The camera (occlusion) and fold (division) regions the content must stay out of.
///
/// Each entry is a dictionary in window points: `leftPt`, `topPt`, `widthPt`, `heightPt`. Empty
/// on systems without those regions (everything before iOS 27.1).
NSArray<NSDictionary<NSString *, NSNumber *> *> *RaftIosReservedRegions(UIWindow * _Nullable window) {
    if (window == nil) {
        return @[];
    }
    NSArray *kinds = RaftIosReservedRegionKinds();
    if (kinds.count == 0) {
        return @[];
    }
    NSMutableArray<NSDictionary<NSString *, NSNumber *> *> *regions = [NSMutableArray array];
    for (id kind in kinds) {
        NSArray *found = RaftIosReservedRegionsForKind(window, kind);
        for (id region in found) {
            CGRect frame = RaftIosReservedRegionFrame(region);
            if (CGRectIsEmpty(frame) || frame.size.width <= 0 || frame.size.height <= 0) {
                continue;
            }
            [regions addObject:@{
                @"leftPt" : @(frame.origin.x),
                @"topPt" : @(frame.origin.y),
                @"widthPt" : @(frame.size.width),
                @"heightPt" : @(frame.size.height),
            }];
        }
    }
    return regions;
}

NS_ASSUME_NONNULL_END