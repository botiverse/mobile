// The MIT License (MIT)
//
// Copyright (c) 2015-2016 forkingdog ( https://github.com/forkingdog )
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

#import "UINavigationController+FDFullscreenPopGesture.h"
#import "KuiklyRenderViewController.h"
#import "SlockConversationForwardGestureAdmission.h"
#import "SlockInteractivePopGestureAdmission.h"
#import <objc/runtime.h>

@interface _FDFullscreenPopGestureRecognizerDelegate : NSObject <UIGestureRecognizerDelegate>

@property (nonatomic, weak) UINavigationController *navigationController;
@property (nonatomic, assign) BOOL touchBeganInEdgeZone;
@property (nonatomic, assign) BOOL touchBeganInPhysicalEdgeZone;
@property (nonatomic, weak) UIView *touchView;

@end

@interface _FDConversationForwardGestureRecognizerDelegate : NSObject <UIGestureRecognizerDelegate>

@property (nonatomic, weak) UINavigationController *navigationController;

@end

@interface UINavigationController (FDConversationForwardGesturePrivate)

@property (nonatomic, strong, readonly) _FDConversationForwardGestureRecognizerDelegate *fd_conversationForwardGestureRecognizerDelegate;
- (void)fd_handleConversationForwardGesture:(UIScreenEdgePanGestureRecognizer *)gestureRecognizer;

@end

static BOOL SlockConversationForwardChildGestureConflict(UIView *view, UIView *rootView)
{
    UIView *candidate = view;
    while (candidate && candidate != rootView) {
        if ([candidate conformsToProtocol:@protocol(UITextInput)]) {
            return YES;
        }
        if ([candidate isKindOfClass:UIScrollView.class]) {
            UIScrollView *scrollView = (UIScrollView *)candidate;
            BOOL horizontal =
                scrollView.alwaysBounceHorizontal ||
                scrollView.contentSize.width > CGRectGetWidth(scrollView.bounds) + 1.0;
            if (horizontal || scrollView.dragging || scrollView.decelerating || scrollView.tracking) {
                return YES;
            }
        }
        candidate = candidate.superview;
    }
    return NO;
}

static BOOL SlockInteractivePopTextInputConflict(UIView *view, UIView *rootView)
{
    UIView *candidate = view;
    while (candidate && candidate != rootView) {
        if ([candidate conformsToProtocol:@protocol(UITextInput)]) return YES;
        candidate = candidate.superview;
    }
    return NO;
}

static BOOL SlockInteractivePopHorizontalScrollCanConsumeDirection(
    UIView *view,
    UIView *rootView,
    BOOL movingRight
)
{
    UIView *candidate = view;
    while (candidate && candidate != rootView) {
        if ([candidate isKindOfClass:UIScrollView.class]) {
            UIScrollView *scrollView = (UIScrollView *)candidate;
            CGFloat minimumOffset = -scrollView.adjustedContentInset.left;
            CGFloat maximumOffset = MAX(
                minimumOffset,
                scrollView.contentSize.width - CGRectGetWidth(scrollView.bounds) + scrollView.adjustedContentInset.right
            );
            if (maximumOffset > minimumOffset + 1.0) {
                CGFloat offset = scrollView.contentOffset.x;
                if (movingRight ? offset > minimumOffset + 0.5 : offset < maximumOffset - 0.5) {
                    return YES;
                }
            }
        }
        candidate = candidate.superview;
    }
    return NO;
}

static BOOL SlockConversationForwardHasActiveTextInput(UIView *view)
{
    if (view.isFirstResponder && [view conformsToProtocol:@protocol(UITextInput)]) {
        return YES;
    }
    for (UIView *subview in view.subviews) {
        if (SlockConversationForwardHasActiveTextInput(subview)) {
            return YES;
        }
    }
    return NO;
}

@implementation _FDConversationForwardGestureRecognizerDelegate

- (BOOL)gestureRecognizer:(UIGestureRecognizer *)gestureRecognizer shouldReceiveTouch:(UITouch *)touch
{
    return !SlockConversationForwardChildGestureConflict(touch.view, gestureRecognizer.view);
}

