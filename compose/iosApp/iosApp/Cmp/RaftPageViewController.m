#import "RaftPageViewController.h"
#import "RaftAdaptiveNavigationSession.h"
#import "RaftAdaptiveEditorSnapshot.h"
#import "HRBridgeModule.h"
#import "UINavigationController+FDFullscreenPopGesture.h"
#import "iosApp-Swift.h"
#import <math.h>

static const NSTimeInterval RaftPageMillisPerDay = 86400000.0;
static const NSTimeInterval RaftPageMillisPerMinute = 60000.0;
static NSString *const RaftAppShellPageName = @"compose_slock_app_shell";
static NSString *const RaftConversationForwardPagerEvent = @"slockConversationForwardRequested";
static NSString *const RaftAdaptivePanePagerEvent = @"slockAdaptivePaneChanged";
static NSString *const RaftAdaptiveNativeCommitResultEvent = @"slockAdaptiveNativeCommitResult";
// Shared contract (RaftAppSafeArea.kt): live window insets in pixels, keyed by pageData slockWindowId.
static NSString *const RaftWindowInsetsPagerEvent = @"slockWindowInsetsChanged";
static NSString *const RaftHostWindowIdKey = @"slockWindowId";
// Kuikly pager event the shared BasePager reads (`isNightMode`).
static NSString *const RaftThemeDidChangePagerEvent = @"themeDidChanged";

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

/** Per-scene window id for the shared window-inset snapshots (RaftAppSafeArea.kt). */
static NSString *RaftPageWindowId(UIWindow *window) {
    NSString *session = window.windowScene.session.persistentIdentifier;
    return session.length > 0 ? [@"ios:" stringByAppendingString:session] : @"ios:default";
}

@implementation RaftPageViewController {
    NSString *_raftControllerToken;
    NSUInteger _raftNavigationRevision;
    NSString *_raftNavigationGeometry;
    RaftAdaptiveNavigationSession *_raftNavigationSession;
    __weak RaftPageViewController *_raftNavigationTarget;
    __weak UIWindow *_raftNavigationWindow;
    NSString *_pageName;
    NSDictionary *_pageData;
    UIViewController *_contentController;
    void (^_sendEvent)(NSString *event, NSDictionary *data);
    BOOL _raftConversationForwardGestureEnabled;
    BOOL _raftAdaptivePanePublishingActive;
    RaftIosAdaptivePanePublisher *_raftAdaptivePanePublisher;
    NSString *_lastPublishedInsetsKey;
    NSNumber *_darkTheme;
}

- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData {
    if (self = [super initWithNibName:nil bundle:nil]) {
        _raftControllerToken = NSUUID.UUID.UUIDString;
        _raftNavigationSession = [RaftAdaptiveNavigationSession new];
        [NSNotificationCenter.defaultCenter addObserver:self
            selector:@selector(raftInvalidateAdaptiveNavigation)
            name:UIApplicationWillResignActiveNotification object:nil];
        _pageName = [pageName copy] ?: @"";
        _pageData = [[self p_mergeHostContextIntoPageData:pageData] copy];
        _raftAdaptivePanePublisher = [[RaftIosAdaptivePanePublisher alloc] init];
        // Pages draw their own top bar; every pushed page can be swiped back from anywhere (the
        // FD push swizzle reads both). Compose horizontal scrollers that can still scroll in the
        // back direction keep the drag through -raftContentConsumesBackSwipe (the probe below).
        self.fd_prefersNavigationBarHidden = YES;
        self.fd_interactivePopAllowsFullScreen = YES;
        __weak RaftPageViewController *weakSelf = self;
        self.fd_interactivePopContentConsumesBackSwipe = ^BOOL(CGPoint locationInView) {
            return [weakSelf raftContentConsumesBackSwipeAtPoint:locationInView];
        };
    }
    return self;
}

- (NSString *)raftPageName {
    return _pageName;
}

