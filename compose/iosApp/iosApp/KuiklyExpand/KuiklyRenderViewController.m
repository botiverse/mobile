#import "KuiklyRenderViewController.h"
#import "SlockAdaptiveNavigationSession.h"
#import "SlockAdaptiveEditorSnapshot.h"
#import <OpenKuiklyIOSRender/KuiklyRenderView.h>
#import <OpenKuiklyIOSRender/KRTextAreaView.h>
#import "Modules/HRBridgeModule.h"
#import "UINavigationController+FDFullscreenPopGesture.h"
#import "iosApp-Swift.h"
#import <OpenKuiklyIOSRender/KuiklyRenderViewControllerBaseDelegator.h>
#import <OpenKuiklyIOSRender/KuiklyRenderContextProtocol.h>
#import <math.h>

static const NSInteger SlockKuiklyKeyEventTypeDown = 2;
static const NSInteger SlockKuiklyKeyCodeTab = 9;
static const NSTimeInterval SlockKuiklyMillisPerDay = 86400000.0;
static const NSTimeInterval SlockKuiklyMillisPerMinute = 60000.0;
static NSString *const SlockAppShellPageName = @"compose_slock_app_shell";
static NSString *const SlockConversationForwardPagerEvent = @"slockConversationForwardRequested";
static NSString *const SlockAdaptivePanePagerEvent = @"slockAdaptivePaneChanged";

static void SlockKuiklyPutHostTimeContextIfMissing(NSMutableDictionary *pageData) {
    NSDate *now = [NSDate date];
    NSTimeInterval nowMillis = [now timeIntervalSince1970] * 1000.0;
    NSInteger offsetMinutes = [[NSTimeZone localTimeZone] secondsFromGMTForDate:now] / 60;
    if (pageData[@"localOffsetMinutes"] == nil) {
        pageData[@"localOffsetMinutes"] = @(offsetMinutes);
    }
    if (pageData[@"todayEpochDay"] == nil) {
        long long todayEpochDay =
            (long long)floor((nowMillis + offsetMinutes * SlockKuiklyMillisPerMinute) / SlockKuiklyMillisPerDay);
        pageData[@"todayEpochDay"] = @(todayEpochDay);
    }
    if (pageData[@"nowEpochMillis"] == nil) {
        pageData[@"nowEpochMillis"] = @((long long)nowMillis);
    }
}

static UIWindow *SlockKuiklyCurrentKeyWindow(void) {
    NSSet<UIScene *> *connectedScenes = UIApplication.sharedApplication.connectedScenes;
    for (UIScene *scene in connectedScenes) {
        if (![scene isKindOfClass:UIWindowScene.class]) {
            continue;
        }
        UIWindowScene *windowScene = (UIWindowScene *)scene;
        if (windowScene.activationState != UISceneActivationStateForegroundActive &&
            windowScene.activationState != UISceneActivationStateForegroundInactive) {
            continue;
        }
        for (UIWindow *window in windowScene.windows) {
            if (window.isKeyWindow) {
                return window;
            }
        }
    }
    for (UIWindow *window in UIApplication.sharedApplication.windows) {
        if (window.isKeyWindow) {
            return window;
        }
    }
    return UIApplication.sharedApplication.windows.firstObject;
}

static CGFloat SlockKuiklyStatusBarHeight(UIWindow *window) {
    if (@available(iOS 13.0, *)) {
        CGRect statusBarFrame = window.windowScene.statusBarManager.statusBarFrame;
        if (!CGRectIsEmpty(statusBarFrame)) {
            return CGRectGetHeight(statusBarFrame);
        }
    }
    return CGRectGetHeight(UIApplication.sharedApplication.statusBarFrame);
}

static NSString *SlockKuiklyPixelString(CGFloat points, CGFloat scale) {
    CGFloat pixels = MAX(0, points) * MAX(1, scale);
    return [NSString stringWithFormat:@"%.3f", pixels];
}

#define HRWeakSelf __weak typeof(self) weakSelf = self;
@interface KuiklyRenderViewController()<KuiklyRenderViewControllerBaseDelegatorDelegate>

