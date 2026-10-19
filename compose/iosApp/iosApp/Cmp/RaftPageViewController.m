#import "RaftPageViewController.h"
#import "SlockAdaptiveNavigationSession.h"
#import "SlockAdaptiveEditorSnapshot.h"
#import "HRBridgeModule.h"
#import "UINavigationController+FDFullscreenPopGesture.h"
#import "iosApp-Swift.h"
#import <math.h>

static const NSTimeInterval RaftPageMillisPerDay = 86400000.0;
static const NSTimeInterval RaftPageMillisPerMinute = 60000.0;
static NSString *const SlockAppShellPageName = @"compose_slock_app_shell";
static NSString *const SlockConversationForwardPagerEvent = @"slockConversationForwardRequested";
static NSString *const SlockAdaptivePanePagerEvent = @"slockAdaptivePaneChanged";
static NSString *const SlockAdaptiveNativeCommitResultEvent = @"slockAdaptiveNativeCommitResult";
// Shared contract (SlockSafeArea.kt): live window insets in pixels, keyed by pageData slockWindowId.
static NSString *const SlockWindowInsetsPagerEvent = @"slockWindowInsetsChanged";
static NSString *const SlockHostWindowIdKey = @"slockWindowId";
// Kuikly pager event the shared BasePager reads (`isNightMode`).
static NSString *const SlockThemeDidChangePagerEvent = @"themeDidChanged";

static void RaftPagePutHostTimeContextIfMissing(NSMutableDictionary *pageData) {
    NSDate *now = [NSDate date];
    NSTimeInterval nowMillis = [now timeIntervalSince1970] * 1000.0;
    NSInteger offsetMinutes = [[NSTimeZone localTimeZone] secondsFromGMTForDate:now] / 60;
    if (pageData[@"localOffsetMinutes"] == nil) {
        pageData[@"localOffsetMinutes"] = @(offsetMinutes);
    }
    if (pageData[@"todayEpochDay"] == nil) {
        long long todayEpochDay =
            (long long)floor((nowMillis + offsetMinutes * RaftPageMillisPerMinute) / RaftPageMillisPerDay);
        pageData[@"todayEpochDay"] = @(todayEpochDay);
    }
    if (pageData[@"nowEpochMillis"] == nil) {
        pageData[@"nowEpochMillis"] = @((long long)nowMillis);
    }
}

static UIWindow *RaftPageCurrentKeyWindow(void) {
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
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    for (UIWindow *window in UIApplication.sharedApplication.windows) {
        if (window.isKeyWindow) {
            return window;
        }
    }
    return UIApplication.sharedApplication.windows.firstObject;
#pragma clang diagnostic pop
}

static CGFloat RaftPageStatusBarHeight(UIWindow *window) {
    CGRect statusBarFrame = window.windowScene.statusBarManager.statusBarFrame;
    if (!CGRectIsEmpty(statusBarFrame)) {
        return CGRectGetHeight(statusBarFrame);
    }
    return 0;
}

static NSString *RaftPagePixelString(CGFloat points, CGFloat scale) {
    CGFloat pixels = MAX(0, points) * MAX(1, scale);
    return [NSString stringWithFormat:@"%.3f", pixels];
}

/** Per-scene window id for the shared window-inset snapshots (SlockSafeArea.kt). */
static NSString *RaftPageWindowId(UIWindow *window) {
    NSString *session = window.windowScene.session.persistentIdentifier;
    return session.length > 0 ? [@"ios:" stringByAppendingString:session] : @"ios:default";
}

@implementation RaftPageViewController {
    NSString *_slockControllerToken;
    NSUInteger _slockNavigationRevision;
    NSString *_slockNavigationGeometry;
    SlockAdaptiveNavigationSession *_slockNavigationSession;
    __weak RaftPageViewController *_slockNavigationTarget;
    __weak UIWindow *_slockNavigationWindow;
    NSString *_pageName;
    NSDictionary *_pageData;
    UIViewController *_contentController;
    void (^_sendEvent)(NSString *event, NSDictionary *data);
    BOOL _slockConversationForwardGestureEnabled;
    BOOL _slockAdaptivePanePublishingActive;
    SlockIosAdaptivePanePublisher *_slockAdaptivePanePublisher;
    NSString *_lastPublishedInsetsKey;
    NSNumber *_darkTheme;
}

- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData {
    if (self = [super initWithNibName:nil bundle:nil]) {
        _slockControllerToken = NSUUID.UUID.UUIDString;
        _slockNavigationSession = [SlockAdaptiveNavigationSession new];
        [NSNotificationCenter.defaultCenter addObserver:self
            selector:@selector(slockInvalidateAdaptiveNavigation)
            name:UIApplicationWillResignActiveNotification object:nil];
        _pageName = [pageName copy] ?: @"";
        _pageData = [[self p_mergeHostContextIntoPageData:pageData] copy];
        _slockAdaptivePanePublisher = [[SlockIosAdaptivePanePublisher alloc] init];
        // Pages draw their own top bar; every pushed page can be swiped back from anywhere
        // (the FD push swizzle reads both).
        self.fd_prefersNavigationBarHidden = YES;
        self.fd_interactivePopAllowsFullScreen = YES;
    }
    return self;
}

- (NSString *)slockPageName {
    return _pageName;
}

- (NSDictionary *)slockPageData {
    return _pageData;
}

- (BOOL)slockIsAppShellPage {
    return [_pageName isEqualToString:SlockAppShellPageName];
}

- (BOOL)slockIsLoginPage {
    return [_pageName isEqualToString:@"compose_login"];
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
    [self slockSendEvent:SlockConversationForwardPagerEvent data:@{}];
}

#pragma mark - Content

- (void)slockAttachContentController:(UIViewController *)content
                           sendEvent:(void (^)(NSString *event, NSDictionary *data))sendEvent {
    NSAssert(_contentController == nil, @"Page content is attached once");
    if (_contentController != nil) {
        return;
    }
    _contentController = content;
    _sendEvent = [sendEvent copy];
    [self slockEmbedContentIfNeeded];
}

- (void)slockEmbedContentIfNeeded {
    UIViewController *content = _contentController;
    if (content == nil || !self.isViewLoaded || content.parentViewController == self) {
        return;
    }
    [self addChildViewController:content];
    content.view.frame = self.view.bounds;
    content.view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.view addSubview:content.view];
    [content didMoveToParentViewController:self];
}

- (void)slockSendEvent:(NSString *)event data:(NSDictionary *)data {
    void (^sendEvent)(NSString *, NSDictionary *) = _sendEvent;
    if (sendEvent == nil || event.length == 0) {
        return;
    }
    sendEvent(event, data ?: @{});
}

- (void)slockSetDarkTheme:(BOOL)dark {
    if (_darkTheme != nil && _darkTheme.boolValue == dark) {
        return;
    }
    _darkTheme = @(dark);
    [self setNeedsStatusBarAppearanceUpdate];
}

- (UIStatusBarStyle)preferredStatusBarStyle {
    if (_darkTheme == nil) {
        return UIStatusBarStyleDefault;
    }
    return _darkTheme.boolValue ? UIStatusBarStyleLightContent : UIStatusBarStyleDarkContent;
}

- (UIViewController *)childViewControllerForStatusBarStyle {
    return nil;
}

#pragma mark - Lifecycle

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor whiteColor];
    [self slockEmbedContentIfNeeded];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self slockRefreshNavigationGeometry];
    [self slockPublishAdaptivePaneForCurrentBounds:@"layout"];
    [self slockPublishWindowInsets];
}

- (void)viewSafeAreaInsetsDidChange {
    [super viewSafeAreaInsetsDidChange];
    [self slockPublishWindowInsets];
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
}

// Page visibility: the embedded Compose controller receives these appearance callbacks (automatic
// child forwarding); its lifecycle drives the viewDidAppear / viewDidDisappear pager events
// (RaftPagerHost), so they are not sent again here.
- (void)viewDidAppear:(BOOL)animated {
    [super viewDidAppear:animated];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
    _slockAdaptivePanePublishingActive = YES;
    [self slockPublishAdaptivePaneForCurrentBounds:@"appear"];
    [self slockPublishWindowInsets];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    _slockAdaptivePanePublishingActive = NO;
    [self slockInvalidateAdaptiveNavigation];
}

