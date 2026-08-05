#import "KuiklyRenderViewController.h"
#import "Components/SlockAdaptiveSystemTabBarController.h"
#import "Components/SlockNativeFloatingTabBarView.h"
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

@interface SlockAdaptiveTabContentViewController : UIViewController
@end

@implementation SlockAdaptiveTabContentViewController
- (void)loadView {
    UIView *view = [[UIView alloc] initWithFrame:CGRectZero];
    view.backgroundColor = UIColor.whiteColor;
    self.view = view;
}
@end

@interface KuiklyRenderViewController()<KuiklyRenderViewControllerBaseDelegatorDelegate,
                                        UITabBarControllerDelegate,
                                        SlockNativeAdaptiveTabBarHosting>

@property (nonatomic, strong) KuiklyRenderViewControllerBaseDelegator *delegator;

@end

@implementation KuiklyRenderViewController {
    NSString *_pageName;
    NSDictionary *_pageData;
    BOOL _slockConversationForwardGestureEnabled;
    BOOL _slockAdaptivePanePublishingActive;
    SlockIosAdaptivePanePublisher *_slockAdaptivePanePublisher;
    UIView *_slockAdaptiveRenderHostView;
    SlockAdaptiveSystemTabBarController *_slockAdaptiveTabBarController API_AVAILABLE(ios(18.0));
    NSDictionary<NSString *, SlockAdaptiveTabContentViewController *> *_slockAdaptiveTabContentControllers API_AVAILABLE(ios(18.0));
    NSDictionary<NSString *, NSNumber *> *_slockAdaptiveTabEnabledByKey API_AVAILABLE(ios(18.0));
    KuiklyRenderCallback _slockAdaptiveTabSelectCallback;
    BOOL _slockApplyingAdaptiveSelection;
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
    UIView *renderHost = self.view;
    if (@available(iOS 18.0, *)) {
        if (self.slockIsAppShellPage) {
            renderHost = [self slock_installAdaptiveTabBarController];
        }
    }
    [_delegator viewDidLoadWithView:renderHost];
    [self.navigationController setNavigationBarHidden:YES animated:NO];

}

- (UIView *)slock_installAdaptiveTabBarController API_AVAILABLE(ios(18.0)) {
    _slockAdaptiveRenderHostView = [[UIView alloc] initWithFrame:self.view.bounds];
    _slockAdaptiveRenderHostView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    _slockAdaptiveRenderHostView.backgroundColor = UIColor.whiteColor;

    _slockAdaptiveTabBarController = [[SlockAdaptiveSystemTabBarController alloc] init];
    _slockAdaptiveTabBarController.delegate = self;
    _slockAdaptiveTabBarController.customizationIdentifier = @"build.raft.app.app-shell";
    [self addChildViewController:_slockAdaptiveTabBarController];
    _slockAdaptiveTabBarController.view.frame = self.view.bounds;
    _slockAdaptiveTabBarController.view.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.view addSubview:_slockAdaptiveTabBarController.view];
    [_slockAdaptiveTabBarController didMoveToParentViewController:self];

    [self slock_rebuildAdaptiveTabsWithKeys:@[@"__bootstrap__"]
                                    labels:@[@""]
                                     icons:@[@"circle"]
                                   enabled:@[@"1"]
                               selectedKey:@"__bootstrap__"];
    return _slockAdaptiveRenderHostView;
}

