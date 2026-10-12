#import "HRBridgeModule.h"
#import "RaftDiagnosticsPathAdmission.h"
#import "RaftDiagnosticsEnvironmentRows.h"

#import "KuiklyRenderViewController.h"
#import <AVFoundation/AVFoundation.h>
#import <CommonCrypto/CommonDigest.h>
#import <Hands/Hands.h>
#import <ImageIO/ImageIO.h>
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
#import <sys/stat.h>
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
static NSUInteger const SlockThreadAttachmentImageMaxEdge = 2048;
static CGFloat const SlockThreadAttachmentImageJPEGQuality = 0.82;
static NSString *const SlockThreadAttachmentImageCompressionErrorDomain =
    @"build.raft.app.thread-attachment-image-compression";
static NSString *const SlockThreadImageCompressionSource = @"composer_image_compressor";
static NSString *const SlockThreadImageCompressionDirectoryName = @"PickedAttachments";
static NSUInteger const SlockThreadImageCompressionMaxRequestIdLength = 128;
static NSUInteger const SlockThreadImageCompressionMaxFileNameLength = 256;
static NSString *const SlockAttachmentExportDestinationFiles = @"files";
static NSString *const SlockAttachmentExportDestinationPhotos = @"photos";
static NSString *const SlockAttachmentExportDestinationOpen = @"open";
static NSString *const SlockThreadShareImageDirectoryName = @"RaftThreadShareImages";
static NSTimeInterval const SlockThreadShareImageMaximumAgeSeconds = 24.0 * 60.0 * 60.0;
static NSString *const SlockBridgeReplaceCurrentPageKey = @"slockReplaceCurrentPage";
static NSString *const SlockDiagnosticsLogDirName = @"raft_diagnostics_logs";
static NSString *const SlockDiagnosticsLogFileName = @"raft-diagnostics.jsonl";
static NSString *const SlockDiagnosticsFeedbackDirName = @"raft-feedback";
static NSString *const SlockDiagnosticsFeedbackFilePrefix = @"raft-feedback-";
static NSString *const SlockDiagnosticsSummaryFileName = @"raft-diagnostics-summary.txt";
static NSString *const SlockFeedbackDiagnosticsContentType = @"application/zip";
static unsigned long long const SlockFeedbackDiagnosticsMaxBytes = 200ULL * 1024ULL * 1024ULL;
static NSString *const SlockHandsFeedbackBaseURL = @"https://hands.build";
static NSString *const SlockHandsFeedbackAppSlug = @"raft-ios";
static NSString *const SlockHandsFeedbackClientKey = @"qk_2d6ac57a6532bc7ff1d3183b43bda99e2e732a99";
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

static NSString *SlockFeedbackDraftAccountSegment(NSString *accountId) {
    NSString *scope = accountId.length > 0 ? accountId : @"anonymous";
    NSData *accountData = [scope dataUsingEncoding:NSUTF8StringEncoding];
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(accountData.bytes, (CC_LONG)accountData.length, digest);
    NSMutableString *segment = [NSMutableString stringWithString:@"account-"];
    for (NSUInteger index = 0; index < 16; index++) {
        [segment appendFormat:@"%02x", digest[index]];
    }
    return segment;
}

static NSString *SlockFeedbackDraftExtension(NSString *fileName, NSString *mimeType) {
    NSString *normalizedMime = [[mimeType componentsSeparatedByString:@";"] firstObject].lowercaseString;
    if ([normalizedMime isEqualToString:@"image/avif"]) return @"avif";
    if ([normalizedMime isEqualToString:@"image/gif"]) return @"gif";
    if ([normalizedMime isEqualToString:@"image/heic"]) return @"heic";
    if ([normalizedMime isEqualToString:@"image/heif"]) return @"heif";
    if ([normalizedMime isEqualToString:@"image/jpeg"] ||
        [normalizedMime isEqualToString:@"image/jpg"]) return @"jpg";
    if ([normalizedMime isEqualToString:@"image/png"]) return @"png";
    if ([normalizedMime isEqualToString:@"image/webp"]) return @"webp";
    NSString *candidate = fileName.pathExtension.lowercaseString;
    NSCharacterSet *nonAlphanumeric = NSCharacterSet.alphanumericCharacterSet.invertedSet;
    if (candidate.length > 0 && candidate.length <= 10 &&
        [candidate rangeOfCharacterFromSet:nonAlphanumeric].location == NSNotFound) {
        return candidate;
    }
    return @"bin";
}

static NSString *SlockFeedbackDraftTargetName(NSUInteger index, NSDictionary *file) {
    NSString *extension = SlockFeedbackDraftExtension(
        SlockBridgeString(file[@"fileName"]),
        SlockBridgeString(file[@"mimeType"]));
    return [NSString stringWithFormat:@"%02lu-attachment.%@", (unsigned long)index, extension];
}

static NSString *SlockFeedbackDraftDisplayName(NSString *fileName, NSString *targetName) {
    NSString *rawName = fileName.lastPathComponent;
    if (rawName.length == 0) {
        NSRange separator = [targetName rangeOfString:@"-"];
        return separator.location == NSNotFound ? targetName : [targetName substringFromIndex:separator.location + 1];
    }
    NSString *targetExtension = targetName.pathExtension.lowercaseString;
    if (targetExtension.length == 0) return rawName;
    NSString *stem = rawName.stringByDeletingPathExtension;
    if (stem.length == 0) stem = @"attachment";
    return [stem stringByAppendingPathExtension:targetExtension];
}

static NSArray<NSDictionary *> *SlockFeedbackDraftFilesAtDirectory(
    NSURL *directory,
    NSArray *files,
    unsigned long long maxBytes,
    NSFileManager *fileManager
) {
    BOOL isDirectory = NO;
    if (![fileManager fileExistsAtPath:directory.path isDirectory:&isDirectory] || !isDirectory) {
        return nil;
    }
    NSArray<NSString *> *names = [fileManager contentsOfDirectoryAtPath:directory.path error:nil];
    if (names.count != files.count) {
        return nil;
    }
    NSMutableArray<NSDictionary *> *attachments = [NSMutableArray arrayWithCapacity:files.count];
    for (NSUInteger index = 0; index < files.count; index++) {
        NSDictionary *file = [files[index] isKindOfClass:NSDictionary.class] ? files[index] : @{};
        NSString *targetName = SlockFeedbackDraftTargetName(index, file);
        NSURL *targetURL = [directory URLByAppendingPathComponent:targetName isDirectory:NO];
        NSNumber *targetSize = nil;
        if (![targetURL getResourceValue:&targetSize forKey:NSURLFileSizeKey error:nil] ||
            targetSize.unsignedLongLongValue == 0 || targetSize.unsignedLongLongValue > maxBytes) {
            return nil;
        }
        NSString *displayName = SlockFeedbackDraftDisplayName(
            SlockBridgeString(file[@"fileName"]), targetName);
        [attachments addObject:@{
            @"id" : SlockBridgeString(file[@"id"]),
            @"fileName" : displayName,
            @"mimeType" : SlockBridgeString(file[@"mimeType"]),
            @"sizeBytes" : targetSize,
            @"previewUri" : targetURL.absoluteString ?: @"",
            @"fileRef" : targetURL.absoluteString ?: @"",
            @"source" : @"settings_feedback_draft"
        }];
    }
    return attachments;
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

static BOOL SlockIosPathHasControlCharacter(NSString *value) {
    return [value rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound;
}

static BOOL SlockIosPathIsSymlink(NSString *path) {
    if (path.length == 0) return NO;
    const char *fileSystemPath = path.fileSystemRepresentation;
    if (fileSystemPath == NULL) return NO;
    struct stat attributes;
    return lstat(fileSystemPath, &attributes) == 0 && S_ISLNK(attributes.st_mode);
}

static BOOL SlockIosPathIsSafeComponent(NSString *component) {
    return component.length > 0 &&
        ![component isEqualToString:@"."] &&
        ![component isEqualToString:@".."] &&
        !SlockIosPathHasControlCharacter(component);
}

static NSString * _Nullable SlockIosPathWithoutTrailingSlashes(NSString *path);

/*
 * iOS/Darwin presents a small, fixed set of system aliases as symlinks.  They
 * are the only symlinked ancestors accepted by the raw walk; arbitrary app
 * symlinks remain rejected.  Containment is still decided on the fully
 * resolved path after this allow-list check.
 */
static BOOL SlockIosIsAllowedDarwinAliasSymlink(NSString *path, const struct stat *attributes) {
    if (attributes == NULL || !S_ISLNK(attributes->st_mode)) return NO;
    NSString *trimmed = SlockIosPathWithoutTrailingSlashes(path);
    return [trimmed isEqualToString:@"/var"] || [trimmed isEqualToString:@"/tmp"];
}

static NSString * _Nullable SlockIosPathWithoutTrailingSlashes(NSString *path) {
    if (path.length == 0 || ![path hasPrefix:@"/"]) return nil;
    NSString *trimmed = path;
    while (trimmed.length > 1 && [trimmed hasSuffix:@"/"]) {
        trimmed = [trimmed substringToIndex:trimmed.length - 1];
    }
    return trimmed;
}

/*
 * Split a pathname without normalizing it first.  In particular, a raw "." or
 * ".." component is rejected instead of being folded away by
 * stringByStandardizingPath.  Empty interior components are rejected too so
 * that the pathname we later rename is exactly the pathname we validated.
 */
static NSArray<NSString *> * _Nullable SlockIosRawAbsolutePathComponents(NSString *path) {
    NSString *trimmed = SlockIosPathWithoutTrailingSlashes(path);
    if (trimmed.length == 0) return nil;
    NSArray<NSString *> *parts = [trimmed componentsSeparatedByString:@"/"];
    if (parts.count == 0 || ![parts.firstObject isEqualToString:@""]) return nil;
    NSMutableArray<NSString *> *components = [NSMutableArray array];
    for (NSUInteger index = 1; index < parts.count; index++) {
        NSString *component = parts[index];
        if (!SlockIosPathIsSafeComponent(component)) return nil;
        [components addObject:component];
    }
    return components;
}

static BOOL SlockIosLstatPath(NSString *path, struct stat * _Nullable output) {
    if (path.length == 0) return NO;
    const char *fileSystemPath = path.fileSystemRepresentation;
    if (fileSystemPath == NULL) return NO;
    struct stat attributes;
    if (lstat(fileSystemPath, &attributes) != 0) return NO;
    if (output != NULL) *output = attributes;
    return YES;
}

/* Validate the root itself and every parent component with lstat/no-follow. */
static BOOL SlockIosValidateDirectoryRoot(NSString *root) {
    NSString *trimmed = SlockIosPathWithoutTrailingSlashes(root);
    NSArray<NSString *> *components = SlockIosRawAbsolutePathComponents(trimmed ?: @"");
    if (components == nil) return NO;
    struct stat rootAttributes;
    if (!SlockIosLstatPath(trimmed, &rootAttributes) ||
        (S_ISLNK(rootAttributes.st_mode) &&
            !SlockIosIsAllowedDarwinAliasSymlink(trimmed, &rootAttributes)) ||
        (!S_ISLNK(rootAttributes.st_mode) && !S_ISDIR(rootAttributes.st_mode))) {
        return NO;
    }
    NSString *cursor = @"/";
    for (NSString *component in components) {
        cursor = [cursor stringByAppendingPathComponent:component];
        struct stat attributes;
        if (!SlockIosLstatPath(cursor, &attributes) ||
            (S_ISLNK(attributes.st_mode)
                ? !SlockIosIsAllowedDarwinAliasSymlink(cursor, &attributes)
                : !S_ISDIR(attributes.st_mode))) {
            return NO;
        }
    }
    return YES;
}

/*
 * Return the original (non-canonicalized) path only after every component has been
 * lstat-style checked.  The retirement transition must rename this exact pathname;
 * returning a symlink-resolved path would re-open an alias/ABA window.
 */
static NSString *SlockIosCacheOwnedTemporaryPath(NSString *root, NSString *candidatePath) {
    if (root.length == 0 || candidatePath.length == 0 ||
        SlockIosPathHasControlCharacter(root) || SlockIosPathHasControlCharacter(candidatePath)) {
        return nil;
    }
    NSString *rawRoot = SlockIosPathWithoutTrailingSlashes(root);
    NSString *rawCandidate = SlockIosPathWithoutTrailingSlashes(candidatePath);
    NSArray<NSString *> *rootComponents = SlockIosRawAbsolutePathComponents(rawRoot ?: @"");
    NSArray<NSString *> *candidateComponents = SlockIosRawAbsolutePathComponents(rawCandidate ?: @"");
    if (rawRoot.length == 0 || rawCandidate.length == 0 || rootComponents == nil ||
        candidateComponents == nil || !SlockIosValidateDirectoryRoot(rawRoot)) {
        return nil;
    }
    /* Validate the candidate's complete raw path without following any component. */
    NSString *cursor = @"/";
    for (NSUInteger index = 0; index < candidateComponents.count; index++) {
        NSString *component = candidateComponents[index];
        cursor = [cursor stringByAppendingPathComponent:component];
        struct stat attributes;
        BOOL isFinal = index + 1 == candidateComponents.count;
        if (!SlockIosLstatPath(cursor, &attributes)) return nil;
        if (isFinal) {
            if (S_ISLNK(attributes.st_mode) || !S_ISREG(attributes.st_mode) || attributes.st_size <= 0) {
                return nil;
            }
        } else if (S_ISLNK(attributes.st_mode)
            ? !SlockIosIsAllowedDarwinAliasSymlink(cursor, &attributes)
            : !S_ISDIR(attributes.st_mode)) {
            return nil;
        }
    }

    /*
     * Darwin exposes aliases such as /var and /private/var.  Raw component
     * prefixes therefore cannot be the containment test.  Resolve aliases only
     * after the complete raw lstat walk above; return the exact raw candidate
     * below so the rename uses the pathname that was actually checked.
     */
    NSString *canonicalRoot = rawRoot.stringByResolvingSymlinksInPath;
    NSString *canonicalCandidate = rawCandidate.stringByResolvingSymlinksInPath;
    NSString *canonicalPrefix = [canonicalRoot stringByAppendingString:@"/"];
    if (canonicalRoot.length == 0 || canonicalCandidate.length == 0 ||
        ![canonicalCandidate hasPrefix:canonicalPrefix]) {
        return nil;
    }
    return rawCandidate;
}

static NSString *SlockIosFilePathFromRetirementRef(NSString *fileRef) {
    if (fileRef.length == 0 || SlockIosPathHasControlCharacter(fileRef)) return nil;
    if ([fileRef hasPrefix:@"/"]) return fileRef;
    NSURL *url = [NSURL URLWithString:fileRef];
    if (!url.isFileURL || url.host.length != 0 || url.user.length != 0 ||
        url.password.length != 0 || url.query.length != 0 || url.fragment.length != 0 ||
        url.path.length == 0) {
        return nil;
    }
    return url.path;
}

static SharedThreadComposerTemporaryFileRetirementResult *
SlockIosRetireThreadComposerTemporaryFileRefs(
    NSString *sessionId,
    NSSet<NSString *> *fileRefs
) {
    NSString *cleanSessionId = [sessionId stringByTrimmingCharactersInSet:
        NSCharacterSet.whitespaceAndNewlineCharacterSet];
    NSMutableSet<NSString *> *requested = [NSMutableSet set];
    for (id rawRef in fileRefs) {
        if (![rawRef isKindOfClass:NSString.class]) continue;
        NSString *ref = [(NSString *)rawRef
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if (ref.length > 0) [requested addObject:ref];
    }
    if (cleanSessionId.length == 0 || cleanSessionId.length > 256 ||
        SlockIosPathHasControlCharacter(cleanSessionId)) {
        return [[SharedThreadComposerTemporaryFileRetirementResult alloc]
            initWithSessionId:cleanSessionId
            requestedFileRefs:requested
            admittedFileRefs:[NSSet set]
            quarantinedFileRefs:[NSSet set]
            rejectedFileRefs:requested
            failedFileRefs:[NSSet set]
            tombstoneByFileRef:@{}
            errorCode:SharedThreadComposerTemporaryFileRetirementResultCompanion.shared.InvalidSessionCode];
    }
    if (requested.count == 0) {
        return [SharedThreadComposerTemporaryFileRetirementResultCompanion.shared
            emptySessionId:cleanSessionId];
    }

    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSArray<NSString *> *candidateRoots = @[
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject ?: @"",
        NSTemporaryDirectory() ?: @""
    ];
    NSMutableArray<NSString *> *roots = [NSMutableArray array];
    for (NSString *candidateRoot in candidateRoots) {
        NSString *root = candidateRoot.stringByStandardizingPath;
        if (root.length == 0 || SlockIosPathIsSymlink(root)) continue;
        NSDictionary<NSFileAttributeKey, id> *attributes =
            [fileManager attributesOfItemAtPath:root error:nil];
        if (![attributes[NSFileType] isEqualToString:NSFileTypeDirectory]) continue;
        if (![roots containsObject:root]) [roots addObject:root];
    }

    NSMutableSet<NSString *> *admitted = [NSMutableSet set];
    NSMutableSet<NSString *> *quarantined = [NSMutableSet set];
    NSMutableSet<NSString *> *rejected = [NSMutableSet set];
    NSMutableSet<NSString *> *failed = [NSMutableSet set];
    NSMutableDictionary<NSString *, NSString *> *tombstoneByRef = [NSMutableDictionary dictionary];
    NSMutableArray<NSString *> *tombstones = [NSMutableArray array];

    for (NSString *ref in requested) {
        NSString *rawPath = SlockIosFilePathFromRetirementRef(ref);
        NSString *ownedPath = nil;
        for (NSString *root in roots) {
            ownedPath = SlockIosCacheOwnedTemporaryPath(root, rawPath ?: @"");
            if (ownedPath.length > 0) break;
        }
        if (ownedPath.length == 0) {
            [rejected addObject:ref];
            continue;
        }
        [admitted addObject:ref];

        NSString *parent = [ownedPath stringByDeletingLastPathComponent];
        NSString *leaf = ownedPath.lastPathComponent;
        NSURL *sourceURL = [NSURL fileURLWithPath:ownedPath];
        NSURL *tombstoneURL = nil;
        for (NSUInteger attempt = 0; attempt < 4 && tombstoneURL == nil; attempt++) {
            NSString *name = [NSString stringWithFormat:@".%@.retired-%@", leaf, NSUUID.UUID.UUIDString];
            NSURL *candidate = [NSURL fileURLWithPath:[parent stringByAppendingPathComponent:name]];
            if ([fileManager fileExistsAtPath:candidate.path]) continue;
            NSError *moveError = nil;
            if ([fileManager moveItemAtURL:sourceURL toURL:candidate error:&moveError]) {
                tombstoneURL = candidate;
            }
        }
        if (tombstoneURL == nil) {
            [failed addObject:ref];
            continue;
        }
        [quarantined addObject:ref];
        tombstoneByRef[ref] = tombstoneURL.path;
        [tombstones addObject:tombstoneURL.path];
    }

    if (tombstones.count > 0) {
        NSArray<NSString *> *deferredPaths = [tombstones copy];
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            NSFileManager *deferredManager = NSFileManager.defaultManager;
            for (NSString *path in deferredPaths) {
                [deferredManager removeItemAtPath:path error:nil];
            }
        });
    }
    NSString *errorCode = failed.count > 0
        ? SharedThreadComposerTemporaryFileRetirementResultCompanion.shared.HandlerFailureCode
        : nil;
    return [[SharedThreadComposerTemporaryFileRetirementResult alloc]
        initWithSessionId:cleanSessionId
        requestedFileRefs:requested
        admittedFileRefs:admitted
        quarantinedFileRefs:quarantined
        rejectedFileRefs:rejected
        failedFileRefs:failed
        tombstoneByFileRef:tombstoneByRef
        errorCode:errorCode];
}

@interface SlockIosThreadComposerTemporaryFileRetirementHandler : NSObject
    <SharedThreadComposerTemporaryFileRetirementHandler>
@end

@implementation SlockIosThreadComposerTemporaryFileRetirementHandler
- (SharedThreadComposerTemporaryFileRetirementResult *)retireSessionId:(NSString *)sessionId
                                                               fileRefs:(NSSet<NSString *> *)fileRefs {
    return SlockIosRetireThreadComposerTemporaryFileRefs(sessionId, fileRefs);
}
@end

static SlockIosThreadComposerTemporaryFileRetirementHandler *SlockIosRetirementHandlerInstance;

void SlockIosInstallThreadComposerTemporaryFileRetirement(void) {
    if (SlockIosRetirementHandlerInstance == nil) {
        SlockIosRetirementHandlerInstance = [SlockIosThreadComposerTemporaryFileRetirementHandler new];
    }
    [SharedThreadComposerTemporaryFileRetirement.shared
        installHandler:SlockIosRetirementHandlerInstance];
    SlockIosDiagnosticsRecord(
        @"info",
        @"SlockFilePreviewCleanup",
        @"ios_retirement_handler_installed");
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

static NSString *SlockDiagnosticsBuildExportText(NSDictionary *about, BOOL omitEmptyRows) {
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
    // task #382 (product ruling 2026-09-17, option (a)): user-facing copy/share
    // surfaces omit empty rows entirely instead of printing "--" placeholders;
    // the diagnostics attachment passes omitEmptyRows=NO and keeps full rows.
    [text appendString:RaftDiagnosticsEnvironmentRowsText(about, rows, omitEmptyRows)];
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
            SlockBridgeSanitizedLogString(SlockDiagnosticsBuildExportText(about ?: @{}, NO))
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
        SlockDiagnosticsRecord(@"warn", @"SlockDiagnostics", @"hands_diagnostics_provider_unavailable");
        return;
    }
    NSArray<NSString *> *_Nullable (^provider)(int64_t) = ^NSArray<NSString *> *_Nullable(int64_t crashAtMillis) {
        return SlockIosPrepareDiagnosticsAttachmentPathsForCrash(crashAtMillis);
    };
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
    [crashReporter performSelector:selector withObject:[provider copy]];
#pragma clang diagnostic pop
    SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"hands_diagnostics_provider_registered crashAtMillis=true");
}

