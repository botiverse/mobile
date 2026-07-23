#import "HRBridgeModule.h"
#import "RaftDiagnosticsPathAdmission.h"

#import "KuiklyRenderViewController.h"
#import <Hands/Hands.h>
#import <shared/shared.h>
#import "iosApp-Swift.h"
#import <MMKV/MMKV.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>
#import <mach/mach_time.h>
#import <os/lock.h>
#import <pthread.h>
#import <Photos/Photos.h>
#import <PhotosUI/PhotosUI.h>
#import <stdatomic.h>
#import <string.h>
#import <time.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#import <UserNotifications/UserNotifications.h>

#if __has_include("RaftIosBuildMetadata.h")
#import "RaftIosBuildMetadata.h"
#endif

#ifndef RAFT_IOS_BUILD_TIME
#define RAFT_IOS_BUILD_TIME @""
#endif

#ifndef RAFT_IOS_GIT_COMMIT
#define RAFT_IOS_GIT_COMMIT @""
#endif

static NSString *const SlockBridgeDefaultLogTag = @"SlockKuikly";
static NSString *const SlockBridgeReplaceCurrentPageKey = @"slockReplaceCurrentPage";
static NSString *const SlockDiagnosticsLogDirName = @"raft_diagnostics_logs";
static NSString *const SlockDiagnosticsLogFileName = @"raft-diagnostics.jsonl";
static NSString *const SlockDiagnosticsFeedbackDirName = @"raft-feedback";
static NSString *const SlockDiagnosticsFeedbackFilePrefix = @"raft-feedback-";
static NSString *const SlockDiagnosticsSummaryFileName = @"raft-diagnostics-summary.txt";
static NSString *const SlockApiEnvironmentMMKVNamespace = @"slock_api_server_environment";
static NSUInteger const SlockDiagnosticsMemoryEntryLimit = 512;
static NSUInteger const SlockDiagnosticsMessageMaxChars = 2048;
static NSUInteger const SlockDiagnosticsRecentMaxChars = 120000;
static unsigned long long const SlockDiagnosticsLogMaxBytes = 512ULL * 1024ULL;
static NSUInteger const SlockDiagnosticsLogMaxFiles = 4;
static NSUInteger const SlockDiagnosticsMaxLaunchSessions = 5;
static NSUInteger const SlockDiagnosticsAttachmentMaxLogFiles = 20;
static NSUInteger const SlockDiagnosticsMinimumRetainedSnapshotCount = 5;
static NSTimeInterval const SlockDiagnosticsFeedbackPruneMinimumAgeSeconds = 24.0 * 60.0 * 60.0;
static NSUInteger const SlockDiagnosticsOutstandingLimit = 512;
static NSUInteger const SlockDiagnosticsOrdinaryOutstandingLimit = 496;
static NSUInteger const SlockDiagnosticsRawLevelMaxChars = 16;
static NSUInteger const SlockDiagnosticsRawTagMaxChars = 128;
static NSUInteger const SlockDiagnosticsRawMessageMaxChars = 4096;
static NSUInteger const SlockDiagnosticsThreadNameMaxChars = 64;
static NSUInteger const SlockDiagnosticsQueueLabelMaxChars = 96;
static NSUInteger const SlockDiagnosticsExceptionPageMaxChars = 128;
static NSUInteger const SlockDiagnosticsExceptionReasonMaxChars = 1024;
static NSUInteger const SlockDiagnosticsExceptionStackMaxChars = 2048;
static char SlockDiagnosticsWriterQueueSpecificKey;
static os_unfair_lock SlockDiagnosticsAdmissionLock = OS_UNFAIR_LOCK_INIT;
static NSUInteger SlockDiagnosticsOutstandingCount = 0;
static uint64_t SlockDiagnosticsNextSequence = 0;
static atomic_uint_fast64_t SlockDiagnosticsAttemptedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsAcceptedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsAcceptedCriticalCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsDroppedOrdinaryCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsDroppedCriticalCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsDroppedContentionCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsRejectedInvalidCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsProcessedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsWrittenCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsCriticalSynchronizedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsWriteFailedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsRotationFailedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t SlockDiagnosticsMaxOutstandingCount = ATOMIC_VAR_INIT(0);

@interface SlockDiagnosticsCapture : NSObject

@property(nonatomic, copy) NSString *rawLevel;
@property(nonatomic, copy) NSString *rawTag;
@property(nonatomic, copy) NSString *rawMessage;
@property(nonatomic, copy) NSString *threadName;
@property(nonatomic, copy) NSString *queueLabel;
@property(nonatomic) uint64_t sequence;
@property(nonatomic) uint64_t monotonicTicks;
@property(nonatomic) uint64_t threadId;
@property(nonatomic) int64_t timestampMillis;
@property(nonatomic) BOOL mainThread;
@property(nonatomic) BOOL rawMessageTruncated;
@property(nonatomic) BOOL critical;
@property(nonatomic) BOOL emitConsole;

@end

@implementation SlockDiagnosticsCapture
@end

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

static NSString *SlockBridgeRawString(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return (NSString *)value;
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [value stringValue] ?: @"";
    }
    return @"";
}

static NSString *SlockHandsFeedbackAttachmentBlock(NSArray<NSString *> *attachmentPaths) {
    NSMutableArray<NSString *> *attachmentNames = [NSMutableArray array];
    NSCharacterSet *lineBreaks = NSCharacterSet.newlineCharacterSet;
    for (id value in attachmentPaths ?: @[]) {
        NSString *path = SlockBridgeString(value);
        NSString *fileName = path.lastPathComponent;
        if (fileName.length == 0) {
            continue;
        }
        NSArray<NSString *> *parts = [fileName componentsSeparatedByCharactersInSet:lineBreaks];
        NSString *normalized = [[parts componentsJoinedByString:@" "]
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
        if (normalized.length > 0) {
            [attachmentNames addObject:normalized];
        }
    }
    if (attachmentNames.count == 0) {
        return @"";
    }
    return [NSString stringWithFormat:@"attachments:\n%@", [attachmentNames componentsJoinedByString:@"\n"]];
}

static NSString *SlockBridgeBuildTime(void) {
    return SlockBridgeString(RAFT_IOS_BUILD_TIME);
}

static NSString *SlockBridgeGitCommit(void) {
    return SlockBridgeString(RAFT_IOS_GIT_COMMIT);
}

static NSDictionary *SlockDiagnosticsCurrentEnvironmentAbout(void) {
    __block NSDictionary *about = nil;
    dispatch_block_t collect = ^{
        NSDictionary *info = NSBundle.mainBundle.infoDictionary ?: @{};
        NSString *version = SlockBridgeString(info[@"CFBundleShortVersionString"]);
        NSString *build = SlockBridgeString(info[@"CFBundleVersion"]);
        NSString *gitCommit = SlockBridgeGitCommit();
        UIDevice *device = UIDevice.currentDevice;
        UIScreen *screen = UIScreen.mainScreen;
        CGRect bounds = screen.bounds;
        CGFloat scale = screen.scale;
        NSString *contentSizeCategory = @"--";
        if (@available(iOS 10.0, *)) {
            contentSizeCategory = SlockBridgeString(UIApplication.sharedApplication.preferredContentSizeCategory).length > 0
                ? SlockBridgeString(UIApplication.sharedApplication.preferredContentSizeCategory)
                : @"--";
        }
        about = @{
            @"appName" : SlockBridgeString(info[@"CFBundleDisplayName"]).length > 0 ? SlockBridgeString(info[@"CFBundleDisplayName"]) : @"Raft",
            @"versionLabel" : version,
            @"versionCodeLabel" : build,
            @"buildLabel" : build,
            @"sourceLabel" : gitCommit.length > 0 ? gitCommit : @"unknown",
            @"packageName" : SlockBridgeString(NSBundle.mainBundle.bundleIdentifier),
            @"buildTime" : SlockBridgeBuildTime(),
            @"gitCommit" : gitCommit,
            @"deviceLabel" : [NSString stringWithFormat:@"%@ %@", device.model ?: @"iOS", device.systemVersion ?: @""],
            @"modelLabel" : device.model ?: @"--",
            @"platformLabel" : [NSString stringWithFormat:@"iOS %@", device.systemVersion ?: @""],
            @"sdkLabel" : @"--",
            @"installTimeLabel" : @"--",
            @"screenLabel" : [NSString stringWithFormat:@"%.0fx%.0f pt @%.0fx", bounds.size.width, bounds.size.height, scale],
            @"densityLabel" : [NSString stringWithFormat:@"@%.0fx", scale],
            @"fontScaleLabel" : contentSizeCategory,
            @"sessionStatusLabel" : @"Not wired",
            @"signedInUserLabel" : @"--",
            @"selectedServerLabel" : @"--",
            @"socketStatusLabel" : [SlockIosRealtimeHost shared].statusLabel,
            @"notificationStatusLabel" : @"Notifications unsupported"
        };
    };
    if ([NSThread isMainThread]) {
        collect();
    } else {
        dispatch_sync(dispatch_get_main_queue(), collect);
    }
    return about ?: @{};
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

static BOOL SlockDiagnosticsIsAsciiIdentifierCharacter(unichar character) {
    return (character >= 'a' && character <= 'z') ||
        (character >= 'A' && character <= 'Z') ||
        (character >= '0' && character <= '9') ||
        character == '_' || character == '-';
}

static BOOL SlockDiagnosticsIsWhitespace(unichar character) {
    return [[NSCharacterSet whitespaceAndNewlineCharacterSet] characterIsMember:character];
}

static NSInteger SlockDiagnosticsHexValue(unichar character) {
    if (character >= '0' && character <= '9') {
        return character - '0';
    }
    if (character >= 'a' && character <= 'f') {
        return character - 'a' + 10;
    }
    if (character >= 'A' && character <= 'F') {
        return character - 'A' + 10;
    }
    return -1;
}

static NSString *SlockDiagnosticsNormalizedCredentialKey(NSString *key) {
    NSMutableString *normalized = [NSMutableString stringWithCapacity:key.length];
    for (NSUInteger index = 0; index < key.length; index++) {
        unichar character = [key characterAtIndex:index];
        if (character == '\\' && index + 5 < key.length &&
            ([key characterAtIndex:index + 1] == 'u' || [key characterAtIndex:index + 1] == 'U')) {
            NSInteger decoded = 0;
            BOOL validEscape = YES;
            for (NSUInteger offset = 2; offset < 6; offset++) {
                NSInteger digit = SlockDiagnosticsHexValue([key characterAtIndex:index + offset]);
                if (digit < 0) {
                    validEscape = NO;
                    break;
                }
                decoded = (decoded << 4) | digit;
            }
            if (validEscape) {
                character = (unichar)decoded;
                index += 5;
            }
        }
        if (character >= 'A' && character <= 'Z') {
            character = (unichar)(character - 'A' + 'a');
        }
        if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
            [normalized appendFormat:@"%C", character];
        }
    }
    return normalized;
}

static BOOL SlockDiagnosticsIsCredentialKey(NSString *key) {
    static NSSet<NSString *> *keys;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        keys = [NSSet setWithArray:@[
            @"accesstoken",
            @"refreshtoken",
            @"idtoken",
            @"sessiontoken",
            @"authtoken",
            @"apitoken",
            @"oauthtoken",
            @"token",
            @"authorization",
            @"proxyauthorization",
            @"cookie",
            @"setcookie",
            @"password",
            @"passwd",
            @"secret",
            @"clientkey",
            @"clientsecret",
            @"apikey",
            @"apisecret",
            @"privatekey"
        ]];
    });
    NSString *canonicalKey = SlockDiagnosticsNormalizedCredentialKey(key);
    if ([keys containsObject:canonicalKey]) {
        return YES;
    }
    return canonicalKey.length > 1 && [canonicalKey hasPrefix:@"x"] &&
        [keys containsObject:[canonicalKey substringFromIndex:1]];
}

static BOOL SlockDiagnosticsParseAssignmentAtIndex(
    NSString *text,
    NSUInteger index,
    BOOL requireCredentialKey,
    NSUInteger *valueStart,
    NSString **normalizedKey,
    BOOL *jsonStyle
) {
    if (index >= text.length) {
        return NO;
    }
    if (index > 0 && SlockDiagnosticsIsAsciiIdentifierCharacter([text characterAtIndex:index - 1])) {
        return NO;
    }
    NSUInteger cursor = index;
    NSUInteger keyStart = cursor;
    NSUInteger keyEnd = cursor;
    BOOL keyWasQuoted = NO;
    unichar quote = [text characterAtIndex:cursor];
    if (quote == '"' || quote == '\'') {
        keyWasQuoted = YES;
        keyStart = ++cursor;
        BOOL escaped = NO;
        while (cursor < text.length) {
            unichar character = [text characterAtIndex:cursor];
            if (escaped) {
                escaped = NO;
            } else if (character == '\\') {
                escaped = YES;
            } else if (character == quote) {
                break;
            }
            cursor++;
        }
        if (cursor >= text.length) {
            return NO;
        }
        keyEnd = cursor;
        cursor++;
    } else {
        if (!SlockDiagnosticsIsAsciiIdentifierCharacter(quote)) {
            return NO;
        }
        while (cursor < text.length) {
            while (cursor < text.length && SlockDiagnosticsIsAsciiIdentifierCharacter([text characterAtIndex:cursor])) {
                cursor++;
            }
            keyEnd = cursor;
            NSUInteger nextWord = cursor;
            while (nextWord < text.length && SlockDiagnosticsIsWhitespace([text characterAtIndex:nextWord])) {
                nextWord++;
            }
            if (!requireCredentialKey || nextWord == cursor || nextWord >= text.length ||
                !SlockDiagnosticsIsAsciiIdentifierCharacter([text characterAtIndex:nextWord])) {
                break;
            }
            cursor = nextWord;
        }
    }
    while (cursor < text.length && SlockDiagnosticsIsWhitespace([text characterAtIndex:cursor])) {
        cursor++;
    }
    if (cursor >= text.length) {
        return NO;
    }
    unichar separator = [text characterAtIndex:cursor];
    if (separator != ':' && separator != '=' && separator != 0xFF1A && separator != 0xFF1D) {
        return NO;
    }
    NSString *key = [text substringWithRange:NSMakeRange(keyStart, keyEnd - keyStart)];
    NSString *canonicalKey = SlockDiagnosticsNormalizedCredentialKey(key);
    if (requireCredentialKey && !SlockDiagnosticsIsCredentialKey(canonicalKey)) {
        return NO;
    }
    cursor++;
    while (cursor < text.length && SlockDiagnosticsIsWhitespace([text characterAtIndex:cursor])) {
        cursor++;
    }
    if (valueStart) {
        *valueStart = cursor;
    }
    if (normalizedKey) {
        *normalizedKey = canonicalKey;
    }
    if (jsonStyle) {
        *jsonStyle = keyWasQuoted && separator == ':';
    }
    return YES;
}