- (BOOL)gestureRecognizerShouldBegin:(UIScreenEdgePanGestureRecognizer *)gestureRecognizer
{
    UINavigationController *navigationController = self.navigationController;
    KuiklyRenderViewController *topViewController =
        [navigationController.topViewController isKindOfClass:KuiklyRenderViewController.class]
            ? (KuiklyRenderViewController *)navigationController.topViewController
            : nil;
    CGPoint translation = [gestureRecognizer translationInView:gestureRecognizer.view];
    CGPoint velocity = [gestureRecognizer velocityInView:gestureRecognizer.view];
    BOOL transitioning = [[navigationController valueForKey:@"_isTransitioning"] boolValue];
    UIGestureRecognizerState popState = navigationController.fd_fullscreenPopGestureRecognizer.state;
    BOOL interactivePopActive =
        popState == UIGestureRecognizerStateBegan || popState == UIGestureRecognizerStateChanged;
    SlockConversationForwardGestureAdmission admission = {
        .enabled = topViewController.slockConversationForwardGestureEnabled,
        .appShellIsTop = topViewController.slockIsAppShellPage,
        .navigationStackAtRoot = navigationController.viewControllers.count == 1,
        .transitioning = transitioning,
        .modalPresented = topViewController.presentedViewController != nil || navigationController.presentedViewController != nil,
        .interactivePopActive = interactivePopActive,
        .childGestureConflict = NO,
        .textInputActive = SlockConversationForwardHasActiveTextInput(navigationController.view),
        .translationX = translation.x,
        .translationY = translation.y,
        .velocityX = velocity.x,
        .velocityY = velocity.y,
    };
    return SlockConversationForwardGestureShouldBegin(admission);
}

@end

@implementation _FDFullscreenPopGestureRecognizerDelegate

- (BOOL)gestureRecognizer:(UIGestureRecognizer *)gestureRecognizer shouldReceiveTouch:(UITouch *)touch
{
    // Text inputs keep selection/focus gestures. Child horizontal scroll views are
    // allowed to arbitrate by UIKit; the full-screen page policy only admits the
    // back direction once the pager has released it.
    if (gestureRecognizer.numberOfTouches > 0) return YES;
    self.touchView = touch.view;
    if (SlockInteractivePopTextInputConflict(touch.view, gestureRecognizer.view)) {
        self.touchBeganInEdgeZone = NO;
        self.touchBeganInPhysicalEdgeZone = NO;
        return NO;
    }
    UINavigationController *navigationController = self.navigationController;
    UIViewController *topViewController = navigationController.viewControllers.lastObject;
    CGPoint location = [touch locationInView:gestureRecognizer.view];
    SlockInteractivePopTouchAdmission admission = {
        .navigationStackCount = navigationController.viewControllers.count,
        .interactivePopDisabled = topViewController.fd_interactivePopDisabled,
        .allowsFullScreen = topViewController.fd_interactivePopAllowsFullScreen,
        .transitioning = [[navigationController valueForKey:@"_isTransitioning"] boolValue],
        .maxAllowedInitialDistance = topViewController.fd_interactivePopMaxAllowedInitialDistanceToLeftEdge,
        .touchLocationX = location.x,
        .viewWidth = CGRectGetWidth(gestureRecognizer.view.bounds),
        .isLeftToRight = [UIApplication sharedApplication].userInterfaceLayoutDirection == UIUserInterfaceLayoutDirectionLeftToRight,
    };
    self.touchBeganInEdgeZone = SlockInteractivePopTouchBeginsInEdgeZone(admission);
    admission.allowsFullScreen = NO;
    self.touchBeganInPhysicalEdgeZone = SlockInteractivePopTouchBeginsInEdgeZone(admission);
    return YES;
}

- (BOOL)gestureRecognizerShouldBegin:(UIPanGestureRecognizer *)gestureRecognizer
{
    CGPoint translation = [gestureRecognizer translationInView:gestureRecognizer.view];
    CGPoint velocity = [gestureRecognizer velocityInView:gestureRecognizer.view];
    SlockInteractivePopGestureAdmission admission = {
        .touchBeganInEdgeZone = self.touchBeganInEdgeZone,
        .transitioning = [[self.navigationController valueForKey:@"_isTransitioning"] boolValue],
        .translationX = translation.x,
        .translationY = translation.y,
        .velocityX = velocity.x,
        .velocityY = velocity.y,
        .isLeftToRight = [UIApplication sharedApplication].userInterfaceLayoutDirection == UIUserInterfaceLayoutDirectionLeftToRight,
    };
    double horizontal = fabs(velocity.x) > 1.0 ? velocity.x : translation.x;
    BOOL movingRight = [UIApplication sharedApplication].userInterfaceLayoutDirection == UIUserInterfaceLayoutDirectionLeftToRight
        ? horizontal > 0.0
        : horizontal < 0.0;
    if (SlockInteractivePopHorizontalScrollCanConsumeDirection(
            self.touchView,
            gestureRecognizer.view,
            movingRight
        )) {
        return NO;
    }
    return SlockInteractivePopGestureShouldBegin(admission);
}