void SlockIosInstallHandsReporting(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        // App-owned Hands reporting config (client key is DSN-class: it
        // identifies the app; rotate from the Hands console if leaked).
        [Hands installWithConfig:[HandsConfig configWithBaseUrl:@"https://hands.build"
                                                          appSlug:@"raft-ios"
                                                          channel:@"main"
                                                        clientKey:@"qk_2d6ac57a6532bc7ff1d3183b43bda99e2e732a99"]];
        // Install fatal handlers before our diagnostic registration/logging.
        // The provider is evaluated on a later upload, not during registration.
        SlockIosRegisterHandsDiagnosticsProvider();
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

static BOOL SlockAttachmentIsImage(NSString *fileName, NSString *mimeType, NSURL *sourceURL) {
    NSString *normalizedMimeType = SlockBridgeString(mimeType).lowercaseString;
    normalizedMimeType = [[normalizedMimeType componentsSeparatedByString:@";"] firstObject] ?: @"";
    NSString *nameExtension = SlockBridgeString(fileName).pathExtension.lowercaseString;
    NSString *sourceExtension = sourceURL.pathExtension.lowercaseString;
    // Picker providers occasionally pair an image MIME/name with a movie-backed URL.  Treat a
    // known video suffix from either identity as authoritative and reject it before accepting
    // image/*; otherwise generic attachFile can send the movie through ImageIO compression.
    NSSet<NSString *> *videoExtensions = [NSSet setWithArray:@[
        @"3gp", @"3g2", @"avi", @"flv", @"m4v", @"mkv", @"mov", @"mp4", @"mpeg", @"mpg",
        @"m2p", @"m2ts", @"mts", @"qt", @"ts", @"webm", @"wmv"
    ]];
    if ([videoExtensions containsObject:nameExtension] ||
        [videoExtensions containsObject:sourceExtension] ||
        [normalizedMimeType hasPrefix:@"video/"]) {
        return NO;
    }
    if ([normalizedMimeType hasPrefix:@"image/"]) {
        return YES;
    }
    NSString *extension = nameExtension;
    if (extension.length == 0) {
        extension = sourceExtension;
    }
    NSSet<NSString *> *imageExtensions = [NSSet setWithArray:@[
        @"avif", @"bmp", @"gif", @"heic", @"heif", @"jpeg", @"jpg", @"png", @"webp", @"tif", @"tiff"
    ]];
    return [imageExtensions containsObject:extension];
}

static NSError *SlockThreadAttachmentImageCompressionError(NSString *message) {
    return [NSError errorWithDomain:SlockThreadAttachmentImageCompressionErrorDomain
                                code:1
                            userInfo:@{ NSLocalizedDescriptionKey :
                                            message.length > 0 ? message : @"Unable to compress image attachment" }];
}

static BOOL SlockIsThreadAttachmentImageCompressionError(NSError *error) {
    return [error.domain isEqualToString:SlockThreadAttachmentImageCompressionErrorDomain];
}

static NSDictionary *SlockThreadImageCompressionFailurePayload(void) {
    return @{
        @"code" : @400,
        @"errorCode" : @"attachment_image_compression_failed",
        @"errorBodyText" : @"Unable to compress image attachment"
    };
}

static NSString *SlockThreadImageCompressionMimeType(NSString *value) {
    NSString *mime = SlockBridgeString(value).lowercaseString;
    return [[mime componentsSeparatedByString:@";"] firstObject] ?: @"";
}

static BOOL SlockThreadImageCompressionSafeName(NSString *value) {
    NSString *name = SlockBridgeString(value);
    if (name.length == 0 || name.length > SlockThreadImageCompressionMaxFileNameLength) {
        return NO;
    }
    if ([name containsString:@"/"] || [name containsString:@"\\"] || [name containsString:@".."] ||
        [name rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound) {
        return NO;
    }
    return YES;
}

static NSString *SlockThreadImageCompressionOutputName(NSString *fileName) {
    NSString *leaf = SlockBridgeString(fileName).lastPathComponent;
    if (leaf.length == 0) leaf = @"image";
    NSString *stem = [leaf stringByDeletingPathExtension];
    if (stem.length == 0) stem = @"image";
    NSMutableString *safe = [NSMutableString stringWithCapacity:stem.length];
    NSCharacterSet *allowed = [NSCharacterSet characterSetWithCharactersInString:
        @"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._- "];
    for (NSUInteger index = 0; index < stem.length; index++) {
        unichar character = [stem characterAtIndex:index];
        [safe appendFormat:@"%C", [allowed characterIsMember:character] ? character : '_'];
    }
    NSString *trimmed = [safe stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (trimmed.length == 0) trimmed = @"image";
    if (trimmed.length > SlockThreadImageCompressionMaxFileNameLength) {
        trimmed = [trimmed substringToIndex:SlockThreadImageCompressionMaxFileNameLength];
    }
    return [trimmed stringByAppendingPathExtension:@"jpg"];
}

static BOOL SlockThreadImageCompressionPathInside(NSString *rootPath, NSString *candidatePath) {
    NSString *prefix = [rootPath hasSuffix:@"/"] ? rootPath : [rootPath stringByAppendingString:@"/"];
    return [candidatePath hasPrefix:prefix];
}

static NSURL *SlockThreadImageCompressionSourceURL(NSString *fileRef, NSArray<NSURL *> *roots) {
    NSString *raw = SlockBridgeString(fileRef);
    if (raw.length == 0 ||
        [raw rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound) {
        return nil;
    }
    NSURL *url = nil;
    if ([raw hasPrefix:@"/"]) {
        url = [NSURL fileURLWithPath:raw];
    } else {
        NSURL *candidate = [NSURL URLWithString:raw];
        if (!candidate.isFileURL || candidate.host.length > 0 || candidate.query.length > 0 ||
            candidate.fragment.length > 0) {
            return nil;
        }
        url = candidate;
    }
    if (!url || !url.isFileURL) return nil;
    NSURL *resolved = [[[url URLByResolvingSymlinksInPath] standardizedURL] copy];
    if (!resolved.path.length) return nil;
    NSDictionary<NSFileAttributeKey, id> *attributes =
        [NSFileManager.defaultManager attributesOfItemAtPath:resolved.path error:nil];
    if (![attributes[NSFileType] isEqualToString:NSFileTypeRegular] ||
        [attributes[NSFileSize] unsignedLongLongValue] == 0) {
        return nil;
    }
    for (NSURL *rootURL in roots) {
        NSString *rootPath = [[[rootURL URLByResolvingSymlinksInPath] standardizedURL] path];
        if (rootPath.length > 0 && SlockThreadImageCompressionPathInside(rootPath, resolved.path)) {
            return resolved;
        }
    }
    return nil;
}

static BOOL SlockThreadImageCompressionInputIsImage(NSString *fileName,
                                                     NSString *mimeType,
                                                     NSURL *sourceURL) {
    NSString *mime = SlockThreadImageCompressionMimeType(mimeType);
    if (![mime hasPrefix:@"image/"]) return NO;
    NSSet<NSString *> *videoExtensions = [NSSet setWithArray:@[
        @"3gp", @"3g2", @"avi", @"flv", @"m4v", @"mkv", @"mov", @"mp4", @"mpeg", @"mpg",
        @"m2p", @"m2ts", @"mts", @"qt", @"ts", @"webm", @"wmv"
    ]];
    NSString *nameExtension = fileName.pathExtension.lowercaseString;
    NSString *sourceExtension = sourceURL.pathExtension.lowercaseString;
    // Providers can disagree about the display name and URL extension.  Reject either known
    // video extension before ImageIO gets a chance to interpret the bytes as an image.
    return ![videoExtensions containsObject:nameExtension] &&
        ![videoExtensions containsObject:sourceExtension];
}

typedef NS_ENUM(NSInteger, SlockThreadImageCompressionOperationState) {
    SlockThreadImageCompressionOperationStateActive = 0,
    SlockThreadImageCompressionOperationStatePending = 1,
    SlockThreadImageCompressionOperationStateDelivered = 2,
    SlockThreadImageCompressionOperationStateCancelled = 3,
};

/**
 * State and output ownership for one asynchronous editor-image compression request.
 *
 * The renderer can destroy a page while ImageIO is still decoding.  Keeping cancellation and
 * delivery state on a small lock-protected object lets the worker finish its autorelease pool
 * without ever invoking a stale callback or publishing a part file as a JPEG.
 */
@interface SlockThreadImageCompressionOperation : NSObject

@property(nonatomic, copy) NSString *requestId;
@property(nonatomic, copy) KuiklyRenderCallback callback;
@property(nonatomic, strong) NSURL *outputURL;
@property(nonatomic, strong) NSURL *partURL;
@property(nonatomic, strong) NSRecursiveLock *lock;
/** Serializes write→rename with every output cleanup path. */
@property(nonatomic, strong) NSRecursiveLock *outputLock;
@property(nonatomic) SlockThreadImageCompressionOperationState state;

- (BOOL)markCancelled;
- (BOOL)markCancelledForce:(BOOL)force;
- (BOOL)markPending;
- (BOOL)markDelivered;
- (BOOL)isCancelled;
- (BOOL)isPending;
- (BOOL)isDelivered;
- (void)removeOutputs;

@end

@implementation SlockThreadImageCompressionOperation

- (instancetype)init {
    self = [super init];
    if (self) {
        // Delivery invokes an arbitrary renderer callback while the operation fence is held.
        // Recursive locking keeps same-thread cancel/re-entry harmless while other threads
        // wait until the callback has either completed or been fenced by teardown.
        _lock = [[NSRecursiveLock alloc] init];
        // Keep output ownership independent from state/map locks.  Callers must never acquire
        // the map/state fence while holding this lock, which keeps teardown lock ordering acyclic.
        _outputLock = [[NSRecursiveLock alloc] init];
        _state = SlockThreadImageCompressionOperationStateActive;
    }
    return self;
}

- (BOOL)markCancelled {
    return [self markCancelledForce:NO];
}

- (BOOL)markCancelledForce:(BOOL)force {
    BOOL changed = NO;
    [self.lock lock];
    if (self.state != SlockThreadImageCompressionOperationStateCancelled &&
        (force || self.state != SlockThreadImageCompressionOperationStateDelivered)) {
        self.state = SlockThreadImageCompressionOperationStateCancelled;
        changed = YES;
    }
    [self.lock unlock];
    return changed;
}

- (BOOL)markPending {
    BOOL changed = NO;
    [self.lock lock];
    if (self.state == SlockThreadImageCompressionOperationStateActive) {
        self.state = SlockThreadImageCompressionOperationStatePending;
        changed = YES;
    }
    [self.lock unlock];
    return changed;
}

- (BOOL)markDelivered {
    BOOL changed = NO;
    [self.lock lock];
    if (self.state == SlockThreadImageCompressionOperationStatePending) {
        self.state = SlockThreadImageCompressionOperationStateDelivered;
        changed = YES;
    }
    [self.lock unlock];
    return changed;
}

- (BOOL)isCancelled {
    [self.lock lock];
    BOOL cancelled = self.state == SlockThreadImageCompressionOperationStateCancelled;
    [self.lock unlock];
    return cancelled;
}

- (BOOL)isDelivered {
    [self.lock lock];
    BOOL delivered = self.state == SlockThreadImageCompressionOperationStateDelivered;
    [self.lock unlock];
    return delivered;
}

- (BOOL)isPending {
    [self.lock lock];
    BOOL pending = self.state == SlockThreadImageCompressionOperationStatePending;
    [self.lock unlock];
    return pending;
}

- (void)removeOutputs {
    NSFileManager *fileManager = NSFileManager.defaultManager;
    [self.outputLock lock];
    @try {
        if (self.partURL) [fileManager removeItemAtURL:self.partURL error:nil];
        if (self.outputURL) [fileManager removeItemAtURL:self.outputURL error:nil];
    } @finally {
        [self.outputLock unlock];
    }
}

@end

static NSString *SlockCompressedAttachmentFileName(NSString *fileName) {
    NSString *leaf = SlockBridgeString(fileName).lastPathComponent;
    if (leaf.length == 0) {
        leaf = @"image";
    }
    NSString *stem = [leaf stringByDeletingPathExtension];
    if (stem.length == 0) {
        stem = @"image";
    }
    return [stem stringByAppendingPathExtension:@"jpg"];
}

static BOOL SlockWriteJPEGFromCGImage(CGImageRef image,
                                      NSURL *targetURL,
                                      NSError **error) {
    if (!image || CGImageGetWidth(image) == 0 || CGImageGetHeight(image) == 0) {
        if (error) *error = SlockThreadAttachmentImageCompressionError(@"Unable to decode image attachment");
        return NO;
    }
    CGImageDestinationRef destination = CGImageDestinationCreateWithURL(
        (__bridge CFURLRef)targetURL,
        (__bridge CFStringRef)UTTypeJPEG.identifier,
        1,
        NULL
    );
    if (!destination) {
        if (error) *error = SlockThreadAttachmentImageCompressionError(@"Unable to prepare image encoder");
        return NO;
    }
    NSDictionary *properties = @{
        (NSString *)kCGImageDestinationLossyCompressionQuality : @(SlockThreadAttachmentImageJPEGQuality)
    };
    CGImageDestinationAddImage(destination, image, (__bridge CFDictionaryRef)properties);
    BOOL finalized = CGImageDestinationFinalize(destination);
    CFRelease(destination);
    if (!finalized) {
        if (error) *error = SlockThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
        return NO;
    }
    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:targetURL.path error:nil];
    if (![attributes[NSFileSize] respondsToSelector:@selector(unsignedLongLongValue)] ||
        [attributes[NSFileSize] unsignedLongLongValue] == 0) {
        if (error) *error = SlockThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
        return NO;
    }
    return YES;
}

static BOOL SlockWriteCompressedPickedImage(NSURL *sourceURL,
                                            NSURL *targetURL,
                                            NSError **error) {
    // Encode into a sibling part first.  A picker/bridge cancellation or encoder failure must
    // never leave a path that looks like a complete JPEG; the final name is published only by
    // same-directory rename after ImageIO has finalized and size-checked the part.
    NSURL *partURL = [targetURL URLByAppendingPathExtension:@"part"];
    [NSFileManager.defaultManager removeItemAtURL:partURL error:nil];
    CGImageSourceRef source = CGImageSourceCreateWithURL((__bridge CFURLRef)sourceURL, NULL);
    if (!source || CGImageSourceGetCount(source) == 0) {
        if (source) CFRelease(source);
        if (error) *error = SlockThreadAttachmentImageCompressionError(@"Unable to decode image attachment");
        return NO;
    }
    NSDictionary *thumbnailOptions = @{
        (NSString *)kCGImageSourceCreateThumbnailFromImageAlways : @YES,
        (NSString *)kCGImageSourceCreateThumbnailWithTransform : @YES,
        (NSString *)kCGImageSourceThumbnailMaxPixelSize : @(SlockThreadAttachmentImageMaxEdge),
        (NSString *)kCGImageSourceShouldCacheImmediately : @NO
    };
    CGImageRef thumbnail = CGImageSourceCreateThumbnailAtIndex(
        source,
        0,
        (__bridge CFDictionaryRef)thumbnailOptions
    );
    BOOL result = SlockWriteJPEGFromCGImage(thumbnail, partURL, error);
    if (thumbnail) CFRelease(thumbnail);
    CFRelease(source);
    if (!result) {
        [NSFileManager.defaultManager removeItemAtURL:partURL error:nil];
        return NO;
    }
    NSError *moveError = nil;
    if (![NSFileManager.defaultManager moveItemAtURL:partURL toURL:targetURL error:&moveError]) {
        [NSFileManager.defaultManager removeItemAtURL:partURL error:nil];
        if (error) *error = SlockThreadAttachmentImageCompressionError(
            moveError.localizedDescription ?: @"Unable to commit image attachment"
        );
        return NO;
    }
    return result;
}

static BOOL SlockWriteCompressedCameraImage(UIImage *image,
                                            NSURL *targetURL,
                                            NSError **error) {
    CGImageRef source = image.CGImage;
    if (!source || CGImageGetWidth(source) == 0 || CGImageGetHeight(source) == 0) {
        if (error) *error = SlockThreadAttachmentImageCompressionError(@"Unable to decode captured image");
        return NO;
    }
    CGFloat sourceWidth = (CGFloat)CGImageGetWidth(source);
    CGFloat sourceHeight = (CGFloat)CGImageGetHeight(source);
    CGFloat scale = MIN(1.0, (CGFloat)SlockThreadAttachmentImageMaxEdge /
                              MAX(sourceWidth, sourceHeight));
    CGSize targetSize = CGSizeMake(MAX(1.0, round(sourceWidth * scale)),
                                   MAX(1.0, round(sourceHeight * scale)));
    UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
    format.scale = 1.0;
    format.opaque = YES;
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:targetSize
                                                                                   format:format];
    UIImage *normalized = [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
        [[UIColor whiteColor] setFill];
        CGContextFillRect(context.CGContext, CGRectMake(0, 0, targetSize.width, targetSize.height));
        [image drawInRect:CGRectMake(0, 0, targetSize.width, targetSize.height)];
    }];
    return SlockWriteJPEGFromCGImage(normalized.CGImage, targetURL, error);
}

static NSDictionary *SlockThreadShareImageFailure(NSString *message) {
    return @{
        @"status" : @"failed",
        @"message" : message.length > 0 ? message : @"Unable to export image"
    };
}

static CGFloat SlockThreadShareImageScale(UIView *view) {
    CGFloat scale = view.window.screen.scale;
    if (scale <= 0) {
        scale = UIScreen.mainScreen.scale;
    }
    return MAX(1.0, scale);
}

static NSString *SlockThreadShareImageDirectoryPath(void) {
    return [NSTemporaryDirectory() stringByAppendingPathComponent:SlockThreadShareImageDirectoryName];
}

static BOOL SlockThreadSharePrepareDirectory(BOOL pruneExpiredFiles) {
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSString *directory = SlockThreadShareImageDirectoryPath();
    BOOL isDirectory = NO;
    if ([fileManager fileExistsAtPath:directory isDirectory:&isDirectory]) {
        if (!isDirectory) {
            return NO;
        }
    } else if (![fileManager createDirectoryAtPath:directory
                       withIntermediateDirectories:YES
                                        attributes:nil
                                             error:nil]) {
        return NO;
    }
    if (pruneExpiredFiles) {
        NSDate *cutoff = [NSDate dateWithTimeIntervalSinceNow:-SlockThreadShareImageMaximumAgeSeconds];
        for (NSString *name in [fileManager contentsOfDirectoryAtPath:directory error:nil] ?: @[]) {
            NSString *path = [directory stringByAppendingPathComponent:name];
            NSDate *modified = [[fileManager attributesOfItemAtPath:path error:nil] fileModificationDate];
            if (modified && [modified compare:cutoff] == NSOrderedAscending) {
                [fileManager removeItemAtPath:path error:nil];
            }
        }
    }
    return YES;
}

static NSDictionary *SlockThreadShareWriteImage(UIImage *image, BOOL pruneExpiredFiles) {
    CGImageRef cgImage = image.CGImage;
    if (!cgImage || CGImageGetWidth(cgImage) == 0 || CGImageGetHeight(cgImage) == 0) {
        return SlockThreadShareImageFailure(@"Unable to capture image");
    }
    if (!SlockThreadSharePrepareDirectory(pruneExpiredFiles)) {
        return SlockThreadShareImageFailure(@"Storage unavailable");
    }
    NSString *fileName = [NSString stringWithFormat:@"raft-message-share-%lld-%@.png",
                                                       (long long)(NSDate.date.timeIntervalSince1970 * 1000.0),
                                                       NSUUID.UUID.UUIDString.lowercaseString];
    NSString *path = [SlockThreadShareImageDirectoryPath() stringByAppendingPathComponent:fileName];
    NSData *data = UIImagePNGRepresentation(image);
    if (data.length == 0 || ![data writeToFile:path options:NSDataWritingAtomic error:nil]) {
        return SlockThreadShareImageFailure(@"Unable to write image");
    }
    return @{
        @"status" : @"ok",
        @"uri" : [NSURL fileURLWithPath:path].absoluteString ?: @"",
        @"path" : path,
        @"fileName" : fileName,
        @"mimeType" : @"image/png",
        @"width" : @(CGImageGetWidth(cgImage)),
        @"height" : @(CGImageGetHeight(cgImage))
    };
}

static UIImage *SlockThreadShareCaptureView(UIView *view, NSDictionary *params) {
    if (!view || CGRectIsEmpty(view.bounds)) {
        return nil;
    }
    CGFloat scale = SlockThreadShareImageScale(view);
    CGFloat requestedX = [params[@"x"] respondsToSelector:@selector(doubleValue)] ? [params[@"x"] doubleValue] : 0;
    CGFloat requestedY = [params[@"y"] respondsToSelector:@selector(doubleValue)] ? [params[@"y"] doubleValue] : 0;
    CGFloat requestedWidth = [params[@"width"] respondsToSelector:@selector(doubleValue)] ? [params[@"width"] doubleValue] : 0;
    CGFloat requestedHeight = [params[@"height"] respondsToSelector:@selector(doubleValue)] ? [params[@"height"] doubleValue] : 0;
    // Compose layout coordinates are physical pixels. UIKit frames are points.
    CGRect requested = CGRectMake(
        MAX(0, requestedX) / scale,
        MAX(0, requestedY) / scale,
        requestedWidth > 0 ? requestedWidth / scale : CGRectGetWidth(view.bounds),
        requestedHeight > 0 ? requestedHeight / scale : CGRectGetHeight(view.bounds)
    );
    CGRect captureRect = CGRectIntersection(view.bounds, requested);
    if (CGRectIsNull(captureRect) || CGRectIsEmpty(captureRect) ||
        captureRect.size.width <= 0 || captureRect.size.height <= 0) {
        return nil;
    }
    [view layoutIfNeeded];
    UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
    format.scale = scale;
    format.opaque = YES;
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:captureRect.size format:format];
    return [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
        CGContextRef graphicsContext = context.CGContext;
        [[UIColor whiteColor] setFill];
        CGContextFillRect(graphicsContext, CGRectMake(0, 0, captureRect.size.width, captureRect.size.height));
        CGContextTranslateCTM(graphicsContext, -captureRect.origin.x, -captureRect.origin.y);
        BOOL rendered = [view drawViewHierarchyInRect:view.bounds afterScreenUpdates:YES];
        if (!rendered) {
            [view.layer renderInContext:graphicsContext];
        }
    }];
}

