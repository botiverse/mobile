#import "HRBridgeModule.h"

#import "KuiklyRenderViewController.h"
#import <OpenKuiklyIOSRender/NSObject+KR.h>

static NSString *const SlockBridgeDefaultLogTag = @"SlockKuikly";
static NSString *const SlockBridgeReplaceCurrentPageKey = @"slockReplaceCurrentPage";

static NSDictionary *SlockBridgeDictionaryFromValue(id value) {
    if ([value isKindOfClass:NSDictionary.class]) {
        return (NSDictionary *)value;
    }
    if (![value isKindOfClass:NSString.class]) {
        return @{};
    }
    NSString *text = (NSString *)value;
    NSDictionary *kuiklyDictionary = [text hr_stringToDictionary];
    if ([kuiklyDictionary isKindOfClass:NSDictionary.class]) {
        return kuiklyDictionary;
    }
    NSData *data = [text dataUsingEncoding:NSUTF8StringEncoding];
    if (!data) {
        return @{};
    }
    id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [object isKindOfClass:NSDictionary.class] ? object : @{};
}

static NSDictionary *SlockBridgeParams(NSDictionary *args) {
    return SlockBridgeDictionaryFromValue(args[KR_PARAM_KEY]);
}

static NSString *SlockBridgeString(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return [(NSString *)value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [[value stringValue] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    }
    return @"";
}

static BOOL SlockBridgeBool(id value) {
    if ([value isKindOfClass:NSNumber.class]) {
        return [(NSNumber *)value boolValue];
    }
    NSString *text = [SlockBridgeString(value) lowercaseString];
    return [text isEqualToString:@"true"] || [text isEqualToString:@"1"] || [text isEqualToString:@"yes"];
}

static NSDictionary *SlockBridgeIdentityPageData(NSDictionary *pageData) {
    if (![pageData isKindOfClass:NSDictionary.class]) {
        return @{};
    }
    static NSSet<NSString *> *blockedKeys;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        blockedKeys = [NSSet setWithArray:@[
            @"messages",
            @"tasks",
            @"members",
            @"files",
            @"attachments",
            @"profiles",
            @"plans",
            @"computers",
            @"suggestions",
            @"composerState",
            @"snapshot"
        ]];
    });
    NSMutableDictionary *sanitized = [pageData mutableCopy];
    for (NSString *key in blockedKeys) {
        if (sanitized[key] != nil) {
            [sanitized removeObjectForKey:key];
            NSLog(@"[%@][warn] dropped non-identity route payload key=%@", SlockBridgeDefaultLogTag, key);
        }
    }
    return sanitized;
}

static NSString *SlockBridgeSanitizedLogString(NSString *content) {
    NSString *sanitized = content ?: @"";
    NSArray<NSString *> *patterns = @[
        @"(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/]+=*",
        @"(?i)(sk_(agent|machine)_[A-Za-z0-9_\\-]+)",
        @"(?i)((accessToken|refreshToken|token|authorization|cookie|password|secret)\\s*[:=]\\s*)[^\\s,}]+"
    ];
    NSArray<NSString *> *templates = @[
        @"$1<redacted>",
        @"sk_<redacted>",
        @"$1<redacted>"
    ];
    for (NSUInteger index = 0; index < patterns.count; index++) {
        NSRegularExpression *regex =
            [NSRegularExpression regularExpressionWithPattern:patterns[index] options:0 error:nil];
        if (!regex) {
            continue;
        }
        sanitized = [regex stringByReplacingMatchesInString:sanitized
                                                    options:0
                                                      range:NSMakeRange(0, sanitized.length)
                                               withTemplate:templates[index]];
    }
    return sanitized;
}

static void SlockBridgeRunOnMain(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

// 扩展桥接接口
/*
 * @brief Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */

@implementation HRBridgeModule

@synthesize hr_rootView;

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *content = SlockBridgeString(params[@"content"]);
    if (content.length == 0) {
        return;
    }
    SlockBridgeRunOnMain(^{
        UIPasteboard *pasteboard = [UIPasteboard generalPasteboard];
        pasteboard.string = content;
    });
}

- (void)log:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *content = SlockBridgeSanitizedLogString(SlockBridgeString(params[@"content"]));
    if (content.length == 0) {
        return;
    }
    NSString *level = [[SlockBridgeString(params[@"level"]) lowercaseString] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (![@[@"debug", @"info", @"warn", @"error", @"fatal"] containsObject:level]) {
        level = @"info";
    }
    if ([level isEqualToString:@"fatal"]) {
        level = @"error";
    }
    NSString *tag = SlockBridgeString(params[@"tag"]);
    if (tag.length == 0) {
        tag = SlockBridgeDefaultLogTag;
    }
    NSLog(@"[%@][%@] %@", tag, level, content);
}