static NSUInteger SlockDiagnosticsJsonContainerEnd(NSString *text, NSUInteger start) {
    if (start >= text.length) {
        return start;
    }
    unichar opening = [text characterAtIndex:start];
    if (opening != '{' && opening != '[') {
        return start;
    }
    NSMutableArray<NSNumber *> *stack = [NSMutableArray arrayWithObject:@(opening)];
    unichar quote = 0;
    BOOL escaped = NO;
    for (NSUInteger cursor = start + 1; cursor < text.length; cursor++) {
        unichar character = [text characterAtIndex:cursor];
        if (quote != 0) {
            if (escaped) {
                escaped = NO;
            } else if (character == '\\') {
                escaped = YES;
            } else if (character == quote) {
                quote = 0;
            }
            continue;
        }
        if (character == '"' || character == '\'') {
            quote = character;
            continue;
        }
        if (character == '{' || character == '[') {
            [stack addObject:@(character)];
            continue;
        }
        if (character != '}' && character != ']') {
            continue;
        }
        unichar expectedOpening = character == '}' ? '{' : '[';
        if (stack.lastObject.unsignedShortValue != expectedOpening) {
            return text.length;
        }
        [stack removeLastObject];
        if (stack.count == 0) {
            return cursor + 1;
        }
    }
    return text.length;
}

static NSString *SlockDiagnosticsRedactCredentialAssignments(NSString *content) {
    NSMutableString *result = [content mutableCopy] ?: [NSMutableString string];
    NSUInteger index = 0;
    while (index < result.length) {
        NSUInteger valueStart = 0;
        NSString *normalizedKey = nil;
        BOOL jsonStyle = NO;
        if (!SlockDiagnosticsParseAssignmentAtIndex(result, index, YES, &valueStart, &normalizedKey, &jsonStyle)) {
            index++;
            continue;
        }
        NSUInteger valueEnd = valueStart;
        BOOL quoted = NO;
        unichar valueQuote = 0;
        if (valueStart < result.length) {
            valueQuote = [result characterAtIndex:valueStart];
            quoted = valueQuote == '"' || valueQuote == '\'';
        }
        if (quoted) {
            NSUInteger cursor = valueStart + 1;
            BOOL escaped = NO;
            while (cursor < result.length) {
                unichar character = [result characterAtIndex:cursor];
                if (escaped) {
                    escaped = NO;
                } else if (character == '\\') {
                    escaped = YES;
                } else if (character == valueQuote) {
                    break;
                }
                cursor++;
            }
            BOOL hasClosingQuote = cursor < result.length;
            NSUInteger contentStart = valueStart + 1;
            valueEnd = cursor;
            [result replaceCharactersInRange:NSMakeRange(contentStart, valueEnd - contentStart)
                                   withString:@"<redacted>"];
            index = contentStart + @"<redacted>".length + (hasClosingQuote ? 1 : 0);
            continue;
        }
        BOOL consumeWholeHeader = [normalizedKey isEqualToString:@"cookie"] ||
            [normalizedKey isEqualToString:@"setcookie"];
        NSUInteger cursor = valueStart;
        if (jsonStyle && cursor < result.length &&
            ([result characterAtIndex:cursor] == '{' || [result characterAtIndex:cursor] == '[')) {
            cursor = SlockDiagnosticsJsonContainerEnd(result, cursor);
        } else {
            while (cursor < result.length) {
                unichar character = [result characterAtIndex:cursor];
                if (character == '\n' || character == '\r' ||
                    (jsonStyle && (character == '}' || character == ']'))) {
                    break;
                }
                if (jsonStyle && character == ',') {
                    break;
                }
                if (!consumeWholeHeader &&
                    (SlockDiagnosticsIsWhitespace(character) || character == ',' || character == ';' || character == '&')) {
                    NSUInteger candidate = cursor + (SlockDiagnosticsIsWhitespace(character) ? 0 : 1);
                    while (candidate < result.length && SlockDiagnosticsIsWhitespace([result characterAtIndex:candidate])) {
                        candidate++;
                    }
                    if (candidate >= result.length ||
                        SlockDiagnosticsParseAssignmentAtIndex(result, candidate, NO, NULL, NULL, NULL)) {
                        break;
                    }
                }
                cursor++;
            }
        }
        valueEnd = cursor;
        NSString *replacement = jsonStyle ? @"\"<redacted>\"" : @"<redacted>";
        [result replaceCharactersInRange:NSMakeRange(valueStart, valueEnd - valueStart)
                               withString:replacement];
        index = valueStart + replacement.length;
    }
    return result;
}

static NSString *SlockBridgeSanitizedLogString(NSString *content) {
    NSString *sanitized = [content isKindOfClass:NSString.class] ? content : @"";
    static NSArray<NSDictionary<NSString *, id> *> *tokenRules;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSArray<NSDictionary<NSString *, NSString *> *> *specs = @[
            @{
                @"pattern" : @"(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/]+=*",
                @"template" : @"$1<redacted>"
            },
            @{
                @"pattern" : @"(?i)sk_(agent|machine)_[A-Za-z0-9_.=+\\-/]+",
                @"template" : @"sk_$1_<redacted>"
            }
        ];
        NSMutableArray<NSDictionary<NSString *, id> *> *compiled = [NSMutableArray arrayWithCapacity:specs.count];
        for (NSDictionary<NSString *, NSString *> *spec in specs) {
            NSRegularExpression *regex =
                [NSRegularExpression regularExpressionWithPattern:spec[@"pattern"] options:0 error:nil];
            if (regex) {
                [compiled addObject:@{ @"regex" : regex, @"template" : spec[@"template"] }];
            }
        }
        tokenRules = [compiled copy];
    });
    for (NSDictionary<NSString *, id> *rule in tokenRules) {
        NSRegularExpression *regex = rule[@"regex"];
        sanitized = [regex stringByReplacingMatchesInString:sanitized
                                                    options:0
                                                      range:NSMakeRange(0, sanitized.length)
                                               withTemplate:rule[@"template"]];
    }
    return SlockDiagnosticsRedactCredentialAssignments(sanitized);
}

static void SlockBridgeRunOnMain(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

static NSUInteger SlockDiagnosticsMemoryStart = 0;
static NSUInteger SlockDiagnosticsMemoryCount = 0;

static NSMutableArray *SlockDiagnosticsMemorySlots(void) {
    static NSMutableArray *slots;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        slots = [NSMutableArray arrayWithCapacity:SlockDiagnosticsMemoryEntryLimit];
        for (NSUInteger index = 0; index < SlockDiagnosticsMemoryEntryLimit; index++) {
            [slots addObject:NSNull.null];
        }
    });
    return slots;
}

static dispatch_queue_t SlockDiagnosticsOwnerQueue(void) {
    static dispatch_queue_t queue;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        dispatch_queue_attr_t attributes =
            dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_UTILITY, 0);
        queue = dispatch_queue_create("build.raft.app.ios.diagnostics.owner", attributes);
        dispatch_queue_set_specific(
            queue,
            &SlockDiagnosticsWriterQueueSpecificKey,
            &SlockDiagnosticsWriterQueueSpecificKey,
            NULL
        );
    });
    return queue;
}

static id SlockDiagnosticsPerformOwnerOperation(id (^operation)(void)) {
    if (dispatch_get_specific(&SlockDiagnosticsWriterQueueSpecificKey) == &SlockDiagnosticsWriterQueueSpecificKey) {
        return operation ? operation() : nil;
    }
    dispatch_semaphore_t completion = dispatch_semaphore_create(0);
    __block id result = nil;
    os_unfair_lock_lock(&SlockDiagnosticsAdmissionLock);
    dispatch_async(SlockDiagnosticsOwnerQueue(), ^{
        @autoreleasepool {
            @try {
                result = operation ? operation() : nil;
            } @finally {
                dispatch_semaphore_signal(completion);
            }
        }
    });
    os_unfair_lock_unlock(&SlockDiagnosticsAdmissionLock);
    dispatch_semaphore_wait(completion, DISPATCH_TIME_FOREVER);
    return result;
}

static NSString *SlockDiagnosticsTimestampForMillis(int64_t timestampMillis) {
    static NSDateFormatter *formatter;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        formatter = [[NSDateFormatter alloc] init];
        formatter.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
        formatter.dateFormat = @"yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ";
    });
    NSDate *date = [NSDate dateWithTimeIntervalSince1970:((NSTimeInterval)timestampMillis / 1000.0)];
    return [formatter stringFromDate:date];
}

static NSString *SlockDiagnosticsDirectoryPath(void) {
    NSArray<NSString *> *urls = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES);
    NSString *base = urls.firstObject ?: NSTemporaryDirectory();
    return [base stringByAppendingPathComponent:SlockDiagnosticsLogDirName];
}

static NSString *SlockDiagnosticsLaunchSessionIdentifier(void) {
    static NSString *identifier;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        long long timestampMs = (long long)(NSDate.date.timeIntervalSince1970 * 1000.0);
        identifier = [NSString stringWithFormat:@"launch-%lld-%@", timestampMs, NSUUID.UUID.UUIDString.lowercaseString];
    });
    return identifier;
}

static NSArray<NSString *> *SlockDiagnosticsLaunchSessionDirectories(void) {
    NSString *root = SlockDiagnosticsDirectoryPath();
    NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:root error:nil] ?: @[];
    NSMutableArray<NSString *> *directories = [NSMutableArray array];
    for (NSString *name in names) {
        if (![name hasPrefix:@"launch-"]) {
            continue;
        }
        NSString *path = [root stringByAppendingPathComponent:name];
        BOOL isDirectory = NO;
        if ([[NSFileManager defaultManager] fileExistsAtPath:path isDirectory:&isDirectory] && isDirectory) {
            [directories addObject:path];
        }
    }
    [directories sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
        return [right.lastPathComponent compare:left.lastPathComponent];
    }];
    return directories;
}

static NSString *SlockDiagnosticsCurrentLaunchDirectoryPath(void) {
    static NSString *path;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSString *root = SlockDiagnosticsDirectoryPath();
        path = [root stringByAppendingPathComponent:SlockDiagnosticsLaunchSessionIdentifier()];
        NSFileManager *fileManager = [NSFileManager defaultManager];
        [fileManager createDirectoryAtPath:path withIntermediateDirectories:YES attributes:nil error:nil];
        NSArray<NSString *> *sessions = SlockDiagnosticsLaunchSessionDirectories();
        for (NSUInteger index = SlockDiagnosticsMaxLaunchSessions; index < sessions.count; index++) {
            NSString *candidate = sessions[index];
            if (![candidate isEqualToString:path]) {
                [fileManager removeItemAtPath:candidate error:nil];
            }
        }
    });
    return path;
}

static NSString *SlockDiagnosticsActiveLogPath(void) {
    return [SlockDiagnosticsCurrentLaunchDirectoryPath() stringByAppendingPathComponent:SlockDiagnosticsLogFileName];
}

static BOOL SlockDiagnosticsIsHighSurrogate(unichar character) {
    return character >= 0xD800 && character <= 0xDBFF;
}

static BOOL SlockDiagnosticsIsLowSurrogate(unichar character) {
    return character >= 0xDC00 && character <= 0xDFFF;
}

static NSString *SlockDiagnosticsStringByReplacingInvalidSurrogates(NSString *source) {
    NSMutableString *result = [NSMutableString stringWithCapacity:source.length];
    for (NSUInteger index = 0; index < source.length; index++) {
        unichar character = [source characterAtIndex:index];
        if (SlockDiagnosticsIsHighSurrogate(character)) {
            if (index + 1 < source.length) {
                unichar next = [source characterAtIndex:index + 1];
                if (SlockDiagnosticsIsLowSurrogate(next)) {
                    [result appendFormat:@"%C%C", character, next];
                    index++;
                    continue;
                }
            }
            [result appendString:@"\uFFFD"];
            continue;
        }
        if (SlockDiagnosticsIsLowSurrogate(character)) {
            [result appendString:@"\uFFFD"];
            continue;
        }
        [result appendFormat:@"%C", character];
    }
    return result;
}

static NSUInteger SlockDiagnosticsSafePrefixEnd(NSString *source, NSUInteger maxLength) {
    NSUInteger end = MIN(source.length, maxLength);
    if (end < source.length && end > 0) {
        unichar previous = [source characterAtIndex:end - 1];
        unichar next = [source characterAtIndex:end];
        if (SlockDiagnosticsIsHighSurrogate(previous) && SlockDiagnosticsIsLowSurrogate(next)) {
            end--;
        }
        if (end > 0) {
            @try {
                NSRange composed = [source rangeOfComposedCharacterSequenceAtIndex:end - 1];
                if (NSMaxRange(composed) > end) {
                    end = composed.location;
                }
            } @catch (__unused NSException *exception) {
                // Invalid UTF-16 is normalized below.
            }
        }
    }
    return end;
}