@property (nonatomic, strong) KuiklyRenderViewControllerBaseDelegator *delegator;

@end

@implementation KuiklyRenderViewController {
    NSString *_slockControllerToken;
    NSUInteger _slockNavigationRevision;
    NSString *_slockNavigationGeometry;
    SlockAdaptiveNavigationSession *_slockNavigationSession;
    __weak KuiklyRenderViewController *_slockNavigationTarget;
    __weak UIWindow *_slockNavigationWindow;
    NSNumber *_slockNavigationEditorRefTag;
#if DEBUG
    // QA-only carrier, default off. Retain the old object so pointer identity is observable.
    UITextView *_slockQaPreparedEditor;
    NSDictionary *_slockQaPreparedEditorState;
    NSDictionary *_slockQaPreparedContext;
    NSString *_slockQaPreparedRequest;
#endif
    NSString *_pageName;
    NSDictionary *_pageData;
    BOOL _slockConversationForwardGestureEnabled;
    BOOL _slockAdaptivePanePublishingActive;
    SlockIosAdaptivePanePublisher *_slockAdaptivePanePublisher;
}

- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData {
    if (self = [super init]) {
        pageData = [self p_mergeExtParamsWithOriditalParam:pageData];
        _slockControllerToken = NSUUID.UUID.UUIDString;
        _slockNavigationSession = [SlockAdaptiveNavigationSession new];
        [NSNotificationCenter.defaultCenter addObserver:self
            selector:@selector(slockInvalidateAdaptiveNavigation)
            name:UIApplicationWillResignActiveNotification object:nil];
        _pageName = [pageName copy] ?: @"";
        _pageData = pageData;
        _delegator = [[KuiklyRenderViewControllerBaseDelegator alloc] initWithPageName:pageName pageData:pageData];
        _delegator.delegate = self;
        _slockAdaptivePanePublisher = [[SlockIosAdaptivePanePublisher alloc] init];
    }
    return self;
}

- (BOOL)slockIsAppShellPage {
    return [_pageName isEqualToString:SlockAppShellPageName];
}

- (BOOL)slockConversationForwardGestureEnabled {
    return _slockConversationForwardGestureEnabled;
}

- (void)setSlockConversationForwardGestureEnabled:(BOOL)enabled {
    _slockConversationForwardGestureEnabled = enabled;
    [self.navigationController fd_setConversationForwardGestureEnabled:
        enabled && self.slockIsAppShellPage];
}

- (void)slockRequestConversationForward {
    if (!self.slockIsAppShellPage || !self.slockConversationForwardGestureEnabled) {
        return;
    }
    self.slockConversationForwardGestureEnabled = NO;
    [_delegator sendWithEvent:SlockConversationForwardPagerEvent data:@{}];
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.fd_prefersNavigationBarHidden = YES;
    self.view.backgroundColor = [UIColor whiteColor];
    [_delegator viewDidLoadWithView:self.view];
    [self.navigationController setNavigationBarHidden:YES animated:NO];

}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [_delegator viewDidLayoutSubviews];
    [self slockRefreshNavigationGeometry];
    [self slockPublishAdaptivePaneForCurrentBounds:@"layout"];

}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [_delegator viewWillAppear];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
}

- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    [_delegator viewDidAppear];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
    _slockAdaptivePanePublishingActive = YES;
    [self slockPublishAdaptivePaneForCurrentBounds:@"appear"];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    _slockAdaptivePanePublishingActive = NO;
    [self slockInvalidateAdaptiveNavigation];
    [_delegator viewWillDisappear];
}

- (void)viewDidDisappear:(BOOL)animated {
    [super viewDidDisappear:animated];
    [_delegator viewDidDisappear];
}

- (void)viewWillTransitionToSize:(CGSize)size
       withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
    [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];
    [self slockInvalidateAdaptiveNavigation];
    __weak typeof(self) weakSelf = self;
    [coordinator animateAlongsideTransition:nil completion:^(__unused id<UIViewControllerTransitionCoordinatorContext> context) {
        [weakSelf slockPublishAdaptivePaneForCurrentBounds:@"viewWillTransition"];
    }];
}

