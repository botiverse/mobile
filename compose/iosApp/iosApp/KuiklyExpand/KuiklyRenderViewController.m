#import "KuiklyRenderViewController.h"
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
    NSString *_pageName;
    NSDictionary *_pageData;
    BOOL _slockConversationForwardGestureEnabled;
    BOOL _slockAdaptivePanePublishingActive;
    SlockIosAdaptivePanePublisher *_slockAdaptivePanePublisher;
}

- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData {
    if (self = [super init]) {
        pageData = [self p_mergeExtParamsWithOriditalParam:pageData];
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
    [_delegator viewWillDisappear];
}

- (void)viewDidDisappear:(BOOL)animated {
    [super viewDidDisappear:animated];
    [_delegator viewDidDisappear];
}

- (void)viewWillTransitionToSize:(CGSize)size
       withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
    [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];
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
