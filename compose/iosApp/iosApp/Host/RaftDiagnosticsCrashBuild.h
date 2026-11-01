#import <Foundation/Foundation.h>

// Crash-time build attribution for Hands crash diagnostics.
//
// The Hands iOS SDK (ios-v0.2.1) uploads a pending crash on the NEXT launch and
// stamps the ticket's version_name / version_code / commit from that uploading
// launch's Info.plist; its crash sidecar records no build. After an update the
// crash of build N therefore arrives tagged as build N+1, and the app-owned
// diagnostics summary (built by the same upload-time provider) said so too.
//
// The app keeps a small launch history instead: one record per build change,
// written at launch. The upload-time provider resolves the build that was
// running at the crash's crash_at from it. Header-only so the CI script test
// (scripts/test_diagnostics_crash_build.m) compiles the same code.

static NSString *const RaftCrashBuildLaunchAtKey = @"launchAtMillis";
static NSString *const RaftCrashBuildVersionKey = @"version";
static NSString *const RaftCrashBuildBuildKey = @"build";
static NSString *const RaftCrashBuildSourceKey = @"source";
static const NSUInteger RaftCrashBuildHistoryMaxRecords = 16;

static inline NSString *RaftCrashBuildString(id value) {
    return [value isKindOfClass:NSString.class] ? (NSString *)value : @"";
}

static inline BOOL RaftCrashBuildRecordIsValid(id record) {
    if (![record isKindOfClass:NSDictionary.class]) {
        return NO;
    }
    id launchAt = ((NSDictionary *)record)[RaftCrashBuildLaunchAtKey];
    return [launchAt isKindOfClass:NSNumber.class] && [launchAt longLongValue] > 0;
}

static inline BOOL RaftCrashBuildSameBuild(NSDictionary *a, NSDictionary *b) {
    return [RaftCrashBuildString(a[RaftCrashBuildVersionKey]) isEqualToString:RaftCrashBuildString(b[RaftCrashBuildVersionKey])] &&
        [RaftCrashBuildString(a[RaftCrashBuildBuildKey]) isEqualToString:RaftCrashBuildString(b[RaftCrashBuildBuildKey])] &&
        [RaftCrashBuildString(a[RaftCrashBuildSourceKey]) isEqualToString:RaftCrashBuildString(b[RaftCrashBuildSourceKey])];
}

static inline NSDictionary *RaftCrashBuildRecord(long long launchAtMillis,
                                                 NSString *version,
                                                 NSString *build,
                                                 NSString *source) {
    return @{
        RaftCrashBuildLaunchAtKey : @(launchAtMillis),
        RaftCrashBuildVersionKey : version ?: @"",
        RaftCrashBuildBuildKey : build ?: @"",
        RaftCrashBuildSourceKey : source ?: @"",
    };
}

/// Returns the history with `record` appended, or nil when nothing changed
/// (the newest valid record is already this build), so callers write only on a
/// build change. Invalid entries are dropped; only the newest `maxRecords` stay.
static inline NSArray<NSDictionary *> *RaftCrashBuildHistoryAppending(NSArray *history,
                                                                      NSDictionary *record,
                                                                      NSUInteger maxRecords) {
    NSArray *input = [history isKindOfClass:NSArray.class] ? history : @[];
    NSMutableArray<NSDictionary *> *valid = [NSMutableArray array];
    for (id item in input) {
        if (RaftCrashBuildRecordIsValid(item)) {
            [valid addObject:item];
        }
    }
    NSDictionary *last = valid.lastObject;
    BOOL sameBuild = last != nil && RaftCrashBuildSameBuild(last, record);
    if (sameBuild && valid.count == input.count && valid.count <= MAX(maxRecords, (NSUInteger)1)) {
        return nil;
    }
    if (!sameBuild) {
        [valid addObject:record];
    }
    NSUInteger limit = MAX(maxRecords, (NSUInteger)1);
    if (valid.count > limit) {
        [valid removeObjectsInRange:NSMakeRange(0, valid.count - limit)];
    }
    return valid;
}

/// The build that was running at `crashAtMillis`: the newest record whose launch
/// is not after the crash. Launch times are floored to whole seconds because the
/// SDK's signal handler records crash_at as time(NULL) * 1000. Returns nil when
/// the crash is unknown (crashAtMillis <= 0) or predates every record (a crash
/// of a build that ran before this history existed); never guesses the
/// uploading build.
static inline NSDictionary *RaftCrashBuildForCrashAt(NSArray *history, long long crashAtMillis) {
    if (crashAtMillis <= 0 || ![history isKindOfClass:NSArray.class]) {
        return nil;
    }
    NSDictionary *best = nil;
    long long bestLaunchAt = 0;
    for (id item in history) {
        if (!RaftCrashBuildRecordIsValid(item)) {
            continue;
        }
        long long launchAt = [((NSDictionary *)item)[RaftCrashBuildLaunchAtKey] longLongValue];
        long long launchSecond = launchAt - (launchAt % 1000);
        if (launchSecond <= crashAtMillis && (!best || launchAt >= bestLaunchAt)) {
            best = item;
            bestLaunchAt = launchAt;
        }
    }
    return best;
}

/// Rewrites the crash diagnostics "about" fields so Version / Version code /
/// Build / Source name the crashed build, and keeps the uploading build in
/// separate Upload* fields. When the crash build is unknown the build fields
/// stay the uploading build and `crashBuildLabel` says so explicitly.
static inline NSDictionary *RaftCrashBuildApplyToAbout(NSDictionary *about, NSDictionary *crashBuild) {
    NSMutableDictionary *result = [about mutableCopy] ?: [NSMutableDictionary dictionary];
    NSString *uploadVersion = RaftCrashBuildString(about[@"versionLabel"]);
    NSString *uploadBuild = RaftCrashBuildString(about[@"buildLabel"]);
    NSString *uploadSource = RaftCrashBuildString(about[@"sourceLabel"]);
    result[@"uploadVersionLabel"] = uploadVersion;
    result[@"uploadBuildLabel"] = uploadBuild;
    result[@"uploadSourceLabel"] = uploadSource;
    if (!crashBuild) {
        result[@"crashBuildLabel"] = @"unknown (Version/Build/Source below are the uploading build)";
        return result;
    }
    NSString *version = RaftCrashBuildString(crashBuild[RaftCrashBuildVersionKey]);
    NSString *build = RaftCrashBuildString(crashBuild[RaftCrashBuildBuildKey]);
    NSString *source = RaftCrashBuildString(crashBuild[RaftCrashBuildSourceKey]);
    result[@"versionLabel"] = version;
    result[@"versionCodeLabel"] = build;
    result[@"buildLabel"] = build;
    result[@"sourceLabel"] = source.length > 0 ? source : @"unknown";
    result[@"gitCommit"] = source;
    result[@"crashBuildLabel"] = [NSString stringWithFormat:@"%@ (%@) %@ from launch history",
        version.length > 0 ? version : @"--",
        build.length > 0 ? build : @"--",
        source.length > 0 ? source : @"unknown"];
    return result;
}