static NSUInteger SlockDiagnosticsRawPrefixEnd(NSString *source, NSUInteger maxLength) {
    NSUInteger end = MIN(source.length, maxLength);
    if (end < source.length && end > 0) {
        unichar previous = [source characterAtIndex:end - 1];
        unichar next = [source characterAtIndex:end];
        if (SlockDiagnosticsIsHighSurrogate(previous) && SlockDiagnosticsIsLowSurrogate(next)) {
            end--;
        }
    }
    return end;
}

static NSString *SlockDiagnosticsValidClippedString(NSString *value, NSUInteger maxLength) {
    NSString *source = [value isKindOfClass:NSString.class] ? value : @"";
    NSUInteger end = SlockDiagnosticsSafePrefixEnd(source, maxLength);
    NSString *clipped = end < source.length ? [source substringToIndex:end] : source;
    return SlockDiagnosticsStringByReplacingInvalidSurrogates(clipped);
}

static NSString *SlockDiagnosticsClippedString(NSString *value, NSUInteger maxLength) {
    return SlockDiagnosticsValidClippedString(value, maxLength);
}

static NSString *SlockDiagnosticsClippedCopy(NSString *value, NSUInteger maxLength) {
    NSString *source = [value isKindOfClass:NSString.class] ? value : @"";
    NSUInteger end = SlockDiagnosticsRawPrefixEnd(source, maxLength);
    return [(end < source.length ? [source substringToIndex:end] : source) copy] ?: @"";
}

static BOOL SlockDiagnosticsContainsNonWhitespace(NSString *value) {
    for (NSUInteger index = 0; index < value.length; index++) {
        if (!SlockDiagnosticsIsWhitespace([value characterAtIndex:index])) {
            return YES;
        }
    }
    return NO;
}

static NSString *SlockDiagnosticsValidSuffixString(NSString *value, NSUInteger maxLength) {
    NSString *source = SlockDiagnosticsStringByReplacingInvalidSurrogates(
        [value isKindOfClass:NSString.class] ? value : @""
    );
    if (source.length <= maxLength) {
        return source;
    }
    NSUInteger start = source.length - maxLength;
    if (start < source.length && start > 0 &&
        SlockDiagnosticsIsLowSurrogate([source characterAtIndex:start]) &&
        SlockDiagnosticsIsHighSurrogate([source characterAtIndex:start - 1])) {
        start++;
    }
    if (start < source.length) {
        @try {
            NSRange composed = [source rangeOfComposedCharacterSequenceAtIndex:start];
            if (composed.location < start) {
                start = NSMaxRange(composed);
            }
        } @catch (__unused NSException *exception) {
            // Invalid UTF-16 was normalized above; retain the surrogate-safe boundary.
        }
    }
    return start < source.length ? [source substringFromIndex:start] : @"";
}

static NSString *SlockDiagnosticsBoundedUTF8String(const char *value, NSUInteger maxLength, NSString *fallback) {
    if (!value || maxLength == 0) {
        return fallback ?: @"";
    }
    size_t length = strnlen(value, maxLength);
    if (length == 0) {
        return fallback ?: @"";
    }
    NSString *result = [[NSString alloc] initWithBytes:value length:length encoding:NSUTF8StringEncoding];
    return result.length > 0 ? result : (fallback ?: @"");
}

static int64_t SlockDiagnosticsNowMillis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0) {
        return (int64_t)(NSDate.date.timeIntervalSince1970 * 1000.0);
    }
    return ((int64_t)now.tv_sec * 1000LL) + ((int64_t)now.tv_nsec / 1000000LL);
}

static BOOL SlockDiagnosticsRawRecordIsCritical(NSString *level, NSString *tag, NSString *message, BOOL forceCritical) {
    (void)tag;
    (void)message;
    NSString *normalizedLevel = [level stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (forceCritical ||
        [normalizedLevel caseInsensitiveCompare:@"warn"] == NSOrderedSame ||
        [normalizedLevel caseInsensitiveCompare:@"error"] == NSOrderedSame ||
        [normalizedLevel caseInsensitiveCompare:@"fatal"] == NSOrderedSame) {
        return YES;
    }
    return NO;
}

static SlockDiagnosticsCapture *SlockDiagnosticsCreateCapture(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
) {
    NSString *rawLevel = SlockDiagnosticsClippedCopy(level ?: @"info", SlockDiagnosticsRawLevelMaxChars);
    NSString *rawTag = SlockDiagnosticsClippedCopy(tag ?: @"", SlockDiagnosticsRawTagMaxChars);
    NSString *rawMessage = SlockDiagnosticsClippedCopy(message ?: @"", SlockDiagnosticsRawMessageMaxChars);
    if (rawTag.length == 0 || rawMessage.length == 0 || !SlockDiagnosticsContainsNonWhitespace(rawMessage)) {
        return nil;
    }
    char threadNameBuffer[128] = {0};
    pthread_getname_np(pthread_self(), threadNameBuffer, sizeof(threadNameBuffer));
    BOOL mainThread = pthread_main_np() != 0;
    NSString *threadName = SlockDiagnosticsBoundedUTF8String(
        threadNameBuffer,
        SlockDiagnosticsThreadNameMaxChars,
        mainThread ? @"main" : @"background"
    );
    const char *queueLabelValue = dispatch_queue_get_label(DISPATCH_CURRENT_QUEUE_LABEL);
    NSString *queueLabel = SlockDiagnosticsBoundedUTF8String(
        queueLabelValue,
        SlockDiagnosticsQueueLabelMaxChars,
        mainThread ? @"com.apple.main-thread" : @"unknown"
    );
    uint64_t threadId = 0;
    pthread_threadid_np(NULL, &threadId);
    SlockDiagnosticsCapture *capture = [[SlockDiagnosticsCapture alloc] init];
    capture.rawLevel = rawLevel;
    capture.rawTag = rawTag;
    capture.rawMessage = rawMessage;
    capture.threadName = threadName;
    capture.queueLabel = queueLabel;
    capture.monotonicTicks = mach_continuous_time();
    capture.threadId = threadId;
    capture.timestampMillis = timestampMillis > 0 ? timestampMillis : SlockDiagnosticsNowMillis();
    capture.mainThread = mainThread;
    capture.rawMessageTruncated = (message.length > SlockDiagnosticsRawMessageMaxChars);
    capture.critical = SlockDiagnosticsRawRecordIsCritical(rawLevel, rawTag, rawMessage, forceCritical);
    capture.emitConsole = emitConsole;
    return capture;
}

static NSString *SlockDiagnosticsEventName(NSString *message) {
    NSString *event = message ?: @"";
    NSRange range = [event rangeOfString:@" "];
    if (range.location != NSNotFound) {
        event = [event substringToIndex:range.location];
    }
    return SlockDiagnosticsClippedString(event, 96);
}

static NSString * _Nullable SlockDiagnosticsJsonLine(NSDictionary *entry) {
    NSError *error = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:entry options:0 error:&error];
    if (!data) {
        return nil;
    }
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}

static void SlockDiagnosticsRemember(NSDictionary *entry) {
    NSMutableArray *slots = SlockDiagnosticsMemorySlots();
    NSUInteger index = 0;
    if (SlockDiagnosticsMemoryCount < SlockDiagnosticsMemoryEntryLimit) {
        index = (SlockDiagnosticsMemoryStart + SlockDiagnosticsMemoryCount) % SlockDiagnosticsMemoryEntryLimit;
        SlockDiagnosticsMemoryCount++;
    } else {
        index = SlockDiagnosticsMemoryStart;
        SlockDiagnosticsMemoryStart = (SlockDiagnosticsMemoryStart + 1) % SlockDiagnosticsMemoryEntryLimit;
    }
    [slots replaceObjectAtIndex:index withObject:entry];
}

static NSArray<NSDictionary *> *SlockDiagnosticsSnapshotMemoryEntries(void) {
    NSMutableArray *slots = SlockDiagnosticsMemorySlots();
    NSMutableArray<NSDictionary *> *entries = [NSMutableArray arrayWithCapacity:SlockDiagnosticsMemoryCount];
    for (NSUInteger offset = 0; offset < SlockDiagnosticsMemoryCount; offset++) {
        NSUInteger index = (SlockDiagnosticsMemoryStart + offset) % SlockDiagnosticsMemoryEntryLimit;
        id value = slots[index];
        if ([value isKindOfClass:NSDictionary.class]) {
            [entries addObject:value];
        }
    }
    return entries;
}

static BOOL SlockDiagnosticsIsLogFileName(NSString *name) {
    return [name hasPrefix:SlockDiagnosticsLogFileName];
}

static NSString *SlockDiagnosticsUniqueSnapshotPath(
    NSString *directory,
    NSString *fileName,
    NSMutableSet<NSString *> *reservedPaths
) {
    NSString *base = fileName.stringByDeletingPathExtension;
    NSString *extension = fileName.pathExtension;
    NSUInteger suffix = 1;
    while (YES) {
        NSString *candidateName = fileName;
        if (suffix > 1) {
            NSString *suffixedBase = [NSString stringWithFormat:@"%@-%lu", base, (unsigned long)suffix];
            candidateName = extension.length > 0
                ? [suffixedBase stringByAppendingPathExtension:extension]
                : suffixedBase;
        }
        NSString *candidatePath = [directory stringByAppendingPathComponent:candidateName];
        if (![reservedPaths containsObject:candidatePath]) {
            [reservedPaths addObject:candidatePath];
            return candidatePath;
        }
        suffix++;
    }
}

static NSComparisonResult SlockDiagnosticsComparePathsByMtimeThenPath(NSString *left, NSString *right) {
    NSDate *leftDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:left error:nil] fileModificationDate]
        ?: NSDate.distantPast;
    NSDate *rightDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:right error:nil] fileModificationDate]
        ?: NSDate.distantPast;
    NSComparisonResult timeResult = [rightDate compare:leftDate];
    return timeResult == NSOrderedSame ? [left compare:right] : timeResult;
}

static NSString *SlockDiagnosticsCanonicalDescendantFilePath(NSString *root, NSString *path) {
    return RaftDiagnosticsCanonicalDescendantRegularFilePath(NSFileManager.defaultManager, root, path);
}

static NSArray<NSString *> *SlockDiagnosticsLogFilesAtRoot(NSString *root, NSUInteger maxSessions) {
    NSMutableArray<NSString *> *paths = [NSMutableArray array];
    NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:root error:nil] ?: @[];
    NSMutableArray<NSString *> *sessions = [NSMutableArray array];
    for (NSString *name in names) {
        if (![name hasPrefix:@"launch-"]) continue;
        NSString *path = [root stringByAppendingPathComponent:name];
        BOOL isDirectory = NO;
        if ([[NSFileManager defaultManager] fileExistsAtPath:path isDirectory:&isDirectory] && isDirectory) {
            [sessions addObject:path];
        }
    }
    [sessions sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
        return [right.lastPathComponent compare:left.lastPathComponent];
    }];
    NSUInteger sessionCount = MIN(sessions.count, maxSessions);
    for (NSUInteger sessionIndex = 0; sessionIndex < sessionCount; sessionIndex++) {
        NSString *dir = sessions[sessionIndex];
        NSArray<NSString *> *sessionNames = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil] ?: @[];
        NSMutableArray<NSString *> *sessionPaths = [NSMutableArray array];
        for (NSString *name in sessionNames) {
            if (!SlockDiagnosticsIsLogFileName(name)) {
                continue;
            }
            NSString *path = SlockDiagnosticsCanonicalDescendantFilePath(
                root,
                [dir stringByAppendingPathComponent:name]
            );
            if (!path) {
                continue;
            }
            NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
            if ([attributes[NSFileSize] unsignedLongLongValue] > 0) {
                [sessionPaths addObject:path];
            }
        }
        [sessionPaths sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
            return SlockDiagnosticsComparePathsByMtimeThenPath(left, right);
        }];
        [paths addObjectsFromArray:sessionPaths];
    }
    if (sessionCount < SlockDiagnosticsMaxLaunchSessions) {
        NSMutableArray<NSString *> *flatPaths = [NSMutableArray array];
        for (NSString *name in names) {
            if (!SlockDiagnosticsIsLogFileName(name)) continue;
            NSString *path = SlockDiagnosticsCanonicalDescendantFilePath(
                root,
                [root stringByAppendingPathComponent:name]
            );
            if (!path) continue;
            NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
            if ([attributes[NSFileSize] unsignedLongLongValue] > 0) [flatPaths addObject:path];
        }
        [flatPaths sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
            return SlockDiagnosticsComparePathsByMtimeThenPath(left, right);
        }];
        [paths addObjectsFromArray:flatPaths];
    }
    return paths;
}

static NSArray<NSString *> *SlockDiagnosticsLogFiles(void) {
    return SlockDiagnosticsLogFilesAtRoot(
        SlockDiagnosticsDirectoryPath(),
        SlockDiagnosticsMaxLaunchSessions
    );
}

static BOOL SlockDiagnosticsRemoveItemIfPresent(NSFileManager *fileManager, NSString *path) {
    return ![fileManager fileExistsAtPath:path] || [fileManager removeItemAtPath:path error:nil];
}

static BOOL SlockDiagnosticsRotateLogsIfNeeded(NSString *activePath) {
    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:activePath error:nil];
    if ([attributes[NSFileSize] unsignedLongLongValue] < SlockDiagnosticsLogMaxBytes) {
        return YES;
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
            if (!SlockDiagnosticsRemoveItemIfPresent(fileManager, source)) {
                return NO;
            }
        } else {
            if (!SlockDiagnosticsRemoveItemIfPresent(fileManager, target)) {
                return NO;
            }
            if (![fileManager moveItemAtPath:source toPath:target error:nil]) {
                return NO;
            }
        }
    }
    NSString *rotated = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.1", SlockDiagnosticsLogFileName]];
    if (!SlockDiagnosticsRemoveItemIfPresent(fileManager, rotated)) {
        return NO;
    }
    return [fileManager moveItemAtPath:activePath toPath:rotated error:nil];
}

