#import "SlockSandboxWebView.h"

#import <OpenKuiklyIOSRender/KRComponentDefine.h>
#import <WebKit/WebKit.h>

static NSString *const SlockSandboxWebViewSrcProp = @"src";
static NSString *const SlockSandboxWebViewAllowScriptsProp = @"allowScripts";
static NSString *const SlockSandboxWebViewAllowNavigationProp = @"allowNavigation";
static NSString *const SlockSandboxWebViewDomStorageEnabledProp = @"domStorageEnabled";
static NSString *const SlockSandboxWebViewCacheEnabledProp = @"cacheEnabled";
static NSString *const SlockSandboxWebViewEphemeralProp = @"ephemeral";
static NSString *const SlockSandboxWebViewInjectedJavaScriptProp = @"injectedJavaScript";
static NSString *const SlockSandboxWebViewLoadSuccessProp = @"loadSuccess";
static NSString *const SlockSandboxWebViewLoadFailureProp = @"loadFailure";
static NSString *const SlockSandboxWebViewNavigationBlockedProp = @"navigationBlocked";
static NSString *const SlockSandboxWebViewUrlChangeProp = @"urlChange";

@interface SlockSandboxWebView () <WKNavigationDelegate, WKUIDelegate>

@property (nonatomic, strong) WKWebView *webView;
@property (nonatomic, copy) NSString *src;
@property (nonatomic, strong) NSURL *allowedURL;
@property (nonatomic, assign) BOOL allowScripts;
@property (nonatomic, assign) BOOL allowNavigation;
@property (nonatomic, assign) BOOL domStorageEnabled;
@property (nonatomic, assign) BOOL cacheEnabled;
@property (nonatomic, assign) BOOL ephemeral;
@property (nonatomic, copy) NSString *injectedJavaScript;
@property (nonatomic, copy) KuiklyRenderCallback loadSuccessCallback;
@property (nonatomic, copy) KuiklyRenderCallback loadFailureCallback;
@property (nonatomic, copy) KuiklyRenderCallback navigationBlockedCallback;
@property (nonatomic, copy) KuiklyRenderCallback urlChangeCallback;

@end

@implementation SlockSandboxWebView

@synthesize hr_rootView;

- (instancetype)init {
    if (self = [super init]) {
        _src = @"";
        _allowScripts = YES;
        _allowNavigation = NO;
        _domStorageEnabled = NO;
        _cacheEnabled = NO;
        _ephemeral = YES;
        _injectedJavaScript = @"";
        self.backgroundColor = UIColor.whiteColor;
        [self rebuildWebView];
    }
    return self;
}

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
    if ([propKey isEqualToString:SlockSandboxWebViewSrcProp]) {
        [self updateSrc:[self stringFromValue:propValue]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewAllowScriptsProp]) {
        [self updateAllowScripts:[self boolFromValue:propValue defaultValue:YES]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewAllowNavigationProp]) {
        [self updateAllowNavigation:[self boolFromValue:propValue defaultValue:NO]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewDomStorageEnabledProp]) {
        [self updateDomStorageEnabled:[self boolFromValue:propValue defaultValue:NO]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewCacheEnabledProp]) {
        [self updateCacheEnabled:[self boolFromValue:propValue defaultValue:NO]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewEphemeralProp]) {
        [self updateEphemeral:[self boolFromValue:propValue defaultValue:YES]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewInjectedJavaScriptProp]) {
        [self updateInjectedJavaScript:[self stringFromValue:propValue]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewLoadSuccessProp]) {
        self.loadSuccessCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewLoadFailureProp]) {
        self.loadFailureCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewNavigationBlockedProp]) {
        self.navigationBlockedCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewUrlChangeProp]) {
        self.urlChangeCallback = propValue;
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    self.webView.frame = self.bounds;
}

- (void)removeFromSuperview {
    [self.webView stopLoading];
    [self.webView loadHTMLString:@"" baseURL:nil];
    [super removeFromSuperview];
}

