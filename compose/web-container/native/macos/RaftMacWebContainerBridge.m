#import <Cocoa/Cocoa.h>
#import <WebKit/WebKit.h>
#import <QuartzCore/QuartzCore.h>
#include <dlfcn.h>
#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>

/*
 * RaftMacWebContainerBridge.m — Thin ARC Objective-C JNI/JAWT WKWebView bridge for
 * compose/web-container on macOS (Phase 3, RFC Option A).
 *
 * Threading invariant (zero AB-BA deadlock by construction):
 * - Every mutating JNI entry point dispatches asynchronously to macOS Thread 0 via
 *   `dispatch_async(dispatch_get_main_queue(), ...)` and NEVER calls `dispatch_sync`.
 * - Every WKWebView delegate / KVO / JSB callback invokes cached JNI static methods on
 *   `build.raft.webcontainer.MacOsNativeWebBridge`, which immediately hops to the AWT EDT via
 *   `SwingUtilities.invokeLater` without blocking macOS Thread 0.
 * - `decidePolicyForNavigationAction` and `WKScriptMessageHandlerWithReply` are both handled
 *   natively asynchronously via retained completion blocks, settled cleanly on cancel/dispose.
 * - `JAWT_GetAWT` is resolved dynamically via `dlsym(RTLD_DEFAULT, "JAWT_GetAWT")` after Kotlin
 *   loads `jawt`, avoiding any compile-time `@rpath/libjawt.dylib` coupling across JDK/JBR layouts.
 */

static JavaVM *gJavaVM = NULL;
static jclass gBridgeClass = NULL;
static jmethodID gOnNavigationDecisionRequest = NULL;
static jmethodID gOnStateChanged = NULL;
static jmethodID gOnLoadStarted = NULL;
static jmethodID gOnLoadCommit = NULL;
static jmethodID gOnLoadFinished = NULL;
static jmethodID gOnLoadFailure = NULL;
static jmethodID gOnProcessTerminated = NULL;
static jmethodID gOnJsbMessage = NULL;
static jmethodID gOnEvalResult = NULL;
static jmethodID gOnNewWindowRequested = NULL;

static char kRaftMacKvoContext = 0;
static NSMutableDictionary<NSNumber *, id> *gSessions = nil;
static NSMutableDictionary<NSString *, WKWebsiteDataStore *> *gNamedStores = nil;

typedef jboolean (*RaftJawtGetAwtFn)(JNIEnv *, JAWT *);

static NSString *RaftStringFromJString(JNIEnv *env, jstring value) {
    if (value == NULL) return nil;
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) return nil;
    jsize len = (*env)->GetStringLength(env, value);
    NSString *result = [NSString stringWithCharacters:(const unichar *)chars length:(NSUInteger)len];
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

static jstring RaftJStringFromNSString(JNIEnv *env, NSString *value) {
    NSString *safe = value ?: @"";
    NSUInteger len = [safe length];
    if (len == 0) {
        jchar empty = 0;
        return (*env)->NewString(env, &empty, 0);
    }
    unichar stackBuf[256];
    unichar *buf = (len <= 256) ? stackBuf : (unichar *)malloc(len * sizeof(unichar));
    if (buf == NULL) {
        jchar empty = 0;
        return (*env)->NewString(env, &empty, 0);
    }
    [safe getCharacters:buf range:NSMakeRange(0, len)];
    jstring jstr = (*env)->NewString(env, (const jchar *)buf, (jsize)len);
    if (buf != stackBuf) free(buf);
    return jstr;
}

static BOOL RaftWithJniEnv(void (^block)(JNIEnv *env)) {
    if (gJavaVM == NULL || gBridgeClass == NULL || block == NULL) return NO;
    JNIEnv *env = NULL;
    jint status = (*gJavaVM)->GetEnv(gJavaVM, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*gJavaVM)->AttachCurrentThreadAsDaemon(gJavaVM, (void **)&env, NULL) != JNI_OK || env == NULL) {
            return NO;
        }
    } else if (status != JNI_OK || env == NULL) {
        return NO;
    }
    if ((*env)->PushLocalFrame(env, 32) != 0) {
        return NO;
    }
    block(env);
    BOOL ok = YES;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        ok = NO;
    }
    (*env)->PopLocalFrame(env, NULL);
    return ok;
}

static NSURLRequestCachePolicy RaftCachePolicy(BOOL cacheEnabled) {
    return cacheEnabled ? NSURLRequestUseProtocolCachePolicy : NSURLRequestReloadIgnoringLocalCacheData;
}

static BOOL RaftUrlsMatchHostLoad(NSString *pending, NSString *target) {
    if (pending.length == 0 || target.length == 0) return NO;
    if ([pending isEqualToString:target]) return YES;
    if (![pending hasSuffix:@"/"] && [[pending stringByAppendingString:@"/"] isEqualToString:target]) return YES;
    if (![target hasSuffix:@"/"] && [[target stringByAppendingString:@"/"] isEqualToString:pending]) return YES;
    return NO;
}

