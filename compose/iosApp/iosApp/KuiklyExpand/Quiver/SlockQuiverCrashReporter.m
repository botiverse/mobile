#import "SlockQuiverCrashReporter.h"

#import <execinfo.h>
#import <fcntl.h>
#import <signal.h>
#import <unistd.h>

#import "SlockQuiverFeedbackClient.h"

static NSUInteger const SlockQuiverMaxStoredCrashes = 5;

// Signal handlers cannot allocate; the crash directory path is captured as a
// C string at install time and file names are built with snprintf.
static char gSlockQuiverCrashDir[512] = {0};
static NSUncaughtExceptionHandler *gSlockQuiverPreviousExceptionHandler = NULL;

static int const kSlockQuiverFatalSignals[] = {SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP};
static int const kSlockQuiverFatalSignalCount = sizeof(kSlockQuiverFatalSignals) / sizeof(int);

static NSString *SlockQuiverCrashDirPath(void) {
    NSString *base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES).firstObject;
    return [base stringByAppendingPathComponent:@"quiver/crashes"];
}

static void SlockQuiverWriteMetaFile(NSString *logPath,
                                     NSString *exceptionClass,
                                     NSString *exceptionMessage,
                                     NSString *topFrame,
                                     NSString *reason) {
    NSDictionary *meta = @{
        @"exception_class" : exceptionClass ?: @"IosCrash",
        @"exception_message" : exceptionMessage ?: @"",
        @"top_frame" : topFrame ?: @"",
        @"reason" : reason ?: @"",
        @"crash_at" : @((long long)(NSDate.date.timeIntervalSince1970 * 1000.0)),
    };
    NSData *data = [NSJSONSerialization dataWithJSONObject:meta options:0 error:nil];
    NSString *metaPath = [[logPath stringByDeletingPathExtension] stringByAppendingString:@".meta.json"];
    [data writeToFile:metaPath atomically:YES];
}

/// First stack frame that belongs to the app image rather than system
/// libraries — the same "top frame" notion the server groups crashes by.
static NSString *SlockQuiverTopAppFrame(NSArray<NSString *> *frames) {
    NSString *executable = NSProcessInfo.processInfo.processName;
    for (NSString *frame in frames) {
        if (executable.length > 0 && [frame containsString:executable]) {
            return frame;
        }
    }
    return frames.count > 1 ? frames[1] : (frames.firstObject ?: @"");
}

static void SlockQuiverHandleException(NSException *exception) {
    NSString *dir = SlockQuiverCrashDirPath();
    [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
    long long ts = (long long)(NSDate.date.timeIntervalSince1970 * 1000.0);
    NSString *logPath = [dir stringByAppendingPathComponent:[NSString stringWithFormat:@"crash-%lld.txt", ts]];

    NSArray<NSString *> *frames = exception.callStackSymbols ?: @[];
    NSMutableString *log = [NSMutableString string];
    [log appendFormat:@"Uncaught exception: %@\n", exception.name ?: @"NSException"];
    [log appendFormat:@"Reason: %@\n\n", exception.reason ?: @""];
    [log appendString:@"Stack:\n"];
    [log appendString:[frames componentsJoinedByString:@"\n"]];
    [log appendString:@"\n"];
    [log writeToFile:logPath atomically:YES encoding:NSUTF8StringEncoding error:nil];

    SlockQuiverWriteMetaFile(logPath,
                             exception.name ?: @"NSException",
                             exception.reason ?: @"",
                             SlockQuiverTopAppFrame(frames),
                             @"uncaught_exception");

    if (gSlockQuiverPreviousExceptionHandler) {
        gSlockQuiverPreviousExceptionHandler(exception);
    }
}

/// Async-signal context: only write()/snprintf/backtrace_symbols_fd. The
/// sidecar carries just the signal name; the uploader fills defaults.
static void SlockQuiverHandleSignal(int signalNumber) {
    if (gSlockQuiverCrashDir[0] == '\0') {
        signal(signalNumber, SIG_DFL);
        raise(signalNumber);
        return;
    }
    const char *name = "SIGNAL";
    switch (signalNumber) {
        case SIGABRT: name = "SIGABRT"; break;
        case SIGSEGV: name = "SIGSEGV"; break;
        case SIGBUS: name = "SIGBUS"; break;
        case SIGILL: name = "SIGILL"; break;
        case SIGFPE: name = "SIGFPE"; break;
        case SIGTRAP: name = "SIGTRAP"; break;
        default: break;
    }
    long long ts = (long long)time(NULL) * 1000LL;

    char logPath[640];
    snprintf(logPath, sizeof(logPath), "%s/crash-%lld.txt", gSlockQuiverCrashDir, ts);
    int logFd = open(logPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (logFd >= 0) {
        char header[128];
        int headerLen = snprintf(header, sizeof(header), "Fatal signal: %s (%d)\n\nStack:\n", name, signalNumber);
        write(logFd, header, (size_t)headerLen);
        void *frames[64];
        int frameCount = backtrace(frames, 64);
        backtrace_symbols_fd(frames, frameCount, logFd);
        close(logFd);
    }

    char metaPath[640];
    snprintf(metaPath, sizeof(metaPath), "%s/crash-%lld.meta.json", gSlockQuiverCrashDir, ts);
    int metaFd = open(metaPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (metaFd >= 0) {
        char meta[256];
        int metaLen = snprintf(meta, sizeof(meta),
                               "{\"exception_class\":\"%s\",\"exception_message\":\"fatal signal %d\","
                               "\"reason\":\"signal\",\"crash_at\":%lld}",
                               name, signalNumber, ts);
        write(metaFd, meta, (size_t)metaLen);
        close(metaFd);
    }

    signal(signalNumber, SIG_DFL);
    raise(signalNumber);
}

@implementation SlockQuiverCrashReporter

+ (void)install {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSString *dir = SlockQuiverCrashDirPath();
        [[NSFileManager defaultManager] createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
        [dir getCString:gSlockQuiverCrashDir maxLength:sizeof(gSlockQuiverCrashDir) encoding:NSUTF8StringEncoding];

        gSlockQuiverPreviousExceptionHandler = NSGetUncaughtExceptionHandler();
        NSSetUncaughtExceptionHandler(&SlockQuiverHandleException);
        for (int i = 0; i < kSlockQuiverFatalSignalCount; i++) {
            signal(kSlockQuiverFatalSignals[i], &SlockQuiverHandleSignal);
        }
        [self enforceRetention];
    });
}

+ (void)enforceRetention {
    NSString *dir = SlockQuiverCrashDirPath();
    NSArray<NSString *> *entries = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil];
    NSArray<NSString *> *logs = [[entries filteredArrayUsingPredicate:
        [NSPredicate predicateWithBlock:^BOOL(NSString *name, NSDictionary *bindings) {
            return [name hasPrefix:@"crash-"] && [name hasSuffix:@".txt"];
        }]] sortedArrayUsingSelector:@selector(compare:)];
    if (logs.count < SlockQuiverMaxStoredCrashes) {
        return;
    }
    NSUInteger excess = logs.count - (SlockQuiverMaxStoredCrashes - 1);
    for (NSUInteger i = 0; i < excess; i++) {
        [self deletePairForLogPath:[dir stringByAppendingPathComponent:logs[i]]];
    }
}

+ (void)deletePairForLogPath:(NSString *)logPath {
    NSFileManager *fm = NSFileManager.defaultManager;
    [fm removeItemAtPath:logPath error:nil];
    NSString *metaPath = [[logPath stringByDeletingPathExtension] stringByAppendingString:@".meta.json"];
    [fm removeItemAtPath:metaPath error:nil];
}

+ (void)uploadPendingAfterDelay:(NSTimeInterval)delay {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay * NSEC_PER_SEC)),
                   dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        [self uploadPendingNow];
    });
}