- (BOOL)gestureRecognizer:(UIGestureRecognizer *)gestureRecognizer shouldBeRequiredToFailByGestureRecognizer:(UIGestureRecognizer *)otherGestureRecognizer
{
    UIView *rootView = self.navigationController.view;
    BOOL otherIsContentPan =
        [otherGestureRecognizer isKindOfClass:UIPanGestureRecognizer.class] &&
        ![otherGestureRecognizer isKindOfClass:UIScreenEdgePanGestureRecognizer.class] &&
        otherGestureRecognizer.view != nil &&
        otherGestureRecognizer.view != gestureRecognizer.view &&
        [otherGestureRecognizer.view isDescendantOfView:rootView];
    return SlockInteractivePopShouldBeRequiredToFailBy(self.touchBeganInPhysicalEdgeZone, otherIsContentPan);
}

@end

typedef void (^_FDViewControllerWillAppearInjectBlock)(UIViewController *viewController, BOOL animated);

@interface UIViewController (FDFullscreenPopGesturePrivate)

@property (nonatomic, copy) _FDViewControllerWillAppearInjectBlock fd_willAppearInjectBlock;

@end

@implementation UIViewController (FDFullscreenPopGesturePrivate)

+ (void)load
{
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Method viewWillAppear_originalMethod = class_getInstanceMethod(self, @selector(viewWillAppear:));
        Method viewWillAppear_swizzledMethod = class_getInstanceMethod(self, @selector(fd_viewWillAppear:));
        method_exchangeImplementations(viewWillAppear_originalMethod, viewWillAppear_swizzledMethod);

        Method viewWillDisappear_originalMethod = class_getInstanceMethod(self, @selector(viewWillDisappear:));
        Method viewWillDisappear_swizzledMethod = class_getInstanceMethod(self, @selector(fd_viewWillDisappear:));
        method_exchangeImplementations(viewWillDisappear_originalMethod, viewWillDisappear_swizzledMethod);
    });
}

- (void)fd_viewWillAppear:(BOOL)animated
{
    // Forward to primary implementation.
    [self fd_viewWillAppear:animated];

    if (self.fd_willAppearInjectBlock) {
        self.fd_willAppearInjectBlock(self, animated);
    }
}

- (void)fd_viewWillDisappear:(BOOL)animated
{
    // Forward to primary implementation.
    [self fd_viewWillDisappear:animated];

//    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
//        UIViewController *viewController = self.navigationController.viewControllers.lastObject;
//        if (viewController && !viewController.fd_prefersNavigationBarHidden) {
//            [self.navigationController setNavigationBarHidden:NO animated:NO];
//        }
//    });
}

- (_FDViewControllerWillAppearInjectBlock)fd_willAppearInjectBlock
{
    return objc_getAssociatedObject(self, _cmd);
}

- (void)setFd_willAppearInjectBlock:(_FDViewControllerWillAppearInjectBlock)block
{
    objc_setAssociatedObject(self, @selector(fd_willAppearInjectBlock), block, OBJC_ASSOCIATION_COPY_NONATOMIC);
}

@end

@implementation UINavigationController (FDFullscreenPopGesture)

+ (void)load
{
    // Inject "-pushViewController:animated:"
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Class class = [self class];

        SEL originalSelector = @selector(pushViewController:animated:);
        SEL swizzledSelector = @selector(fd_pushViewController:animated:);

        Method originalMethod = class_getInstanceMethod(class, originalSelector);
        Method swizzledMethod = class_getInstanceMethod(class, swizzledSelector);

        BOOL success = class_addMethod(class, originalSelector, method_getImplementation(swizzledMethod), method_getTypeEncoding(swizzledMethod));
        if (success) {
            class_replaceMethod(class, swizzledSelector, method_getImplementation(originalMethod), method_getTypeEncoding(originalMethod));
        } else {
            method_exchangeImplementations(originalMethod, swizzledMethod);
        }
    });
}