static UIFont *SlockThreadShareFont(NSString *name, CGFloat size, UIFontWeight fallbackWeight) {
    return [UIFont fontWithName:name size:size] ?: [UIFont systemFontOfSize:size weight:fallbackWeight];
}

static UIImage *SlockThreadShareFrameImage(UIImage *content) {
    CGImageRef contentCGImage = content.CGImage;
    if (!contentCGImage) {
        return nil;
    }
    CGFloat width = CGImageGetWidth(contentCGImage);
    CGFloat contentHeight = CGImageGetHeight(contentCGImage);
    if (width <= 0 || contentHeight <= 0) {
        return nil;
    }
    CGFloat headerHeight = MIN(96.0, MAX(72.0, round(width * 0.12)));
    CGSize outputSize = CGSizeMake(width, contentHeight + headerHeight);
    UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
    format.scale = 1.0;
    format.opaque = YES;
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:outputSize format:format];
    return [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
        CGContextRef graphicsContext = context.CGContext;
        [[UIColor whiteColor] setFill];
        CGContextFillRect(graphicsContext, CGRectMake(0, 0, outputSize.width, outputSize.height));
        [[UIColor colorWithRed:1.0 green:(216.0 / 255.0) blue:(77.0 / 255.0) alpha:1.0] setFill];
        CGContextFillRect(graphicsContext, CGRectMake(0, 0, width, headerHeight));

        CGFloat horizontalInset = MIN(38.0, MAX(26.0, width * 0.048));
        UIImage *logo = [UIImage imageNamed:@"lucide-raft-logo"];
        if (logo.size.width > 0 && logo.size.height > 0) {
            CGFloat maxLogoWidth = MIN(300.0, MAX(190.0, width * 0.36));
            CGFloat maxLogoHeight = MAX(48.0, headerHeight - 34.0);
            CGFloat logoScale = MIN(maxLogoWidth / logo.size.width, maxLogoHeight / logo.size.height);
            CGSize logoSize = CGSizeMake(logo.size.width * logoScale, logo.size.height * logoScale);
            [logo drawInRect:CGRectMake(horizontalInset,
                                        MAX(0, (headerHeight - logoSize.height) / 2.0),
                                        logoSize.width,
                                        logoSize.height)];
        } else {
            CGFloat titleSize = MIN(48.0, MAX(36.0, width * 0.07));
            NSDictionary *attributes = @{
                NSFontAttributeName : SlockThreadShareFont(@"SpaceGrotesk-Bold", titleSize, UIFontWeightBold),
                NSForegroundColorAttributeName : UIColor.blackColor
            };
            CGSize titleBounds = [@"RAFT" sizeWithAttributes:attributes];
            [@"RAFT" drawAtPoint:CGPointMake(horizontalInset, (headerHeight - titleBounds.height) / 2.0)
                   withAttributes:attributes];
        }

        CGFloat captionSize = MIN(26.0, MAX(18.0, width * 0.034));
        NSDictionary *captionAttributes = @{
            NSFontAttributeName : SlockThreadShareFont(@"SpaceGrotesk-SemiBold", captionSize, UIFontWeightSemibold),
            NSForegroundColorAttributeName : [UIColor colorWithRed:(24.0 / 255.0)
                                                              green:(24.0 / 255.0)
                                                               blue:(24.0 / 255.0)
                                                              alpha:1.0],
            NSKernAttributeName : @(captionSize * 0.045)
        };
        NSString *site = @"raft.build";
        CGSize siteBounds = [site sizeWithAttributes:captionAttributes];
        [site drawAtPoint:CGPointMake(width - siteBounds.width - horizontalInset,
                                      (headerHeight - siteBounds.height) / 2.0)
           withAttributes:captionAttributes];

        [content drawInRect:CGRectMake(0, headerHeight, width, contentHeight)];
        CGContextSetStrokeColorWithColor(graphicsContext, UIColor.blackColor.CGColor);
        CGContextSetLineWidth(graphicsContext, 3.0);
        CGContextMoveToPoint(graphicsContext, 0, headerHeight);
        CGContextAddLineToPoint(graphicsContext, width, headerHeight);
        CGContextStrokePath(graphicsContext);
        CGContextStrokeRect(graphicsContext, CGRectMake(1.5, 1.5, width - 3.0, outputSize.height - 3.0));
    }];
}

static UIImage *SlockThreadShareStitchFragments(NSArray<NSString *> *fragmentPaths) {
    NSMutableArray<UIImage *> *images = [NSMutableArray array];
    CGFloat width = 0;
    CGFloat height = 0;
    for (NSString *path in fragmentPaths) {
        UIImage *image = [UIImage imageWithContentsOfFile:path];
        CGImageRef cgImage = image.CGImage;
        if (!cgImage) {
            return nil;
        }
        width = MAX(width, CGImageGetWidth(cgImage));
        height += CGImageGetHeight(cgImage);
        [images addObject:image];
    }
    if (images.count == 0 || width <= 0 || height <= 0) {
        return nil;
    }
    UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
    format.scale = 1.0;
    format.opaque = YES;
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:CGSizeMake(width, height) format:format];
    return [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
        [[UIColor whiteColor] setFill];
        CGContextFillRect(context.CGContext, CGRectMake(0, 0, width, height));
        CGFloat top = 0;
        for (UIImage *image in images) {
            CGFloat imageWidth = CGImageGetWidth(image.CGImage);
            CGFloat imageHeight = CGImageGetHeight(image.CGImage);
            [image drawInRect:CGRectMake(MAX(0, (width - imageWidth) / 2.0), top, imageWidth, imageHeight)];
            top += imageHeight;
        }
    }];
}

// The iOS Hands SDK intentionally does not expose the diagnostics presign path.  Keep the
// optional WebView upload self-contained: generate a small store-only ZIP, ask Hands for one
// signed object, then stream that object with URLSession.  This avoids putting access tokens or
// raw diagnostics bytes through the JavaScript bridge.
static uint32_t SlockFeedbackDiagnosticsCRC32(NSData *data) {
    static uint32_t table[256];
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        for (uint32_t index = 0; index < 256; index++) {
            uint32_t value = index;
            for (NSUInteger bit = 0; bit < 8; bit++) {
                value = (value & 1U) ? ((value >> 1U) ^ 0xedb88320U) : (value >> 1U);
            }
            table[index] = value;
        }
    });
    uint32_t crc = 0xffffffffU;
    const uint8_t *bytes = data.bytes;
    for (NSUInteger index = 0; index < data.length; index++) {
        crc = table[(crc ^ bytes[index]) & 0xffU] ^ (crc >> 8U);
    }
    return crc ^ 0xffffffffU;
}

static void SlockFeedbackDiagnosticsWriteU16(NSOutputStream *stream, uint16_t value) {
    uint8_t bytes[] = { (uint8_t)(value & 0xffU), (uint8_t)((value >> 8U) & 0xffU) };
    [stream write:bytes maxLength:sizeof(bytes)];
}

static void SlockFeedbackDiagnosticsWriteU32(NSOutputStream *stream, uint32_t value) {
    uint8_t bytes[] = {
        (uint8_t)(value & 0xffU), (uint8_t)((value >> 8U) & 0xffU),
        (uint8_t)((value >> 16U) & 0xffU), (uint8_t)((value >> 24U) & 0xffU)
    };
    [stream write:bytes maxLength:sizeof(bytes)];
}

static BOOL SlockFeedbackDiagnosticsWriteBytes(NSOutputStream *stream, NSData *data) {
    const uint8_t *bytes = data.bytes;
    NSUInteger remaining = data.length;
    while (remaining > 0) {
        NSInteger written = [stream write:bytes maxLength:MIN(remaining, (NSUInteger)64 * 1024)];
        if (written <= 0) return NO;
        bytes += written;
        remaining -= (NSUInteger)written;
    }
    return YES;
}

static NSString *SlockFeedbackDiagnosticsSafeEntryName(NSString *name, NSUInteger index) {
    NSString *leaf = name.lastPathComponent;
    if (leaf.length == 0) leaf = [NSString stringWithFormat:@"diagnostics-%lu.log", (unsigned long)index];
    NSCharacterSet *allowed = [NSCharacterSet characterSetWithCharactersInString:
        @"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"];
    NSMutableString *safe = [NSMutableString stringWithCapacity:leaf.length];
    for (NSUInteger offset = 0; offset < leaf.length; offset++) {
        unichar character = [leaf characterAtIndex:offset];
        [safe appendFormat:@"%C", [allowed characterIsMember:character] ? character : '_'];
    }
    if (safe.length == 0) safe = [NSMutableString stringWithFormat:@"diagnostics-%lu.log", (unsigned long)index];
    return [NSString stringWithFormat:@"%02lu-%@", (unsigned long)index, safe];
}