- (void)traitCollectionDidChange:(UITraitCollection *)previousTraitCollection {
    [super traitCollectionDidChange:previousTraitCollection];
    if (previousTraitCollection == nil ||
        previousTraitCollection.horizontalSizeClass != self.traitCollection.horizontalSizeClass ||
        previousTraitCollection.verticalSizeClass != self.traitCollection.verticalSizeClass) {
        [self slockPublishAdaptivePaneForCurrentBounds:@"sizeClass"];
    }
}

- (NSArray<UIKeyCommand *> *)keyCommands {
    return @[
        [UIKeyCommand keyCommandWithInput:@"\t"
                            modifierFlags:0
                                   action:@selector(handleTabKeyCommand:)],
        [UIKeyCommand keyCommandWithInput:@"\t"
                            modifierFlags:UIKeyModifierShift
                                   action:@selector(handleTabKeyCommand:)]
    ];
}

- (void)handleTabKeyCommand:(UIKeyCommand *)command {
    BOOL shiftPressed = (command.modifierFlags & UIKeyModifierShift) == UIKeyModifierShift;
    [_delegator sendKeyEventWithKeyCode:SlockKuiklyKeyCodeTab
                                   type:SlockKuiklyKeyEventTypeDown
                        utf16CodePoint:SlockKuiklyKeyCodeTab
                             altPressed:NO
                            ctrlPressed:NO
                            metaPressed:NO
                           shiftPressed:shiftPressed];
}

// A4 native primitives only. Shared admission remains false until the entire restore protocol works.
- (void)slockInvalidateAdaptiveNavigation {
    _slockNavigationRevision += 1;
}

- (void)slockRefreshNavigationGeometry {
    UIWindow *window = self.viewIfLoaded.window;
    NSString *geometry = [NSString stringWithFormat:@"%@|%@|%g", NSStringFromCGRect(window.bounds),
                          NSStringFromCGRect(self.viewIfLoaded.bounds), window.screen.scale];
    if (![_slockNavigationGeometry isEqual:geometry]) {
        _slockNavigationGeometry = geometry;
        [self slockInvalidateAdaptiveNavigation];
    }
}

- (KuiklyRenderViewController *)slockAdaptiveNavigationTargetForRoot:(UIView *)root {
    // Never fall back to the key window/top controller: a stale module must not acquire a new owner.
    if (root != (id)_delegator.renderView || self.slockIsAppShellPage ||
        ![_pageName isEqualToString:@"compose_thread"]) return nil;
    UINavigationController *nav = self.navigationController;
    UIWindow *window = self.viewIfLoaded.window;
    if (!_slockAdaptivePanePublishingActive || window == nil || !window.isKeyWindow ||
        window.windowScene.activationState != UISceneActivationStateForegroundActive ||
        nav.topViewController != self || nav.viewControllers.count < 2 ||
        nav.transitionCoordinator != nil || self.transitionCoordinator != nil ||
        self.presentedViewController != nil || nav.presentedViewController != nil ||
        self.isBeingDismissed || self.isMovingFromParentViewController ||
        window.bounds.size.width < 840.0) return nil;
    // A SwiftUI/container ancestor can present a sheet without disappearing this child.
    for (UIViewController *ancestor = self.parentViewController; ancestor; ancestor = ancestor.parentViewController) {
        if (ancestor.presentedViewController || ancestor.transitionCoordinator || ancestor.isBeingDismissed) return nil;
    }
    UIViewController *previous = nav.viewControllers[nav.viewControllers.count - 2];
    if (![previous isKindOfClass:KuiklyRenderViewController.class] ||
        !((KuiklyRenderViewController *)previous).slockIsAppShellPage) return nil;
    return (KuiklyRenderViewController *)previous;
}

- (NSDictionary *)slockAdaptiveNavigationContext:(KuiklyRenderViewController *)target {
    [self slockRefreshNavigationGeometry];
    return target ? @{ @"sourceControllerToken": _slockControllerToken,
                       @"targetControllerToken": target->_slockControllerToken,
                       @"sourceRevision": @(_slockNavigationRevision),
                       @"geometry": _slockNavigationGeometry ?: @"" } : @{};
}

