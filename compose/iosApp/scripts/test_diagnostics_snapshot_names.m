#import <Foundation/Foundation.h>
#import "../iosApp/Host/RaftDiagnosticsPathAdmission.h"

static void Expect(NSString *actual, NSString *expected) {
    if (![actual isEqualToString:expected]) {
        NSLog(@"FAIL: got %@ expected %@", actual, expected);
        exit(1);
    }
}

int main(void) {
    @autoreleasepool {
        // 2026-10-24T00:30:12Z
        NSString *a = RaftDiagnosticsSnapshotLogFileName(@"launch-1792801812000-ABCD", @"raft-diagnostics.jsonl.1");
        Expect(a, @"launch-20261024T003012Z-raft-diagnostics.jsonl.1");
        NSString *b = RaftDiagnosticsSnapshotLogFileName(@"launch-1792888212000-x", @"raft-diagnostics.jsonl");
        Expect(b, @"launch-20261025T003012Z-raft-diagnostics.jsonl");
        if ([a compare:b] != NSOrderedAscending) { NSLog(@"FAIL: order"); exit(1); }
        Expect(RaftDiagnosticsSnapshotLogFileName(@"flat", @"raft-diagnostics.jsonl"), @"raft-diagnostics.jsonl");
        Expect(RaftDiagnosticsSnapshotLogFileName(@"launch-we ird/x", @"raft-diagnostics.jsonl"),
               @"launch-we_ird_x-raft-diagnostics.jsonl");
        // still recognised by the prefix-based log-file check family: ends with the bare name + rotation suffix
        if (![a containsString:@"raft-diagnostics.jsonl"]) { NSLog(@"FAIL: contains"); exit(1); }
    }
    NSLog(@"diagnostics snapshot names OK");
    return 0;
}