static NSDictionary *SlockFeedbackDiagnosticsWriteArchive(BOOL (^isCancelled)(void), NSError **error) {
    NSDictionary *about = SlockDiagnosticsCurrentEnvironmentAbout();
    NSDictionary *prepared = SlockDiagnosticsPrepareAttachmentFiles(about, @"settings_feedback_web", NO);
    NSArray<NSString *> *paths = [prepared[@"attachmentPaths"] isKindOfClass:NSArray.class]
        ? prepared[@"attachmentPaths"] : @[];
    if (![SlockBridgeString(prepared[@"status"]) isEqualToString:@"ok"] || paths.count == 0) {
        if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:1 userInfo:@{
            NSLocalizedDescriptionKey : @"Unable to create diagnostics snapshot"
        }];
        return nil;
    }
    NSString *name = [NSString stringWithFormat:@"raft-feedback-diagnostics-%@.zip", NSUUID.UUID.UUIDString.lowercaseString];
    NSString *path = [NSTemporaryDirectory() stringByAppendingPathComponent:name];
    NSOutputStream *stream = [NSOutputStream outputStreamToFileAtPath:path append:NO];
    [stream open];
    if (stream.streamStatus == NSStreamStatusError || stream.streamStatus == NSStreamStatusClosed) {
        if (error) *error = stream.streamError ?: [NSError errorWithDomain:@"raft.feedback.diagnostics" code:2 userInfo:nil];
        return nil;
    }
    NSMutableArray<NSDictionary *> *entries = [NSMutableArray arrayWithCapacity:paths.count];
    unsigned long long offset = 0;
    BOOL success = YES;
    for (NSUInteger index = 0; index < paths.count; index++) {
        if (isCancelled && isCancelled()) { success = NO; break; }
        NSString *sourcePath = SlockBridgeString(paths[index]);
        unsigned long long sourceSize = [[[NSFileManager defaultManager] attributesOfItemAtPath:sourcePath error:nil][NSFileSize] unsignedLongLongValue];
        // Reject oversized inputs before mapping/allocating their bytes. The fixed ZIP headers
        // and central directory are covered by the conservative 1 KiB allowance and the final
        // archive-size check below remains authoritative.
        if (sourceSize == 0 || sourceSize + offset + 1024ULL > SlockFeedbackDiagnosticsMaxBytes) {
            if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:3 userInfo:@{
                NSLocalizedDescriptionKey : @"Diagnostics archive exceeds upload limit",
                @"tooLarge" : @YES
            }];
            success = NO;
            break;
        }
        NSData *data = [NSData dataWithContentsOfFile:sourcePath options:NSDataReadingMappedIfSafe error:nil];
        if (!data) { success = NO; break; }
        NSString *entryName = index == 0 ? @"diagnostics-summary.txt" : SlockFeedbackDiagnosticsSafeEntryName(sourcePath, index);
        NSData *entryNameData = [entryName dataUsingEncoding:NSUTF8StringEncoding];
        if (entryNameData.length > UINT16_MAX || data.length > UINT32_MAX ||
            data.length + offset + 1024ULL > SlockFeedbackDiagnosticsMaxBytes) {
            if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:3 userInfo:@{
                NSLocalizedDescriptionKey : @"Diagnostics archive exceeds upload limit",
                @"tooLarge" : @YES
            }];
            success = NO;
            break;
        }
        uint32_t crc = SlockFeedbackDiagnosticsCRC32(data);
        SlockFeedbackDiagnosticsWriteU32(stream, 0x04034b50U);
        SlockFeedbackDiagnosticsWriteU16(stream, 20); // version needed
        SlockFeedbackDiagnosticsWriteU16(stream, 0);  // flags
        SlockFeedbackDiagnosticsWriteU16(stream, 0);  // store method
        SlockFeedbackDiagnosticsWriteU16(stream, 0);  // mtime
        SlockFeedbackDiagnosticsWriteU16(stream, 0);  // mdate
        SlockFeedbackDiagnosticsWriteU32(stream, crc);
        SlockFeedbackDiagnosticsWriteU32(stream, (uint32_t)data.length);
        SlockFeedbackDiagnosticsWriteU32(stream, (uint32_t)data.length);
        SlockFeedbackDiagnosticsWriteU16(stream, (uint16_t)entryNameData.length);
        SlockFeedbackDiagnosticsWriteU16(stream, 0);
        if (!SlockFeedbackDiagnosticsWriteBytes(stream, entryNameData) ||
            !SlockFeedbackDiagnosticsWriteBytes(stream, data)) { success = NO; break; }
        [entries addObject:@{ @"name" : entryNameData, @"crc" : @(crc), @"size" : @((uint32_t)data.length), @"offset" : @(offset) }];
        offset += 30ULL + entryNameData.length + data.length;
    }
    if (success) {
        unsigned long long centralOffset = offset;
        for (NSDictionary *entry in entries) {
            NSData *entryNameData = entry[@"name"];
            SlockFeedbackDiagnosticsWriteU32(stream, 0x02014b50U);
            SlockFeedbackDiagnosticsWriteU16(stream, 20);
            SlockFeedbackDiagnosticsWriteU16(stream, 20);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU32(stream, [entry[@"crc"] unsignedIntValue]);
            SlockFeedbackDiagnosticsWriteU32(stream, [entry[@"size"] unsignedIntValue]);
            SlockFeedbackDiagnosticsWriteU32(stream, [entry[@"size"] unsignedIntValue]);
            SlockFeedbackDiagnosticsWriteU16(stream, (uint16_t)entryNameData.length);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU16(stream, 0);
            SlockFeedbackDiagnosticsWriteU32(stream, 0);
            SlockFeedbackDiagnosticsWriteU32(stream, [entry[@"offset"] unsignedIntValue]);
            SlockFeedbackDiagnosticsWriteBytes(stream, entryNameData);
            offset += 46ULL + entryNameData.length;
        }
        unsigned long long centralSize = offset - centralOffset;
        SlockFeedbackDiagnosticsWriteU32(stream, 0x06054b50U);
        SlockFeedbackDiagnosticsWriteU16(stream, 0);
        SlockFeedbackDiagnosticsWriteU16(stream, 0);
        SlockFeedbackDiagnosticsWriteU16(stream, (uint16_t)entries.count);
        SlockFeedbackDiagnosticsWriteU16(stream, (uint16_t)entries.count);
        SlockFeedbackDiagnosticsWriteU32(stream, (uint32_t)centralSize);
        SlockFeedbackDiagnosticsWriteU32(stream, (uint32_t)centralOffset);
        SlockFeedbackDiagnosticsWriteU16(stream, 0);
    }
    [stream close];
    if (!success || ![[NSFileManager defaultManager] fileExistsAtPath:path]) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        if (error && !*error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:4 userInfo:nil];
        return nil;
    }
    unsigned long long size = [[[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil][NSFileSize] unsignedLongLongValue];
    if (size == 0 || size > SlockFeedbackDiagnosticsMaxBytes) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:3 userInfo:@{ @"tooLarge" : @YES }];
        return nil;
    }
    return @{ @"path" : path, @"fileName" : name, @"size" : @(size) };
}

@interface SlockFeedbackDiagnosticsUploadOperation : NSObject <NSURLSessionTaskDelegate, NSURLSessionDataDelegate, NSURLSessionUploadDelegate>
@property(nonatomic, copy) NSString *requestId;
@property(nonatomic, copy) NSString *accountId;
@property(nonatomic) long long sessionGeneration;
@property(nonatomic) long long webViewGeneration;
@property(nonatomic, copy) KuiklyRenderCallback callback;
@property(nonatomic, strong) NSURLSession *session;
@property(nonatomic, strong) NSURLSessionUploadTask *uploadTask;
@property(nonatomic, strong) NSURLSessionDataTask *presignTask;
@property(nonatomic, copy) NSString *archivePath;
@property(nonatomic, copy) NSString *fileName;
@property(nonatomic) unsigned long long fileSize;
@property(nonatomic, copy) NSString *r2Key;
@property(nonatomic, strong) NSRecursiveLock *lock;
@property(nonatomic) BOOL cancelled;
@property(nonatomic) BOOL terminal;
@property(nonatomic, copy) void (^onTerminal)(SlockFeedbackDiagnosticsUploadOperation *operation);
- (instancetype)initWithRequestId:(NSString *)requestId binding:(NSDictionary *)binding callback:(KuiklyRenderCallback)callback;
- (void)start;
- (void)cancel;
@end


@implementation SlockFeedbackDiagnosticsUploadOperation {
    NSMutableData *_presignData;
    NSInteger _presignStatus;
    NSInteger _uploadStatus;
}

- (instancetype)initWithRequestId:(NSString *)requestId binding:(NSDictionary *)binding callback:(KuiklyRenderCallback)callback {
    if (self = [super init]) {
        _requestId = [requestId copy];
        _accountId = [SlockBridgeString(binding[@"accountId"]) copy];
        _sessionGeneration = [binding[@"sessionGeneration"] longLongValue];
        _webViewGeneration = [binding[@"webViewGeneration"] longLongValue];
        _callback = [callback copy];
        _lock = [[NSRecursiveLock alloc] init];
        _presignData = [NSMutableData data];
    }
    return self;
}

- (NSDictionary *)binding {
    return @{ @"accountId" : self.accountId ?: @"",
              @"sessionGeneration" : @(self.sessionGeneration),
              @"webViewGeneration" : @(self.webViewGeneration) };
}

- (void)emit:(NSDictionary *)payload terminal:(BOOL)terminal {
    [self.lock lock];
    if (self.cancelled || self.terminal) { [self.lock unlock]; return; }
    if (terminal) self.terminal = YES;
    KuiklyRenderCallback callback = self.callback;
    void (^onTerminal)(SlockFeedbackDiagnosticsUploadOperation *) = terminal ? self.onTerminal : nil;
    [self.lock unlock];
    if (onTerminal) onTerminal(self);
    NSMutableDictionary *event = [payload mutableCopy];
    event[@"status"] = @"ok";
    event[@"requestId"] = self.requestId ?: @"";
    event[@"binding"] = [self binding];
    event[@"terminal"] = @(terminal);
    __weak typeof(self) weakSelf = self;
    SlockBridgeRunOnMain(^{
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) return;
        [self.lock lock];
        BOOL invalidated = self.cancelled;
        [self.lock unlock];
        if (!invalidated && callback) callback(event);
    });
}

- (void)emitFailure:(NSString *)reason {
    [self emit:@{ @"type" : @"failed", @"reason" : reason ?: @"upload_failed" } terminal:YES];
    NSString *path = self.archivePath;
    if (path.length > 0) [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    [self.session finishTasksAndInvalidate];
}

- (void)start {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        NSError *archiveError = nil;
        NSDictionary *archive = SlockFeedbackDiagnosticsWriteArchive(^{
            [self.lock lock];
            BOOL cancelled = self.cancelled;
            [self.lock unlock];
            return cancelled;
        }, &archiveError);
        if (!archive) {
            [self emitFailure:archiveError.userInfo[@"tooLarge"] ? @"too_large" : @"generate_failed"];
            return;
        }
        [self.lock lock];
        if (self.cancelled) { [self.lock unlock]; [[NSFileManager defaultManager] removeItemAtPath:archive[@"path"] error:nil]; return; }
        self.archivePath = archive[@"path"];
        self.fileName = archive[@"fileName"];
        self.fileSize = [archive[@"size"] unsignedLongLongValue];
        [self.lock unlock];
        NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"%@/public/v2/apps/%@/feedback/presign", SlockHandsFeedbackBaseURL, SlockHandsFeedbackAppSlug]];
        NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
        request.HTTPMethod = @"POST";
        [request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
        [request setValue:@"application/json" forHTTPHeaderField:@"Accept"];
        [request setValue:SlockHandsFeedbackClientKey forHTTPHeaderField:@"X-Hands-Client-Key"];
        NSDictionary *body = @{ @"files" : @[ @{ @"filename" : self.fileName ?: @"diagnostics.zip",
                                                 @"content_type" : SlockFeedbackDiagnosticsContentType,
                                                 @"size" : @(self.fileSize) } ] };
        request.HTTPBody = [NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
        NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        configuration.timeoutIntervalForRequest = 30.0;
        configuration.timeoutIntervalForResource = 600.0;
        self.session = [NSURLSession sessionWithConfiguration:configuration delegate:self delegateQueue:nil];
        self.presignTask = [self.session dataTaskWithRequest:request];
        [self.presignTask resume];
    });
}

- (void)cancel {
    [self.lock lock];
    self.cancelled = YES;
    self.callback = nil;
    NSURLSessionTask *presignTask = self.presignTask;
    NSURLSessionTask *uploadTask = self.uploadTask;
    [self.lock unlock];
    [presignTask cancel];
    [uploadTask cancel];
    [self.session invalidateAndCancel];
    if (self.archivePath.length > 0) [[NSFileManager defaultManager] removeItemAtPath:self.archivePath error:nil];
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveResponse:(NSURLResponse *)response completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
    if (task == self.presignTask) {
        _presignStatus = [(NSHTTPURLResponse *)response statusCode];
        [_presignData setLength:0];
    } else if (task == self.uploadTask) {
        _uploadStatus = [(NSHTTPURLResponse *)response statusCode];
    }
    completionHandler(NSURLSessionResponseAllow);
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveData:(NSData *)data {
    if (task == self.presignTask) [_presignData appendData:data];
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    if (task == self.presignTask) {
        if (error || _presignStatus < 200 || _presignStatus >= 300) { [self emitFailure:@"upload_failed"]; return; }
        NSDictionary *json = [NSJSONSerialization JSONObjectWithData:_presignData options:0 error:nil];
        NSArray *uploads = [json isKindOfClass:NSDictionary.class] && [json[@"uploads"] isKindOfClass:NSArray.class] ? json[@"uploads"] : @[];
        NSDictionary *upload = uploads.count == 1 && [uploads.firstObject isKindOfClass:NSDictionary.class] ? uploads.firstObject : nil;
        NSString *attachmentId = SlockBridgeString(upload[@"attachment_id"]);
        NSString *uploadURL = SlockBridgeString(upload[@"upload_url"]);
        self.r2Key = SlockBridgeString(upload[@"r2_key"]);
        long long expiresAt = [upload[@"expires_at"] longLongValue];
        if (attachmentId.length == 0 || uploadURL.length == 0 || self.r2Key.length == 0 || expiresAt <= 0) {
            [self emitFailure:@"upload_failed"];
            return;
        }
        NSMutableURLRequest *put = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:uploadURL]];
        put.HTTPMethod = @"PUT";
        [put setValue:SlockFeedbackDiagnosticsContentType forHTTPHeaderField:@"Content-Type"];
        self.uploadTask = [self.session uploadTaskWithRequest:put fromFile:[NSURL fileURLWithPath:self.archivePath]];
        [self.uploadTask resume];
        return;
    }
    if (task == self.uploadTask) {
        NSString *path = self.archivePath;
        if (error || _uploadStatus < 200 || _uploadStatus >= 300) {
            [self emitFailure:@"upload_failed"];
        } else {
            [self emit:@{ @"type" : @"done", @"ref" : @{ @"r2_key" : self.r2Key ?: @"",
                @"filename" : self.fileName ?: @"diagnostics.zip", @"content_type" : SlockFeedbackDiagnosticsContentType,
                @"size" : @(self.fileSize) } } terminal:YES];
        }
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        [self.session finishTasksAndInvalidate];
    }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task
 didSendBodyData:(int64_t)bytesSent totalBytesSent:(int64_t)totalBytesSent totalBytesExpectedToSend:(int64_t)totalBytesExpectedToSend {
    if (task != self.uploadTask || totalBytesExpectedToSend <= 0) return;
    [self emit:@{ @"type" : @"progress", @"sent" : @(totalBytesSent), @"total" : @(totalBytesExpectedToSend) } terminal:NO];
}

@end

@interface HRBridgeModule () <PHPickerViewControllerDelegate, UIDocumentPickerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate, SharedAttachmentDownloadObserver>

@property(nonatomic, copy, nullable) KuiklyRenderCallback slock_attachmentExportCallback;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportRequestId;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportDownloadId;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportOperationId;
@property(nonatomic, strong, nullable) NSURL *slock_attachmentExportURL;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportFileName;
@property(nonatomic, strong, nullable) NSNumber *slock_attachmentExportSizeBytes;
@property(nonatomic) BOOL slock_attachmentExportTrackProgress;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportDestination;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportMimeType;
@property(nonatomic, copy, nullable) NSString *slock_attachmentExportPermissionStatus;
@property(nonatomic, weak, nullable) UIViewController *slock_sharePresenterController;
@property(nonatomic, copy, nullable) KuiklyRenderCallback slock_attachmentPickerCallback;
@property(nonatomic, copy, nullable) NSString *slock_attachmentPickerChannelId;
@property(nonatomic) BOOL slock_attachmentPickerCompressImages;
@property(nonatomic, strong) NSMutableDictionary<NSString *, SlockThreadImageCompressionOperation *> *slock_threadImageCompressionOperations;
@property(nonatomic, strong) NSRecursiveLock *slock_threadImageCompressionOperationsLock;
@property(nonatomic) BOOL slock_threadImageCompressionDestroyed;
@property(nonatomic, strong) NSMutableDictionary<NSString *, SlockFeedbackDiagnosticsUploadOperation *> *slock_feedbackDiagnosticsOperations;
@property(nonatomic, strong) NSRecursiveLock *slock_feedbackDiagnosticsOperationsLock;
@property(nonatomic) BOOL slock_feedbackDiagnosticsDestroyed;

- (void)slock_finishAttachmentExport:(NSDictionary *)result;
- (void)slock_startAttachmentExport:(NSDictionary *)args destination:(NSString *)destination;
- (void)slock_beginAttachmentExportDownloadWithParams:(NSDictionary *)params;
- (void)slock_saveAttachmentExportToPhotos;
- (void)slock_finishAttachmentPicker:(NSDictionary *)result;
- (void)slock_presentPhotoPicker;
- (void)slock_requestAndPresentCamera;
- (void)slock_presentCamera;
- (void)slock_presentDocumentPicker;
- (void)slock_processPhotoPickerResults:(NSArray<PHPickerResult *> *)results;
- (void)slock_processDocumentPickerURLs:(NSArray<NSURL *> *)urls;
- (void)slock_finishAttachmentPickerFiles:(NSArray<NSDictionary *> *)files
                                    errors:(NSArray<NSDictionary *> *)errors;
- (nullable NSDictionary *)slock_cachePickedAttachmentURL:(NSURL *)sourceURL
                                            suggestedName:(nullable NSString *)suggestedName
                                                 mimeType:(nullable NSString *)mimeType
                                        compressImages:(BOOL)compressImages
                                                    error:(NSError * _Nullable * _Nullable)error;
- (void)compressThreadImage:(NSDictionary *)args;
- (void)cancelCompressThreadImage:(NSDictionary *)args;
- (BOOL)slock_finishThreadImageCompressionOperation:(SlockThreadImageCompressionOperation *)operation
                                             payload:(NSDictionary *)payload;
- (void)slock_removeThreadImageCompressionOperation:(SlockThreadImageCompressionOperation *)operation;
- (BOOL)slock_isThreadImageCompressionDestroyed;
- (BOOL)slock_isCurrentThreadImageCompressionOperation:(SlockThreadImageCompressionOperation *)operation;
- (void)slock_dispatchThreadImageCompressionFailure:(KuiklyRenderCallback)callback;
- (void)slock_startFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(KuiklyRenderCallback)callback;
- (void)slock_cancelFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(KuiklyRenderCallback)callback;
- (void)slock_cancelAllFeedbackDiagnosticsUploads;
- (void)slock_destroyFeedbackDiagnosticsUploads;

@end

// 扩展桥接接口
/*
 * @brief Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */

@implementation HRBridgeModule

@synthesize hr_rootView;

- (instancetype)init {
    self = [super init];
    if (self) {
        _slock_threadImageCompressionOperations = [NSMutableDictionary dictionary];
        _slock_threadImageCompressionOperationsLock = [[NSRecursiveLock alloc] init];
        _slock_threadImageCompressionDestroyed = NO;
        _slock_feedbackDiagnosticsOperations = [NSMutableDictionary dictionary];
        _slock_feedbackDiagnosticsOperationsLock = [[NSRecursiveLock alloc] init];
        _slock_feedbackDiagnosticsDestroyed = NO;
    }
    return self;
}

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
    [self slock_cancelAllFeedbackDiagnosticsUploads];
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

- (void)adaptiveNavigation:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    SlockBridgeRunOnMain(^{
        UIViewController *controller = self.hr_rootView.kr_viewController;
        NSDictionary *result;
        if ([controller isKindOfClass:KuiklyRenderViewController.class]) {
            result = [(KuiklyRenderViewController *)controller slockAdaptiveNavigation:params root:self.hr_rootView];
        } else {
            result = @{ @"requestId": SlockBridgeString(params[@"requestId"]),
                        @"status": @"rejected", @"reason": @"unsupported_source" };
        }
        // Even a failed stack readback may follow removal. Commit never calls a source callback.
        // Shared receives the target event, or retains state on timeout if no target survived.
        if (callback && ![SlockBridgeString(params[@"action"]) isEqual:@"commit"]) callback(result);
    });
}

