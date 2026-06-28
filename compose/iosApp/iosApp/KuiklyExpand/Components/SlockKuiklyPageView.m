#import "SlockKuiklyPageView.h"

#import <OpenKuiklyIOSRender/KRComponentDefine.h>
#import <OpenKuiklyIOSRender/KuiklyBaseView.h>
#import <OpenKuiklyIOSRender/KuiklyRenderView.h>
#import <QuartzCore/QuartzCore.h>

static NSString *const SlockKuiklyPageViewPageNameProp = @"pageName";
static NSString *const SlockKuiklyPageViewPageDataProp = @"pageData";
static NSString *const SlockKuiklyPageViewGlassStyleProp = @"glassStyle";
static NSString *const SlockKuiklyPageViewLoadSuccessProp = @"loadSuccess";
static NSString *const SlockKuiklyPageViewLoadFailureProp = @"loadFailure";
static NSString *const SlockKuiklyPageViewSendEventMethod = @"sendEvent";
static NSString *const SlockKuiklyPageViewFrameworkName = @"shared";
static NSString *const SlockKuiklyPageViewLiquidGlassStyle = @"liquid";
static NSInteger const SlockKuiklyAppId = 1;
static CGFloat const SlockKuiklyPageViewLiquidGlassCornerRadius = 28.0;

@interface SlockKuiklyPageView () <KuiklyViewBaseDelegate>

@property (nonatomic, copy) NSString *pageName;
@property (nonatomic, copy) NSString *pageDataText;
@property (nonatomic, copy) NSString *glassStyle;
@property (nonatomic, copy) KuiklyRenderCallback loadSuccessCallback;
@property (nonatomic, copy) KuiklyRenderCallback loadFailureCallback;
@property (nonatomic, strong) UIView *contentContainerView;
@property (nonatomic, strong) UIVisualEffectView *glassEffectView;
@property (nonatomic, strong) UIView *glassTintView;
@property (nonatomic, strong) UIView *glassHighlightView;
@property (nonatomic, strong) KuiklyBaseView *childView;
@property (nonatomic, assign) BOOL viewDidAppear;
@property (nonatomic, assign) BOOL pageDidAppear;

@end

@implementation SlockKuiklyPageView

@synthesize hr_rootView;

- (instancetype)init {
    if (self = [super init]) {
        _pageName = @"";
        _pageDataText = @"{}";
        _glassStyle = @"";
        self.backgroundColor = UIColor.clearColor;
        _contentContainerView = [[UIView alloc] initWithFrame:CGRectZero];
        _contentContainerView.backgroundColor = UIColor.clearColor;
        [self addSubview:_contentContainerView];
    }
    return self;
}

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
    if ([propKey isEqualToString:SlockKuiklyPageViewPageNameProp]) {
        [self updatePageName:[self stringFromValue:propValue]];
    } else if ([propKey isEqualToString:SlockKuiklyPageViewPageDataProp]) {
        [self updatePageDataText:[self stringFromValue:propValue]];
    } else if ([propKey isEqualToString:SlockKuiklyPageViewGlassStyleProp]) {
        [self updateGlassStyle:[self stringFromValue:propValue]];
    } else if ([propKey isEqualToString:SlockKuiklyPageViewLoadSuccessProp]) {
        self.loadSuccessCallback = propValue;
    } else if ([propKey isEqualToString:SlockKuiklyPageViewLoadFailureProp]) {
        self.loadFailureCallback = propValue;
    }
}

