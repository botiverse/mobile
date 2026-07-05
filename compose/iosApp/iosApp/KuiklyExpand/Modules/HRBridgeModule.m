#import "HRBridgeModule.h"

#import "KuiklyRenderViewController.h"
#import <Quiver/QuiverReport.h>
#import "iosApp-Swift.h"
#import <MMKV/MMKV.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>

#if __has_include("SlockIosBuildMetadata.h")
#import "SlockIosBuildMetadata.h"
#endif

#ifndef SLOCK_IOS_BUILD_TIME
#define SLOCK_IOS_BUILD_TIME @""
#endif

#ifndef SLOCK_IOS_GIT_COMMIT
#define SLOCK_IOS_GIT_COMMIT @""
#endif

static NSString *const SlockBridgeDefaultLogTag = @"SlockKuikly";
static NSString *const SlockBridgeReplaceCurrentPageKey = @"slockReplaceCurrentPage";
static NSString *const SlockDiagnosticsLogDirName = @"Slock/diagnostics";
static NSString *const SlockDiagnosticsLogFileName = @"slock-diagnostics.jsonl";
static NSString *const SlockApiEnvironmentMMKVNamespace = @"slock_api_server_environment";
static NSUInteger const SlockDiagnosticsMemoryEntryLimit = 512;
static NSUInteger const SlockDiagnosticsMessageMaxChars = 2048;
static NSUInteger const SlockDiagnosticsRecentMaxChars = 120000;
static unsigned long long const SlockDiagnosticsLogMaxBytes = 512ULL * 1024ULL;
static NSUInteger const SlockDiagnosticsLogMaxFiles = 4;

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

static NSString *SlockBridgeBuildTime(void) {
    return SlockBridgeString(SLOCK_IOS_BUILD_TIME);
}

static NSString *SlockBridgeGitCommit(void) {
    return SlockBridgeString(SLOCK_IOS_GIT_COMMIT);
}

static BOOL SlockBridgeBool(id value) {
    if ([value isKindOfClass:NSNumber.class]) {
        return [(NSNumber *)value boolValue];
    }
    NSString *text = [SlockBridgeString(value) lowercaseString];
    return [text isEqualToString:@"true"] || [text isEqualToString:@"1"] || [text isEqualToString:@"yes"];
}

static MMKV *SlockBridgeApiEnvironmentStore(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        [MMKV initializeMMKV:nil];
    });
    return [MMKV mmkvWithID:SlockApiEnvironmentMMKVNamespace] ?: [MMKV defaultMMKV];
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

static NSMutableArray<NSDictionary *> *SlockDiagnosticsMemoryEntries(void) {
    static NSMutableArray<NSDictionary *> *entries;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        entries = [NSMutableArray arrayWithCapacity:SlockDiagnosticsMemoryEntryLimit];
    });
    return entries;
}

static dispatch_queue_t SlockDiagnosticsWriterQueue(void) {
    static dispatch_queue_t queue;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        queue = dispatch_queue_create("build.raft.app.ios.diagnostics.writer", DISPATCH_QUEUE_SERIAL);
    });
    return queue;
}

static NSString *SlockDiagnosticsTimestamp(void) {
    static NSDateFormatter *formatter;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        formatter = [[NSDateFormatter alloc] init];
        formatter.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
        formatter.dateFormat = @"yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ";
    });
    @synchronized (formatter) {
        return [formatter stringFromDate:[NSDate date]];
    }
}

static NSString *SlockDiagnosticsDirectoryPath(void) {
    NSArray<NSString *> *urls = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES);
    NSString *base = urls.firstObject ?: NSTemporaryDirectory();
    return [base stringByAppendingPathComponent:SlockDiagnosticsLogDirName];
}

static NSString *SlockDiagnosticsActiveLogPath(void) {
    return [SlockDiagnosticsDirectoryPath() stringByAppendingPathComponent:SlockDiagnosticsLogFileName];
}

static NSString *SlockDiagnosticsClippedString(NSString *value, NSUInteger maxLength) {
    if (value.length <= maxLength) {
        return value ?: @"";
    }
    return [value substringToIndex:maxLength];
}

static NSString *SlockDiagnosticsEventName(NSString *message) {
    NSString *event = message ?: @"";
    NSRange range = [event rangeOfString:@" "];
    if (range.location != NSNotFound) {
        event = [event substringToIndex:range.location];
    }
    return SlockDiagnosticsClippedString(event, 96);
}

static NSString *SlockDiagnosticsJsonLine(NSDictionary *entry) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:entry options:0 error:nil];
    if (!data) {
        return @"{}";
    }
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"{}";
}

