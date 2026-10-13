#import "SlockSandboxWebView.h"
#import "SlockSandboxWebViewNavigationCommit.h"

#import <OpenKuiklyIOSRender/KRComponentDefine.h>
#import <WebKit/WebKit.h>

static NSString *const SlockSandboxWebViewSrcProp = @"src";
static NSString *const SlockSandboxWebViewAllowScriptsProp = @"allowScripts";
static NSString *const SlockSandboxWebViewAllowNavigationProp = @"allowNavigation";
static NSString *const SlockSandboxWebViewDomStorageEnabledProp = @"domStorageEnabled";
static NSString *const SlockSandboxWebViewCacheEnabledProp = @"cacheEnabled";
static NSString *const SlockSandboxWebViewEphemeralProp = @"ephemeral";
static NSString *const SlockSandboxWebViewInjectedJavaScriptProp = @"injectedJavaScript";
static NSString *const SlockSandboxWebViewAllowedNavigationSchemesProp = @"allowedNavigationSchemes";
static NSString *const SlockSandboxWebViewHostEventsEnabledProp = @"hostEventsEnabled";
static NSString *const SlockSandboxWebViewHostMessageProp = @"hostMessage";
static NSString *const SlockSandboxWebViewHostMessageName = @"raftHostPort";
static NSString *const SlockSandboxWebViewNavigationCommandMethod = @"navigationCommand";
static NSString *const SlockSandboxWebViewLoadCommitProp = @"loadCommit";
static NSString *const SlockSandboxWebViewLoadSuccessProp = @"loadSuccess";
static NSString *const SlockSandboxWebViewLoadFailureProp = @"loadFailure";
static NSString *const SlockSandboxWebViewNavigationBlockedProp = @"navigationBlocked";
static NSString *const SlockSandboxWebViewUrlChangeProp = @"urlChange";
static NSString *const SlockSandboxWebViewNavigationStateChangeProp = @"navigationStateChange";

static NSString *const SlockSandboxWebViewCommandGoBack = @"goBack";
static NSString *const SlockSandboxWebViewCommandGoForward = @"goForward";
static NSString *const SlockSandboxWebViewCommandReload = @"reload";
static NSString *const SlockSandboxWebViewCommandStop = @"stop";
static NSString *const SlockSandboxWebViewCommandEvaluateJavaScriptPrefix = @"evaluateJavaScript:";

static NSArray<NSString *> *SlockSandboxWebViewObservedKeyPaths(void) {
    static NSArray<NSString *> *keyPaths;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        keyPaths = @[ @"estimatedProgress", @"title", @"canGoBack", @"canGoForward", @"loading", @"URL" ];
    });
    return keyPaths;
}

@interface SlockSandboxWebView () <WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler>