- (void)conversationForwardGesture:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    BOOL enabled = SlockBridgeBool(params[@"enabled"]);
    SlockBridgeRunOnMain(^{
        // This module belongs to the AppShell render root. Do not follow a presented modal to
        // decide ownership; modal presence is a separate gesture-admission veto.
        UIViewController *controller = self.hr_rootView.kr_viewController;
        if ([controller isKindOfClass:UINavigationController.class]) {
            controller = ((UINavigationController *)controller).topViewController;
        }
        if ([controller isKindOfClass:KuiklyRenderViewController.class]) {
            KuiklyRenderViewController *renderController = (KuiklyRenderViewController *)controller;
            if (renderController.slockIsAppShellPage) {
                renderController.slockConversationForwardGestureEnabled = enabled;
            }
        }
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

- (void)exportThreadShareImage:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    NSDictionary *params = SlockBridgeParams(args);
    SlockBridgeRunOnMain(^{
        NSString *fragmentValue = SlockBridgeRawString(params[@"fragments"]);
        NSMutableArray<NSString *> *fragmentPaths = [NSMutableArray array];
        for (NSString *line in [fragmentValue componentsSeparatedByCharactersInSet:NSCharacterSet.newlineCharacterSet]) {
            NSString *path = SlockBridgeString(line);
            if (path.length > 0) {
                [fragmentPaths addObject:path];
            }
        }
        UIImage *image = nil;
        BOOL frame = params[@"frame"] == nil || SlockBridgeBool(params[@"frame"]);
        if (fragmentPaths.count > 0) {
            image = SlockThreadShareStitchFragments(fragmentPaths);
            frame = YES;
        } else {
            image = SlockThreadShareCaptureView(self.hr_rootView, params);
        }
        if (!image) {
            callback(SlockThreadShareImageFailure(@"Unable to capture image"));
            return;
        }
        UIImage *output = frame ? SlockThreadShareFrameImage(image) : image;
        if (!output) {
            callback(SlockThreadShareImageFailure(@"Unable to frame image"));
            return;
        }
        callback(SlockThreadShareWriteImage(output, frame));
    });
}

- (void)probeThreadViewportPixels:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (callback) {
        callback(@{
            @"status": @"unsupported",
            @"generation": args[@"generation"] ?: @0
        });
    }
}

- (void)saveThreadShareImage:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    NSDictionary *params = SlockBridgeParams(args);
    NSString *path = SlockBridgeString(params[@"path"]);
    BOOL isDirectory = NO;
    if (path.length == 0 ||
        ![NSFileManager.defaultManager fileExistsAtPath:path isDirectory:&isDirectory] ||
        isDirectory ||
        [[NSFileManager.defaultManager attributesOfItemAtPath:path error:nil][NSFileSize] unsignedLongLongValue] == 0) {
        callback(SlockThreadShareImageFailure(@"Image file unavailable"));
        return;
    }
    NSURL *fileURL = [NSURL fileURLWithPath:path];
    void (^saveImage)(void) = ^{
        [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
            [PHAssetChangeRequest creationRequestForAssetFromImageAtFileURL:fileURL];
        } completionHandler:^(BOOL success, NSError *error) {
            SlockBridgeRunOnMain(^{
                if (success) {
                    callback(@{ @"status" : @"ok", @"uri" : fileURL.absoluteString ?: @"" });
                } else {
                    callback(SlockThreadShareImageFailure(error.localizedDescription ?: @"Unable to save image"));
                }
            });
        }];
    };
    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelAddOnly];
    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) {
        saveImage();
        return;
    }
    if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
        callback(SlockThreadShareImageFailure(@"Photos access is unavailable"));
        return;
    }
    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus authorizationStatus) {
        SlockBridgeRunOnMain(^{
            if (authorizationStatus == PHAuthorizationStatusAuthorized ||
                authorizationStatus == PHAuthorizationStatusLimited) {
                saveImage();
            } else {
                callback(SlockThreadShareImageFailure(@"Photos access is unavailable"));
            }
        });
    }];
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

- (void)syncDesktopShortcuts:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *rawShortcuts = SlockBridgeString(params[@"shortcuts"]);
    SlockBridgeRunOnMain(^{
        if (rawShortcuts.length == 0) {
            [UIApplication sharedApplication].shortcutItems = @[];
            NSLog(@"[%@][info] syncDesktopShortcuts cleared", SlockBridgeDefaultLogTag);
            return;
        }
        NSData *data = [rawShortcuts dataUsingEncoding:NSUTF8StringEncoding];
        if (!data) {
            return;
        }
        NSError *error = nil;
        id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&error];
        if (error || ![json isKindOfClass:[NSArray class]]) {
            NSLog(@"[%@][warn] syncDesktopShortcuts JSON parse failed: %@", SlockBridgeDefaultLogTag, error);
            return;
        }
        NSArray *items = (NSArray *)json;
        NSMutableArray<UIApplicationShortcutItem *> *shortcutItems = [NSMutableArray arrayWithCapacity:items.count];
        for (id rawItem in items) {
            if (![rawItem isKindOfClass:[NSDictionary class]]) continue;
            NSDictionary *item = (NSDictionary *)rawItem;
            NSString *type = SlockBridgeString(item[@"id"]);
            NSString *title = SlockBridgeString(item[@"title"]);
            NSString *subtitle = SlockBridgeString(item[@"subtitle"]);
            NSString *serverId = SlockBridgeString(item[@"serverId"]);
            NSString *targetId = SlockBridgeString(item[@"targetId"]);
            NSString *targetKind = SlockBridgeString(item[@"targetKind"]);

            if (type.length == 0 || title.length == 0 || targetId.length == 0 || serverId.length == 0) {
                continue;
            }

            NSDictionary *userInfo = @{
                @"slockShortcutServerId": serverId,
                @"slockShortcutTargetId": targetId,
                @"slockShortcutTargetKind": targetKind.length > 0 ? targetKind : @"channel",
                @"slockShortcutTitle": title
            };

            UIApplicationShortcutIcon *icon = [UIApplicationShortcutIcon iconWithType:UIApplicationShortcutIconTypeCompose];
            UIApplicationShortcutItem *shortcutItem =
                [[UIApplicationShortcutItem alloc] initWithType:type
                                                 localizedTitle:title
                                              localizedSubtitle:subtitle.length > 0 ? subtitle : nil
                                                           icon:icon
                                                       userInfo:userInfo];
            [shortcutItems addObject:shortcutItem];
            if (shortcutItems.count >= 4) {
                break;
            }
        }
        [UIApplication sharedApplication].shortcutItems = shortcutItems;
        NSLog(@"[%@][info] syncDesktopShortcuts updated count=%lu", SlockBridgeDefaultLogTag, (unsigned long)shortcutItems.count);
    });
}

- (void)avatarCacheCapability:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) return;
    [[SlockIosAvatarCacheHost shared] handleLegacyCommand:params completion:^(NSDictionary<NSString *, id> *result) {
        callback(result);
    }];
}

- (void)settingsCapability:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    NSString *action = SlockBridgeString(params[@"action"]);
    if ([action isEqualToString:@"feedbackDiagnosticsStart"]) {
        [self slock_startFeedbackDiagnosticsUpload:params callback:callback];
        return;
    }
    if ([action isEqualToString:@"feedbackDiagnosticsCancel"]) {
        [self slock_cancelFeedbackDiagnosticsUpload:params callback:callback];
        return;
    }
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
    if ([action isEqualToString:@"promoteFeedbackDraftAttachments"] ||
        [action isEqualToString:@"discardFeedbackDraftAttachments"]) {
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            NSDictionary *result = [action isEqualToString:@"promoteFeedbackDraftAttachments"]
                ? [self slock_promoteFeedbackDraftAttachments:params]
                : [self slock_discardFeedbackDraftAttachments:params];
            SlockBridgeRunOnMain(^{ callback(result); });
        });
        return;
    }
    callback([self slock_settingsCapabilityResultForAction:action params:params]);
}

static BOOL SlockFeedbackDiagnosticsValidRequest(NSString *requestId, NSDictionary *binding) {
    NSCharacterSet *allowed = [NSCharacterSet characterSetWithCharactersInString:@"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._:-"];
    if (requestId.length == 0 || requestId.length > 128 ||
        [requestId rangeOfCharacterFromSet:allowed.invertedSet].location != NSNotFound) return NO;
    NSString *account = SlockBridgeString(binding[@"accountId"]);
    long long sessionGeneration = [binding[@"sessionGeneration"] longLongValue];
    long long webViewGeneration = [binding[@"webViewGeneration"] longLongValue];
    return account.length > 0 && account.length <= 256 &&
        [account rangeOfCharacterFromSet:NSCharacterSet.newlineCharacterSet].location == NSNotFound &&
        sessionGeneration >= 0 && webViewGeneration >= 0;
}

static NSDictionary *SlockFeedbackDiagnosticsBindingPayload(NSDictionary *binding) {
    return @{ @"accountId" : SlockBridgeString(binding[@"accountId"]),
              @"sessionGeneration" : @([binding[@"sessionGeneration"] longLongValue]),
              @"webViewGeneration" : @([binding[@"webViewGeneration"] longLongValue]) };
}

- (void)slock_startFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(KuiklyRenderCallback)callback {
    NSString *requestId = SlockBridgeString(params[@"requestId"]);
    NSDictionary *binding = [params isKindOfClass:NSDictionary.class] && [params[@"binding"] isKindOfClass:NSDictionary.class]
        ? params[@"binding"] : params;
    if (!callback || !SlockFeedbackDiagnosticsValidRequest(requestId, binding)) return;
    [self.slock_feedbackDiagnosticsOperationsLock lock];
    if (self.slock_feedbackDiagnosticsDestroyed || self.slock_feedbackDiagnosticsOperations[requestId] != nil) {
        [self.slock_feedbackDiagnosticsOperationsLock unlock];
        callback(@{ @"status" : @"ok", @"requestId" : requestId, @"binding" : SlockFeedbackDiagnosticsBindingPayload(binding),
                    @"type" : @"failed", @"reason" : @"generate_failed", @"terminal" : @YES });
        return;
    }
    // A page replacement can start a new request before the old WebView's cancellation message
    // arrives.  Retire every older native operation first; only one upload may own this bridge.
    NSArray<SlockFeedbackDiagnosticsUploadOperation *> *staleOperations =
        self.slock_feedbackDiagnosticsOperations.allValues.copy;
    [self.slock_feedbackDiagnosticsOperations removeAllObjects];
    SlockFeedbackDiagnosticsUploadOperation *operation =
        [[SlockFeedbackDiagnosticsUploadOperation alloc] initWithRequestId:requestId binding:binding callback:callback];
    __weak typeof(self) weakSelf = self;
    operation.onTerminal = ^(SlockFeedbackDiagnosticsUploadOperation *finished) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) return;
        [self.slock_feedbackDiagnosticsOperationsLock lock];
        if (self.slock_feedbackDiagnosticsOperations[finished.requestId] == finished) {
            [self.slock_feedbackDiagnosticsOperations removeObjectForKey:finished.requestId];
        }
        [self.slock_feedbackDiagnosticsOperationsLock unlock];
    };
    self.slock_feedbackDiagnosticsOperations[requestId] = operation;
    [self.slock_feedbackDiagnosticsOperationsLock unlock];
    for (SlockFeedbackDiagnosticsUploadOperation *stale in staleOperations) [stale cancel];
    [operation start];
}

- (void)slock_cancelFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(KuiklyRenderCallback)callback {
    NSString *requestId = SlockBridgeString(params[@"requestId"]);
    NSDictionary *binding = [params isKindOfClass:NSDictionary.class] && [params[@"binding"] isKindOfClass:NSDictionary.class]
        ? params[@"binding"] : params;
    [self.slock_feedbackDiagnosticsOperationsLock lock];
    SlockFeedbackDiagnosticsUploadOperation *operation = self.slock_feedbackDiagnosticsOperations[requestId];
    BOOL matches = operation != nil && [operation.accountId isEqualToString:SlockBridgeString(binding[@"accountId"])] &&
        operation.sessionGeneration == [binding[@"sessionGeneration"] longLongValue] &&
        operation.webViewGeneration == [binding[@"webViewGeneration"] longLongValue];
    if (matches) [self.slock_feedbackDiagnosticsOperations removeObjectForKey:requestId];
    [self.slock_feedbackDiagnosticsOperationsLock unlock];
    if (matches) [operation cancel];
    if (callback) callback(@{ @"status" : matches ? @"ok" : @"ignored", @"requestId" : requestId,
                              @"binding" : SlockFeedbackDiagnosticsBindingPayload(binding ?: @{}),
                              @"cancelled" : @(matches), @"terminal" : @YES });
}

- (void)slock_cancelAllFeedbackDiagnosticsUploads {
    [self.slock_feedbackDiagnosticsOperationsLock lock];
    NSArray *operations = self.slock_feedbackDiagnosticsOperations.allValues.copy;
    [self.slock_feedbackDiagnosticsOperations removeAllObjects];
    [self.slock_feedbackDiagnosticsOperationsLock unlock];
    for (SlockFeedbackDiagnosticsUploadOperation *operation in operations) [operation cancel];
}

- (void)slock_destroyFeedbackDiagnosticsUploads {
    [self.slock_feedbackDiagnosticsOperationsLock lock];
    self.slock_feedbackDiagnosticsDestroyed = YES;
    NSArray *operations = self.slock_feedbackDiagnosticsOperations.allValues.copy;
    [self.slock_feedbackDiagnosticsOperations removeAllObjects];
    [self.slock_feedbackDiagnosticsOperationsLock unlock];
    for (SlockFeedbackDiagnosticsUploadOperation *operation in operations) [operation cancel];
}

- (NSDictionary *)slock_promoteFeedbackDraftAttachments:(NSDictionary *)params {
    NSArray *files = [params[@"files"] isKindOfClass:NSArray.class] ? params[@"files"] : @[];
    NSString *groupId = SlockBridgeString(params[@"groupId"]);
    unsigned long long maxBytes = [params[@"maxBytes"] respondsToSelector:@selector(unsignedLongLongValue)]
        ? [params[@"maxBytes"] unsignedLongLongValue]
        : 0;
    if (groupId.length == 0 || files.count == 0 || files.count > 9 || maxBytes == 0) {
        return @{ @"status" : @"error", @"code" : @"settings_feedback_attachment_promotion_invalid" };
    }

    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSURL *applicationSupport = [fileManager URLsForDirectory:NSApplicationSupportDirectory
                                                    inDomains:NSUserDomainMask].firstObject;
    if (!applicationSupport) {
        return @{ @"status" : @"error", @"code" : @"settings_feedback_attachment_promotion_failed" };
    }
    NSString *accountSegment = SlockFeedbackDraftAccountSegment(SlockBridgeString(params[@"accountId"]));
    NSCharacterSet *unsafe = [[NSCharacterSet characterSetWithCharactersInString:
        @"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"] invertedSet];
    NSString *safeGroup = [[groupId componentsSeparatedByCharactersInSet:unsafe] componentsJoinedByString:@"_"];
    if (safeGroup.length == 0) safeGroup = @"group";
    if (safeGroup.length > 96) safeGroup = [safeGroup substringToIndex:96];

    NSURL *root = [[[applicationSupport URLByAppendingPathComponent:@"Raft" isDirectory:YES]
        URLByAppendingPathComponent:@"SettingsHandsFeedbackDrafts" isDirectory:YES]
        URLByAppendingPathComponent:accountSegment isDirectory:YES];
    NSError *error = nil;
    if (![fileManager createDirectoryAtURL:root withIntermediateDirectories:YES attributes:nil error:&error]) {
        return @{ @"status" : @"error", @"code" : @"settings_feedback_attachment_promotion_failed",
                  @"message" : error.localizedDescription ?: @"Draft storage unavailable" };
    }
    NSURL *finalDirectory = [root URLByAppendingPathComponent:safeGroup isDirectory:YES];
    NSArray<NSDictionary *> *existing =
        SlockFeedbackDraftFilesAtDirectory(finalDirectory, files, maxBytes, fileManager);
    if (existing) {
        return @{ @"status" : @"ok", @"files" : existing };
    }
    NSURL *stagingDirectory = [root URLByAppendingPathComponent:
        [NSString stringWithFormat:@".%@-%@", safeGroup, NSUUID.UUID.UUIDString.lowercaseString] isDirectory:YES];
    if (![fileManager createDirectoryAtURL:stagingDirectory withIntermediateDirectories:YES attributes:nil error:&error]) {
        return @{ @"status" : @"error", @"code" : @"settings_feedback_attachment_promotion_failed",
                  @"message" : error.localizedDescription ?: @"Draft staging unavailable" };
    }

    NSString *failureCode = nil;
    for (NSUInteger index = 0; index < files.count; index++) {
        NSDictionary *file = [files[index] isKindOfClass:NSDictionary.class] ? files[index] : @{};
        NSString *fileRef = SlockBridgeString(file[@"fileRef"]);
        NSURL *sourceURL = [NSURL URLWithString:fileRef];
        if (!sourceURL.isFileURL) {
            failureCode = @"settings_feedback_attachment_promotion_failed";
            break;
        }
        NSNumber *sourceSize = nil;
        [sourceURL getResourceValue:&sourceSize forKey:NSURLFileSizeKey error:&error];
        if (sourceSize.unsignedLongLongValue == 0 || sourceSize.unsignedLongLongValue > maxBytes) {
            failureCode = sourceSize.unsignedLongLongValue > maxBytes
                ? @"settings_feedback_attachment_too_large"
                : @"settings_feedback_attachment_promotion_failed";
            break;
        }
        NSString *targetName = SlockFeedbackDraftTargetName(index, file);
        NSURL *targetURL = [stagingDirectory URLByAppendingPathComponent:targetName isDirectory:NO];
        if (![fileManager copyItemAtURL:sourceURL toURL:targetURL error:&error]) {
            failureCode = @"settings_feedback_attachment_promotion_failed";
            break;
        }
        NSNumber *targetSize = nil;
        [targetURL getResourceValue:&targetSize forKey:NSURLFileSizeKey error:&error];
        if (targetSize.unsignedLongLongValue != sourceSize.unsignedLongLongValue ||
            targetSize.unsignedLongLongValue == 0 || targetSize.unsignedLongLongValue > maxBytes) {
            failureCode = targetSize.unsignedLongLongValue > maxBytes
                ? @"settings_feedback_attachment_too_large"
                : @"settings_feedback_attachment_promotion_failed";
            break;
        }
    }
    if (failureCode) {
        [fileManager removeItemAtURL:stagingDirectory error:nil];
        return @{ @"status" : @"error", @"code" : failureCode,
                  @"message" : error.localizedDescription ?: @"Unable to promote feedback attachment" };
    }
    [fileManager removeItemAtURL:finalDirectory error:nil];
    if (![fileManager moveItemAtURL:stagingDirectory toURL:finalDirectory error:&error]) {
        [fileManager removeItemAtURL:stagingDirectory error:nil];
        return @{ @"status" : @"error", @"code" : @"settings_feedback_attachment_promotion_failed",
                  @"message" : error.localizedDescription ?: @"Unable to commit feedback attachments" };
    }

    NSArray<NSDictionary *> *promoted =
        SlockFeedbackDraftFilesAtDirectory(finalDirectory, files, maxBytes, fileManager);
    if (!promoted) {
        [fileManager removeItemAtURL:finalDirectory error:nil];
        return @{ @"status" : @"error", @"code" : @"settings_feedback_attachment_promotion_failed",
                  @"message" : @"Committed feedback attachment metadata is unavailable" };
    }
    return @{ @"status" : @"ok", @"files" : promoted };
}