static void SlockDiagnosticsRemember(NSDictionary *entry) {
    NSMutableArray<NSDictionary *> *entries = SlockDiagnosticsMemoryEntries();
    @synchronized (entries) {
        while (entries.count >= SlockDiagnosticsMemoryEntryLimit) {
            [entries removeObjectAtIndex:0];
        }
        [entries addObject:entry];
    }
}

static NSArray<NSDictionary *> *SlockDiagnosticsSnapshotMemoryEntries(void) {
    NSMutableArray<NSDictionary *> *entries = SlockDiagnosticsMemoryEntries();
    @synchronized (entries) {
        return [entries copy];
    }
}

static NSArray<NSString *> *SlockDiagnosticsLogFiles(void) {
    NSString *dir = SlockDiagnosticsDirectoryPath();
    NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil] ?: @[];
    NSMutableArray<NSString *> *paths = [NSMutableArray array];
    for (NSString *name in names) {
        if (![name hasPrefix:SlockDiagnosticsLogFileName]) {
            continue;
        }
        NSString *path = [dir stringByAppendingPathComponent:name];
        NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
        if ([attributes[NSFileSize] unsignedLongLongValue] > 0) {
            [paths addObject:path];
        }
    }
    [paths sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
        NSDate *leftDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:left error:nil] fileModificationDate] ?: NSDate.distantPast;
        NSDate *rightDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:right error:nil] fileModificationDate] ?: NSDate.distantPast;
        return [rightDate compare:leftDate];
    }];
    return paths;
}

static void SlockDiagnosticsRotateLogsIfNeeded(NSString *activePath) {
    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:activePath error:nil];
    if ([attributes[NSFileSize] unsignedLongLongValue] < SlockDiagnosticsLogMaxBytes) {
        return;
    }
    NSString *dir = [activePath stringByDeletingLastPathComponent];
    NSFileManager *fileManager = [NSFileManager defaultManager];
    for (NSInteger index = (NSInteger)SlockDiagnosticsLogMaxFiles - 1; index >= 1; index--) {
        NSString *source = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.%ld", SlockDiagnosticsLogFileName, (long)index]];
        NSString *target = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.%ld", SlockDiagnosticsLogFileName, (long)index + 1]];
        if (![fileManager fileExistsAtPath:source]) {
            continue;
        }
        if ((NSUInteger)index == SlockDiagnosticsLogMaxFiles - 1) {
            [fileManager removeItemAtPath:source error:nil];
        } else {
            [fileManager removeItemAtPath:target error:nil];
            [fileManager moveItemAtPath:source toPath:target error:nil];
        }
    }
    NSString *rotated = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.1", SlockDiagnosticsLogFileName]];
    [fileManager removeItemAtPath:rotated error:nil];
    [fileManager moveItemAtPath:activePath toPath:rotated error:nil];
}