// The ref belongs to this exact renderer. Never search the view tree, first responder,
// key window or a different page for an editor when the bound ref is unavailable.
- (UITextView *)slockAdaptiveEditorWithRefTag:(NSNumber *)refTag root:(UIView *)root {
    if (![refTag isKindOfClass:NSNumber.class] ||
        CFGetTypeID((__bridge CFTypeRef)refTag) == CFBooleanGetTypeID() ||
        !isfinite(refTag.doubleValue) || refTag.doubleValue < 0 ||
        floor(refTag.doubleValue) != refTag.doubleValue ||
        ![root isKindOfClass:KuiklyRenderView.class]) return nil;
    id editor = [(KuiklyRenderView *)root viewWithRefTag:refTag];
    if (![editor isKindOfClass:KRTextAreaView.class]) return nil;
    UITextView *textView = editor;
    if (![textView isDescendantOfView:root] || !textView.window || textView.window != root.window) return nil;
    return textView;
}

- (NSDictionary *)slockAdaptiveNavigation:(NSDictionary *)params root:(UIView *)root {
    NSAssert(NSThread.isMainThread, @"Adaptive navigation must execute on the main thread");
    NSString *(^string)(NSString *) = ^NSString *(NSString *key) {
        return [params[key] isKindOfClass:NSString.class] ? params[key] : @"";
    };
    NSString *requestId = string(@"requestId");
    NSString *action = string(@"action");
    NSString *token = string(@"token");
    if (root != (id)_delegator.renderView) {
        return @{ @"requestId": requestId, @"status": @"rejected", @"reason": @"root_mismatch" };
    }
    if ([action isEqual:@"cancel"]) {
        NSDictionary *result = [_slockNavigationSession cancel:requestId token:token];
#if DEBUG
        if ([_slockQaPreparedRequest isEqual:requestId] &&
            ![result[@"reason"] isEqual:@"request_mismatch"]) {
            [self slockQaReleasePreparedEditor];
        }
#endif
        return result;
    }
    KuiklyRenderViewController *target = [self slockAdaptiveNavigationTargetForRoot:root];
    NSDictionary *context = [self slockAdaptiveNavigationContext:target];
    NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
    if ([action isEqual:@"prepare"]) {
        NSDictionary *expectedEditor = [params[@"expectedEditor"] isKindOfClass:NSDictionary.class]
            ? params[@"expectedEditor"] : nil;
        NSNumber *refTag = expectedEditor[@"refTag"];
        UITextView *editor = [self slockAdaptiveEditorWithRefTag:refTag root:root];
        NSDictionary *editorState = SlockAdaptiveEditorSnapshot(editor);
        NSString *expectedText = expectedEditor[@"text"];
        if (![expectedText isKindOfClass:NSString.class] || ![editorState[@"text"] isEqual:expectedText]) {
            editorState = nil;
        }
        NSDictionary *result = [_slockNavigationSession prepare:requestId context:context
                                                         editor:editor editorState:editorState now:now];
        if ([result[@"status"] isEqual:@"prepared"]) {
            _slockNavigationTarget = target;
            _slockNavigationWindow = self.viewIfLoaded.window;
            _slockNavigationEditorRefTag = [refTag copy];
#if DEBUG
            if ([_pageData[@"slockQaReplaceAdaptiveEditor"] boolValue]) {
                _slockQaPreparedEditor = editor;
                _slockQaPreparedEditorState = [editorState copy];
                _slockQaPreparedContext = [context copy];
                _slockQaPreparedRequest = [requestId copy];
            }
#endif
        }
        return result;
    }
    if (![action isEqual:@"commit"]) {
        return @{ @"requestId": requestId, @"status": @"rejected", @"reason": @"invalid_action" };
    }
    // Keep the destination alive through pop. Never use a source callback as the success receipt.
    KuiklyRenderViewController *preparedTarget = _slockNavigationTarget;
    if (target != preparedTarget || self.viewIfLoaded.window != _slockNavigationWindow) context = @{};
    UITextView *editor = [self slockAdaptiveEditorWithRefTag:_slockNavigationEditorRefTag root:root];
#if DEBUG
    if ([_pageData[@"slockQaReplaceAdaptiveEditor"] boolValue] &&
        [_slockQaPreparedRequest isEqual:requestId]) {
        BOOL contextEqual = [_slockQaPreparedContext isEqual:context];
        BOOL objectChanged = editor != nil && _slockQaPreparedEditor != nil && editor != _slockQaPreparedEditor;
        BOOL textEqual = [editor.text isEqual:_slockQaPreparedEditorState[@"text"]];
        // Renderer recreation replays text/props but not UIKit selection. Keep that independent
        // state identical so rejection can be attributed to object identity, not a cursor move.
        if (contextEqual && objectChanged && textEqual && editor.markedTextRange == nil) {
            NSUInteger start = [_slockQaPreparedEditorState[@"selectionStart"] unsignedIntegerValue];
            NSUInteger end = [_slockQaPreparedEditorState[@"selectionEnd"] unsignedIntegerValue];
            editor.selectedRange = NSMakeRange(start, end - start);
        }
        NSDictionary *newState = SlockAdaptiveEditorSnapshot(editor);
        SlockIosDiagnosticsRecord(@"info", @"SlockAdaptiveQA", [NSString stringWithFormat:
            @"editor_replacement requestId=%@ ref=%@ contextEqual=%d objectChanged=%d oldDetached=%d newInRoot=%d textEqual=%d snapshotEqual=%d textLength=%lu selectionStart=%@ selectionEnd=%@",
            requestId, _slockNavigationEditorRefTag, contextEqual, objectChanged,
            ![_slockQaPreparedEditor isDescendantOfView:root],
            editor != nil && [editor isDescendantOfView:root], textEqual,
            [_slockQaPreparedEditorState isEqual:newState], (unsigned long)editor.text.length,
            newState[@"selectionStart"], newState[@"selectionEnd"]]);
    }
#endif
    NSDictionary *editorState = SlockAdaptiveEditorSnapshot(editor);
    NSDictionary *result = [_slockNavigationSession commit:requestId token:token context:context
                                                   editor:editor editorState:editorState now:now perform:^BOOL {
        UINavigationController *nav = self.navigationController;
        // Synchronous removal: UIKit stack mutation and its readback occur in this main-thread turn.
        // No asynchronous animation completion can race a subsequent user navigation.
        if (nav.topViewController != self || nav.viewControllers.count < 2 ||
            nav.viewControllers[nav.viewControllers.count - 2] != preparedTarget) return NO;
        UIViewController *removed = [nav popViewControllerAnimated:NO];
        return removed == self && nav.topViewController == preparedTarget &&
            ![nav.viewControllers containsObject:self];
    }];
#if DEBUG
    if ([_pageData[@"slockQaReplaceAdaptiveEditor"] boolValue]) {
        SlockIosDiagnosticsRecord(@"info", @"SlockAdaptiveQA", [NSString stringWithFormat:
            @"editor_replacement_result requestId=%@ status=%@ reason=%@ sourceStillTop=%d sourceInStack=%d",
            requestId, result[@"status"], result[@"reason"], self.navigationController.topViewController == self,
            [self.navigationController.viewControllers containsObject:self]]);
    }
#endif
    NSMutableDictionary *receipt = [result mutableCopy];
    receipt[@"sourceControllerToken"] = _slockControllerToken;
    receipt[@"targetControllerToken"] = preparedTarget ? preparedTarget->_slockControllerToken : @"";
    // The destination revalidates requestId/authority before consuming any recovery state.
    // Failure or a missing receipt never permits shared to consume the anchor.
    if (preparedTarget) {
        [preparedTarget.delegator sendWithEvent:@"slockAdaptiveNativeCommitResult" data:receipt];
    }
#if DEBUG
    if ([_slockQaPreparedRequest isEqual:requestId] &&
        ![result[@"reason"] isEqual:@"request_mismatch"]) {
        [self slockQaReleasePreparedEditor];
    }
#endif
    return receipt;
}