- (NSDictionary *)slock_discardFeedbackDraftAttachments:(NSDictionary *)params {
    NSArray *fileRefs = [params[@"fileRefs"] isKindOfClass:NSArray.class] ? params[@"fileRefs"] : @[];
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSURL *applicationSupport = [fileManager URLsForDirectory:NSApplicationSupportDirectory
                                                    inDomains:NSUserDomainMask].firstObject;
    if (!applicationSupport) {
        return @{ @"status" : @"ok" };
    }
    NSString *accountSegment = SlockFeedbackDraftAccountSegment(SlockBridgeString(params[@"accountId"]));
    NSString *ownedRoot = [[[[[applicationSupport URLByAppendingPathComponent:@"Raft" isDirectory:YES]
        URLByAppendingPathComponent:@"SettingsHandsFeedbackDrafts" isDirectory:YES]
        URLByAppendingPathComponent:accountSegment isDirectory:YES] path] stringByStandardizingPath];
    NSString *ownedPrefix = [ownedRoot stringByAppendingString:@"/"];
    for (id value in fileRefs) {
        NSURL *url = [NSURL URLWithString:SlockBridgeString(value)];
        NSString *path = url.isFileURL ? url.path.stringByStandardizingPath : @"";
        if (path.length > 0 && [path hasPrefix:ownedPrefix]) {
            [fileManager removeItemAtPath:path error:nil];
            NSString *groupPath = path.stringByDeletingLastPathComponent;
            NSArray *remaining = [fileManager contentsOfDirectoryAtPath:groupPath error:nil] ?: @[];
            if (remaining.count == 0 && [groupPath hasPrefix:ownedPrefix]) {
                [fileManager removeItemAtPath:groupPath error:nil];
            }
        }
    }
    return @{ @"status" : @"ok" };
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
    BOOL capture = SlockBridgeBool(params[@"capture"]);
    self.slock_attachmentPickerCompressImages = SlockBridgeBool(params[@"compressImages"]);
    SlockDiagnosticsRecord(
        @"info",
        @"SlockKuiklyAttachment",
        [NSString stringWithFormat:@"picker_start imageOnly=%@ capture=%@ channelIdPresent=%@",
                                   imageOnly ? @"true" : @"false",
                                   capture ? @"true" : @"false",
                                   self.slock_attachmentPickerChannelId.length > 0 ? @"true" : @"false"]
    );
    if (capture) {
        [self slock_requestAndPresentCamera];
        return;
    }
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

- (void)compressThreadImage:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    NSDictionary *params = SlockBridgeParams(args);
    NSString *requestId = SlockBridgeString(params[@"requestId"]);
    NSString *fileName = SlockBridgeString(params[@"fileName"]);
    NSString *mimeType = SlockThreadImageCompressionMimeType(SlockBridgeString(params[@"mimeType"]));
    NSString *fileRef = SlockBridgeRawString(params[@"fileRef"]);
    BOOL validRequest = requestId.length > 0 &&
        requestId.length <= SlockThreadImageCompressionMaxRequestIdLength &&
        [requestId rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location == NSNotFound &&
        SlockThreadImageCompressionSafeName(fileName) &&
        mimeType.length > 0 && [mimeType hasPrefix:@"image/"] &&
        fileRef.length > 0 &&
        [fileRef rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location == NSNotFound;
    if (!validRequest) {
        [self slock_dispatchThreadImageCompressionFailure:callback];
        return;
    }

    SlockThreadImageCompressionOperation *operation = [[SlockThreadImageCompressionOperation alloc] init];
    operation.requestId = requestId;
    operation.callback = callback;
    NSArray<NSURL *> *roots = @[
        [NSURL fileURLWithPath:NSTemporaryDirectory() ?: @"" isDirectory:YES],
        [NSURL fileURLWithPath:NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory, NSUserDomainMask, YES).firstObject ?: @"" isDirectory:YES]
    ];
    NSString *cacheRootPath = NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory, NSUserDomainMask, YES).firstObject;
    NSURL *cacheDirectory = cacheRootPath.length > 0
        ? [[[NSURL fileURLWithPath:cacheRootPath isDirectory:YES]
            URLByAppendingPathComponent:@"Slock" isDirectory:YES]
            URLByAppendingPathComponent:SlockThreadImageCompressionDirectoryName isDirectory:YES]
        : nil;
    if (!cacheDirectory) {
        [operation markCancelled];
        [self slock_dispatchThreadImageCompressionFailure:callback];
        return;
    }
    NSString *outputName = [NSString stringWithFormat:@"composer-image-%@.jpg", NSUUID.UUID.UUIDString];
    operation.outputURL = [cacheDirectory URLByAppendingPathComponent:outputName isDirectory:NO];
    operation.partURL = [operation.outputURL URLByAppendingPathExtension:@"part"];

    [self.slock_threadImageCompressionOperationsLock lock];
    SlockThreadImageCompressionOperation *existing =
        self.slock_threadImageCompressionOperations[requestId];
    BOOL destroyed = self.slock_threadImageCompressionDestroyed;
    if (!existing && !destroyed) {
        self.slock_threadImageCompressionOperations[requestId] = operation;
    }
    [self.slock_threadImageCompressionOperationsLock unlock];
    if (existing || destroyed) {
        [operation markCancelled];
        [self slock_dispatchThreadImageCompressionFailure:callback];
        return;
    }

    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        @autoreleasepool {
            BOOL responseAccepted = NO;
            NSDictionary *terminalPayload = nil;
            NSError *error = nil;
            @try {
                if ([operation isCancelled] || !weakSelf ||
                    ![weakSelf slock_isCurrentThreadImageCompressionOperation:operation]) {
                    [operation markCancelledForce:YES];
                    return;
                }
                NSFileManager *fileManager = NSFileManager.defaultManager;
                BOOL isDirectory = NO;
                if (![fileManager createDirectoryAtURL:cacheDirectory
                           withIntermediateDirectories:YES
                                            attributes:nil
                                                 error:&error] ||
                    ![fileManager fileExistsAtPath:cacheDirectory.path isDirectory:&isDirectory] ||
                    !isDirectory) {
                    error = SlockThreadAttachmentImageCompressionError(@"Attachment cache unavailable");
                    return;
                }
                NSURL *sourceURL = SlockThreadImageCompressionSourceURL(fileRef, roots);
                if (!sourceURL || !SlockThreadImageCompressionInputIsImage(fileName, mimeType, sourceURL) ||
                    [operation isCancelled] || !weakSelf ||
                    ![weakSelf slock_isCurrentThreadImageCompressionOperation:operation]) {
                    error = SlockThreadAttachmentImageCompressionError(@"Unable to decode image attachment");
                    return;
                }
                // Serialize the complete part-write → rename → size-validation transaction with
                // cancel/dealloc cleanup.  Without this fence, teardown can remove the output
                // before ImageIO renames its sibling `.part`, allowing a late worker to recreate
                // a final JPEG after the module has already retired the operation.
                NSDictionary<NSFileAttributeKey, id> *attributes = nil;
                BOOL encoded = NO;
                [operation.outputLock lock];
                @try {
                    encoded = SlockWriteCompressedPickedImage(sourceURL, operation.outputURL, &error);
                    if (encoded) {
                        attributes = [fileManager attributesOfItemAtPath:operation.outputURL.path error:&error];
                    }
                } @finally {
                    [operation.outputLock unlock];
                }
                if (!encoded ||
                    ![attributes[NSFileType] isEqualToString:NSFileTypeRegular] ||
                    [attributes[NSFileSize] unsignedLongLongValue] == 0 || [operation isCancelled] ||
                    !weakSelf || ![weakSelf slock_isCurrentThreadImageCompressionOperation:operation]) {
                    error = SlockThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
                    return;
                }
                NSString *outputRef = operation.outputURL.absoluteString ?: @"";
                NSDictionary *file = @{
                    @"fileRef" : outputRef,
                    @"fileName" : SlockThreadImageCompressionOutputName(fileName),
                    @"mimeType" : @"image/jpeg",
                    @"previewUri" : outputRef,
                    @"sizeBytes" : attributes[NSFileSize] ?: @0,
                    @"source" : SlockThreadImageCompressionSource
                };
                NSData *bodyData = [NSJSONSerialization dataWithJSONObject:@{ @"files" : @[ file ] }
                                                               options:0
                                                                 error:&error];
                if (!bodyData) {
                    error = SlockThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
                    return;
                }
                NSString *bodyText = [[NSString alloc] initWithData:bodyData encoding:NSUTF8StringEncoding];
                if (bodyText.length == 0) {
                    return;
                }
                terminalPayload = @{ @"code" : @200, @"bodyText" : bodyText };
            } @catch (__unused NSException *exception) {
                error = SlockThreadAttachmentImageCompressionError(@"Unable to compress image attachment");
            } @finally {
                if (![operation isCancelled]) {
                    @try {
                        if (weakSelf) {
                            // Build the terminal result first, then admit exactly one response.
                            // An exception after the JPEG was encoded but before the success
                            // payload was complete must still fail closed instead of leaving the
                            // shared coroutine waiting forever.
                            responseAccepted = [weakSelf slock_finishThreadImageCompressionOperation:
                                operation
                                payload:terminalPayload ?: SlockThreadImageCompressionFailurePayload()];
                        } else {
                            [operation markCancelledForce:YES];
                        }
                    } @catch (__unused NSException *exception) {
                        // A bridge/lifecycle exception during response admission is terminal.
                        // Never attempt a second callback; cancel and let the cleanup fence below
                        // remove any unpublished output.
                        [operation markCancelledForce:YES];
                    }
                }
                BOOL terminalOwned = responseAccepted || [operation isPending] || [operation isDelivered];
                // A generated JPEG is only owned by the consumer after finish has admitted the
                // terminal payload to the main-thread delivery fence.  Rejected/stale/cancelled
                // operations (and every failure payload) therefore clean both the final JPEG and
                // its sibling part here.
                if (!terminalPayload || !terminalOwned || [operation isCancelled]) {
                    [operation removeOutputs];
                }
                if (!terminalOwned || [operation isCancelled]) {
                    if (weakSelf) {
                        [weakSelf slock_removeThreadImageCompressionOperation:operation];
                    }
                }
            }
        }
    });
}

- (void)cancelCompressThreadImage:(NSDictionary *)args {
    NSString *requestId = SlockBridgeString(SlockBridgeParams(args)[@"requestId"]);
    if (requestId.length == 0) {
        return;
    }
    [self.slock_threadImageCompressionOperationsLock lock];
    SlockThreadImageCompressionOperation *operation = self.slock_threadImageCompressionOperations[requestId];
    [self.slock_threadImageCompressionOperationsLock unlock];
    if (!operation || ![operation markCancelled]) {
        // In particular, an already-delivered response belongs to the consumer.  Do not remove
        // its map entry or generated JPEG on an ordinary cancel; the delivery fence retires the
        // entry after the callback returns.
        return;
    }
    [self.slock_threadImageCompressionOperationsLock lock];
    if (self.slock_threadImageCompressionOperations[requestId] == operation) {
        [self.slock_threadImageCompressionOperations removeObjectForKey:requestId];
    }
    [self.slock_threadImageCompressionOperationsLock unlock];
    [operation removeOutputs];
}

- (void)slock_removeThreadImageCompressionOperation:(SlockThreadImageCompressionOperation *)operation {
    if (!operation) return;
    [self.slock_threadImageCompressionOperationsLock lock];
    if (self.slock_threadImageCompressionOperations[operation.requestId] == operation) {
        [self.slock_threadImageCompressionOperations removeObjectForKey:operation.requestId];
    }
    [self.slock_threadImageCompressionOperationsLock unlock];
}

- (BOOL)slock_isThreadImageCompressionDestroyed {
    [self.slock_threadImageCompressionOperationsLock lock];
    BOOL destroyed = self.slock_threadImageCompressionDestroyed;
    [self.slock_threadImageCompressionOperationsLock unlock];
    return destroyed;
}

- (BOOL)slock_isCurrentThreadImageCompressionOperation:(SlockThreadImageCompressionOperation *)operation {
    if (!operation) return NO;
    [self.slock_threadImageCompressionOperationsLock lock];
    BOOL current = !self.slock_threadImageCompressionDestroyed &&
        self.slock_threadImageCompressionOperations[operation.requestId] == operation;
    [self.slock_threadImageCompressionOperationsLock unlock];
    return current;
}

- (void)slock_dispatchThreadImageCompressionFailure:(KuiklyRenderCallback)callback {
    if (!callback) return;
    __weak typeof(self) weakSelf = self;
    SlockBridgeRunOnMain(^{
        HRBridgeModule *strongSelf = weakSelf;
        if (!strongSelf) {
            return;
        }
        [strongSelf.slock_threadImageCompressionOperationsLock lock];
        if (!strongSelf.slock_threadImageCompressionDestroyed) {
            @try {
                callback(SlockThreadImageCompressionFailurePayload());
            } @catch (__unused NSException *exception) {
                // Renderer callback removal during teardown is terminal and must not escape the
                // native dispatch turn.
            }
        }
        [strongSelf.slock_threadImageCompressionOperationsLock unlock];
    });
}

- (BOOL)slock_finishThreadImageCompressionOperation:(SlockThreadImageCompressionOperation *)operation
                                             payload:(NSDictionary *)payload {
    if (!operation) return NO;

    // Reserve ACTIVE -> PENDING while the map and operation locks are held together.  Keeping
    // this lock order (map first, operation second) matches cancel and dealloc, so teardown
    // cannot pass the identity check between reservation and the main-thread callback fence.
    [self.slock_threadImageCompressionOperationsLock lock];
    [operation.lock lock];
    BOOL admitted = !self.slock_threadImageCompressionDestroyed &&
        self.slock_threadImageCompressionOperations[operation.requestId] == operation &&
        operation.state == SlockThreadImageCompressionOperationStateActive;
    if (admitted) operation.state = SlockThreadImageCompressionOperationStatePending;
    [operation.lock unlock];
    [self.slock_threadImageCompressionOperationsLock unlock];
    if (!admitted) {
        if (![operation isDelivered]) {
            [operation markCancelledForce:YES];
            [operation removeOutputs];
        }
        return NO;
    }

    __weak typeof(self) weakSelf = self;
    SlockBridgeRunOnMain(^{
        HRBridgeModule *strongSelf = weakSelf;
        if (!strongSelf) {
            [operation markCancelledForce:YES];
            [operation removeOutputs];
            return;
        }

        BOOL callbackInvoked = NO;
        BOOL callbackFailed = NO;
        [strongSelf.slock_threadImageCompressionOperationsLock lock];
        [operation.lock lock];
        BOOL current = !strongSelf.slock_threadImageCompressionDestroyed &&
            strongSelf.slock_threadImageCompressionOperations[operation.requestId] == operation &&
            operation.state == SlockThreadImageCompressionOperationStatePending;
        if (current) {
            // Hold both lifecycle locks across callback invocation.  A re-entrant ordinary cancel
            // observes DELIVERED and is a no-op; another thread waits until the callback has
            // returned before it can cancel or force-clean this operation.
            operation.state = SlockThreadImageCompressionOperationStateDelivered;
            KuiklyRenderCallback callback = operation.callback;
            if (callback) {
                callbackInvoked = YES;
                @try {
                    callback(payload ?: SlockThreadImageCompressionFailurePayload());
                } @catch (__unused NSException *exception) {
                    callbackFailed = YES;
                    operation.state = SlockThreadImageCompressionOperationStateCancelled;
                }
            } else {
                operation.state = SlockThreadImageCompressionOperationStateCancelled;
            }
        } else if (strongSelf.slock_threadImageCompressionOperations[operation.requestId] == operation) {
            // The operation became stale or teardown started after worker admission.  Fence it
            // before releasing the locks so no later callback can observe a pending identity.
            operation.state = SlockThreadImageCompressionOperationStateCancelled;
            [strongSelf.slock_threadImageCompressionOperations removeObjectForKey:operation.requestId];
        }
        [operation.lock unlock];
        [strongSelf.slock_threadImageCompressionOperationsLock unlock];

        // Keep a DELIVERED operation in the map until this callback turn has finished.  This
        // gives module destruction a chance to force-clean a still-tracked output, while a normal
        // cancel that re-enters from the callback observes DELIVERED and leaves ownership alone.
        if (current) {
            [strongSelf slock_removeThreadImageCompressionOperation:operation];
        }
        if (!current || callbackFailed || !callbackInvoked) {
            [operation removeOutputs];
        }
    });
    return YES;
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

#pragma mark - Slock persistent attachment store

// Persistent local copy keyed by <ownerKey>-<safe(attachmentId)>, matching the
// OHOS SlockOhosPrivateAttachmentStore scheme (KRBridgeModule.ets:140-152).
// Files live under Application Support/Slock/Attachments so they survive across
// launches; "Open with" can then share the local file directly instead of
// re-downloading to a throwaway temp dir each tap.

static NSString *SlockAttachmentStoreRoot() {
    NSArray<NSString *> *urls = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory, NSUserDomainMask, YES);
    NSString *base = urls.firstObject ?: NSTemporaryDirectory();
    NSString *root = [[base stringByAppendingPathComponent:@"Raft"] stringByAppendingPathComponent:@"Attachments"];
    // Attachment caches must not be synced to iCloud backup (they're re-downloadable).
    [[NSURL fileURLWithPath:root isDirectory:YES] setResourceValue:@YES
                                                          forKey:NSURLIsExcludedFromBackupKey
                                                           error:nil];
    return root;
}

NSString *SlockAttachmentSafeComponent(NSString *raw) {
    if (raw.length == 0) return @"";
    NSMutableCharacterSet *allowed = [NSMutableCharacterSet alphanumericCharacterSet];
    [allowed addCharactersInString:@"-_."];
    return [[raw componentsSeparatedByCharactersInSet:[allowed invertedSet]] componentsJoinedByString:@"_"];
}

NSString *SlockAttachmentCacheKey(NSString *ownerKey, NSString *attachmentId) {
    NSString *owner = SlockAttachmentSafeComponent(ownerKey ?: @"");
    NSString *attachment = SlockAttachmentSafeComponent(attachmentId ?: @"");
    if (attachment.length == 0) return @"";
    return owner.length ? [owner stringByAppendingFormat:@"-%@", attachment] : attachment;
}