- (void)hrv_callWithMethod:(NSString *)method
                    params:(NSString *)params
                  callback:(KuiklyRenderCallback)callback {
    if ([method isEqualToString:SlockKuiklyPageViewSendEventMethod]) {
        [self sendEventWithParams:params ?: @"{}"];
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    self.contentContainerView.frame = self.bounds;
    [self applyGlassStyle];
    [self attachChildIfReady];
    self.childView.frame = self.contentContainerView.bounds;
    self.glassEffectView.frame = self.contentContainerView.bounds;
    self.glassTintView.frame = self.contentContainerView.bounds;
    self.glassHighlightView.frame = self.contentContainerView.bounds;
}

- (void)removeFromSuperview {
    [self detachChild];
    [super removeFromSuperview];
}

#pragma mark - KuiklyViewBaseDelegate

- (void)fetchContextCodeWithPageName:(NSString *)pageName resultCallback:(KuiklyContextCodeCallback)callback {
    if (callback) {
        callback(SlockKuiklyPageViewFrameworkName, nil);
    }
}

- (void)onPageLoadComplete:(BOOL)isSucceed error:(NSError *)error mode:(KuiklyContextMode)mode {
    if (isSucceed) {
        if (self.loadSuccessCallback) {
            self.loadSuccessCallback(@{});
        }
    } else if (self.loadFailureCallback) {
        self.loadFailureCallback(@{
            @"reason" : error.localizedDescription ?: @"unknown",
        });
    }
}

- (void)onUnhandledException:(NSString *)exReason stack:(NSString *)callstackStr mode:(KuiklyContextMode)mode {
    if (self.loadFailureCallback) {
        self.loadFailureCallback(@{
            @"reason" : exReason ?: @"unknown",
            @"stack" : callstackStr ?: @"",
        });
    }
}

#pragma mark - Private

- (void)updatePageName:(NSString *)pageName {
    if ([self.pageName isEqualToString:pageName]) {
        return;
    }
    self.pageName = pageName;
    [self resetChildIfAttached];
}

- (void)updatePageDataText:(NSString *)pageDataText {
    if ([self.pageDataText isEqualToString:pageDataText]) {
        return;
    }
    self.pageDataText = pageDataText.length > 0 ? pageDataText : @"{}";
    [self resetChildIfAttached];
}

- (void)updateGlassStyle:(NSString *)glassStyle {
    NSString *nextStyle = glassStyle.length > 0 ? glassStyle : @"";
    if ([self.glassStyle isEqualToString:nextStyle]) {
        return;
    }
    self.glassStyle = nextStyle;
    [self setNeedsLayout];
}

- (void)resetChildIfAttached {
    if (!self.childView) {
        return;
    }
    [self detachChild];
    [self setNeedsLayout];
}

- (void)attachChildIfReady {
    if (self.childView || self.pageName.length == 0 || CGRectGetWidth(self.bounds) <= 0 || CGRectGetHeight(self.bounds) <= 0) {
        return;
    }
    KuiklyBaseView *childView = [[KuiklyBaseView alloc] initWithFrame:self.bounds
                                                             pageName:self.pageName
                                                             pageData:[self childPageData]
                                                             delegate:self
                                                        frameworkName:SlockKuiklyPageViewFrameworkName];
    childView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    self.childView = childView;
    [self.contentContainerView addSubview:childView];
    [self bringGlassHighlightToFront];
    [self applyGlassStyle];
    [self dispatchAppearIfReady];
}

- (void)detachChild {
    KuiklyBaseView *childView = self.childView;
    if (!childView) {
        return;
    }
    [childView viewDidDisappear];
    [childView removeFromSuperview];
    self.childView = nil;
}

- (void)applyGlassStyle {
    if ([self.glassStyle isEqualToString:SlockKuiklyPageViewLiquidGlassStyle]) {
        [self ensureGlassViews];
        self.contentContainerView.clipsToBounds = YES;
        self.contentContainerView.layer.cornerRadius = SlockKuiklyPageViewLiquidGlassCornerRadius;
        if (@available(iOS 13.0, *)) {
            self.contentContainerView.layer.cornerCurve = kCACornerCurveContinuous;
        }
        self.contentContainerView.layer.borderWidth = 1.0;
        self.contentContainerView.layer.borderColor = [UIColor colorWithWhite:1.0 alpha:0.42].CGColor;

        self.layer.shadowColor = UIColor.blackColor.CGColor;
        self.layer.shadowOpacity = 0.16;
        self.layer.shadowRadius = 22.0;
        self.layer.shadowOffset = CGSizeMake(0.0, 10.0);
        self.layer.masksToBounds = NO;

        self.childView.layer.cornerRadius = SlockKuiklyPageViewLiquidGlassCornerRadius;
        if (@available(iOS 13.0, *)) {
            self.childView.layer.cornerCurve = kCACornerCurveContinuous;
        }
        self.childView.layer.masksToBounds = YES;
        return;
    }

    [self.glassEffectView removeFromSuperview];
    [self.glassTintView removeFromSuperview];
    [self.glassHighlightView removeFromSuperview];
    self.glassEffectView = nil;
    self.glassTintView = nil;
    self.glassHighlightView = nil;
    self.contentContainerView.clipsToBounds = NO;
    self.contentContainerView.layer.cornerRadius = 0.0;
    self.contentContainerView.layer.borderWidth = 0.0;
    self.contentContainerView.layer.borderColor = nil;
    self.layer.shadowOpacity = 0.0;
    self.childView.layer.cornerRadius = 0.0;
    self.childView.layer.masksToBounds = NO;
}

- (void)ensureGlassViews {
    if (!self.glassEffectView) {
        UIBlurEffect *blurEffect;
        if (@available(iOS 13.0, *)) {
            blurEffect = [UIBlurEffect effectWithStyle:UIBlurEffectStyleSystemUltraThinMaterial];
        } else {
            blurEffect = [UIBlurEffect effectWithStyle:UIBlurEffectStyleLight];
        }
        self.glassEffectView = [[UIVisualEffectView alloc] initWithEffect:blurEffect];
        self.glassEffectView.userInteractionEnabled = NO;
        [self.contentContainerView insertSubview:self.glassEffectView atIndex:0];
    }
    if (!self.glassTintView) {
        self.glassTintView = [[UIView alloc] initWithFrame:CGRectZero];
        self.glassTintView.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.18];
        self.glassTintView.userInteractionEnabled = NO;
        [self.contentContainerView insertSubview:self.glassTintView aboveSubview:self.glassEffectView];
    }
    if (!self.glassHighlightView) {
        self.glassHighlightView = [[UIView alloc] initWithFrame:CGRectZero];
        self.glassHighlightView.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.07];
        self.glassHighlightView.userInteractionEnabled = NO;
        [self.contentContainerView addSubview:self.glassHighlightView];
    }
    [self bringGlassHighlightToFront];
}