+ (void)uploadPendingNow {
    NSString *dir = SlockQuiverCrashDirPath();
    NSArray<NSString *> *entries = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:dir error:nil];
    NSArray<NSString *> *sidecars = [[entries filteredArrayUsingPredicate:
        [NSPredicate predicateWithBlock:^BOOL(NSString *name, NSDictionary *bindings) {
            return [name hasSuffix:@".meta.json"];
        }]] sortedArrayUsingSelector:@selector(compare:)];

    for (NSString *sidecarName in sidecars) {
        NSString *sidecarPath = [dir stringByAppendingPathComponent:sidecarName];
        NSString *logName = [[sidecarName stringByReplacingOccurrencesOfString:@".meta.json"
                                                                    withString:@""] stringByAppendingString:@".txt"];
        NSString *logPath = [dir stringByAppendingPathComponent:logName];
        if (![NSFileManager.defaultManager fileExistsAtPath:logPath]) {
            [NSFileManager.defaultManager removeItemAtPath:sidecarPath error:nil];
            continue;
        }

        NSData *metaData = [NSData dataWithContentsOfFile:sidecarPath];
        NSDictionary *meta = metaData
            ? ([NSJSONSerialization JSONObjectWithData:metaData options:0 error:nil] ?: @{})
            : @{};
        NSString *exceptionClass = [meta[@"exception_class"] isKindOfClass:NSString.class]
            ? meta[@"exception_class"] : @"IosCrash";
        NSString *exceptionMessage = [meta[@"exception_message"] isKindOfClass:NSString.class]
            ? meta[@"exception_message"] : @"";
        NSString *topFrame = [meta[@"top_frame"] isKindOfClass:NSString.class] ? meta[@"top_frame"] : @"";

        NSMutableString *message = [NSMutableString stringWithFormat:@"Crash: %@", exceptionClass];
        if (exceptionMessage.length > 0) {
            [message appendFormat:@": %@", [exceptionMessage substringToIndex:MIN(exceptionMessage.length, (NSUInteger)200)]];
        }
        if (topFrame.length > 0) {
            [message appendFormat:@"\nat %@", topFrame];
        }

        NSDictionary<NSString *, NSString *> *extras = @{
            @"crash_exception_class" : exceptionClass,
            @"crash_top_frame" : topFrame,
            @"crash_reason" : [meta[@"reason"] isKindOfClass:NSString.class] ? meta[@"reason"] : @"",
            @"crash_at" : [NSString stringWithFormat:@"%@", meta[@"crash_at"] ?: @0],
        };

        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        [SlockQuiverFeedbackClient submitWithMessage:message
                                                kind:@"crash"
                                     attachmentPaths:@[ logPath ]
                                              extras:extras
                                          completion:^(NSString *ticketId, NSError *error) {
            if (ticketId.length > 0 && !error) {
                [self deletePairForLogPath:logPath];
                NSLog(@"[SlockQuiver] uploaded crash %@ as %@", logName, [ticketId substringToIndex:MIN(ticketId.length, (NSUInteger)8)]);
            } else {
                NSLog(@"[SlockQuiver] crash upload failed for %@: %@", logName, error.localizedDescription ?: @"unknown");
            }
            dispatch_semaphore_signal(done);
        }];
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(60 * NSEC_PER_SEC)));
    }
}

@end