@property (nonatomic, strong) WKWebView *webView;
@property (nonatomic, copy) NSString *src;
@property (nonatomic, strong) NSURL *allowedURL;
@property (nonatomic, assign) BOOL allowScripts;
@property (nonatomic, assign) BOOL allowNavigation;
@property (nonatomic, assign) BOOL domStorageEnabled;
@property (nonatomic, assign) BOOL cacheEnabled;
@property (nonatomic, assign) BOOL ephemeral;
@property (nonatomic, copy) NSString *injectedJavaScript;
@property (nonatomic, copy) NSSet<NSString *> *allowedNavigationSchemes;
@property (nonatomic, copy) NSString *currentURL;
@property (nonatomic, copy) NSString *currentTitle;
@property (nonatomic, assign) BOOL currentLoading;
@property (nonatomic, assign) NSInteger currentProgress;
@property (nonatomic, copy) NSString *lastNavigationStateKey;
@property (nonatomic, copy) NSString *lastSuccessfulURL;
@property (nonatomic, copy) NSString *lastLoadFailureKey;
@property (nonatomic, assign) BOOL observingWebView;
@property (nonatomic, assign) BOOL detachedFromSuperview;
@property (nonatomic, assign) BOOL reloadOnReattach;
@property (nonatomic, copy) KuiklyRenderCallback loadCommitCallback;
@property (nonatomic, copy) KuiklyRenderCallback loadSuccessCallback;
@property (nonatomic, copy) KuiklyRenderCallback loadFailureCallback;
@property (nonatomic, copy) KuiklyRenderCallback navigationBlockedCallback;
@property (nonatomic, copy) KuiklyRenderCallback urlChangeCallback;
@property (nonatomic, copy) KuiklyRenderCallback navigationStateChangeCallback;
@property (nonatomic, copy) KuiklyRenderCallback hostMessageCallback;
@property (nonatomic, assign) BOOL hostEventsEnabled;
- (BOOL)isSameOriginURL:(NSURL *)targetURL;

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
        _hostEventsEnabled = NO;
        _injectedJavaScript = @"";
        _allowedNavigationSchemes = [NSSet set];
        _currentURL = @"";
        _currentTitle = @"";
        _lastNavigationStateKey = @"";
        _lastSuccessfulURL = @"";
        _lastLoadFailureKey = @"";
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
    } else if ([propKey isEqualToString:SlockSandboxWebViewHostEventsEnabledProp]) {
        self.hostEventsEnabled = [self boolFromValue:propValue defaultValue:NO];
        [self rebuildWebView];
    } else if ([propKey isEqualToString:SlockSandboxWebViewHostMessageProp]) {
        self.hostMessageCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewInjectedJavaScriptProp]) {
        [self updateInjectedJavaScript:[self stringFromValue:propValue]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewAllowedNavigationSchemesProp]) {
        [self updateAllowedNavigationSchemes:[self stringFromValue:propValue]];
    } else if ([propKey isEqualToString:SlockSandboxWebViewLoadCommitProp]) {
        self.loadCommitCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewLoadSuccessProp]) {
        self.loadSuccessCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewLoadFailureProp]) {
        self.loadFailureCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewNavigationBlockedProp]) {
        self.navigationBlockedCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewUrlChangeProp]) {
        self.urlChangeCallback = propValue;
    } else if ([propKey isEqualToString:SlockSandboxWebViewNavigationStateChangeProp]) {
        self.navigationStateChangeCallback = propValue;
        [self notifyNavigationStateForce:YES];
    }
}

- (void)hrv_callWithMethod:(NSString *)method
                    params:(NSString * _Nullable)params
                  callback:(KuiklyRenderCallback _Nullable)callback {
    if ([method isEqualToString:SlockSandboxWebViewNavigationCommandMethod]) {
        [self executeNavigationCommand:params ?: @""];
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    self.webView.frame = self.bounds;
}

- (void)removeFromSuperview {
    self.reloadOnReattach = self.currentLoading || self.webView.loading;
    self.detachedFromSuperview = YES;
    [self stopObservingWebView:self.webView];
    self.webView.navigationDelegate = nil;
    self.webView.UIDelegate = nil;
    [self.webView stopLoading];
    self.currentLoading = NO;
    [super removeFromSuperview];
}

- (void)didMoveToSuperview {
    [super didMoveToSuperview];
    if (!self.superview || !self.detachedFromSuperview) {
        return;
    }
    self.detachedFromSuperview = NO;
    self.webView.navigationDelegate = self;
    self.webView.UIDelegate = self;
    [self startObservingWebView:self.webView];
    BOOL shouldReload = self.reloadOnReattach;
    self.reloadOnReattach = NO;
    if (shouldReload) {
        [self loadCurrentSrc];
    } else {
        [self notifyNavigationStateForce:YES];
    }
}

- (void)dealloc {
    [self stopObservingWebView:self.webView];
    self.webView.navigationDelegate = nil;
    self.webView.UIDelegate = nil;
    [self.webView stopLoading];
    _loadCommitCallback = nil;
    _loadSuccessCallback = nil;
    _loadFailureCallback = nil;
    _navigationBlockedCallback = nil;
    _urlChangeCallback = nil;
    _navigationStateChangeCallback = nil;
    _hostMessageCallback = nil;
}


- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message {
    if (![message.name isEqualToString:SlockSandboxWebViewHostMessageName] || !self.hostEventsEnabled || !self.hostMessageCallback) return;
    if (userContentController != self.webView.configuration.userContentController || !message.frameInfo.isMainFrame) return;
    NSURL *expectedURL = [NSURL URLWithString:self.src];
    WKSecurityOrigin *origin = message.frameInfo.securityOrigin;
    NSString *scheme = expectedURL.scheme.lowercaseString;
    if (!([scheme isEqualToString:@"https"] || [scheme isEqualToString:@"http"])) return;
    NSInteger defaultPort = [scheme isEqualToString:@"https"] ? 443 : 80;
    NSInteger expectedPort = expectedURL.port ? expectedURL.port.integerValue : defaultPort;
    NSInteger actualPort = origin.port > 0 ? origin.port : defaultPort;
    if (![origin.protocol.lowercaseString isEqualToString:scheme] ||
        ![origin.host.lowercaseString isEqualToString:expectedURL.host.lowercaseString] || actualPort != expectedPort) return;
    NSURL *activeURL = self.webView.URL;
    if (!self.allowNavigation && ![self isAllowedMainDocumentURL:activeURL]) return;
    if (![message.body isKindOfClass:NSString.class]) return;
    self.hostMessageCallback(@{ @"message" : message.body });
}

#pragma mark - WKNavigationDelegate

- (void)webView:(WKWebView *)webView
decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler {
    if (webView != self.webView) {
        decisionHandler(WKNavigationActionPolicyCancel);
        return;
    }
    NSURL *targetURL = navigationAction.request.URL;
    BOOL isMainFrame = navigationAction.targetFrame == nil || navigationAction.targetFrame.isMainFrame;
    if (isMainFrame) {
        if (![self isNavigationSchemeAllowedURL:targetURL] ||
            (!self.allowNavigation && ![self isAllowedMainDocumentURL:targetURL])) {
            [self handleBlockedMainFrameNavigation:targetURL.absoluteString ?: @""];
            decisionHandler(WKNavigationActionPolicyCancel);
            return;
        }
        self.currentURL = targetURL.absoluteString ?: @"";
        if (self.urlChangeCallback) {
            self.urlChangeCallback(@{ @"url" : self.currentURL });
        }
        [self notifyNavigationStateForce:NO];
    }
    decisionHandler(WKNavigationActionPolicyAllow);
}

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    if (webView != self.webView) {
        return;
    }
    self.currentURL = webView.URL.absoluteString ?: self.currentURL ?: @"";
    self.currentTitle = @"";
    self.currentProgress = MAX(0, MIN(100, (NSInteger)(webView.estimatedProgress * 100.0 + 0.5)));
    self.currentLoading = YES;
    self.lastSuccessfulURL = @"";
    self.lastLoadFailureKey = @"";
    [self notifyNavigationStateForce:NO];
}