- (void)openPage:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *pageName = SlockBridgeString(params[@"pageName"]);
    if (pageName.length == 0) {
        pageName = SlockBridgeString(params[@"url"]);
    }
    if (pageName.length == 0) {
        NSLog(@"[%@][warn] openPage ignored missing pageName", SlockBridgeDefaultLogTag);
        return;
    }
    NSDictionary *pageData =
        SlockBridgeDictionaryFromValue(params[@"pageData"]).count > 0
            ? SlockBridgeDictionaryFromValue(params[@"pageData"])
            : SlockBridgeDictionaryFromValue(params[@"userData"]);
    NSMutableDictionary *nextPageData = [SlockBridgeIdentityPageData(pageData) mutableCopy];
    if (nextPageData[@"pageName"] == nil) {
        nextPageData[@"pageName"] = pageName;
    }
    BOOL replaceCurrent =
        SlockBridgeBool(params[SlockBridgeReplaceCurrentPageKey]) ||
        SlockBridgeBool(params[@"closeCurPage"]);
    if (replaceCurrent) {
        nextPageData[SlockBridgeReplaceCurrentPageKey] = @YES;
    }
    [self slock_openPageWithName:pageName pageData:nextPageData replaceCurrent:replaceCurrent];
}

- (void)closePage:(NSDictionary *)args {
    UIViewController *controller = [self slock_currentViewController];
    SlockBridgeRunOnMain(^{
        UINavigationController *navigationController = controller.navigationController;
        if (navigationController && navigationController.viewControllers.count > 1) {
            [navigationController popViewControllerAnimated:YES];
            return;
        }
        if (controller.presentingViewController) {
            [controller dismissViewControllerAnimated:YES completion:nil];
            return;
        }
        NSLog(@"[%@][warn] closePage no-op: missing navigation stack", SlockBridgeDefaultLogTag);
    });
}

- (void)openUrl:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *urlText = SlockBridgeString(params[@"url"]);
    if (urlText.length == 0) {
        NSLog(@"[%@][warn] openUrl ignored missing url", SlockBridgeDefaultLogTag);
        return;
    }
    NSURL *url = [NSURL URLWithString:urlText];
    if (!url || url.scheme.length == 0) {
        NSLog(@"[%@][warn] openUrl ignored invalid url", SlockBridgeDefaultLogTag);
        return;
    }
    SlockBridgeRunOnMain(^{
        [[UIApplication sharedApplication] openURL:url
                                           options:@{}
                                 completionHandler:^(BOOL success) {
            if (!success) {
                NSLog(@"[%@][warn] openUrl failed", SlockBridgeDefaultLogTag);
            }
        }];
    });
}

- (void)toast:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *content = SlockBridgeString(params[@"content"]);
    if (content.length == 0) {
        return;
    }
    SlockBridgeRunOnMain(^{
        [self slock_showToast:content];
    });
}

- (void)localServeTime:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback(@{ @"time" : @([[NSDate date] timeIntervalSince1970]) });
    }
}

- (id)currentTimestamp:(NSDictionary *)args {
    long long timestamp = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
    return [NSString stringWithFormat:@"%lld", timestamp];
}

- (id)dateFormatter:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *format = SlockBridgeString(params[@"format"]);
    if (format.length == 0) {
        format = @"yyyy-MM-dd HH:mm:ss";
    }
    double timestamp = [params[@"timeStamp"] respondsToSelector:@selector(doubleValue)] ? [params[@"timeStamp"] doubleValue] : 0;
    if (timestamp <= 0) {
        return @"";
    }
    NSTimeInterval seconds = timestamp > 10000000000.0 ? timestamp / 1000.0 : timestamp;
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    formatter.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
    formatter.dateFormat = format;
    return [formatter stringFromDate:[NSDate dateWithTimeIntervalSince1970:seconds]] ?: @"";
}

- (void)performanceScene:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *sceneName = SlockBridgeString(params[@"sceneName"]);
    if (sceneName.length == 0) {
        return;
    }
    NSString *action = SlockBridgeString(params[@"action"]);
    if (action.length == 0) {
        action = @"enter";
    }
    NSLog(@"[SlockPerfScene] action=%@ scene=%@", action, sceneName);
}

