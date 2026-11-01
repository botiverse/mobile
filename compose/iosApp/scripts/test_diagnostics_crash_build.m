#import <Foundation/Foundation.h>
#import "../iosApp/Host/RaftDiagnosticsCrashBuild.h"

static void Require(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"FAIL: %@", message);
        exit(1);
    }
}

int main(void) {
    @autoreleasepool {
        // 1.17.1 (11701001) first launched at t=1_000_500, updated to 1.17.1
        // (11701003) first launched at t=5_000_250.
        NSDictionary *oldBuild = RaftCrashBuildRecord(1000500, @"1.17.1", @"11701001", @"aaaaaaa");
        NSDictionary *newBuild = RaftCrashBuildRecord(5000250, @"1.17.1", @"11701003", @"bbbbbbb");

        NSArray *history = RaftCrashBuildHistoryAppending(@[], oldBuild, 16);
        Require(history.count == 1, @"first launch must be recorded");
        Require(RaftCrashBuildHistoryAppending(history, RaftCrashBuildRecord(2000000, @"1.17.1", @"11701001", @"aaaaaaa"), 16) == nil,
                @"a relaunch of the same build must not rewrite the history");
        history = RaftCrashBuildHistoryAppending(history, newBuild, 16);
        Require(history.count == 2, @"a build change must append a record");
        Require(RaftCrashBuildHistoryAppending(history, RaftCrashBuildRecord(6000000, @"1.17.1", @"11701003", @"ccccccc"), 16).count == 3,
                @"same version/build from another source commit is a different build");

        // The crash of the old build is uploaded by the new build's launch: the
        // resolved build is the old one, not the uploader.
        NSDictionary *crashed = RaftCrashBuildForCrashAt(history, 4000123);
        Require([crashed[RaftCrashBuildBuildKey] isEqualToString:@"11701001"], @"crash before the update must resolve the crashed build");
        crashed = RaftCrashBuildForCrashAt(history, 5100000);
        Require([crashed[RaftCrashBuildBuildKey] isEqualToString:@"11701003"], @"crash after the update must resolve the new build");

        // The signal handler records crash_at as time(NULL) * 1000 (whole
        // seconds); a crash in the launch's own second still resolves it.
        crashed = RaftCrashBuildForCrashAt(history, 5000000);
        Require([crashed[RaftCrashBuildBuildKey] isEqualToString:@"11701003"], @"second-truncated crash_at must match its launch");

        // Unknown: a crash older than every record, or no crash time.
        Require(RaftCrashBuildForCrashAt(history, 999000) == nil, @"crash before the history must be unknown, not the uploader");
        Require(RaftCrashBuildForCrashAt(history, 0) == nil, @"missing crash_at must be unknown");
        Require(RaftCrashBuildForCrashAt(@[ @"junk", @{ @"launchAtMillis" : @"x" } ], 4000000) == nil, @"invalid records must be ignored");

        // Downgrade then re-upgrade keeps attributing by launch order.
        NSArray *flip = RaftCrashBuildHistoryAppending(history, RaftCrashBuildRecord(7000000, @"1.17.1", @"11701001", @"aaaaaaa"), 16);
        Require([RaftCrashBuildForCrashAt(flip, 7500000)[RaftCrashBuildBuildKey] isEqualToString:@"11701001"], @"reinstalled older build must resolve");

        // Retention keeps only the newest records and drops invalid entries.
        NSMutableArray *many = [NSMutableArray arrayWithObject:@"junk"];
        for (int i = 1; i <= 20; i++) {
            [many addObject:RaftCrashBuildRecord(i * 1000, @"1.0", [NSString stringWithFormat:@"%d", i], @"s")];
        }
        NSArray *trimmed = RaftCrashBuildHistoryAppending(many, RaftCrashBuildRecord(21000, @"1.0", @"20", @"s"), 16);
        Require(trimmed.count == 16, @"history must be trimmed to the limit");
        Require([trimmed.firstObject[RaftCrashBuildBuildKey] isEqualToString:@"5"], @"oldest records are dropped first");

        // About rewrite: crash build replaces the build rows, uploader kept apart.
        NSDictionary *about = @{ @"versionLabel" : @"1.17.1", @"versionCodeLabel" : @"11701003", @"buildLabel" : @"11701003", @"sourceLabel" : @"bbbbbbb" };
        NSDictionary *resolved = RaftCrashBuildApplyToAbout(about, oldBuild);
        Require([resolved[@"buildLabel"] isEqualToString:@"11701001"], @"Build must be the crashed build");
        Require([resolved[@"versionCodeLabel"] isEqualToString:@"11701001"], @"Version code must be the crashed build");
        Require([resolved[@"sourceLabel"] isEqualToString:@"aaaaaaa"], @"Source must be the crashed commit");
        Require([resolved[@"uploadBuildLabel"] isEqualToString:@"11701003"], @"uploading build must be kept separately");
        Require([resolved[@"crashBuildLabel"] containsString:@"launch history"], @"resolved crash build must say where it came from");
        NSDictionary *unknown = RaftCrashBuildApplyToAbout(about, nil);
        Require([unknown[@"buildLabel"] isEqualToString:@"11701003"], @"unknown crash build keeps the uploader rows");
        Require([unknown[@"crashBuildLabel"] hasPrefix:@"unknown"], @"unknown crash build must be labelled unknown");

        NSLog(@"PASS: diagnostics crash build attribution");
    }
    return 0;
}