static BOOL SlockDiagnosticsAppendEntry(NSDictionary *entry, BOOL synchronize) {
    @try {
        NSString *activePath = SlockDiagnosticsActiveLogPath();
        NSString *dir = activePath.stringByDeletingLastPathComponent;
        NSFileManager *fileManager = [NSFileManager defaultManager];
        NSError *directoryError = nil;
        if (![fileManager createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:&directoryError]) {
            return NO;
        }
        if (!SlockDiagnosticsRotateLogsIfNeeded(activePath)) {
            atomic_fetch_add_explicit(&SlockDiagnosticsRotationFailedCount, 1, memory_order_relaxed);
        }
        NSString *jsonLine = SlockDiagnosticsJsonLine(entry);
        if (!jsonLine) {
            return NO;
        }
        NSString *line = [jsonLine stringByAppendingString:@"\n"];
        NSData *data = [line dataUsingEncoding:NSUTF8StringEncoding];
        if (!data) {
            return NO;
        }
        BOOL fileExisted = [fileManager fileExistsAtPath:activePath];
        if (!fileExisted) {
            NSError *writeError = nil;
            if (![data writeToFile:activePath options:NSDataWritingAtomic error:&writeError]) {
                return NO;
            }
            if (!synchronize) {
                return YES;
            }
        }
        NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:activePath];
        if (!handle) {
            return NO;
        }
        [handle seekToEndOfFile];
        if (fileExisted) {
            [handle writeData:data];
        }
        if (synchronize) {
            [handle synchronizeFile];
        }
        [handle closeFile];
        return YES;
    } @catch (__unused NSException *exception) {
        return NO;
    }
}

static NSString *SlockDiagnosticsProcessSessionIdentifier(void) {
    static NSString *identifier;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        identifier = NSUUID.UUID.UUIDString.lowercaseString;
    });
    return identifier;
}

static void SlockDiagnosticsProcessCapture(SlockDiagnosticsCapture *capture) {
    NSString *normalizedTag = [SlockBridgeSanitizedLogString(
        SlockDiagnosticsStringByReplacingInvalidSurrogates(capture.rawTag)
    ) stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    NSString *safeTag = SlockDiagnosticsClippedString(
        normalizedTag.length > 0 ? normalizedTag : SlockBridgeDefaultLogTag,
        48
    );
    NSString *safeMessage = [SlockBridgeSanitizedLogString(
        SlockDiagnosticsStringByReplacingInvalidSurrogates(capture.rawMessage)
    ) stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (safeTag.length == 0 || safeMessage.length == 0) {
        return;
    }
    NSString *normalizedLevel = [[capture.rawLevel lowercaseString]
        stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if ([normalizedLevel isEqualToString:@"fatal"]) {
        normalizedLevel = @"error";
    }
    if (![@[@"debug", @"info", @"warn", @"error"] containsObject:normalizedLevel]) {
        normalizedLevel = @"info";
    }
    BOOL truncated = capture.rawMessageTruncated || safeMessage.length > SlockDiagnosticsMessageMaxChars;
    NSString *finalMessage = SlockDiagnosticsClippedString(safeMessage, SlockDiagnosticsMessageMaxChars);
    NSString *safeThread = SlockDiagnosticsClippedString(
        SlockBridgeSanitizedLogString(capture.threadName),
        SlockDiagnosticsThreadNameMaxChars
    );
    NSString *safeQueue = SlockDiagnosticsClippedString(
        SlockBridgeSanitizedLogString(capture.queueLabel),
        SlockDiagnosticsQueueLabelMaxChars
    );
    uint64_t droppedOrdinary = atomic_load_explicit(&SlockDiagnosticsDroppedOrdinaryCount, memory_order_relaxed);
    uint64_t droppedCritical = atomic_load_explicit(&SlockDiagnosticsDroppedCriticalCount, memory_order_relaxed);
    NSDictionary *entry = @{
        @"ts" : SlockDiagnosticsTimestampForMillis(capture.timestampMillis),
        @"timestampMillis" : @(capture.timestampMillis),
        @"level" : normalizedLevel,
        @"tag" : safeTag,
        @"event" : SlockDiagnosticsEventName(finalMessage),
        @"message" : finalMessage,
        @"thread" : safeThread,
        @"threadId" : @(capture.threadId),
        @"queue" : safeQueue,
        @"mainThread" : @(capture.mainThread),
        @"processId" : @(NSProcessInfo.processInfo.processIdentifier),
        @"processSession" : SlockDiagnosticsProcessSessionIdentifier(),
        @"seq" : @(capture.sequence),
        @"monotonicTicks" : @(capture.monotonicTicks),
        @"dropped" : @(droppedOrdinary + droppedCritical),
        @"droppedOrdinary" : @(droppedOrdinary),
        @"droppedCritical" : @(droppedCritical),
        @"truncated" : @(truncated),
        @"critical" : @(capture.critical)
    };
    SlockDiagnosticsRemember(entry);
    if (capture.emitConsole) {
        NSLog(@"[%@][%@] %@", safeTag, normalizedLevel, finalMessage);
    }
    if (SlockDiagnosticsAppendEntry(entry, capture.critical)) {
        atomic_fetch_add_explicit(&SlockDiagnosticsWrittenCount, 1, memory_order_relaxed);
        if (capture.critical) {
            atomic_fetch_add_explicit(&SlockDiagnosticsCriticalSynchronizedCount, 1, memory_order_relaxed);
        }
    } else {
        atomic_fetch_add_explicit(&SlockDiagnosticsWriteFailedCount, 1, memory_order_relaxed);
    }
}

static uint64_t SlockDiagnosticsRecordWithContext(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
) {
    atomic_fetch_add_explicit(&SlockDiagnosticsAttemptedCount, 1, memory_order_relaxed);
    SlockDiagnosticsCapture *capture = SlockDiagnosticsCreateCapture(
        level,
        tag,
        message,
        timestampMillis,
        emitConsole,
        forceCritical
    );
    if (!capture) {
        atomic_fetch_add_explicit(&SlockDiagnosticsRejectedInvalidCount, 1, memory_order_relaxed);
        return 0;
    }
    if (!os_unfair_lock_trylock(&SlockDiagnosticsAdmissionLock)) {
        atomic_fetch_add_explicit(&SlockDiagnosticsDroppedContentionCount, 1, memory_order_relaxed);
        atomic_fetch_add_explicit(
            capture.critical ? &SlockDiagnosticsDroppedCriticalCount : &SlockDiagnosticsDroppedOrdinaryCount,
            1,
            memory_order_relaxed
        );
        return 0;
    }
    NSUInteger limit = capture.critical
        ? SlockDiagnosticsOutstandingLimit
        : SlockDiagnosticsOrdinaryOutstandingLimit;
    if (SlockDiagnosticsOutstandingCount >= limit) {
        atomic_fetch_add_explicit(
            capture.critical ? &SlockDiagnosticsDroppedCriticalCount : &SlockDiagnosticsDroppedOrdinaryCount,
            1,
            memory_order_relaxed
        );
        os_unfair_lock_unlock(&SlockDiagnosticsAdmissionLock);
        return 0;
    }
    capture.sequence = ++SlockDiagnosticsNextSequence;
    SlockDiagnosticsOutstandingCount++;
    uint64_t maxOutstanding = atomic_load_explicit(&SlockDiagnosticsMaxOutstandingCount, memory_order_relaxed);
    if (SlockDiagnosticsOutstandingCount > maxOutstanding) {
        atomic_store_explicit(
            &SlockDiagnosticsMaxOutstandingCount,
            SlockDiagnosticsOutstandingCount,
            memory_order_relaxed
        );
    }
    atomic_fetch_add_explicit(&SlockDiagnosticsAcceptedCount, 1, memory_order_relaxed);
    if (capture.critical) {
        atomic_fetch_add_explicit(&SlockDiagnosticsAcceptedCriticalCount, 1, memory_order_relaxed);
    }
    dispatch_async(SlockDiagnosticsOwnerQueue(), ^{
        @autoreleasepool {
            @try {
                SlockDiagnosticsProcessCapture(capture);
            } @finally {
                atomic_fetch_add_explicit(&SlockDiagnosticsProcessedCount, 1, memory_order_relaxed);
                os_unfair_lock_lock(&SlockDiagnosticsAdmissionLock);
                if (SlockDiagnosticsOutstandingCount > 0) {
                    SlockDiagnosticsOutstandingCount--;
                }
                os_unfair_lock_unlock(&SlockDiagnosticsAdmissionLock);
            }
        }
    });
    uint64_t sequence = capture.sequence;
    os_unfair_lock_unlock(&SlockDiagnosticsAdmissionLock);
    return sequence;
}

static void SlockDiagnosticsRecord(NSString *level, NSString *tag, NSString *message) {
    SlockDiagnosticsRecordWithContext(level, tag, message, 0, NO, NO);
}

void SlockIosDiagnosticsRecord(NSString *level, NSString *tag, NSString *message) {
    SlockDiagnosticsRecordWithContext(level, tag, message, 0, NO, NO);
}

void SlockIosDiagnosticsRecordWithContext(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
) {
    SlockDiagnosticsRecordWithContext(level, tag, message, timestampMillis, emitConsole, forceCritical);
}

void SlockIosDiagnosticsRecordCriticalSynchronously(NSString *tag, NSString *message) {
    NSString *boundedTag = SlockDiagnosticsClippedCopy(
        tag ?: SlockBridgeDefaultLogTag,
        SlockDiagnosticsRawTagMaxChars
    );
    NSString *boundedMessage = SlockDiagnosticsClippedCopy(
        message ?: @"",
        SlockDiagnosticsRawMessageMaxChars
    );
    NSString *safeTag = SlockDiagnosticsClippedString(
        [SlockBridgeSanitizedLogString(
            SlockDiagnosticsStringByReplacingInvalidSurrogates(boundedTag)
        )
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet],
        48
    );
    NSString *safeMessage = SlockDiagnosticsClippedString(
        [SlockBridgeSanitizedLogString(SlockDiagnosticsStringByReplacingInvalidSurrogates(boundedMessage))
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet],
        SlockDiagnosticsMessageMaxChars
    );
    if (safeMessage.length == 0) {
        return;
    }
    if (safeTag.length == 0) {
        safeTag = SlockBridgeDefaultLogTag;
    }
    NSLog(@"[%@][error] %@", safeTag, safeMessage);
    SlockDiagnosticsRecordWithContext(@"error", safeTag, safeMessage, 0, NO, YES);
}

void SlockIosDiagnosticsRecordUnhandledException(
    NSString *pageName,
    NSInteger mode,
    NSString *reason,
    NSString *stack
) {
    NSString *boundedPage = SlockDiagnosticsClippedCopy(
        pageName ?: @"unknown",
        SlockDiagnosticsExceptionPageMaxChars
    );
    NSString *boundedReason = SlockDiagnosticsClippedCopy(
        reason ?: @"KuiklyUnhandledException",
        SlockDiagnosticsExceptionReasonMaxChars
    );
    NSString *boundedStack = SlockDiagnosticsClippedCopy(
        stack ?: @"",
        SlockDiagnosticsExceptionStackMaxChars
    );
    NSString *message = [NSString stringWithFormat:@"kuikly_unhandled_exception page=%@ mode=%ld reason=%@ stack=%@",
        boundedPage,
        (long)mode,
        boundedReason,
        boundedStack];
    SlockIosDiagnosticsRecordCriticalSynchronously(@"SlockKuikly", message);
}

static NSString *SlockDiagnosticsExportRecentJsonl(NSUInteger maxChars) {
    NSMutableArray<NSString *> *lines = [NSMutableArray array];
    for (NSDictionary *entry in SlockDiagnosticsSnapshotMemoryEntries()) {
        NSString *line = SlockDiagnosticsJsonLine(entry);
        if (line) {
            [lines addObject:line];
        }
    }
    NSString *text = [lines componentsJoinedByString:@"\n"];
    if (text.length > maxChars) {
        return SlockDiagnosticsValidSuffixString(text, maxChars);
    }
    return text;
}

static NSString *SlockDiagnosticsReadTail(NSString *path, NSUInteger maxChars) {
    NSData *data = [NSData dataWithContentsOfFile:path] ?: [NSData data];
    NSString *text = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"";
    text = SlockBridgeSanitizedLogString(text);
    if (text.length > maxChars) {
        return SlockDiagnosticsValidSuffixString(text, maxChars);
    }
    return text;
}

static NSString *SlockDiagnosticsExportRecentText(NSUInteger maxChars) {
    NSString *result = SlockDiagnosticsPerformOwnerOperation(^id{
        NSArray<NSString *> *files = SlockDiagnosticsLogFiles();
        NSMutableString *text = [NSMutableString string];
        [text appendString:@"App-owned recent diagnostics\n"];
        [text appendFormat:@"Files: %lu\n", (unsigned long)files.count];
        [text appendFormat:
            @"Queue: attempted=%llu accepted=%llu acceptedCritical=%llu rejectedInvalid=%llu processed=%llu written=%llu criticalSynchronized=%llu writeFailed=%llu rotationFailed=%llu droppedOrdinary=%llu droppedCritical=%llu droppedContention=%llu maxDepth=%lu\n",
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsAttemptedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsAcceptedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsAcceptedCriticalCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsRejectedInvalidCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsProcessedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsWrittenCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsCriticalSynchronizedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsWriteFailedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsRotationFailedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsDroppedOrdinaryCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsDroppedCriticalCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&SlockDiagnosticsDroppedContentionCount, memory_order_relaxed),
            (unsigned long)atomic_load_explicit(&SlockDiagnosticsMaxOutstandingCount, memory_order_relaxed)
        ];
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
            NSString *sessionName = path.stringByDeletingLastPathComponent.lastPathComponent;
            NSString *displayName = [sessionName hasPrefix:@"launch-"]
                ? [NSString stringWithFormat:@"%@/%@", sessionName, path.lastPathComponent]
                : [NSString stringWithFormat:@"flat/%@", path.lastPathComponent];
            [text appendFormat:@"\n### %@ (%llu bytes)\n", displayName, [attributes[NSFileSize] unsignedLongLongValue]];
            [text appendString:SlockDiagnosticsReadTail(path, MAX(fileBudget / MAX(fileCount, 1), 1000))];
        }
        if (text.length > maxChars) {
            return SlockDiagnosticsValidSuffixString(text, maxChars);
        }
        return text;
    });
    return result ?: @"";
}

static void SlockDiagnosticsClear(void) {
    SlockDiagnosticsPerformOwnerOperation(^id{
        NSMutableArray *slots = SlockDiagnosticsMemorySlots();
        for (NSUInteger index = 0; index < SlockDiagnosticsMemoryEntryLimit; index++) {
            [slots replaceObjectAtIndex:index withObject:NSNull.null];
        }
        SlockDiagnosticsMemoryStart = 0;
        SlockDiagnosticsMemoryCount = 0;
        NSString *dir = SlockDiagnosticsDirectoryPath();
        NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil] ?: @[];
        for (NSString *name in names) {
            if (SlockDiagnosticsIsLogFileName(name) || [name hasPrefix:@"launch-"] ||
                [name hasPrefix:SlockDiagnosticsFeedbackDirName]) {
                [[NSFileManager defaultManager] removeItemAtPath:[dir stringByAppendingPathComponent:name] error:nil];
            }
        }
        return NSNull.null;
    });
}