- (BOOL)slock_updateAdaptiveTabKeys:(NSArray<NSString *> *)keys
                             labels:(NSArray<NSString *> *)labels
                              icons:(NSArray<NSString *> *)icons
                            enabled:(NSArray<NSString *> *)enabled
                        selectedKey:(NSString *)selectedKey
                     selectCallback:(KuiklyRenderCallback)selectCallback {
    if (@available(iOS 18.0, *)) {
        if (!_slockAdaptiveTabBarController || keys.count == 0) {
            return _slockAdaptiveTabBarController != nil;
        }
        _slockAdaptiveTabSelectCallback = [selectCallback copy];
        NSArray<NSString *> *currentKeys = [_slockAdaptiveTabBarController.tabs valueForKey:@"identifier"];
        if (![currentKeys isEqualToArray:keys]) {
            [self slock_rebuildAdaptiveTabsWithKeys:keys
                                            labels:labels
                                             icons:icons
                                           enabled:enabled
                                       selectedKey:selectedKey];
        } else {
            NSMutableDictionary<NSString *, NSNumber *> *enabledByKey = [NSMutableDictionary dictionary];
            [keys enumerateObjectsUsingBlock:^(NSString *key, NSUInteger index, BOOL *stop) {
                BOOL itemEnabled = index >= enabled.count || ![enabled[index] isEqualToString:@"0"];
                enabledByKey[key] = @(itemEnabled);
                UITab *tab = [_slockAdaptiveTabBarController tabForIdentifier:key];
                if (@available(iOS 18.4, *)) {
                    tab.enabled = itemEnabled;
                }
                NSString *label = index < labels.count ? labels[index] : key;
                tab.title = [label capitalizedString];
                NSString *iconName = index < icons.count ? icons[index] : @"circle";
                tab.image = [self slock_adaptiveTabImageForIconName:iconName];
            }];
            _slockAdaptiveTabEnabledByKey = enabledByKey;
            [self slock_applyAdaptiveSelectedKey:selectedKey];
        }
        return YES;
    }
    return NO;
}

- (void)slock_rebuildAdaptiveTabsWithKeys:(NSArray<NSString *> *)keys
                                   labels:(NSArray<NSString *> *)labels
                                    icons:(NSArray<NSString *> *)icons
                                  enabled:(NSArray<NSString *> *)enabled
                              selectedKey:(NSString *)selectedKey API_AVAILABLE(ios(18.0)) {
    NSMutableArray<UITab *> *tabs = [NSMutableArray array];
    NSMutableDictionary<NSString *, SlockAdaptiveTabContentViewController *> *controllers = [NSMutableDictionary dictionary];
    NSMutableDictionary<NSString *, NSNumber *> *enabledByKey = [NSMutableDictionary dictionary];
    [keys enumerateObjectsUsingBlock:^(NSString *key, NSUInteger index, BOOL *stop) {
        if (key.length == 0) {
            return;
        }
        NSString *label = index < labels.count ? labels[index] : key;
        NSString *iconName = index < icons.count ? icons[index] : @"circle";
        UIImage *image = [self slock_adaptiveTabImageForIconName:iconName];
        SlockAdaptiveTabContentViewController *content = [[SlockAdaptiveTabContentViewController alloc] init];
        controllers[key] = content;
        UITab *tab = [[UITab alloc] initWithTitle:[label capitalizedString]
                                           image:image
                                      identifier:key
                          viewControllerProvider:^UIViewController * _Nonnull(UITab * _Nonnull requestedTab) {
            return content;
        }];
        tab.preferredPlacement = UITabPlacementFixed;
        BOOL itemEnabled = index >= enabled.count || ![enabled[index] isEqualToString:@"0"];
        enabledByKey[key] = @(itemEnabled);
        if (@available(iOS 18.4, *)) {
            tab.enabled = itemEnabled;
        }
        [tabs addObject:tab];
    }];
    if (tabs.count == 0) {
        return;
    }
    _slockAdaptiveTabContentControllers = controllers;
    _slockAdaptiveTabEnabledByKey = enabledByKey;
    BOOL wasApplyingSelection = _slockApplyingAdaptiveSelection;
    _slockApplyingAdaptiveSelection = YES;
    [_slockAdaptiveTabBarController setTabs:tabs animated:NO];
    [self slock_applyAdaptiveSelectedKey:selectedKey];
    _slockApplyingAdaptiveSelection = wasApplyingSelection;
    [self slock_attachRenderHostToSelectedAdaptiveTab];
}

