#import <Foundation/Foundation.h>
#import "../iosApp/KuiklyExpand/Modules/RaftDiagnosticsEnvironmentRows.h"

static void Require(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"FAIL: %@", message);
        exit(1);
    }
}

static NSArray<NSDictionary *> *SampleRows(void) {
    return @[
        @{ @"key": @"appName", @"label": @"App" },
        @{ @"key": @"versionLabel", @"label": @"Version" },
        @{ @"key": @"selectedServerLabel", @"label": @"Selected server" },
        @{ @"key": @"socketStatusLabel", @"label": @"Socket" }
    ];
}

int main(void) {
    @autoreleasepool {
        NSDictionary *about = @{
            @"appName": @"Raft",
            @"versionLabel": @"1.0.0+abcdef0",
            // selectedServerLabel intentionally missing (absent field)
            @"socketStatusLabel": @"  ", // whitespace-only value trims to empty
        };

        // omitEmptyRows = YES: empty/absent rows are skipped ENTIRELY —
        // no "--" placeholder and no bare "Label: " shell line.
        NSString *omitted = RaftDiagnosticsEnvironmentRowsText(about, SampleRows(), YES);
        Require([omitted containsString:@"App: Raft\n"], @"non-empty row must be kept");
        Require([omitted containsString:@"Version: 1.0.0+abcdef0\n"], @"non-empty row must be kept verbatim");
        Require(![omitted containsString:@"Selected server"], @"absent field must not produce any line");
        Require(![omitted containsString:@"Socket"], @"whitespace-only value must not produce any line");
        Require(![omitted containsString:@"--"], @"placeholder must never appear when omitting");

        // omitEmptyRows = NO: legacy shape preserved for the diagnostics
        // attachment — empty/absent values render as "Label: --".
        NSString *kept = RaftDiagnosticsEnvironmentRowsText(about, SampleRows(), NO);
        Require([kept containsString:@"App: Raft\n"], @"non-empty row must be kept");
        Require([kept containsString:@"Selected server: --\n"], @"absent field must render placeholder when not omitting");
        Require([kept containsString:@"Socket: --\n"], @"whitespace-only value must render placeholder when not omitting");

        // Non-string values (NSNumber) are stringified via stringValue.
        NSDictionary *numericAbout = @{ @"appName": @42 };
        NSString *numericOmitted = RaftDiagnosticsEnvironmentRowsText(numericAbout, SampleRows(), YES);
        Require([numericOmitted containsString:@"App: 42\n"], @"NSNumber must be stringified");
        NSString *numericKept = RaftDiagnosticsEnvironmentRowsText(numericAbout, SampleRows(), NO);
        Require([numericKept containsString:@"App: 42\n"], @"NSNumber must be stringified");

        NSLog(@"PASS: diagnostics environment rows omit/keep semantics");
    }
    return 0;
}