- (NSDictionary *)raftPageData {
    return _pageData;
}

- (BOOL)raftIsAppShellPage {
    return [_pageName isEqualToString:RaftAppShellPageName];
}

- (BOOL)raftIsLoginPage {
    return [_pageName isEqualToString:@"compose_login"];
}

- (BOOL)raftConversationForwardGestureEnabled {
    return _raftConversationForwardGestureEnabled;
}

- (void)setRaftConversationForwardGestureEnabled:(BOOL)enabled {
    _raftConversationForwardGestureEnabled = enabled;
    [self.navigationController fd_setConversationForwardGestureEnabled:
        enabled && self.raftIsAppShellPage];
}

- (void)raftRequestConversationForward {
    if (!self.raftIsAppShellPage || !self.raftConversationForwardGestureEnabled) {
        return;
    }
    self.raftConversationForwardGestureEnabled = NO;
    [self raftSendEvent:RaftConversationForwardPagerEvent data:@{}];
}

#pragma mark - Content

- (void)raftAttachContentController:(UIViewController *)content
                           sendEvent:(void (^)(NSString *event, NSDictionary *data))sendEvent {
    NSAssert(_contentController == nil, @"Page content is attached once");
    if (_contentController != nil) {
        return;
    }
    _contentController = content;
    _sendEvent = [sendEvent copy];
    [self raftEmbedContentIfNeeded];
}

- (void)raftEmbedContentIfNeeded {
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

- (BOOL)raftContentConsumesBackSwipeAtPoint:(CGPoint)locationInView {
    BOOL (^probe)(CGPoint) = self.raftBackSwipeContentProbe;
    UIView *contentView = _contentController.isViewLoaded ? _contentController.view : nil;
    if (probe == nil || contentView == nil || !self.isViewLoaded) {
        return NO;
    }
    return probe([self.view convertPoint:locationInView toView:contentView]);
}

- (void)raftSendEvent:(NSString *)event data:(NSDictionary *)data {
    void (^sendEvent)(NSString *, NSDictionary *) = _sendEvent;
    if (sendEvent == nil || event.length == 0) {
        return;
    }
    sendEvent(event, data ?: @{});
}

- (void)raftSetDarkTheme:(BOOL)dark {
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
    // Not a fixed white: under a dark app theme a pushed page flashed white until its first
    // composition painted the canvas (RaftCmpIos raftPaintHostCanvas).
    self.view.backgroundColor = self.raftInitialBackgroundColor ?: UIColor.systemBackgroundColor;
    [self raftEmbedContentIfNeeded];
    [self.navigationController setNavigationBarHidden:YES animated:NO];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self raftRefreshNavigationGeometry];
    [self raftPublishAdaptivePaneForCurrentBounds:@"layout"];
    [self raftPublishWindowInsets];
}

- (void)viewSafeAreaInsetsDidChange {
    [super viewSafeAreaInsetsDidChange];
    [self raftPublishWindowInsets];
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
    _raftAdaptivePanePublishingActive = YES;
    [self raftPublishAdaptivePaneForCurrentBounds:@"appear"];
    [self raftPublishWindowInsets];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    _raftAdaptivePanePublishingActive = NO;
    [self raftInvalidateAdaptiveNavigation];
}

- (void)viewWillTransitionToSize:(CGSize)size
       withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
    [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];
    [self raftInvalidateAdaptiveNavigation];
    __weak typeof(self) weakSelf = self;
    [coordinator animateAlongsideTransition:nil completion:^(__unused id<UIViewControllerTransitionCoordinatorContext> context) {
        [weakSelf raftPublishAdaptivePaneForCurrentBounds:@"viewWillTransition"];
        [weakSelf raftPublishWindowInsets];
    }];
}