- (void)slock_applyAdaptiveSelectedKey:(NSString *)selectedKey API_AVAILABLE(ios(18.0)) {
    UITab *tab = [_slockAdaptiveTabBarController tabForIdentifier:selectedKey ?: @""];
    if (!tab || ![_slockAdaptiveTabEnabledByKey[tab.identifier] boolValue]) {
        for (UITab *candidate in _slockAdaptiveTabBarController.tabs) {
            if ([_slockAdaptiveTabEnabledByKey[candidate.identifier] boolValue]) {
                tab = candidate;
                break;
            }
        }
    }
    if (!tab || _slockAdaptiveTabBarController.selectedTab == tab) {
        [self slock_attachRenderHostToSelectedAdaptiveTab];
        return;
    }
    BOOL wasApplyingSelection = _slockApplyingAdaptiveSelection;
    _slockApplyingAdaptiveSelection = YES;
    _slockAdaptiveTabBarController.selectedTab = tab;
    _slockApplyingAdaptiveSelection = wasApplyingSelection;
    [self slock_attachRenderHostToSelectedAdaptiveTab];
}

- (void)slock_attachRenderHostToSelectedAdaptiveTab API_AVAILABLE(ios(18.0)) {
    NSString *key = _slockAdaptiveTabBarController.selectedTab.identifier;
    SlockAdaptiveTabContentViewController *content = _slockAdaptiveTabContentControllers[key];
    if (!content || !_slockAdaptiveRenderHostView) {
        return;
    }
    UIView *container = content.view;
    if (_slockAdaptiveRenderHostView.superview != container) {
        [_slockAdaptiveRenderHostView removeFromSuperview];
        _slockAdaptiveRenderHostView.frame = container.bounds;
        [container addSubview:_slockAdaptiveRenderHostView];
    }
}

- (BOOL)tabBarController:(UITabBarController *)tabBarController shouldSelectTab:(UITab *)tab API_AVAILABLE(ios(18.0)) {
    return [_slockAdaptiveTabEnabledByKey[tab.identifier] boolValue];
}

- (void)tabBarController:(UITabBarController *)tabBarController
             didSelectTab:(UITab *)selectedTab
              previousTab:(UITab *)previousTab API_AVAILABLE(ios(18.0)) {
    [self slock_attachRenderHostToSelectedAdaptiveTab];
    if (!_slockApplyingAdaptiveSelection && _slockAdaptiveTabSelectCallback && selectedTab.identifier.length > 0 &&
        ![selectedTab.identifier isEqualToString:@"__bootstrap__"]) {
        NSUInteger index = [tabBarController.tabs indexOfObject:selectedTab];
        _slockAdaptiveTabSelectCallback(@{ @"key": selectedTab.identifier, @"index": @(index) });
    }
}

- (UIImage *)slock_adaptiveTabImageForIconName:(NSString *)iconName {
    NSString *assetName = [@"lucide-" stringByAppendingString:[self slock_normalizedTabIconName:iconName]];
    UIImage *image = [UIImage imageNamed:assetName] ?: [UIImage imageNamed:@"lucide-circle"];
    return [image imageWithRenderingMode:UIImageRenderingModeAlwaysTemplate];
}

- (NSString *)slock_normalizedTabIconName:(NSString *)name {
    NSMutableString *result = [[NSMutableString alloc] init];
    for (NSUInteger index = 0; index < name.length; index++) {
        unichar character = [name characterAtIndex:index];
        if (character == '_') {
            [result appendString:@"-"];
        } else if ([[NSCharacterSet uppercaseLetterCharacterSet] characterIsMember:character]) {
            if (index > 0) [result appendString:@"-"];
            [result appendString:[[NSString stringWithCharacters:&character length:1] lowercaseString]];
        } else {
            [result appendString:[[NSString stringWithCharacters:&character length:1] lowercaseString]];
        }
    }
    return result;
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
