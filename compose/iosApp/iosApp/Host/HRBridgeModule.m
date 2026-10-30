#import "HRBridgeModule.h"
#import "RaftDiagnosticsPathAdmission.h"
#import "RaftDiagnosticsEnvironmentRows.h"

#import "RaftPageViewController.h"
#import <AVFoundation/AVFoundation.h>
#import <CommonCrypto/CommonDigest.h>
#import <Hands/Hands.h>
#import <ImageIO/ImageIO.h>
#import <shared/shared.h>
#import "iosApp-Swift.h"
#import <MMKV/MMKV.h>
#import <mach/mach_time.h>
#import <os/lock.h>
#import <pthread.h>
#import <Photos/Photos.h>
#import <SafariServices/SafariServices.h>
#import <PhotosUI/PhotosUI.h>
#import <SDWebImage/SDImageCache.h>
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

static NSString *const RaftBridgeDefaultLogTag = @"SlockKuikly";
static NSUInteger const RaftThreadAttachmentImageMaxEdge = 2048;
static CGFloat const RaftThreadAttachmentImageJPEGQuality = 0.82;
static NSString *const RaftThreadAttachmentImageCompressionErrorDomain =
    @"build.raft.app.thread-attachment-image-compression";
static NSString *const RaftThreadImageCompressionSource = @"composer_image_compressor";
static NSString *const RaftThreadImageCompressionDirectoryName = @"PickedAttachments";
// Caches/<RaftPickedAttachmentParentDirectoryName>/<RaftThreadImageCompressionDirectoryName>
// holds composer-picked/compressed attachments (camera roll, Files, image compressor).
static NSString *const RaftPickedAttachmentParentDirectoryName = @"Slock";
static NSUInteger const RaftThreadImageCompressionMaxRequestIdLength = 128;
static NSUInteger const RaftThreadImageCompressionMaxFileNameLength = 256;
static NSString *const RaftAttachmentExportDestinationFiles = @"files";
static NSString *const RaftAttachmentExportDestinationPhotos = @"photos";
static NSString *const RaftAttachmentExportDestinationOpen = @"open";
static NSString *const RaftAttachmentExportDestinationPreview = @"preview";
static NSString *const RaftAttachmentExportDirectoryName = @"SlockAttachmentExports";
// Camera captures are written as tmp/<UUID>.<RaftCameraCaptureFileExtension>.
static NSString *const RaftCameraCaptureFileExtension = @"jpg";
static NSString *const RaftThreadShareImageDirectoryName = @"RaftThreadShareImages";
static NSTimeInterval const RaftThreadShareImageMaximumAgeSeconds = 24.0 * 60.0 * 60.0;
static NSString *const RaftBridgeReplaceCurrentPageKey = @"slockReplaceCurrentPage";
static NSString *const RaftDiagnosticsLogDirName = @"raft_diagnostics_logs";
static NSString *const RaftDiagnosticsLogFileName = @"raft-diagnostics.jsonl";
static NSString *const RaftDiagnosticsFeedbackDirName = @"raft-feedback";
static NSString *const RaftDiagnosticsFeedbackFilePrefix = @"raft-feedback-";
static NSString *const RaftFeedbackDiagnosticsArchivePrefix = @"raft-feedback-diagnostics-";
static NSString *const RaftFeedbackDiagnosticsArchiveExtension = @"zip";
// Mirrors iosDatabaseBasePath() in compose/shared/src/iosMain/kotlin/build/raft/app/data/
// RelationalStoreFactory.ios.kt (Application Support/Slock/databases). That helper and
// RaftSqlDatabase.DatabaseName are Kotlin-internal, so they are not exported to Objective-C.
static NSString *const RaftSqlDatabaseDirectoryRelativePath = @"Slock/databases";
static NSString *const RaftDiagnosticsSummaryFileName = @"raft-diagnostics-summary.txt";
static NSString *const RaftFeedbackDiagnosticsContentType = @"application/zip";
static unsigned long long const RaftFeedbackDiagnosticsMaxBytes = 200ULL * 1024ULL * 1024ULL;
static NSUInteger const RaftFeedbackDiagnosticsMaxR2KeyLength = 1024;
static NSString *const RaftHandsFeedbackBaseURL = @"https://hands.build";
static NSString *const RaftHandsFeedbackAppSlug = @"raft-ios";
static NSString *const RaftHandsFeedbackClientKey = @"qk_2d6ac57a6532bc7ff1d3183b43bda99e2e732a99";
static NSString *const RaftApiEnvironmentMMKVNamespace = @"slock_api_server_environment";
static NSUInteger const RaftDiagnosticsMemoryEntryLimit = 512;
static NSUInteger const RaftDiagnosticsMessageMaxChars = 2048;
static NSUInteger const RaftDiagnosticsRecentMaxChars = 120000;
static unsigned long long const RaftDiagnosticsLogMaxBytes = 512ULL * 1024ULL;
static NSUInteger const RaftDiagnosticsLogMaxFiles = 4;
static NSUInteger const RaftDiagnosticsMaxLaunchSessions = 5;
static NSUInteger const RaftDiagnosticsAttachmentMaxLogFiles = 20;
static NSUInteger const RaftDiagnosticsMinimumRetainedSnapshotCount = 5;
static NSTimeInterval const RaftDiagnosticsFeedbackPruneMinimumAgeSeconds = 24.0 * 60.0 * 60.0;
static NSUInteger const RaftDiagnosticsOutstandingLimit = 512;
static NSUInteger const RaftDiagnosticsOrdinaryOutstandingLimit = 496;
static NSUInteger const RaftDiagnosticsRawLevelMaxChars = 16;
static NSUInteger const RaftDiagnosticsRawTagMaxChars = 128;
static NSUInteger const RaftDiagnosticsRawMessageMaxChars = 4096;
static NSUInteger const RaftDiagnosticsThreadNameMaxChars = 64;
static NSUInteger const RaftDiagnosticsQueueLabelMaxChars = 96;
static NSUInteger const RaftDiagnosticsExceptionPageMaxChars = 128;
static NSUInteger const RaftDiagnosticsExceptionReasonMaxChars = 1024;
static NSUInteger const RaftDiagnosticsExceptionStackMaxChars = 2048;
static char RaftDiagnosticsWriterQueueSpecificKey;
static os_unfair_lock RaftDiagnosticsAdmissionLock = OS_UNFAIR_LOCK_INIT;
static NSUInteger RaftDiagnosticsOutstandingCount = 0;
static uint64_t RaftDiagnosticsNextSequence = 0;
static atomic_uint_fast64_t RaftDiagnosticsAttemptedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsAcceptedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsAcceptedCriticalCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsDroppedOrdinaryCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsDroppedCriticalCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsDroppedContentionCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsRejectedInvalidCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsProcessedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsWrittenCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsCriticalSynchronizedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsWriteFailedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsRotationFailedCount = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t RaftDiagnosticsMaxOutstandingCount = ATOMIC_VAR_INIT(0);

@interface RaftDiagnosticsCapture : NSObject

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

@implementation RaftDiagnosticsCapture
@end

static NSDictionary *RaftBridgeDictionaryFromValue(id value) {
    if ([value isKindOfClass:NSDictionary.class]) {
        return (NSDictionary *)value;
    }
    if (![value isKindOfClass:NSString.class]) {
        return @{};
    }
    NSString *text = (NSString *)value;
    NSData *data = [text dataUsingEncoding:NSUTF8StringEncoding];
    if (!data) {
        return @{};
    }
    id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [object isKindOfClass:NSDictionary.class] ? object : @{};
}

static NSDictionary *RaftBridgeParams(NSDictionary *args) {
    return RaftBridgeDictionaryFromValue(args[RaftBridgeParamKey]);
}

static NSString *RaftBridgeString(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return [(NSString *)value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [[value stringValue] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    }
    return @"";
}

static NSString *RaftBridgeRawString(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return (NSString *)value;
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [value stringValue] ?: @"";
    }
    return @"";
}