// OHOS key scheme: directory keyed by <ownerKey>-<safe(attachmentId)>, the file lives
// inside it; lookups only need the directory, never the filename.
static NSString *SlockAttachmentCacheDirectory(NSString *ownerKey, NSString *attachmentId) {
    NSString *key = SlockAttachmentCacheKey(ownerKey, attachmentId);
    if (key.length == 0) return nil;
    return [SlockAttachmentStoreRoot() stringByAppendingFormat:@"/%@", key];
}

static NSString *SlockAttachmentCachedFilePath(NSString *ownerKey, NSString *attachmentId, NSString *fileName) {
    NSString *dir = SlockAttachmentCacheDirectory(ownerKey, attachmentId);
    if (!dir) return nil;
    NSString *leaf = SlockAttachmentSafeComponent(fileName ?: @"attachment");
    if (leaf.length == 0) leaf = @"attachment";
    return [dir stringByAppendingFormat:@"/%@", leaf];
}

static NSDictionary *SlockAttachmentCacheStatusDict(NSString *directoryPath) {
    NSFileManager *fm = NSFileManager.defaultManager;
    NSArray<NSString *> *files = [fm contentsOfDirectoryAtPath:directoryPath error:nil];
    if (files.count == 0) {
        return @{ @"status" : @"missing" };
    }
    NSString *filePath = [directoryPath stringByAppendingFormat:@"/%@", files.firstObject];
    NSDictionary<NSString *, id> *attrs = [fm attributesOfItemAtPath:filePath error:nil];
    NSNumber *size = attrs[NSFileSize];
    if (!size || size.longLongValue <= 0) {
        return @{ @"status" : @"missing" };
    }
    NSNumber *mtimeMs = attrs[NSFileModificationDate]
        ? @((long long)([attrs[NSFileModificationDate] timeIntervalSince1970] * 1000.0))
        : @0;
    return @{
        @"status" : @"ok",
        @"cached" : @YES,
        @"recordVersion" : mtimeMs,
        @"sizeBytes" : size,
        @"uri" : [NSURL fileURLWithPath:filePath].absoluteString ?: @"",
        @"storageKind" : @"app_private",
    };
}

- (void)openAttachment:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    if (self.slock_attachmentExportCallback) {
        callback(@{ @"status" : @"failed", @"reason" : @"busy",
                    @"message" : @"Another file operation is already active" });
        return;
    }

    NSDictionary *params = SlockBridgeParams(args);
    NSString *ownerKey = SlockBridgeString(params[@"ownerKey"] ?: params[@"owner"]);
    NSString *attachmentId = SlockBridgeString(params[@"attachmentId"]);
    NSString *fileName = SlockBridgeString(params[@"fileName"]) ?: @"attachment";
    NSString *downloadId = SlockBridgeString(params[@"downloadId"]) ?: attachmentId;
    BOOL requirePublicDownload = [params[@"requirePublicDownload"] boolValue];

    // Capture the presenter at tap time while the page is certainly alive (weak),
    // then prefer it with a frontmost fallback — Signal AttachmentSharing L117.
    self.slock_sharePresenterController = [self slock_currentViewController];

    NSString *cachedDir = SlockAttachmentCacheDirectory(ownerKey, attachmentId);
    NSString *cachedPath = SlockAttachmentCachedFilePath(ownerKey, attachmentId, fileName);
    NSDictionary *status = cachedPath
        ? SlockAttachmentCacheStatusDict(cachedDir)
        : @{ @"status" : @"missing" };

    if ([status[@"cached"] boolValue]) {
        [self slock_presentShareItems:@[ [NSURL fileURLWithPath:cachedPath] ] attempt:1];
        callback(@{ @"status" : @"opened", @"cached" : @YES, @"uri" : status[@"uri"] ?: @"" });
        return;
    }

    if (requirePublicDownload) {
        callback(@{ @"status" : @"missing" });
        return;
    }

    // Download directly to the persistent cache path (not the throwaway temp dir the
    // export path uses). On success we have a durable local copy and present the share.
    if (!cachedPath || !downloadId) {
        callback(@{ @"status" : @"failed", @"reason" : @"no_cache_path" });
        return;
    }
    NSString *urlText = SlockBridgeString(params[@"url"]);
    if (urlText.length == 0) {
        callback(@{ @"status" : @"failed", @"reason" : @"missing_url" });
        return;
    }
    self.slock_attachmentExportDownloadId = downloadId;
    self.slock_attachmentExportURL = [NSURL fileURLWithPath:cachedPath];
    self.slock_attachmentExportDestination = SlockAttachmentExportDestinationOpen;
    self.slock_attachmentExportFileName = fileName;
    self.slock_attachmentExportMimeType = SlockBridgeString(params[@"mimeType"]);
    self.slock_attachmentExportCallback = callback;
    __weak HRBridgeModule *weakSelf = self;
    NSString *attemptToken =
        [SharedAttachmentDownloadCoordinator.shared startDownloadId:downloadId
                                                          targetKey:cachedPath
                                                                url:urlText
                                                         targetPath:cachedPath
                                                           observer:self];
    if (attemptToken.length == 0) {
        callback(@{ @"status" : @"failed", @"reason" : @"download_start_failed",
                    @"message" : @"Unable to start attachment download" });
        self.slock_attachmentExportCallback = nil;
        self.slock_attachmentExportURL = nil;
        return;
    }
    self.slock_attachmentExportRequestId = attemptToken;
}

- (void)attachmentCacheStatus:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    if (!callback) {
        return;
    }
    NSDictionary *params = SlockBridgeParams(args);
    NSString *cachedDir = SlockAttachmentCacheDirectory(
        SlockBridgeString(params[@"ownerKey"] ?: params[@"owner"]),
        SlockBridgeString(params[@"attachmentId"]));
    NSDictionary *status = cachedDir
        ? SlockAttachmentCacheStatusDict(cachedDir)
        : @{ @"status" : @"missing" };
    callback(status);
}

- (void)deleteAttachmentCache:(NSDictionary *)args {
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSDictionary *params = SlockBridgeParams(args);
    NSString *cachedDir = SlockAttachmentCacheDirectory(
        SlockBridgeString(params[@"ownerKey"] ?: params[@"owner"]),
        SlockBridgeString(params[@"attachmentId"]));
    BOOL removed = cachedDir &&
        [NSFileManager.defaultManager removeItemAtPath:cachedDir error:nil];
    if (callback) {
        callback(@{ @"status" : removed ? @"ok" : @"missing" });
    }
}

- (void)saveAttachment:(NSDictionary *)args {
    [self slock_startAttachmentExport:args destination:SlockAttachmentExportDestinationFiles];
}

- (void)saveImageToPhotos:(NSDictionary *)args {
    [self slock_startAttachmentExport:args destination:SlockAttachmentExportDestinationPhotos];
}

- (void)cancelAttachmentExport:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *downloadId = SlockBridgeString(params[@"downloadId"]);
    NSString *operationId = SlockBridgeString(params[@"operationId"]);
    if (!self.slock_attachmentExportCallback || downloadId.length == 0 ||
        operationId.length == 0 ||
        ![self.slock_attachmentExportDownloadId isEqualToString:downloadId] ||
        ![self.slock_attachmentExportOperationId isEqualToString:operationId]) {
        return;
    }
    NSString *expectedAttemptToken = SlockBridgeString(params[@"attemptToken"]);
    NSString *attemptToken = expectedAttemptToken.length > 0
        ? expectedAttemptToken
        : ([self.slock_attachmentExportRequestId isEqualToString:@"pending"]
            ? @""
            : (self.slock_attachmentExportRequestId ?: @""));
    [SharedAttachmentDownloadCoordinator.shared controlExistingDownloadId:downloadId
                                                      expectedAttemptToken:attemptToken
                                                                    action:@"cancel"];
    [self slock_finishAttachmentExport:@{ @"status" : @"cancelled" }];
}

- (void)slock_startAttachmentExport:(NSDictionary *)args destination:(NSString *)destination {
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
    NSString *mimeType = [[[[SlockBridgeString(params[@"mimeType"])
        componentsSeparatedByString:@";"] firstObject]
        stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet]
        lowercaseString];
    NSString *operationId = SlockBridgeString(params[@"operationId"]);
    if (operationId.length == 0) {
        callback(@{
            @"status" : @"failed",
            @"message" : @"Missing attachment export authority"
        });
        return;
    }
    BOOL genericBinaryMime =
        [mimeType isEqualToString:@"application/octet-stream"] || [mimeType isEqualToString:@"binary/octet-stream"];
    BOOL savesToPhotos = [destination isEqualToString:SlockAttachmentExportDestinationPhotos];
    if (savesToPhotos && mimeType.length > 0 && ![mimeType hasPrefix:@"image/"]) {
        callback(@{
            @"status" : @"unsupported_image",
            @"message" : @"This attachment is not a supported image.",
            @"retryable" : @NO
        });
        return;
    }
    if (savesToPhotos && mimeType.length > 0) {
        UTType *mimeClaimType = [UTType typeWithMIMEType:mimeType];
        if (!mimeClaimType || ![mimeClaimType conformsToType:UTTypeImage]) {
            callback(@{
                @"status" : @"unsupported_image",
                @"message" : @"This attachment is not a supported image.",
                @"retryable" : @NO
            });
            return;
        }
    }
    if (savesToPhotos && fileName.pathExtension.length > 0) {
        UTType *extensionType = [UTType typeWithFilenameExtension:fileName.pathExtension];
        if (!extensionType || ![extensionType conformsToType:UTTypeImage]) {
            callback(@{
                @"status" : @"unsupported_image",
                @"message" : @"This attachment is not a supported image.",
                @"retryable" : @NO
            });
            return;
        }
    }
    self.slock_attachmentExportCallback = callback;
    self.slock_attachmentExportDownloadId = SlockBridgeString(params[@"downloadId"]);
    self.slock_attachmentExportOperationId = operationId;
    self.slock_attachmentExportDestination = destination;
    self.slock_attachmentExportMimeType = genericBinaryMime && !savesToPhotos ? @"" : mimeType;
    self.slock_attachmentExportFileName = fileName;
    self.slock_attachmentExportTrackProgress = SlockBridgeBool(params[@"trackProgress"]);

    if (!savesToPhotos) {
        [self slock_beginAttachmentExportDownloadWithParams:params];
        return;
    }

    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelAddOnly];
    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) {
        self.slock_attachmentExportPermissionStatus =
            status == PHAuthorizationStatusLimited ? @"limited" : @"authorized";
        [self slock_beginAttachmentExportDownloadWithParams:params];
        return;
    }
    if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
        [self slock_finishAttachmentExport:@{
            @"status" : status == PHAuthorizationStatusRestricted ? @"permission_restricted" : @"permission_denied",
            @"permissionStatus" : status == PHAuthorizationStatusRestricted ? @"restricted" : @"denied",
            @"message" : status == PHAuthorizationStatusRestricted
                ? @"Photos access is restricted on this device."
                : @"Allow Photos access in Settings, then try again.",
            @"retryable" : @(status == PHAuthorizationStatusDenied)
        }];
        return;
    }

    __weak typeof(self) weakSelf = self;
    NSDictionary *capturedParams = [params copy];
    NSString *capturedDownloadId = self.slock_attachmentExportDownloadId ?: @"";
    NSString *capturedOperationId = self.slock_attachmentExportOperationId ?: @"";
    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus authorizationStatus) {
        SlockBridgeRunOnMain(^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf || !strongSelf.slock_attachmentExportCallback ||
                ![strongSelf.slock_attachmentExportDestination isEqualToString:SlockAttachmentExportDestinationPhotos] ||
                ![strongSelf.slock_attachmentExportDownloadId isEqualToString:capturedDownloadId] ||
                ![strongSelf.slock_attachmentExportOperationId isEqualToString:capturedOperationId]) {
                return;
            }
            if (authorizationStatus == PHAuthorizationStatusAuthorized || authorizationStatus == PHAuthorizationStatusLimited) {
                strongSelf.slock_attachmentExportPermissionStatus =
                    authorizationStatus == PHAuthorizationStatusLimited ? @"limited" : @"authorized";
                [strongSelf slock_beginAttachmentExportDownloadWithParams:capturedParams];
                return;
            }
            BOOL restricted = authorizationStatus == PHAuthorizationStatusRestricted;
            [strongSelf slock_finishAttachmentExport:@{
                @"status" : restricted ? @"permission_restricted" : @"permission_denied",
                @"permissionStatus" : restricted ? @"restricted" : @"denied",
                @"message" : restricted
                    ? @"Photos access is restricted on this device."
                    : @"Allow Photos access in Settings, then try again.",
                @"retryable" : @(!restricted)
            }];
        });
    }];
}

- (void)slock_beginAttachmentExportDownloadWithParams:(NSDictionary *)params {
    NSString *urlText = SlockBridgeString(params[@"url"]);
    NSString *fileName = self.slock_attachmentExportFileName ?: @"attachment";
    NSString *requestId = NSUUID.UUID.UUIDString;
    NSString *downloadId = SlockBridgeString(params[@"downloadId"]);
    if (downloadId.length == 0) downloadId = requestId;
    self.slock_attachmentExportRequestId = @"pending";
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
            NSString *errorKind = result.errorKind ?: @"";
            BOOL storageFailure =
                [errorKind isEqualToString:@"storage_write"] ||
                [errorKind isEqualToString:@"storage_prepare"] ||
                [errorKind isEqualToString:@"storage_publish"];
            NSString *status = [result.status isEqualToString:@"cancelled"]
                ? @"cancelled"
                : (storageFailure ? @"storage_failed" : @"download_failed");
            [strongSelf slock_finishAttachmentExport:@{
                @"status" : status,
                @"message" : result.message ?: @"Unable to download attachment",
                @"errorKind" : errorKind
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
        strongSelf.slock_attachmentExportSizeBytes = @(result.downloadedBytes);
        if ([strongSelf.slock_attachmentExportDestination isEqualToString:SlockAttachmentExportDestinationOpen]) {
            // Open-with: the file is a persistent local copy — present the share sheet on it,
            // do NOT call slock_finishAttachmentExport (it deletes the parent dir) or the
            // save-to-files picker. Clear state and report success, keeping the file.
            KuiklyRenderCallback openCallback = strongSelf.slock_attachmentExportCallback;
            [strongSelf slock_presentShareItems:@[ targetURL ] attempt:1];
            strongSelf.slock_attachmentExportCallback = nil;
            strongSelf.slock_attachmentExportRequestId = nil;
            strongSelf.slock_attachmentExportDownloadId = nil;
            strongSelf.slock_attachmentExportURL = nil;
            strongSelf.slock_attachmentExportFileName = nil;
            strongSelf.slock_attachmentExportSizeBytes = nil;
            strongSelf.slock_attachmentExportDestination = nil;
            strongSelf.slock_attachmentExportMimeType = nil;
            if (openCallback) {
                openCallback(@{
                    @"status" : @"opened",
                    @"cached" : @YES,
                    @"uri" : targetURL.absoluteString ?: @"",
                });
            }
            return;
        }
        if ([strongSelf.slock_attachmentExportDestination isEqualToString:SlockAttachmentExportDestinationPhotos]) {
            [strongSelf slock_saveAttachmentExportToPhotos];
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
        UIDocumentPickerViewController *pickerController =
            [[UIDocumentPickerViewController alloc] initForExportingURLs:@[targetURL] asCopy:YES];
        pickerController.delegate = strongSelf;
        [controller presentViewController:pickerController animated:YES completion:nil];
    });
}

- (void)slock_saveAttachmentExportToPhotos {
    NSURL *targetURL = self.slock_attachmentExportURL;
    if (!targetURL) {
        [self slock_finishAttachmentExport:@{
            @"status" : @"storage_failed",
            @"message" : @"Unable to prepare image for Photos.",
            @"retryable" : @YES
        }];
        return;
    }

    CGImageSourceRef source = CGImageSourceCreateWithURL((__bridge CFURLRef)targetURL, NULL);
    if (!source || CGImageSourceGetCount(source) == 0) {
        if (source) CFRelease(source);
        [self slock_finishAttachmentExport:@{
            @"status" : @"unsupported_image",
            @"message" : @"This attachment is not a supported image.",
            @"retryable" : @NO
        }];
        return;
    }

    CFStringRef typeIdentifier = CGImageSourceGetType(source);
    NSString *typeIdentifierString = typeIdentifier ? (__bridge NSString *)typeIdentifier : @"";
    UTType *actualType = typeIdentifierString.length > 0 ? [UTType typeWithIdentifier:typeIdentifierString] : nil;
    NSDictionary *properties = CFBridgingRelease(CGImageSourceCopyPropertiesAtIndex(source, 0, NULL));
    CFRelease(source);

    NSNumber *pixelWidth = properties[(NSString *)kCGImagePropertyPixelWidth];
    NSNumber *pixelHeight = properties[(NSString *)kCGImagePropertyPixelHeight];
    NSNumber *orientation = properties[(NSString *)kCGImagePropertyOrientation];
    BOOL validDimensions = pixelWidth.longLongValue > 0 && pixelHeight.longLongValue > 0;
    BOOL validOrientation = !orientation || (orientation.integerValue >= 1 && orientation.integerValue <= 8);
    if (!actualType || ![actualType conformsToType:UTTypeImage] || !validDimensions || !validOrientation) {
        [self slock_finishAttachmentExport:@{
            @"status" : @"unsupported_image",
            @"message" : @"This attachment is not a supported image.",
            @"retryable" : @NO
        }];
        return;
    }

    NSString *expectedMimeType = self.slock_attachmentExportMimeType ?: @"";
    BOOL hasMimeClaim = expectedMimeType.length > 0;
    NSString *claimedExtension = self.slock_attachmentExportFileName.pathExtension ?: @"";
    BOOL hasExtensionClaim = claimedExtension.length > 0;
    UTType *mimeType = hasMimeClaim ? [UTType typeWithMIMEType:expectedMimeType] : nil;
    UTType *extensionType = hasExtensionClaim
        ? [UTType typeWithFilenameExtension:claimedExtension]
        : nil;
    BOOL mimeMatches = !hasMimeClaim ||
        (mimeType && [mimeType conformsToType:UTTypeImage] &&
         ([actualType conformsToType:mimeType] || [mimeType conformsToType:actualType]));
    BOOL extensionMatches = !hasExtensionClaim ||
        (extensionType && [extensionType conformsToType:UTTypeImage] &&
         ([actualType conformsToType:extensionType] || [extensionType conformsToType:actualType]));
    if (!mimeMatches || !extensionMatches) {
        [self slock_finishAttachmentExport:@{
            @"status" : @"unsupported_image",
            @"message" : @"The downloaded image does not match its attachment type.",
            @"retryable" : @NO
        }];
        return;
    }

    NSString *fileName = self.slock_attachmentExportFileName ?: @"image";
    NSURL *photoResourceURL = targetURL;
    if (fileName.pathExtension.length == 0) {
        NSString *preferredExtension = actualType.preferredFilenameExtension ?: @"";
        if (preferredExtension.length == 0) {
            [self slock_finishAttachmentExport:@{
                @"status" : @"unsupported_image",
                @"message" : @"This image type cannot be saved to Photos.",
                @"retryable" : @NO
            }];
            return;
        }
        fileName = [fileName stringByAppendingPathExtension:preferredExtension];
        photoResourceURL = [[targetURL URLByDeletingLastPathComponent]
            URLByAppendingPathComponent:fileName isDirectory:NO];
        NSError *stagingError = nil;
        if (![NSFileManager.defaultManager copyItemAtURL:targetURL toURL:photoResourceURL error:&stagingError]) {
            [self slock_finishAttachmentExport:@{
                @"status" : @"storage_failed",
                @"message" : @"Unable to prepare image for Photos.",
                @"retryable" : @YES
            }];
            return;
        }
    }
    NSString *permissionStatus = self.slock_attachmentExportPermissionStatus ?: @"authorized";
    NSString *actualMimeType = actualType.preferredMIMEType ?: @"";
    NSNumber *sizeBytes = self.slock_attachmentExportSizeBytes ?: @0;
    __block NSString *assetIdentifier = @"";
    __weak typeof(self) weakSelf = self;
    [[PHPhotoLibrary sharedPhotoLibrary]
        performChanges:^{
            PHAssetCreationRequest *creationRequest = [PHAssetCreationRequest creationRequestForAsset];
            PHAssetResourceCreationOptions *options = [[PHAssetResourceCreationOptions alloc] init];
            options.originalFilename = fileName;
            [creationRequest addResourceWithType:PHAssetResourceTypePhoto fileURL:photoResourceURL options:options];
            assetIdentifier = creationRequest.placeholderForCreatedAsset.localIdentifier ?: @"";
        }
        completionHandler:^(BOOL success, NSError *error) {
            SlockBridgeRunOnMain(^{
                __strong typeof(weakSelf) strongSelf = weakSelf;
                if (!strongSelf || !strongSelf.slock_attachmentExportCallback ||
                    ![strongSelf.slock_attachmentExportURL isEqual:targetURL]) {
                    return;
                }
                if (!success || error) {
                    [strongSelf slock_finishAttachmentExport:@{
                        @"status" : @"photos_failed",
                        @"message" : @"Couldn’t save this image to Photos. Try again.",
                        @"permissionStatus" : permissionStatus,
                        @"retryable" : @YES
                    }];
                    return;
                }
                [strongSelf slock_finishAttachmentExport:@{
                    @"status" : @"saved",
                    @"message" : @"Saved to Photos",
                    @"permissionStatus" : permissionStatus,
                    @"assetIdentifier" : assetIdentifier,
                    @"fileName" : fileName,
                    @"mimeType" : actualMimeType,
                    @"pixelWidth" : pixelWidth,
                    @"pixelHeight" : pixelHeight,
                    @"sizeBytes" : sizeBytes,
                    @"cached" : @NO,
                    @"retryable" : @NO
                }];
            });
        }];
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

- (void)slock_requestAndPresentCamera {
    if (![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
        [self slock_finishAttachmentPicker:@{
            @"code" : @503,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_camera_unavailable",
            @"errorBodyText" : @"Camera is unavailable"
        }];
        return;
    }
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    if (status == AVAuthorizationStatusAuthorized) {
        [self slock_presentCamera];
        return;
    }
    if (status == AVAuthorizationStatusDenied || status == AVAuthorizationStatusRestricted) {
        [self slock_finishAttachmentPicker:@{
            @"code" : @403,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_camera_permission_denied",
            @"errorBodyText" : @"Camera access is denied"
        }];
        return;
    }
    __weak typeof(self) weakSelf = self;
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
        SlockBridgeRunOnMain(^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf || !strongSelf.slock_attachmentPickerCallback) {
                return;
            }
            if (granted) {
                [strongSelf slock_presentCamera];
            } else {
                [strongSelf slock_finishAttachmentPicker:@{
                    @"code" : @403,
                    @"bodyText" : @"",
                    @"errorCode" : @"attachment_camera_permission_denied",
                    @"errorBodyText" : @"Camera access is denied"
                }];
            }
        });
    }];
}