#if DEBUG
- (void)slockQaReleasePreparedEditor {
    _slockQaPreparedEditor = nil;
    _slockQaPreparedEditorState = nil;
    _slockQaPreparedContext = nil;
    _slockQaPreparedRequest = nil;
}
#endif

#pragma mark - private

- (void)slockPublishAdaptivePaneForCurrentBounds:(NSString *)reason {
    if (!_slockAdaptivePanePublishingActive) {
        return;
    }
    UIWindow *window = self.view.window ?: SlockKuiklyCurrentKeyWindow();
    CGSize size = window != nil ? window.bounds.size : self.view.bounds.size;
    CGFloat scale = window.screen.scale ?: UIScreen.mainScreen.scale;
    NSDictionary *payload =
        [_slockAdaptivePanePublisher nextPayloadWithWidthPoints:size.width
                                                  heightPoints:size.height
                                                         scale:scale];
    if (payload == nil) {
        return;
    }
    [_delegator sendWithEvent:SlockAdaptivePanePagerEvent data:payload];
    NSString *message =
        [NSString stringWithFormat:
            @"ios_window_facts page=%@ widthPx=%@ heightPx=%@ density=%@ posture=flat hSizeClass=%ld vSizeClass=%ld reason=%@",
            _pageName ?: @"unknown",
            payload[@"widthPx"],
            payload[@"heightPx"],
            payload[@"density"],
            (long)self.traitCollection.horizontalSizeClass,
            (long)self.traitCollection.verticalSizeClass,
            reason ?: @"unknown"];
    SlockIosDiagnosticsRecord(@"info", @"SlockAdaptivePane", message);
}

