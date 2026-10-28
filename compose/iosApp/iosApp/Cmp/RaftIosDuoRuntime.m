#import <UIKit/UIKit.h>

/// Runtime access to the UIKit APIs that exist only in the iOS 27.1 SDK.
///
/// The app builds against the 26.x SDK in the default lane, so `verticalBarEdge` and
/// `reservedRegions(ofKind:)` cannot be called directly. They are reached through the
/// Objective-C runtime instead: on 26.x the selectors are absent, every lookup below returns nil,
/// and the host reports "no side column, no reserved regions" - which is what 26.x does anyway,
/// because a 26.x build is letterboxed on the Duo.
///
/// `UIVerticalBarEdge`: 0 unspecified, 1 leading, 2 trailing.
/// `UIViewReservedRegionKind`: `+occlusionRegionKind` (camera), `+divisionRegionKind` (fold).

NS_ASSUME_NONNULL_BEGIN

static id RaftIosClassNamed(const char *name) {
    return NSClassFromString([NSString stringWithUTF8String:name]);
}

/// `UITraitCollection.verticalBarEdge` as a number, or nil when the SDK/runtime has no such
/// property.
NSNumber * _Nullable RaftIosVerticalBarEdgeValue(UITraitCollection * _Nullable traits) {
    if (traits == nil) {
        return nil;
    }
    SEL selector = NSSelectorFromString(@"verticalBarEdge");
    if (![traits respondsToSelector:selector]) {
        return nil;
    }
    NSMethodSignature *signature = [traits methodSignatureForSelector:selector];
    if (signature == nil) {
        return nil;
    }
    NSInvocation *invocation = [NSInvocation invocationWithMethodSignature:signature];
    invocation.target = traits;
    invocation.selector = selector;
    [invocation invoke];
    NSUInteger raw = 0;
    [invocation getReturnValue:&raw];
    return @(raw);
}

/// The reserved-region kinds (`UIViewReservedRegionKind`), or an empty array when the SDK has
/// none. The kinds are class objects, so they are created with the runtime too.
NSArray *RaftIosReservedRegionKinds(void) {
    Class kindClass = RaftIosClassNamed("UIViewReservedRegionKind");
    if (kindClass == nil) {
        return @[];
    }
    NSMutableArray *kinds = [NSMutableArray array];
    for (NSString *name in @[ @"occlusionRegionKind", @"divisionRegionKind" ]) {
        SEL selector = NSSelectorFromString(name);
        if (![kindClass respondsToSelector:selector]) {
            continue;
        }
        id kind = [kindClass performSelector:selector];
        if (kind != nil) {
            [kinds addObject:kind];
        }
    }
    return kinds;
}

/// The name of a reserved region's kind: "occlusion" (the camera) or "division" (the fold).
///
/// The kind objects are class objects, so they are matched by identity against the two the
/// runtime can produce. A kind this build does not know is reported as "unknown" rather than
/// dropped, so a region is never silently lost.
NSString *RaftIosReservedRegionKindName(id _Nullable kind) {
    if (kind == nil) {
        return @"unknown";
    }
    NSArray *kinds = RaftIosReservedRegionKinds();
    NSUInteger index = [kinds indexOfObject:kind];
    if (index == NSNotFound) {
        return @"unknown";
    }
    return index == 0 ? @"occlusion" : @"division";
}

/// The active reserved regions of one kind, as `UIViewReservedRegion` objects.
NSArray *RaftIosReservedRegionsForKind(UIView * _Nullable view, id _Nullable kind) {
    if (view == nil || kind == nil) {
        return @[];
    }
    SEL selector = NSSelectorFromString(@"reservedRegionsOfKind:options:");
    if (![view respondsToSelector:selector]) {
        return @[];
    }
    // The second argument is UIViewReservedRegionQueryOptions (an OptionSet); 0 means "active
    // regions only", which is what the host reports.
    NSMethodSignature *signature = [view methodSignatureForSelector:selector];
    if (signature == nil) {
        return @[];
    }
    NSInvocation *invocation = [NSInvocation invocationWithMethodSignature:signature];
    invocation.target = view;
    invocation.selector = selector;
    [invocation setArgument:&kind atIndex:2];
    NSUInteger options = 0;
    [invocation setArgument:&options atIndex:3];
    [invocation invoke];
    NSArray * _Nullable regions = nil;
    [invocation getReturnValue:&regions];
    return regions ?: @[];
}

/// A reserved region's frame in the region's owner's coordinate space.
CGRect RaftIosReservedRegionFrame(id _Nullable region) {
    if (region == nil) {
        return CGRectNull;
    }
    SEL selector = NSSelectorFromString(@"frame");
    if (![region respondsToSelector:selector]) {
        return CGRectNull;
    }
    NSMethodSignature *signature = [region methodSignatureForSelector:selector];
    if (signature == nil) {
        return CGRectNull;
    }
    NSInvocation *invocation = [NSInvocation invocationWithMethodSignature:signature];
    invocation.target = region;
    invocation.selector = selector;
    [invocation invoke];
    CGRect frame = CGRectNull;
    [invocation getReturnValue:&frame];
    return frame;
}

NS_ASSUME_NONNULL_END