- (void)viewWillTransitionToSize:(CGSize)size
       withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
    [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];
    [self slockInvalidateAdaptiveNavigation];
    __weak typeof(self) weakSelf = self;
    [coordinator animateAlongsideTransition:nil completion:^(__unused id<UIViewControllerTransitionCoordinatorContext> context) {
        [weakSelf slockPublishAdaptivePaneForCurrentBounds:@"viewWillTransition"];
        [weakSelf slockPublishWindowInsets];
    }];
}

- (void)traitCollectionDidChange:(UITraitCollection *)previousTraitCollection {
    [super traitCollectionDidChange:previousTraitCollection];
    if (previousTraitCollection == nil ||
        previousTraitCollection.horizontalSizeClass != self.traitCollection.horizontalSizeClass ||
        previousTraitCollection.verticalSizeClass != self.traitCollection.verticalSizeClass) {
        [self slockPublishAdaptivePaneForCurrentBounds:@"sizeClass"];
    }
    if (previousTraitCollection != nil &&
        previousTraitCollection.userInterfaceStyle != self.traitCollection.userInterfaceStyle) {
        // The CMP theme follows the trait itself (RaftTheme); this is the Kuikly pager event pages
        // that track night mode (BasePager.isNightMode) listen to.
        BOOL night = self.traitCollection.userInterfaceStyle == UIUserInterfaceStyleDark;
        [self slockSendEvent:SlockThemeDidChangePagerEvent data:@{ @"isNightMode": @(night) }];
    }
}

#pragma mark - Adaptive navigation (A4 native primitives)

// Shared admission remains false until the entire restore protocol works.
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

- (RaftPageViewController *)slockAdaptiveNavigationTarget {
    // Never fall back to the key window/top controller: a stale module must not acquire a new owner.
    if (self.slockIsAppShellPage || ![_pageName isEqualToString:@"compose_thread"]) return nil;
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
    if (![previous isKindOfClass:RaftPageViewController.class] ||
        !((RaftPageViewController *)previous).slockIsAppShellPage) return nil;
    return (RaftPageViewController *)previous;
}

- (NSDictionary *)slockAdaptiveNavigationContext:(RaftPageViewController *)target {
    [self slockRefreshNavigationGeometry];
    return target ? @{ @"sourceControllerToken": _slockControllerToken,
                       @"targetControllerToken": target->_slockControllerToken,
                       @"sourceRevision": @(_slockNavigationRevision),
                       @"geometry": _slockNavigationGeometry ?: @"" } : @{};
}

// TODO(cmp-switch): the draft editor was the Kuikly KRTextAreaView (a UITextView) bound by ref
// tag. CMP text fields are not UITextViews, so there is no native editor to snapshot: prepare
// rejects with editor_drift and shared keeps the compact route (no native pop) until the shared
// side hands the draft over itself.
- (UITextView *)slockAdaptiveEditor {
    return nil;
}

- (NSDictionary *)slockAdaptiveNavigation:(NSDictionary *)params {
    NSAssert(NSThread.isMainThread, @"Adaptive navigation must execute on the main thread");
    NSString *(^string)(NSString *) = ^NSString *(NSString *key) {
        return [params[key] isKindOfClass:NSString.class] ? params[key] : @"";
    };
    NSString *requestId = string(@"requestId");
    NSString *action = string(@"action");
    NSString *token = string(@"token");
    if ([action isEqual:@"cancel"]) return [_slockNavigationSession cancel:requestId token:token];
    RaftPageViewController *target = [self slockAdaptiveNavigationTarget];
    NSDictionary *context = [self slockAdaptiveNavigationContext:target];
    NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
    if ([action isEqual:@"prepare"]) {
        NSDictionary *expectedEditor = [params[@"expectedEditor"] isKindOfClass:NSDictionary.class]
            ? params[@"expectedEditor"] : nil;
        UITextView *editor = [self slockAdaptiveEditor];
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
        }
        return result;
    }
    if (![action isEqual:@"commit"]) {
        return @{ @"requestId": requestId, @"status": @"rejected", @"reason": @"invalid_action" };
    }
    // Keep the destination alive through pop. Never use a source callback as the success receipt.
    RaftPageViewController *preparedTarget = _slockNavigationTarget;
    if (target != preparedTarget || self.viewIfLoaded.window != _slockNavigationWindow) context = @{};
    UITextView *editor = [self slockAdaptiveEditor];
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
    NSMutableDictionary *receipt = [result mutableCopy];
    receipt[@"sourceControllerToken"] = _slockControllerToken;
    receipt[@"targetControllerToken"] = preparedTarget ? preparedTarget->_slockControllerToken : @"";
    // The destination revalidates requestId/authority before consuming any recovery state.
    // Failure or a missing receipt never permits shared to consume the anchor.
    if (preparedTarget) {
        [preparedTarget slockSendEvent:SlockAdaptiveNativeCommitResultEvent data:receipt];
    }
    return receipt;
}