- (NSDictionary *)p_mergeExtParamsWithOriditalParam:(NSDictionary *)pageParam {
    NSMutableDictionary *mParam = [(pageParam ?: @{}) mutableCopy];
    SlockKuiklyPutHostTimeContextIfMissing(mParam);
#if DEBUG
    // Local test-carrier launch intent, never inherited as product content or enabled by default.
    mParam[@"slockQaReplaceAdaptiveEditor"] = @([NSProcessInfo.processInfo.environment[@"SLOCK_QA_REPLACE_ADAPTIVE_EDITOR"] isEqual:@"1"]);
#endif
    // iOS Kuikly pageData safeAreaInsets are reported in native pixels, while
    // shared Compose layout uses dp. Mark the unit so shared safe-area helpers
    // convert through LocalDensity instead of treating 102px as 102dp.
    mParam[@"slockNativeSafeAreaInsetsArePixels"] = @YES;
    UIWindow *window = SlockKuiklyCurrentKeyWindow();
    CGFloat scale = window.screen.scale ?: UIScreen.mainScreen.scale;
    UIEdgeInsets safeAreaInsets = window.safeAreaInsets;
    CGFloat statusBarHeight = SlockKuiklyStatusBarHeight(window);
    mParam[@"safeAreaTop"] = SlockKuiklyPixelString(safeAreaInsets.top, scale);
    mParam[@"safeAreaBottom"] = SlockKuiklyPixelString(safeAreaInsets.bottom, scale);
    mParam[@"statusBarHeight"] = SlockKuiklyPixelString(statusBarHeight, scale);

    return mParam;
}

#pragma mark - KuiklyRenderViewControllerDelegatorDelegate

- (UIView *)createLoadingView {
    UIView *loadingView = [[UIView alloc] init];
    loadingView.backgroundColor = [UIColor whiteColor];
    return loadingView;
}

- (UIView *)createErrorView {
    UIView *errorView = [[UIView alloc] init];
    errorView.backgroundColor = [UIColor whiteColor];
    return errorView;
}

- (void)fetchContextCodeWithPageName:(NSString *)pageName resultCallback:(KuiklyContextCodeCallback)callback {
    if (callback) {
        // 返回对应framework名字
        callback(@"shared", nil);
    }
}

- (void)onUnhandledException:(NSString *)exReason stack:(NSString *)callstackStr mode:(KuiklyContextMode)mode {
    NSString *reason = exReason.length > 0 ? exReason : @"KuiklyUnhandledException";
    NSString *stack = callstackStr ?: @"";
    SlockIosDiagnosticsRecordUnhandledException(
        _pageName.length > 0 ? _pageName : @"unknown",
        (NSInteger)mode,
        reason,
        stack
    );
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

@end