- (void)traitCollectionDidChange:(UITraitCollection *)previousTraitCollection {
    [super traitCollectionDidChange:previousTraitCollection];
    if (previousTraitCollection == nil ||
        previousTraitCollection.horizontalSizeClass != self.traitCollection.horizontalSizeClass ||
        previousTraitCollection.verticalSizeClass != self.traitCollection.verticalSizeClass) {
        [self raftPublishAdaptivePaneForCurrentBounds:@"sizeClass"];
    }
    if (previousTraitCollection != nil &&
        previousTraitCollection.userInterfaceStyle != self.traitCollection.userInterfaceStyle) {
        // The CMP theme follows the trait itself (RaftTheme); this is the Kuikly pager event pages
        // that track night mode (BasePager.isNightMode) listen to.
        BOOL night = self.traitCollection.userInterfaceStyle == UIUserInterfaceStyleDark;
        [self raftSendEvent:RaftThemeDidChangePagerEvent data:@{ @"isNightMode": @(night) }];
    }
}

#pragma mark - Adaptive navigation (A4 native primitives)

// Shared admission remains false until the entire restore protocol works.
- (void)raftInvalidateAdaptiveNavigation {
    _raftNavigationRevision += 1;
}

- (void)raftRefreshNavigationGeometry {
    UIWindow *window = self.viewIfLoaded.window;
    NSString *geometry = [NSString stringWithFormat:@"%@|%@|%g", NSStringFromCGRect(window.bounds),
                          NSStringFromCGRect(self.viewIfLoaded.bounds), window.screen.scale];
    if (![_raftNavigationGeometry isEqual:geometry]) {
        _raftNavigationGeometry = geometry;
        [self raftInvalidateAdaptiveNavigation];
    }
}

- (RaftPageViewController *)raftAdaptiveNavigationTarget {
    // Never fall back to the key window/top controller: a stale module must not acquire a new owner.
    if (self.raftIsAppShellPage || ![_pageName isEqualToString:@"compose_thread"]) return nil;
    UINavigationController *nav = self.navigationController;
    UIWindow *window = self.viewIfLoaded.window;
    if (!_raftAdaptivePanePublishingActive || window == nil || !window.isKeyWindow ||
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
        !((RaftPageViewController *)previous).raftIsAppShellPage) return nil;
    return (RaftPageViewController *)previous;
}

- (NSDictionary *)raftAdaptiveNavigationContext:(RaftPageViewController *)target {
    [self raftRefreshNavigationGeometry];
    return target ? @{ @"sourceControllerToken": _raftControllerToken,
                       @"targetControllerToken": target->_raftControllerToken,
                       @"sourceRevision": @(_raftNavigationRevision),
                       @"geometry": _raftNavigationGeometry ?: @"" } : @{};
}

// TODO(cmp-switch): the draft editor was the Kuikly KRTextAreaView (a UITextView) bound by ref
// tag. CMP text fields are not UITextViews, so there is no native editor to snapshot: prepare
// rejects with editor_drift and shared keeps the compact route (no native pop) until the shared
// side hands the draft over itself.
- (UITextView *)raftAdaptiveEditor {
    return nil;
}