#pragma mark - Host facts

- (void)slockPublishAdaptivePaneForCurrentBounds:(NSString *)reason {
    if (!_slockAdaptivePanePublishingActive) {
        return;
    }
    UIWindow *window = self.view.window ?: RaftPageCurrentKeyWindow();
    CGSize size = window != nil ? window.bounds.size : self.view.bounds.size;
    CGFloat scale = window.screen.scale ?: UIScreen.mainScreen.scale;
    NSDictionary *payload =
        [_slockAdaptivePanePublisher nextPayloadWithWidthPoints:size.width
                                                  heightPoints:size.height
                                                         scale:scale];
    if (payload == nil) {
        return;
    }
    [self slockSendEvent:SlockAdaptivePanePagerEvent data:payload];
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

/**
 * Live window insets (the Android host's slockWindowInsetsChanged contract): status bar / top
 * safe area and bottom safe area in pixels, replacing the attach-time pageData seed. Deduplicated.
 */
- (void)slockPublishWindowInsets {
    UIWindow *window = self.viewIfLoaded.window;
    if (window == nil || _sendEvent == nil) {
        return;
    }
    CGFloat scale = window.screen.scale ?: UIScreen.mainScreen.scale;
    UIEdgeInsets insets = window.safeAreaInsets;
    BOOL statusBarHidden = window.windowScene.statusBarManager.isStatusBarHidden;
    CGFloat top = MAX(insets.top, statusBarHidden ? 0 : RaftPageStatusBarHeight(window));
    NSString *topPx = RaftPagePixelString(top, scale);
    NSString *bottomPx = RaftPagePixelString(insets.bottom, scale);
    NSString *windowId = [_pageData[SlockHostWindowIdKey] isKindOfClass:NSString.class]
        ? _pageData[SlockHostWindowIdKey] : RaftPageWindowId(window);
    NSString *key = [NSString stringWithFormat:@"%@:%@:%d:%@", topPx, bottomPx, statusBarHidden, windowId];
    if ([key isEqualToString:_lastPublishedInsetsKey]) {
        return;
    }
    _lastPublishedInsetsKey = key;
    [self slockSendEvent:SlockWindowInsetsPagerEvent data:@{
        @"statusBarHeightPx": topPx,
        @"navigationBarHeightPx": bottomPx,
        @"statusBarHidden": @(statusBarHidden),
        SlockHostWindowIdKey: windowId,
    }];
}

- (NSDictionary *)p_mergeHostContextIntoPageData:(NSDictionary *)pageParam {
    NSMutableDictionary *mParam = [(pageParam ?: @{}) mutableCopy];
    RaftPagePutHostTimeContextIfMissing(mParam);
    // iOS pageData safeAreaInsets are reported in native pixels, while shared Compose layout
    // uses dp. Mark the unit so shared safe-area helpers convert through LocalDensity (on iOS the
    // Compose density is the screen scale) instead of treating 102px as 102dp.
    mParam[@"slockNativeSafeAreaInsetsArePixels"] = @YES;
    UIWindow *window = RaftPageCurrentKeyWindow();
    CGFloat scale = window.screen.scale ?: UIScreen.mainScreen.scale;
    UIEdgeInsets safeAreaInsets = window.safeAreaInsets;
    CGFloat statusBarHeight = RaftPageStatusBarHeight(window);
    mParam[@"safeAreaTop"] = RaftPagePixelString(safeAreaInsets.top, scale);
    mParam[@"safeAreaBottom"] = RaftPagePixelString(safeAreaInsets.bottom, scale);
    mParam[@"statusBarHeight"] = RaftPagePixelString(statusBarHeight, scale);
    if (![mParam[SlockHostWindowIdKey] isKindOfClass:NSString.class] && window != nil) {
        mParam[SlockHostWindowIdKey] = RaftPageWindowId(window);
    }
    return mParam;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

@end
