#import <Foundation/Foundation.h>
#import "SlockAdaptiveNavigationSession.h"
#include <assert.h>

static NSDictionary *context(NSInteger revision) {
    return @{ @"sourceControllerToken": @"source", @"targetControllerToken": @"shell",
              @"sourceRevision": @(revision), @"geometry": @"wide" };
}
static void expect(NSDictionary *result, NSString *status) {
    assert([result[@"status"] isEqual:status]);
}
int main(void) {
    @autoreleasepool {
        __block NSInteger pops = 0;
        BOOL (^pop)(void) = ^{ pops++; return YES; };
        SlockAdaptiveNavigationSession *s = [SlockAdaptiveNavigationSession new];
        expect([s prepare:@"" context:context(1) now:0], @"rejected");
        expect([s prepare:@"a" context:@{} now:0], @"rejected");
        NSDictionary *p = [s prepare:@"a" context:context(1) now:0];
        expect(p, @"prepared");
        NSDictionary *again = [s prepare:@"a" context:context(1) now:1];
        assert([p[@"token"] isEqual:again[@"token"]]);
        expect([s prepare:@"b" context:context(1) now:2], @"rejected");
        expect([s commit:@"a" token:@"stale" context:context(1) now:2 perform:pop], @"rejected");
        assert(pops == 0);
        expect([s commit:@"a" token:p[@"token"] context:context(1) now:2 perform:pop], @"committed");
        expect([s commit:@"a" token:p[@"token"] context:@{} now:200 perform:pop], @"committed");
        assert(pops == 1); // A retry after source removal cannot pop the newly exposed shell.
        expect([s prepare:@"b" context:context(1) now:3], @"rejected");
        expect([s cancel:@"a" token:p[@"token"]], @"committed");

        // Every native identity/geometry fence must prevent the side effect.
        for (NSString *key in context(1)) {
            s = [SlockAdaptiveNavigationSession new];
            p = [s prepare:@"a" context:context(1) now:0];
            NSMutableDictionary *changed = [context(1) mutableCopy];
            changed[key] = @"changed";
            expect([s commit:@"a" token:p[@"token"] context:changed now:1 perform:pop], @"rejected");
            // Restoring the original context cannot revive the rejected request.
            expect([s commit:@"a" token:p[@"token"] context:context(1) now:2 perform:pop], @"rejected");
        }
        assert(pops == 1);
        s = [SlockAdaptiveNavigationSession new];
        p = [s prepare:@"expired" context:context(1) now:10];
        expect([s commit:@"expired" token:p[@"token"] context:context(1) now:40 perform:pop], @"rejected");
        assert(pops == 1);
        s = [SlockAdaptiveNavigationSession new];
        p = [s prepare:@"cancel" context:context(1) now:0];
        expect([s cancel:@"cancel" token:@"wrong"], @"rejected");
        expect([s cancel:@"cancel" token:p[@"token"]], @"cancelled");
        expect([s commit:@"cancel" token:p[@"token"] context:context(1) now:1 perform:pop], @"cancelled");
        NSDictionary *next = [s prepare:@"next" context:context(2) now:2];
        expect(next, @"prepared");
        expect([s commit:@"cancel" token:p[@"token"] context:context(2) now:3 perform:pop], @"rejected");
        assert(pops == 1);
        __block NSInteger failedAttempts = 0;
        BOOL (^failedPop)(void) = ^{ failedAttempts++; return NO; };
        expect([s commit:@"next" token:next[@"token"] context:context(2) now:3 perform:failedPop], @"rejected");
        expect([s commit:@"next" token:next[@"token"] context:context(2) now:4 perform:failedPop], @"rejected");
        assert(failedAttempts == 1);
        s = [SlockAdaptiveNavigationSession new];
        p = [s prepare:@"reentrant" context:context(1) now:0];
        expect([s commit:@"reentrant" token:p[@"token"] context:context(1) now:1 perform:^{
            expect([s commit:@"reentrant" token:p[@"token"] context:context(1) now:1 perform:pop], @"rejected");
            expect([s cancel:@"reentrant" token:p[@"token"]], @"rejected");
            return YES;
        }], @"committed");
        assert(pops == 1);
        puts("PASS: production adaptive navigation session — identity, expiry, cancel, failed/duplicate/reentrant commit");
    }
}