static NSView *RaftFindHostViewFromLayer(CALayer *windowLayer) {
    CALayer *cursor = windowLayer;
    while (cursor != nil) {
        id delegate = cursor.delegate;
        if ([delegate isKindOfClass:[NSView class]]) {
            NSView *view = (NSView *)delegate;
            return view.window.contentView ?: view;
        }
        cursor = cursor.superlayer;
    }
    return nil;
}

static NSString *RaftFormatSecurityOrigin(WKSecurityOrigin *origin) {
    if (origin == nil) return @"";
    NSString *proto = origin.protocol ?: @"";
    NSString *host = origin.host ?: @"";
    if (proto.length == 0 || host.length == 0) return @"";
    if ([host containsString:@":"] && ![host hasPrefix:@"["]) {
        host = [NSString stringWithFormat:@"[%@]", host];
    }
    if (origin.port > 0) {
        return [NSString stringWithFormat:@"%@://%@:%ld", proto, host, (long)origin.port];
    }
    return [NSString stringWithFormat:@"%@://%@", proto, host];
}

static NSInteger RaftMapNavigationStarter(WKNavigationType navType) {
    switch (navType) {
        case WKNavigationTypeLinkActivated: return 1; // Link
        case WKNavigationTypeFormSubmitted:
        case WKNavigationTypeFormResubmitted: return 2; // Form
        case WKNavigationTypeBackForward: return 3; // BackForward
        case WKNavigationTypeReload: return 4; // Reload
        default: return 0; // Other
    }
}

static void RaftEnsureGlobalsOnMain(void) {
    if (gSessions == nil) gSessions = [NSMutableDictionary dictionary];
    if (gNamedStores == nil) gNamedStores = [NSMutableDictionary dictionary];
}

@interface RaftMacWebSession : NSObject <WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandlerWithReply>
@property (nonatomic, assign) jlong sessionId;
@property (nonatomic, strong) WKWebView *webView;
@property (nonatomic, copy) NSString *jsbPortName;
@property (nonatomic, copy) NSString *scopedHeaderName;
@property (nonatomic, copy) NSString *pendingHostLoadUrl;
@property (nonatomic, copy) NSString *hostCancelledUrl;
@property (nonatomic, assign) BOOL cacheEnabled;
@property (nonatomic, assign) BOOL kvoAttached;
@property (nonatomic, assign) BOOL disposed;
@property (nonatomic, assign) int64_t nextDecisionId;
@property (nonatomic, assign) int64_t nextReplyId;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, void (^)(WKNavigationActionPolicy)> *pendingDecisions;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, NSNumber *> *pendingDecisionMainFrame;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, void (^)(id, NSString *)> *pendingJsbReplies;

- (void)attachKvo;
- (void)detachKvo;
- (void)applyBoundsX:(NSInteger)x y:(NSInteger)y w:(NSInteger)w h:(NSInteger)h visible:(BOOL)visible;
- (void)teardown;
@end

@implementation RaftMacWebSession

+ (NSArray<NSString *> *)observedKeyPaths {
    static NSArray<NSString *> *paths = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        paths = @[@"URL", @"title", @"estimatedProgress", @"loading", @"canGoBack", @"canGoForward"];
    });
    return paths;
}

- (instancetype)initWithSessionId:(jlong)sessionId {
    self = [super init];
    if (self != nil) {
        _sessionId = sessionId;
        _pendingDecisions = [NSMutableDictionary dictionary];
        _pendingDecisionMainFrame = [NSMutableDictionary dictionary];
        _pendingJsbReplies = [NSMutableDictionary dictionary];
    }
    return self;
}

- (void)attachKvo {
    if (self.kvoAttached || self.webView == nil) return;
    self.kvoAttached = YES;
    for (NSString *path in [RaftMacWebSession observedKeyPaths]) {
        [self.webView addObserver:self
                       forKeyPath:path
                          options:NSKeyValueObservingOptionNew
                          context:&kRaftMacKvoContext];
    }
}

- (void)detachKvo {
    if (!self.kvoAttached || self.webView == nil) return;
    self.kvoAttached = NO;
    for (NSString *path in [RaftMacWebSession observedKeyPaths]) {
        @try {
            [self.webView removeObserver:self forKeyPath:path context:&kRaftMacKvoContext];
        } @catch (__unused NSException *ex) {
        }
    }
}

- (void)applyBoundsX:(NSInteger)x y:(NSInteger)y w:(NSInteger)w h:(NSInteger)h visible:(BOOL)visible {
    if (self.disposed || self.webView == nil) return;
    NSView *superview = self.webView.superview;
    if (superview != nil) {
        CGFloat clampedW = MAX(1.0, (CGFloat)w);
        CGFloat clampedH = MAX(1.0, (CGFloat)h);
        CGFloat sy = superview.isFlipped
            ? (CGFloat)y
            : (superview.bounds.size.height - (CGFloat)y - clampedH);
        self.webView.frame = NSMakeRect((CGFloat)x, sy, clampedW, clampedH);
    }
    self.webView.hidden = !visible;
}