- (void)performanceMarker:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *routeName = SlockBridgeString(params[@"routeName"]);
    NSString *phase = SlockBridgeString(params[@"phase"]);
    if (routeName.length == 0 && phase.length == 0) {
        return;
    }
    NSString *sceneId = SlockBridgeString(params[@"sceneId"]);
    NSString *event = SlockBridgeString(params[@"event"]);
    NSString *threadHash = SlockBridgeString(params[@"threadHash"]);
    NSString *duration = params[@"durationMs"] ? [NSString stringWithFormat:@" durationMs=%@", params[@"durationMs"]] : @"";
    NSLog(@"[SlockPerfMarker] sceneId=%@ route=%@ phase=%@ event=%@ threadHash=%@%@",
          sceneId.length > 0 ? sceneId : @"unknown",
          routeName.length > 0 ? routeName : @"unknown",
          phase.length > 0 ? phase : @"unknown",
          event.length > 0 ? event : @"instant",
          threadHash.length > 0 ? threadHash : @"unknown",
          duration);
}

- (void)kuiklyPerformanceSnapshot:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *summary = SlockBridgeString(params[@"summary"]);
    if (summary.length > 0) {
        NSLog(@"[SlockKuiklyPerfSnapshot] %@", SlockBridgeSanitizedLogString(summary));
    }
}

- (void)appBadge:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSInteger count = MAX(0, [params[@"count"] respondsToSelector:@selector(integerValue)] ? [params[@"count"] integerValue] : 0);
    SlockBridgeRunOnMain(^{
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        [UIApplication sharedApplication].applicationIconBadgeNumber = count;
#pragma clang diagnostic pop
        NSLog(@"[%@][info] appBadge count=%ld source=%@ stale=%@",
              SlockBridgeDefaultLogTag,
              (long)count,
              SlockBridgeString(params[@"source"]),
              SlockBridgeBool(params[@"isStale"]) ? @"true" : @"false");
    });
}

- (void)settingsCapability:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    callback([self slock_settingsCapabilityResultForAction:SlockBridgeString(params[@"action"]) params:params]);
}

- (void)threadAttachmentUpload:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback(@{
            @"code" : @204,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_picker_unavailable",
            @"errorBodyText" : @"Attachment picker unavailable on iOS P0 host bridge"
        });
    }
}

- (void)socialAuth:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback([self slock_unsupportedResultWithCode:@"social_auth_unavailable"
                                               message:@"Social auth is out of scope for iOS P0 host bridge"]);
    }
}

- (void)openAttachment:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback(@{
            @"code" : @204,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_open_unavailable",
            @"errorBodyText" : @"Attachment opening is out of scope for iOS P0 host bridge"
        });
    }
}

- (void)attachmentCacheStatus:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback([self slock_unsupportedResultWithCode:@"attachment_cache_unavailable"
                                               message:@"Attachment cache is out of scope for iOS P0 host bridge"]);
    }
}

- (void)deleteAttachmentCache:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback([self slock_unsupportedResultWithCode:@"attachment_cache_unavailable"
                                               message:@"Attachment cache is out of scope for iOS P0 host bridge"]);
    }
}

- (void)attachmentDownloadControl:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback([self slock_unsupportedResultWithCode:@"attachment_download_unavailable"
                                               message:@"Attachment downloads are out of scope for iOS P0 host bridge"]);
    }
}

- (void)relationalStore:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback([self slock_unsupportedResultWithCode:@"relational_store_unavailable"
                                               message:@"Relational store is out of scope for iOS P0 host bridge"]);
    }
}

- (void)motionCapability:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback([self slock_unsupportedResultWithCode:@"motion_capability_unavailable"
                                               message:@"Motion capability is out of scope for iOS P0 host bridge"]);
    }
}

- (void)reportRealTime:(NSDictionary *)args {
    NSLog(@"[%@][warn] realtime bridge is out of scope for iOS P0 host bridge", SlockBridgeDefaultLogTag);
}

- (void)reportRealtime:(NSDictionary *)args {
    [self reportRealTime:args];
}

#pragma mark - Private

- (NSDictionary *)slock_unsupportedResultWithCode:(NSString *)code message:(NSString *)message {
    return @{
        @"status" : @"error",
        @"code" : code ?: @"unsupported",
        @"message" : message ?: @"Unsupported on iOS P0 host bridge"
    };
}