- (void)webView:(WKWebView *)webView didCommitNavigation:(WKNavigation *)navigation {
    if (webView != self.webView) {
        return;
    }
    self.currentURL = webView.URL.absoluteString ?: self.currentURL ?: @"";
    self.currentTitle = webView.title ?: self.currentTitle ?: @"";
    self.currentLoading = webView.loading;
    [self notifyURLChange:self.currentURL];
    [self notifyNavigationStateForce:NO];
    __weak typeof(self) weakSelf = self;
    SlockSandboxWebViewPublishNavigationCommit(self.webView, webView, ^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (strongSelf.loadCommitCallback) {
            strongSelf.loadCommitCallback(@{});
        }
    });
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    if (webView != self.webView) {
        return;
    }
    self.currentURL = webView.URL.absoluteString ?: self.currentURL ?: @"";
    self.currentTitle = webView.title ?: @"";
    self.currentProgress = 100;
    self.currentLoading = NO;
    [self notifyURLChange:self.currentURL];
    [self notifyNavigationStateForce:NO];
    [self notifyLoadSuccess:self.currentURL];
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    if (webView != self.webView || [self isIntentionalCancellation:error]) {
        return;
    }
    self.currentURL = webView.URL.absoluteString ?: self.currentURL ?: @"";
    self.currentLoading = NO;
    [self notifyNavigationStateForce:NO];
    [self notifyLoadFailure:error.localizedDescription ?: @"webview_load_failed"];
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    if (webView != self.webView || [self isIntentionalCancellation:error]) {
        return;
    }
    self.currentURL = webView.URL.absoluteString ?: self.currentURL ?: @"";
    self.currentLoading = NO;
    [self notifyNavigationStateForce:NO];
    [self notifyLoadFailure:error.localizedDescription ?: @"webview_load_failed"];
}

#pragma mark - WKUIDelegate

- (WKWebView *)webView:(WKWebView *)webView
createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
   forNavigationAction:(WKNavigationAction *)navigationAction
        windowFeatures:(WKWindowFeatures *)windowFeatures {
    if (webView != self.webView) {
        return nil;
    }
    [self notifyNavigationBlocked:navigationAction.request.URL.absoluteString ?: @""];
    return nil;
}

#pragma mark - Private