- (NSDictionary *)raftAdaptiveNavigation:(NSDictionary *)params {
    NSAssert(NSThread.isMainThread, @"Adaptive navigation must execute on the main thread");
    NSString *(^string)(NSString *) = ^NSString *(NSString *key) {
        return [params[key] isKindOfClass:NSString.class] ? params[key] : @"";
    };
    NSString *requestId = string(@"requestId");
    NSString *action = string(@"action");
    NSString *token = string(@"token");
    if ([action isEqual:@"cancel"]) return [_raftNavigationSession cancel:requestId token:token];
    RaftPageViewController *target = [self raftAdaptiveNavigationTarget];
    NSDictionary *context = [self raftAdaptiveNavigationContext:target];
    NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
    if ([action isEqual:@"prepare"]) {
        NSDictionary *expectedEditor = [params[@"expectedEditor"] isKindOfClass:NSDictionary.class]
            ? params[@"expectedEditor"] : nil;
        UITextView *editor = [self raftAdaptiveEditor];
        NSDictionary *editorState = RaftAdaptiveEditorSnapshot(editor);
        NSString *expectedText = expectedEditor[@"text"];
        if (![expectedText isKindOfClass:NSString.class] || ![editorState[@"text"] isEqual:expectedText]) {
            editorState = nil;
        }
        NSDictionary *result = [_raftNavigationSession prepare:requestId context:context
                                                         editor:editor editorState:editorState now:now];
        if ([result[@"status"] isEqual:@"prepared"]) {
            _raftNavigationTarget = target;
            _raftNavigationWindow = self.viewIfLoaded.window;
        }
        return result;
    }
    if (![action isEqual:@"commit"]) {
        return @{ @"requestId": requestId, @"status": @"rejected", @"reason": @"invalid_action" };
    }
    // Keep the destination alive through pop. Never use a source callback as the success receipt.
    RaftPageViewController *preparedTarget = _raftNavigationTarget;
    if (target != preparedTarget || self.viewIfLoaded.window != _raftNavigationWindow) context = @{};
    UITextView *editor = [self raftAdaptiveEditor];
    NSDictionary *editorState = RaftAdaptiveEditorSnapshot(editor);
    NSDictionary *result = [_raftNavigationSession commit:requestId token:token context:context
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
    receipt[@"sourceControllerToken"] = _raftControllerToken;
    receipt[@"targetControllerToken"] = preparedTarget ? preparedTarget->_raftControllerToken : @"";
    // The destination revalidates requestId/authority before consuming any recovery state.
    // Failure or a missing receipt never permits shared to consume the anchor.
    if (preparedTarget) {
        [preparedTarget raftSendEvent:RaftAdaptiveNativeCommitResultEvent data:receipt];
    }
    return receipt;
}

#pragma mark - Host facts

- (void)raftPublishAdaptivePaneForCurrentBounds:(NSString *)reason {
    if (!_raftAdaptivePanePublishingActive) {
        return;
    }
    UIWindow *window = self.view.window ?: RaftPageCurrentKeyWindow();
    CGSize size = window != nil ? window.bounds.size : self.view.bounds.size;
    CGFloat scale = window.screen.scale ?: UIScreen.mainScreen.scale;
    NSDictionary *payload =
        [_raftAdaptivePanePublisher nextPayloadWithWidthPoints:size.width
                                                  heightPoints:size.height
                                                         scale:scale];
    if (payload == nil) {
        return;
    }
    [self raftSendEvent:RaftAdaptivePanePagerEvent data:payload];
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
    RaftIosDiagnosticsRecord(@"info", @"SlockAdaptivePane", message);
}

/**
 * Live window insets (the Android host's slockWindowInsetsChanged contract): status bar / top
 * safe area and bottom safe area in pixels, replacing the attach-time pageData seed. Deduplicated.
 */
- (void)raftPublishWindowInsets {
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
    NSString *windowId = [_pageData[RaftHostWindowIdKey] isKindOfClass:NSString.class]
        ? _pageData[RaftHostWindowIdKey] : RaftPageWindowId(window);
    NSString *key = [NSString stringWithFormat:@"%@:%@:%d:%@", topPx, bottomPx, statusBarHidden, windowId];
    if ([key isEqualToString:_lastPublishedInsetsKey]) {
        return;
    }
    _lastPublishedInsetsKey = key;
    [self raftSendEvent:RaftWindowInsetsPagerEvent data:@{
        @"statusBarHeightPx": topPx,
        @"navigationBarHeightPx": bottomPx,
        @"statusBarHidden": @(statusBarHidden),
        RaftHostWindowIdKey: windowId,
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
    if (![mParam[RaftHostWindowIdKey] isKindOfClass:NSString.class] && window != nil) {
        mParam[RaftHostWindowIdKey] = RaftPageWindowId(window);
    }
    return mParam;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

@end