- (void)fd_pushViewController:(UIViewController *)viewController animated:(BOOL)animated
{
    if (![self.interactivePopGestureRecognizer.view.gestureRecognizers containsObject:self.fd_fullscreenPopGestureRecognizer]) {

        // Add our own gesture recognizer to where the onboard screen edge pan gesture recognizer is attached to.
        [self.interactivePopGestureRecognizer.view addGestureRecognizer:self.fd_fullscreenPopGestureRecognizer];

        // Forward the gesture events to the private handler of the onboard gesture recognizer.
        NSArray *internalTargets = [self.interactivePopGestureRecognizer valueForKey:@"targets"];
        id internalTarget = [internalTargets.firstObject valueForKey:@"target"];
        SEL internalAction = NSSelectorFromString(@"handleNavigationTransition:");
        self.fd_fullscreenPopGestureRecognizer.delegate = self.fd_popGestureRecognizerDelegate;
        [self.fd_fullscreenPopGestureRecognizer addTarget:internalTarget action:internalAction];

        // Disable the onboard gesture recognizer.
        self.interactivePopGestureRecognizer.enabled = NO;
    }

    KuiklyRenderViewController *renderViewController =
        [viewController isKindOfClass:KuiklyRenderViewController.class]
            ? (KuiklyRenderViewController *)viewController
            : nil;
    if (renderViewController) {
        // Every pushed Kuikly page can be returned from. The admission policy
        // still requires a navigation stack above root; text-input and native
        // horizontal-scroll conflicts are rejected at touch-down above.
        renderViewController.fd_interactivePopAllowsFullScreen = YES;
    }
    [self fd_setConversationForwardGestureEnabled:
        renderViewController.slockConversationForwardGestureEnabled && renderViewController.slockIsAppShellPage];

    // Handle perferred navigation bar appearance.
    [self fd_setupViewControllerBasedNavigationBarAppearanceIfNeeded:viewController];

    // Forward to primary implementation.
    if (![self.viewControllers containsObject:viewController]) {
        [self fd_pushViewController:viewController animated:animated];
    }
}

- (void)fd_handleConversationForwardGesture:(UIScreenEdgePanGestureRecognizer *)gestureRecognizer
{
    if (gestureRecognizer.state != UIGestureRecognizerStateEnded) {
        return;
    }
    CGFloat width = MAX(1.0, CGRectGetWidth(gestureRecognizer.view.bounds));
    CGPoint translation = [gestureRecognizer translationInView:gestureRecognizer.view];
    CGPoint velocity = [gestureRecognizer velocityInView:gestureRecognizer.view];
    CGFloat progress = MAX(0.0, -translation.x / width);
    if (progress < 0.12 && velocity.x > -300.0) {
        return;
    }
    KuiklyRenderViewController *topViewController =
        [self.topViewController isKindOfClass:KuiklyRenderViewController.class]
            ? (KuiklyRenderViewController *)self.topViewController
            : nil;
    [topViewController slockRequestConversationForward];
}

- (void)fd_setConversationForwardGestureEnabled:(BOOL)enabled
{
    UIScreenEdgePanGestureRecognizer *gestureRecognizer =
        objc_getAssociatedObject(self, @selector(fd_conversationForwardGestureRecognizer));
    if (!enabled) {
        gestureRecognizer.enabled = NO;
        if (gestureRecognizer.view != nil) {
            [gestureRecognizer.view removeGestureRecognizer:gestureRecognizer];
        }
        return;
    }

    gestureRecognizer = self.fd_conversationForwardGestureRecognizer;
    if (gestureRecognizer.view != self.view) {
        if (gestureRecognizer.view != nil) {
            [gestureRecognizer.view removeGestureRecognizer:gestureRecognizer];
        }
        [self.view addGestureRecognizer:gestureRecognizer];
    }
    gestureRecognizer.enabled = YES;
}

- (void)fd_setupViewControllerBasedNavigationBarAppearanceIfNeeded:(UIViewController *)appearingViewController
{
    if (!self.fd_viewControllerBasedNavigationBarAppearanceEnabled) {
        return;
    }

    __weak typeof(self) weakSelf = self;
    _FDViewControllerWillAppearInjectBlock block = ^(UIViewController *viewController, BOOL animated) {
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (strongSelf) {
            [strongSelf setNavigationBarHidden:viewController.fd_prefersNavigationBarHidden animated:animated];
        }
    };

    // Setup will appear inject block to appearing view controller.
    // Setup disappearing view controller as well, because not every view controller is added into
    // stack by pushing, maybe by "-setViewControllers:".
    appearingViewController.fd_willAppearInjectBlock = block;
    UIViewController *disappearingViewController = self.viewControllers.lastObject;
    if (disappearingViewController && !disappearingViewController.fd_willAppearInjectBlock) {
        disappearingViewController.fd_willAppearInjectBlock = block;
    }
}