static NSString *SlockDiagnosticsBuildExportText(NSDictionary *about) {
    NSMutableString *text = [NSMutableString string];
    [text appendString:@"Raft diagnostics\n\nEnvironment\n"];
    NSArray<NSDictionary *> *rows = @[
        @{ @"key": @"appName", @"label": @"App" },
        @{ @"key": @"versionLabel", @"label": @"Version" },
        @{ @"key": @"versionCodeLabel", @"label": @"Version code" },
        @{ @"key": @"buildLabel", @"label": @"Build" },
        @{ @"key": @"sourceLabel", @"label": @"Source" },
        @{ @"key": @"packageName", @"label": @"Package" },
        @{ @"key": @"platformLabel", @"label": @"Platform" },
        @{ @"key": @"sdkLabel", @"label": @"SDK" },
        @{ @"key": @"deviceLabel", @"label": @"Device" },
        @{ @"key": @"modelLabel", @"label": @"Model" },
        @{ @"key": @"installTimeLabel", @"label": @"Install time" },
        @{ @"key": @"screenLabel", @"label": @"Screen" },
        @{ @"key": @"densityLabel", @"label": @"Density" },
        @{ @"key": @"fontScaleLabel", @"label": @"Font scale" },
        @{ @"key": @"sessionStatusLabel", @"label": @"Session" },
        @{ @"key": @"signedInUserLabel", @"label": @"Signed in user" },
        @{ @"key": @"selectedServerLabel", @"label": @"Selected server" },
        @{ @"key": @"socketStatusLabel", @"label": @"Socket" },
        @{ @"key": @"diagnosticsReason", @"label": @"Diagnostics reason" },
        @{ @"key": @"crashAtMillis", @"label": @"Crash at millis" },
        @{ @"key": @"crashAt", @"label": @"Crash at" }
    ];
    for (NSDictionary *row in rows) {
        NSString *key = row[@"key"];
        NSString *value = SlockBridgeString(about[key]);
        [text appendFormat:@"%@: %@\n", row[@"label"], value.length > 0 ? value : @"--"];
    }
    [text appendString:@"\niOS diagnostics\n"];
    [text appendString:SlockDiagnosticsExportRecentText(SlockDiagnosticsRecentMaxChars)];
    return text;
}

static NSString *SlockDiagnosticsEnvironmentSectionFromExportText(NSString *exportText) {
    NSRange environmentRange = [exportText rangeOfString:@"Environment\n"];
    if (environmentRange.location == NSNotFound) {
        return @"";
    }
    NSUInteger sectionStart = environmentRange.location + environmentRange.length;
    NSRange diagnosticsRange = [exportText rangeOfString:@"\niOS diagnostics" options:0 range:NSMakeRange(sectionStart, exportText.length - sectionStart)];
    NSUInteger sectionEnd = diagnosticsRange.location == NSNotFound ? exportText.length : diagnosticsRange.location;
    NSString *section = [exportText substringWithRange:NSMakeRange(sectionStart, sectionEnd - sectionStart)];
    return [section stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
}

static NSString *SlockDiagnosticsFeedbackDirectoryPath(void) {
    return [SlockDiagnosticsDirectoryPath() stringByAppendingPathComponent:SlockDiagnosticsFeedbackDirName];
}

static id SlockDiagnosticsSanitizedJsonValue(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return SlockBridgeSanitizedLogString((NSString *)value);
    }
    if ([value isKindOfClass:NSArray.class]) {
        NSMutableArray *sanitized = [NSMutableArray arrayWithCapacity:[(NSArray *)value count]];
        for (id item in (NSArray *)value) {
            [sanitized addObject:SlockDiagnosticsSanitizedJsonValue(item) ?: NSNull.null];
        }
        return sanitized;
    }
    if ([value isKindOfClass:NSDictionary.class]) {
        NSMutableDictionary *sanitized = [NSMutableDictionary dictionaryWithCapacity:[(NSDictionary *)value count]];
        [(NSDictionary *)value enumerateKeysAndObjectsUsingBlock:^(id key, id item, BOOL *stop) {
            (void)stop;
            NSString *stringKey = [key isKindOfClass:NSString.class] ? key : [key description];
            if (SlockDiagnosticsIsCredentialKey(stringKey)) {
                sanitized[stringKey] = @"<redacted>";
            } else {
                sanitized[stringKey] = SlockDiagnosticsSanitizedJsonValue(item) ?: NSNull.null;
            }
        }];
        return sanitized;
    }
    return value ?: NSNull.null;
}

static BOOL SlockDiagnosticsWriteSanitizedSnapshotLog(NSString *sourcePath, NSString *destinationPath) {
    NSData *sourceData = [NSData dataWithContentsOfFile:sourcePath];
    if (!sourceData) {
        return NO;
    }
    NSString *sourceText = [[NSString alloc] initWithData:sourceData encoding:NSUTF8StringEncoding];
    if (!sourceText) {
        return NO;
    }
    NSMutableArray<NSString *> *sanitizedLines = [NSMutableArray array];
    __block NSUInteger omittedLineCount = 0;
    [sourceText enumerateLinesUsingBlock:^(NSString *line, BOOL *stop) {
        (void)stop;
        NSData *lineData = [line dataUsingEncoding:NSUTF8StringEncoding];
        id object = lineData ? [NSJSONSerialization JSONObjectWithData:lineData options:0 error:nil] : nil;
        id sanitizedObject = SlockDiagnosticsSanitizedJsonValue(object);
        if (!sanitizedObject || ![NSJSONSerialization isValidJSONObject:sanitizedObject]) {
            omittedLineCount++;
            return;
        }
        NSData *sanitizedLineData = [NSJSONSerialization dataWithJSONObject:sanitizedObject options:0 error:nil];
        NSString *sanitizedLine = sanitizedLineData
            ? [[NSString alloc] initWithData:sanitizedLineData encoding:NSUTF8StringEncoding]
            : nil;
        if (sanitizedLine) {
            [sanitizedLines addObject:sanitizedLine];
        } else {
            omittedLineCount++;
        }
    }];
    if (omittedLineCount > 0) {
        NSString *omissionLine = SlockDiagnosticsJsonLine(@{
            @"event" : @"snapshot_invalid_jsonl_omitted",
            @"count" : @(omittedLineCount)
        });
        if (omissionLine) {
            [sanitizedLines addObject:omissionLine];
        }
    }
    NSString *sanitizedText = sanitizedLines.count > 0
        ? [[sanitizedLines componentsJoinedByString:@"\n"] stringByAppendingString:@"\n"]
        : @"";
    NSData *sanitizedData = [sanitizedText dataUsingEncoding:NSUTF8StringEncoding];
    if (!sanitizedData) {
        return NO;
    }
    return [sanitizedData writeToFile:destinationPath atomically:YES];
}

static void SlockDiagnosticsPruneFeedbackFiles(NSString *dir) {
    NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil] ?: @[];
    NSMutableArray<NSString *> *paths = [NSMutableArray array];
    for (NSString *name in names) {
        if (![name hasPrefix:SlockDiagnosticsFeedbackFilePrefix]) {
            continue;
        }
        NSString *path = [dir stringByAppendingPathComponent:name];
        BOOL isDirectory = NO;
        if ([[NSFileManager defaultManager] fileExistsAtPath:path isDirectory:&isDirectory] && isDirectory) {
            [paths addObject:path];
        }
    }
    [paths sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
        NSDate *leftDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:left error:nil] fileModificationDate] ?: NSDate.distantPast;
        NSDate *rightDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:right error:nil] fileModificationDate] ?: NSDate.distantPast;
        return [rightDate compare:leftDate];
    }];
    NSDate *now = NSDate.date;
    for (NSUInteger index = SlockDiagnosticsMinimumRetainedSnapshotCount; index < paths.count; index++) {
        NSString *path = paths[index];
        NSDate *modifiedAt = [[[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil] fileModificationDate]
            ?: NSDate.distantPast;
        if ([now timeIntervalSinceDate:modifiedAt] >= SlockDiagnosticsFeedbackPruneMinimumAgeSeconds) {
            [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        }
    }
}

static NSDictionary *SlockDiagnosticsPrepareAttachmentFiles(NSDictionary *about, NSString *reason, BOOL includeBodyBase64) {
    NSDictionary *result = SlockDiagnosticsPerformOwnerOperation(^id{
        NSString *summaryText = SlockDiagnosticsStringByReplacingInvalidSurrogates(
            SlockBridgeSanitizedLogString(SlockDiagnosticsBuildExportText(about ?: @{}))
        );
        NSString *safeReason = SlockBridgeSanitizedLogString(reason ?: @"diagnostics").lowercaseString;
        NSData *summaryData = [summaryText dataUsingEncoding:NSUTF8StringEncoding];
        if (!summaryData) {
            return @{
                @"status" : @"error",
                @"code" : @"diagnostics_summary_encoding_failed",
                @"message" : @"Unable to encode diagnostics summary."
            };
        }
        NSString *dir = SlockDiagnosticsFeedbackDirectoryPath();
        NSFileManager *fileManager = [NSFileManager defaultManager];
        [fileManager createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
        long long timestampMs = SlockDiagnosticsNowMillis();
        NSString *snapshotIdentifier = NSUUID.UUID.UUIDString.lowercaseString;
        NSString *snapshotDir =
            [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@%lld-%@",
                SlockDiagnosticsFeedbackFilePrefix,
                timestampMs,
                snapshotIdentifier]];
        if (![fileManager createDirectoryAtPath:snapshotDir
                     withIntermediateDirectories:YES
                                      attributes:nil
                                           error:nil]) {
            return @{
                @"status" : @"error",
                @"code" : @"diagnostics_snapshot_directory_failed",
                @"message" : @"Unable to create diagnostics snapshot directory."
            };
        }
        NSString *fileName = SlockDiagnosticsSummaryFileName;
        NSString *path = [snapshotDir stringByAppendingPathComponent:fileName];
        if (![summaryData writeToFile:path atomically:YES]) {
            [fileManager removeItemAtPath:snapshotDir error:nil];
            return @{
                @"status" : @"error",
                @"code" : @"diagnostics_summary_write_failed",
                @"message" : @"Unable to write diagnostics summary."
            };
        }
        NSMutableArray<NSString *> *attachmentPaths = [NSMutableArray arrayWithObject:path];
        NSMutableSet<NSString *> *reservedSnapshotPaths = [NSMutableSet setWithObject:path];
        NSArray<NSString *> *files = SlockDiagnosticsLogFiles();
        NSUInteger fileCount = MIN(files.count, SlockDiagnosticsAttachmentMaxLogFiles);
        for (NSUInteger index = 0; index < fileCount; index++) {
            NSString *logPath = files[index];
            if (![fileManager isReadableFileAtPath:logPath]) {
                continue;
            }
            NSString *sessionName = logPath.stringByDeletingLastPathComponent.lastPathComponent;
            NSString *snapshotLogDir = [sessionName hasPrefix:@"launch-"]
                ? [snapshotDir stringByAppendingPathComponent:sessionName]
                : [snapshotDir stringByAppendingPathComponent:@"flat"];
            [fileManager createDirectoryAtPath:snapshotLogDir withIntermediateDirectories:YES attributes:nil error:nil];
            NSString *snapshotLogPath = SlockDiagnosticsUniqueSnapshotPath(
                snapshotLogDir,
                logPath.lastPathComponent,
                reservedSnapshotPaths
            );
            if (SlockDiagnosticsWriteSanitizedSnapshotLog(logPath, snapshotLogPath)) {
                [attachmentPaths addObject:snapshotLogPath];
            } else {
                [fileManager removeItemAtPath:snapshotLogPath error:nil];
            }
        }
        SlockDiagnosticsPruneFeedbackFiles(dir);
        NSMutableDictionary *prepared = [@{
            @"status" : @"ok",
            @"reason" : safeReason.length > 0 ? safeReason : @"diagnostics",
            @"fileName" : fileName,
            @"mimeType" : @"text/plain",
            @"sizeBytes" : @(summaryData.length),
            @"filePath" : path,
            @"attachmentPaths" : [attachmentPaths copy],
            @"logFileCount" : @(attachmentPaths.count > 0 ? attachmentPaths.count - 1 : 0)
        } mutableCopy];
        if (includeBodyBase64) {
            prepared[@"bodyBase64"] = [summaryData base64EncodedStringWithOptions:0];
        }
        return prepared;
    });
    if ([result[@"status"] isEqualToString:@"ok"]) {
        NSArray *paths = [result[@"attachmentPaths"] isKindOfClass:NSArray.class] ? result[@"attachmentPaths"] : @[];
        SlockDiagnosticsRecord(
            @"info",
            @"SlockDiagnostics",
            [NSString stringWithFormat:@"diagnostics_attachments_prepared reason=%@ summary=%@ bytes=%@ paths=%lu",
                result[@"reason"] ?: @"diagnostics",
                result[@"fileName"] ?: SlockDiagnosticsSummaryFileName,
                result[@"sizeBytes"] ?: @0,
                (unsigned long)paths.count]
        );
    }
    return result;
}


static NSDictionary *SlockDiagnosticsCrashAbout(long long crashAtMillis) {
    NSMutableDictionary *about = [SlockDiagnosticsCurrentEnvironmentAbout() mutableCopy];
    [about addEntriesFromDictionary:@{
        @"diagnosticsReason" : @"crash",
        @"crashAtMillis" : @(crashAtMillis)
    }];
    if (crashAtMillis > 0) {
        NSDate *date = [NSDate dateWithTimeIntervalSince1970:((NSTimeInterval)crashAtMillis / 1000.0)];
        if (@available(iOS 10.0, *)) {
            NSISO8601DateFormatter *formatter = [[NSISO8601DateFormatter alloc] init];
            formatter.formatOptions = NSISO8601DateFormatWithInternetDateTime | NSISO8601DateFormatWithFractionalSeconds;
            about[@"crashAt"] = [formatter stringFromDate:date];
        } else {
            about[@"crashAt"] = date.description;
        }
    }
    return about;
}

NSArray<NSString *> *SlockIosPrepareDiagnosticsAttachmentPaths(NSString *reason) {
    NSMutableDictionary *about = [SlockDiagnosticsCurrentEnvironmentAbout() mutableCopy];
    about[@"diagnosticsReason"] = reason ?: @"crash";
    NSDictionary *attachments = SlockDiagnosticsPrepareAttachmentFiles(about, reason ?: @"crash", NO);
    NSArray *paths = [attachments[@"attachmentPaths"] isKindOfClass:NSArray.class] ? attachments[@"attachmentPaths"] : @[];
    return paths;
}

NSArray<NSString *> *SlockIosPrepareDiagnosticsAttachmentPathsForCrash(int64_t crashAtMillis) {
    NSString *reason = crashAtMillis > 0
        ? [NSString stringWithFormat:@"crash-%lld", (long long)crashAtMillis]
        : @"crash";
    NSDictionary *attachments = SlockDiagnosticsPrepareAttachmentFiles(SlockDiagnosticsCrashAbout((long long)crashAtMillis), reason, NO);
    NSArray *paths = [attachments[@"attachmentPaths"] isKindOfClass:NSArray.class] ? attachments[@"attachmentPaths"] : @[];
    return paths;
}

NSString *SlockIosPrepareDiagnosticsCrashAttachmentPath(void) {
    return SlockIosPrepareDiagnosticsAttachmentPaths(@"crash").firstObject;
}

static void SlockIosRegisterHandsDiagnosticsProvider(void) {
    Class crashReporter = NSClassFromString(@"HandsCrashReporter");
    SEL selector = NSSelectorFromString(@"setDiagnosticsProvider:");
    if (!crashReporter || ![crashReporter respondsToSelector:selector]) {
        SlockDiagnosticsRecord(@"warn", @"SlockDiagnostics", @"quiver_diagnostics_provider_unavailable");
        return;
    }
    NSArray<NSString *> *_Nullable (^provider)(int64_t) = ^NSArray<NSString *> *_Nullable(int64_t crashAtMillis) {
        return SlockIosPrepareDiagnosticsAttachmentPathsForCrash(crashAtMillis);
    };
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
    [crashReporter performSelector:selector withObject:[provider copy]];
#pragma clang diagnostic pop
    SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"quiver_diagnostics_provider_registered crashAtMillis=true");
}