- (NSDictionary *)slock_settingsCapabilityResultForAction:(NSString *)action params:(NSDictionary *)params {
    if (action.length == 0) {
        return [self slock_unsupportedResultWithCode:@"settings_capability_unknown"
                                             message:@"Missing settings capability action"];
    }
    if ([action isEqualToString:@"loadAbout"]) {
        NSDictionary *info = NSBundle.mainBundle.infoDictionary ?: @{};
        NSString *version = SlockBridgeString(info[@"CFBundleShortVersionString"]);
        NSString *build = SlockBridgeString(info[@"CFBundleVersion"]);
        UIDevice *device = UIDevice.currentDevice;
        return @{
            @"status" : @"ok",
            @"appName" : SlockBridgeString(info[@"CFBundleDisplayName"]).length > 0 ? SlockBridgeString(info[@"CFBundleDisplayName"]) : @"Raft",
            @"versionLabel" : version,
            @"buildLabel" : build,
            @"sourceLabel" : @"iOS P0 host bridge",
            @"packageName" : NSBundle.mainBundle.bundleIdentifier ?: @"",
            @"deviceLabel" : [NSString stringWithFormat:@"%@ %@", device.model ?: @"iOS", device.systemVersion ?: @""],
            @"platformLabel" : @"iOS",
            @"sessionStatusLabel" : @"Not wired",
            @"socketStatusLabel" : @"Realtime unsupported",
            @"notificationStatusLabel" : @"Notifications unsupported"
        };
    }
    if ([action isEqualToString:@"loadStorage"]) {
        return @{
            @"status" : @"ok",
            @"totalSizeLabel" : @"--",
            @"feedback" : @"iOS P0 host bridge does not expose storage usage yet.",
            @"rows" : @[
                @{
                    @"target" : @"database",
                    @"title" : @"Local database",
                    @"detail" : @"Database storage is not wired on iOS P0.",
                    @"sizeLabel" : @"--",
                    @"clearEnabled" : @NO
                },
                @{
                    @"target" : @"imageCache",
                    @"title" : @"Image cache",
                    @"detail" : @"Image cache clearing is not wired on iOS P0.",
                    @"sizeLabel" : @"--",
                    @"clearEnabled" : @NO
                },
                @{
                    @"target" : @"temporaryAndLogs",
                    @"title" : @"Temporary files and logs",
                    @"detail" : @"Diagnostics export is not wired on iOS P0.",
                    @"sizeLabel" : @"--",
                    @"clearEnabled" : @NO
                }
            ]
        };
    }
    if ([action isEqualToString:@"loadDeveloper"]) {
        return @{
            @"status" : @"ok",
            @"environmentKey" : @"default",
            @"environmentLabel" : @"Default",
            @"feedback" : @"API environment switching is not wired on iOS P0.",
            @"options" : @[]
        };
    }
    if ([action isEqualToString:@"loadDiagnostics"]) {
        return @{
            @"status" : @"ok",
            @"entries" : @[
                @{
                    @"timestampLabel" : @"",
                    @"tag" : @"iOS",
                    @"message" : @"iOS P0 host diagnostics are local console logs only."
                }
            ]
        };
    }
    if ([action isEqualToString:@"setRecompositionProfilerEnabled"] ||
        [action isEqualToString:@"copyDiagnostics"] ||
        [action isEqualToString:@"shareDiagnostics"] ||
        [action isEqualToString:@"clearDiagnostics"]) {
        NSLog(@"[%@][info] settingsCapability %@ handled as local no-op", SlockBridgeDefaultLogTag, action);
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"apiEnvironmentChanged"]) {
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"setApiEnvironment"]) {
        return [self slock_unsupportedResultWithCode:@"settings_developer_environment_unsupported"
                                             message:@"API environment switching is not wired on iOS P0"];
    }
    if ([action isEqualToString:@"requestNotificationPermission"] ||
        [action isEqualToString:@"openNotificationSettings"] ||
        [action isEqualToString:@"sendTestNotification"] ||
        [action isEqualToString:@"postUnreadNotification"] ||
        [action isEqualToString:@"cancelTestNotification"] ||
        [action isEqualToString:@"cancelNotification"]) {
        return @{
            @"status" : @"unsupported",
            @"code" : @"notification_unsupported",
            @"message" : @"Notifications are out of scope for iOS P0 host bridge"
        };
    }
    if ([action isEqualToString:@"prepareDiagnosticsFeedback"] ||
        [action isEqualToString:@"sendFeedback"] ||
        [action isEqualToString:@"clearStorage"]) {
        return [self slock_unsupportedResultWithCode:@"settings_capability_unsupported"
                                             message:[NSString stringWithFormat:@"%@ is not wired on iOS P0", action]];
    }
    return [self slock_unsupportedResultWithCode:@"settings_capability_unknown"
                                         message:[NSString stringWithFormat:@"Unsupported settings capability action: %@", action]];
}