- (void)bringGlassHighlightToFront {
    if (self.glassHighlightView) {
        [self.contentContainerView bringSubviewToFront:self.glassHighlightView];
    }
}

- (NSDictionary *)childPageData {
    NSMutableDictionary *pageData = [[NSMutableDictionary alloc] init];
    NSData *data = [self.pageDataText dataUsingEncoding:NSUTF8StringEncoding];
    if (data) {
        id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
        if ([object isKindOfClass:NSDictionary.class]) {
            [pageData addEntriesFromDictionary:(NSDictionary *)object];
        }
    }
    pageData[@"appId"] = @(SlockKuiklyAppId);
    return pageData;
}

- (void)sendEventWithParams:(NSString *)params {
    NSDictionary *eventData = [self dictionaryFromJsonText:params];
    NSString *event = [self stringFromValue:eventData[@"event"]];
    NSDictionary *data = [eventData[@"data"] isKindOfClass:NSDictionary.class] ? eventData[@"data"] : @{};
    if ([event isEqualToString:@"didAppear"]) {
        self.viewDidAppear = YES;
        [self dispatchAppearIfReady];
    } else if ([event isEqualToString:@"didDisappear"]) {
        self.viewDidAppear = NO;
        if (self.pageDidAppear) {
            [self.childView viewDidDisappear];
        }
    } else if ([event isEqualToString:@"viewDidAppear"]) {
        self.pageDidAppear = YES;
        [self dispatchAppearIfReady];
    } else if ([event isEqualToString:@"viewDidDisappear"]) {
        self.pageDidAppear = NO;
        if (self.viewDidAppear) {
            [self.childView viewDidDisappear];
        }
    } else if (
        [event isEqualToString:@"windowSizeDidChanged"] ||
        [event isEqualToString:@"rootViewSizeDidChanged"] ||
        [event isEqualToString:@"pageFirstFramePaint"] ||
        [event isEqualToString:@"pageWillDestroy"] ||
        [event isEqualToString:@"setNeedLayout"] ||
        [event isEqualToString:@"onBackPressed"]
    ) {
        return;
    } else if (event.length > 0) {
        [self.childView sendWithEvent:event data:data];
    }
}

- (void)dispatchAppearIfReady {
    if (self.viewDidAppear && self.pageDidAppear && self.childView) {
        [self.childView viewDidAppear];
    }
}

- (NSDictionary *)dictionaryFromJsonText:(NSString *)text {
    NSData *data = [text dataUsingEncoding:NSUTF8StringEncoding];
    if (!data) {
        return @{};
    }
    id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [object isKindOfClass:NSDictionary.class] ? object : @{};
}

- (NSString *)stringFromValue:(id)value {
    if ([value isKindOfClass:NSString.class]) {
        return value;
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [value stringValue];
    }
    return value ? [value description] : @"";
}

@end