void SlockIosInstallHandsReporting(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        // App-owned Hands reporting config (client key is DSN-class: it
        // identifies the app; rotate from the Hands console if leaked).
        SlockIosRegisterHandsDiagnosticsProvider();
        [Hands installWithConfig:[HandsConfig configWithBaseUrl:@"https://hands.build"
                                                          appSlug:@"raft-ios"
                                                          channel:@"main"
                                                        clientKey:@"qk_2d6ac57a6532bc7ff1d3183b43bda99e2e732a99"]];
    });
}

static NSString *SlockAttachmentExportFileName(NSString *value) {
    NSString *source = SlockBridgeString(value).lastPathComponent;
    NSCharacterSet *allowed = [NSCharacterSet characterSetWithCharactersInString:@"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._- "];
    NSMutableString *result = [NSMutableString string];
    for (NSUInteger index = 0; index < source.length; index++) {
        unichar character = [source characterAtIndex:index];
        if ([allowed characterIsMember:character]) {
            [result appendFormat:@"%C", character];
        } else {
            [result appendString:@"_"];
        }
    }
    NSString *trimmed = [result stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    return trimmed.length > 0 ? trimmed : @"attachment";
}

static UTType *SlockPreferredPickedMediaType(NSItemProvider *provider) {
    UTType *preferred = nil;
    NSInteger preferredScore = NSIntegerMin;
    for (NSString *identifier in provider.registeredTypeIdentifiers) {
        UTType *type = [UTType typeWithIdentifier:identifier];
        BOOL isImage = [type conformsToType:UTTypeImage];
        BOOL isVideo = [type conformsToType:UTTypeMovie];
        if (!isImage && !isVideo) {
            continue;
        }
        BOOL isGeneric =
            [type isEqual:UTTypeImage] ||
            [type isEqual:UTTypeMovie] ||
            [type isEqual:UTTypeVideo] ||
            [type isEqual:UTTypeAudiovisualContent];
        NSInteger score = isGeneric ? 0 : 4;
        if (type.preferredMIMEType.length > 0) {
            score += 2;
        }
        if (type.preferredFilenameExtension.length > 0) {
            score += 2;
        }
        if (!preferred || score > preferredScore) {
            preferred = type;
            preferredScore = score;
        }
    }
    return preferred;
}

static NSString *SlockPickedAttachmentDisplayName(NSString *suggestedName,
                                                   NSURL *sourceURL,
                                                   NSString *mimeType) {
    NSString *displayName =
        SlockAttachmentExportFileName(suggestedName.length > 0 ? suggestedName : sourceURL.lastPathComponent);
    if (displayName.pathExtension.length > 0) {
        return displayName;
    }
    NSString *extension = sourceURL.pathExtension;
    if (extension.length == 0 && mimeType.length > 0) {
        extension = [UTType typeWithMIMEType:mimeType].preferredFilenameExtension;
    }
    if (extension.length > 0) {
        displayName = [displayName stringByAppendingPathExtension:extension];
    }
    return displayName;
}

@interface HRBridgeModule () <PHPickerViewControllerDelegate, UIDocumentPickerDelegate, SharedAttachmentDownloadObserver>

@property(nonatomic, copy, nullable) KuiklyRenderCallback slock_attachmentExportCallback;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportRequestId;
@property(nonatomic, strong, nullable) NSURL *slock_attachmentExportURL;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportFileName;
@property(nonatomic, strong, nullable) NSNumber *slock_attachmentExportSizeBytes;
@property(nonatomic) BOOL slock_attachmentExportTrackProgress;
@property(nonatomic, copy, nullable) KuiklyRenderCallback slock_attachmentPickerCallback;
@property(nonatomic, copy, nullable) NSString *slock_attachmentPickerChannelId;

- (void)slock_finishAttachmentExport:(NSDictionary *)result;
- (void)slock_finishAttachmentPicker:(NSDictionary *)result;
- (void)slock_presentPhotoPicker;
- (void)slock_presentDocumentPicker;
- (void)slock_processPhotoPickerResults:(NSArray<PHPickerResult *> *)results;
- (void)slock_processDocumentPickerURLs:(NSArray<NSURL *> *)urls;
- (void)slock_finishAttachmentPickerFiles:(NSArray<NSDictionary *> *)files
                                    errors:(NSArray<NSDictionary *> *)errors;
- (nullable NSDictionary *)slock_cachePickedAttachmentURL:(NSURL *)sourceURL
                                            suggestedName:(nullable NSString *)suggestedName
                                                 mimeType:(nullable NSString *)mimeType
                                                    error:(NSError * _Nullable * _Nullable)error;

@end

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
    NSString *content = SlockBridgeRawString(params[@"content"]);
    if (content.length == 0) {
        return;
    }
    NSString *level = SlockBridgeRawString(params[@"level"]);
    if (level.length == 0) {
        level = @"info";
    }
    NSString *tag = SlockBridgeRawString(params[@"tag"]);
    if (tag.length == 0) {
        tag = SlockBridgeDefaultLogTag;
    }
    SlockDiagnosticsRecordWithContext(level, tag, content, 0, YES, NO);
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
    NSString *action = SlockBridgeString(params[@"action"]);
    if ([action isEqualToString:@"getNotificationPermissionStatus"]) {
        [self slock_getNotificationPermissionStatusWithCallback:callback];
        return;
    }
    if ([action isEqualToString:@"requestNotificationPermission"]) {
        [self slock_requestNotificationPermissionWithCallback:callback];
        return;
    }
    if ([action isEqualToString:@"openNotificationSettings"]) {
        [self slock_openNotificationSettingsWithCallback:callback];
        return;
    }
    if ([action isEqualToString:@"mobileOAuthLink"]) {
        NSString *provider = SlockBridgeString(params[@"providerId"]);
        [[SlockIosMobileOAuthHost shared] startWithProvider:provider
                                                      mode:@"link"
                                                completion:^(NSDictionary *result) {
            callback(result);
        }];
        return;
    }
    callback([self slock_settingsCapabilityResultForAction:action params:params]);
}

- (void)threadAttachmentUpload:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    if (self.slock_attachmentPickerCallback || self.slock_attachmentExportCallback) {
        callback(@{
            @"code" : @409,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_picker_busy",
            @"errorBodyText" : @"Attachment picker already open"
        });
        return;
    }
    self.slock_attachmentPickerCallback = callback;
    self.slock_attachmentPickerChannelId = SlockBridgeString(params[@"channelId"]);
    BOOL imageOnly = SlockBridgeBool(params[@"imageOnly"]);
    SlockDiagnosticsRecord(
        @"info",
        @"SlockKuiklyAttachment",
        [NSString stringWithFormat:@"picker_start imageOnly=%@ channelIdPresent=%@",
                                   imageOnly ? @"true" : @"false",
                                   self.slock_attachmentPickerChannelId.length > 0 ? @"true" : @"false"]
    );
    if (!imageOnly) {
        [self slock_presentDocumentPicker];
        return;
    }

    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelReadWrite];
    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) {
        [self slock_presentPhotoPicker];
        return;
    }
    if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
        [self slock_finishAttachmentPicker:@{
            @"code" : @403,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_permission_denied",
            @"errorBodyText" : @"Photo library access is denied"
        }];
        return;
    }
    __weak typeof(self) weakSelf = self;
    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelReadWrite handler:^(PHAuthorizationStatus authorizationStatus) {
        SlockBridgeRunOnMain(^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf || !strongSelf.slock_attachmentPickerCallback) {
                return;
            }
            if (authorizationStatus == PHAuthorizationStatusAuthorized || authorizationStatus == PHAuthorizationStatusLimited) {
                [strongSelf slock_presentPhotoPicker];
            } else {
                [strongSelf slock_finishAttachmentPicker:@{
                    @"code" : @403,
                    @"bodyText" : @"",
                    @"errorCode" : @"attachment_permission_denied",
                    @"errorBodyText" : @"Photo library access is denied"
                }];
            }
        });
    }];
}

- (void)socialAuth:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    NSString *provider = SlockBridgeString(params[@"provider"]);
    [[SlockIosMobileOAuthHost shared] startWithProvider:provider
                                                  mode:@"login"
                                            completion:^(NSDictionary *result) {
        callback(result);
    }];
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

- (void)saveAttachment:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    if (self.slock_attachmentExportCallback) {
        callback(@{
            @"status" : @"failed",
            @"message" : @"Another file save is already active"
        });
        return;
    }
    NSDictionary *params = SlockBridgeParams(args);
    NSString *urlText = SlockBridgeString(params[@"url"]);
    NSURL *url = [NSURL URLWithString:urlText];
    if (!url || url.scheme.length == 0) {
        callback(@{
            @"status" : @"failed",
            @"message" : @"Unable to download attachment"
        });
        return;
    }
    NSString *fileName = SlockAttachmentExportFileName(SlockBridgeString(params[@"fileName"]));
    NSString *requestId = NSUUID.UUID.UUIDString;
    NSString *downloadId = SlockBridgeString(params[@"downloadId"]);
    if (downloadId.length == 0) downloadId = requestId;
    self.slock_attachmentExportCallback = callback;
    self.slock_attachmentExportRequestId = @"pending";
    self.slock_attachmentExportFileName = fileName;
    self.slock_attachmentExportTrackProgress = SlockBridgeBool(params[@"trackProgress"]);
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSURL *root = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:@"SlockAttachmentExports"] isDirectory:YES];
    NSURL *directory = [root URLByAppendingPathComponent:requestId isDirectory:YES];
    NSURL *targetURL = [directory URLByAppendingPathComponent:fileName isDirectory:NO];
    NSError *fileError = nil;
    [fileManager createDirectoryAtURL:directory withIntermediateDirectories:YES attributes:nil error:&fileError];
    if (fileError) {
        [self slock_finishAttachmentExport:@{
            @"status" : @"storage_failed",
            @"message" : @"Unable to prepare attachment for export"
        }];
        return;
    }
    self.slock_attachmentExportURL = targetURL;
    NSString *attemptToken =
        [SharedAttachmentDownloadCoordinator.shared startDownloadId:downloadId
                                                          targetKey:targetURL.path
                                                                url:urlText
                                                         targetPath:targetURL.path
                                                           observer:self];
    if (self.slock_attachmentExportCallback) {
        self.slock_attachmentExportRequestId = attemptToken;
        if (attemptToken.length == 0) {
            [self slock_finishAttachmentExport:@{
                @"status" : @"failed",
                @"message" : @"Unable to start attachment download"
            }];
        }
    }
}