- (void)slock_openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData replaceCurrent:(BOOL)replaceCurrent {
    SlockBridgeRunOnMain(^{
        UIViewController *controller = [self slock_currentViewController];
        KuiklyRenderViewController *renderViewController =
            [[KuiklyRenderViewController alloc] initWithPageName:pageName pageData:pageData ?: @{}];
        UINavigationController *navigationController = controller.navigationController;
        if (navigationController) {
            if (replaceCurrent && navigationController.viewControllers.count > 0) {
                NSMutableArray<UIViewController *> *viewControllers = [navigationController.viewControllers mutableCopy];
                [viewControllers removeLastObject];
                [viewControllers addObject:renderViewController];
                [navigationController setViewControllers:viewControllers animated:YES];
            } else {
                [navigationController pushViewController:renderViewController animated:YES];
            }
            return;
        }
        UINavigationController *presentedNavigationController =
            [[UINavigationController alloc] initWithRootViewController:renderViewController];
        [controller presentViewController:presentedNavigationController animated:YES completion:nil];
    });
}

- (UIViewController *)slock_currentViewController {
    UIViewController *controller = self.hr_rootView.kr_viewController;
    if (!controller) {
        controller = [self slock_keyWindow].rootViewController;
    }
    return [self slock_topViewControllerFrom:controller] ?: controller;
}

- (UIViewController *)slock_topViewControllerFrom:(UIViewController *)controller {
    if ([controller isKindOfClass:UINavigationController.class]) {
        return [self slock_topViewControllerFrom:((UINavigationController *)controller).topViewController];
    }
    if ([controller isKindOfClass:UITabBarController.class]) {
        return [self slock_topViewControllerFrom:((UITabBarController *)controller).selectedViewController];
    }
    if (controller.presentedViewController) {
        return [self slock_topViewControllerFrom:controller.presentedViewController];
    }
    return controller;
}

- (UIWindow *)slock_keyWindow {
    if (self.hr_rootView.window) {
        return self.hr_rootView.window;
    }
    if (@available(iOS 13.0, *)) {
        for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
            if (scene.activationState != UISceneActivationStateForegroundActive ||
                ![scene isKindOfClass:UIWindowScene.class]) {
                continue;
            }
            for (UIWindow *window in ((UIWindowScene *)scene).windows) {
                if (window.isKeyWindow) {
                    return window;
                }
            }
        }
    }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    return UIApplication.sharedApplication.keyWindow ?: UIApplication.sharedApplication.windows.firstObject;
#pragma clang diagnostic pop
}

- (void)slock_showToast:(NSString *)content {
    UIViewController *controller = [self slock_currentViewController];
    UIView *hostView = controller.view ?: [self slock_keyWindow];
    if (!hostView) {
        NSLog(@"[%@][info] toast: %@", SlockBridgeDefaultLogTag, content);
        return;
    }
    UILabel *label = [[UILabel alloc] init];
    label.text = content;
    label.textColor = UIColor.whiteColor;
    label.backgroundColor = [[UIColor blackColor] colorWithAlphaComponent:0.82];
    label.textAlignment = NSTextAlignmentCenter;
    label.font = [UIFont systemFontOfSize:14 weight:UIFontWeightMedium];
    label.numberOfLines = 3;
    label.layer.cornerRadius = 10;
    label.layer.masksToBounds = YES;
    CGFloat maxWidth = MAX(160, hostView.bounds.size.width - 48);
    CGSize textSize = [label sizeThatFits:CGSizeMake(maxWidth - 32, CGFLOAT_MAX)];
    CGFloat width = MIN(maxWidth, textSize.width + 32);
    CGFloat height = textSize.height + 20;
    CGFloat y = hostView.bounds.size.height - hostView.safeAreaInsets.bottom - height - 72;
    label.frame = CGRectMake((hostView.bounds.size.width - width) / 2.0, MAX(24, y), width, height);
    label.alpha = 0;
    label.autoresizingMask = UIViewAutoresizingFlexibleTopMargin | UIViewAutoresizingFlexibleLeftMargin | UIViewAutoresizingFlexibleRightMargin;
    [hostView addSubview:label];
    [UIView animateWithDuration:0.18 animations:^{
        label.alpha = 1;
    } completion:^(BOOL finished) {
        [UIView animateWithDuration:0.22
                              delay:1.6
                            options:UIViewAnimationOptionCurveEaseInOut
                         animations:^{
            label.alpha = 0;
        } completion:^(BOOL finished) {
            [label removeFromSuperview];
        }];
    }];
}

@end