- (_FDFullscreenPopGestureRecognizerDelegate *)fd_popGestureRecognizerDelegate
{
    _FDFullscreenPopGestureRecognizerDelegate *delegate = objc_getAssociatedObject(self, _cmd);

    if (!delegate) {
        delegate = [[_FDFullscreenPopGestureRecognizerDelegate alloc] init];
        delegate.navigationController = self;

        objc_setAssociatedObject(self, _cmd, delegate, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    return delegate;
}

- (UIPanGestureRecognizer *)fd_fullscreenPopGestureRecognizer
{
    UIPanGestureRecognizer *panGestureRecognizer = objc_getAssociatedObject(self, _cmd);

    if (!panGestureRecognizer) {
        panGestureRecognizer = [[UIPanGestureRecognizer alloc] init];
        panGestureRecognizer.maximumNumberOfTouches = 1;

        objc_setAssociatedObject(self, _cmd, panGestureRecognizer, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    return panGestureRecognizer;
}

- (_FDConversationForwardGestureRecognizerDelegate *)fd_conversationForwardGestureRecognizerDelegate
{
    _FDConversationForwardGestureRecognizerDelegate *delegate = objc_getAssociatedObject(self, _cmd);
    if (!delegate) {
        delegate = [[_FDConversationForwardGestureRecognizerDelegate alloc] init];
        delegate.navigationController = self;
        objc_setAssociatedObject(self, _cmd, delegate, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    return delegate;
}

- (UIScreenEdgePanGestureRecognizer *)fd_conversationForwardGestureRecognizer
{
    UIScreenEdgePanGestureRecognizer *gestureRecognizer = objc_getAssociatedObject(self, _cmd);
    if (!gestureRecognizer) {
        gestureRecognizer = [[UIScreenEdgePanGestureRecognizer alloc] init];
        gestureRecognizer.edges = UIRectEdgeRight;
        gestureRecognizer.maximumNumberOfTouches = 1;
        gestureRecognizer.cancelsTouchesInView = NO;
        gestureRecognizer.delegate = self.fd_conversationForwardGestureRecognizerDelegate;
        [gestureRecognizer addTarget:self action:@selector(fd_handleConversationForwardGesture:)];
        objc_setAssociatedObject(self, _cmd, gestureRecognizer, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    return gestureRecognizer;
}

- (BOOL)fd_viewControllerBasedNavigationBarAppearanceEnabled
{
    NSNumber *number = objc_getAssociatedObject(self, _cmd);
    if (number) {
        return number.boolValue;
    }
    self.fd_viewControllerBasedNavigationBarAppearanceEnabled = YES;
    return YES;
}

- (void)setFd_viewControllerBasedNavigationBarAppearanceEnabled:(BOOL)enabled
{
    SEL key = @selector(fd_viewControllerBasedNavigationBarAppearanceEnabled);
    objc_setAssociatedObject(self, key, @(enabled), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

@end

@implementation UIViewController (FDFullscreenPopGesture)

- (BOOL)fd_interactivePopAllowsFullScreen
{
    return [objc_getAssociatedObject(self, _cmd) boolValue];
}

- (void)setFd_interactivePopAllowsFullScreen:(BOOL)enabled
{
    objc_setAssociatedObject(self, @selector(fd_interactivePopAllowsFullScreen), @(enabled), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (BOOL)fd_interactivePopDisabled
{
    return [objc_getAssociatedObject(self, _cmd) boolValue];
}

- (void)setFd_interactivePopDisabled:(BOOL)disabled
{
    objc_setAssociatedObject(self, @selector(fd_interactivePopDisabled), @(disabled), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (BOOL)fd_prefersNavigationBarHidden
{
    return [objc_getAssociatedObject(self, _cmd) boolValue];
}

- (void)setFd_prefersNavigationBarHidden:(BOOL)hidden
{
    objc_setAssociatedObject(self, @selector(fd_prefersNavigationBarHidden), @(hidden), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (CGFloat)fd_interactivePopMaxAllowedInitialDistanceToLeftEdge
{
#if CGFLOAT_IS_DOUBLE
    return [objc_getAssociatedObject(self, _cmd) doubleValue];
#else
    return [objc_getAssociatedObject(self, _cmd) floatValue];
#endif
}

- (void)setFd_interactivePopMaxAllowedInitialDistanceToLeftEdge:(CGFloat)distance
{
    SEL key = @selector(fd_interactivePopMaxAllowedInitialDistanceToLeftEdge);
    objc_setAssociatedObject(self, key, @(MAX(0, distance)), OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

@end