- (void)onProgressProgress:(SharedAttachmentDownloadProgress *)progress {
    __weak typeof(self) weakSelf = self;
    SlockBridgeRunOnMain(^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf.slock_attachmentExportTrackProgress || !strongSelf.slock_attachmentExportCallback ||
            (strongSelf.slock_attachmentExportRequestId.length > 0 &&
             ![strongSelf.slock_attachmentExportRequestId isEqualToString:@"pending"] &&
             ![strongSelf.slock_attachmentExportRequestId isEqualToString:progress.attemptToken])) {
            return;
        }
        NSMutableDictionary *payload = [@{
            @"status" : @"progress",
            @"downloadId" : progress.downloadId ?: @"",
            @"attemptToken" : progress.attemptToken ?: @"",
            @"sequence" : @(progress.sequence),
            @"phase" : progress.phase ?: @"",
            @"downloadedBytes" : @(progress.downloadedBytes)
        } mutableCopy];
        if (progress.totalBytes) payload[@"totalBytes"] = progress.totalBytes;
        if (progress.percent) payload[@"percent"] = progress.percent;
        if (progress.bytesPerSecond) payload[@"bytesPerSecond"] = progress.bytesPerSecond;
        strongSelf.slock_attachmentExportCallback(payload);
    });
}

- (void)onCompleteResult:(SharedAttachmentDownloadResult *)result {
    __weak typeof(self) weakSelf = self;
    SlockBridgeRunOnMain(^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf || !strongSelf.slock_attachmentExportCallback ||
            (strongSelf.slock_attachmentExportRequestId.length > 0 &&
             ![strongSelf.slock_attachmentExportRequestId isEqualToString:@"pending"] &&
             ![strongSelf.slock_attachmentExportRequestId isEqualToString:result.attemptToken])) {
            return;
        }
        if (![result.status isEqualToString:@"complete"]) {
            [strongSelf slock_finishAttachmentExport:@{
                @"status" : result.status ?: @"failed",
                @"message" : result.message ?: @"Unable to download attachment"
            }];
            return;
        }
        NSURL *targetURL = strongSelf.slock_attachmentExportURL;
        if (!targetURL) {
            [strongSelf slock_finishAttachmentExport:@{
                @"status" : @"storage_failed",
                @"message" : @"Unable to prepare attachment for export"
            }];
            return;
        }
        UIViewController *controller = [strongSelf slock_currentViewController];
        if (!controller) {
            [strongSelf slock_finishAttachmentExport:@{
                @"status" : @"storage_failed",
                @"message" : @"Unable to open Files"
            }];
            return;
        }
        strongSelf.slock_attachmentExportSizeBytes = @(result.downloadedBytes);
        UIDocumentPickerViewController *pickerController =
            [[UIDocumentPickerViewController alloc] initForExportingURLs:@[targetURL] asCopy:YES];
        pickerController.delegate = strongSelf;
        [controller presentViewController:pickerController animated:YES completion:nil];
    });
}

- (void)documentPicker:(UIDocumentPickerViewController *)controller didPickDocumentsAtURLs:(NSArray<NSURL *> *)urls {
    if (self.slock_attachmentPickerCallback) {
        [self slock_processDocumentPickerURLs:urls];
        return;
    }
    NSURL *savedURL = urls.firstObject;
    [self slock_finishAttachmentExport:@{
        @"status" : @"saved",
        @"message" : @"Saved to Files",
        @"uri" : savedURL.absoluteString ?: @"",
        @"fileName" : self.slock_attachmentExportFileName ?: @"attachment",
        @"cached" : @NO,
        @"sizeBytes" : self.slock_attachmentExportSizeBytes ?: @0
    }];
}

- (void)documentPickerWasCancelled:(UIDocumentPickerViewController *)controller {
    if (self.slock_attachmentPickerCallback) {
        [self slock_finishAttachmentPicker:@{ @"code" : @204, @"bodyText" : @"" }];
        return;
    }
    [self slock_finishAttachmentExport:@{ @"status" : @"cancelled" }];
}

- (void)slock_presentPhotoPicker {
    SlockBridgeRunOnMain(^{
        UIViewController *controller = [self slock_currentViewController];
        if (!controller) {
            [self slock_finishAttachmentPicker:@{
                @"code" : @0,
                @"bodyText" : @"",
                @"errorCode" : @"attachment_picker_unavailable",
                @"errorBodyText" : @"Attachment picker is unavailable"
            }];
            return;
        }
        PHPickerConfiguration *configuration =
            [[PHPickerConfiguration alloc] initWithPhotoLibrary:PHPhotoLibrary.sharedPhotoLibrary];
        configuration.filter = [PHPickerFilter anyFilterMatchingSubfilters:@[
            PHPickerFilter.imagesFilter,
            PHPickerFilter.videosFilter
        ]];
        configuration.selectionLimit = 0;
        configuration.preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCurrent;
        PHPickerViewController *picker = [[PHPickerViewController alloc] initWithConfiguration:configuration];
        picker.delegate = self;
        [controller presentViewController:picker animated:YES completion:nil];
    });
}

- (void)slock_presentDocumentPicker {
    SlockBridgeRunOnMain(^{
        UIViewController *controller = [self slock_currentViewController];
        if (!controller) {
            [self slock_finishAttachmentPicker:@{
                @"code" : @0,
                @"bodyText" : @"",
                @"errorCode" : @"attachment_picker_unavailable",
                @"errorBodyText" : @"Attachment picker is unavailable"
            }];
            return;
        }
        UIDocumentPickerViewController *picker =
            [[UIDocumentPickerViewController alloc] initForOpeningContentTypes:@[UTTypeItem] asCopy:YES];
        picker.delegate = self;
        picker.allowsMultipleSelection = YES;
        [controller presentViewController:picker animated:YES completion:nil];
    });
}

- (void)picker:(PHPickerViewController *)picker didFinishPicking:(NSArray<PHPickerResult *> *)results {
    [picker dismissViewControllerAnimated:YES completion:nil];
    if (results.count == 0) {
        [self slock_finishAttachmentPicker:@{ @"code" : @204, @"bodyText" : @"" }];
        return;
    }
    [self slock_processPhotoPickerResults:results];
}

- (void)slock_processPhotoPickerResults:(NSArray<PHPickerResult *> *)results {
    dispatch_queue_t worker = dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0);
    dispatch_group_t group = dispatch_group_create();
    NSMutableArray *orderedFiles = [NSMutableArray arrayWithCapacity:results.count];
    NSMutableArray<NSDictionary *> *errors = [NSMutableArray array];
    for (NSUInteger index = 0; index < results.count; index++) {
        [orderedFiles addObject:NSNull.null];
    }
    [results enumerateObjectsUsingBlock:^(PHPickerResult *result, NSUInteger index, BOOL *stop) {
        NSItemProvider *provider = result.itemProvider;
        UTType *mediaType = SlockPreferredPickedMediaType(provider);
        NSString *typeIdentifier = mediaType.identifier;
        if (mediaType == nil || ![provider hasItemConformingToTypeIdentifier:typeIdentifier]) {
            @synchronized (errors) {
                [errors addObject:@{ @"index" : @(index), @"errorCode" : @"attachment_read_failed" }];
            }
            return;
        }
        dispatch_group_enter(group);
        [provider loadFileRepresentationForTypeIdentifier:typeIdentifier completionHandler:^(NSURL *url, NSError *loadError) {
            // PHPicker's file-representation URL is temporary and is only valid
            // for the lifetime of this completion handler. Cache it before the
            // handler returns; dispatching the copy to another queue leaves that
            // queue racing the provider's cleanup and produces ENOENT.
            NSError *cacheError = loadError;
            NSDictionary *file = nil;
            if (url && !cacheError) {
                file = [self slock_cachePickedAttachmentURL:url
                                             suggestedName:provider.suggestedName
                                                  mimeType:mediaType.preferredMIMEType
                                                     error:&cacheError];
            }
            if (file) {
                @synchronized (orderedFiles) {
                    orderedFiles[index] = file;
                }
            } else {
                @synchronized (errors) {
                    [errors addObject:@{
                        @"index" : @(index),
                        @"errorCode" : @"attachment_read_failed",
                        @"message" : cacheError.localizedDescription ?: @"Unable to read attachment"
                    }];
                }
            }
            dispatch_group_leave(group);
        }];
    }];
    dispatch_group_notify(group, worker, ^{
        NSMutableArray<NSDictionary *> *files = [NSMutableArray array];
        @synchronized (orderedFiles) {
            for (id value in orderedFiles) {
                if ([value isKindOfClass:NSDictionary.class]) {
                    [files addObject:value];
                }
            }
        }
        [self slock_finishAttachmentPickerFiles:files errors:errors];
    });
}

- (void)slock_processDocumentPickerURLs:(NSArray<NSURL *> *)urls {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSMutableArray<NSDictionary *> *files = [NSMutableArray array];
        NSMutableArray<NSDictionary *> *errors = [NSMutableArray array];
        [urls enumerateObjectsUsingBlock:^(NSURL *url, NSUInteger index, BOOL *stop) {
            BOOL securityScoped = [url startAccessingSecurityScopedResource];
            NSError *error = nil;
            NSDictionary *file = [self slock_cachePickedAttachmentURL:url
                                                        suggestedName:url.lastPathComponent
                                                             mimeType:nil
                                                                error:&error];
            if (securityScoped) {
                [url stopAccessingSecurityScopedResource];
            }
            if (file) {
                [files addObject:file];
            } else {
                [errors addObject:@{
                    @"index" : @(index),
                    @"errorCode" : @"attachment_read_failed",
                    @"message" : error.localizedDescription ?: @"Unable to read attachment"
                }];
            }
        }];
        [self slock_finishAttachmentPickerFiles:files errors:errors];
    });
}