- (void)notifyStateSnapshot {
    if (self.disposed || self.webView == nil) return;
    jlong sid = self.sessionId;
    NSString *url = self.webView.URL.absoluteString ?: @"";
    NSString *title = self.webView.title ?: @"";
    jboolean canGoBack = self.webView.canGoBack ? JNI_TRUE : JNI_FALSE;
    jboolean canGoForward = self.webView.canGoForward ? JNI_TRUE : JNI_FALSE;
    jboolean loading = self.webView.isLoading ? JNI_TRUE : JNI_FALSE;
    jint progress = (jint)(self.webView.estimatedProgress * 100.0 + 0.5);
    if (progress < 0) progress = 0;
    if (progress > 100) progress = 100;
    RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnStateChanged == NULL) return;
        jstring jUrl = RaftJStringFromNSString(env, url);
        jstring jTitle = RaftJStringFromNSString(env, title);
        (*env)->CallStaticVoidMethod(
            env, gBridgeClass, gOnStateChanged,
            sid, jUrl, jTitle, canGoBack, canGoForward, loading, progress
        );
    });
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey, id> *)change
                       context:(void *)context {
    if (context != &kRaftMacKvoContext) {
        [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
        return;
    }
    if (self.disposed || object != self.webView) return;
    [self notifyStateSnapshot];
}

- (void)teardown {
    if (self.disposed) return;
    self.disposed = YES;

    // Settle all outstanding WebKit completion handlers before deallocating WKWebView.
    NSArray<void (^)(WKNavigationActionPolicy)> *decisions = [self.pendingDecisions allValues];
    [self.pendingDecisions removeAllObjects];
    [self.pendingDecisionMainFrame removeAllObjects];
    for (void (^handler)(WKNavigationActionPolicy) in decisions) {
        handler(WKNavigationActionPolicyCancel);
    }

    NSArray<void (^)(id, NSString *)> *replies = [self.pendingJsbReplies allValues];
    [self.pendingJsbReplies removeAllObjects];
    NSString *disposedReply = @"{\"v\":1,\"ok\":false,\"error\":{\"code\":\"BRIDGE_DISABLED\",\"message\":\"Container disposed\"}}";
    for (void (^replyHandler)(id, NSString *) in replies) {
        replyHandler(disposedReply, nil);
    }

    [self detachKvo];
    WKWebView *wv = self.webView;
    self.webView = nil;
    if (wv != nil) {
        wv.navigationDelegate = nil;
        wv.UIDelegate = nil;
        [wv stopLoading];
        if (self.jsbPortName.length > 0) {
            [wv.configuration.userContentController removeScriptMessageHandlerForName:self.jsbPortName
                                                                         contentWorld:[WKContentWorld pageWorld]];
        }
        [wv.configuration.userContentController removeAllUserScripts];
        [wv removeFromSuperview];
    }
}

#pragma mark - WKNavigationDelegate

- (void)webView:(WKWebView *)webView
    decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction
                    decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler {
    if (self.disposed || webView != self.webView) {
        decisionHandler(WKNavigationActionPolicyCancel);
        return;
    }
    NSString *targetUrl = navigationAction.request.URL.absoluteString ?: @"";
    if (navigationAction.targetFrame == nil) {
        jlong sid = self.sessionId;
        RaftWithJniEnv(^(JNIEnv *env) {
            if (gOnNewWindowRequested == NULL) return;
            jstring jUrl = RaftJStringFromNSString(env, targetUrl);
            (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnNewWindowRequested, sid, jUrl);
        });
        decisionHandler(WKNavigationActionPolicyCancel);
        return;
    }
    BOOL isMainFrame = navigationAction.targetFrame.isMainFrame;
    if (isMainFrame && self.pendingHostLoadUrl != nil) {
        NSString *expectedHostUrl = self.pendingHostLoadUrl;
        // Consume once on the first main-frame navigation so bare-origin canonicalization or
        // redirects can never leave pendingHostLoadUrl armed across future navigations.
        self.pendingHostLoadUrl = nil;
        if (RaftUrlsMatchHostLoad(expectedHostUrl, targetUrl)) {
            decisionHandler(WKNavigationActionPolicyAllow);
            return;
        }
    }

    int64_t decisionId = ++self.nextDecisionId;
    NSNumber *dKey = @(decisionId);
    self.pendingDecisions[dKey] = [decisionHandler copy];
    self.pendingDecisionMainFrame[dKey] = @(isMainFrame);
    NSString *method = navigationAction.request.HTTPMethod ?: @"GET";
    BOOL carriesHeader = NO;
    if (self.scopedHeaderName.length > 0) {
        NSString *hv = [navigationAction.request valueForHTTPHeaderField:self.scopedHeaderName];
        carriesHeader = (hv.length > 0);
    }
    BOOL isUserGesture = (navigationAction.navigationType == WKNavigationTypeLinkActivated);
    NSInteger starterCode = RaftMapNavigationStarter(navigationAction.navigationType);
    jlong sid = self.sessionId;
    BOOL dispatched = RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnNavigationDecisionRequest == NULL) return;
        jstring jUrl = RaftJStringFromNSString(env, targetUrl);
        jstring jMethod = RaftJStringFromNSString(env, method);
        (*env)->CallStaticVoidMethod(
            env, gBridgeClass, gOnNavigationDecisionRequest,
            sid, (jlong)decisionId, jUrl,
            isMainFrame ? JNI_TRUE : JNI_FALSE,
            jMethod,
            carriesHeader ? JNI_TRUE : JNI_FALSE,
            isUserGesture ? JNI_TRUE : JNI_FALSE,
            (jint)starterCode
        );
    });
    if (!dispatched || gOnNavigationDecisionRequest == NULL) {
        void (^fallback)(WKNavigationActionPolicy) = self.pendingDecisions[dKey];
        [self.pendingDecisions removeObjectForKey:dKey];
        [self.pendingDecisionMainFrame removeObjectForKey:dKey];
        if (fallback != nil) {
            fallback(WKNavigationActionPolicyCancel);
        }
    }
}