static void SlockDiagnosticsAppendEntry(NSDictionary *entry) {
    dispatch_async(SlockDiagnosticsWriterQueue(), ^{
        NSString *dir = SlockDiagnosticsDirectoryPath();
        NSFileManager *fileManager = [NSFileManager defaultManager];
        [fileManager createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
        NSString *activePath = SlockDiagnosticsActiveLogPath();
        SlockDiagnosticsRotateLogsIfNeeded(activePath);
        NSString *line = [SlockDiagnosticsJsonLine(entry) stringByAppendingString:@"\n"];
        NSData *data = [line dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
        if (![fileManager fileExistsAtPath:activePath]) {
            [data writeToFile:activePath atomically:YES];
            return;
        }
        NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:activePath];
        [handle seekToEndOfFile];
        [handle writeData:data];
        [handle closeFile];
    });
}

static void SlockDiagnosticsRecord(NSString *level, NSString *tag, NSString *message) {
    NSString *safeTag = SlockDiagnosticsClippedString(SlockBridgeSanitizedLogString(tag ?: @""), 48);
    NSString *safeMessage = SlockBridgeSanitizedLogString(message ?: @"");
    if (safeTag.length == 0 || safeMessage.length == 0) {
        return;
    }
    NSString *normalizedLevel = [[level ?: @"info" lowercaseString] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (![@[@"debug", @"info", @"warn", @"error"] containsObject:normalizedLevel]) {
        normalizedLevel = @"info";
    }
    BOOL truncated = safeMessage.length > SlockDiagnosticsMessageMaxChars;
    NSDictionary *entry = @{
        @"ts" : SlockDiagnosticsTimestamp(),
        @"level" : normalizedLevel,
        @"tag" : safeTag,
        @"event" : SlockDiagnosticsEventName(safeMessage),
        @"message" : SlockDiagnosticsClippedString(safeMessage, SlockDiagnosticsMessageMaxChars),
        @"thread" : NSThread.currentThread.name.length > 0 ? NSThread.currentThread.name : ([NSThread isMainThread] ? @"main" : @"background"),
        @"seq" : @((long long)(NSDate.date.timeIntervalSince1970 * 1000000.0)),
        @"dropped" : @0,
        @"truncated" : @(truncated)
    };
    SlockDiagnosticsRemember(entry);
    SlockDiagnosticsAppendEntry(entry);
}

void SlockIosDiagnosticsRecord(NSString *level, NSString *tag, NSString *message) {
    SlockDiagnosticsRecord(level, tag, message);
}

static NSString *SlockDiagnosticsExportRecentJsonl(NSUInteger maxChars) {
    NSMutableArray<NSString *> *lines = [NSMutableArray array];
    for (NSDictionary *entry in SlockDiagnosticsSnapshotMemoryEntries()) {
        [lines addObject:SlockDiagnosticsJsonLine(entry)];
    }
    NSString *text = [lines componentsJoinedByString:@"\n"];
    if (text.length > maxChars) {
        return [text substringFromIndex:text.length - maxChars];
    }
    return text;
}

static NSString *SlockDiagnosticsReadTail(NSString *path, NSUInteger maxChars) {
    NSData *data = [NSData dataWithContentsOfFile:path] ?: [NSData data];
    NSString *text = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"";
    text = SlockBridgeSanitizedLogString(text);
    if (text.length > maxChars) {
        return [text substringFromIndex:text.length - maxChars];
    }
    return text;
}

static NSString *SlockDiagnosticsExportRecentText(NSUInteger maxChars) {
    dispatch_sync(SlockDiagnosticsWriterQueue(), ^{});
    NSArray<NSString *> *files = SlockDiagnosticsLogFiles();
    NSMutableString *text = [NSMutableString string];
    [text appendString:@"App-owned recent diagnostics\n"];
    [text appendFormat:@"Files: %lu\n", (unsigned long)files.count];
    NSString *memoryJsonl = SlockDiagnosticsExportRecentJsonl(maxChars / 2);
    if (memoryJsonl.length > 0) {
        [text appendString:@"\n### recent-memory.jsonl\n"];
        [text appendString:memoryJsonl];
        [text appendString:@"\n"];
    }
    NSUInteger fileBudget = MAX((NSInteger)maxChars - (NSInteger)text.length, 1000);
    NSUInteger fileCount = MIN(files.count, 6);
    for (NSUInteger index = 0; index < fileCount; index++) {
        NSString *path = files[index];
        NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
        [text appendFormat:@"\n### %@ (%llu bytes)\n", path.lastPathComponent, [attributes[NSFileSize] unsignedLongLongValue]];
        [text appendString:SlockDiagnosticsReadTail(path, MAX(fileBudget / MAX(fileCount, 1), 1000))];
    }
    if (text.length > maxChars) {
        return [text substringFromIndex:text.length - maxChars];
    }
    return text;
}

static void SlockDiagnosticsClear(void) {
    NSMutableArray<NSDictionary *> *entries = SlockDiagnosticsMemoryEntries();
    @synchronized (entries) {
        [entries removeAllObjects];
    }
    dispatch_sync(SlockDiagnosticsWriterQueue(), ^{
        NSString *dir = SlockDiagnosticsDirectoryPath();
        NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil] ?: @[];
        for (NSString *name in names) {
            if ([name hasPrefix:SlockDiagnosticsLogFileName]) {
                [[NSFileManager defaultManager] removeItemAtPath:[dir stringByAppendingPathComponent:name] error:nil];
            }
        }
    });
}

static NSString *SlockDiagnosticsBuildExportText(NSDictionary *about) {
    NSMutableString *text = [NSMutableString string];
    [text appendString:@"Raft diagnostics\n\nEnvironment\n"];
    NSArray<NSString *> *keys = @[@"appName", @"versionLabel", @"buildLabel", @"sourceLabel", @"packageName", @"buildTime", @"gitCommit", @"platformLabel", @"deviceLabel", @"sessionStatusLabel", @"socketStatusLabel"];
    for (NSString *key in keys) {
        NSString *value = SlockBridgeString(about[key]);
        if (value.length > 0) {
            [text appendFormat:@"%@: %@\n", key, value];
        }
    }
    [text appendString:@"\niOS diagnostics\n"];
    [text appendString:SlockDiagnosticsExportRecentText(SlockDiagnosticsRecentMaxChars)];
    return text;
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
    SlockDiagnosticsRecord(level, tag, content);
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
    NSDictionary *params = SlockBridgeParams(args);
    NSMutableDictionary *command = [params isKindOfClass:NSDictionary.class] ? [params mutableCopy] : [NSMutableDictionary dictionary];
    [[SlockIosRealtimeHost shared] handleCommand:command];
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

- (NSDictionary *)slock_developerCapabilityResultWithFeedback:(NSString *)feedback {
    return @{
        @"status" : @"ok",
        @"environmentKey" : @"default",
        @"environmentLabel" : @"Default",
        @"feedback" : feedback.length > 0 ? feedback : @"API environment switching is not wired on iOS P0.",
        @"options" : @[]
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
        NSString *gitCommit = SlockBridgeGitCommit();
        UIDevice *device = UIDevice.currentDevice;
        return @{
            @"status" : @"ok",
            @"appName" : SlockBridgeString(info[@"CFBundleDisplayName"]).length > 0 ? SlockBridgeString(info[@"CFBundleDisplayName"]) : @"Raft",
            @"versionLabel" : version,
            @"buildLabel" : build,
            @"sourceLabel" : gitCommit.length > 0 ? gitCommit : @"unknown",
            @"packageName" : SlockBridgeString(NSBundle.mainBundle.bundleIdentifier),
            @"buildTime" : SlockBridgeBuildTime(),
            @"gitCommit" : gitCommit,
            @"deviceLabel" : [NSString stringWithFormat:@"%@ %@", device.model ?: @"iOS", device.systemVersion ?: @""],
            @"platformLabel" : @"iOS",
            @"sessionStatusLabel" : @"Not wired",
            @"socketStatusLabel" : [SlockIosRealtimeHost shared].statusLabel,
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
        return [self slock_developerCapabilityResultWithFeedback:nil];
    }
    if ([action isEqualToString:@"loadDiagnostics"]) {
        NSString *recentText = SlockDiagnosticsExportRecentText(SlockDiagnosticsRecentMaxChars);
        return @{
            @"status" : @"ok",
            @"entries" : @[
                @{
                    @"timestampLabel" : @"",
                    @"tag" : @"iOS",
                    @"message" : recentText.length > 0 ? recentText : @"No local diagnostics log files are currently available."
                }
            ]
        };
    }
    if ([action isEqualToString:@"setRecompositionProfilerEnabled"]) {
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", [NSString stringWithFormat:@"recomposition_profiler_enabled=%@", SlockBridgeBool(params[@"enabled"]) ? @"true" : @"false"]);
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"copyDiagnostics"]) {
        NSString *exportText = SlockDiagnosticsBuildExportText([self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}]);
        SlockBridgeRunOnMain(^{
            UIPasteboard.generalPasteboard.string = exportText;
        });
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"shared_copy_diagnostics");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"shareDiagnostics"]) {
        NSString *exportText = SlockDiagnosticsBuildExportText([self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}]);
        [self slock_shareItems:@[ exportText ]];
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"shared_share_diagnostics");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"clearDiagnostics"]) {
        SlockDiagnosticsClear();
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"shared_logs_cleared");
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
    if ([action isEqualToString:@"prepareDiagnosticsFeedback"]) {
        return [self slock_prepareDiagnosticsFeedbackDraft];
    }
    if ([action isEqualToString:@"sendFeedback"]) {
        [self slock_promptAndSubmitQuiverFeedback];
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"quiver_feedback_prompt_opened");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"clearStorage"]) {
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

/// Quiver feedback flow (parity with the Android host): prompt for a
/// description, submit a kind=bug ticket with the diagnostics export
/// attached, then copy a shareable "Quiver feedback <id>" reference to the
/// clipboard so the user can paste it in the community.
- (void)slock_promptAndSubmitQuiverFeedback {
    SlockBridgeRunOnMain(^{
        UIViewController *controller = [self slock_currentViewController];
        if (!controller) {
            return;
        }
        UIAlertController *prompt =
            [UIAlertController alertControllerWithTitle:@"Send feedback"
                                                message:@"Your message plus device diagnostics will be sent to Quiver. You'll get a copyable reference to share in the community."
                                         preferredStyle:UIAlertControllerStyleAlert];
        [prompt addTextFieldWithConfigurationHandler:^(UITextField *textField) {
            textField.placeholder = @"Describe your feedback";
        }];
        [prompt addAction:[UIAlertAction actionWithTitle:@"Cancel"
                                                   style:UIAlertActionStyleCancel
                                                 handler:nil]];
        __weak UIAlertController *weakPrompt = prompt;
        [prompt addAction:[UIAlertAction actionWithTitle:@"Send"
                                                   style:UIAlertActionStyleDefault
                                                 handler:^(UIAlertAction *action) {
            NSString *description = [weakPrompt.textFields.firstObject.text
                stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
            if (description.length == 0) {
                return;
            }
            [self slock_submitQuiverFeedbackWithDescription:description];
        }]];
        [controller presentViewController:prompt animated:YES completion:nil];
    });
}

- (void)slock_submitQuiverFeedbackWithDescription:(NSString *)description {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        NSDictionary *draft = [self slock_prepareDiagnosticsFeedbackDraft];
        NSString *path = SlockBridgeString(draft[@"filePath"]);
        NSArray<NSString *> *attachments = path.length > 0 ? @[ path ] : @[];
        [QuiverReport submitFeedback:description
                                 kind:@"bug"
                      attachmentPaths:attachments
                               extras:nil
                           completion:^(NSString *ticketId, NSError *error) {
            SlockBridgeRunOnMain(^{
                UIViewController *controller = [self slock_currentViewController];
                if (error || ticketId.length == 0) {
                    SlockDiagnosticsRecord(@"warn", @"SlockDiagnostics",
                        [NSString stringWithFormat:@"quiver_feedback_submit_failed error=%@", error.localizedDescription ?: @"unknown"]);
                    UIAlertController *failed =
                        [UIAlertController alertControllerWithTitle:@"Feedback failed"
                                                            message:error.localizedDescription ?: @"Unknown error"
                                                     preferredStyle:UIAlertControllerStyleAlert];
                    [failed addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil]];
                    [controller presentViewController:failed animated:YES completion:nil];
                    return;
                }
                NSString *shortId = [ticketId substringToIndex:MIN(ticketId.length, (NSUInteger)8)];
                NSString *shareText = [NSString stringWithFormat:@"Quiver feedback %@\n%@", shortId, description];
                UIPasteboard.generalPasteboard.string = shareText;
                SlockDiagnosticsRecord(@"info", @"SlockDiagnostics",
                    [NSString stringWithFormat:@"quiver_feedback_submitted ticket=%@", shortId]);
                UIAlertController *done =
                    [UIAlertController alertControllerWithTitle:@"Feedback sent"
                                                        message:@"Copied — paste it in the community."
                                                 preferredStyle:UIAlertControllerStyleAlert];
                [done addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil]];
                [controller presentViewController:done animated:YES completion:nil];
            });
        }];
    });
}