static NSString *RaftFeedbackDraftAccountSegment(NSString *accountId) {
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

static NSString *RaftFeedbackDraftExtension(NSString *fileName, NSString *mimeType) {
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

static NSString *RaftFeedbackDraftTargetName(NSUInteger index, NSDictionary *file) {
    NSString *extension = RaftFeedbackDraftExtension(
        RaftBridgeString(file[@"fileName"]),
        RaftBridgeString(file[@"mimeType"]));
    return [NSString stringWithFormat:@"%02lu-attachment.%@", (unsigned long)index, extension];
}

static NSString *RaftFeedbackDraftDisplayName(NSString *fileName, NSString *targetName) {
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

static NSArray<NSDictionary *> *RaftFeedbackDraftFilesAtDirectory(
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
        NSString *targetName = RaftFeedbackDraftTargetName(index, file);
        NSURL *targetURL = [directory URLByAppendingPathComponent:targetName isDirectory:NO];
        NSNumber *targetSize = nil;
        if (![targetURL getResourceValue:&targetSize forKey:NSURLFileSizeKey error:nil] ||
            targetSize.unsignedLongLongValue == 0 || targetSize.unsignedLongLongValue > maxBytes) {
            return nil;
        }
        NSString *displayName = RaftFeedbackDraftDisplayName(
            RaftBridgeString(file[@"fileName"]), targetName);
        [attachments addObject:@{
            @"id" : RaftBridgeString(file[@"id"]),
            @"fileName" : displayName,
            @"mimeType" : RaftBridgeString(file[@"mimeType"]),
            @"sizeBytes" : targetSize,
            @"previewUri" : targetURL.absoluteString ?: @"",
            @"fileRef" : targetURL.absoluteString ?: @"",
            @"source" : @"settings_feedback_draft"
        }];
    }
    return attachments;
}

static NSString *RaftHandsFeedbackAttachmentBlock(NSArray<NSString *> *attachmentPaths) {
    NSMutableArray<NSString *> *attachmentNames = [NSMutableArray array];
    NSCharacterSet *lineBreaks = NSCharacterSet.newlineCharacterSet;
    for (id value in attachmentPaths ?: @[]) {
        NSString *path = RaftBridgeString(value);
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

static NSString *RaftBridgeBuildTime(void) {
    return RaftBridgeString(RAFT_IOS_BUILD_TIME);
}

static NSString *RaftBridgeGitCommit(void) {
    return RaftBridgeString(RAFT_IOS_GIT_COMMIT);
}

static NSDictionary *RaftDiagnosticsCurrentEnvironmentAbout(void) {
    __block NSDictionary *about = nil;
    dispatch_block_t collect = ^{
        NSDictionary *info = NSBundle.mainBundle.infoDictionary ?: @{};
        NSString *version = RaftBridgeString(info[@"CFBundleShortVersionString"]);
        NSString *build = RaftBridgeString(info[@"CFBundleVersion"]);
        NSString *gitCommit = RaftBridgeGitCommit();
        UIDevice *device = UIDevice.currentDevice;
        UIScreen *screen = UIScreen.mainScreen;
        CGRect bounds = screen.bounds;
        CGFloat scale = screen.scale;
        NSString *contentSizeCategory = @"--";
        if (@available(iOS 10.0, *)) {
            contentSizeCategory = RaftBridgeString(UIApplication.sharedApplication.preferredContentSizeCategory).length > 0
                ? RaftBridgeString(UIApplication.sharedApplication.preferredContentSizeCategory)
                : @"--";
        }
        about = @{
            @"appName" : RaftBridgeString(info[@"CFBundleDisplayName"]).length > 0 ? RaftBridgeString(info[@"CFBundleDisplayName"]) : @"Raft",
            @"versionLabel" : version,
            @"versionCodeLabel" : build,
            @"buildLabel" : build,
            @"sourceLabel" : gitCommit.length > 0 ? gitCommit : @"unknown",
            @"packageName" : RaftBridgeString(NSBundle.mainBundle.bundleIdentifier),
            @"buildTime" : RaftBridgeBuildTime(),
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
            @"socketStatusLabel" : [RaftIosRealtimeHost shared].statusLabel,
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

static BOOL RaftBridgeBool(id value) {
    if ([value isKindOfClass:NSNumber.class]) {
        return [(NSNumber *)value boolValue];
    }
    NSString *text = [RaftBridgeString(value) lowercaseString];
    return [text isEqualToString:@"true"] || [text isEqualToString:@"1"] || [text isEqualToString:@"yes"];
}

static MMKV *RaftBridgeApiEnvironmentStore(void) {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        [MMKV initializeMMKV:nil];
    });
    return [MMKV mmkvWithID:RaftApiEnvironmentMMKVNamespace] ?: [MMKV defaultMMKV];
}

static NSDictionary *RaftBridgeIdentityPageData(NSDictionary *pageData) {
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
            NSLog(@"[%@][warn] dropped non-identity route payload key=%@", RaftBridgeDefaultLogTag, key);
        }
    }
    return sanitized;
}

static BOOL RaftDiagnosticsIsAsciiIdentifierCharacter(unichar character) {
    return (character >= 'a' && character <= 'z') ||
        (character >= 'A' && character <= 'Z') ||
        (character >= '0' && character <= '9') ||
        character == '_' || character == '-';
}

static BOOL RaftDiagnosticsIsWhitespace(unichar character) {
    return [[NSCharacterSet whitespaceAndNewlineCharacterSet] characterIsMember:character];
}

static NSInteger RaftDiagnosticsHexValue(unichar character) {
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

static NSString *RaftDiagnosticsNormalizedCredentialKey(NSString *key) {
    NSMutableString *normalized = [NSMutableString stringWithCapacity:key.length];
    for (NSUInteger index = 0; index < key.length; index++) {
        unichar character = [key characterAtIndex:index];
        if (character == '\\' && index + 5 < key.length &&
            ([key characterAtIndex:index + 1] == 'u' || [key characterAtIndex:index + 1] == 'U')) {
            NSInteger decoded = 0;
            BOOL validEscape = YES;
            for (NSUInteger offset = 2; offset < 6; offset++) {
                NSInteger digit = RaftDiagnosticsHexValue([key characterAtIndex:index + offset]);
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

static BOOL RaftDiagnosticsIsCredentialKey(NSString *key) {
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
    NSString *canonicalKey = RaftDiagnosticsNormalizedCredentialKey(key);
    if ([keys containsObject:canonicalKey]) {
        return YES;
    }
    return canonicalKey.length > 1 && [canonicalKey hasPrefix:@"x"] &&
        [keys containsObject:[canonicalKey substringFromIndex:1]];
}

static BOOL RaftDiagnosticsParseAssignmentAtIndex(
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
    if (index > 0 && RaftDiagnosticsIsAsciiIdentifierCharacter([text characterAtIndex:index - 1])) {
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
        if (!RaftDiagnosticsIsAsciiIdentifierCharacter(quote)) {
            return NO;
        }
        while (cursor < text.length) {
            while (cursor < text.length && RaftDiagnosticsIsAsciiIdentifierCharacter([text characterAtIndex:cursor])) {
                cursor++;
            }
            keyEnd = cursor;
            NSUInteger nextWord = cursor;
            while (nextWord < text.length && RaftDiagnosticsIsWhitespace([text characterAtIndex:nextWord])) {
                nextWord++;
            }
            if (!requireCredentialKey || nextWord == cursor || nextWord >= text.length ||
                !RaftDiagnosticsIsAsciiIdentifierCharacter([text characterAtIndex:nextWord])) {
                break;
            }
            cursor = nextWord;
        }
    }
    while (cursor < text.length && RaftDiagnosticsIsWhitespace([text characterAtIndex:cursor])) {
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
    NSString *canonicalKey = RaftDiagnosticsNormalizedCredentialKey(key);
    if (requireCredentialKey && !RaftDiagnosticsIsCredentialKey(canonicalKey)) {
        return NO;
    }
    cursor++;
    while (cursor < text.length && RaftDiagnosticsIsWhitespace([text characterAtIndex:cursor])) {
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

static NSUInteger RaftDiagnosticsJsonContainerEnd(NSString *text, NSUInteger start) {
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

static NSString *RaftDiagnosticsRedactCredentialAssignments(NSString *content) {
    NSMutableString *result = [content mutableCopy] ?: [NSMutableString string];
    NSUInteger index = 0;
    while (index < result.length) {
        NSUInteger valueStart = 0;
        NSString *normalizedKey = nil;
        BOOL jsonStyle = NO;
        if (!RaftDiagnosticsParseAssignmentAtIndex(result, index, YES, &valueStart, &normalizedKey, &jsonStyle)) {
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
            cursor = RaftDiagnosticsJsonContainerEnd(result, cursor);
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
                    (RaftDiagnosticsIsWhitespace(character) || character == ',' || character == ';' || character == '&')) {
                    NSUInteger candidate = cursor + (RaftDiagnosticsIsWhitespace(character) ? 0 : 1);
                    while (candidate < result.length && RaftDiagnosticsIsWhitespace([result characterAtIndex:candidate])) {
                        candidate++;
                    }
                    if (candidate >= result.length ||
                        RaftDiagnosticsParseAssignmentAtIndex(result, candidate, NO, NULL, NULL, NULL)) {
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

static NSString *RaftBridgeSanitizedLogString(NSString *content) {
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
    return RaftDiagnosticsRedactCredentialAssignments(sanitized);
}

static void RaftBridgeRunOnMain(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

static NSUInteger RaftDiagnosticsMemoryStart = 0;
static NSUInteger RaftDiagnosticsMemoryCount = 0;

static NSMutableArray *RaftDiagnosticsMemorySlots(void) {
    static NSMutableArray *slots;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        slots = [NSMutableArray arrayWithCapacity:RaftDiagnosticsMemoryEntryLimit];
        for (NSUInteger index = 0; index < RaftDiagnosticsMemoryEntryLimit; index++) {
            [slots addObject:NSNull.null];
        }
    });
    return slots;
}

static dispatch_queue_t RaftDiagnosticsOwnerQueue(void) {
    static dispatch_queue_t queue;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        dispatch_queue_attr_t attributes =
            dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_UTILITY, 0);
        queue = dispatch_queue_create("build.raft.app.ios.diagnostics.owner", attributes);
        dispatch_queue_set_specific(
            queue,
            &RaftDiagnosticsWriterQueueSpecificKey,
            &RaftDiagnosticsWriterQueueSpecificKey,
            NULL
        );
    });
    return queue;
}

static id RaftDiagnosticsPerformOwnerOperation(id (^operation)(void)) {
    if (dispatch_get_specific(&RaftDiagnosticsWriterQueueSpecificKey) == &RaftDiagnosticsWriterQueueSpecificKey) {
        return operation ? operation() : nil;
    }
    dispatch_semaphore_t completion = dispatch_semaphore_create(0);
    __block id result = nil;
    os_unfair_lock_lock(&RaftDiagnosticsAdmissionLock);
    dispatch_async(RaftDiagnosticsOwnerQueue(), ^{
        @autoreleasepool {
            @try {
                result = operation ? operation() : nil;
            } @finally {
                dispatch_semaphore_signal(completion);
            }
        }
    });
    os_unfair_lock_unlock(&RaftDiagnosticsAdmissionLock);
    dispatch_semaphore_wait(completion, DISPATCH_TIME_FOREVER);
    return result;
}

static NSString *RaftDiagnosticsTimestampForMillis(int64_t timestampMillis) {
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

static NSString *RaftDiagnosticsDirectoryPath(void) {
    NSArray<NSString *> *urls = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES);
    NSString *base = urls.firstObject ?: NSTemporaryDirectory();
    return [base stringByAppendingPathComponent:RaftDiagnosticsLogDirName];
}

static NSString *RaftDiagnosticsLaunchSessionIdentifier(void) {
    static NSString *identifier;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        long long timestampMs = (long long)(NSDate.date.timeIntervalSince1970 * 1000.0);
        identifier = [NSString stringWithFormat:@"launch-%lld-%@", timestampMs, NSUUID.UUID.UUIDString.lowercaseString];
    });
    return identifier;
}

static NSArray<NSString *> *RaftDiagnosticsLaunchSessionDirectories(void) {
    NSString *root = RaftDiagnosticsDirectoryPath();
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

static NSString *RaftDiagnosticsCurrentLaunchDirectoryPath(void) {
    static NSString *path;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSString *root = RaftDiagnosticsDirectoryPath();
        path = [root stringByAppendingPathComponent:RaftDiagnosticsLaunchSessionIdentifier()];
        NSFileManager *fileManager = [NSFileManager defaultManager];
        [fileManager createDirectoryAtPath:path withIntermediateDirectories:YES attributes:nil error:nil];
        NSArray<NSString *> *sessions = RaftDiagnosticsLaunchSessionDirectories();
        for (NSUInteger index = RaftDiagnosticsMaxLaunchSessions; index < sessions.count; index++) {
            NSString *candidate = sessions[index];
            if (![candidate isEqualToString:path]) {
                [fileManager removeItemAtPath:candidate error:nil];
            }
        }
    });
    return path;
}

static NSString *RaftDiagnosticsActiveLogPath(void) {
    return [RaftDiagnosticsCurrentLaunchDirectoryPath() stringByAppendingPathComponent:RaftDiagnosticsLogFileName];
}

static BOOL RaftDiagnosticsIsHighSurrogate(unichar character) {
    return character >= 0xD800 && character <= 0xDBFF;
}

static BOOL RaftDiagnosticsIsLowSurrogate(unichar character) {
    return character >= 0xDC00 && character <= 0xDFFF;
}

static NSString *RaftDiagnosticsStringByReplacingInvalidSurrogates(NSString *source) {
    NSMutableString *result = [NSMutableString stringWithCapacity:source.length];
    for (NSUInteger index = 0; index < source.length; index++) {
        unichar character = [source characterAtIndex:index];
        if (RaftDiagnosticsIsHighSurrogate(character)) {
            if (index + 1 < source.length) {
                unichar next = [source characterAtIndex:index + 1];
                if (RaftDiagnosticsIsLowSurrogate(next)) {
                    [result appendFormat:@"%C%C", character, next];
                    index++;
                    continue;
                }
            }
            [result appendString:@"\uFFFD"];
            continue;
        }
        if (RaftDiagnosticsIsLowSurrogate(character)) {
            [result appendString:@"\uFFFD"];
            continue;
        }
        [result appendFormat:@"%C", character];
    }
    return result;
}

static NSUInteger RaftDiagnosticsSafePrefixEnd(NSString *source, NSUInteger maxLength) {
    NSUInteger end = MIN(source.length, maxLength);
    if (end < source.length && end > 0) {
        unichar previous = [source characterAtIndex:end - 1];
        unichar next = [source characterAtIndex:end];
        if (RaftDiagnosticsIsHighSurrogate(previous) && RaftDiagnosticsIsLowSurrogate(next)) {
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

static NSUInteger RaftDiagnosticsRawPrefixEnd(NSString *source, NSUInteger maxLength) {
    NSUInteger end = MIN(source.length, maxLength);
    if (end < source.length && end > 0) {
        unichar previous = [source characterAtIndex:end - 1];
        unichar next = [source characterAtIndex:end];
        if (RaftDiagnosticsIsHighSurrogate(previous) && RaftDiagnosticsIsLowSurrogate(next)) {
            end--;
        }
    }
    return end;
}

static NSString *RaftDiagnosticsValidClippedString(NSString *value, NSUInteger maxLength) {
    NSString *source = [value isKindOfClass:NSString.class] ? value : @"";
    NSUInteger end = RaftDiagnosticsSafePrefixEnd(source, maxLength);
    NSString *clipped = end < source.length ? [source substringToIndex:end] : source;
    return RaftDiagnosticsStringByReplacingInvalidSurrogates(clipped);
}

static NSString *RaftDiagnosticsClippedString(NSString *value, NSUInteger maxLength) {
    return RaftDiagnosticsValidClippedString(value, maxLength);
}

static NSString *RaftDiagnosticsClippedCopy(NSString *value, NSUInteger maxLength) {
    NSString *source = [value isKindOfClass:NSString.class] ? value : @"";
    NSUInteger end = RaftDiagnosticsRawPrefixEnd(source, maxLength);
    return [(end < source.length ? [source substringToIndex:end] : source) copy] ?: @"";
}

static BOOL RaftDiagnosticsContainsNonWhitespace(NSString *value) {
    for (NSUInteger index = 0; index < value.length; index++) {
        if (!RaftDiagnosticsIsWhitespace([value characterAtIndex:index])) {
            return YES;
        }
    }
    return NO;
}

static NSString *RaftDiagnosticsValidSuffixString(NSString *value, NSUInteger maxLength) {
    NSString *source = RaftDiagnosticsStringByReplacingInvalidSurrogates(
        [value isKindOfClass:NSString.class] ? value : @""
    );
    if (source.length <= maxLength) {
        return source;
    }
    NSUInteger start = source.length - maxLength;
    if (start < source.length && start > 0 &&
        RaftDiagnosticsIsLowSurrogate([source characterAtIndex:start]) &&
        RaftDiagnosticsIsHighSurrogate([source characterAtIndex:start - 1])) {
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

static NSString *RaftDiagnosticsBoundedUTF8String(const char *value, NSUInteger maxLength, NSString *fallback) {
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

static int64_t RaftDiagnosticsNowMillis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0) {
        return (int64_t)(NSDate.date.timeIntervalSince1970 * 1000.0);
    }
    return ((int64_t)now.tv_sec * 1000LL) + ((int64_t)now.tv_nsec / 1000000LL);
}

static BOOL RaftDiagnosticsRawRecordIsCritical(NSString *level, NSString *tag, NSString *message, BOOL forceCritical) {
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

static RaftDiagnosticsCapture *RaftDiagnosticsCreateCapture(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
) {
    NSString *rawLevel = RaftDiagnosticsClippedCopy(level ?: @"info", RaftDiagnosticsRawLevelMaxChars);
    NSString *rawTag = RaftDiagnosticsClippedCopy(tag ?: @"", RaftDiagnosticsRawTagMaxChars);
    NSString *rawMessage = RaftDiagnosticsClippedCopy(message ?: @"", RaftDiagnosticsRawMessageMaxChars);
    if (rawTag.length == 0 || rawMessage.length == 0 || !RaftDiagnosticsContainsNonWhitespace(rawMessage)) {
        return nil;
    }
    char threadNameBuffer[128] = {0};
    pthread_getname_np(pthread_self(), threadNameBuffer, sizeof(threadNameBuffer));
    BOOL mainThread = pthread_main_np() != 0;
    NSString *threadName = RaftDiagnosticsBoundedUTF8String(
        threadNameBuffer,
        RaftDiagnosticsThreadNameMaxChars,
        mainThread ? @"main" : @"background"
    );
    const char *queueLabelValue = dispatch_queue_get_label(DISPATCH_CURRENT_QUEUE_LABEL);
    NSString *queueLabel = RaftDiagnosticsBoundedUTF8String(
        queueLabelValue,
        RaftDiagnosticsQueueLabelMaxChars,
        mainThread ? @"com.apple.main-thread" : @"unknown"
    );
    uint64_t threadId = 0;
    pthread_threadid_np(NULL, &threadId);
    RaftDiagnosticsCapture *capture = [[RaftDiagnosticsCapture alloc] init];
    capture.rawLevel = rawLevel;
    capture.rawTag = rawTag;
    capture.rawMessage = rawMessage;
    capture.threadName = threadName;
    capture.queueLabel = queueLabel;
    capture.monotonicTicks = mach_continuous_time();
    capture.threadId = threadId;
    capture.timestampMillis = timestampMillis > 0 ? timestampMillis : RaftDiagnosticsNowMillis();
    capture.mainThread = mainThread;
    capture.rawMessageTruncated = (message.length > RaftDiagnosticsRawMessageMaxChars);
    capture.critical = RaftDiagnosticsRawRecordIsCritical(rawLevel, rawTag, rawMessage, forceCritical);
    capture.emitConsole = emitConsole;
    return capture;
}

static NSString *RaftDiagnosticsEventName(NSString *message) {
    NSString *event = message ?: @"";
    NSRange range = [event rangeOfString:@" "];
    if (range.location != NSNotFound) {
        event = [event substringToIndex:range.location];
    }
    return RaftDiagnosticsClippedString(event, 96);
}

static NSString * _Nullable RaftDiagnosticsJsonLine(NSDictionary *entry) {
    NSError *error = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:entry options:0 error:&error];
    if (!data) {
        return nil;
    }
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}

static void RaftDiagnosticsRemember(NSDictionary *entry) {
    NSMutableArray *slots = RaftDiagnosticsMemorySlots();
    NSUInteger index = 0;
    if (RaftDiagnosticsMemoryCount < RaftDiagnosticsMemoryEntryLimit) {
        index = (RaftDiagnosticsMemoryStart + RaftDiagnosticsMemoryCount) % RaftDiagnosticsMemoryEntryLimit;
        RaftDiagnosticsMemoryCount++;
    } else {
        index = RaftDiagnosticsMemoryStart;
        RaftDiagnosticsMemoryStart = (RaftDiagnosticsMemoryStart + 1) % RaftDiagnosticsMemoryEntryLimit;
    }
    [slots replaceObjectAtIndex:index withObject:entry];
}

static NSArray<NSDictionary *> *RaftDiagnosticsSnapshotMemoryEntries(void) {
    NSMutableArray *slots = RaftDiagnosticsMemorySlots();
    NSMutableArray<NSDictionary *> *entries = [NSMutableArray arrayWithCapacity:RaftDiagnosticsMemoryCount];
    for (NSUInteger offset = 0; offset < RaftDiagnosticsMemoryCount; offset++) {
        NSUInteger index = (RaftDiagnosticsMemoryStart + offset) % RaftDiagnosticsMemoryEntryLimit;
        id value = slots[index];
        if ([value isKindOfClass:NSDictionary.class]) {
            [entries addObject:value];
        }
    }
    return entries;
}

static BOOL RaftDiagnosticsIsLogFileName(NSString *name) {
    return [name hasPrefix:RaftDiagnosticsLogFileName];
}

static NSString *RaftDiagnosticsUniqueSnapshotPath(
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

static NSComparisonResult RaftDiagnosticsComparePathsByMtimeThenPath(NSString *left, NSString *right) {
    NSDate *leftDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:left error:nil] fileModificationDate]
        ?: NSDate.distantPast;
    NSDate *rightDate = [[[NSFileManager defaultManager] attributesOfItemAtPath:right error:nil] fileModificationDate]
        ?: NSDate.distantPast;
    NSComparisonResult timeResult = [rightDate compare:leftDate];
    return timeResult == NSOrderedSame ? [left compare:right] : timeResult;
}

static NSString *RaftDiagnosticsCanonicalDescendantFilePath(NSString *root, NSString *path) {
    return RaftDiagnosticsCanonicalDescendantRegularFilePath(NSFileManager.defaultManager, root, path);
}

static BOOL RaftIosPathHasControlCharacter(NSString *value) {
    return [value rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound;
}

static BOOL RaftIosPathIsSymlink(NSString *path) {
    if (path.length == 0) return NO;
    const char *fileSystemPath = path.fileSystemRepresentation;
    if (fileSystemPath == NULL) return NO;
    struct stat attributes;
    return lstat(fileSystemPath, &attributes) == 0 && S_ISLNK(attributes.st_mode);
}

static BOOL RaftIosPathIsSafeComponent(NSString *component) {
    return component.length > 0 &&
        ![component isEqualToString:@"."] &&
        ![component isEqualToString:@".."] &&
        !RaftIosPathHasControlCharacter(component);
}

static NSString * _Nullable RaftIosPathWithoutTrailingSlashes(NSString *path);

/*
 * iOS/Darwin presents a small, fixed set of system aliases as symlinks.  They
 * are the only symlinked ancestors accepted by the raw walk; arbitrary app
 * symlinks remain rejected.  Containment is still decided on the fully
 * resolved path after this allow-list check.
 */
static BOOL RaftIosIsAllowedDarwinAliasSymlink(NSString *path, const struct stat *attributes) {
    if (attributes == NULL || !S_ISLNK(attributes->st_mode)) return NO;
    NSString *trimmed = RaftIosPathWithoutTrailingSlashes(path);
    return [trimmed isEqualToString:@"/var"] || [trimmed isEqualToString:@"/tmp"];
}

static NSString * _Nullable RaftIosPathWithoutTrailingSlashes(NSString *path) {
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
static NSArray<NSString *> * _Nullable RaftIosRawAbsolutePathComponents(NSString *path) {
    NSString *trimmed = RaftIosPathWithoutTrailingSlashes(path);
    if (trimmed.length == 0) return nil;
    NSArray<NSString *> *parts = [trimmed componentsSeparatedByString:@"/"];
    if (parts.count == 0 || ![parts.firstObject isEqualToString:@""]) return nil;
    NSMutableArray<NSString *> *components = [NSMutableArray array];
    for (NSUInteger index = 1; index < parts.count; index++) {
        NSString *component = parts[index];
        if (!RaftIosPathIsSafeComponent(component)) return nil;
        [components addObject:component];
    }
    return components;
}

static BOOL RaftIosLstatPath(NSString *path, struct stat * _Nullable output) {
    if (path.length == 0) return NO;
    const char *fileSystemPath = path.fileSystemRepresentation;
    if (fileSystemPath == NULL) return NO;
    struct stat attributes;
    if (lstat(fileSystemPath, &attributes) != 0) return NO;
    if (output != NULL) *output = attributes;
    return YES;
}

/* Validate the root itself and every parent component with lstat/no-follow. */
static BOOL RaftIosValidateDirectoryRoot(NSString *root) {
    NSString *trimmed = RaftIosPathWithoutTrailingSlashes(root);
    NSArray<NSString *> *components = RaftIosRawAbsolutePathComponents(trimmed ?: @"");
    if (components == nil) return NO;
    struct stat rootAttributes;
    if (!RaftIosLstatPath(trimmed, &rootAttributes) ||
        (S_ISLNK(rootAttributes.st_mode) &&
            !RaftIosIsAllowedDarwinAliasSymlink(trimmed, &rootAttributes)) ||
        (!S_ISLNK(rootAttributes.st_mode) && !S_ISDIR(rootAttributes.st_mode))) {
        return NO;
    }
    NSString *cursor = @"/";
    for (NSString *component in components) {
        cursor = [cursor stringByAppendingPathComponent:component];
        struct stat attributes;
        if (!RaftIosLstatPath(cursor, &attributes) ||
            (S_ISLNK(attributes.st_mode)
                ? !RaftIosIsAllowedDarwinAliasSymlink(cursor, &attributes)
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
static NSString *RaftIosCacheOwnedTemporaryPath(NSString *root, NSString *candidatePath) {
    if (root.length == 0 || candidatePath.length == 0 ||
        RaftIosPathHasControlCharacter(root) || RaftIosPathHasControlCharacter(candidatePath)) {
        return nil;
    }
    NSString *rawRoot = RaftIosPathWithoutTrailingSlashes(root);
    NSString *rawCandidate = RaftIosPathWithoutTrailingSlashes(candidatePath);
    NSArray<NSString *> *rootComponents = RaftIosRawAbsolutePathComponents(rawRoot ?: @"");
    NSArray<NSString *> *candidateComponents = RaftIosRawAbsolutePathComponents(rawCandidate ?: @"");
    if (rawRoot.length == 0 || rawCandidate.length == 0 || rootComponents == nil ||
        candidateComponents == nil || !RaftIosValidateDirectoryRoot(rawRoot)) {
        return nil;
    }
    /* Validate the candidate's complete raw path without following any component. */
    NSString *cursor = @"/";
    for (NSUInteger index = 0; index < candidateComponents.count; index++) {
        NSString *component = candidateComponents[index];
        cursor = [cursor stringByAppendingPathComponent:component];
        struct stat attributes;
        BOOL isFinal = index + 1 == candidateComponents.count;
        if (!RaftIosLstatPath(cursor, &attributes)) return nil;
        if (isFinal) {
            if (S_ISLNK(attributes.st_mode) || !S_ISREG(attributes.st_mode) || attributes.st_size <= 0) {
                return nil;
            }
        } else if (S_ISLNK(attributes.st_mode)
            ? !RaftIosIsAllowedDarwinAliasSymlink(cursor, &attributes)
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

static NSString *RaftIosFilePathFromRetirementRef(NSString *fileRef) {
    if (fileRef.length == 0 || RaftIosPathHasControlCharacter(fileRef)) return nil;
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
RaftIosRetireThreadComposerTemporaryFileRefs(
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
        RaftIosPathHasControlCharacter(cleanSessionId)) {
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
        if (root.length == 0 || RaftIosPathIsSymlink(root)) continue;
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
        NSString *rawPath = RaftIosFilePathFromRetirementRef(ref);
        NSString *ownedPath = nil;
        for (NSString *root in roots) {
            ownedPath = RaftIosCacheOwnedTemporaryPath(root, rawPath ?: @"");
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

@interface RaftIosThreadComposerTemporaryFileRetirementHandler : NSObject
    <SharedThreadComposerTemporaryFileRetirementHandler>
@end

@implementation RaftIosThreadComposerTemporaryFileRetirementHandler
- (SharedThreadComposerTemporaryFileRetirementResult *)retireSessionId:(NSString *)sessionId
                                                               fileRefs:(NSSet<NSString *> *)fileRefs {
    return RaftIosRetireThreadComposerTemporaryFileRefs(sessionId, fileRefs);
}
@end

static RaftIosThreadComposerTemporaryFileRetirementHandler *RaftIosRetirementHandlerInstance;

void RaftIosInstallThreadComposerTemporaryFileRetirement(void) {
    if (RaftIosRetirementHandlerInstance == nil) {
        RaftIosRetirementHandlerInstance = [RaftIosThreadComposerTemporaryFileRetirementHandler new];
    }
    [SharedThreadComposerTemporaryFileRetirement.shared
        installHandler:RaftIosRetirementHandlerInstance];
    RaftIosDiagnosticsRecord(
        @"info",
        @"SlockFilePreviewCleanup",
        @"ios_retirement_handler_installed");
}

static NSArray<NSString *> *RaftDiagnosticsLogFilesAtRoot(NSString *root, NSUInteger maxSessions) {
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
            if (!RaftDiagnosticsIsLogFileName(name)) {
                continue;
            }
            NSString *path = RaftDiagnosticsCanonicalDescendantFilePath(
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
            return RaftDiagnosticsComparePathsByMtimeThenPath(left, right);
        }];
        [paths addObjectsFromArray:sessionPaths];
    }
    if (sessionCount < RaftDiagnosticsMaxLaunchSessions) {
        NSMutableArray<NSString *> *flatPaths = [NSMutableArray array];
        for (NSString *name in names) {
            if (!RaftDiagnosticsIsLogFileName(name)) continue;
            NSString *path = RaftDiagnosticsCanonicalDescendantFilePath(
                root,
                [root stringByAppendingPathComponent:name]
            );
            if (!path) continue;
            NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
            if ([attributes[NSFileSize] unsignedLongLongValue] > 0) [flatPaths addObject:path];
        }
        [flatPaths sortUsingComparator:^NSComparisonResult(NSString *left, NSString *right) {
            return RaftDiagnosticsComparePathsByMtimeThenPath(left, right);
        }];
        [paths addObjectsFromArray:flatPaths];
    }
    return paths;
}

static NSArray<NSString *> *RaftDiagnosticsLogFiles(void) {
    return RaftDiagnosticsLogFilesAtRoot(
        RaftDiagnosticsDirectoryPath(),
        RaftDiagnosticsMaxLaunchSessions
    );
}

static BOOL RaftDiagnosticsRemoveItemIfPresent(NSFileManager *fileManager, NSString *path) {
    return ![fileManager fileExistsAtPath:path] || [fileManager removeItemAtPath:path error:nil];
}

static BOOL RaftDiagnosticsRotateLogsIfNeeded(NSString *activePath) {
    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:activePath error:nil];
    if ([attributes[NSFileSize] unsignedLongLongValue] < RaftDiagnosticsLogMaxBytes) {
        return YES;
    }
    NSString *dir = [activePath stringByDeletingLastPathComponent];
    NSFileManager *fileManager = [NSFileManager defaultManager];
    for (NSInteger index = (NSInteger)RaftDiagnosticsLogMaxFiles - 1; index >= 1; index--) {
        NSString *source = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.%ld", RaftDiagnosticsLogFileName, (long)index]];
        NSString *target = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.%ld", RaftDiagnosticsLogFileName, (long)index + 1]];
        if (![fileManager fileExistsAtPath:source]) {
            continue;
        }
        if ((NSUInteger)index == RaftDiagnosticsLogMaxFiles - 1) {
            if (!RaftDiagnosticsRemoveItemIfPresent(fileManager, source)) {
                return NO;
            }
        } else {
            if (!RaftDiagnosticsRemoveItemIfPresent(fileManager, target)) {
                return NO;
            }
            if (![fileManager moveItemAtPath:source toPath:target error:nil]) {
                return NO;
            }
        }
    }
    NSString *rotated = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@.1", RaftDiagnosticsLogFileName]];
    if (!RaftDiagnosticsRemoveItemIfPresent(fileManager, rotated)) {
        return NO;
    }
    return [fileManager moveItemAtPath:activePath toPath:rotated error:nil];
}

static BOOL RaftDiagnosticsAppendEntry(NSDictionary *entry, BOOL synchronize) {
    @try {
        NSString *activePath = RaftDiagnosticsActiveLogPath();
        NSString *dir = activePath.stringByDeletingLastPathComponent;
        NSFileManager *fileManager = [NSFileManager defaultManager];
        NSError *directoryError = nil;
        if (![fileManager createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:&directoryError]) {
            return NO;
        }
        if (!RaftDiagnosticsRotateLogsIfNeeded(activePath)) {
            atomic_fetch_add_explicit(&RaftDiagnosticsRotationFailedCount, 1, memory_order_relaxed);
        }
        NSString *jsonLine = RaftDiagnosticsJsonLine(entry);
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

static NSString *RaftDiagnosticsProcessSessionIdentifier(void) {
    static NSString *identifier;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        identifier = NSUUID.UUID.UUIDString.lowercaseString;
    });
    return identifier;
}

static void RaftDiagnosticsProcessCapture(RaftDiagnosticsCapture *capture) {
    NSString *normalizedTag = [RaftBridgeSanitizedLogString(
        RaftDiagnosticsStringByReplacingInvalidSurrogates(capture.rawTag)
    ) stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    NSString *safeTag = RaftDiagnosticsClippedString(
        normalizedTag.length > 0 ? normalizedTag : RaftBridgeDefaultLogTag,
        48
    );
    NSString *safeMessage = [RaftBridgeSanitizedLogString(
        RaftDiagnosticsStringByReplacingInvalidSurrogates(capture.rawMessage)
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
    BOOL truncated = capture.rawMessageTruncated || safeMessage.length > RaftDiagnosticsMessageMaxChars;
    NSString *finalMessage = RaftDiagnosticsClippedString(safeMessage, RaftDiagnosticsMessageMaxChars);
    NSString *safeThread = RaftDiagnosticsClippedString(
        RaftBridgeSanitizedLogString(capture.threadName),
        RaftDiagnosticsThreadNameMaxChars
    );
    NSString *safeQueue = RaftDiagnosticsClippedString(
        RaftBridgeSanitizedLogString(capture.queueLabel),
        RaftDiagnosticsQueueLabelMaxChars
    );
    uint64_t droppedOrdinary = atomic_load_explicit(&RaftDiagnosticsDroppedOrdinaryCount, memory_order_relaxed);
    uint64_t droppedCritical = atomic_load_explicit(&RaftDiagnosticsDroppedCriticalCount, memory_order_relaxed);
    NSDictionary *entry = @{
        @"ts" : RaftDiagnosticsTimestampForMillis(capture.timestampMillis),
        @"timestampMillis" : @(capture.timestampMillis),
        @"level" : normalizedLevel,
        @"tag" : safeTag,
        @"event" : RaftDiagnosticsEventName(finalMessage),
        @"message" : finalMessage,
        @"thread" : safeThread,
        @"threadId" : @(capture.threadId),
        @"queue" : safeQueue,
        @"mainThread" : @(capture.mainThread),
        @"processId" : @(NSProcessInfo.processInfo.processIdentifier),
        @"processSession" : RaftDiagnosticsProcessSessionIdentifier(),
        @"seq" : @(capture.sequence),
        @"monotonicTicks" : @(capture.monotonicTicks),
        @"dropped" : @(droppedOrdinary + droppedCritical),
        @"droppedOrdinary" : @(droppedOrdinary),
        @"droppedCritical" : @(droppedCritical),
        @"truncated" : @(truncated),
        @"critical" : @(capture.critical)
    };
    RaftDiagnosticsRemember(entry);
    if (capture.emitConsole) {
        NSLog(@"[%@][%@] %@", safeTag, normalizedLevel, finalMessage);
    }
    if (RaftDiagnosticsAppendEntry(entry, capture.critical)) {
        atomic_fetch_add_explicit(&RaftDiagnosticsWrittenCount, 1, memory_order_relaxed);
        if (capture.critical) {
            atomic_fetch_add_explicit(&RaftDiagnosticsCriticalSynchronizedCount, 1, memory_order_relaxed);
        }
    } else {
        atomic_fetch_add_explicit(&RaftDiagnosticsWriteFailedCount, 1, memory_order_relaxed);
    }
}

static uint64_t RaftDiagnosticsRecordWithContext(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
) {
    atomic_fetch_add_explicit(&RaftDiagnosticsAttemptedCount, 1, memory_order_relaxed);
    RaftDiagnosticsCapture *capture = RaftDiagnosticsCreateCapture(
        level,
        tag,
        message,
        timestampMillis,
        emitConsole,
        forceCritical
    );
    if (!capture) {
        atomic_fetch_add_explicit(&RaftDiagnosticsRejectedInvalidCount, 1, memory_order_relaxed);
        return 0;
    }
    if (!os_unfair_lock_trylock(&RaftDiagnosticsAdmissionLock)) {
        atomic_fetch_add_explicit(&RaftDiagnosticsDroppedContentionCount, 1, memory_order_relaxed);
        atomic_fetch_add_explicit(
            capture.critical ? &RaftDiagnosticsDroppedCriticalCount : &RaftDiagnosticsDroppedOrdinaryCount,
            1,
            memory_order_relaxed
        );
        return 0;
    }
    NSUInteger limit = capture.critical
        ? RaftDiagnosticsOutstandingLimit
        : RaftDiagnosticsOrdinaryOutstandingLimit;
    if (RaftDiagnosticsOutstandingCount >= limit) {
        atomic_fetch_add_explicit(
            capture.critical ? &RaftDiagnosticsDroppedCriticalCount : &RaftDiagnosticsDroppedOrdinaryCount,
            1,
            memory_order_relaxed
        );
        os_unfair_lock_unlock(&RaftDiagnosticsAdmissionLock);
        return 0;
    }
    capture.sequence = ++RaftDiagnosticsNextSequence;
    RaftDiagnosticsOutstandingCount++;
    uint64_t maxOutstanding = atomic_load_explicit(&RaftDiagnosticsMaxOutstandingCount, memory_order_relaxed);
    if (RaftDiagnosticsOutstandingCount > maxOutstanding) {
        atomic_store_explicit(
            &RaftDiagnosticsMaxOutstandingCount,
            RaftDiagnosticsOutstandingCount,
            memory_order_relaxed
        );
    }
    atomic_fetch_add_explicit(&RaftDiagnosticsAcceptedCount, 1, memory_order_relaxed);
    if (capture.critical) {
        atomic_fetch_add_explicit(&RaftDiagnosticsAcceptedCriticalCount, 1, memory_order_relaxed);
    }
    dispatch_async(RaftDiagnosticsOwnerQueue(), ^{
        @autoreleasepool {
            @try {
                RaftDiagnosticsProcessCapture(capture);
            } @finally {
                atomic_fetch_add_explicit(&RaftDiagnosticsProcessedCount, 1, memory_order_relaxed);
                os_unfair_lock_lock(&RaftDiagnosticsAdmissionLock);
                if (RaftDiagnosticsOutstandingCount > 0) {
                    RaftDiagnosticsOutstandingCount--;
                }
                os_unfair_lock_unlock(&RaftDiagnosticsAdmissionLock);
            }
        }
    });
    uint64_t sequence = capture.sequence;
    os_unfair_lock_unlock(&RaftDiagnosticsAdmissionLock);
    return sequence;
}

static void RaftDiagnosticsRecord(NSString *level, NSString *tag, NSString *message) {
    RaftDiagnosticsRecordWithContext(level, tag, message, 0, NO, NO);
}

void RaftIosDiagnosticsRecord(NSString *level, NSString *tag, NSString *message) {
    RaftDiagnosticsRecordWithContext(level, tag, message, 0, NO, NO);
}

void RaftIosDiagnosticsRecordWithContext(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
) {
    RaftDiagnosticsRecordWithContext(level, tag, message, timestampMillis, emitConsole, forceCritical);
}

void RaftIosDiagnosticsRecordCriticalSynchronously(NSString *tag, NSString *message) {
    NSString *boundedTag = RaftDiagnosticsClippedCopy(
        tag ?: RaftBridgeDefaultLogTag,
        RaftDiagnosticsRawTagMaxChars
    );
    NSString *boundedMessage = RaftDiagnosticsClippedCopy(
        message ?: @"",
        RaftDiagnosticsRawMessageMaxChars
    );
    NSString *safeTag = RaftDiagnosticsClippedString(
        [RaftBridgeSanitizedLogString(
            RaftDiagnosticsStringByReplacingInvalidSurrogates(boundedTag)
        )
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet],
        48
    );
    NSString *safeMessage = RaftDiagnosticsClippedString(
        [RaftBridgeSanitizedLogString(RaftDiagnosticsStringByReplacingInvalidSurrogates(boundedMessage))
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet],
        RaftDiagnosticsMessageMaxChars
    );
    if (safeMessage.length == 0) {
        return;
    }
    if (safeTag.length == 0) {
        safeTag = RaftBridgeDefaultLogTag;
    }
    NSLog(@"[%@][error] %@", safeTag, safeMessage);
    RaftDiagnosticsRecordWithContext(@"error", safeTag, safeMessage, 0, NO, YES);
}

void RaftIosDiagnosticsRecordUnhandledException(
    NSString *pageName,
    NSInteger mode,
    NSString *reason,
    NSString *stack
) {
    NSString *boundedPage = RaftDiagnosticsClippedCopy(
        pageName ?: @"unknown",
        RaftDiagnosticsExceptionPageMaxChars
    );
    NSString *boundedReason = RaftDiagnosticsClippedCopy(
        reason ?: @"KuiklyUnhandledException",
        RaftDiagnosticsExceptionReasonMaxChars
    );
    NSString *boundedStack = RaftDiagnosticsClippedCopy(
        stack ?: @"",
        RaftDiagnosticsExceptionStackMaxChars
    );
    NSString *message = [NSString stringWithFormat:@"kuikly_unhandled_exception page=%@ mode=%ld reason=%@ stack=%@",
        boundedPage,
        (long)mode,
        boundedReason,
        boundedStack];
    RaftIosDiagnosticsRecordCriticalSynchronously(@"SlockKuikly", message);
}

static NSString *RaftDiagnosticsExportRecentJsonl(NSUInteger maxChars) {
    NSMutableArray<NSString *> *lines = [NSMutableArray array];
    for (NSDictionary *entry in RaftDiagnosticsSnapshotMemoryEntries()) {
        NSString *line = RaftDiagnosticsJsonLine(entry);
        if (line) {
            [lines addObject:line];
        }
    }
    NSString *text = [lines componentsJoinedByString:@"\n"];
    if (text.length > maxChars) {
        return RaftDiagnosticsValidSuffixString(text, maxChars);
    }
    return text;
}

static NSString *RaftDiagnosticsReadTail(NSString *path, NSUInteger maxChars) {
    NSData *data = [NSData dataWithContentsOfFile:path] ?: [NSData data];
    NSString *text = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"";
    text = RaftBridgeSanitizedLogString(text);
    if (text.length > maxChars) {
        return RaftDiagnosticsValidSuffixString(text, maxChars);
    }
    return text;
}

static NSString *RaftDiagnosticsExportRecentText(NSUInteger maxChars) {
    NSString *result = RaftDiagnosticsPerformOwnerOperation(^id{
        NSArray<NSString *> *files = RaftDiagnosticsLogFiles();
        NSMutableString *text = [NSMutableString string];
        [text appendString:@"App-owned recent diagnostics\n"];
        [text appendFormat:@"Files: %lu\n", (unsigned long)files.count];
        [text appendFormat:
            @"Queue: attempted=%llu accepted=%llu acceptedCritical=%llu rejectedInvalid=%llu processed=%llu written=%llu criticalSynchronized=%llu writeFailed=%llu rotationFailed=%llu droppedOrdinary=%llu droppedCritical=%llu droppedContention=%llu maxDepth=%lu\n",
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsAttemptedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsAcceptedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsAcceptedCriticalCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsRejectedInvalidCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsProcessedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsWrittenCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsCriticalSynchronizedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsWriteFailedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsRotationFailedCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsDroppedOrdinaryCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsDroppedCriticalCount, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&RaftDiagnosticsDroppedContentionCount, memory_order_relaxed),
            (unsigned long)atomic_load_explicit(&RaftDiagnosticsMaxOutstandingCount, memory_order_relaxed)
        ];
        NSString *memoryJsonl = RaftDiagnosticsExportRecentJsonl(maxChars / 2);
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
            [text appendString:RaftDiagnosticsReadTail(path, MAX(fileBudget / MAX(fileCount, 1), 1000))];
        }
        if (text.length > maxChars) {
            return RaftDiagnosticsValidSuffixString(text, maxChars);
        }
        return text;
    });
    return result ?: @"";
}

static void RaftDiagnosticsClear(void) {
    RaftDiagnosticsPerformOwnerOperation(^id{
        NSMutableArray *slots = RaftDiagnosticsMemorySlots();
        for (NSUInteger index = 0; index < RaftDiagnosticsMemoryEntryLimit; index++) {
            [slots replaceObjectAtIndex:index withObject:NSNull.null];
        }
        RaftDiagnosticsMemoryStart = 0;
        RaftDiagnosticsMemoryCount = 0;
        NSString *dir = RaftDiagnosticsDirectoryPath();
        NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil] ?: @[];
        for (NSString *name in names) {
            if (RaftDiagnosticsIsLogFileName(name) || [name hasPrefix:@"launch-"] ||
                [name hasPrefix:RaftDiagnosticsFeedbackDirName]) {
                [[NSFileManager defaultManager] removeItemAtPath:[dir stringByAppendingPathComponent:name] error:nil];
            }
        }
        return NSNull.null;
    });
}

static NSString *RaftDiagnosticsBuildExportText(NSDictionary *about, BOOL omitEmptyRows) {
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
    [text appendString:RaftDiagnosticsExportRecentText(RaftDiagnosticsRecentMaxChars)];
    return text;
}

static NSString *RaftDiagnosticsEnvironmentSectionFromExportText(NSString *exportText) {
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

static NSString *RaftDiagnosticsFeedbackDirectoryPath(void) {
    return [RaftDiagnosticsDirectoryPath() stringByAppendingPathComponent:RaftDiagnosticsFeedbackDirName];
}

static id RaftDiagnosticsSanitizedJsonValue(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return RaftBridgeSanitizedLogString((NSString *)value);
    }
    if ([value isKindOfClass:NSArray.class]) {
        NSMutableArray *sanitized = [NSMutableArray arrayWithCapacity:[(NSArray *)value count]];
        for (id item in (NSArray *)value) {
            [sanitized addObject:RaftDiagnosticsSanitizedJsonValue(item) ?: NSNull.null];
        }
        return sanitized;
    }
    if ([value isKindOfClass:NSDictionary.class]) {
        NSMutableDictionary *sanitized = [NSMutableDictionary dictionaryWithCapacity:[(NSDictionary *)value count]];
        [(NSDictionary *)value enumerateKeysAndObjectsUsingBlock:^(id key, id item, BOOL *stop) {
            (void)stop;
            NSString *stringKey = [key isKindOfClass:NSString.class] ? key : [key description];
            if (RaftDiagnosticsIsCredentialKey(stringKey)) {
                sanitized[stringKey] = @"<redacted>";
            } else {
                sanitized[stringKey] = RaftDiagnosticsSanitizedJsonValue(item) ?: NSNull.null;
            }
        }];
        return sanitized;
    }
    return value ?: NSNull.null;
}

static BOOL RaftDiagnosticsWriteSanitizedSnapshotLog(NSString *sourcePath, NSString *destinationPath) {
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
        id sanitizedObject = RaftDiagnosticsSanitizedJsonValue(object);
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
        NSString *omissionLine = RaftDiagnosticsJsonLine(@{
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

static void RaftDiagnosticsPruneFeedbackFiles(NSString *dir) {
    NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil] ?: @[];
    NSMutableArray<NSString *> *paths = [NSMutableArray array];
    for (NSString *name in names) {
        if (![name hasPrefix:RaftDiagnosticsFeedbackFilePrefix]) {
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
    for (NSUInteger index = RaftDiagnosticsMinimumRetainedSnapshotCount; index < paths.count; index++) {
        NSString *path = paths[index];
        NSDate *modifiedAt = [[[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil] fileModificationDate]
            ?: NSDate.distantPast;
        if ([now timeIntervalSinceDate:modifiedAt] >= RaftDiagnosticsFeedbackPruneMinimumAgeSeconds) {
            [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        }
    }
}

static NSDictionary *RaftDiagnosticsPrepareAttachmentFiles(NSDictionary *about, NSString *reason, BOOL includeBodyBase64) {
    NSDictionary *result = RaftDiagnosticsPerformOwnerOperation(^id{
        NSString *summaryText = RaftDiagnosticsStringByReplacingInvalidSurrogates(
            RaftBridgeSanitizedLogString(RaftDiagnosticsBuildExportText(about ?: @{}, NO))
        );
        NSString *safeReason = RaftBridgeSanitizedLogString(reason ?: @"diagnostics").lowercaseString;
        NSData *summaryData = [summaryText dataUsingEncoding:NSUTF8StringEncoding];
        if (!summaryData) {
            return @{
                @"status" : @"error",
                @"code" : @"diagnostics_summary_encoding_failed",
                @"message" : @"Unable to encode diagnostics summary."
            };
        }
        NSString *dir = RaftDiagnosticsFeedbackDirectoryPath();
        NSFileManager *fileManager = [NSFileManager defaultManager];
        [fileManager createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
        long long timestampMs = RaftDiagnosticsNowMillis();
        NSString *snapshotIdentifier = NSUUID.UUID.UUIDString.lowercaseString;
        NSString *snapshotDir =
            [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"%@%lld-%@",
                RaftDiagnosticsFeedbackFilePrefix,
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
        NSString *fileName = RaftDiagnosticsSummaryFileName;
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
        NSArray<NSString *> *files = RaftDiagnosticsLogFiles();
        NSUInteger fileCount = MIN(files.count, RaftDiagnosticsAttachmentMaxLogFiles);
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
            NSString *snapshotLogPath = RaftDiagnosticsUniqueSnapshotPath(
                snapshotLogDir,
                RaftDiagnosticsSnapshotLogFileName(sessionName, logPath.lastPathComponent),
                reservedSnapshotPaths
            );
            if (RaftDiagnosticsWriteSanitizedSnapshotLog(logPath, snapshotLogPath)) {
                [attachmentPaths addObject:snapshotLogPath];
            } else {
                [fileManager removeItemAtPath:snapshotLogPath error:nil];
            }
        }
        RaftDiagnosticsPruneFeedbackFiles(dir);
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
        RaftDiagnosticsRecord(
            @"info",
            @"SlockDiagnostics",
            [NSString stringWithFormat:@"diagnostics_attachments_prepared reason=%@ summary=%@ bytes=%@ paths=%lu",
                result[@"reason"] ?: @"diagnostics",
                result[@"fileName"] ?: RaftDiagnosticsSummaryFileName,
                result[@"sizeBytes"] ?: @0,
                (unsigned long)paths.count]
        );
    }
    return result;
}


static NSDictionary *RaftDiagnosticsCrashAbout(long long crashAtMillis) {
    NSMutableDictionary *about = [RaftDiagnosticsCurrentEnvironmentAbout() mutableCopy];
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

NSArray<NSString *> *RaftIosPrepareDiagnosticsAttachmentPaths(NSString *reason) {
    NSMutableDictionary *about = [RaftDiagnosticsCurrentEnvironmentAbout() mutableCopy];
    about[@"diagnosticsReason"] = reason ?: @"crash";
    NSDictionary *attachments = RaftDiagnosticsPrepareAttachmentFiles(about, reason ?: @"crash", NO);
    NSArray *paths = [attachments[@"attachmentPaths"] isKindOfClass:NSArray.class] ? attachments[@"attachmentPaths"] : @[];
    return paths;
}

NSArray<NSString *> *RaftIosPrepareDiagnosticsAttachmentPathsForCrash(int64_t crashAtMillis) {
    NSString *reason = crashAtMillis > 0
        ? [NSString stringWithFormat:@"crash-%lld", (long long)crashAtMillis]
        : @"crash";
    NSDictionary *attachments = RaftDiagnosticsPrepareAttachmentFiles(RaftDiagnosticsCrashAbout((long long)crashAtMillis), reason, NO);
    NSArray *paths = [attachments[@"attachmentPaths"] isKindOfClass:NSArray.class] ? attachments[@"attachmentPaths"] : @[];
    return paths;
}

NSString *RaftIosPrepareDiagnosticsCrashAttachmentPath(void) {
    return RaftIosPrepareDiagnosticsAttachmentPaths(@"crash").firstObject;
}

static void RaftIosRegisterHandsDiagnosticsProvider(void) {
    Class crashReporter = NSClassFromString(@"HandsCrashReporter");
    SEL selector = NSSelectorFromString(@"setDiagnosticsProvider:");
    if (!crashReporter || ![crashReporter respondsToSelector:selector]) {
        RaftDiagnosticsRecord(@"warn", @"SlockDiagnostics", @"hands_diagnostics_provider_unavailable");
        return;
    }
    NSArray<NSString *> *_Nullable (^provider)(int64_t) = ^NSArray<NSString *> *_Nullable(int64_t crashAtMillis) {
        return RaftIosPrepareDiagnosticsAttachmentPathsForCrash(crashAtMillis);
    };
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
    [crashReporter performSelector:selector withObject:[provider copy]];
#pragma clang diagnostic pop
    RaftDiagnosticsRecord(@"info", @"SlockDiagnostics", @"hands_diagnostics_provider_registered crashAtMillis=true");
}

void RaftIosInstallHandsReporting(void) {
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
        RaftIosRegisterHandsDiagnosticsProvider();
    });
}

static NSString *RaftAttachmentExportFileName(NSString *value) {
    NSString *source = RaftBridgeString(value).lastPathComponent;
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

static UTType *RaftPreferredPickedMediaType(NSItemProvider *provider) {
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

static NSString *RaftPickedAttachmentDisplayName(NSString *suggestedName,
                                                   NSURL *sourceURL,
                                                   NSString *mimeType) {
    NSString *displayName =
        RaftAttachmentExportFileName(suggestedName.length > 0 ? suggestedName : sourceURL.lastPathComponent);
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

static BOOL RaftAttachmentIsImage(NSString *fileName, NSString *mimeType, NSURL *sourceURL) {
    NSString *normalizedMimeType = RaftBridgeString(mimeType).lowercaseString;
    normalizedMimeType = [[normalizedMimeType componentsSeparatedByString:@";"] firstObject] ?: @"";
    NSString *nameExtension = RaftBridgeString(fileName).pathExtension.lowercaseString;
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

static NSError *RaftThreadAttachmentImageCompressionError(NSString *message) {
    return [NSError errorWithDomain:RaftThreadAttachmentImageCompressionErrorDomain
                                code:1
                            userInfo:@{ NSLocalizedDescriptionKey :
                                            message.length > 0 ? message : @"Unable to compress image attachment" }];
}

static BOOL RaftIsThreadAttachmentImageCompressionError(NSError *error) {
    return [error.domain isEqualToString:RaftThreadAttachmentImageCompressionErrorDomain];
}

static NSDictionary *RaftThreadImageCompressionFailurePayload(void) {
    return @{
        @"code" : @400,
        @"errorCode" : @"attachment_image_compression_failed",
        @"errorBodyText" : @"Unable to compress image attachment"
    };
}

static NSString *RaftThreadImageCompressionMimeType(NSString *value) {
    NSString *mime = RaftBridgeString(value).lowercaseString;
    return [[mime componentsSeparatedByString:@";"] firstObject] ?: @"";
}

static BOOL RaftThreadImageCompressionSafeName(NSString *value) {
    NSString *name = RaftBridgeString(value);
    if (name.length == 0 || name.length > RaftThreadImageCompressionMaxFileNameLength) {
        return NO;
    }
    if ([name containsString:@"/"] || [name containsString:@"\\"] || [name containsString:@".."] ||
        [name rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound) {
        return NO;
    }
    return YES;
}

static NSString *RaftThreadImageCompressionOutputName(NSString *fileName) {
    NSString *leaf = RaftBridgeString(fileName).lastPathComponent;
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
    if (trimmed.length > RaftThreadImageCompressionMaxFileNameLength) {
        trimmed = [trimmed substringToIndex:RaftThreadImageCompressionMaxFileNameLength];
    }
    return [trimmed stringByAppendingPathExtension:@"jpg"];
}

static BOOL RaftThreadImageCompressionPathInside(NSString *rootPath, NSString *candidatePath) {
    NSString *prefix = [rootPath hasSuffix:@"/"] ? rootPath : [rootPath stringByAppendingString:@"/"];
    return [candidatePath hasPrefix:prefix];
}

static NSURL *RaftThreadImageCompressionSourceURL(NSString *fileRef, NSArray<NSURL *> *roots) {
    NSString *raw = RaftBridgeString(fileRef);
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
        if (rootPath.length > 0 && RaftThreadImageCompressionPathInside(rootPath, resolved.path)) {
            return resolved;
        }
    }
    return nil;
}

static BOOL RaftThreadImageCompressionInputIsImage(NSString *fileName,
                                                     NSString *mimeType,
                                                     NSURL *sourceURL) {
    NSString *mime = RaftThreadImageCompressionMimeType(mimeType);
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

typedef NS_ENUM(NSInteger, RaftThreadImageCompressionOperationState) {
    RaftThreadImageCompressionOperationStateActive = 0,
    RaftThreadImageCompressionOperationStatePending = 1,
    RaftThreadImageCompressionOperationStateDelivered = 2,
    RaftThreadImageCompressionOperationStateCancelled = 3,
};

/**
 * State and output ownership for one asynchronous editor-image compression request.
 *
 * The renderer can destroy a page while ImageIO is still decoding.  Keeping cancellation and
 * delivery state on a small lock-protected object lets the worker finish its autorelease pool
 * without ever invoking a stale callback or publishing a part file as a JPEG.
 */
@interface RaftThreadImageCompressionOperation : NSObject

@property(nonatomic, copy) NSString *requestId;
@property(nonatomic, copy) RaftBridgeCallback callback;
@property(nonatomic, strong) NSURL *outputURL;
@property(nonatomic, strong) NSURL *partURL;
@property(nonatomic, strong) NSRecursiveLock *lock;
/** Serializes write→rename with every output cleanup path. */
@property(nonatomic, strong) NSRecursiveLock *outputLock;
@property(nonatomic) RaftThreadImageCompressionOperationState state;

- (BOOL)markCancelled;
- (BOOL)markCancelledForce:(BOOL)force;
- (BOOL)markPending;
- (BOOL)markDelivered;
- (BOOL)isCancelled;
- (BOOL)isPending;
- (BOOL)isDelivered;
- (void)removeOutputs;

@end

@implementation RaftThreadImageCompressionOperation

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
        _state = RaftThreadImageCompressionOperationStateActive;
    }
    return self;
}

- (BOOL)markCancelled {
    return [self markCancelledForce:NO];
}

- (BOOL)markCancelledForce:(BOOL)force {
    BOOL changed = NO;
    [self.lock lock];
    if (self.state != RaftThreadImageCompressionOperationStateCancelled &&
        (force || self.state != RaftThreadImageCompressionOperationStateDelivered)) {
        self.state = RaftThreadImageCompressionOperationStateCancelled;
        changed = YES;
    }
    [self.lock unlock];
    return changed;
}

- (BOOL)markPending {
    BOOL changed = NO;
    [self.lock lock];
    if (self.state == RaftThreadImageCompressionOperationStateActive) {
        self.state = RaftThreadImageCompressionOperationStatePending;
        changed = YES;
    }
    [self.lock unlock];
    return changed;
}

- (BOOL)markDelivered {
    BOOL changed = NO;
    [self.lock lock];
    if (self.state == RaftThreadImageCompressionOperationStatePending) {
        self.state = RaftThreadImageCompressionOperationStateDelivered;
        changed = YES;
    }
    [self.lock unlock];
    return changed;
}

- (BOOL)isCancelled {
    [self.lock lock];
    BOOL cancelled = self.state == RaftThreadImageCompressionOperationStateCancelled;
    [self.lock unlock];
    return cancelled;
}

- (BOOL)isDelivered {
    [self.lock lock];
    BOOL delivered = self.state == RaftThreadImageCompressionOperationStateDelivered;
    [self.lock unlock];
    return delivered;
}

- (BOOL)isPending {
    [self.lock lock];
    BOOL pending = self.state == RaftThreadImageCompressionOperationStatePending;
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

static NSString *RaftCompressedAttachmentFileName(NSString *fileName) {
    NSString *leaf = RaftBridgeString(fileName).lastPathComponent;
    if (leaf.length == 0) {
        leaf = @"image";
    }
    NSString *stem = [leaf stringByDeletingPathExtension];
    if (stem.length == 0) {
        stem = @"image";
    }
    return [stem stringByAppendingPathExtension:@"jpg"];
}

static BOOL RaftWriteJPEGFromCGImage(CGImageRef image,
                                      NSURL *targetURL,
                                      NSError **error) {
    if (!image || CGImageGetWidth(image) == 0 || CGImageGetHeight(image) == 0) {
        if (error) *error = RaftThreadAttachmentImageCompressionError(@"Unable to decode image attachment");
        return NO;
    }
    CGImageDestinationRef destination = CGImageDestinationCreateWithURL(
        (__bridge CFURLRef)targetURL,
        (__bridge CFStringRef)UTTypeJPEG.identifier,
        1,
        NULL
    );
    if (!destination) {
        if (error) *error = RaftThreadAttachmentImageCompressionError(@"Unable to prepare image encoder");
        return NO;
    }
    NSDictionary *properties = @{
        (NSString *)kCGImageDestinationLossyCompressionQuality : @(RaftThreadAttachmentImageJPEGQuality)
    };
    CGImageDestinationAddImage(destination, image, (__bridge CFDictionaryRef)properties);
    BOOL finalized = CGImageDestinationFinalize(destination);
    CFRelease(destination);
    if (!finalized) {
        if (error) *error = RaftThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
        return NO;
    }
    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:targetURL.path error:nil];
    if (![attributes[NSFileSize] respondsToSelector:@selector(unsignedLongLongValue)] ||
        [attributes[NSFileSize] unsignedLongLongValue] == 0) {
        if (error) *error = RaftThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
        return NO;
    }
    return YES;
}

static BOOL RaftWriteCompressedPickedImage(NSURL *sourceURL,
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
        if (error) *error = RaftThreadAttachmentImageCompressionError(@"Unable to decode image attachment");
        return NO;
    }
    NSDictionary *thumbnailOptions = @{
        (NSString *)kCGImageSourceCreateThumbnailFromImageAlways : @YES,
        (NSString *)kCGImageSourceCreateThumbnailWithTransform : @YES,
        (NSString *)kCGImageSourceThumbnailMaxPixelSize : @(RaftThreadAttachmentImageMaxEdge),
        (NSString *)kCGImageSourceShouldCacheImmediately : @NO
    };
    CGImageRef thumbnail = CGImageSourceCreateThumbnailAtIndex(
        source,
        0,
        (__bridge CFDictionaryRef)thumbnailOptions
    );
    BOOL result = RaftWriteJPEGFromCGImage(thumbnail, partURL, error);
    if (thumbnail) CFRelease(thumbnail);
    CFRelease(source);
    if (!result) {
        [NSFileManager.defaultManager removeItemAtURL:partURL error:nil];
        return NO;
    }
    NSError *moveError = nil;
    if (![NSFileManager.defaultManager moveItemAtURL:partURL toURL:targetURL error:&moveError]) {
        [NSFileManager.defaultManager removeItemAtURL:partURL error:nil];
        if (error) *error = RaftThreadAttachmentImageCompressionError(
            moveError.localizedDescription ?: @"Unable to commit image attachment"
        );
        return NO;
    }
    return result;
}

static BOOL RaftWriteCompressedCameraImage(UIImage *image,
                                            NSURL *targetURL,
                                            NSError **error) {
    CGImageRef source = image.CGImage;
    if (!source || CGImageGetWidth(source) == 0 || CGImageGetHeight(source) == 0) {
        if (error) *error = RaftThreadAttachmentImageCompressionError(@"Unable to decode captured image");
        return NO;
    }
    CGFloat sourceWidth = (CGFloat)CGImageGetWidth(source);
    CGFloat sourceHeight = (CGFloat)CGImageGetHeight(source);
    CGFloat scale = MIN(1.0, (CGFloat)RaftThreadAttachmentImageMaxEdge /
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
    return RaftWriteJPEGFromCGImage(normalized.CGImage, targetURL, error);
}

static NSDictionary *RaftThreadShareImageFailure(NSString *message) {
    return @{
        @"status" : @"failed",
        @"message" : message.length > 0 ? message : @"Unable to export image"
    };
}

static CGFloat RaftThreadShareImageScale(UIView *view) {
    CGFloat scale = view.window.screen.scale;
    if (scale <= 0) {
        scale = UIScreen.mainScreen.scale;
    }
    return MAX(1.0, scale);
}

static NSString *RaftThreadShareImageDirectoryPath(void) {
    return [NSTemporaryDirectory() stringByAppendingPathComponent:RaftThreadShareImageDirectoryName];
}

static BOOL RaftThreadSharePrepareDirectory(BOOL pruneExpiredFiles) {
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSString *directory = RaftThreadShareImageDirectoryPath();
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
        NSDate *cutoff = [NSDate dateWithTimeIntervalSinceNow:-RaftThreadShareImageMaximumAgeSeconds];
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

static NSDictionary *RaftThreadShareWriteImage(UIImage *image, BOOL pruneExpiredFiles) {
    CGImageRef cgImage = image.CGImage;
    if (!cgImage || CGImageGetWidth(cgImage) == 0 || CGImageGetHeight(cgImage) == 0) {
        return RaftThreadShareImageFailure(@"Unable to capture image");
    }
    if (!RaftThreadSharePrepareDirectory(pruneExpiredFiles)) {
        return RaftThreadShareImageFailure(@"Storage unavailable");
    }
    NSString *fileName = [NSString stringWithFormat:@"raft-message-share-%lld-%@.png",
                                                       (long long)(NSDate.date.timeIntervalSince1970 * 1000.0),
                                                       NSUUID.UUID.UUIDString.lowercaseString];
    NSString *path = [RaftThreadShareImageDirectoryPath() stringByAppendingPathComponent:fileName];
    NSData *data = UIImagePNGRepresentation(image);
    if (data.length == 0 || ![data writeToFile:path options:NSDataWritingAtomic error:nil]) {
        return RaftThreadShareImageFailure(@"Unable to write image");
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

static UIImage *RaftThreadShareCaptureView(UIView *view, NSDictionary *params) {
    if (!view || CGRectIsEmpty(view.bounds)) {
        return nil;
    }
    CGFloat scale = RaftThreadShareImageScale(view);
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

static UIFont *RaftThreadShareFont(NSString *name, CGFloat size, UIFontWeight fallbackWeight) {
    return [UIFont fontWithName:name size:size] ?: [UIFont systemFontOfSize:size weight:fallbackWeight];
}

static UIImage *RaftThreadShareFrameImage(UIImage *content) {
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
                NSFontAttributeName : RaftThreadShareFont(@"SpaceGrotesk-Bold", titleSize, UIFontWeightBold),
                NSForegroundColorAttributeName : UIColor.blackColor
            };
            CGSize titleBounds = [@"RAFT" sizeWithAttributes:attributes];
            [@"RAFT" drawAtPoint:CGPointMake(horizontalInset, (headerHeight - titleBounds.height) / 2.0)
                   withAttributes:attributes];
        }

        CGFloat captionSize = MIN(26.0, MAX(18.0, width * 0.034));
        NSDictionary *captionAttributes = @{
            NSFontAttributeName : RaftThreadShareFont(@"SpaceGrotesk-SemiBold", captionSize, UIFontWeightSemibold),
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

static UIImage *RaftThreadShareStitchFragments(NSArray<NSString *> *fragmentPaths) {
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
static uint32_t RaftFeedbackDiagnosticsCRC32(NSData *data) {
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

static void RaftFeedbackDiagnosticsWriteU16(NSOutputStream *stream, uint16_t value) {
    uint8_t bytes[] = { (uint8_t)(value & 0xffU), (uint8_t)((value >> 8U) & 0xffU) };
    [stream write:bytes maxLength:sizeof(bytes)];
}

static void RaftFeedbackDiagnosticsWriteU32(NSOutputStream *stream, uint32_t value) {
    uint8_t bytes[] = {
        (uint8_t)(value & 0xffU), (uint8_t)((value >> 8U) & 0xffU),
        (uint8_t)((value >> 16U) & 0xffU), (uint8_t)((value >> 24U) & 0xffU)
    };
    [stream write:bytes maxLength:sizeof(bytes)];
}

static BOOL RaftFeedbackDiagnosticsWriteBytes(NSOutputStream *stream, NSData *data) {
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

static NSString *RaftFeedbackDiagnosticsSafeEntryName(NSString *name, NSUInteger index) {
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

static NSDictionary *RaftFeedbackDiagnosticsWriteArchive(BOOL (^isCancelled)(void), NSError **error) {
    NSDictionary *about = RaftDiagnosticsCurrentEnvironmentAbout();
    NSDictionary *prepared = RaftDiagnosticsPrepareAttachmentFiles(about, @"settings_feedback_web", NO);
    NSArray<NSString *> *paths = [prepared[@"attachmentPaths"] isKindOfClass:NSArray.class]
        ? prepared[@"attachmentPaths"] : @[];
    if (![RaftBridgeString(prepared[@"status"]) isEqualToString:@"ok"] || paths.count == 0) {
        if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:1 userInfo:@{
            NSLocalizedDescriptionKey : @"Unable to create diagnostics snapshot"
        }];
        return nil;
    }
    NSString *name = [[RaftFeedbackDiagnosticsArchivePrefix stringByAppendingString:NSUUID.UUID.UUIDString.lowercaseString]
        stringByAppendingPathExtension:RaftFeedbackDiagnosticsArchiveExtension];
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
        NSString *sourcePath = RaftBridgeString(paths[index]);
        unsigned long long sourceSize = [[[NSFileManager defaultManager] attributesOfItemAtPath:sourcePath error:nil][NSFileSize] unsignedLongLongValue];
        // Reject oversized inputs before mapping/allocating their bytes. The fixed ZIP headers
        // and central directory are covered by the conservative 1 KiB allowance and the final
        // archive-size check below remains authoritative.
        if (sourceSize == 0 || sourceSize + offset + 1024ULL > RaftFeedbackDiagnosticsMaxBytes) {
            if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:3 userInfo:@{
                NSLocalizedDescriptionKey : @"Diagnostics archive exceeds upload limit",
                @"tooLarge" : @YES
            }];
            success = NO;
            break;
        }
        NSData *data = [NSData dataWithContentsOfFile:sourcePath options:NSDataReadingMappedIfSafe error:nil];
        if (!data) { success = NO; break; }
        NSString *entryName = index == 0 ? @"diagnostics-summary.txt" : RaftFeedbackDiagnosticsSafeEntryName(sourcePath, index);
        NSData *entryNameData = [entryName dataUsingEncoding:NSUTF8StringEncoding];
        if (entryNameData.length > UINT16_MAX || data.length > UINT32_MAX ||
            data.length + offset + 1024ULL > RaftFeedbackDiagnosticsMaxBytes) {
            if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:3 userInfo:@{
                NSLocalizedDescriptionKey : @"Diagnostics archive exceeds upload limit",
                @"tooLarge" : @YES
            }];
            success = NO;
            break;
        }
        uint32_t crc = RaftFeedbackDiagnosticsCRC32(data);
        RaftFeedbackDiagnosticsWriteU32(stream, 0x04034b50U);
        RaftFeedbackDiagnosticsWriteU16(stream, 20); // version needed
        RaftFeedbackDiagnosticsWriteU16(stream, 0);  // flags
        RaftFeedbackDiagnosticsWriteU16(stream, 0);  // store method
        RaftFeedbackDiagnosticsWriteU16(stream, 0);  // mtime
        RaftFeedbackDiagnosticsWriteU16(stream, 0);  // mdate
        RaftFeedbackDiagnosticsWriteU32(stream, crc);
        RaftFeedbackDiagnosticsWriteU32(stream, (uint32_t)data.length);
        RaftFeedbackDiagnosticsWriteU32(stream, (uint32_t)data.length);
        RaftFeedbackDiagnosticsWriteU16(stream, (uint16_t)entryNameData.length);
        RaftFeedbackDiagnosticsWriteU16(stream, 0);
        if (!RaftFeedbackDiagnosticsWriteBytes(stream, entryNameData) ||
            !RaftFeedbackDiagnosticsWriteBytes(stream, data)) { success = NO; break; }
        [entries addObject:@{ @"name" : entryNameData, @"crc" : @(crc), @"size" : @((uint32_t)data.length), @"offset" : @(offset) }];
        offset += 30ULL + entryNameData.length + data.length;
    }
    if (success) {
        unsigned long long centralOffset = offset;
        for (NSDictionary *entry in entries) {
            NSData *entryNameData = entry[@"name"];
            RaftFeedbackDiagnosticsWriteU32(stream, 0x02014b50U);
            RaftFeedbackDiagnosticsWriteU16(stream, 20);
            RaftFeedbackDiagnosticsWriteU16(stream, 20);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU32(stream, [entry[@"crc"] unsignedIntValue]);
            RaftFeedbackDiagnosticsWriteU32(stream, [entry[@"size"] unsignedIntValue]);
            RaftFeedbackDiagnosticsWriteU32(stream, [entry[@"size"] unsignedIntValue]);
            RaftFeedbackDiagnosticsWriteU16(stream, (uint16_t)entryNameData.length);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU16(stream, 0);
            RaftFeedbackDiagnosticsWriteU32(stream, 0);
            RaftFeedbackDiagnosticsWriteU32(stream, [entry[@"offset"] unsignedIntValue]);
            RaftFeedbackDiagnosticsWriteBytes(stream, entryNameData);
            offset += 46ULL + entryNameData.length;
        }
        unsigned long long centralSize = offset - centralOffset;
        RaftFeedbackDiagnosticsWriteU32(stream, 0x06054b50U);
        RaftFeedbackDiagnosticsWriteU16(stream, 0);
        RaftFeedbackDiagnosticsWriteU16(stream, 0);
        RaftFeedbackDiagnosticsWriteU16(stream, (uint16_t)entries.count);
        RaftFeedbackDiagnosticsWriteU16(stream, (uint16_t)entries.count);
        RaftFeedbackDiagnosticsWriteU32(stream, (uint32_t)centralSize);
        RaftFeedbackDiagnosticsWriteU32(stream, (uint32_t)centralOffset);
        RaftFeedbackDiagnosticsWriteU16(stream, 0);
    }
    [stream close];
    if (!success || ![[NSFileManager defaultManager] fileExistsAtPath:path]) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        if (error && !*error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:4 userInfo:nil];
        return nil;
    }
    unsigned long long size = [[[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil][NSFileSize] unsignedLongLongValue];
    if (size == 0 || size > RaftFeedbackDiagnosticsMaxBytes) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
        if (error) *error = [NSError errorWithDomain:@"raft.feedback.diagnostics" code:3 userInfo:@{ @"tooLarge" : @YES }];
        return nil;
    }
    return @{ @"path" : path, @"fileName" : name, @"size" : @(size) };
}

@interface RaftFeedbackDiagnosticsUploadOperation : NSObject <NSURLSessionTaskDelegate, NSURLSessionDataDelegate>
@property(nonatomic, copy) NSString *requestId;
@property(nonatomic, copy) NSString *accountId;
@property(nonatomic) long long sessionGeneration;
@property(nonatomic) long long webViewGeneration;
@property(nonatomic, copy) RaftBridgeCallback callback;
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
@property(nonatomic, copy) void (^onTerminal)(RaftFeedbackDiagnosticsUploadOperation *operation);
- (instancetype)initWithRequestId:(NSString *)requestId binding:(NSDictionary *)binding callback:(RaftBridgeCallback)callback;
- (void)start;
- (void)cancel;
@end


@implementation RaftFeedbackDiagnosticsUploadOperation {
    NSMutableData *_presignData;
    NSInteger _presignStatus;
    NSInteger _uploadStatus;
}

- (instancetype)initWithRequestId:(NSString *)requestId binding:(NSDictionary *)binding callback:(RaftBridgeCallback)callback {
    if (self = [super init]) {
        _requestId = [requestId copy];
        _accountId = [RaftBridgeString(binding[@"accountId"]) copy];
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
    RaftBridgeCallback callback = self.callback;
    void (^onTerminal)(RaftFeedbackDiagnosticsUploadOperation *) = terminal ? self.onTerminal : nil;
    [self.lock unlock];
    if (onTerminal) onTerminal(self);
    NSMutableDictionary *event = [payload mutableCopy];
    event[@"status"] = @"ok";
    event[@"requestId"] = self.requestId ?: @"";
    event[@"binding"] = [self binding];
    event[@"terminal"] = @(terminal);
    __weak typeof(self) weakSelf = self;
    // A generation failure can finish before NSURLSession has retained its delegate. Keep the
    // operation alive until the queued terminal callback runs, while progress remains weak.
    RaftFeedbackDiagnosticsUploadOperation *terminalOwner = terminal ? self : nil;
    RaftBridgeRunOnMain(^{
        RaftFeedbackDiagnosticsUploadOperation *owner = terminalOwner ?: weakSelf;
        if (!owner) return;
        [owner.lock lock];
        BOOL invalidated = owner.cancelled;
        [owner.lock unlock];
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
        NSDictionary *archive = RaftFeedbackDiagnosticsWriteArchive(^{
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
        NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"%@/public/v2/apps/%@/feedback/presign", RaftHandsFeedbackBaseURL, RaftHandsFeedbackAppSlug]];
        NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
        request.HTTPMethod = @"POST";
        [request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
        [request setValue:@"application/json" forHTTPHeaderField:@"Accept"];
        [request setValue:RaftHandsFeedbackClientKey forHTTPHeaderField:@"X-Hands-Client-Key"];
        NSDictionary *body = @{ @"files" : @[ @{ @"filename" : self.fileName ?: @"diagnostics.zip",
                                                 @"content_type" : RaftFeedbackDiagnosticsContentType,
                                                 @"size" : @(self.fileSize) } ] };
        request.HTTPBody = [NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
        NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        configuration.timeoutIntervalForRequest = 30.0;
        configuration.timeoutIntervalForResource = 600.0;
        // Install and start the session while holding the same lock used by cancel(). This
        // closes the archive-generation → NSURLSession creation gap: cancellation cannot see a
        // nil session, delete the archive, and then have this block resurrect a presign request.
        [self.lock lock];
        if (self.cancelled) {
            [self.lock unlock];
            [[NSFileManager defaultManager] removeItemAtPath:archive[@"path"] error:nil];
            [self.session invalidateAndCancel];
            return;
        }
        self.session = [NSURLSession sessionWithConfiguration:configuration delegate:self delegateQueue:nil];
        self.presignTask = [self.session dataTaskWithRequest:request];
        [self.presignTask resume];
        [self.lock unlock];
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
        NSString *attachmentId = RaftBridgeString(upload[@"attachment_id"]);
        NSString *uploadURL = RaftBridgeString(upload[@"upload_url"]);
        self.r2Key = RaftBridgeString(upload[@"r2_key"]);
        long long expiresAt = [upload[@"expires_at"] longLongValue];
        if (attachmentId.length == 0 || uploadURL.length == 0 ||
            self.r2Key.length == 0 || self.r2Key.length > RaftFeedbackDiagnosticsMaxR2KeyLength ||
            [self.r2Key rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound ||
            expiresAt <= 0) {
            [self emitFailure:@"upload_failed"];
            return;
        }
        NSURL *uploadURLValue = [NSURL URLWithString:uploadURL];
        if (!uploadURLValue || ![uploadURLValue.scheme.lowercaseString isEqualToString:@"https"] ||
            uploadURLValue.host.length == 0 ||
            [uploadURL rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location != NSNotFound ||
            [uploadURL rangeOfCharacterFromSet:NSCharacterSet.whitespaceAndNewlineCharacterSet].location != NSNotFound) {
            [self emitFailure:@"upload_failed"];
            return;
        }
        NSMutableURLRequest *put = [NSMutableURLRequest requestWithURL:uploadURLValue];
        put.HTTPMethod = @"PUT";
        [put setValue:RaftFeedbackDiagnosticsContentType forHTTPHeaderField:@"Content-Type"];
        [self.lock lock];
        if (self.cancelled || self.session != session) {
            [self.lock unlock];
            return;
        }
        self.uploadTask = [self.session uploadTaskWithRequest:put fromFile:[NSURL fileURLWithPath:self.archivePath]];
        [self.uploadTask resume];
        [self.lock unlock];
        return;
    }
    if (task == self.uploadTask) {
        NSString *path = self.archivePath;
        if (error || _uploadStatus < 200 || _uploadStatus >= 300) {
            [self emitFailure:@"upload_failed"];
        } else {
            [self emit:@{ @"type" : @"done", @"ref" : @{ @"r2_key" : self.r2Key ?: @"",
                @"filename" : self.fileName ?: @"diagnostics.zip", @"content_type" : RaftFeedbackDiagnosticsContentType,
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

@property(nonatomic, copy, nullable) RaftBridgeCallback raft_attachmentExportCallback;
@property(nonatomic, copy, nullable) NSString *raft_attachmentExportRequestId;
@property(nonatomic, copy, nullable) NSString *raft_attachmentExportDownloadId;
@property(nonatomic, copy, nullable) NSString *raft_attachmentExportOperationId;
@property(nonatomic, strong, nullable) NSURL *raft_attachmentExportURL;
@property(nonatomic, copy, nullable) NSString *raft_attachmentExportFileName;
@property(nonatomic, strong, nullable) NSNumber *raft_attachmentExportSizeBytes;
@property(nonatomic) BOOL raft_attachmentExportTrackProgress;
@property(nonatomic, copy, nullable) NSString *raft_attachmentExportDestination;
@property(nonatomic, copy, nullable) NSString *raft_attachmentExportMimeType;
@property(nonatomic, copy, nullable) NSString *raft_attachmentExportPermissionStatus;
@property(nonatomic, weak, nullable) UIViewController *raft_sharePresenterController;
@property(nonatomic, copy, nullable) RaftBridgeCallback raft_attachmentPickerCallback;
@property(nonatomic, copy, nullable) NSString *raft_attachmentPickerChannelId;
@property(nonatomic) BOOL raft_attachmentPickerCompressImages;
@property(nonatomic, strong) NSMutableDictionary<NSString *, RaftThreadImageCompressionOperation *> *raft_threadImageCompressionOperations;
@property(nonatomic, strong) NSRecursiveLock *raft_threadImageCompressionOperationsLock;
@property(nonatomic) BOOL raft_threadImageCompressionDestroyed;
@property(nonatomic, strong) NSMutableDictionary<NSString *, RaftFeedbackDiagnosticsUploadOperation *> *raft_feedbackDiagnosticsOperations;
@property(nonatomic, strong) NSRecursiveLock *raft_feedbackDiagnosticsOperationsLock;
@property(nonatomic) BOOL raft_feedbackDiagnosticsDestroyed;

- (void)raft_finishAttachmentExport:(NSDictionary *)result;
- (void)raft_startAttachmentExport:(NSDictionary *)args destination:(NSString *)destination;
- (void)raft_beginAttachmentExportDownloadWithParams:(NSDictionary *)params;
- (void)raft_saveAttachmentExportToPhotos;
- (void)raft_finishAttachmentPicker:(NSDictionary *)result;
- (void)raft_presentPhotoPicker;
- (void)raft_requestAndPresentCamera;
- (void)raft_presentCamera;
- (void)raft_presentDocumentPicker;
- (void)raft_processPhotoPickerResults:(NSArray<PHPickerResult *> *)results;
- (void)raft_processDocumentPickerURLs:(NSArray<NSURL *> *)urls;
- (void)raft_finishAttachmentPickerFiles:(NSArray<NSDictionary *> *)files
                                    errors:(NSArray<NSDictionary *> *)errors;
- (nullable NSDictionary *)raft_cachePickedAttachmentURL:(NSURL *)sourceURL
                                            suggestedName:(nullable NSString *)suggestedName
                                                 mimeType:(nullable NSString *)mimeType
                                        compressImages:(BOOL)compressImages
                                                    error:(NSError * _Nullable * _Nullable)error;
- (void)compressThreadImage:(NSDictionary *)args;
- (void)cancelCompressThreadImage:(NSDictionary *)args;
- (BOOL)raft_finishThreadImageCompressionOperation:(RaftThreadImageCompressionOperation *)operation
                                             payload:(NSDictionary *)payload;
- (void)raft_removeThreadImageCompressionOperation:(RaftThreadImageCompressionOperation *)operation;
- (BOOL)raft_isThreadImageCompressionDestroyed;
- (BOOL)raft_isCurrentThreadImageCompressionOperation:(RaftThreadImageCompressionOperation *)operation;
- (void)raft_dispatchThreadImageCompressionFailure:(RaftBridgeCallback)callback;
- (void)raft_startFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(RaftBridgeCallback)callback;
- (void)raft_cancelFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(RaftBridgeCallback)callback;
- (void)raft_cancelAllFeedbackDiagnosticsUploads;
- (void)raft_destroyFeedbackDiagnosticsUploads;

@end

// Settings → Storage (row contract: SettingsHomePage.kt localizedSettingsStorageRowDetail /
// localizedSettingsStorageFeedback; Android reference: StorageManagementRepository.kt).
typedef struct {
    unsigned long long database;
    unsigned long long imageCache;
    unsigned long long attachmentDownloads;
    unsigned long long temporaryAndLogs;
} RaftStorageUsage;

static RaftStorageUsage RaftStorageMeasure(void);
static NSDictionary *RaftStoragePayload(RaftStorageUsage usage, NSString *feedback);
static void RaftStorageClearTarget(NSString *target, void (^completion)(NSDictionary *result));

// 扩展桥接接口
/*
 * @brief Native暴露接口到kotlin侧，提供kotlin侧调用native能力
 */

NSString *const RaftBridgeParamKey = @"param";
NSString *const RaftBridgeCallbackKey = @"callback";

@implementation HRBridgeModule

+ (BOOL)isSynchronousMethod:(NSString *)method {
    static NSSet<NSString *> *methods;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        methods = [NSSet setWithArray:@[ @"urlEncode", @"urlDecode", @"currentTimestamp", @"dateFormatter" ]];
    });
    return method != nil && [methods containsObject:method];
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _raft_threadImageCompressionOperations = [NSMutableDictionary dictionary];
        _raft_threadImageCompressionOperationsLock = [[NSRecursiveLock alloc] init];
        _raft_threadImageCompressionDestroyed = NO;
        _raft_feedbackDiagnosticsOperations = [NSMutableDictionary dictionary];
        _raft_feedbackDiagnosticsOperationsLock = [[NSRecursiveLock alloc] init];
        _raft_feedbackDiagnosticsDestroyed = NO;
    }
    return self;
}

- (void)copyToPasteboard:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *content = RaftBridgeString(params[@"content"]);
    if (content.length == 0) {
        return;
    }
    RaftBridgeRunOnMain(^{
        UIPasteboard *pasteboard = [UIPasteboard generalPasteboard];
        pasteboard.string = content;
    });
}

- (void)log:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *content = RaftBridgeRawString(params[@"content"]);
    if (content.length == 0) {
        return;
    }
    NSString *level = RaftBridgeRawString(params[@"level"]);
    if (level.length == 0) {
        level = @"info";
    }
    NSString *tag = RaftBridgeRawString(params[@"tag"]);
    if (tag.length == 0) {
        tag = RaftBridgeDefaultLogTag;
    }
    RaftDiagnosticsRecordWithContext(level, tag, content, 0, YES, NO);
}

- (void)openPage:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *pageName = RaftBridgeString(params[@"pageName"]);
    if (pageName.length == 0) {
        pageName = RaftBridgeString(params[@"url"]);
    }
    if (pageName.length == 0) {
        NSLog(@"[%@][warn] openPage ignored missing pageName", RaftBridgeDefaultLogTag);
        return;
    }
    NSDictionary *pageData =
        RaftBridgeDictionaryFromValue(params[@"pageData"]).count > 0
            ? RaftBridgeDictionaryFromValue(params[@"pageData"])
            : RaftBridgeDictionaryFromValue(params[@"userData"]);
    NSMutableDictionary *nextPageData = [RaftBridgeIdentityPageData(pageData) mutableCopy];
    if (nextPageData[@"pageName"] == nil) {
        nextPageData[@"pageName"] = pageName;
    }
    BOOL replaceCurrent =
        RaftBridgeBool(params[RaftBridgeReplaceCurrentPageKey]) ||
        RaftBridgeBool(params[@"closeCurPage"]);
    if (replaceCurrent) {
        nextPageData[RaftBridgeReplaceCurrentPageKey] = @YES;
    }
    [self raft_openPageWithName:pageName pageData:nextPageData replaceCurrent:replaceCurrent];
}

- (void)closePage:(NSDictionary *)args {
    [self raft_cancelAllFeedbackDiagnosticsUploads];
    UIViewController *controller = [self raft_currentViewController];
    RaftBridgeRunOnMain(^{
        UINavigationController *navigationController = controller.navigationController;
        if (navigationController && navigationController.viewControllers.count > 1) {
            [navigationController popViewControllerAnimated:YES];
            return;
        }
        if (controller.presentingViewController) {
            [controller dismissViewControllerAnimated:YES completion:nil];
            return;
        }
        NSLog(@"[%@][warn] closePage no-op: missing navigation stack", RaftBridgeDefaultLogTag);
    });
}

- (void)adaptiveNavigation:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    RaftBridgeRunOnMain(^{
        // Only this module's own page may run the transaction; never the key window's top page.
        UIViewController *controller = self.raftHostController;
        NSDictionary *result;
        if ([controller isKindOfClass:RaftPageViewController.class]) {
            result = [(RaftPageViewController *)controller raftAdaptiveNavigation:params];
        } else {
            result = @{ @"requestId": RaftBridgeString(params[@"requestId"]),
                        @"status": @"rejected", @"reason": @"unsupported_source" };
        }
        // Even a failed stack readback may follow removal. Commit never calls a source callback.
        // Shared receives the target event, or retains state on timeout if no target survived.
        if (callback && ![RaftBridgeString(params[@"action"]) isEqual:@"commit"]) callback(result);
    });
}

- (void)conversationForwardGesture:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    BOOL enabled = RaftBridgeBool(params[@"enabled"]);
    RaftBridgeRunOnMain(^{
        // This module belongs to the AppShell page. Do not follow a presented modal to decide
        // ownership; modal presence is a separate gesture-admission veto.
        UIViewController *controller = self.raftHostController;
        if ([controller isKindOfClass:RaftPageViewController.class]) {
            RaftPageViewController *page = (RaftPageViewController *)controller;
            if (page.raftIsAppShellPage) {
                page.raftConversationForwardGestureEnabled = enabled;
            }
        }
    });
}

- (void)openUrl:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *urlText = RaftBridgeString(params[@"url"]);
    if (urlText.length == 0) {
        NSLog(@"[%@][warn] openUrl ignored missing url", RaftBridgeDefaultLogTag);
        return;
    }
    NSURL *url = [NSURL URLWithString:urlText];
    if (!url || url.scheme.length == 0) {
        NSLog(@"[%@][warn] openUrl ignored invalid url", RaftBridgeDefaultLogTag);
        return;
    }
    // HostLinkCapability.openUrlInBrowser (`browser: true`): an http(s) page in Safari inside the
    // app, so a Raft universal link can't route back into Raft. Everything else: openURL.
    NSString *scheme = url.scheme.lowercaseString;
    BOOL webUrl = [scheme isEqualToString:@"https"] || [scheme isEqualToString:@"http"];
    if ([params[@"browser"] boolValue] && webUrl) {
        RaftBridgeRunOnMain(^{
            UIViewController *controller = [self raft_currentViewController];
            if (controller) {
                [controller presentViewController:[[SFSafariViewController alloc] initWithURL:url] animated:YES completion:nil];
                return;
            }
            [[UIApplication sharedApplication] openURL:url options:@{} completionHandler:nil];
        });
        return;
    }
    RaftBridgeRunOnMain(^{
        [[UIApplication sharedApplication] openURL:url
                                           options:@{}
                                 completionHandler:^(BOOL success) {
            if (!success) {
                NSLog(@"[%@][warn] openUrl failed", RaftBridgeDefaultLogTag);
            }
        }];
    });
}

- (void)toast:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *content = RaftBridgeString(params[@"content"]);
    if (content.length == 0) {
        return;
    }
    RaftBridgeRunOnMain(^{
        [self raft_showToast:content];
    });
}

- (void)exportThreadShareImage:(NSDictionary *)args {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    NSDictionary *params = RaftBridgeParams(args);
    RaftBridgeRunOnMain(^{
        NSString *fragmentValue = RaftBridgeRawString(params[@"fragments"]);
        NSMutableArray<NSString *> *fragmentPaths = [NSMutableArray array];
        for (NSString *line in [fragmentValue componentsSeparatedByCharactersInSet:NSCharacterSet.newlineCharacterSet]) {
            NSString *path = RaftBridgeString(line);
            if (path.length > 0) {
                [fragmentPaths addObject:path];
            }
        }
        UIImage *image = nil;
        BOOL frame = params[@"frame"] == nil || RaftBridgeBool(params[@"frame"]);
        if (fragmentPaths.count > 0) {
            image = RaftThreadShareStitchFragments(fragmentPaths);
            frame = YES;
        } else {
            image = RaftThreadShareCaptureView(self.raftHostController.viewIfLoaded, params);
        }
        if (!image) {
            callback(RaftThreadShareImageFailure(@"Unable to capture image"));
            return;
        }
        UIImage *output = frame ? RaftThreadShareFrameImage(image) : image;
        if (!output) {
            callback(RaftThreadShareImageFailure(@"Unable to frame image"));
            return;
        }
        callback(RaftThreadShareWriteImage(output, frame));
    });
}

- (void)probeThreadViewportPixels:(NSDictionary *)args {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (callback) {
        callback(@{
            @"status": @"unsupported",
            @"generation": args[@"generation"] ?: @0
        });
    }
}

- (void)saveThreadShareImage:(NSDictionary *)args {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    NSDictionary *params = RaftBridgeParams(args);
    NSString *path = RaftBridgeString(params[@"path"]);
    BOOL isDirectory = NO;
    if (path.length == 0 ||
        ![NSFileManager.defaultManager fileExistsAtPath:path isDirectory:&isDirectory] ||
        isDirectory ||
        [[NSFileManager.defaultManager attributesOfItemAtPath:path error:nil][NSFileSize] unsignedLongLongValue] == 0) {
        callback(RaftThreadShareImageFailure(@"Image file unavailable"));
        return;
    }
    NSURL *fileURL = [NSURL fileURLWithPath:path];
    void (^saveImage)(void) = ^{
        [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
            [PHAssetChangeRequest creationRequestForAssetFromImageAtFileURL:fileURL];
        } completionHandler:^(BOOL success, NSError *error) {
            RaftBridgeRunOnMain(^{
                if (success) {
                    callback(@{ @"status" : @"ok", @"uri" : fileURL.absoluteString ?: @"" });
                } else {
                    callback(RaftThreadShareImageFailure(error.localizedDescription ?: @"Unable to save image"));
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
        callback(RaftThreadShareImageFailure(@"Photos access is unavailable"));
        return;
    }
    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus authorizationStatus) {
        RaftBridgeRunOnMain(^{
            if (authorizationStatus == PHAuthorizationStatusAuthorized ||
                authorizationStatus == PHAuthorizationStatusLimited) {
                saveImage();
            } else {
                callback(RaftThreadShareImageFailure(@"Photos access is unavailable"));
            }
        });
    }];
}

- (void)localServeTime:(NSDictionary *)args {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (callback) {
        callback(@{ @"time" : @([[NSDate date] timeIntervalSince1970]) });
    }
}

- (id)currentTimestamp:(NSDictionary *)args {
    long long timestamp = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
    return [NSString stringWithFormat:@"%lld", timestamp];
}

// Synchronous (see +isSynchronousMethod:). Form encoding, matching the Android host
// (java.net.URLEncoder / URLDecoder, UTF-8): unreserved [A-Za-z0-9.*_-], space as '+'.
- (id)urlEncode:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *value = RaftBridgeRawString(params[@"string"]);
    static NSCharacterSet *allowed;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        allowed = [NSCharacterSet characterSetWithCharactersInString:
            @"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.*_- "];
    });
    NSString *encoded = [value stringByAddingPercentEncodingWithAllowedCharacters:allowed] ?: @"";
    return [encoded stringByReplacingOccurrencesOfString:@" " withString:@"+"];
}

- (id)urlDecode:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *value = [RaftBridgeRawString(params[@"string"]) stringByReplacingOccurrencesOfString:@"+" withString:@" "];
    return [value stringByRemovingPercentEncoding] ?: value;
}

- (id)dateFormatter:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *format = RaftBridgeString(params[@"format"]);
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
    NSDictionary *params = RaftBridgeParams(args);
    NSString *sceneName = RaftBridgeString(params[@"sceneName"]);
    if (sceneName.length == 0) {
        return;
    }
    NSString *action = RaftBridgeString(params[@"action"]);
    if (action.length == 0) {
        action = @"enter";
    }
    NSLog(@"[SlockPerfScene] action=%@ scene=%@", action, sceneName);
}

- (void)performanceMarker:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *routeName = RaftBridgeString(params[@"routeName"]);
    NSString *phase = RaftBridgeString(params[@"phase"]);
    if (routeName.length == 0 && phase.length == 0) {
        return;
    }
    NSString *sceneId = RaftBridgeString(params[@"sceneId"]);
    NSString *event = RaftBridgeString(params[@"event"]);
    NSString *threadHash = RaftBridgeString(params[@"threadHash"]);
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
    NSDictionary *params = RaftBridgeParams(args);
    NSString *summary = RaftBridgeString(params[@"summary"]);
    if (summary.length > 0) {
        NSLog(@"[SlockKuiklyPerfSnapshot] %@", RaftBridgeSanitizedLogString(summary));
    }
}

- (void)appBadge:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSInteger count = MAX(0, [params[@"count"] respondsToSelector:@selector(integerValue)] ? [params[@"count"] integerValue] : 0);
    RaftBridgeRunOnMain(^{
        if (@available(iOS 16.0, *)) {
            [[UNUserNotificationCenter currentNotificationCenter] setBadgeCount:count withCompletionHandler:nil];
        } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            [UIApplication sharedApplication].applicationIconBadgeNumber = count;
#pragma clang diagnostic pop
        }
        NSLog(@"[%@][info] appBadge count=%ld source=%@ stale=%@",
              RaftBridgeDefaultLogTag,
              (long)count,
              RaftBridgeString(params[@"source"]),
              RaftBridgeBool(params[@"isStale"]) ? @"true" : @"false");
    });
}

- (void)syncDesktopShortcuts:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *rawShortcuts = RaftBridgeString(params[@"shortcuts"]);
    RaftBridgeRunOnMain(^{
        if (rawShortcuts.length == 0) {
            [UIApplication sharedApplication].shortcutItems = @[];
            NSLog(@"[%@][info] syncDesktopShortcuts cleared", RaftBridgeDefaultLogTag);
            return;
        }
        NSData *data = [rawShortcuts dataUsingEncoding:NSUTF8StringEncoding];
        if (!data) {
            return;
        }
        NSError *error = nil;
        id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&error];
        if (error || ![json isKindOfClass:[NSArray class]]) {
            NSLog(@"[%@][warn] syncDesktopShortcuts JSON parse failed: %@", RaftBridgeDefaultLogTag, error);
            return;
        }
        NSArray *items = (NSArray *)json;
        NSMutableArray<UIApplicationShortcutItem *> *shortcutItems = [NSMutableArray arrayWithCapacity:items.count];
        for (id rawItem in items) {
            if (![rawItem isKindOfClass:[NSDictionary class]]) continue;
            NSDictionary *item = (NSDictionary *)rawItem;
            NSString *type = RaftBridgeString(item[@"id"]);
            NSString *title = RaftBridgeString(item[@"title"]);
            NSString *subtitle = RaftBridgeString(item[@"subtitle"]);
            NSString *serverId = RaftBridgeString(item[@"serverId"]);
            NSString *targetId = RaftBridgeString(item[@"targetId"]);
            NSString *targetKind = RaftBridgeString(item[@"targetKind"]);

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
        NSLog(@"[%@][info] syncDesktopShortcuts updated count=%lu", RaftBridgeDefaultLogTag, (unsigned long)shortcutItems.count);
    });
}

- (void)avatarCacheCapability:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) return;
    [[RaftIosAvatarCacheHost shared] handleLegacyCommand:params completion:^(NSDictionary<NSString *, id> *result) {
        callback(result);
    }];
}

- (void)settingsCapability:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    NSString *action = RaftBridgeString(params[@"action"]);
    if ([action isEqualToString:@"feedbackDiagnosticsStart"]) {
        [self raft_startFeedbackDiagnosticsUpload:params callback:callback];
        return;
    }
    if ([action isEqualToString:@"feedbackDiagnosticsCancel"]) {
        [self raft_cancelFeedbackDiagnosticsUpload:params callback:callback];
        return;
    }
    if ([action isEqualToString:@"getNotificationPermissionStatus"]) {
        [self raft_getNotificationPermissionStatusWithCallback:callback];
        return;
    }
    if ([action isEqualToString:@"requestNotificationPermission"]) {
        [self raft_requestNotificationPermissionWithCallback:callback];
        return;
    }
    if ([action isEqualToString:@"openNotificationSettings"]) {
        [self raft_openNotificationSettingsWithCallback:callback];
        return;
    }
    if ([action isEqualToString:@"appLoginPrepare"]) {
        callback([[RaftIosMobileOAuthHost shared] prepareAppLogin]);
        return;
    }
    if ([action isEqualToString:@"appLoginOpen"]) {
        NSString *authorizationUrl = RaftBridgeString(params[@"authorizationUrl"]);
        RaftBridgeRunOnMain(^{
            // The requesting page's window: the sign-in sheet opens in this page's scene, not in
            // whichever Raft window is key (Split View, several scenes, iPhone Duo).
            [[RaftIosMobileOAuthHost shared] openAppLoginWithUrl:authorizationUrl
                                                presentingWindow:self.raftHostController.viewIfLoaded.window
                                                      completion:^(NSDictionary *result) {
                callback(result);
            }];
        });
        return;
    }
    if ([action isEqualToString:@"mobileOAuthLink"]) {
        NSString *provider = RaftBridgeString(params[@"providerId"]);
        RaftBridgeRunOnMain(^{
            [[RaftIosMobileOAuthHost shared] startWithProvider:provider
                                                          mode:@"link"
                                              presentingWindow:self.raftHostController.viewIfLoaded.window
                                                    completion:^(NSDictionary *result) {
                callback(result);
            }];
        });
        return;
    }
    if ([action isEqualToString:@"promoteFeedbackDraftAttachments"] ||
        [action isEqualToString:@"discardFeedbackDraftAttachments"]) {
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            NSDictionary *result = [action isEqualToString:@"promoteFeedbackDraftAttachments"]
                ? [self raft_promoteFeedbackDraftAttachments:params]
                : [self raft_discardFeedbackDraftAttachments:params];
            RaftBridgeRunOnMain(^{ callback(result); });
        });
        return;
    }
    if ([action isEqualToString:@"loadStorage"] || [action isEqualToString:@"clearStorage"]) {
        // Storage rows walk app directories; never measure or delete on the main thread.
        NSString *target = RaftBridgeString(params[@"target"]);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            void (^deliver)(NSDictionary *) = ^(NSDictionary *result) {
                RaftBridgeRunOnMain(^{ callback(result); });
            };
            if ([action isEqualToString:@"loadStorage"]) {
                deliver(RaftStoragePayload(RaftStorageMeasure(), @""));
            } else {
                RaftStorageClearTarget(target, deliver);
            }
        });
        return;
    }
    callback([self raft_settingsCapabilityResultForAction:action params:params]);
}

static BOOL RaftFeedbackDiagnosticsValidRequest(NSString *requestId, NSDictionary *binding) {
    NSCharacterSet *allowed = [NSCharacterSet characterSetWithCharactersInString:@"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._:-"];
    if (requestId.length == 0 || requestId.length > 128 ||
        [requestId rangeOfCharacterFromSet:allowed.invertedSet].location != NSNotFound) return NO;
    NSString *account = RaftBridgeString(binding[@"accountId"]);
    long long sessionGeneration = [binding[@"sessionGeneration"] longLongValue];
    long long webViewGeneration = [binding[@"webViewGeneration"] longLongValue];
    NSCharacterSet *accountControlCharacters = NSCharacterSet.controlCharacterSet;
    return account.length > 0 && account.length <= 256 &&
        [account rangeOfCharacterFromSet:accountControlCharacters].location == NSNotFound &&
        sessionGeneration >= 0 && webViewGeneration >= 0;
}

static NSDictionary *RaftFeedbackDiagnosticsBindingPayload(NSDictionary *binding) {
    return @{ @"accountId" : RaftBridgeString(binding[@"accountId"]),
              @"sessionGeneration" : @([binding[@"sessionGeneration"] longLongValue]),
              @"webViewGeneration" : @([binding[@"webViewGeneration"] longLongValue]) };
}

- (void)raft_startFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(RaftBridgeCallback)callback {
    NSString *requestId = RaftBridgeString(params[@"requestId"]);
    NSDictionary *binding = [params isKindOfClass:NSDictionary.class] && [params[@"binding"] isKindOfClass:NSDictionary.class]
        ? params[@"binding"] : params;
    if (!callback || !RaftFeedbackDiagnosticsValidRequest(requestId, binding)) return;
    [self.raft_feedbackDiagnosticsOperationsLock lock];
    if (self.raft_feedbackDiagnosticsDestroyed || self.raft_feedbackDiagnosticsOperations[requestId] != nil) {
        [self.raft_feedbackDiagnosticsOperationsLock unlock];
        callback(@{ @"status" : @"ok", @"requestId" : requestId, @"binding" : RaftFeedbackDiagnosticsBindingPayload(binding),
                    @"type" : @"failed", @"reason" : @"generate_failed", @"terminal" : @YES });
        return;
    }
    // A page replacement can start a new request before the old WebView's cancellation message
    // arrives.  Retire every older native operation first; only one upload may own this bridge.
    NSArray<RaftFeedbackDiagnosticsUploadOperation *> *staleOperations =
        self.raft_feedbackDiagnosticsOperations.allValues.copy;
    [self.raft_feedbackDiagnosticsOperations removeAllObjects];
    RaftFeedbackDiagnosticsUploadOperation *operation =
        [[RaftFeedbackDiagnosticsUploadOperation alloc] initWithRequestId:requestId binding:binding callback:callback];
    __weak typeof(self) weakSelf = self;
    operation.onTerminal = ^(RaftFeedbackDiagnosticsUploadOperation *finished) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) return;
        [self.raft_feedbackDiagnosticsOperationsLock lock];
        if (self.raft_feedbackDiagnosticsOperations[finished.requestId] == finished) {
            [self.raft_feedbackDiagnosticsOperations removeObjectForKey:finished.requestId];
        }
        [self.raft_feedbackDiagnosticsOperationsLock unlock];
    };
    self.raft_feedbackDiagnosticsOperations[requestId] = operation;
    [self.raft_feedbackDiagnosticsOperationsLock unlock];
    for (RaftFeedbackDiagnosticsUploadOperation *stale in staleOperations) [stale cancel];
    [operation start];
}

- (void)raft_cancelFeedbackDiagnosticsUpload:(NSDictionary *)params callback:(RaftBridgeCallback)callback {
    NSString *requestId = RaftBridgeString(params[@"requestId"]);
    NSDictionary *binding = [params isKindOfClass:NSDictionary.class] && [params[@"binding"] isKindOfClass:NSDictionary.class]
        ? params[@"binding"] : params;
    [self.raft_feedbackDiagnosticsOperationsLock lock];
    RaftFeedbackDiagnosticsUploadOperation *operation = self.raft_feedbackDiagnosticsOperations[requestId];
    BOOL matches = operation != nil && [operation.accountId isEqualToString:RaftBridgeString(binding[@"accountId"])] &&
        operation.sessionGeneration == [binding[@"sessionGeneration"] longLongValue] &&
        operation.webViewGeneration == [binding[@"webViewGeneration"] longLongValue];
    if (matches) [self.raft_feedbackDiagnosticsOperations removeObjectForKey:requestId];
    [self.raft_feedbackDiagnosticsOperationsLock unlock];
    if (matches) [operation cancel];
    if (callback) callback(@{ @"status" : matches ? @"ok" : @"ignored", @"requestId" : requestId,
                              @"binding" : RaftFeedbackDiagnosticsBindingPayload(binding ?: @{}),
                              @"cancelled" : @(matches), @"terminal" : @YES });
}

- (void)raft_cancelAllFeedbackDiagnosticsUploads {
    [self.raft_feedbackDiagnosticsOperationsLock lock];
    NSArray *operations = self.raft_feedbackDiagnosticsOperations.allValues.copy;
    [self.raft_feedbackDiagnosticsOperations removeAllObjects];
    [self.raft_feedbackDiagnosticsOperationsLock unlock];
    for (RaftFeedbackDiagnosticsUploadOperation *operation in operations) [operation cancel];
}

- (void)raft_destroyFeedbackDiagnosticsUploads {
    [self.raft_feedbackDiagnosticsOperationsLock lock];
    self.raft_feedbackDiagnosticsDestroyed = YES;
    NSArray *operations = self.raft_feedbackDiagnosticsOperations.allValues.copy;
    [self.raft_feedbackDiagnosticsOperations removeAllObjects];
    [self.raft_feedbackDiagnosticsOperationsLock unlock];
    for (RaftFeedbackDiagnosticsUploadOperation *operation in operations) [operation cancel];
}

- (NSDictionary *)raft_promoteFeedbackDraftAttachments:(NSDictionary *)params {
    NSArray *files = [params[@"files"] isKindOfClass:NSArray.class] ? params[@"files"] : @[];
    NSString *groupId = RaftBridgeString(params[@"groupId"]);
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
    NSString *accountSegment = RaftFeedbackDraftAccountSegment(RaftBridgeString(params[@"accountId"]));
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
        RaftFeedbackDraftFilesAtDirectory(finalDirectory, files, maxBytes, fileManager);
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
        NSString *fileRef = RaftBridgeString(file[@"fileRef"]);
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
        NSString *targetName = RaftFeedbackDraftTargetName(index, file);
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
        RaftFeedbackDraftFilesAtDirectory(finalDirectory, files, maxBytes, fileManager);
    if (!promoted) {
        [fileManager removeItemAtURL:finalDirectory error:nil];
        return @{ @"status" : @"error", @"code" : @"settings_feedback_attachment_promotion_failed",
                  @"message" : @"Committed feedback attachment metadata is unavailable" };
    }
    return @{ @"status" : @"ok", @"files" : promoted };
}

- (NSDictionary *)raft_discardFeedbackDraftAttachments:(NSDictionary *)params {
    NSArray *fileRefs = [params[@"fileRefs"] isKindOfClass:NSArray.class] ? params[@"fileRefs"] : @[];
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSURL *applicationSupport = [fileManager URLsForDirectory:NSApplicationSupportDirectory
                                                    inDomains:NSUserDomainMask].firstObject;
    if (!applicationSupport) {
        return @{ @"status" : @"ok" };
    }
    NSString *accountSegment = RaftFeedbackDraftAccountSegment(RaftBridgeString(params[@"accountId"]));
    NSString *ownedRoot = [[[[[applicationSupport URLByAppendingPathComponent:@"Raft" isDirectory:YES]
        URLByAppendingPathComponent:@"SettingsHandsFeedbackDrafts" isDirectory:YES]
        URLByAppendingPathComponent:accountSegment isDirectory:YES] path] stringByStandardizingPath];
    NSString *ownedPrefix = [ownedRoot stringByAppendingString:@"/"];
    for (id value in fileRefs) {
        NSURL *url = [NSURL URLWithString:RaftBridgeString(value)];
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
    NSDictionary *params = RaftBridgeParams(args);
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    if (self.raft_attachmentPickerCallback || self.raft_attachmentExportCallback) {
        callback(@{
            @"code" : @409,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_picker_busy",
            @"errorBodyText" : @"Attachment picker already open"
        });
        return;
    }
    self.raft_attachmentPickerCallback = callback;
    self.raft_attachmentPickerChannelId = RaftBridgeString(params[@"channelId"]);
    BOOL imageOnly = RaftBridgeBool(params[@"imageOnly"]);
    BOOL capture = RaftBridgeBool(params[@"capture"]);
    self.raft_attachmentPickerCompressImages = RaftBridgeBool(params[@"compressImages"]);
    RaftDiagnosticsRecord(
        @"info",
        @"SlockKuiklyAttachment",
        [NSString stringWithFormat:@"picker_start imageOnly=%@ capture=%@ channelIdPresent=%@",
                                   imageOnly ? @"true" : @"false",
                                   capture ? @"true" : @"false",
                                   self.raft_attachmentPickerChannelId.length > 0 ? @"true" : @"false"]
    );
    if (capture) {
        [self raft_requestAndPresentCamera];
        return;
    }
    if (!imageOnly) {
        [self raft_presentDocumentPicker];
        return;
    }

    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelReadWrite];
    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) {
        [self raft_presentPhotoPicker];
        return;
    }
    if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
        [self raft_finishAttachmentPicker:@{
            @"code" : @403,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_permission_denied",
            @"errorBodyText" : @"Photo library access is denied"
        }];
        return;
    }
    __weak typeof(self) weakSelf = self;
    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelReadWrite handler:^(PHAuthorizationStatus authorizationStatus) {
        RaftBridgeRunOnMain(^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf || !strongSelf.raft_attachmentPickerCallback) {
                return;
            }
            if (authorizationStatus == PHAuthorizationStatusAuthorized || authorizationStatus == PHAuthorizationStatusLimited) {
                [strongSelf raft_presentPhotoPicker];
            } else {
                [strongSelf raft_finishAttachmentPicker:@{
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
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    NSDictionary *params = RaftBridgeParams(args);
    NSString *requestId = RaftBridgeString(params[@"requestId"]);
    NSString *fileName = RaftBridgeString(params[@"fileName"]);
    NSString *mimeType = RaftThreadImageCompressionMimeType(RaftBridgeString(params[@"mimeType"]));
    NSString *fileRef = RaftBridgeRawString(params[@"fileRef"]);
    BOOL validRequest = requestId.length > 0 &&
        requestId.length <= RaftThreadImageCompressionMaxRequestIdLength &&
        [requestId rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location == NSNotFound &&
        RaftThreadImageCompressionSafeName(fileName) &&
        mimeType.length > 0 && [mimeType hasPrefix:@"image/"] &&
        fileRef.length > 0 &&
        [fileRef rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location == NSNotFound;
    if (!validRequest) {
        [self raft_dispatchThreadImageCompressionFailure:callback];
        return;
    }

    RaftThreadImageCompressionOperation *operation = [[RaftThreadImageCompressionOperation alloc] init];
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
            URLByAppendingPathComponent:RaftPickedAttachmentParentDirectoryName isDirectory:YES]
            URLByAppendingPathComponent:RaftThreadImageCompressionDirectoryName isDirectory:YES]
        : nil;
    if (!cacheDirectory) {
        [operation markCancelled];
        [self raft_dispatchThreadImageCompressionFailure:callback];
        return;
    }
    NSString *outputName = [NSString stringWithFormat:@"composer-image-%@.jpg", NSUUID.UUID.UUIDString];
    operation.outputURL = [cacheDirectory URLByAppendingPathComponent:outputName isDirectory:NO];
    operation.partURL = [operation.outputURL URLByAppendingPathExtension:@"part"];

    [self.raft_threadImageCompressionOperationsLock lock];
    RaftThreadImageCompressionOperation *existing =
        self.raft_threadImageCompressionOperations[requestId];
    BOOL destroyed = self.raft_threadImageCompressionDestroyed;
    if (!existing && !destroyed) {
        self.raft_threadImageCompressionOperations[requestId] = operation;
    }
    [self.raft_threadImageCompressionOperationsLock unlock];
    if (existing || destroyed) {
        [operation markCancelled];
        [self raft_dispatchThreadImageCompressionFailure:callback];
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
                    ![weakSelf raft_isCurrentThreadImageCompressionOperation:operation]) {
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
                    error = RaftThreadAttachmentImageCompressionError(@"Attachment cache unavailable");
                    return;
                }
                NSURL *sourceURL = RaftThreadImageCompressionSourceURL(fileRef, roots);
                if (!sourceURL || !RaftThreadImageCompressionInputIsImage(fileName, mimeType, sourceURL) ||
                    [operation isCancelled] || !weakSelf ||
                    ![weakSelf raft_isCurrentThreadImageCompressionOperation:operation]) {
                    error = RaftThreadAttachmentImageCompressionError(@"Unable to decode image attachment");
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
                    encoded = RaftWriteCompressedPickedImage(sourceURL, operation.outputURL, &error);
                    if (encoded) {
                        attributes = [fileManager attributesOfItemAtPath:operation.outputURL.path error:&error];
                    }
                } @finally {
                    [operation.outputLock unlock];
                }
                if (!encoded ||
                    ![attributes[NSFileType] isEqualToString:NSFileTypeRegular] ||
                    [attributes[NSFileSize] unsignedLongLongValue] == 0 || [operation isCancelled] ||
                    !weakSelf || ![weakSelf raft_isCurrentThreadImageCompressionOperation:operation]) {
                    error = RaftThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
                    return;
                }
                NSString *outputRef = operation.outputURL.absoluteString ?: @"";
                NSDictionary *file = @{
                    @"fileRef" : outputRef,
                    @"fileName" : RaftThreadImageCompressionOutputName(fileName),
                    @"mimeType" : @"image/jpeg",
                    @"previewUri" : outputRef,
                    @"sizeBytes" : attributes[NSFileSize] ?: @0,
                    @"source" : RaftThreadImageCompressionSource
                };
                NSData *bodyData = [NSJSONSerialization dataWithJSONObject:@{ @"files" : @[ file ] }
                                                               options:0
                                                                 error:&error];
                if (!bodyData) {
                    error = RaftThreadAttachmentImageCompressionError(@"Unable to encode image attachment");
                    return;
                }
                NSString *bodyText = [[NSString alloc] initWithData:bodyData encoding:NSUTF8StringEncoding];
                if (bodyText.length == 0) {
                    return;
                }
                terminalPayload = @{ @"code" : @200, @"bodyText" : bodyText };
            } @catch (__unused NSException *exception) {
                error = RaftThreadAttachmentImageCompressionError(@"Unable to compress image attachment");
            } @finally {
                if (![operation isCancelled]) {
                    @try {
                        if (weakSelf) {
                            // Build the terminal result first, then admit exactly one response.
                            // An exception after the JPEG was encoded but before the success
                            // payload was complete must still fail closed instead of leaving the
                            // shared coroutine waiting forever.
                            responseAccepted = [weakSelf raft_finishThreadImageCompressionOperation:
                                operation
                                payload:terminalPayload ?: RaftThreadImageCompressionFailurePayload()];
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
                        [weakSelf raft_removeThreadImageCompressionOperation:operation];
                    }
                }
            }
        }
    });
}

- (void)cancelCompressThreadImage:(NSDictionary *)args {
    NSString *requestId = RaftBridgeString(RaftBridgeParams(args)[@"requestId"]);
    if (requestId.length == 0) {
        return;
    }
    [self.raft_threadImageCompressionOperationsLock lock];
    RaftThreadImageCompressionOperation *operation = self.raft_threadImageCompressionOperations[requestId];
    [self.raft_threadImageCompressionOperationsLock unlock];
    if (!operation || ![operation markCancelled]) {
        // In particular, an already-delivered response belongs to the consumer.  Do not remove
        // its map entry or generated JPEG on an ordinary cancel; the delivery fence retires the
        // entry after the callback returns.
        return;
    }
    [self.raft_threadImageCompressionOperationsLock lock];
    if (self.raft_threadImageCompressionOperations[requestId] == operation) {
        [self.raft_threadImageCompressionOperations removeObjectForKey:requestId];
    }
    [self.raft_threadImageCompressionOperationsLock unlock];
    [operation removeOutputs];
}

- (void)raft_removeThreadImageCompressionOperation:(RaftThreadImageCompressionOperation *)operation {
    if (!operation) return;
    [self.raft_threadImageCompressionOperationsLock lock];
    if (self.raft_threadImageCompressionOperations[operation.requestId] == operation) {
        [self.raft_threadImageCompressionOperations removeObjectForKey:operation.requestId];
    }
    [self.raft_threadImageCompressionOperationsLock unlock];
}

- (BOOL)raft_isThreadImageCompressionDestroyed {
    [self.raft_threadImageCompressionOperationsLock lock];
    BOOL destroyed = self.raft_threadImageCompressionDestroyed;
    [self.raft_threadImageCompressionOperationsLock unlock];
    return destroyed;
}

- (BOOL)raft_isCurrentThreadImageCompressionOperation:(RaftThreadImageCompressionOperation *)operation {
    if (!operation) return NO;
    [self.raft_threadImageCompressionOperationsLock lock];
    BOOL current = !self.raft_threadImageCompressionDestroyed &&
        self.raft_threadImageCompressionOperations[operation.requestId] == operation;
    [self.raft_threadImageCompressionOperationsLock unlock];
    return current;
}

- (void)raft_dispatchThreadImageCompressionFailure:(RaftBridgeCallback)callback {
    if (!callback) return;
    __weak typeof(self) weakSelf = self;
    RaftBridgeRunOnMain(^{
        HRBridgeModule *strongSelf = weakSelf;
        if (!strongSelf) {
            return;
        }
        [strongSelf.raft_threadImageCompressionOperationsLock lock];
        if (!strongSelf.raft_threadImageCompressionDestroyed) {
            @try {
                callback(RaftThreadImageCompressionFailurePayload());
            } @catch (__unused NSException *exception) {
                // Renderer callback removal during teardown is terminal and must not escape the
                // native dispatch turn.
            }
        }
        [strongSelf.raft_threadImageCompressionOperationsLock unlock];
    });
}

- (BOOL)raft_finishThreadImageCompressionOperation:(RaftThreadImageCompressionOperation *)operation
                                             payload:(NSDictionary *)payload {
    if (!operation) return NO;

    // Reserve ACTIVE -> PENDING while the map and operation locks are held together.  Keeping
    // this lock order (map first, operation second) matches cancel and dealloc, so teardown
    // cannot pass the identity check between reservation and the main-thread callback fence.
    [self.raft_threadImageCompressionOperationsLock lock];
    [operation.lock lock];
    BOOL admitted = !self.raft_threadImageCompressionDestroyed &&
        self.raft_threadImageCompressionOperations[operation.requestId] == operation &&
        operation.state == RaftThreadImageCompressionOperationStateActive;
    if (admitted) operation.state = RaftThreadImageCompressionOperationStatePending;
    [operation.lock unlock];
    [self.raft_threadImageCompressionOperationsLock unlock];
    if (!admitted) {
        if (![operation isDelivered]) {
            [operation markCancelledForce:YES];
            [operation removeOutputs];
        }
        return NO;
    }

    __weak typeof(self) weakSelf = self;
    RaftBridgeRunOnMain(^{
        HRBridgeModule *strongSelf = weakSelf;
        if (!strongSelf) {
            [operation markCancelledForce:YES];
            [operation removeOutputs];
            return;
        }

        BOOL callbackInvoked = NO;
        BOOL callbackFailed = NO;
        [strongSelf.raft_threadImageCompressionOperationsLock lock];
        [operation.lock lock];
        BOOL current = !strongSelf.raft_threadImageCompressionDestroyed &&
            strongSelf.raft_threadImageCompressionOperations[operation.requestId] == operation &&
            operation.state == RaftThreadImageCompressionOperationStatePending;
        if (current) {
            // Hold both lifecycle locks across callback invocation.  A re-entrant ordinary cancel
            // observes DELIVERED and is a no-op; another thread waits until the callback has
            // returned before it can cancel or force-clean this operation.
            operation.state = RaftThreadImageCompressionOperationStateDelivered;
            RaftBridgeCallback callback = operation.callback;
            if (callback) {
                callbackInvoked = YES;
                @try {
                    callback(payload ?: RaftThreadImageCompressionFailurePayload());
                } @catch (__unused NSException *exception) {
                    callbackFailed = YES;
                    operation.state = RaftThreadImageCompressionOperationStateCancelled;
                }
            } else {
                operation.state = RaftThreadImageCompressionOperationStateCancelled;
            }
        } else if (strongSelf.raft_threadImageCompressionOperations[operation.requestId] == operation) {
            // The operation became stale or teardown started after worker admission.  Fence it
            // before releasing the locks so no later callback can observe a pending identity.
            operation.state = RaftThreadImageCompressionOperationStateCancelled;
            [strongSelf.raft_threadImageCompressionOperations removeObjectForKey:operation.requestId];
        }
        [operation.lock unlock];
        [strongSelf.raft_threadImageCompressionOperationsLock unlock];

        // Keep a DELIVERED operation in the map until this callback turn has finished.  This
        // gives module destruction a chance to force-clean a still-tracked output, while a normal
        // cancel that re-enters from the callback observes DELIVERED and leaves ownership alone.
        if (current) {
            [strongSelf raft_removeThreadImageCompressionOperation:operation];
        }
        if (!current || callbackFailed || !callbackInvoked) {
            [operation removeOutputs];
        }
    });
    return YES;
}

#pragma mark - Slock persistent attachment store

// Persistent local copy keyed by <ownerKey>-<safe(attachmentId)>.
// Files live under Application Support/Slock/Attachments so they survive across
// launches; "Open with" can then share the local file directly instead of
// re-downloading to a throwaway temp dir each tap.

static NSString *RaftAttachmentStoreRoot() {
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

// Throwaway download staging for Save to Files / Photos exports (one subdirectory per request).
static NSString *RaftAttachmentExportRootPath(void) {
    return [NSTemporaryDirectory() stringByAppendingPathComponent:RaftAttachmentExportDirectoryName];
}

#pragma mark - Settings storage

static NSString *RaftStorageDatabaseDirectoryPath(void) {
    NSString *base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES).firstObject;
    return base.length > 0 ? [base stringByAppendingPathComponent:RaftSqlDatabaseDirectoryRelativePath] : @"";
}

static NSString *RaftStoragePickedAttachmentDirectoryPath(void) {
    NSString *caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
    if (caches.length == 0) return @"";
    return [[caches stringByAppendingPathComponent:RaftPickedAttachmentParentDirectoryName]
        stringByAppendingPathComponent:RaftThreadImageCompressionDirectoryName];
}

static BOOL RaftStorageIsFeedbackDiagnosticsArchiveName(NSString *name) {
    return [name hasPrefix:RaftFeedbackDiagnosticsArchivePrefix] &&
        [name.pathExtension isEqualToString:RaftFeedbackDiagnosticsArchiveExtension];
}

static BOOL RaftStorageIsCameraCaptureName(NSString *name) {
    return [name.pathExtension isEqualToString:RaftCameraCaptureFileExtension] &&
        [[NSUUID alloc] initWithUUIDString:name.stringByDeletingPathExtension] != nil;
}

/// Logical byte size (same measure as Android File.length()) of a file or directory tree.
static unsigned long long RaftStorageTreeBytes(NSString *path) {
    if (path.length == 0) return 0;
    NSFileManager *fileManager = NSFileManager.defaultManager;
    BOOL isDirectory = NO;
    if (![fileManager fileExistsAtPath:path isDirectory:&isDirectory]) return 0;
    if (!isDirectory) return [[fileManager attributesOfItemAtPath:path error:nil] fileSize];
    NSArray<NSURLResourceKey> *keys = @[ NSURLIsRegularFileKey, NSURLFileSizeKey ];
    NSDirectoryEnumerator<NSURL *> *enumerator =
        [fileManager enumeratorAtURL:[NSURL fileURLWithPath:path isDirectory:YES]
          includingPropertiesForKeys:keys
                             options:0
                        errorHandler:^BOOL(NSURL *url, NSError *error) { return YES; }];
    unsigned long long total = 0;
    for (NSURL *url in enumerator) {
        @autoreleasepool {
            NSDictionary<NSURLResourceKey, id> *values = [url resourceValuesForKeys:keys error:nil];
            if ([values[NSURLIsRegularFileKey] boolValue]) {
                total += [values[NSURLFileSizeKey] unsignedLongLongValue];
            }
        }
    }
    return total;
}

/// Regular files directly inside NSTemporaryDirectory() whose name matches. Only app-owned
/// name patterns are passed in; the rest of tmp is never counted or touched.
static unsigned long long RaftStorageTemporaryFileBytes(BOOL (*matches)(NSString *name)) {
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSString *temporaryDirectory = NSTemporaryDirectory();
    unsigned long long total = 0;
    for (NSString *name in [fileManager contentsOfDirectoryAtPath:temporaryDirectory error:nil] ?: @[]) {
        if (!matches(name)) continue;
        NSDictionary<NSFileAttributeKey, id> *attributes =
            [fileManager attributesOfItemAtPath:[temporaryDirectory stringByAppendingPathComponent:name] error:nil];
        if ([attributes.fileType isEqualToString:NSFileTypeRegular]) total += attributes.fileSize;
    }
    return total;
}

/// Deletes the children of `path`, keeping the directory itself (Android deleteContents()).
static BOOL RaftStorageRemoveDirectoryContents(NSString *path) {
    if (path.length == 0) return YES;
    NSFileManager *fileManager = NSFileManager.defaultManager;
    BOOL isDirectory = NO;
    if (![fileManager fileExistsAtPath:path isDirectory:&isDirectory]) return YES;
    if (!isDirectory) return NO;
    BOOL removedAll = YES;
    for (NSString *name in [fileManager contentsOfDirectoryAtPath:path error:nil] ?: @[]) {
        NSString *child = [path stringByAppendingPathComponent:name];
        if (![fileManager removeItemAtPath:child error:nil] && [fileManager fileExistsAtPath:child]) {
            removedAll = NO;
        }
    }
    return removedAll;
}

/// Clears a directory that AttachmentDownloadCoordinator writes into. The directory reservation
/// rejects new downloads under the prefix; active ones are cancelled and their sink cleanup is
/// awaited before any file is removed (same fence as Android clearDirectAttachmentStaging).
static BOOL RaftStorageClearDownloadDirectory(NSString *directory) {
    if (directory.length == 0) return YES;
    NSString *prefix = [directory hasSuffix:@"/"] ? directory : [directory stringByAppendingString:@"/"];
    SharedAttachmentDownloadCoordinator *coordinator = SharedAttachmentDownloadCoordinator.shared;
    SharedAttachmentDownloadDirectoryCleanupReservation *directoryReservation =
        [coordinator beginDirectoryCleanupTargetPrefix:prefix];
    NSMutableDictionary<NSString *, NSString *> *targetTokens = [NSMutableDictionary dictionary];
    BOOL fenced = YES;
    for (NSString *targetKey in directoryReservation.targetKeys) {
        SharedAttachmentDownloadCleanupReservation *reservation =
            [coordinator beginTargetCleanupTargetKey:targetKey expectedAttemptToken:nil];
        if (reservation.token.length > 0) targetTokens[targetKey] = reservation.token;
        if (![reservation.status isEqualToString:@"missing"] &&
            ![reservation.status isEqualToString:@"cancelled"] &&
            ![reservation.status isEqualToString:@"complete"]) {
            fenced = NO;
        }
    }
    BOOL cleared = fenced && RaftStorageRemoveDirectoryContents(directory);
    [targetTokens enumerateKeysAndObjectsUsingBlock:^(NSString *targetKey, NSString *token, BOOL *stop) {
        [coordinator finishTargetCleanupTargetKey:targetKey token:token];
    }];
    [coordinator finishDirectoryCleanupTargetPrefix:prefix token:directoryReservation.token];
    return cleared;
}

static void RaftStorageRemoveStaleFeedbackDiagnosticsArchives(void) {
    // An upload operation owns and deletes its archive; only leftovers past the feedback prune
    // age (orphans from a killed upload) are removed so an in-flight upload keeps its file.
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSString *temporaryDirectory = NSTemporaryDirectory();
    NSDate *now = NSDate.date;
    for (NSString *name in [fileManager contentsOfDirectoryAtPath:temporaryDirectory error:nil] ?: @[]) {
        if (!RaftStorageIsFeedbackDiagnosticsArchiveName(name)) continue;
        NSString *path = [temporaryDirectory stringByAppendingPathComponent:name];
        NSDate *modifiedAt = [[fileManager attributesOfItemAtPath:path error:nil] fileModificationDate] ?: NSDate.distantPast;
        if ([now timeIntervalSinceDate:modifiedAt] >= RaftDiagnosticsFeedbackPruneMinimumAgeSeconds) {
            [fileManager removeItemAtPath:path error:nil];
        }
    }
}

static RaftStorageUsage RaftStorageMeasure(void) {
    RaftStorageUsage usage;
    usage.database = RaftStorageTreeBytes(RaftStorageDatabaseDirectoryPath());
    // Avatars are counted here but cleared only by the shared avatar runtime (store.clearAll),
    // which SharedSettingsDataSource.clearStorage runs before this host's imageCache clear.
    usage.imageCache = (unsigned long long)[SDImageCache.sharedImageCache totalDiskSize] +
        RaftStorageTreeBytes(RaftIosAvatarCacheHost.shared.rootPath);
    usage.attachmentDownloads = RaftStorageTreeBytes(RaftAttachmentStoreRoot()) +
        RaftStorageTreeBytes(RaftAttachmentExportRootPath());
    // Picked attachments and camera captures belong to composer drafts (retired through
    // RaftIosRetireThreadComposerTemporaryFileRefs); they are counted but never cleared here.
    usage.temporaryAndLogs = RaftStorageTreeBytes(RaftDiagnosticsDirectoryPath()) +
        RaftStorageTreeBytes(RaftThreadShareImageDirectoryPath()) +
        RaftStorageTemporaryFileBytes(RaftStorageIsFeedbackDiagnosticsArchiveName) +
        RaftStorageTemporaryFileBytes(RaftStorageIsCameraCaptureName) +
        RaftStorageTreeBytes(RaftStoragePickedAttachmentDirectoryPath());
    return usage;
}

static NSDictionary *RaftStorageRow(NSString *target, NSString *title, NSString *detail,
                                     unsigned long long bytes, BOOL clearEnabled) {
    return @{
        @"target" : target,
        @"title" : title,
        @"detail" : detail,
        @"sizeBytes" : @(bytes),
        @"clearEnabled" : @(clearEnabled)
    };
}

static NSDictionary *RaftStoragePayload(RaftStorageUsage usage, NSString *feedback) {
    unsigned long long total =
        usage.database + usage.imageCache + usage.attachmentDownloads + usage.temporaryAndLogs;
    return @{
        @"status" : @"ok",
        @"totalSizeBytes" : @(total),
        @"feedback" : feedback ?: @"",
        @"rows" : @[
            RaftStorageRow(@"database", @"Local database",
                            @"Messages, task metadata, and server snapshots stored on this device.",
                            usage.database, NO),
            RaftStorageRow(@"imageCache", @"Image cache",
                            @"Downloaded image previews kept for faster viewing.",
                            usage.imageCache, YES),
            RaftStorageRow(@"attachmentDownloads", @"Attachment cache",
                            @"Private copies kept for opening or installing files. Public exports are not removed.",
                            usage.attachmentDownloads, YES),
            RaftStorageRow(@"temporaryAndLogs", @"Temporary files and logs",
                            @"Debug logs and temporary files generated by the app.",
                            usage.temporaryAndLogs, YES)
        ]
    };
}

/// Runs on a background queue; `completion` may be called on any queue.
static void RaftStorageClearTarget(NSString *target, void (^completion)(NSDictionary *result)) {
    if ([target isEqualToString:@"imageCache"]) {
        SDImageCache *cache = SDImageCache.sharedImageCache;
        [cache clearMemory];
        [cache clearDiskOnCompletion:^{
            dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
                RaftDiagnosticsRecord(@"info", @"SlockStorage", @"shared_clear target=imageCache");
                completion(RaftStoragePayload(RaftStorageMeasure(), @"Image cache cleared."));
            });
        }];
        return;
    }
    if ([target isEqualToString:@"attachmentDownloads"]) {
        BOOL storeCleared = RaftStorageClearDownloadDirectory(RaftAttachmentStoreRoot());
        BOOL exportsCleared = RaftStorageClearDownloadDirectory(RaftAttachmentExportRootPath());
        if (!storeCleared || !exportsCleared) {
            RaftDiagnosticsRecord(@"warn", @"SlockStorage", @"shared_clear target=attachmentDownloads result=failure");
            completion(@{ @"status" : @"error", @"code" : @"settings_storage_clear_failed" });
            return;
        }
        RaftDiagnosticsRecord(@"info", @"SlockStorage", @"shared_clear target=attachmentDownloads");
        completion(RaftStoragePayload(RaftStorageMeasure(), @"Attachment cache cleared. Public exports were not removed."));
        return;
    }
    if ([target isEqualToString:@"temporaryAndLogs"]) {
        RaftStorageRemoveDirectoryContents(RaftThreadShareImageDirectoryPath());
        RaftStorageRemoveStaleFeedbackDiagnosticsArchives();
        RaftDiagnosticsClear();
        RaftDiagnosticsRecord(@"info", @"SlockStorage", @"shared_clear target=temporaryAndLogs");
        completion(RaftStoragePayload(RaftStorageMeasure(), @"Temporary files and diagnostics logs cleared."));
        return;
    }
    completion(@{ @"status" : @"error", @"code" : @"settings_storage_clear_unsupported" });
}

NSString *RaftAttachmentSafeComponent(NSString *raw) {
    if (raw.length == 0) return @"";
    NSMutableCharacterSet *allowed = [NSMutableCharacterSet alphanumericCharacterSet];
    [allowed addCharactersInString:@"-_."];
    return [[raw componentsSeparatedByCharactersInSet:[allowed invertedSet]] componentsJoinedByString:@"_"];
}

NSString *RaftAttachmentCacheKey(NSString *ownerKey, NSString *attachmentId) {
    NSString *owner = RaftAttachmentSafeComponent(ownerKey ?: @"");
    NSString *attachment = RaftAttachmentSafeComponent(attachmentId ?: @"");
    if (attachment.length == 0) return @"";
    return owner.length ? [owner stringByAppendingFormat:@"-%@", attachment] : attachment;
}

// Key scheme: directory keyed by <ownerKey>-<safe(attachmentId)>, the file lives
// inside it; lookups only need the directory, never the filename.
static NSString *RaftAttachmentCacheDirectory(NSString *ownerKey, NSString *attachmentId) {
    NSString *key = RaftAttachmentCacheKey(ownerKey, attachmentId);
    if (key.length == 0) return nil;
    return [RaftAttachmentStoreRoot() stringByAppendingFormat:@"/%@", key];
}

static NSString *RaftAttachmentCachedFilePath(NSString *ownerKey, NSString *attachmentId, NSString *fileName) {
    NSString *dir = RaftAttachmentCacheDirectory(ownerKey, attachmentId);
    if (!dir) return nil;
    NSString *leaf = RaftAttachmentSafeComponent(fileName ?: @"attachment");
    if (leaf.length == 0) leaf = @"attachment";
    return [dir stringByAppendingFormat:@"/%@", leaf];
}

NSString *RaftAttachmentReusableExportFileName(
    NSArray<NSString *> *storedFileNames,
    NSString *requestedFileName) {
    if (storedFileNames.count == 0) {
        return nil;
    }
    // A `.part-` file is an in-progress download, never a usable copy.
    NSMutableArray<NSString *> *usable = [NSMutableArray arrayWithCapacity:storedFileNames.count];
    for (NSString *name in storedFileNames) {
        if (name.length > 0 && ![name containsString:@".part-"]) {
            [usable addObject:name];
        }
    }
    if (usable.count == 0) {
        return nil;
    }
    NSString *wanted = RaftAttachmentSafeComponent(requestedFileName ?: @"");
    for (NSString *name in usable) {
        if (name.length == wanted.length && [name isEqualToString:wanted]) {
            return name;
        }
    }
    return usable.firstObject;
}

static NSDictionary *RaftAttachmentCacheStatusDict(NSString *directoryPath) {
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
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    if (self.raft_attachmentExportCallback) {
        callback(@{ @"status" : @"failed", @"reason" : @"busy",
                    @"message" : @"Another file operation is already active" });
        return;
    }

    NSDictionary *params = RaftBridgeParams(args);
    NSString *ownerKey = RaftBridgeString(params[@"ownerKey"] ?: params[@"owner"]);
    NSString *attachmentId = RaftBridgeString(params[@"attachmentId"]);
    NSString *fileName = RaftBridgeString(params[@"fileName"]) ?: @"attachment";
    NSString *downloadId = RaftBridgeString(params[@"downloadId"]) ?: attachmentId;
    BOOL requirePublicDownload = [params[@"requirePublicDownload"] boolValue];
    // In-app preview (PDF): download into the same attachment cache and report the local path;
    // no share sheet is presented.
    BOOL prepareOnly = [params[@"prepareOnly"] boolValue];

    if (!prepareOnly) {
        // Capture the presenter at tap time while the page is certainly alive (weak),
        // then prefer it with a frontmost fallback — Signal AttachmentSharing L117.
        self.raft_sharePresenterController = [self raft_currentViewController];
    }

    NSString *cachedDir = RaftAttachmentCacheDirectory(ownerKey, attachmentId);
    NSString *cachedPath = RaftAttachmentCachedFilePath(ownerKey, attachmentId, fileName);
    NSDictionary *status = cachedPath
        ? RaftAttachmentCacheStatusDict(cachedDir)
        : @{ @"status" : @"missing" };

    if (prepareOnly && [status[@"cached"] boolValue]) {
        NSString *cachedFilePath = [NSURL URLWithString:status[@"uri"] ?: @""].path ?: cachedPath;
        callback(@{ @"status" : @"ready", @"cached" : @YES, @"path" : cachedFilePath ?: @"" });
        return;
    }

    if ([status[@"cached"] boolValue]) {
        [self raft_presentShareItems:@[ [NSURL fileURLWithPath:cachedPath] ] attempt:1];
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
    NSString *urlText = RaftBridgeString(params[@"url"]);
    if (urlText.length == 0) {
        callback(@{ @"status" : @"failed", @"reason" : @"missing_url" });
        return;
    }
    // The download sink creates `<cachedPath>.part-<token>` with posix creat, which does not
    // create parents. A never-cached attachment (e.g. a Markdown preview rendered from memory)
    // has no cache directory yet, so create it first or every first open fails immediately.
    NSError *directoryError = nil;
    if (![NSFileManager.defaultManager createDirectoryAtPath:cachedDir
                                 withIntermediateDirectories:YES
                                                  attributes:nil
                                                       error:&directoryError]) {
        callback(@{ @"status" : @"storage_failed", @"reason" : @"cache_dir_create_failed",
                    @"errorKind" : @"storage_prepare",
                    @"message" : @"Unable to prepare attachment for opening" });
        return;
    }
    self.raft_attachmentExportDownloadId = downloadId;
    self.raft_attachmentExportURL = [NSURL fileURLWithPath:cachedPath];
    self.raft_attachmentExportDestination =
        prepareOnly ? RaftAttachmentExportDestinationPreview : RaftAttachmentExportDestinationOpen;
    // Only the preview download reports progress; Open keeps its previous behaviour.
    self.raft_attachmentExportTrackProgress = prepareOnly && RaftBridgeBool(params[@"trackProgress"]);
    self.raft_attachmentExportFileName = fileName;
    self.raft_attachmentExportMimeType = RaftBridgeString(params[@"mimeType"]);
    self.raft_attachmentExportCallback = callback;
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
        self.raft_attachmentExportCallback = nil;
        self.raft_attachmentExportURL = nil;
        return;
    }
    self.raft_attachmentExportRequestId = attemptToken;
}

- (void)attachmentCacheStatus:(NSDictionary *)args {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    NSDictionary *params = RaftBridgeParams(args);
    NSString *cachedDir = RaftAttachmentCacheDirectory(
        RaftBridgeString(params[@"ownerKey"] ?: params[@"owner"]),
        RaftBridgeString(params[@"attachmentId"]));
    NSDictionary *status = cachedDir
        ? RaftAttachmentCacheStatusDict(cachedDir)
        : @{ @"status" : @"missing" };
    callback(status);
}

- (void)deleteAttachmentCache:(NSDictionary *)args {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    NSDictionary *params = RaftBridgeParams(args);
    NSString *cachedDir = RaftAttachmentCacheDirectory(
        RaftBridgeString(params[@"ownerKey"] ?: params[@"owner"]),
        RaftBridgeString(params[@"attachmentId"]));
    BOOL removed = cachedDir &&
        [NSFileManager.defaultManager removeItemAtPath:cachedDir error:nil];
    if (callback) {
        callback(@{ @"status" : removed ? @"ok" : @"missing" });
    }
}

- (void)saveAttachment:(NSDictionary *)args {
    [self raft_startAttachmentExport:args destination:RaftAttachmentExportDestinationFiles];
}

- (void)saveImageToPhotos:(NSDictionary *)args {
    [self raft_startAttachmentExport:args destination:RaftAttachmentExportDestinationPhotos];
}

- (void)cancelAttachmentExport:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *downloadId = RaftBridgeString(params[@"downloadId"]);
    NSString *operationId = RaftBridgeString(params[@"operationId"]);
    if (!self.raft_attachmentExportCallback || downloadId.length == 0 ||
        operationId.length == 0 ||
        ![self.raft_attachmentExportDownloadId isEqualToString:downloadId] ||
        ![self.raft_attachmentExportOperationId isEqualToString:operationId]) {
        return;
    }
    NSString *expectedAttemptToken = RaftBridgeString(params[@"attemptToken"]);
    NSString *attemptToken = expectedAttemptToken.length > 0
        ? expectedAttemptToken
        : ([self.raft_attachmentExportRequestId isEqualToString:@"pending"]
            ? @""
            : (self.raft_attachmentExportRequestId ?: @""));
    [SharedAttachmentDownloadCoordinator.shared controlExistingDownloadId:downloadId
                                                      expectedAttemptToken:attemptToken
                                                                    action:@"cancel"];
    [self raft_finishAttachmentExport:@{ @"status" : @"cancelled" }];
}

- (void)raft_startAttachmentExport:(NSDictionary *)args destination:(NSString *)destination {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) {
        return;
    }
    if (self.raft_attachmentExportCallback) {
        callback(@{
            @"status" : @"failed",
            @"message" : @"Another file save is already active"
        });
        return;
    }
    NSDictionary *params = RaftBridgeParams(args);
    NSString *urlText = RaftBridgeString(params[@"url"]);
    NSURL *url = [NSURL URLWithString:urlText];
    if (!url || url.scheme.length == 0) {
        callback(@{
            @"status" : @"failed",
            @"message" : @"Unable to download attachment"
        });
        return;
    }
    NSString *fileName = RaftAttachmentExportFileName(RaftBridgeString(params[@"fileName"]));
    NSString *mimeType = [[[[RaftBridgeString(params[@"mimeType"])
        componentsSeparatedByString:@";"] firstObject]
        stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet]
        lowercaseString];
    NSString *operationId = RaftBridgeString(params[@"operationId"]);
    if (operationId.length == 0) {
        callback(@{
            @"status" : @"failed",
            @"message" : @"Missing attachment export authority"
        });
        return;
    }
    BOOL genericBinaryMime =
        [mimeType isEqualToString:@"application/octet-stream"] || [mimeType isEqualToString:@"binary/octet-stream"];
    BOOL savesToPhotos = [destination isEqualToString:RaftAttachmentExportDestinationPhotos];
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
    self.raft_attachmentExportCallback = callback;
    self.raft_attachmentExportDownloadId = RaftBridgeString(params[@"downloadId"]);
    self.raft_attachmentExportOperationId = operationId;
    self.raft_attachmentExportDestination = destination;
    self.raft_attachmentExportMimeType = genericBinaryMime && !savesToPhotos ? @"" : mimeType;
    self.raft_attachmentExportFileName = fileName;
    self.raft_attachmentExportTrackProgress = RaftBridgeBool(params[@"trackProgress"]);

    if (!savesToPhotos) {
        [self raft_beginAttachmentExportDownloadWithParams:params];
        return;
    }

    PHAuthorizationStatus status = [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelAddOnly];
    if (status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited) {
        self.raft_attachmentExportPermissionStatus =
            status == PHAuthorizationStatusLimited ? @"limited" : @"authorized";
        [self raft_beginAttachmentExportDownloadWithParams:params];
        return;
    }
    if (status == PHAuthorizationStatusDenied || status == PHAuthorizationStatusRestricted) {
        [self raft_finishAttachmentExport:@{
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
    NSString *capturedDownloadId = self.raft_attachmentExportDownloadId ?: @"";
    NSString *capturedOperationId = self.raft_attachmentExportOperationId ?: @"";
    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus authorizationStatus) {
        RaftBridgeRunOnMain(^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf || !strongSelf.raft_attachmentExportCallback ||
                ![strongSelf.raft_attachmentExportDestination isEqualToString:RaftAttachmentExportDestinationPhotos] ||
                ![strongSelf.raft_attachmentExportDownloadId isEqualToString:capturedDownloadId] ||
                ![strongSelf.raft_attachmentExportOperationId isEqualToString:capturedOperationId]) {
                return;
            }
            if (authorizationStatus == PHAuthorizationStatusAuthorized || authorizationStatus == PHAuthorizationStatusLimited) {
                strongSelf.raft_attachmentExportPermissionStatus =
                    authorizationStatus == PHAuthorizationStatusLimited ? @"limited" : @"authorized";
                [strongSelf raft_beginAttachmentExportDownloadWithParams:capturedParams];
                return;
            }
            BOOL restricted = authorizationStatus == PHAuthorizationStatusRestricted;
            [strongSelf raft_finishAttachmentExport:@{
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

// Reuses the durable local copy of an attachment for an export, when one exists.
// The persistent attachment store is keyed by `<ownerKey>-<safe(attachmentId)>` and holds the
// file "Open with" downloaded earlier. When the same attachment is saved to Files or Photos,
// that copy is presented instead of downloading the bytes a second time.
//
// `requestedFileName` is the name the attachment was stored under (the raw name, which the
// store sanitizes); `exportFileName` is the name the exported file gets.
//
// Returns YES when the export was served from the local copy (the callback is answered through
// the normal presentation path); NO when there is no usable copy and the caller must download.
- (BOOL)raft_reuseCachedAttachmentForExportWithParams:(NSDictionary *)params
                                   requestedFileName:(NSString *)requestedFileName
                                       exportFileName:(NSString *)exportFileName {
    NSString *ownerKey = RaftBridgeString(params[@"ownerKey"] ?: params[@"owner"]);
    NSString *attachmentId = RaftBridgeString(params[@"attachmentId"]);
    if (ownerKey.length == 0 || attachmentId.length == 0) {
        return NO;
    }
    NSString *cachedDir = RaftAttachmentCacheDirectory(ownerKey, attachmentId);
    if (!cachedDir) {
        return NO;
    }
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSArray<NSString *> *storedNames = [fileManager contentsOfDirectoryAtPath:cachedDir error:nil];
    NSString *reusableName = RaftAttachmentReusableExportFileName(storedNames, requestedFileName);
    if (!reusableName) {
        return NO;
    }
    NSString *cachedPath = [cachedDir stringByAppendingPathComponent:reusableName];
    NSDictionary *attributes = [fileManager attributesOfItemAtPath:cachedPath error:nil];
    long long sizeBytes = [attributes[NSFileSize] longLongValue];
    if (sizeBytes <= 0) {
        return NO;
    }
    // The export path presents the file and then removes the directory it lives in
    // (raft_finishAttachmentExport deletes the parent). The durable copy must survive that, so
    // the file is staged into the throwaway export directory first and presented from there.
    NSURL *root = [NSURL fileURLWithPath:RaftAttachmentExportRootPath() isDirectory:YES];
    NSString *requestId = NSUUID.UUID.UUIDString;
    NSURL *directory = [root URLByAppendingPathComponent:requestId isDirectory:YES];
    NSURL *targetURL = [directory URLByAppendingPathComponent:exportFileName isDirectory:NO];
    NSError *error = nil;
    [fileManager createDirectoryAtURL:directory withIntermediateDirectories:YES attributes:nil error:&error];
    if (error) {
        return NO;
    }
    if (![fileManager copyItemAtPath:cachedPath toPath:targetURL.path error:&error]) {
        [fileManager removeItemAtURL:directory error:nil];
        return NO;
    }
    self.raft_attachmentExportURL = targetURL;
    [self raft_presentAttachmentExportWithURL:targetURL sizeBytes:sizeBytes];
    return YES;
}

- (void)raft_beginAttachmentExportDownloadWithParams:(NSDictionary *)params {
    // Save to Files / Save to Photos reuses the durable local copy when the attachment was
    // already downloaded ("Open with" keeps one), instead of fetching it again. This sits at
    // the point where a download would start, so the Photos path still asks for the photo
    // library permission first and reports it on the result.
    if ([self raft_reuseCachedAttachmentForExportWithParams:params
                                       requestedFileName:RaftBridgeString(params[@"fileName"])
                                           exportFileName:self.raft_attachmentExportFileName ?: @"attachment"]) {
        return;
    }
    NSString *urlText = RaftBridgeString(params[@"url"]);
    NSString *fileName = self.raft_attachmentExportFileName ?: @"attachment";
    NSString *requestId = NSUUID.UUID.UUIDString;
    NSString *downloadId = RaftBridgeString(params[@"downloadId"]);
    if (downloadId.length == 0) downloadId = requestId;
    self.raft_attachmentExportRequestId = @"pending";
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSURL *root = [NSURL fileURLWithPath:RaftAttachmentExportRootPath() isDirectory:YES];
    NSURL *directory = [root URLByAppendingPathComponent:requestId isDirectory:YES];
    NSURL *targetURL = [directory URLByAppendingPathComponent:fileName isDirectory:NO];
    NSError *fileError = nil;
    [fileManager createDirectoryAtURL:directory withIntermediateDirectories:YES attributes:nil error:&fileError];
    if (fileError) {
        [self raft_finishAttachmentExport:@{
            @"status" : @"storage_failed",
            @"message" : @"Unable to prepare attachment for export"
        }];
        return;
    }
    self.raft_attachmentExportURL = targetURL;
    NSString *attemptToken =
        [SharedAttachmentDownloadCoordinator.shared startDownloadId:downloadId
                                                          targetKey:targetURL.path
                                                                url:urlText
                                                         targetPath:targetURL.path
                                                           observer:self];
    if (self.raft_attachmentExportCallback) {
        self.raft_attachmentExportRequestId = attemptToken;
        if (attemptToken.length == 0) {
            [self raft_finishAttachmentExport:@{
                @"status" : @"failed",
                @"message" : @"Unable to start attachment download"
            }];
        }
    }
}

- (void)onProgressProgress:(SharedAttachmentDownloadProgress *)progress {
    __weak typeof(self) weakSelf = self;
    RaftBridgeRunOnMain(^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf.raft_attachmentExportTrackProgress || !strongSelf.raft_attachmentExportCallback ||
            (strongSelf.raft_attachmentExportRequestId.length > 0 &&
             ![strongSelf.raft_attachmentExportRequestId isEqualToString:@"pending"] &&
             ![strongSelf.raft_attachmentExportRequestId isEqualToString:progress.attemptToken])) {
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
        strongSelf.raft_attachmentExportCallback(payload);
    });
}

- (void)onCompleteResult:(SharedAttachmentDownloadResult *)result {
    __weak typeof(self) weakSelf = self;
    RaftBridgeRunOnMain(^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf || !strongSelf.raft_attachmentExportCallback ||
            (strongSelf.raft_attachmentExportRequestId.length > 0 &&
             ![strongSelf.raft_attachmentExportRequestId isEqualToString:@"pending"] &&
             ![strongSelf.raft_attachmentExportRequestId isEqualToString:result.attemptToken])) {
            return;
        }
        if (![result.status isEqualToString:@"complete"]) {
            NSString *errorKind = result.errorKind ?: @"";
            BOOL storageFailure =
                [errorKind isEqualToString:@"storage_failed"] ||
                [errorKind isEqualToString:@"storage_write"] ||
                [errorKind isEqualToString:@"storage_prepare"] ||
                [errorKind isEqualToString:@"storage_publish"];
            NSString *status = [result.status isEqualToString:@"cancelled"]
                ? @"cancelled"
                : (storageFailure ? @"storage_failed" : @"download_failed");
            [strongSelf raft_finishAttachmentExport:@{
                @"status" : status,
                @"message" : result.message ?: @"Unable to download attachment",
                @"errorKind" : errorKind
            }];
            return;
        }
        NSURL *targetURL = strongSelf.raft_attachmentExportURL;
        if (!targetURL) {
            [strongSelf raft_finishAttachmentExport:@{
                @"status" : @"storage_failed",
                @"message" : @"Unable to prepare attachment for export"
            }];
            return;
        }
        strongSelf.raft_attachmentExportSizeBytes = @(result.downloadedBytes);
        [strongSelf raft_presentAttachmentExportWithURL:targetURL sizeBytes:result.downloadedBytes];
    });
}

// Presents an attachment that is on disk, according to the destination the caller asked for.
// `targetURL` is the local file; `sizeBytes` its size. Shared by the download-completed path and
// the path that reuses an already downloaded local copy.
- (void)raft_presentAttachmentExportWithURL:(NSURL *)targetURL sizeBytes:(long long)sizeBytes {
    __weak typeof(self) weakSelf = self;
    RaftBridgeRunOnMain(^{
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (!strongSelf) {
            return;
        }
        strongSelf.raft_attachmentExportSizeBytes = @(sizeBytes);
        if ([strongSelf.raft_attachmentExportDestination isEqualToString:RaftAttachmentExportDestinationPreview]) {
            // In-app preview: keep the cached copy (no raft_finishAttachmentExport, which deletes
            // the parent dir) and report its path; nothing is presented.
            RaftBridgeCallback previewCallback = strongSelf.raft_attachmentExportCallback;
            strongSelf.raft_attachmentExportCallback = nil;
            strongSelf.raft_attachmentExportRequestId = nil;
            strongSelf.raft_attachmentExportDownloadId = nil;
            strongSelf.raft_attachmentExportURL = nil;
            strongSelf.raft_attachmentExportFileName = nil;
            strongSelf.raft_attachmentExportSizeBytes = nil;
            strongSelf.raft_attachmentExportDestination = nil;
            strongSelf.raft_attachmentExportMimeType = nil;
            strongSelf.raft_attachmentExportTrackProgress = NO;
            if (previewCallback) {
                previewCallback(@{
                    @"status" : @"ready",
                    @"cached" : @NO,
                    @"path" : targetURL.path ?: @"",
                });
            }
            return;
        }
        if ([strongSelf.raft_attachmentExportDestination isEqualToString:RaftAttachmentExportDestinationOpen]) {
            // Open-with: the file is a persistent local copy — present the share sheet on it,
            // do NOT call raft_finishAttachmentExport (it deletes the parent dir) or the
            // save-to-files picker. Clear state and report success, keeping the file.
            RaftBridgeCallback openCallback = strongSelf.raft_attachmentExportCallback;
            [strongSelf raft_presentShareItems:@[ targetURL ] attempt:1];
            strongSelf.raft_attachmentExportCallback = nil;
            strongSelf.raft_attachmentExportRequestId = nil;
            strongSelf.raft_attachmentExportDownloadId = nil;
            strongSelf.raft_attachmentExportURL = nil;
            strongSelf.raft_attachmentExportFileName = nil;
            strongSelf.raft_attachmentExportSizeBytes = nil;
            strongSelf.raft_attachmentExportDestination = nil;
            strongSelf.raft_attachmentExportMimeType = nil;
            if (openCallback) {
                openCallback(@{
                    @"status" : @"opened",
                    @"cached" : @YES,
                    @"uri" : targetURL.absoluteString ?: @"",
                });
            }
            return;
        }
        if ([strongSelf.raft_attachmentExportDestination isEqualToString:RaftAttachmentExportDestinationPhotos]) {
            [strongSelf raft_saveAttachmentExportToPhotos];
            return;
        }
        UIViewController *controller = [strongSelf raft_currentViewController];
        if (!controller) {
            [strongSelf raft_finishAttachmentExport:@{
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

- (void)raft_saveAttachmentExportToPhotos {
    NSURL *targetURL = self.raft_attachmentExportURL;
    if (!targetURL) {
        [self raft_finishAttachmentExport:@{
            @"status" : @"storage_failed",
            @"message" : @"Unable to prepare image for Photos.",
            @"retryable" : @YES
        }];
        return;
    }

    CGImageSourceRef source = CGImageSourceCreateWithURL((__bridge CFURLRef)targetURL, NULL);
    if (!source || CGImageSourceGetCount(source) == 0) {
        if (source) CFRelease(source);
        [self raft_finishAttachmentExport:@{
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
        [self raft_finishAttachmentExport:@{
            @"status" : @"unsupported_image",
            @"message" : @"This attachment is not a supported image.",
            @"retryable" : @NO
        }];
        return;
    }

    NSString *expectedMimeType = self.raft_attachmentExportMimeType ?: @"";
    BOOL hasMimeClaim = expectedMimeType.length > 0;
    NSString *claimedExtension = self.raft_attachmentExportFileName.pathExtension ?: @"";
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
        [self raft_finishAttachmentExport:@{
            @"status" : @"unsupported_image",
            @"message" : @"The downloaded image does not match its attachment type.",
            @"retryable" : @NO
        }];
        return;
    }

    NSString *fileName = self.raft_attachmentExportFileName ?: @"image";
    NSURL *photoResourceURL = targetURL;
    if (fileName.pathExtension.length == 0) {
        NSString *preferredExtension = actualType.preferredFilenameExtension ?: @"";
        if (preferredExtension.length == 0) {
            [self raft_finishAttachmentExport:@{
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
            [self raft_finishAttachmentExport:@{
                @"status" : @"storage_failed",
                @"message" : @"Unable to prepare image for Photos.",
                @"retryable" : @YES
            }];
            return;
        }
    }
    NSString *permissionStatus = self.raft_attachmentExportPermissionStatus ?: @"authorized";
    NSString *actualMimeType = actualType.preferredMIMEType ?: @"";
    NSNumber *sizeBytes = self.raft_attachmentExportSizeBytes ?: @0;
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
            RaftBridgeRunOnMain(^{
                __strong typeof(weakSelf) strongSelf = weakSelf;
                if (!strongSelf || !strongSelf.raft_attachmentExportCallback ||
                    ![strongSelf.raft_attachmentExportURL isEqual:targetURL]) {
                    return;
                }
                if (!success || error) {
                    [strongSelf raft_finishAttachmentExport:@{
                        @"status" : @"photos_failed",
                        @"message" : @"Couldn’t save this image to Photos. Try again.",
                        @"permissionStatus" : permissionStatus,
                        @"retryable" : @YES
                    }];
                    return;
                }
                [strongSelf raft_finishAttachmentExport:@{
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
    if (self.raft_attachmentPickerCallback) {
        [self raft_processDocumentPickerURLs:urls];
        return;
    }
    NSURL *savedURL = urls.firstObject;
    [self raft_finishAttachmentExport:@{
        @"status" : @"saved",
        @"message" : @"Saved to Files",
        @"uri" : savedURL.absoluteString ?: @"",
        @"fileName" : self.raft_attachmentExportFileName ?: @"attachment",
        @"cached" : @NO,
        @"sizeBytes" : self.raft_attachmentExportSizeBytes ?: @0
    }];
}

- (void)documentPickerWasCancelled:(UIDocumentPickerViewController *)controller {
    if (self.raft_attachmentPickerCallback) {
        [self raft_finishAttachmentPicker:@{ @"code" : @204, @"bodyText" : @"" }];
        return;
    }
    [self raft_finishAttachmentExport:@{ @"status" : @"cancelled" }];
}

- (void)raft_presentPhotoPicker {
    RaftBridgeRunOnMain(^{
        UIViewController *controller = [self raft_currentViewController];
        if (!controller) {
            [self raft_finishAttachmentPicker:@{
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

- (void)raft_requestAndPresentCamera {
    if (![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
        [self raft_finishAttachmentPicker:@{
            @"code" : @503,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_camera_unavailable",
            @"errorBodyText" : @"Camera is unavailable"
        }];
        return;
    }
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
    if (status == AVAuthorizationStatusAuthorized) {
        [self raft_presentCamera];
        return;
    }
    if (status == AVAuthorizationStatusDenied || status == AVAuthorizationStatusRestricted) {
        [self raft_finishAttachmentPicker:@{
            @"code" : @403,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_camera_permission_denied",
            @"errorBodyText" : @"Camera access is denied"
        }];
        return;
    }
    __weak typeof(self) weakSelf = self;
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
        RaftBridgeRunOnMain(^{
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf || !strongSelf.raft_attachmentPickerCallback) {
                return;
            }
            if (granted) {
                [strongSelf raft_presentCamera];
            } else {
                [strongSelf raft_finishAttachmentPicker:@{
                    @"code" : @403,
                    @"bodyText" : @"",
                    @"errorCode" : @"attachment_camera_permission_denied",
                    @"errorBodyText" : @"Camera access is denied"
                }];
            }
        });
    }];
}

- (void)raft_presentCamera {
    RaftBridgeRunOnMain(^{
        UIViewController *controller = [self raft_currentViewController];
        if (!controller || ![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
            [self raft_finishAttachmentPicker:@{
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
    [self raft_finishAttachmentPicker:@{ @"code" : @204, @"bodyText" : @"" }];
}

- (void)imagePickerController:(UIImagePickerController *)picker
didFinishPickingMediaWithInfo:(NSDictionary<UIImagePickerControllerInfoKey, id> *)info {
    UIImage *image = info[UIImagePickerControllerOriginalImage];
    [picker dismissViewControllerAnimated:YES completion:nil];
    if (!image) {
        [self raft_finishAttachmentPicker:@{
            @"code" : @400,
            @"bodyText" : @"",
            @"errorCode" : @"attachment_read_failed",
            @"errorBodyText" : @"Captured image is unavailable"
        }];
        return;
    }
    BOOL compressImages = self.raft_attachmentPickerCompressImages;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSURL *temporaryURL = [[NSURL fileURLWithPath:NSTemporaryDirectory() isDirectory:YES]
            URLByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingPathExtension:RaftCameraCaptureFileExtension]
                         isDirectory:NO];
        NSError *error = nil;
        BOOL encoded = compressImages
            ? RaftWriteCompressedCameraImage(image, temporaryURL, &error)
            : [UIImageJPEGRepresentation(image, 0.92) writeToURL:temporaryURL
                                                          options:NSDataWritingAtomic
                                                            error:&error];
        if (!encoded) {
            [NSFileManager.defaultManager removeItemAtURL:temporaryURL error:nil];
            [self raft_finishAttachmentPicker:@{
                @"code" : @400,
                @"bodyText" : @"",
                @"errorCode" : compressImages
                    ? @"attachment_image_compression_failed" : @"attachment_read_failed",
                @"errorBodyText" : error.localizedDescription ?: @"Captured image could not be encoded"
            }];
            return;
        }
        NSDictionary *file = nil;
        file = [self raft_cachePickedAttachmentURL:temporaryURL
                                      suggestedName:@"camera.jpg"
                                           mimeType:@"image/jpeg"
                                      compressImages:NO
                                               error:&error];
        [NSFileManager.defaultManager removeItemAtURL:temporaryURL error:nil];
        if (!file) {
            [self raft_finishAttachmentPicker:@{
                @"code" : @400,
                @"bodyText" : @"",
                @"errorCode" : compressImages &&
                    RaftIsThreadAttachmentImageCompressionError(error)
                    ? @"attachment_image_compression_failed" : @"attachment_read_failed",
                @"errorBodyText" : error.localizedDescription ?: @"Captured image could not be cached"
            }];
            return;
        }
        NSMutableDictionary *capturedFile = [file mutableCopy];
        capturedFile[@"source"] = @"platform_camera";
        [self raft_finishAttachmentPickerFiles:@[ capturedFile ] errors:@[]];
    });
}

- (void)raft_presentDocumentPicker {
    RaftBridgeRunOnMain(^{
        UIViewController *controller = [self raft_currentViewController];
        if (!controller) {
            [self raft_finishAttachmentPicker:@{
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
        [self raft_finishAttachmentPicker:@{ @"code" : @204, @"bodyText" : @"" }];
        return;
    }
    [self raft_processPhotoPickerResults:results];
}

- (void)raft_processPhotoPickerResults:(NSArray<PHPickerResult *> *)results {
    BOOL compressImages = self.raft_attachmentPickerCompressImages;
    dispatch_queue_t worker = dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0);
    dispatch_group_t group = dispatch_group_create();
    NSMutableArray *orderedFiles = [NSMutableArray arrayWithCapacity:results.count];
    NSMutableArray<NSDictionary *> *errors = [NSMutableArray array];
    for (NSUInteger index = 0; index < results.count; index++) {
        [orderedFiles addObject:NSNull.null];
    }
    [results enumerateObjectsUsingBlock:^(PHPickerResult *result, NSUInteger index, BOOL *stop) {
        NSItemProvider *provider = result.itemProvider;
        UTType *mediaType = RaftPreferredPickedMediaType(provider);
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
                file = [self raft_cachePickedAttachmentURL:url
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
                    RaftIsThreadAttachmentImageCompressionError(cacheError)
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
        [self raft_finishAttachmentPickerFiles:files errors:errors];
    });
}

- (void)raft_processDocumentPickerURLs:(NSArray<NSURL *> *)urls {
    BOOL compressImages = self.raft_attachmentPickerCompressImages;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        NSMutableArray<NSDictionary *> *files = [NSMutableArray array];
        NSMutableArray<NSDictionary *> *errors = [NSMutableArray array];
        [urls enumerateObjectsUsingBlock:^(NSURL *url, NSUInteger index, BOOL *stop) {
            BOOL securityScoped = [url startAccessingSecurityScopedResource];
            NSError *error = nil;
            NSDictionary *file = [self raft_cachePickedAttachmentURL:url
                                                        suggestedName:url.lastPathComponent
                                                             mimeType:nil
                                                        compressImages:compressImages &&
                                                            RaftAttachmentIsImage(url.lastPathComponent, nil, url)
                                                                error:&error];
            if (securityScoped) {
                [url stopAccessingSecurityScopedResource];
            }
            if (file) {
                [files addObject:file];
            } else {
                NSString *errorCode =
                    RaftIsThreadAttachmentImageCompressionError(error)
                        ? @"attachment_image_compression_failed"
                        : @"attachment_read_failed";
                [errors addObject:@{
                    @"index" : @(index),
                    @"errorCode" : errorCode,
                    @"message" : error.localizedDescription ?: @"Unable to read attachment"
                }];
            }
        }];
        [self raft_finishAttachmentPickerFiles:files errors:errors];
    });
}

- (void)raft_finishAttachmentPickerFiles:(NSArray<NSDictionary *> *)files
                                    errors:(NSArray<NSDictionary *> *)errors {
    if (files.count == 0) {
        BOOL compressionFailed = NO;
        for (NSDictionary *entry in errors) {
            if ([entry[@"errorCode"] isEqualToString:@"attachment_image_compression_failed"]) {
                compressionFailed = YES;
                break;
            }
        }
        [self raft_finishAttachmentPicker:@{
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
    [self raft_finishAttachmentPicker:result];
}

- (NSDictionary *)raft_cachePickedAttachmentURL:(NSURL *)sourceURL
                                    suggestedName:(NSString *)suggestedName
                                         mimeType:(NSString *)mimeType
                                    compressImages:(BOOL)compressImages
                                            error:(NSError **)error {
    NSFileManager *fileManager = NSFileManager.defaultManager;
    NSURL *cacheRoot = [fileManager URLsForDirectory:NSCachesDirectory inDomains:NSUserDomainMask].firstObject;
    if (!cacheRoot) {
        return nil;
    }
    NSURL *directory = [[cacheRoot URLByAppendingPathComponent:RaftPickedAttachmentParentDirectoryName isDirectory:YES]
        URLByAppendingPathComponent:RaftThreadImageCompressionDirectoryName isDirectory:YES];
    if (![fileManager createDirectoryAtURL:directory withIntermediateDirectories:YES attributes:nil error:error]) {
        return nil;
    }
    NSString *displayName = RaftPickedAttachmentDisplayName(suggestedName, sourceURL, mimeType);
    NSString *extension = displayName.pathExtension;
    NSString *cacheName = NSUUID.UUID.UUIDString;
    if (extension.length > 0) {
        cacheName = [cacheName stringByAppendingPathExtension:extension];
    }
    NSURL *targetURL = [directory URLByAppendingPathComponent:cacheName isDirectory:NO];
    BOOL isImage = RaftAttachmentIsImage(displayName, mimeType, sourceURL);
    if (compressImages && isImage) {
        NSString *compressedName = RaftCompressedAttachmentFileName(displayName);
        targetURL = [directory URLByAppendingPathComponent:
            [NSString stringWithFormat:@"%@-%@", NSUUID.UUID.UUIDString, compressedName]
                                                   isDirectory:NO];
        if (!RaftWriteCompressedPickedImage(sourceURL, targetURL, error)) {
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

- (void)raft_finishAttachmentPicker:(NSDictionary *)result {
    RaftBridgeCallback callback = self.raft_attachmentPickerCallback;
    NSString *channelId = self.raft_attachmentPickerChannelId;
    NSArray *files = [result[@"files"] isKindOfClass:NSArray.class] ? result[@"files"] : @[];
    self.raft_attachmentPickerCallback = nil;
    self.raft_attachmentPickerChannelId = nil;
    self.raft_attachmentPickerCompressImages = NO;
    RaftDiagnosticsRecord(
        @"info",
        @"SlockKuiklyAttachment",
        [NSString stringWithFormat:@"picker_finish code=%@ files=%lu channelIdPresent=%@",
                                   result[@"code"] ?: @0,
                                   (unsigned long)files.count,
                                   channelId.length > 0 ? @"true" : @"false"]
    );
    if (callback) {
        RaftBridgeRunOnMain(^{ callback(result ?: @{}); });
    }
}

- (void)raft_finishAttachmentExport:(NSDictionary *)result {
    RaftBridgeCallback callback = self.raft_attachmentExportCallback;
    NSURL *exportURL = self.raft_attachmentExportURL;
    self.raft_attachmentExportCallback = nil;
    self.raft_attachmentExportRequestId = nil;
    self.raft_attachmentExportDownloadId = nil;
    self.raft_attachmentExportOperationId = nil;
    self.raft_attachmentExportURL = nil;
    self.raft_attachmentExportFileName = nil;
    self.raft_attachmentExportSizeBytes = nil;
    self.raft_attachmentExportTrackProgress = NO;
    self.raft_attachmentExportDestination = nil;
    self.raft_attachmentExportMimeType = nil;
    self.raft_attachmentExportPermissionStatus = nil;
    if (exportURL) {
        [NSFileManager.defaultManager removeItemAtURL:[exportURL URLByDeletingLastPathComponent] error:nil];
    }
    if (callback) {
        callback(result);
    }
}

- (NSString *)retireFilePreviewTemporaryFiles:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSString *sessionId = RaftBridgeString(params[@"sessionId"]);
    NSArray *rawRefs = [params[@"fileRefs"] isKindOfClass:NSArray.class] ? params[@"fileRefs"] : @[];
    NSMutableSet<NSString *> *refs = [NSMutableSet set];
    for (id rawRef in rawRefs) {
        if (![rawRef isKindOfClass:NSString.class]) continue;
        NSString *ref = [(NSString *)rawRef
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if (ref.length > 0) [refs addObject:ref];
    }
    SharedThreadComposerTemporaryFileRetirementResult *result =
        RaftIosRetireThreadComposerTemporaryFileRefs(sessionId, refs);
    RaftIosDiagnosticsRecord(
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
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (!callback) return;
    NSDictionary *params = RaftBridgeParams(args);
    NSString *downloadId = RaftBridgeString(params[@"downloadId"]);
    NSString *attemptToken = RaftBridgeString(params[@"attemptToken"]);
    NSString *action = RaftBridgeString(params[@"action"]);
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
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (callback) {
        callback([self raft_unsupportedResultWithCode:@"relational_store_unavailable"
                                               message:@"Relational store is out of scope for iOS P0 host bridge"]);
    }
}

- (void)motionCapability:(NSDictionary *)args {
    RaftBridgeCallback callback = args[RaftBridgeCallbackKey];
    if (callback) {
        callback([self raft_unsupportedResultWithCode:@"motion_capability_unavailable"
                                               message:@"Motion capability is out of scope for iOS P0 host bridge"]);
    }
}

- (void)reportRealTime:(NSDictionary *)args {
    NSDictionary *params = RaftBridgeParams(args);
    NSMutableDictionary *command = [params isKindOfClass:NSDictionary.class] ? [params mutableCopy] : [NSMutableDictionary dictionary];
    [[RaftIosRealtimeHost shared] handleCommand:command];
}

- (void)reportRealtime:(NSDictionary *)args {
    [self reportRealTime:args];
}

#pragma mark - Private

- (NSDictionary *)raft_unsupportedResultWithCode:(NSString *)code message:(NSString *)message {
    return @{
        @"status" : @"error",
        @"code" : code ?: @"unsupported",
        @"message" : message ?: @"Unsupported on iOS P0 host bridge"
    };
}

- (NSDictionary *)raft_developerCapabilityResultWithFeedback:(NSString *)feedback {
    return @{
        @"status" : @"ok",
        @"environmentKey" : @"default",
        @"environmentLabel" : @"Default",
        @"feedback" : feedback.length > 0 ? feedback : @"API environment switching is not wired on iOS P0.",
        @"options" : @[]
    };
}

- (NSDictionary *)raft_notificationPermissionResultForSettings:(UNNotificationSettings *)settings {
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

- (void)raft_getNotificationPermissionStatusWithCallback:(RaftBridgeCallback)callback {
    [[UNUserNotificationCenter currentNotificationCenter]
        getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
            NSDictionary *result = [self raft_notificationPermissionResultForSettings:settings];
            RaftBridgeRunOnMain(^{
                callback(result);
            });
        }];
}

- (void)raft_requestNotificationPermissionWithCallback:(RaftBridgeCallback)callback {
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        if (settings.authorizationStatus != UNAuthorizationStatusNotDetermined &&
            settings.authorizationStatus != UNAuthorizationStatusProvisional) {
            NSDictionary *result = [self raft_notificationPermissionResultForSettings:settings];
            RaftBridgeRunOnMain(^{
                callback(result);
            });
            return;
        }
        UNAuthorizationOptions options = UNAuthorizationOptionAlert | UNAuthorizationOptionBadge | UNAuthorizationOptionSound;
        [center requestAuthorizationWithOptions:options
                              completionHandler:^(BOOL granted, NSError *error) {
            if (error) {
                RaftDiagnosticsRecord(@"warn", @"SlockIosPush", @"notification_permission_request_failed source=settings");
                RaftBridgeRunOnMain(^{
                    callback(@{
                        @"status" : @"error",
                        @"code" : @"permission_request_failed",
                        @"message" : @"The iOS notification permission request failed."
                    });
                });
                return;
            }
            RaftDiagnosticsRecord(
                @"info",
                @"SlockIosPush",
                [NSString stringWithFormat:@"notification_permission_request_finished source=settings granted=%@", granted ? @"true" : @"false"]
            );
            [self raft_getNotificationPermissionStatusWithCallback:callback];
        }];
    }];
}

- (void)raft_openNotificationSettingsWithCallback:(RaftBridgeCallback)callback {
    RaftBridgeRunOnMain(^{
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

- (NSDictionary *)raft_settingsCapabilityResultForAction:(NSString *)action params:(NSDictionary *)params {
    if (action.length == 0) {
        return [self raft_unsupportedResultWithCode:@"settings_capability_unknown"
                                             message:@"Missing settings capability action"];
    }
    if ([action isEqualToString:@"loadAbout"]) {
        NSMutableDictionary *about = [RaftDiagnosticsCurrentEnvironmentAbout() mutableCopy];
        about[@"status"] = @"ok";
        return about;
    }
    if ([action isEqualToString:@"loadDeveloper"]) {
        return [self raft_developerCapabilityResultWithFeedback:nil];
    }
    if ([action isEqualToString:@"loadDiagnostics"]) {
        NSString *recentText = RaftDiagnosticsExportRecentText(RaftDiagnosticsRecentMaxChars);
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
        NSString *exportText = RaftDiagnosticsBuildExportText([self raft_settingsCapabilityResultForAction:@"loadAbout" params:@{}], YES);
        RaftBridgeRunOnMain(^{
            UIPasteboard.generalPasteboard.string = exportText;
        });
        RaftDiagnosticsRecord(@"info", @"SlockDiagnostics", @"shared_copy_diagnostics");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"shareDiagnostics"]) {
        NSString *exportText = RaftDiagnosticsBuildExportText([self raft_settingsCapabilityResultForAction:@"loadAbout" params:@{}], YES);
        [self raft_shareItems:@[ exportText ]];
        RaftDiagnosticsRecord(@"info", @"SlockDiagnostics", @"shared_share_diagnostics");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"clearDiagnostics"]) {
        RaftDiagnosticsClear();
        RaftDiagnosticsRecord(@"info", @"SlockDiagnostics", @"shared_logs_cleared");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"apiEnvironmentChanged"]) {
        // Self-host login: the socket still points at the previous server. Close every connection now, as the
        // Android host does; the cleared session reconnects to the new environment after login.
        [[RaftIosRealtimeHost shared] handleCommand:@{
            @"action" : @"disconnect",
            @"reason" : @"api_environment_changed",
        }];
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"setApiEnvironment"]) {
        return [self raft_unsupportedResultWithCode:@"settings_developer_environment_unsupported"
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
        return [self raft_prepareDiagnosticsFeedbackDraft];
    }
    if ([action isEqualToString:@"sendFeedback"]) {
        [self raft_promptAndSubmitHandsFeedback];
        RaftDiagnosticsRecord(@"info", @"SlockDiagnostics", @"hands_feedback_prompt_opened");
        return @{ @"status" : @"ok" };
    }
    if ([action isEqualToString:@"submitFeedback"]) {
        NSString *description = [RaftBridgeString(params[@"message"])
            stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
        if (description.length == 0) {
            return [self raft_unsupportedResultWithCode:@"settings_feedback_message_required"
                                                 message:@"Feedback message is required"];
        }
        [self raft_submitHandsFeedbackWithDescription:description];
        RaftDiagnosticsRecord(@"info", @"SlockDiagnostics", @"hands_feedback_submit_started");
        return @{ @"status" : @"ok", @"ticketId" : @"", @"shareText" : @"" };
    }
    return [self raft_unsupportedResultWithCode:@"settings_capability_unknown"
                                         message:[NSString stringWithFormat:@"Unsupported settings capability action: %@", action]];
}

- (void)raft_openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData replaceCurrent:(BOOL)replaceCurrent {
    RaftBridgeRunOnMain(^{
        // task #473: a server switch marked `slockDocumentShell` targets a shell
        // window keyed by the route owner triple instead of a page in this
        // scene. On iPad this activates/reuses the matching scene; on a
        // single-scene surface (iPhone) the activation resolves to the current
        // scene, and the shared side then opens the target page via the normal
        // render path once the (re)activated shell loads.
        if (RaftBridgeBool(pageData[@"slockDocumentShell"]) &&
            [RaftIosShellWindowBridge activateShellWithEnvironmentId:RaftBridgeString(pageData[@"slockRouteEnvironmentId"])
                                                         accountId:RaftBridgeString(pageData[@"slockRouteAccountId"])
                                                          serverId:RaftBridgeString(pageData[@"slockRouteServerId"])
                                                            source:@"server_switch"]) {
            return;
        }
        if (RaftBridgeBool(pageData[@"slockDocumentShell"])) {
            RaftDiagnosticsRecord(@"warn", @"RaftIosShellWindow", @"document_shell_fallback source=server_switch");
        }
        UIViewController *controller = [self raft_currentViewController];
        UIViewController *renderViewController =
            [RaftPageControllerFactory controllerForPageName:pageName pageData:pageData ?: @{}];
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
- (void)raft_promptAndSubmitHandsFeedback {
    RaftBridgeRunOnMain(^{
        UIViewController *controller = [self raft_currentViewController];
        if (!controller) {
            return;
        }
        UIAlertController *prompt =
            [UIAlertController alertControllerWithTitle:SharedRaftHostStrings.shared.feedbackPromptTitle
                                                message:SharedRaftHostStrings.shared.feedbackPromptMessage
                                         preferredStyle:UIAlertControllerStyleAlert];
        [prompt addTextFieldWithConfigurationHandler:^(UITextField *textField) {
            textField.placeholder = SharedRaftHostStrings.shared.feedbackPromptPlaceholder;
        }];
        [prompt addAction:[UIAlertAction actionWithTitle:SharedRaftHostStrings.shared.feedbackCancel
                                                   style:UIAlertActionStyleCancel
                                                 handler:nil]];
        __weak UIAlertController *weakPrompt = prompt;
        [prompt addAction:[UIAlertAction actionWithTitle:SharedRaftHostStrings.shared.feedbackSend
                                                   style:UIAlertActionStyleDefault
                                                 handler:^(UIAlertAction *action) {
            NSString *description = [weakPrompt.textFields.firstObject.text
                stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
            if (description.length == 0) {
                return;
            }
            [self raft_submitHandsFeedbackWithDescription:description];
        }]];
        [controller presentViewController:prompt animated:YES completion:nil];
    });
}

- (void)raft_submitHandsFeedbackWithDescription:(NSString *)description {
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        NSDictionary *draft = [self raft_prepareDiagnosticsFeedbackDraft];
        NSArray *draftPaths = [draft[@"attachmentPaths"] isKindOfClass:NSArray.class] ? draft[@"attachmentPaths"] : @[];
        NSString *path = RaftBridgeString(draft[@"filePath"]);
        NSArray<NSString *> *attachments = draftPaths.count > 0 ? draftPaths : (path.length > 0 ? @[ path ] : @[]);
        [Hands submitFeedback:description
                           kind:@"bug"
                attachmentPaths:attachments
                         extras:nil
                     completion:^(NSString *ticketId, NSError *error) {
            RaftBridgeRunOnMain(^{
                UIViewController *controller = [self raft_currentViewController];
                if (error || ticketId.length == 0) {
                    RaftDiagnosticsRecord(@"warn", @"SlockDiagnostics",
                        [NSString stringWithFormat:@"hands_feedback_submit_failed error=%@", error.localizedDescription ?: @"unknown"]);
                    UIAlertController *failed =
                        [UIAlertController alertControllerWithTitle:SharedRaftHostStrings.shared.feedbackFailedTitle
                                                            message:error.localizedDescription ?: SharedRaftHostStrings.shared.feedbackErrorUnknown
                                                     preferredStyle:UIAlertControllerStyleAlert];
                    [failed addAction:[UIAlertAction actionWithTitle:SharedRaftHostStrings.shared.ok style:UIAlertActionStyleDefault handler:nil]];
                    [controller presentViewController:failed animated:YES completion:nil];
                    return;
                }
                NSString *shortId = [ticketId substringToIndex:MIN(ticketId.length, (NSUInteger)8)];
                NSString *environment = RaftDiagnosticsEnvironmentSectionFromExportText(
                    RaftDiagnosticsBuildExportText([self raft_settingsCapabilityResultForAction:@"loadAbout" params:@{}], YES)
                );
                NSString *attachmentBlock = RaftHandsFeedbackAttachmentBlock(attachments);
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
                RaftDiagnosticsRecord(@"info", @"SlockDiagnostics",
                    [NSString stringWithFormat:@"hands_feedback_submitted ticket=%@", shortId]);
                UIAlertController *done =
                    [UIAlertController alertControllerWithTitle:SharedRaftHostStrings.shared.feedbackSentTitle
                                                        message:SharedRaftHostStrings.shared.feedbackCopiedMessage
                                                 preferredStyle:UIAlertControllerStyleAlert];
                [done addAction:[UIAlertAction actionWithTitle:SharedRaftHostStrings.shared.ok style:UIAlertActionStyleDefault handler:nil]];
                [controller presentViewController:done animated:YES completion:nil];
            });
        }];
    });
}

- (NSDictionary *)raft_prepareDiagnosticsFeedbackDraft {
    NSDictionary *about = [self raft_settingsCapabilityResultForAction:@"loadAbout" params:@{}];
    NSDictionary *attachments = RaftDiagnosticsPrepareAttachmentFiles(about, @"settings_feedback", YES);
    if (![RaftBridgeString(attachments[@"status"]) isEqualToString:@"ok"]) {
        return attachments;
    }
    NSString *fileName = RaftBridgeString(attachments[@"fileName"]);
    NSString *message = [NSString stringWithFormat:@"## Raft feedback\n\nDiagnostics attachment: %@\n\n<!-- Describe what happened here. -->", fileName];
    return @{
        @"status" : @"ok",
        @"message" : message,
        @"fileName" : fileName,
        @"mimeType" : RaftBridgeString(attachments[@"mimeType"]),
        @"sizeBytes" : attachments[@"sizeBytes"] ?: @0,
        @"bodyBase64" : RaftBridgeString(attachments[@"bodyBase64"]),
        @"filePath" : RaftBridgeString(attachments[@"filePath"]),
        @"attachmentPaths" : attachments[@"attachmentPaths"] ?: @[]
    };
}

- (void)raft_shareItems:(NSArray *)items {
    if (items.count == 0) {
        return;
    }
    RaftBridgeRunOnMain(^{
        [self raft_presentShareItems:items attempt:1];
    });
}

- (void)raft_presentShareItems:(NSArray *)items attempt:(NSInteger)attempt {
    UIViewController *controller = RaftSharePresenterController(
        self.raft_sharePresenterController, [self raft_currentViewController]);
    if (!controller) {
        NSString *reason = [self raft_shareControllerFailureReason];
        if (attempt < 2) {
            RaftDiagnosticsRecord(
                @"warn", @"SlockShare",
                [NSString stringWithFormat:@"share_present_retry attempt=%ld reason=%@",
                 (long)attempt, reason]);
            dispatch_async(dispatch_get_main_queue(), ^{
                [self raft_presentShareItems:items attempt:attempt + 1];
            });
            return;
        }
        RaftDiagnosticsRecord(
            @"error", @"SlockShare",
            [NSString stringWithFormat:@"share_present_failed attempt=%ld reason=%@",
             (long)attempt, reason]);
        [self raft_showToast:SharedRaftHostStrings.shared.shareOpenFailed];
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

UIViewController *RaftSharePresenterController(UIViewController *capturedPresenter,
                                                UIViewController *frontmostPresenter) {
    return capturedPresenter ?: frontmostPresenter;
}

- (NSString *)raft_shareControllerFailureReason {
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
    return RaftShareControllerFailureReason(
        self.raftHostController.viewIfLoaded.window != nil, hasForegroundActiveScene);
}

NSString *RaftShareControllerFailureReason(BOOL hasRootViewWindow,
                                            BOOL hasForegroundActiveScene) {
    if (!hasRootViewWindow) {
        return @"detached_root";
    }
    if (!hasForegroundActiveScene) {
        return @"scene_inactive";
    }
    return @"no_controller";
}

- (UIViewController *)raft_currentViewController {
    UIViewController *controller = self.raftHostController;
    if (!controller) {
        controller = [self raft_keyWindow].rootViewController;
    }
    return [self raft_topViewControllerFrom:controller] ?: controller;
}

- (UIViewController *)raft_topViewControllerFrom:(UIViewController *)controller {
    if ([controller isKindOfClass:UINavigationController.class]) {
        return [self raft_topViewControllerFrom:((UINavigationController *)controller).topViewController];
    }
    if ([controller isKindOfClass:UITabBarController.class]) {
        return [self raft_topViewControllerFrom:((UITabBarController *)controller).selectedViewController];
    }
    if (controller.presentedViewController) {
        return [self raft_topViewControllerFrom:controller.presentedViewController];
    }
    return controller;
}

- (UIWindow *)raft_keyWindow {
    UIWindow *hostWindow = self.raftHostController.viewIfLoaded.window;
    if (hostWindow) {
        return hostWindow;
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

- (void)raft_showToast:(NSString *)content {
    UIViewController *controller = [self raft_currentViewController];
    UIView *hostView = controller.view ?: [self raft_keyWindow];
    if (!hostView) {
        NSLog(@"[%@][info] toast: %@", RaftBridgeDefaultLogTag, content);
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
    [self raft_destroyFeedbackDiagnosticsUploads];
    NSArray<RaftThreadImageCompressionOperation *> *operations = @[];
    if (_raft_threadImageCompressionOperationsLock) {
        [_raft_threadImageCompressionOperationsLock lock];
        // Publish the terminal lifecycle fence before dropping the map.  Any worker/main block
        // that still owns a weak reference will observe this bit and must not invoke a renderer
        // callback after the module starts teardown.
        _raft_threadImageCompressionDestroyed = YES;
        operations = [_raft_threadImageCompressionOperations.allValues copy];
        [_raft_threadImageCompressionOperations removeAllObjects];
        [_raft_threadImageCompressionOperationsLock unlock];
    }
    for (RaftThreadImageCompressionOperation *operation in operations) {
        // Destruction is the sole force-cancel path: even a DELIVERED operation may still own a
        // tracked output until its callback turn has returned, so teardown must remove it.
        [operation markCancelledForce:YES];
        [operation removeOutputs];
    }
}

@end