#pragma mark - WKNavigationDelegate

- (void)webView:(WKWebView *)webView
decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler {
    NSURL *targetURL = navigationAction.request.URL;
    BOOL isMainFrame = navigationAction.targetFrame == nil || navigationAction.targetFrame.isMainFrame;
    if (isMainFrame && self.urlChangeCallback) {
        self.urlChangeCallback(@{ @"url" : targetURL.absoluteString ?: @"" });
    }
    if (isMainFrame && !self.allowNavigation && ![self isAllowedMainDocumentURL:targetURL]) {
        [self notifyNavigationBlocked:targetURL.absoluteString ?: @""];
        decisionHandler(WKNavigationActionPolicyCancel);
        return;
    }
    decisionHandler(WKNavigationActionPolicyAllow);
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    if (self.loadSuccessCallback) {
        self.loadSuccessCallback(@{});
    }
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    [self notifyLoadFailure:error.localizedDescription ?: @"webview_load_failed"];
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    [self notifyLoadFailure:error.localizedDescription ?: @"webview_load_failed"];
}

#pragma mark - WKUIDelegate

- (WKWebView *)webView:(WKWebView *)webView
createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
   forNavigationAction:(WKNavigationAction *)navigationAction
        windowFeatures:(WKWindowFeatures *)windowFeatures {
    [self notifyNavigationBlocked:navigationAction.request.URL.absoluteString ?: @""];
    return nil;
}

#pragma mark - Private

- (void)rebuildWebView {
    [self.webView stopLoading];
    [self.webView removeFromSuperview];

    WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
    configuration.websiteDataStore =
        self.ephemeral ? [WKWebsiteDataStore nonPersistentDataStore] : [WKWebsiteDataStore defaultDataStore];
    WKUserContentController *userContentController = [[WKUserContentController alloc] init];
    if (self.allowScripts && self.injectedJavaScript.length > 0) {
        WKUserScript *script =
            [[WKUserScript alloc] initWithSource:self.injectedJavaScript
                                   injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                forMainFrameOnly:NO];
        [userContentController addUserScript:script];
    }
    configuration.userContentController = userContentController;
    configuration.allowsInlineMediaPlayback = YES;
    configuration.preferences.javaScriptCanOpenWindowsAutomatically = NO;
    if (@available(iOS 10.0, *)) {
        configuration.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll;
    }
    if (@available(iOS 14.0, *)) {
        WKWebpagePreferences *pagePreferences = [[WKWebpagePreferences alloc] init];
        pagePreferences.allowsContentJavaScript = self.allowScripts;
        configuration.defaultWebpagePreferences = pagePreferences;
    } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        configuration.preferences.javaScriptEnabled = self.allowScripts;
#pragma clang diagnostic pop
    }

    WKWebView *webView = [[WKWebView alloc] initWithFrame:self.bounds configuration:configuration];
    webView.navigationDelegate = self;
    webView.UIDelegate = self;
    webView.backgroundColor = UIColor.whiteColor;
    webView.opaque = YES;
    webView.allowsBackForwardNavigationGestures = NO;
    if (@available(iOS 16.4, *)) {
        webView.inspectable = NO;
    }
    self.webView = webView;
    [self addSubview:webView];
    [self loadCurrentSrc];
}

- (void)updateAllowScripts:(BOOL)allowScripts {
    if (self.allowScripts == allowScripts) {
        return;
    }
    self.allowScripts = allowScripts;
    [self rebuildWebView];
}

- (void)updateAllowNavigation:(BOOL)allowNavigation {
    self.allowNavigation = allowNavigation;
}

- (void)updateDomStorageEnabled:(BOOL)domStorageEnabled {
    if (self.domStorageEnabled == domStorageEnabled) {
        return;
    }
    self.domStorageEnabled = domStorageEnabled;
    [self rebuildWebView];
}

- (void)updateCacheEnabled:(BOOL)cacheEnabled {
    self.cacheEnabled = cacheEnabled;
}