- (NSDictionary *)slock_prepareDiagnosticsFeedbackDraft {
    NSDictionary *about = [self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}];
    NSString *summaryText = SlockDiagnosticsBuildExportText(about);
    NSString *fileName = [NSString stringWithFormat:@"slock-feedback-%lld.txt", (long long)(NSDate.date.timeIntervalSince1970 * 1000.0)];
    NSString *dir = SlockDiagnosticsDirectoryPath();
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    NSString *path = [dir stringByAppendingPathComponent:fileName];
    [summaryText writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil];
    NSData *data = [summaryText dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
    NSString *bodyBase64 = [data base64EncodedStringWithOptions:0];
    NSString *message = [NSString stringWithFormat:@"## Raft feedback\n\nDiagnostics attachment: %@\n\n<!-- Describe what happened here. -->", fileName];
    SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", [NSString stringWithFormat:@"diagnostics_feedback_prepared file=%@ bytes=%lu", fileName, (unsigned long)data.length]);
    return @{
        @"status" : @"ok",
        @"message" : message,
        @"fileName" : fileName,
        @"mimeType" : @"text/plain",
        @"sizeBytes" : @(data.length),
        @"bodyBase64" : bodyBase64,
        @"filePath" : path
    };
}

- (void)slock_shareItems:(NSArray *)items {
    if (items.count == 0) {
        return;
    }
    SlockBridgeRunOnMain(^{
        UIViewController *controller = [self slock_currentViewController];
        if (!controller) {
            NSLog(@"[%@][warn] share skipped missing controller", SlockBridgeDefaultLogTag);
            return;
        }
        UIActivityViewController *activityController = [[UIActivityViewController alloc] initWithActivityItems:items applicationActivities:nil];
        UIPopoverPresentationController *popover = activityController.popoverPresentationController;
        if (popover) {
            popover.sourceView = controller.view;
            popover.sourceRect = CGRectMake(CGRectGetMidX(controller.view.bounds), CGRectGetMidY(controller.view.bounds), 1, 1);
            popover.permittedArrowDirections = 0;
        }
        [controller presentViewController:activityController animated:YES completion:nil];
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