- (void)slock_presentCamera {
    SlockBridgeRunOnMain(^{
        UIViewController *controller = [self slock_currentViewController];
        if (!controller || ![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
            [self slock_finishAttachmentPicker:@{
                @"code" : @503,
                @"bodyText" : @"",
                @"errorCode" : @"attachment_camera_unavailable",
                @"errorBodyText" : @"Camera is unavailable"
            }];
            return;
        }
        UIImagePickerController *picker = [[UIImagePickerController alloc] init];
        picker.sourceType = UIImagePickerControllerSourceTypeCamera;
        picker.mediaTypes = @[ UTTypeImage.identifier ];
        picker.cameraCaptureMode = UIImagePickerControllerCameraCaptureModePhoto;
        picker.delegate = self;
        [controller presentViewController:picker animated:YES completion:nil];
    });
}

- (void)imagePickerControllerDidCancel:(UIImagePickerController *)picker {
    [picker dismissViewControllerAnimated:YES completion:nil];
    [self slock_finishAttachmentPicker:@{ @"code" : @204, @"bodyText" : @"" }];
}

- (void)imagePickerController:(UIImagePickerController *)picker
didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey, id> *)info {
    UIImage *image = info[UIImagePickerControllerOriginalImage];
    [picker dismissViewControllerAnimated:YES completion:nil];
    if (!image) {
        [self slock_finishAttachmentPicker:@{
            @"code" : @400,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_read_failed",
            @"errorBodyText" : @"Captured image is unavailable"
        }];
        return;
    }
    BOOL compressImages = self.slock_attachmentPickerCompressImages;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSURL *temporaryURL = [[NSURL fileURLWithPath:NSTemporaryDirectory() isDirectory:YES]
            URLByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingPathExtension:@"jpg"]
                         isDirectory:NO];
        NSError *error = nil;
        BOOL encoded = compressImages
            ? SlockWriteCompressedCameraImage(image, temporaryURL, &error)
            : [UIImageJPEGRepresentation(image, 0.92) writeToURL:temporaryURL
                                                          options:NSDataWritingAtomic
                                                            error:&error];
        if (!encoded) {
            [NSFileManager.defaultManager removeItemAtURL:temporaryURL error:nil];
            [self slock_finishAttachmentPicker:@{
                @"code" : @400,
                @"bodyText" : @"",
                @"errorCode" : compressImages
                    ? @"attachment_image_compression_failed" : @"attachment_read_failed",
                @"errorBodyText" : error.localizedDescription ?: @"Captured image could not be encoded"
            }];
            return;
        }
        NSDictionary *file = nil;
        file = [self slock_cachePickedAttachmentURL:temporaryURL
                                      suggestedName:@"camera.jpg"
                                           mimeType:@"image/jpeg"
                                      compressImages:NO
                                               error:&error];
        [NSFileManager.defaultManager removeItemAtURL:temporaryURL error:nil];
        if (!file) {
            [self slock_finishAttachmentPicker:@{
                @"code" : @400,
                @"bodyText" : @"",
                @"errorCode" : compressImages &&
                    SlockIsThreadAttachmentImageCompressionError(error)
                    ? @"attachment_image_compression_failed" : @"attachment_read_failed",
                @"errorBodyText" : error.localizedDescription ?: @"Captured image could not be cached"
            }];
            return;
        }
        NSMutableDictionary *capturedFile = [file mutableCopy];
        capturedFile[@"source"] = @"platform_camera";
        [self slock_finishAttachmentPickerFiles:@[ capturedFile ] errors:@[]];
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
    BOOL compressImages = self.slock_attachmentPickerCompressImages;
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
                                             compressImages:compressImages &&
                                                 [mediaType conformsToType:UTTypeImage]
                                                     error:&cacheError];
            }
            if (file) {
                @synchronized (orderedFiles) {
                    orderedFiles[index] = file;
                }
            } else {
                NSString *errorCode =
                    SlockIsThreadAttachmentImageCompressionError(cacheError)
                        ? @"attachment_image_compression_failed"
                        : @"attachment_read_failed";
                @synchronized (errors) {
                    [errors addObject:@{
                        @"index" : @(index),
                        @"errorCode" : errorCode,
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
    BOOL compressImages = self.slock_attachmentPickerCompressImages;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSMutableArray<NSDictionary *> *files = [NSMutableArray array];
        NSMutableArray<NSDictionary *> *errors = [NSMutableArray array];
        [urls enumerateObjectsUsingBlock:^(NSURL *url, NSUInteger index, BOOL *stop) {
            BOOL securityScoped = [url startAccessingSecurityScopedResource];
            NSError *error = nil;
            NSDictionary *file = [self slock_cachePickedAttachmentURL:url
                                                        suggestedName:url.lastPathComponent
                                                             mimeType:nil
                                                        compressImages:compressImages &&
                                                            SlockAttachmentIsImage(url.lastPathComponent, nil, url)
                                                                error:&error];
            if (securityScoped) {
                [url stopAccessingSecurityScopedResource];
            }
            if (file) {
                [files addObject:file];
            } else {
                NSString *errorCode =
                    SlockIsThreadAttachmentImageCompressionError(error)
                        ? @"attachment_image_compression_failed"
                        : @"attachment_read_failed";
                [errors addObject:@{
                    @"index" : @(index),
                    @"errorCode" : errorCode,
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
        BOOL compressionFailed = NO;
        for (NSDictionary *entry in errors) {
            if ([entry[@"errorCode"] isEqualToString:@"attachment_image_compression_failed"]) {
                compressionFailed = YES;
                break;
            }
        }
        [self slock_finishAttachmentPicker:@{
            @"code" : @400,
            @"bodyText" : @"",
            @"errorCode" : compressionFailed ? @"attachment_image_compression_failed" : @"attachment_read_failed",
            @"errorBodyText" : compressionFailed ? @"Unable to compress image attachment" : @"No readable attachments"
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
        BOOL compressionFailed = NO;
        for (NSDictionary *entry in errors) {
            if ([entry[@"errorCode"] isEqualToString:@"attachment_image_compression_failed"]) {
                compressionFailed = YES;
                break;
            }
        }
        result[@"errors"] = errors;
        result[@"skippedCount"] = @(errors.count);
        result[@"errorCode"] = compressionFailed ? @"attachment_image_compression_failed" : @"attachment_read_partial";
        result[@"errorBodyText"] = [NSString stringWithFormat:@"%lu attachment(s) could not be read", (unsigned long)errors.count];
    }
    [self slock_finishAttachmentPicker:result];
}

- (NSDictionary *)slock_cachePickedAttachmentURL:(NSURL *)sourceURL
                                    suggestedName:(NSString *)suggestedName
                                         mimeType:(NSString *)mimeType
                                    compressImages:(BOOL)compressImages
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
    BOOL isImage = SlockAttachmentIsImage(displayName, mimeType, sourceURL);
    if (compressImages && isImage) {
        NSString *compressedName = SlockCompressedAttachmentFileName(displayName);
        targetURL = [directory URLByAppendingPathComponent:
            [NSString stringWithFormat:@"%@-%@", NSUUID.UUID.UUIDString, compressedName]
                                                   isDirectory:NO];
        if (!SlockWriteCompressedPickedImage(sourceURL, targetURL, error)) {
            [fileManager removeItemAtURL:targetURL error:nil];
            return nil;
        }
        displayName = compressedName;
    } else if (![fileManager copyItemAtURL:sourceURL toURL:targetURL error:error]) {
        return nil;
    }
    NSNumber *fileSize = nil;
    [targetURL getResourceValue:&fileSize forKey:NSURLFileSizeKey error:nil];
    NSString *resolvedMimeType = compressImages && isImage ? @"image/jpeg" : mimeType;
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
    self.slock_attachmentPickerCompressImages = NO;
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
    self.slock_attachmentExportDownloadId = nil;
    self.slock_attachmentExportOperationId = nil;
    self.slock_attachmentExportURL = nil;
    self.slock_attachmentExportFileName = nil;
    self.slock_attachmentExportSizeBytes = nil;
    self.slock_attachmentExportTrackProgress = NO;
    self.slock_attachmentExportDestination = nil;
    self.slock_attachmentExportMimeType = nil;
    self.slock_attachmentExportPermissionStatus = nil;
    if (exportURL) {
        [NSFileManager.defaultManager removeItemAtURL:[exportURL URLByDeletingLastPathComponent] error:nil];
    }
    if (callback) {
        callback(result);
    }
}

- (NSString *)retireFilePreviewTemporaryFiles:(NSDictionary *)args {
    NSDictionary *params = SlockBridgeParams(args);
    NSString *sessionId = SlockBridgeString(params[@"sessionId"]);
    NSArray *rawRefs = [params[@"fileRefs"] isKindOfClass:NSArray.class] ? params[@"fileRefs"] : @[];
    NSMutableSet<NSString *> *refs = [NSMutableSet set];
    for (id rawRef in rawRefs) {
        if (![rawRef isKindOfClass:NSString.class]) continue;
        NSString *ref = [(NSString *)rawRef
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if (ref.length > 0) [refs addObject:ref];
    }
    SharedThreadComposerTemporaryFileRetirementResult *result =
        SlockIosRetireThreadComposerTemporaryFileRefs(sessionId, refs);
    SlockIosDiagnosticsRecord(
        @"info",
        @"SlockFilePreviewCleanup",
        [NSString stringWithFormat:@"session=%@ requested=%d admitted=%d quarantined=%d rejected=%d failed=%d",
                                   result.sessionId,
                                   result.requestedCount,
                                   result.admittedCount,
                                   result.quarantinedCount,
                                   result.rejectedCount,
                                   result.failedCount]
    );
    return [NSString stringWithFormat:
        @"{\"status\":\"%@\",\"requested\":%d,\"admitted\":%d,\"quarantined\":%d,\"rejected\":%d,\"failed\":%d%@}",
        result.succeeded ? @"quarantined" : @"failed",
        result.requestedCount,
        result.admittedCount,
        result.quarantinedCount,
        result.rejectedCount,
        result.failedCount,
        result.errorCode.length > 0
            ? [NSString stringWithFormat:@",\"errorCode\":\"%@\"", result.errorCode]
            : @""];
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
    if ([action isEqualToString:@"copyDiagnostics"]) {
        NSString *exportText = SlockDiagnosticsBuildExportText([self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}], YES);
        SlockBridgeRunOnMain(^{
            UIPasteboard.generalPasteboard.string = exportText;
        });
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"shared_copy_diagnostics");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"shareDiagnostics"]) {
        NSString *exportText = SlockDiagnosticsBuildExportText([self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}], YES);
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
        // Self-host login: the socket still points at the previous server. Close every connection now, as the
        // Android and HarmonyOS hosts do; the cleared session reconnects to the new environment after login.
        [[SlockIosRealtimeHost shared] handleCommand:@{
            @"action" : @"disconnect",
            @"reason" : @"api_environment_changed",
        }];
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
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"hands_feedback_prompt_opened");
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
        SlockDiagnosticsRecord(@"info", @"SlockDiagnostics", @"hands_feedback_submit_started");
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
                        [NSString stringWithFormat:@"hands_feedback_submit_failed error=%@", error.localizedDescription ?: @"unknown"]);
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
                    SlockDiagnosticsBuildExportText([self slock_settingsCapabilityResultForAction:@"loadAbout" params:@{}], YES)
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
                    [NSString stringWithFormat:@"hands_feedback_submitted ticket=%@", shortId]);
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
        [self slock_presentShareItems:items attempt:1];
    });
}

- (void)slock_presentShareItems:(NSArray *)items attempt:(NSInteger)attempt {
    UIViewController *controller = SlockSharePresenterController(
        self.slock_sharePresenterController, [self slock_currentViewController]);
    if (!controller) {
        NSString *reason = [self slock_shareControllerFailureReason];
        if (attempt < 2) {
            SlockDiagnosticsRecord(
                @"warn", @"SlockShare",
                [NSString stringWithFormat:@"share_present_retry attempt=%ld reason=%@",
                 (long)attempt, reason]);
            dispatch_async(dispatch_get_main_queue(), ^{
                [self slock_presentShareItems:items attempt:attempt + 1];
            });
            return;
        }
        SlockDiagnosticsRecord(
            @"error", @"SlockShare",
            [NSString stringWithFormat:@"share_present_failed attempt=%ld reason=%@",
             (long)attempt, reason]);
        [self slock_showToast:SharedSlockHostStrings.shared.shareOpenFailed];
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
}

UIViewController *SlockSharePresenterController(UIViewController *capturedPresenter,
                                                UIViewController *frontmostPresenter) {
    return capturedPresenter ?: frontmostPresenter;
}

- (NSString *)slock_shareControllerFailureReason {
    BOOL hasForegroundActiveScene = YES;
    if (@available(iOS 13.0, *)) {
        hasForegroundActiveScene = NO;
        for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
            if (scene.activationState == UISceneActivationStateForegroundActive &&
                [scene isKindOfClass:UIWindowScene.class]) {
                hasForegroundActiveScene = YES;
                break;
            }
        }
    }
    return SlockShareControllerFailureReason(
        self.hr_rootView.window != nil, hasForegroundActiveScene);
}

NSString *SlockShareControllerFailureReason(BOOL hasRootViewWindow,
                                            BOOL hasForegroundActiveScene) {
    if (!hasRootViewWindow) {
        return @"detached_root";
    }
    if (!hasForegroundActiveScene) {
        return @"scene_inactive";
    }
    return @"no_controller";
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

- (void)dealloc {
    [self slock_destroyFeedbackDiagnosticsUploads];
    NSArray<SlockThreadImageCompressionOperation *> *operations = @[];
    if (_slock_threadImageCompressionOperationsLock) {
        [_slock_threadImageCompressionOperationsLock lock];
        // Publish the terminal lifecycle fence before dropping the map.  Any worker/main block
        // that still owns a weak reference will observe this bit and must not invoke a renderer
        // callback after the module starts teardown.
        _slock_threadImageCompressionDestroyed = YES;
        operations = [_slock_threadImageCompressionOperations.allValues copy];
        [_slock_threadImageCompressionOperations removeAllObjects];
        [_slock_threadImageCompressionOperationsLock unlock];
    }
    for (SlockThreadImageCompressionOperation *operation in operations) {
        // Destruction is the sole force-cancel path: even a DELIVERED operation may still own a
        // tracked output until its callback turn has returned, so teardown must remove it.
        [operation markCancelledForce:YES];
        [operation removeOutputs];
    }
}

@end