- (void)updateEphemeral:(BOOL)ephemeral {
    if (self.ephemeral == ephemeral) {
        return;
    }
    self.ephemeral = ephemeral;
    [self rebuildWebView];
}

- (void)updateInjectedJavaScript:(NSString *)injectedJavaScript {
    NSString *nextInjectedJavaScript = injectedJavaScript ?: @"";
    if ([self.injectedJavaScript isEqualToString:nextInjectedJavaScript]) {
        return;
    }
    self.injectedJavaScript = nextInjectedJavaScript;
    [self rebuildWebView];
}

- (void)updateSrc:(NSString *)src {
    NSString *nextSrc = src.length > 0 ? src : @"";
    if ([self.src isEqualToString:nextSrc]) {
        return;
    }
    self.src = nextSrc;
    self.allowedURL = nextSrc.length > 0 ? [NSURL URLWithString:nextSrc] : nil;
    [self loadCurrentSrc];
}

- (void)loadCurrentSrc {
    if (!self.webView) {
        return;
    }
    if (self.src.length == 0) {
        [self.webView loadHTMLString:@"" baseURL:nil];
        return;
    }
    NSURL *url = [NSURL URLWithString:self.src];
    if (!url) {
        [self notifyLoadFailure:@"invalid_preview_url"];
        return;
    }
    NSURLRequestCachePolicy cachePolicy =
        self.cacheEnabled ? NSURLRequestUseProtocolCachePolicy : NSURLRequestReloadIgnoringLocalCacheData;
    [self.webView loadRequest:[NSURLRequest requestWithURL:url cachePolicy:cachePolicy timeoutInterval:60.0]];
}

- (BOOL)isAllowedMainDocumentURL:(NSURL *)targetURL {
    if (!targetURL || !self.allowedURL) {
        return NO;
    }
    if ([targetURL.absoluteString isEqualToString:self.src]) {
        return YES;
    }
    NSString *allowedScheme = self.allowedURL.scheme ?: @"";
    NSString *targetScheme = targetURL.scheme ?: @"";
    NSString *allowedHost = self.allowedURL.host ?: @"";
    NSString *targetHost = targetURL.host ?: @"";
    NSString *allowedPath = self.allowedURL.path ?: @"";
    NSString *targetPath = targetURL.path ?: @"";
    NSString *allowedQuery = self.allowedURL.query ?: @"";
    NSString *targetQuery = targetURL.query ?: @"";
    NSNumber *allowedPort = self.allowedURL.port ?: @(-1);
    NSNumber *targetPort = targetURL.port ?: @(-1);
    BOOL sameScheme = [allowedScheme isEqualToString:targetScheme];
    BOOL sameHost = [allowedHost isEqualToString:targetHost];
    BOOL samePath = [allowedPath isEqualToString:targetPath];
    BOOL sameQuery = [allowedQuery isEqualToString:targetQuery];
    return sameScheme && sameHost && samePath && sameQuery && [allowedPort isEqualToNumber:targetPort];
}

- (void)notifyLoadFailure:(NSString *)reason {
    if (self.loadFailureCallback) {
        self.loadFailureCallback(@{ @"reason" : reason ?: @"webview_load_failed" });
    }
}

- (void)notifyNavigationBlocked:(NSString *)url {
    if (self.navigationBlockedCallback) {
        self.navigationBlockedCallback(@{ @"url" : url ?: @"" });
    }
}

- (NSString *)stringFromValue:(id)value {
    if ([value isKindOfClass:NSString.class]) {
        return value;
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [value stringValue];
    }
    return @"";
}

- (BOOL)boolFromValue:(id)value defaultValue:(BOOL)defaultValue {
    if ([value isKindOfClass:NSNumber.class]) {
        return [value boolValue];
    }
    if ([value isKindOfClass:NSString.class]) {
        NSString *text = [(NSString *)value lowercaseString];
        if ([text isEqualToString:@"true"]) {
            return YES;
        }
        if ([text isEqualToString:@"false"]) {
            return NO;
        }
    }
    return defaultValue;
}

@end
