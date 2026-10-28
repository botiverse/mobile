#import <UIKit/UIKit.h>

#import "RaftIosDuoGeometry.h"

#import "iosApp-Swift.h"

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
///
/// The edge is reported as a *physical* side - "left" or "right" - not as "leading" or
/// "trailing". The system's `verticalBarEdge` is a logical edge: in a right-to-left language
/// "trailing" is the left of the screen. The side column is laid out by physical side (the
/// design system's column is placed on the Left or the Right), so a logical edge would put it on
/// the wrong side in those languages. The host knows the window's effective layout direction, so
/// it converts the system's logical edge into the physical side the strip is actually drawn on.
NSString *RaftIosSideColumnEdgeName(UIWindow * _Nullable window) {
    NSNumber *edge = RaftIosVerticalBarEdgeValue(window.traitCollection);
    if (edge == nil) {
        return @"none";
    }
    BOOL isRightToLeft =
        [window effectiveUserInterfaceLayoutDirection] == UIUserInterfaceLayoutDirectionRightToLeft;
    // The conversion from the system's logical edge to the physical side lives in Swift, where
    // it is unit tested.
    return [RaftIosAdaptivePanePublisher
        physicalStripEdgeForVerticalBarEdge:edge.integerValue
                                    rightToLeft:isRightToLeft];
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
    if ([RaftIosSideColumnEdgeName(window) isEqualToString:@"left"]) {
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
    // The bar is hosted by the tab bar controller, which the page owns; it is not in the window's
    // own view tree, so the live bar is asked for directly and the window tree is the fallback.
    UIView *live = [RaftIosTabBarController liveTabBarInWindow:window];
    if (live != nil && !live.hidden && live.superview != nil) {
        return live;
    }
    return RaftIosFindTabBarInView(window);
}

/// The space the native tab bar takes at the end of the vertical strip, in points.
///
/// The tab bar is a real `UITabBarController` the host moves into the strip. Its extent along
/// the strip comes from the bar's own frame in the window, not from an assumed size.
///
/// On the iPhone Duo the bar is laid out along the strip rather than across it: it is taller than
/// it is wide, so the space it takes at the end of the strip is its *width* (the bar runs
/// vertically), not its height. Measuring the height there would report the bar's length along
/// the strip as the strip's width.
CGFloat RaftIosNativeTabBarHeightPoints(UIWindow * _Nullable window) {
    if (!RaftIosSideColumnIsActive(window)) {
        return 0;
    }
    UIView *tabBar = RaftIosNativeTabBarView(window);
    if (tabBar == nil || tabBar.hidden || tabBar.superview == nil) {
        return 0;
    }
    CGRect frame = [tabBar convertRect:tabBar.bounds toView:window];
    if (CGRectIsEmpty(frame) || frame.size.width <= 0 || frame.size.height <= 0) {
        return 0;
    }
    // The bar runs along the strip when it is taller than it is wide.
    return frame.size.height > frame.size.width ? frame.size.width : frame.size.height;
}

/// Which of the iPhone Duo's displays the window is on: "duoOuter", "duoInner" or "other".
///
/// The Duo's two displays have fixed, different sizes: the outer (folded) one is 466 x 678
/// points and the inner (unfolded) one is 669 x 951 points portrait (951 x 669 landscape). A
/// full-screen window on one of those sizes is on that display; any other size - an ordinary
/// phone, an iPad, or a window that does not fill the display, such as a Split View window on
/// the inner display - is "other", so shared can tell "the Duo, folded" from "a small phone"
/// without guessing.
///
/// The *window's* size is measured, not the screen's: the screen keeps its size whatever the
/// window does, so a windowed app on the inner display would still read as the inner display
/// and would keep a layout meant for the whole display.

/// The iPhone Duo's outer (folded) display: 466 x 678 points.
static const CGFloat RaftIosDuoOuterDisplayShortSide = 466.0;
static const CGFloat RaftIosDuoOuterDisplayLongSide = 678.0;

/// The iPhone Duo's inner (unfolded) display: 669 x 951 points.
static const CGFloat RaftIosDuoInnerDisplayShortSide = 669.0;
static const CGFloat RaftIosDuoInnerDisplayLongSide = 951.0;

NSString *RaftIosDuoDisplayName(UIWindow * _Nullable window) {
    if (window == nil) {
        return @"other";
    }
    // The *window's* size, not the screen's: a window that does not fill the display is not
    // that display's own shape - a Split View window on the Duo's inner display, for one - and
    // such a window is left to the ordinary layout.
    CGRect bounds = window.bounds;
    if (bounds.size.width <= 0 || bounds.size.height <= 0) {
        return @"other";
    }
    // The Duo's two displays have fixed, different sizes: the outer (folded) one is 466 x 678
    // points and the inner (unfolded) one is 669 x 951 points portrait (951 x 669 landscape).
    // They are compared in the orientation-agnostic way - the shorter side first - because the
    // inner display is used in both.
    //
    // The sizes are the hardware's, so they do not change with the fold state: when the Duo is
    // folded only the outer display is lit, and when it is open both are. A full-screen window on
    // the outer display's size is on the outer display, whatever else is lit.
    CGSize size = CGSizeMake(MIN(bounds.size.width, bounds.size.height),
                             MAX(bounds.size.width, bounds.size.height));
    if (fabs(size.width - RaftIosDuoOuterDisplayShortSide) < 0.5 &&
        fabs(size.height - RaftIosDuoOuterDisplayLongSide) < 0.5) {
        return @"duoOuter";
    }
    if (fabs(size.width - RaftIosDuoInnerDisplayShortSide) < 0.5 &&
        fabs(size.height - RaftIosDuoInnerDisplayLongSide) < 0.5) {
        return @"duoInner";
    }
    return @"other";
}

/// The camera (occlusion) and fold (division) regions the content must stay out of.
///
/// Each entry is a dictionary in window points: `leftPt`, `topPt`, `widthPt`, `heightPt`. Empty
/// on systems without those regions (everything before iOS 27.1).
NSArray<NSDictionary<NSString *, id> *> *RaftIosReservedRegions(UIWindow * _Nullable window) {
    if (window == nil) {
        return @[];
    }
    // The kinds are looked up by name so each region carries the kind it was found under: a
    // runtime without one of them must not shift the other's name.
    NSDictionary<NSString *, id> *kindsByName = RaftIosReservedRegionKindsByName();
    if (kindsByName.count == 0) {
        return @[];
    }
    NSMutableArray<NSDictionary<NSString *, id> *> *regions = [NSMutableArray array];
    for (NSString *name in kindsByName) {
        NSArray *found = RaftIosReservedRegionsForKind(window, kindsByName[name]);
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
                // The kind tells the camera's occlusion apart from the fold's division, so
                // shared can treat them differently.
                @"kind" : name,
            }];
        }
    }
    return regions;
}

NS_ASSUME_NONNULL_END