- (void)webView:(WKWebView *)webView didStartProvisionalNavigation:(WKNavigation *)navigation {
    (void)navigation;
    if (self.disposed || webView != self.webView) return;
    self.pendingHostLoadUrl = nil;
    jlong sid = self.sessionId;
    NSString *url = webView.URL.absoluteString ?: @"";
    RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnLoadStarted == NULL) return;
        jstring jUrl = RaftJStringFromNSString(env, url);
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnLoadStarted, sid, jUrl);
    });
}

- (void)webView:(WKWebView *)webView didCommitNavigation:(WKNavigation *)navigation {
    (void)navigation;
    if (self.disposed || webView != self.webView) return;
    self.pendingHostLoadUrl = nil;
    self.hostCancelledUrl = nil;
    jlong sid = self.sessionId;
    NSString *url = webView.URL.absoluteString ?: @"";
    RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnLoadCommit == NULL) return;
        jstring jUrl = RaftJStringFromNSString(env, url);
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnLoadCommit, sid, jUrl);
    });
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(WKNavigation *)navigation {
    (void)navigation;
    if (self.disposed || webView != self.webView) return;
    self.pendingHostLoadUrl = nil;
    self.hostCancelledUrl = nil;
    jlong sid = self.sessionId;
    NSString *url = webView.URL.absoluteString ?: @"";
    RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnLoadFinished == NULL) return;
        jstring jUrl = RaftJStringFromNSString(env, url);
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnLoadFinished, sid, jUrl);
    });
    [self notifyStateSnapshot];
}

- (void)handleNavigationError:(NSError *)error webView:(WKWebView *)webView {
    if (self.disposed || webView != self.webView) return;
    self.pendingHostLoadUrl = nil;
    NSString *failingUrl = error.userInfo[NSURLErrorFailingURLStringErrorKey];
    BOOL isCancelled = ([error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled)
        || ([error.domain isEqualToString:@"WebKitErrorDomain"] && error.code == 102
            && self.hostCancelledUrl != nil
            && (failingUrl.length == 0 || [failingUrl isEqualToString:self.hostCancelledUrl]));
    if (isCancelled) {
        if (failingUrl == nil || [failingUrl isEqualToString:self.hostCancelledUrl]) {
            self.hostCancelledUrl = nil;
        }
        return;
    }
    NSString *reason = [NSString stringWithFormat:@"%@:%ld", error.domain ?: @"WKError", (long)error.code];
    jlong sid = self.sessionId;
    RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnLoadFailure == NULL) return;
        jstring jReason = RaftJStringFromNSString(env, reason);
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnLoadFailure, sid, jReason);
    });
}

- (void)webView:(WKWebView *)webView didFailNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    (void)navigation;
    [self handleNavigationError:error webView:webView];
}

- (void)webView:(WKWebView *)webView didFailProvisionalNavigation:(WKNavigation *)navigation withError:(NSError *)error {
    (void)navigation;
    [self handleNavigationError:error webView:webView];
}

- (void)webViewWebContentProcessDidTerminate:(WKWebView *)webView {
    if (self.disposed || webView != self.webView) return;
    jlong sid = self.sessionId;
    RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnProcessTerminated == NULL) return;
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnProcessTerminated, sid, JNI_TRUE);
    });
}

#pragma mark - WKUIDelegate

- (WKWebView *)webView:(WKWebView *)webView
    createWebViewWithConfiguration:(WKWebViewConfiguration *)configuration
               forNavigationAction:(WKNavigationAction *)navigationAction
                    windowFeatures:(WKWindowFeatures *)windowFeatures {
    (void)configuration;
    (void)windowFeatures;
    if (!self.disposed && webView == self.webView) {
        NSString *targetUrl = navigationAction.request.URL.absoluteString ?: @"";
        jlong sid = self.sessionId;
        RaftWithJniEnv(^(JNIEnv *env) {
            if (gOnNewWindowRequested == NULL) return;
            jstring jUrl = RaftJStringFromNSString(env, targetUrl);
            (*env)->CallStaticVoidMethod(env, gBridgeClass, gOnNewWindowRequested, sid, jUrl);
        });
    }
    return nil;
}

#pragma mark - WKScriptMessageHandlerWithReply

- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message
                 replyHandler:(void (^)(id, NSString *))replyHandler {
    if (self.disposed || self.webView == nil || userContentController != self.webView.configuration.userContentController) {
        replyHandler(@"{\"v\":1,\"ok\":false,\"error\":{\"code\":\"BRIDGE_DISABLED\",\"message\":\"Stale WebView\"}}", nil);
        return;
    }
    NSString *raw = [message.body isKindOfClass:[NSString class]] ? (NSString *)message.body : @"";
    BOOL isMainFrame = message.frameInfo.isMainFrame;
    NSString *frameOrigin = RaftFormatSecurityOrigin(message.frameInfo.securityOrigin);
    NSString *docUrl = self.webView.URL.absoluteString ?: @"";
    int64_t replyId = ++self.nextReplyId;
    NSNumber *rKey = @(replyId);
    self.pendingJsbReplies[rKey] = [replyHandler copy];
    jlong sid = self.sessionId;
    BOOL dispatched = RaftWithJniEnv(^(JNIEnv *env) {
        if (gOnJsbMessage == NULL) return;
        jstring jRaw = RaftJStringFromNSString(env, raw);
        jstring jOrigin = RaftJStringFromNSString(env, frameOrigin);
        jstring jDocUrl = RaftJStringFromNSString(env, docUrl);
        (*env)->CallStaticVoidMethod(
            env, gBridgeClass, gOnJsbMessage,
            sid, (jlong)replyId, jRaw, jOrigin, jDocUrl,
            isMainFrame ? JNI_TRUE : JNI_FALSE
        );
    });
    if (!dispatched || gOnJsbMessage == NULL) {
        void (^fallback)(id, NSString *) = self.pendingJsbReplies[rKey];
        [self.pendingJsbReplies removeObjectForKey:rKey];
        if (fallback != nil) {
            fallback(@"{\"v\":1,\"ok\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Native JSB dispatch failed\"}}", nil);
        }
    }
}

@end

#pragma mark - JNI Lifecycle & Entry Points

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    gJavaVM = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
    jclass localCls = (*env)->FindClass(env, "build/raft/webcontainer/MacOsNativeWebBridge");
    if (localCls == NULL) return JNI_ERR;
    gBridgeClass = (*env)->NewGlobalRef(env, localCls);
    (*env)->DeleteLocalRef(env, localCls);
    if (gBridgeClass == NULL) return JNI_ERR;

    gOnNavigationDecisionRequest = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onNavigationDecisionRequest", "(JJLjava/lang/String;ZLjava/lang/String;ZZI)V");
    gOnStateChanged = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onStateChanged", "(JLjava/lang/String;Ljava/lang/String;ZZZI)V");
    gOnLoadStarted = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onLoadStarted", "(JLjava/lang/String;)V");
    gOnLoadCommit = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onLoadCommit", "(JLjava/lang/String;)V");
    gOnLoadFinished = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onLoadFinished", "(JLjava/lang/String;)V");
    gOnLoadFailure = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onLoadFailure", "(JLjava/lang/String;)V");
    gOnProcessTerminated = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onProcessTerminated", "(JZ)V");
    gOnJsbMessage = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onJsbMessage", "(JJLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
    gOnEvalResult = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onEvalResult", "(JJLjava/lang/String;)V");
    gOnNewWindowRequested = (*env)->GetStaticMethodID(
        env, gBridgeClass, "onNewWindowRequested", "(JLjava/lang/String;)V");

    if (gOnNavigationDecisionRequest == NULL || gOnStateChanged == NULL || gOnLoadStarted == NULL ||
        gOnLoadCommit == NULL || gOnLoadFinished == NULL || gOnLoadFailure == NULL ||
        gOnProcessTerminated == NULL || gOnJsbMessage == NULL || gOnEvalResult == NULL ||
        gOnNewWindowRequested == NULL) {
        (*env)->DeleteGlobalRef(env, gBridgeClass);
        gBridgeClass = NULL;
        return JNI_ERR;
    }
    return JNI_VERSION_1_8;
}