- (void)rebuildWebView {
    WKWebView *previousWebView = self.webView;
    [self stopObservingWebView:previousWebView];
    previousWebView.navigationDelegate = nil;
    previousWebView.UIDelegate = nil;
    [previousWebView stopLoading];
    [previousWebView removeFromSuperview];

    WKWebViewConfiguration *configuration = [[WKWebViewConfiguration alloc] init];
    configuration.websiteDataStore =
        self.ephemeral ? [WKWebsiteDataStore nonPersistentDataStore] : [WKWebsiteDataStore defaultDataStore];
    WKUserContentController *userContentController = [[WKUserContentController alloc] init];
    if (self.hostEventsEnabled) {
        [userContentController addScriptMessageHandler:self name:SlockSandboxWebViewHostMessageName];
        // Diagnostics capability is installed only when a native uploader exists.
        NSString *bridge = @"(function(){var h=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.raftHostPort;if(!h)return;Object.defineProperty(window,\"raftHostPort\",{value:{postMessage:function(m){h.postMessage(m);}},writable:false,configurable:false});Object.defineProperty(window,\"RaftHost\",{value:Object.freeze({version:\"raft-host-v1\",capabilities:[\"raft-feedback-v1\"],emit:function(k,p){if(typeof k===\"string\")h.postMessage(JSON.stringify({kind:k,payload:(p&&typeof p===\"object\"&&!Array.isArray(p))?p:{}}));}}),writable:false,configurable:false});})();";
        [userContentController addUserScript:[[WKUserScript alloc] initWithSource:bridge injectionTime:WKUserScriptInjectionTimeAtDocumentStart forMainFrameOnly:YES]];
    }
    if (self.allowScripts && self.injectedJavaScript.length > 0) {
        WKUserScript *script =
            [[WKUserScript alloc] initWithSource:self.injectedJavaScript
                                   injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                forMainFrameOnly:!self.allowNavigation];
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
    if (!self.detachedFromSuperview) {
        webView.navigationDelegate = self;
        webView.UIDelegate = self;
    }
    webView.backgroundColor = UIColor.whiteColor;
    webView.opaque = YES;
    webView.allowsBackForwardNavigationGestures = NO;
    if (@available(iOS 16.4, *)) {
        webView.inspectable = NO;
    }
    self.webView = webView;
    [self addSubview:webView];
    if (!self.detachedFromSuperview) {
        [self startObservingWebView:webView];
    }
    self.currentProgress = 0;
    self.currentLoading = NO;
    self.currentTitle = @"";
    self.lastNavigationStateKey = @"";
    [self loadCurrentSrc];
}

- (void)startObservingWebView:(WKWebView *)webView {
    if (!webView || self.observingWebView) {
        return;
    }
    for (NSString *keyPath in SlockSandboxWebViewObservedKeyPaths()) {
        [webView addObserver:self forKeyPath:keyPath options:NSKeyValueObservingOptionNew context:nil];
    }
    self.observingWebView = YES;
}

- (void)stopObservingWebView:(WKWebView *)webView {
    if (!webView || !self.observingWebView) {
        return;
    }
    for (NSString *keyPath in SlockSandboxWebViewObservedKeyPaths()) {
        @try {
            [webView removeObserver:self forKeyPath:keyPath];
        } @catch (__unused NSException *exception) {
        }
    }
    self.observingWebView = NO;
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey, id> *)change
                       context:(void *)context {
    if (![SlockSandboxWebViewObservedKeyPaths() containsObject:keyPath]) {
        [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
        return;
    }
    if (object != self.webView) {
        return;
    }
    WKWebView *webView = (WKWebView *)object;
    self.currentURL = webView.URL.absoluteString ?: self.currentURL ?: @"";
    self.currentTitle = webView.title ?: @"";
    self.currentProgress = MAX(0, MIN(100, (NSInteger)(webView.estimatedProgress * 100.0 + 0.5)));
    self.currentLoading = webView.loading;
    [self notifyNavigationStateForce:NO];
}

- (void)updateAllowScripts:(BOOL)allowScripts {
    if (self.allowScripts == allowScripts) {
        return;
    }
    self.allowScripts = allowScripts;
    [self rebuildWebView];
}

- (void)updateAllowNavigation:(BOOL)allowNavigation {
    if (self.allowNavigation == allowNavigation) {
        return;
    }
    self.allowNavigation = allowNavigation;
    // The auth user-script's main-frame scope is selected from this policy at configuration
    // time. Rebuild when the policy changes so a previously broad script cannot survive into a
    // fenced feedback document.
    [self rebuildWebView];
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

- (void)updateAllowedNavigationSchemes:(NSString *)allowedNavigationSchemes {
    NSMutableSet<NSString *> *normalizedSchemes = [NSMutableSet set];
    NSCharacterSet *whitespace = NSCharacterSet.whitespaceAndNewlineCharacterSet;
    for (NSString *rawScheme in [allowedNavigationSchemes componentsSeparatedByString:@","]) {
        NSString *scheme = [[rawScheme stringByTrimmingCharactersInSet:whitespace] lowercaseString];
        while ([scheme hasSuffix:@":"]) {
            scheme = [scheme substringToIndex:scheme.length - 1];
        }
        if (scheme.length > 0) {
            [normalizedSchemes addObject:scheme];
        }
    }
    NSSet<NSString *> *nextSchemes = [normalizedSchemes copy];
    if ([self.allowedNavigationSchemes isEqualToSet:nextSchemes]) {
        return;
    }
    self.allowedNavigationSchemes = nextSchemes;
    if (self.src.length == 0) {
        return;
    }
    NSURL *activeURL = self.webView.URL;
    if (!activeURL || [activeURL.absoluteString isEqualToString:@"about:blank"]) {
        activeURL = [NSURL URLWithString:self.src];
    }
    if (activeURL && ![self isNavigationSchemeAllowedURL:activeURL]) {
        [self.webView stopLoading];
        self.currentLoading = NO;
        [self notifyNavigationStateForce:NO];
        [self notifyNavigationBlocked:activeURL.absoluteString ?: self.src ?: @""];
    }
}

- (void)executeNavigationCommand:(NSString *)navigationCommand {
    NSString *command =
        [navigationCommand stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if ([command hasPrefix:SlockSandboxWebViewCommandEvaluateJavaScriptPrefix]) {
        NSString *script = [command substringFromIndex:SlockSandboxWebViewCommandEvaluateJavaScriptPrefix.length];
        NSURL *activeURL = self.webView.URL;
        BOOL sameTrustedDocument = activeURL && [self isNavigationSchemeAllowedURL:activeURL] &&
            [self isSameOriginURL:activeURL] &&
            (self.allowNavigation || [self isAllowedMainDocumentURL:activeURL]);
        if (sameTrustedDocument && script.length > 0) {
            [self.webView evaluateJavaScript:script completionHandler:nil];
        }
        return;
    }
    if ([command isEqualToString:SlockSandboxWebViewCommandGoBack]) {
        if (self.webView.canGoBack) {
            [self.webView goBack];
        }
    } else if ([command isEqualToString:SlockSandboxWebViewCommandGoForward]) {
        if (self.webView.canGoForward) {
            [self.webView goForward];
        }
    } else if ([command isEqualToString:SlockSandboxWebViewCommandReload]) {
        self.lastSuccessfulURL = @"";
        self.lastLoadFailureKey = @"";
        self.currentProgress = 0;
        self.currentLoading = YES;
        [self.webView reload];
    } else if ([command isEqualToString:SlockSandboxWebViewCommandStop]) {
        [self.webView stopLoading];
        self.currentLoading = NO;
    } else {
        return;
    }
    [self notifyNavigationStateForce:YES];
}

- (void)updateSrc:(NSString *)src {
    NSString *nextSrc = src.length > 0 ? src : @"";
    if ([self.src isEqualToString:nextSrc]) {
        return;
    }
    self.src = nextSrc;
    self.allowedURL = nextSrc.length > 0 ? [NSURL URLWithString:nextSrc] : nil;
    self.currentURL = nextSrc;
    self.currentTitle = @"";
    self.currentProgress = 0;
    self.currentLoading = NO;
    self.lastSuccessfulURL = @"";
    self.lastLoadFailureKey = @"";
    [self loadCurrentSrc];
}

- (void)loadCurrentSrc {
    if (!self.webView) {
        return;
    }
    if (self.detachedFromSuperview) {
        self.reloadOnReattach = self.src.length > 0;
        return;
    }
    if (self.src.length == 0) {
        self.currentURL = @"";
        self.currentTitle = @"";
        self.currentProgress = 0;
        self.currentLoading = NO;
        [self notifyNavigationStateForce:NO];
        [self.webView loadHTMLString:@"" baseURL:nil];
        return;
    }
    NSURL *url = [NSURL URLWithString:self.src];
    if (!url) {
        [self notifyNavigationStateForce:NO];
        if (self.allowedNavigationSchemes.count > 0) {
            [self notifyNavigationBlocked:self.src];
        } else {
            [self notifyLoadFailure:@"invalid_preview_url"];
        }
        return;
    }
    if (![self isNavigationSchemeAllowedURL:url]) {
        [self notifyNavigationStateForce:NO];
        [self notifyNavigationBlocked:self.src];
        return;
    }
    self.currentLoading = YES;
    [self notifyNavigationStateForce:NO];
    NSURLRequestCachePolicy cachePolicy =
        self.cacheEnabled ? NSURLRequestUseProtocolCachePolicy : NSURLRequestReloadIgnoringLocalCacheData;
    [self.webView loadRequest:[NSURLRequest requestWithURL:url cachePolicy:cachePolicy timeoutInterval:60.0]];
}

- (BOOL)isNavigationSchemeAllowedURL:(NSURL *)targetURL {
    if (self.allowedNavigationSchemes.count == 0) {
        return YES;
    }
    if (self.src.length == 0 && [targetURL.absoluteString isEqualToString:@"about:blank"]) {
        return YES;
    }
    NSString *scheme = targetURL.scheme.lowercaseString;
    return scheme.length > 0 && [self.allowedNavigationSchemes containsObject:scheme];
}

- (BOOL)isIntentionalCancellation:(NSError *)error {
    return [error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled;
}

- (void)handleBlockedMainFrameNavigation:(NSString *)url {
    self.currentLoading = NO;
    [self notifyNavigationStateForce:NO];
    [self notifyNavigationBlocked:url];
}

- (void)notifyURLChange:(NSString *)url {
    if (self.urlChangeCallback) {
        self.urlChangeCallback(@{ @"url" : url ?: @"" });
    }
}

- (void)notifyNavigationStateForce:(BOOL)force {
    NSString *url = self.currentURL ?: @"";
    NSString *title = self.currentTitle ?: @"";
    NSInteger progress = MAX(0, MIN(100, self.currentProgress));
    BOOL canGoBack = self.webView.canGoBack;
    BOOL canGoForward = self.webView.canGoForward;
    NSString *stateKey = [NSString stringWithFormat:@"%@\x1f%@\x1f%d\x1f%d\x1f%d\x1f%ld",
                                                        url,
                                                        title,
                                                        canGoBack,
                                                        canGoForward,
                                                        self.currentLoading,
                                                        (long)progress];
    if (!force && [self.lastNavigationStateKey isEqualToString:stateKey]) {
        return;
    }
    self.lastNavigationStateKey = stateKey;
    if (self.navigationStateChangeCallback) {
        self.navigationStateChangeCallback(@{
            @"url" : url,
            @"title" : title,
            @"canGoBack" : @(canGoBack),
            @"canGoForward" : @(canGoForward),
            @"isLoading" : @(self.currentLoading),
            @"progress" : @(progress),
        });
    }
}

- (void)notifyLoadSuccess:(NSString *)url {
    NSString *normalizedURL = url ?: @"";
    if ([self.lastSuccessfulURL isEqualToString:normalizedURL]) {
        return;
    }
    self.lastSuccessfulURL = normalizedURL;
    self.lastLoadFailureKey = @"";
    if (self.loadSuccessCallback) {
        self.loadSuccessCallback(@{});
    }
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

- (BOOL)isSameOriginURL:(NSURL *)targetURL {
    if (!targetURL || !self.allowedURL) {
        return NO;
    }
    NSString *allowedScheme = self.allowedURL.scheme.lowercaseString ?: @"";
    NSString *targetScheme = targetURL.scheme.lowercaseString ?: @"";
    NSString *allowedHost = self.allowedURL.host.lowercaseString ?: @"";
    NSString *targetHost = targetURL.host.lowercaseString ?: @"";
    NSInteger defaultPort = [allowedScheme isEqualToString:@"https"] ? 443 : 80;
    NSInteger allowedPort = self.allowedURL.port ? self.allowedURL.port.integerValue : defaultPort;
    NSInteger targetPort = targetURL.port ? targetURL.port.integerValue : defaultPort;
    return [allowedScheme isEqualToString:targetScheme] &&
        [allowedHost isEqualToString:targetHost] && allowedPort == targetPort;
}

- (void)notifyLoadFailure:(NSString *)reason {
    NSString *normalizedReason = reason ?: @"webview_load_failed";
    NSString *failureKey = [NSString stringWithFormat:@"%@|%@", self.currentURL ?: @"", normalizedReason];
    if ([self.lastLoadFailureKey isEqualToString:failureKey]) {
        return;
    }
    self.lastLoadFailureKey = failureKey;
    if (self.loadFailureCallback) {
        self.loadFailureCallback(@{ @"reason" : normalizedReason });
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