- (void)slock_finishAttachmentPickerFiles:(NSArray<NSDictionary *> *)files
                                    errors:(NSArray<NSDictionary *> *)errors {
    if (files.count == 0) {
        [self slock_finishAttachmentPicker:@{
            @"code" : @400,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_read_failed",
            @"errorBodyText" : @"No readable attachments"
        }];
        return;
    }
    NSMutableDictionary *body = [@{ @"files" : files } mutableCopy];
    if (errors.count > 0) {
        body[@"errors"] = errors;
        body[@"skippedCount"] = @(errors.count);
    }
    NSData *bodyData = [NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
    NSString *bodyText = bodyData ? [[NSString alloc] initWithData:bodyData encoding:NSUTF8StringEncoding] : @"";
    NSMutableDictionary *result = [@{
        @"code" : errors.count > 0 ? @206 : @200,
        @"bodyText" : bodyText ?: @"",
        @"files" : files
    } mutableCopy];
    if (errors.count > 0) {
        result[@"errors"] = errors;
        result[@"skippedCount"] = @(errors.count);
        result[@"errorCode"] = @"attachment_read_partial";
        result[@"errorBodyText"] = [NSString stringWithFormat:@"%lu attachment(s) could not be read", (unsigned long)errors.count];
    }
    [self slock_finishAttachmentPicker:result];
}

- (NSDictionary *)slock_cachePickedAttachmentURL:(NSURL *)sourceURL
                                    suggestedName:(NSString *)suggestedName
                                         mimeType:(NSString *)mimeType
                                            error:(NSError **)error {
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSURL *cacheRoot = [fileManager URLsForDirectory:NSCachesDirectory inDomains:NSUserDomainMask].firstObject;
    if (!cacheRoot) {
        return nil;
    }
    NSURL *directory = [[cacheRoot URLByAppendingPathComponent:@"Slock" isDirectory:YES]
        URLByAppendingPathComponent:@"PickedAttachments" isDirectory:YES];
    if (![fileManager createDirectoryAtURL:directory withIntermediateDirectories:YES attributes:nil error:error]) {
        return nil;
    }
    NSString *displayName = SlockPickedAttachmentDisplayName(suggestedName, sourceURL, mimeType);
    NSString *extension = displayName.pathExtension;
    NSString *cacheName = NSUUID.UUID.UUIDString;
    if (extension.length > 0) {
        cacheName = [cacheName stringByAppendingPathExtension:extension];
    }
    NSURL *targetURL = [directory URLByAppendingPathComponent:cacheName isDirectory:NO];
    if (![fileManager copyItemAtURL:sourceURL toURL:targetURL error:error]) {
        return nil;
    }
    NSNumber *fileSize = nil;
    [targetURL getResourceValue:&fileSize forKey:NSURLFileSizeKey error:nil];
    NSString *resolvedMimeType = mimeType;
    if (resolvedMimeType.length == 0 && extension.length > 0) {
        resolvedMimeType = [UTType typeWithFilenameExtension:extension].preferredMIMEType;
    }
    if (resolvedMimeType.length == 0) {
        resolvedMimeType = @"application/octet-stream";
    }
    return @{
        @"id" : [NSString stringWithFormat:@"picked-%@", NSUUID.UUID.UUIDString],
        @"fileName" : displayName.length > 0 ? displayName : @"attachment",
        @"mimeType" : resolvedMimeType,
        @"sizeBytes" : fileSize ?: @0,
        @"previewUri" : targetURL.absoluteString ?: @"",
        @"fileRef" : targetURL.absoluteString ?: @"",
        @"source" : @"platform_picker"
    };
}

- (void)slock_finishAttachmentPicker:(NSDictionary *)result {
    KuiklyRenderCallback callback = self.slock_attachmentPickerCallback;
    NSString *channelId = self.slock_attachmentPickerChannelId;
    NSArray *files = [result[@"files"] isKindOfClass:NSArray.class] ? result[@"files"] : @[];
    self.slock_attachmentPickerCallback = nil;
    self.slock_attachmentPickerChannelId = nil;
    SlockDiagnosticsRecord(
        @"info",
        @"SlockKuiklyAttachment",
        [NSString stringWithFormat:@"picker_finish code=%@ files=%lu channelIdPresent=%@",
                                   result[@"code"] ?: @0,
                                   (unsigned long)files.count,
                                   channelId.length > 0 ? @"true" : @"false"]
    );
    if (callback) {
        SlockBridgeRunOnMain(^{ callback(result ?: @{}); });
    }
}

- (void)slock_finishAttachmentExport:(NSDictionary *)result {
    KuiklyRenderCallback callback = self.slock_attachmentExportCallback;
    NSURL *exportURL = self.slock_attachmentExportURL;
    self.slock_attachmentExportCallback = nil;
    self.slock_attachmentExportRequestId = nil;
    self.slock_attachmentExportURL = nil;
    self.slock_attachmentExportFileName = nil;
    self.slock_attachmentExportSizeBytes = nil;
    self.slock_attachmentExportTrackProgress = NO;
    if (exportURL) {
        [NSFileManager.defaultManager removeItemAtURL:[exportURL URLByDeletingLastPathComponent] error:nil];
    }
    if (callback) {
        callback(result);
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
    if (!callback) return;
    NSDictionary *params = SlockBridgeParams(args);
    NSString *downloadId = SlockBridgeString(params[@"downloadId"]);
    NSString *attemptToken = SlockBridgeString(params[@"attemptToken"]);
    NSString *action = SlockBridgeString(params[@"action"]);
    if (downloadId.length == 0 || action.length == 0) {
        callback(@{ @"status" : @"failed", @"message" : @"Missing download control field" });
        return;
    }
    NSString *status = [SharedAttachmentDownloadCoordinator.shared controlDownloadId:downloadId
                                                              expectedAttemptToken:attemptToken
                                                                            action:action];
    callback(@{ @"status" : status ?: @"failed" });
}

- (void)attachmentDownloadOwnerChanged:(NSDictionary *)args {
    // iOS keeps attachment transfer authority in the common coordinator. The shared owner lease
    // still fences UI commits, but there is no Android-style native transient state to retire.
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

- (NSDictionary *)slock_notificationPermissionResultForSettings:(UNNotificationSettings *)settings {
    switch (settings.authorizationStatus) {
        case UNAuthorizationStatusAuthorized:
            return @{
                @"status" : @"ok",
                @"code" : @"permission_authorized",
                @"authorizationStatus" : @"authorized",
                @"message" : @"System notifications can show alerts, sounds, and badges."
            };
        case UNAuthorizationStatusProvisional:
            return @{
                @"status" : @"ok",
                @"code" : @"permission_provisional",
                @"authorizationStatus" : @"provisional",
                @"message" : @"Notifications are provisionally allowed and may be delivered quietly."
            };
        case UNAuthorizationStatusEphemeral:
            return @{
                @"status" : @"ok",
                @"code" : @"permission_ephemeral",
                @"authorizationStatus" : @"ephemeral",
                @"message" : @"Notifications are temporarily allowed."
            };
        case UNAuthorizationStatusDenied:
            return @{
                @"status" : @"error",
                @"code" : @"permission_denied",
                @"authorizationStatus" : @"denied",
                @"message" : @"Notification permission is denied. Open iOS Settings to enable it."
            };
        case UNAuthorizationStatusNotDetermined:
            return @{
                @"status" : @"ok",
                @"code" : @"permission_not_determined",
                @"authorizationStatus" : @"not_determined",
                @"message" : @"Raft will ask for notification permission after login."
            };
    }
    return @{
        @"status" : @"error",
        @"code" : @"permission_status_unknown",
        @"authorizationStatus" : @"unknown",
        @"message" : @"The iOS notification permission status is unknown."
    };
}

- (void)slock_getNotificationPermissionStatusWithCallback:(KuiklyRenderCallback)callback {
    [[UNUserNotificationCenter currentNotificationCenter]
        getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
            NSDictionary *result = [self slock_notificationPermissionResultForSettings:settings];
            SlockBridgeRunOnMain(^{
                callback(result);
            });
        }];
}

- (void)slock_requestNotificationPermissionWithCallback:(KuiklyRenderCallback)callback {
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        if (settings.authorizationStatus != UNAuthorizationStatusNotDetermined &&
            settings.authorizationStatus != UNAuthorizationStatusProvisional) {
            NSDictionary *result = [self slock_notificationPermissionResultForSettings:settings];
            SlockBridgeRunOnMain(^{
                callback(result);
            });
            return;
        }
        UNAuthorizationOptions options = UNAuthorizationOptionAlert | UNAuthorizationOptionBadge | UNAuthorizationOptionSound;
        [center requestAuthorizationWithOptions:options
                              completionHandler:^(BOOL granted, NSError *error) {
            if (error) {
                SlockDiagnosticsRecord(@"warn", @"SlockIosPush", @"notification_permission_request_failed source=settings");
                SlockBridgeRunOnMain(^{
                    callback(@{
                        @"status" : @"error",
                        @"code" : @"permission_request_failed",
                        @"message" : @"The iOS notification permission request failed."
                    });
                });
                return;
            }
            SlockDiagnosticsRecord(
                @"info",
                @"SlockIosPush",
                [NSString stringWithFormat:@"notification_permission_request_finished source=settings granted=%@", granted ? @"true" : @"false"]
            );
            [self slock_getNotificationPermissionStatusWithCallback:callback];
        }];
    }];
}

- (void)slock_openNotificationSettingsWithCallback:(KuiklyRenderCallback)callback {
    SlockBridgeRunOnMain(^{
        NSString *settingsUrlString = UIApplicationOpenSettingsURLString;
        if (@available(iOS 16.0, *)) {
            settingsUrlString = UIApplicationOpenNotificationSettingsURLString;
        }
        NSURL *settingsUrl = [NSURL URLWithString:settingsUrlString];
        if (!settingsUrl) {
            callback(@{
                @"status" : @"error",
                @"code" : @"notification_settings_url_unavailable",
                @"message" : @"iOS notification settings are unavailable."
            });
            return;
        }
        [[UIApplication sharedApplication]
            openURL:settingsUrl
            options:@{}
            completionHandler:^(BOOL success) {
                callback(success
                    ? @{
                        @"status" : @"ok",
                        @"code" : @"notification_settings_opened",
                        @"message" : @"iOS notification settings were opened."
                    }
                    : @{
                        @"status" : @"error",
                        @"code" : @"notification_settings_open_failed",
                        @"message" : @"iOS notification settings could not be opened."
                    });
            }];
    });
}

- (NSDictionary *)slock_settingsCapabilityResultForAction:(NSString *)action params:(NSDictionary *)params {
    if (action.length == 0) {
        return [self slock_unsupportedResultWithCode:@"settings_capability_unknown"
                                             message:@"Missing settings capability action"];
    }
    if ([action isEqualToString:@"loadAbout"]) {
        NSMutableDictionary *about = [SlockDiagnosticsCurrentEnvironmentAbout() mutableCopy];
        about[@"status"] = @"ok";
        return about;
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
    if ([action isEqualToString:@"sendTestNotification"] ||
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
        [self slock_promptAndSubmitHandsFeedback];
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"quiver_feedback_prompt_opened");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"submitFeedback"]) {
        NSString *description = [SlockBridgeString(params[@"message"])
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if (description.length == 0) {
            return [self slock_unsupportedResultWithCode:@"settings_feedback_message_required"
                                                 message:@"Feedback message is required"];
        }
        [self slock_submitHandsFeedbackWithDescription:description];
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"quiver_feedback_submit_started");
        return @{ @"status" : @"ok", @"ticketId" : @"", @"shareText" : @"" };
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

/// Hands feedback flow (parity with the Android host): prompt for a
/// description, submit a kind=bug ticket with the diagnostics export
/// attached, then copy a shareable "Hands feedback <id>" reference to the
/// clipboard so the user can paste it in the community.
- (void)slock_promptAndSubmitHandsFeedback {
    SlockBridgeRunOnMain(^{
        UIViewController *controller = [self slock_currentViewController];
        if (!controller) {
            return;
        }
        UIAlertController *prompt =
            [UIAlertController alertControllerWithTitle:SharedSlockHostStrings.shared.feedbackPromptTitle
                                                message:SharedSlockHostStrings.shared.feedbackPromptMessage
                                         preferredStyle:UIAlertControllerStyleAlert];
        [prompt addTextFieldWithConfigurationHandler:^(UITextField *textField) {
            textField.placeholder = SharedSlockHostStrings.shared.feedbackPromptPlaceholder;
        }];
        [prompt addAction:[UIAlertAction actionWithTitle:SharedSlockHostStrings.shared.feedbackCancel
                                                   style:UIAlertActionStyleCancel
                                                 handler:nil]];
        __weak UIAlertController *weakPrompt = prompt;
        [prompt addAction:[UIAlertAction actionWithTitle:SharedSlockHostStrings.shared.feedbackSend
                                                   style:UIAlertActionStyleDefault
                                                 handler:^(UIAlertAction *action) {
            NSString *description = [weakPrompt.textFields.firstObject.text
                stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
            if (description.length == 0) {
                return;
            }
            [self slock_submitHandsFeedbackWithDescription:description];
        }]];
        [controller presentViewController:prompt animated:YES completion:nil];
    });
}

- (void)slock_submitHandsFeedbackWithDescription:(NSString *)description {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        NSDictionary *draft = [self slock_prepareDiagnosticsFeedbackDraft];
        NSArray *draftPaths = [draft[@"attachmentPaths"] isKindOfClass:NSArray.class] ? draft[@"attachmentPaths"] : @[];
        NSString *path = SlockBridgeString(draft[@"filePath"]);
        NSArray<NSString *> *attachments = draftPaths.count > 0 ? draftPaths : (path.length > 0 ? @[ path ] : @[]);
        [Hands submitFeedback:description
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
                        [UIAlertController alertControllerWithTitle:SharedSlockHostStrings.shared.feedbackFailedTitle
                                                            message:error.localizedDescription ?: SharedSlockHostStrings.shared.feedbackErrorUnknown
                                                     preferredStyle:UIAlertControllerStyleAlert];
                    [failed addAction:[UIAlertAction actionWithTitle:SharedSlockHostStrings.shared.ok style:UIAlertActionStyleDefault handler:nil]];
                    [controller presentViewController:failed animated:YES completion:nil];
                    return;
                }
                NSString *shortId = [ticketId substringToIndex:MIN(ticketId.length, (NSUInteger)8)];
                NSString *environment = SlockDiagnosticsEnvironmentSectionFromExportText(
                    SlockDiagnosticsBuildExportText([self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}])
                );
                NSString *attachmentBlock = SlockHandsFeedbackAttachmentBlock(attachments);
                NSString *attachmentSection = attachmentBlock.length > 0
                    ? [NSString stringWithFormat:@"\n%@", attachmentBlock]
                    : @"";
                NSString *shareText = [NSString stringWithFormat:@"Hands feedback %@\nTicket: %@%@\n\nEnvironment:\n%@\n\nFeedback:\n%@",
                    shortId,
                    ticketId,
                    attachmentSection,
                    environment.length > 0 ? environment : @"--",
                    description];
                UIPasteboard.generalPasteboard.string = shareText;
                SlockDiagnosticsRecord(@"info", @"SlockDiagnostics",
                    [NSString stringWithFormat:@"quiver_feedback_submitted ticket=%@", shortId]);
                UIAlertController *done =
                    [UIAlertController alertControllerWithTitle:SharedSlockHostStrings.shared.feedbackSentTitle
                                                        message:SharedSlockHostStrings.shared.feedbackCopiedMessage
                                                 preferredStyle:UIAlertControllerStyleAlert];
                [done addAction:[UIAlertAction actionWithTitle:SharedSlockHostStrings.shared.ok style:UIAlertActionStyleDefault handler:nil]];
                [controller presentViewController:done animated:YES completion:nil];
            });
        }];
    });
}

- (NSDictionary *)slock_prepareDiagnosticsFeedbackDraft {
    NSDictionary *about = [self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}];
    NSDictionary *attachments = SlockDiagnosticsPrepareAttachmentFiles(about, @"settings_feedback", YES);
    if (![SlockBridgeString(attachments[@"status"]) isEqualToString:@"ok"]) {
        return attachments;
    }
    NSString *fileName = SlockBridgeString(attachments[@"fileName"]);
    NSString *message = [NSString stringWithFormat:@"## Raft feedback\n\nDiagnostics attachment: %@\n\n<!-- Describe what happened here. -->", fileName];
    return @{
        @"status" : @"ok",
        @"message" : message,
        @"fileName" : fileName,
        @"mimeType" : SlockBridgeString(attachments[@"mimeType"]),
        @"sizeBytes" : attachments[@"sizeBytes"] ?: @0,
        @"bodyBase64" : SlockBridgeString(attachments[@"bodyBase64"]),
        @"filePath" : SlockBridgeString(attachments[@"filePath"]),
        @"attachmentPaths" : attachments[@"attachmentPaths"] ?: @[]
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