JNIEXPORT jboolean JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeCreateSession(
    JNIEnv *env,
    jclass clazz,
    jlong sessionId,
    jobject awtCanvas,
    jstring jInitialUrl,
    jboolean allowScripts,
    jboolean cacheEnabled,
    jboolean ephemeral,
    jstring jNamedScopeUuid,
    jstring jJsbPortName,
    jstring jUserScriptsJson,
    jboolean allowsBackForwardGestures,
    jstring jHeaderName,
    jstring jHeaderValue,
    jboolean attachInitialHeader,
    jint initialX,
    jint initialY,
    jint initialW,
    jint initialH,
    jboolean initialVisible
) {
    (void)clazz;
    if (awtCanvas == NULL) return JNI_FALSE;

    RaftJawtGetAwtFn getAwt = (RaftJawtGetAwtFn)dlsym(RTLD_DEFAULT, "JAWT_GetAWT");
    if (getAwt == NULL) return JNI_FALSE;

    JAWT awt;
    awt.version = JAWT_VERSION_1_4 | JAWT_MACOSX_USE_CALAYER;
    if (getAwt(env, &awt) == JNI_FALSE) return JNI_FALSE;

    JAWT_DrawingSurface *ds = awt.GetDrawingSurface(env, awtCanvas);
    if (ds == NULL) return JNI_FALSE;

    CALayer *capturedWindowLayer = nil;
    jint lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo *dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi != NULL) {
            if (dsi->platformInfo != NULL) {
                id<JAWT_SurfaceLayers> surfaceLayers = (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;
                capturedWindowLayer = surfaceLayers.windowLayer;
            }
            ds->FreeDrawingSurfaceInfo(dsi);
        }
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);
    if (capturedWindowLayer == nil) return JNI_FALSE;

    NSString *initialUrl = RaftStringFromJString(env, jInitialUrl) ?: @"";
    NSString *namedScopeUuid = RaftStringFromJString(env, jNamedScopeUuid);
    NSString *jsbPortName = RaftStringFromJString(env, jJsbPortName);
    NSString *userScriptsJson = RaftStringFromJString(env, jUserScriptsJson) ?: @"[]";
    NSString *headerName = RaftStringFromJString(env, jHeaderName);
    NSString *headerValue = RaftStringFromJString(env, jHeaderValue);

    dispatch_async(dispatch_get_main_queue(), ^{
        RaftEnsureGlobalsOnMain();
        NSNumber *key = @(sessionId);
        RaftMacWebSession *existing = gSessions[key];
        if (existing != nil) {
            [existing teardown];
            [gSessions removeObjectForKey:key];
        }

        NSView *hostView = RaftFindHostViewFromLayer(capturedWindowLayer);
        if (hostView == nil) {
            RaftWithJniEnv(^(JNIEnv *cbEnv) {
                if (gOnLoadFailure == NULL) return;
                jstring jReason = RaftJStringFromNSString(cbEnv, @"macos_native_host_view_unavailable");
                (*cbEnv)->CallStaticVoidMethod(cbEnv, gBridgeClass, gOnLoadFailure, sessionId, jReason);
            });
            return;
        }

        RaftMacWebSession *session = [[RaftMacWebSession alloc] initWithSessionId:sessionId];
        session.jsbPortName = (allowScripts == JNI_TRUE && jsbPortName.length > 0) ? jsbPortName : nil;
        session.scopedHeaderName = headerName;
        session.cacheEnabled = (cacheEnabled == JNI_TRUE);

        WKUserContentController *ucc = [[WKUserContentController alloc] init];
        if (allowScripts == JNI_TRUE && jsbPortName.length > 0) {
            [ucc addScriptMessageHandlerWithReply:session
                                     contentWorld:[WKContentWorld pageWorld]
                                             name:jsbPortName];
        }
        if (allowScripts == JNI_TRUE && userScriptsJson.length > 2) {
            NSData *jsonData = [userScriptsJson dataUsingEncoding:NSUTF8StringEncoding];
            id parsed = (jsonData != nil)
                ? [NSJSONSerialization JSONObjectWithData:jsonData options:0 error:nil]
                : nil;
            if ([parsed isKindOfClass:[NSArray class]]) {
                for (id item in (NSArray *)parsed) {
                    if (![item isKindOfClass:[NSDictionary class]]) continue;
                    NSDictionary *dict = (NSDictionary *)item;
                    NSString *src = [dict[@"source"] isKindOfClass:[NSString class]] ? dict[@"source"] : nil;
                    BOOL mainOnly = [dict[@"mainFrameOnly"] isKindOfClass:[NSNumber class]]
                        ? [dict[@"mainFrameOnly"] boolValue]
                        : YES;
                    if (src.length > 0) {
                        WKUserScript *script = [[WKUserScript alloc]
                            initWithSource:src
                             injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                          forMainFrameOnly:mainOnly];
                        [ucc addUserScript:script];
                    }
                }
            }
        }

        WKWebViewConfiguration *config = [[WKWebViewConfiguration alloc] init];
        config.userContentController = ucc;
        config.preferences.javaScriptCanOpenWindowsAutomatically = NO;
        WKWebpagePreferences *pagePrefs = [[WKWebpagePreferences alloc] init];
        pagePrefs.allowsContentJavaScript = (allowScripts == JNI_TRUE);
        config.defaultWebpagePreferences = pagePrefs;

        if (ephemeral == JNI_TRUE) {
            config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
        } else if (namedScopeUuid.length > 0) {
            if (@available(macOS 14.0, *)) {
                NSUUID *uuid = [[NSUUID alloc] initWithUUIDString:namedScopeUuid];
                if (uuid != nil) {
                    WKWebsiteDataStore *cached = gNamedStores[namedScopeUuid];
                    if (cached == nil) {
                        cached = [WKWebsiteDataStore dataStoreForIdentifier:uuid];
                        if (cached != nil) gNamedStores[namedScopeUuid] = cached;
                    }
                    config.websiteDataStore = cached ?: [WKWebsiteDataStore nonPersistentDataStore];
                } else {
                    config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
                }
            } else {
                config.websiteDataStore = [WKWebsiteDataStore nonPersistentDataStore];
            }
        } else {
            config.websiteDataStore = [WKWebsiteDataStore defaultDataStore];
        }

        WKWebView *webView = [[WKWebView alloc] initWithFrame:NSMakeRect(0, 0, MAX(1, initialW), MAX(1, initialH))
                                                configuration:config];
        webView.hidden = YES;
        webView.navigationDelegate = session;
        webView.UIDelegate = session;
        webView.allowsBackForwardNavigationGestures = (allowsBackForwardGestures == JNI_TRUE);

        session.webView = webView;
        gSessions[key] = session;
        [hostView addSubview:webView];
        [session applyBoundsX:initialX y:initialY w:initialW h:initialH visible:(initialVisible == JNI_TRUE)];
        [session attachKvo];

        if (initialUrl.length > 0) {
            NSURL *url = [NSURL URLWithString:initialUrl];
            if (url != nil) {
                NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url
                                                                   cachePolicy:RaftCachePolicy(session.cacheEnabled)
                                                               timeoutInterval:60.0];
                if (attachInitialHeader == JNI_TRUE && headerName.length > 0 && headerValue.length > 0) {
                    [req setValue:headerValue forHTTPHeaderField:headerName];
                }
                session.pendingHostLoadUrl = url.absoluteString ?: initialUrl;
                [webView loadRequest:req];
            }
        }
    });
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeUpdateBounds(
    JNIEnv *env, jclass clazz, jlong sessionId, jint x, jint y, jint w, jint h, jboolean visible
) {
    (void)env; (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session == nil || session.disposed) return;
        [session applyBoundsX:x y:y w:w h:h visible:(visible == JNI_TRUE)];
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeLoadUrl(
    JNIEnv *env, jclass clazz, jlong sessionId, jstring jUrl, jstring jHeaderName, jstring jHeaderValue
) {
    (void)clazz;
    NSString *urlStr = RaftStringFromJString(env, jUrl);
    NSString *headerName = RaftStringFromJString(env, jHeaderName);
    NSString *headerValue = RaftStringFromJString(env, jHeaderValue);
    if (urlStr.length == 0) return;
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session == nil || session.disposed || session.webView == nil) return;
        NSURL *url = [NSURL URLWithString:urlStr];
        if (url == nil) return;
        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url
                                                           cachePolicy:RaftCachePolicy(session.cacheEnabled)
                                                       timeoutInterval:60.0];
        if (headerName.length > 0 && headerValue.length > 0) {
            [req setValue:headerValue forHTTPHeaderField:headerName];
        }
        session.pendingHostLoadUrl = url.absoluteString ?: urlStr;
        session.hostCancelledUrl = nil;
        [session.webView loadRequest:req];
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeGoBack(
    JNIEnv *env, jclass clazz, jlong sessionId
) {
    (void)env; (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session != nil && !session.disposed && session.webView.canGoBack) {
            [session.webView goBack];
        }
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeGoForward(
    JNIEnv *env, jclass clazz, jlong sessionId
) {
    (void)env; (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session != nil && !session.disposed && session.webView.canGoForward) {
            [session.webView goForward];
        }
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeReload(
    JNIEnv *env, jclass clazz, jlong sessionId
) {
    (void)env; (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session != nil && !session.disposed && session.webView != nil) {
            [session.webView reload];
        }
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeStopLoading(
    JNIEnv *env, jclass clazz, jlong sessionId
) {
    (void)env; (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session != nil && !session.disposed && session.webView != nil) {
            [session.webView stopLoading];
        }
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeEvaluateJavaScript(
    JNIEnv *env, jclass clazz, jlong sessionId, jlong evalId, jstring jScript, jboolean isolatedWorld
) {
    (void)clazz;
    NSString *script = RaftStringFromJString(env, jScript) ?: @"";
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session == nil || session.disposed || session.webView == nil || script.length == 0) {
            if (evalId != 0) {
                RaftWithJniEnv(^(JNIEnv *cbEnv) {
                    if (gOnEvalResult == NULL) return;
                    (*cbEnv)->CallStaticVoidMethod(cbEnv, gBridgeClass, gOnEvalResult, sessionId, evalId, NULL);
                });
            }
            return;
        }
        WKContentWorld *world = (isolatedWorld == JNI_TRUE)
            ? [WKContentWorld defaultClientWorld]
            : [WKContentWorld pageWorld];
        [session.webView evaluateJavaScript:script
                                    inFrame:nil
                             inContentWorld:world
                          completionHandler:^(id result, NSError *error) {
            if (evalId == 0) return;
            NSString *resultStr = nil;
            if (error == nil && result != nil && result != [NSNull null]) {
                if ([result isKindOfClass:[NSString class]]) {
                    resultStr = (NSString *)result;
                } else {
                    resultStr = [result description];
                }
            }
            RaftWithJniEnv(^(JNIEnv *cbEnv) {
                if (gOnEvalResult == NULL) return;
                jstring jRes = (resultStr != nil) ? RaftJStringFromNSString(cbEnv, resultStr) : NULL;
                (*cbEnv)->CallStaticVoidMethod(cbEnv, gBridgeClass, gOnEvalResult, sessionId, evalId, jRes);
            });
        }];
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeCompleteNavigationDecision(
    JNIEnv *env,
    jclass clazz,
    jlong sessionId,
    jlong decisionId,
    jint actionCode,
    jstring jReissueUrl,
    jstring jHeaderName,
    jstring jHeaderValue
) {
    (void)clazz;
    NSString *reissueUrl = RaftStringFromJString(env, jReissueUrl);
    NSString *headerName = RaftStringFromJString(env, jHeaderName);
    NSString *headerValue = RaftStringFromJString(env, jHeaderValue);
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session == nil) return;
        NSNumber *dKey = @(decisionId);
        void (^handler)(WKNavigationActionPolicy) = session.pendingDecisions[dKey];
        if (handler == nil) return;
        BOOL isMainFrame = [session.pendingDecisionMainFrame[dKey] boolValue];
        [session.pendingDecisions removeObjectForKey:dKey];
        [session.pendingDecisionMainFrame removeObjectForKey:dKey];
        if (session.disposed || actionCode == 0) {
            if (isMainFrame) {
                session.hostCancelledUrl = reissueUrl;
            }
            handler(WKNavigationActionPolicyCancel);
            return;
        }
        if (actionCode == 1) {
            handler(WKNavigationActionPolicyAllow);
            return;
        }
        // actionCode 2 = ReissueWithHeader, 3 = ReissueWithoutHeader
        if (isMainFrame) {
            session.hostCancelledUrl = reissueUrl;
        }
        handler(WKNavigationActionPolicyCancel);
        if (reissueUrl.length > 0 && session.webView != nil) {
            // Defer reissued loadRequest onto the next main-queue turn to avoid WebKit re-entrancy
            // inside the cancelled decidePolicyForNavigationAction completion handler.
            dispatch_async(dispatch_get_main_queue(), ^{
                if (session.disposed || session.webView == nil) return;
                NSURL *url = [NSURL URLWithString:reissueUrl];
                if (url == nil) return;
                NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url
                                                                   cachePolicy:RaftCachePolicy(session.cacheEnabled)
                                                               timeoutInterval:60.0];
                if (actionCode == 2 && headerName.length > 0 && headerValue.length > 0) {
                    [req setValue:headerValue forHTTPHeaderField:headerName];
                }
                session.pendingHostLoadUrl = url.absoluteString ?: reissueUrl;
                [session.webView loadRequest:req];
            });
        }
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeCompleteJsbReply(
    JNIEnv *env, jclass clazz, jlong sessionId, jlong replyId, jstring jReplyJson
) {
    (void)clazz;
    NSString *replyJson = RaftStringFromJString(env, jReplyJson) ?: @"{}";
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftMacWebSession *session = gSessions[@(sessionId)];
        if (session == nil) return;
        NSNumber *rKey = @(replyId);
        void (^replyHandler)(id, NSString *) = session.pendingJsbReplies[rKey];
        if (replyHandler == nil) return;
        [session.pendingJsbReplies removeObjectForKey:rKey];
        replyHandler(replyJson, nil);
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeClearIsolationScope(
    JNIEnv *env, jclass clazz, jstring jNamedScopeUuid
) {
    (void)clazz;
    NSString *namedScopeUuid = RaftStringFromJString(env, jNamedScopeUuid);
    dispatch_async(dispatch_get_main_queue(), ^{
        RaftEnsureGlobalsOnMain();
        NSSet<NSString *> *allTypes = [WKWebsiteDataStore allWebsiteDataTypes];
        NSDate *epoch = [NSDate dateWithTimeIntervalSince1970:0];
        if (namedScopeUuid.length == 0) {
            [[WKWebsiteDataStore defaultDataStore] removeDataOfTypes:allTypes
                                                       modifiedSince:epoch
                                                   completionHandler:^{}];
            return;
        }
        if (@available(macOS 14.0, *)) {
            WKWebsiteDataStore *cached = gNamedStores[namedScopeUuid];
            [gNamedStores removeObjectForKey:namedScopeUuid];
            NSUUID *uuid = [[NSUUID alloc] initWithUUIDString:namedScopeUuid];
            if (cached != nil) {
                [cached removeDataOfTypes:allTypes
                            modifiedSince:epoch
                        completionHandler:^{
                    if (uuid != nil) {
                        [WKWebsiteDataStore removeDataStoreForIdentifier:uuid completionHandler:^(__unused NSError *err) {}];
                    }
                }];
            } else if (uuid != nil) {
                [WKWebsiteDataStore removeDataStoreForIdentifier:uuid completionHandler:^(__unused NSError *err) {}];
            }
        }
    });
}

JNIEXPORT void JNICALL Java_build_raft_webcontainer_MacOsNativeWebBridge_nativeDisposeSession(
    JNIEnv *env, jclass clazz, jlong sessionId
) {
    (void)env; (void)clazz;
    dispatch_async(dispatch_get_main_queue(), ^{
        NSNumber *key = @(sessionId);
        RaftMacWebSession *session = gSessions[key];
        if (session == nil) return;
        [gSessions removeObjectForKey:key];
        [session teardown];
    });
}
