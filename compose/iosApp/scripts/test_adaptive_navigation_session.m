#import <Foundation/Foundation.h>
#import "RaftAdaptiveNavigationSession.h"
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
        NSObject *editor = [NSObject new];
        NSDictionary *editorState = @{ @"text": @"draft", @"selectionStart": @5, @"selectionEnd": @5 };
        __block NSInteger pops = 0;
        BOOL (^pop)(void) = ^{ pops++; return YES; };
        RaftAdaptiveNavigationSession *s = [RaftAdaptiveNavigationSession new];
        expect([s prepare:@"" context:context(1) editor:editor editorState:editorState now:0], @"rejected");
        expect([s prepare:@"a" context:@{} editor:editor editorState:editorState now:0], @"rejected");
        NSDictionary *p = [s prepare:@"a" context:context(1) editor:editor editorState:editorState now:0];
        expect(p, @"prepared");
        NSDictionary *again = [s prepare:@"a" context:context(1) editor:editor editorState:editorState now:1];
        assert([p[@"token"] isEqual:again[@"token"]]);
        expect([s prepare:@"b" context:context(1) editor:editor editorState:editorState now:2], @"rejected");
        expect([s commit:@"a" token:@"stale" context:context(1) editor:editor editorState:editorState now:2 perform:pop], @"rejected");
        assert(pops == 0);
        expect([s commit:@"a" token:p[@"token"] context:context(1) editor:editor editorState:editorState now:2 perform:pop], @"committed");
        expect([s commit:@"a" token:p[@"token"] context:@{} editor:editor editorState:editorState now:200 perform:pop], @"committed");
        assert(pops == 1); // A retry after source removal cannot pop the newly exposed shell.
        expect([s prepare:@"b" context:context(1) editor:editor editorState:editorState now:3], @"rejected");
        expect([s cancel:@"a" token:p[@"token"]], @"committed");

        // Every native identity/geometry fence must prevent the side effect.
        for (NSString *key in context(1)) {
            s = [RaftAdaptiveNavigationSession new];
            p = [s prepare:@"a" context:context(1) editor:editor editorState:editorState now:0];
            NSMutableDictionary *changed = [context(1) mutableCopy];
            changed[key] = @"changed";
            expect([s commit:@"a" token:p[@"token"] context:changed editor:editor editorState:editorState now:1 perform:pop], @"rejected");
            // Restoring the original context cannot revive the rejected request.
            expect([s commit:@"a" token:p[@"token"] context:context(1) editor:editor editorState:editorState now:2 perform:pop], @"rejected");
        }
        assert(pops == 1);
        s = [RaftAdaptiveNavigationSession new];
        p = [s prepare:@"expired" context:context(1) editor:editor editorState:editorState now:10];
        expect([s commit:@"expired" token:p[@"token"] context:context(1) editor:editor editorState:editorState now:40 perform:pop], @"rejected");
        assert(pops == 1);
        s = [RaftAdaptiveNavigationSession new];
        p = [s prepare:@"cancel" context:context(1) editor:editor editorState:editorState now:0];
        expect([s cancel:@"cancel" token:@"wrong"], @"rejected");
        expect([s cancel:@"cancel" token:p[@"token"]], @"cancelled");
        expect([s commit:@"cancel" token:p[@"token"] context:context(1) editor:editor editorState:editorState now:1 perform:pop], @"cancelled");
        NSDictionary *next = [s prepare:@"next" context:context(2) editor:editor editorState:editorState now:2];
        expect(next, @"prepared");
        expect([s commit:@"cancel" token:p[@"token"] context:context(2) editor:editor editorState:editorState now:3 perform:pop], @"rejected");
        assert(pops == 1);
        __block NSInteger failedAttempts = 0;
        BOOL (^failedPop)(void) = ^{ failedAttempts++; return NO; };
        expect([s commit:@"next" token:next[@"token"] context:context(2) editor:editor editorState:editorState now:3 perform:failedPop], @"rejected");
        expect([s commit:@"next" token:next[@"token"] context:context(2) editor:editor editorState:editorState now:4 perform:failedPop], @"rejected");
        assert(failedAttempts == 1);
        s = [RaftAdaptiveNavigationSession new];
        p = [s prepare:@"reentrant" context:context(1) editor:editor editorState:editorState now:0];
        expect([s commit:@"reentrant" token:p[@"token"] context:context(1) editor:editor editorState:editorState now:1 perform:^{
            expect([s commit:@"reentrant" token:p[@"token"] context:context(1) editor:editor editorState:editorState now:1 perform:pop], @"rejected");
            expect([s cancel:@"reentrant" token:p[@"token"]], @"rejected");
            return YES;
        }], @"committed");
        assert(pops == 1);
        // A draft edited after shared's final read must never reach the pop callback.
        for (NSString *field in editorState) {
            s = [RaftAdaptiveNavigationSession new];
            p = [s prepare:@"edit" context:context(1) editor:editor editorState:editorState now:0];
            NSMutableDictionary *changed = [editorState mutableCopy];
            changed[field] = [field isEqual:@"text"] ? @"new input" : @0;
            NSDictionary *rejected = [s commit:@"edit" token:p[@"token"] context:context(1)
                                          editor:editor editorState:changed now:1 perform:pop];
            expect(rejected, @"rejected");
            assert([rejected[@"reason"] isEqual:@"editor_drift"]);
            // Restoring the old value cannot revive this rejected nonce.
            expect([s commit:@"edit" token:p[@"token"] context:context(1)
                      editor:editor editorState:editorState now:2 perform:pop], @"rejected");
        }
        assert(pops == 1);
        // Equal content in a replacement editor is not the editor that was prepared.
        s = [RaftAdaptiveNavigationSession new];
        p = [s prepare:@"replace" context:context(1) editor:editor editorState:editorState now:0];
        NSObject *replacement = [NSObject new];
        NSDictionary *rejected = [s commit:@"replace" token:p[@"token"] context:context(1)
                                       editor:replacement editorState:editorState now:1 perform:pop];
        assert([rejected[@"reason"] isEqual:@"editor_drift"]);
        // Missing/composing snapshots and released editors fail closed, including prepare.
        s = [RaftAdaptiveNavigationSession new];
        expect([s prepare:@"missing" context:context(1) editor:editor editorState:nil now:0], @"rejected");
        p = [s prepare:@"composition" context:context(1) editor:editor editorState:editorState now:0];
        rejected = [s commit:@"composition" token:p[@"token"] context:context(1)
                       editor:editor editorState:nil now:1 perform:pop];
        assert([rejected[@"reason"] isEqual:@"editor_drift"]);
        s = [RaftAdaptiveNavigationSession new];
        @autoreleasepool {
            NSObject *temporaryEditor = [NSObject new];
            p = [s prepare:@"released" context:context(1) editor:temporaryEditor editorState:editorState now:0];
        }
        rejected = [s commit:@"released" token:p[@"token"] context:context(1)
                       editor:nil editorState:editorState now:1 perform:pop];
        assert([rejected[@"reason"] isEqual:@"editor_drift"]);
        // Repeated prepare cannot silently rebind the original token to a different editor.
        s = [RaftAdaptiveNavigationSession new];
        p = [s prepare:@"repeat" context:context(1) editor:editor editorState:editorState now:0];
        rejected = [s prepare:@"repeat" context:context(1) editor:replacement editorState:editorState now:1];
        assert([rejected[@"reason"] isEqual:@"editor_drift"]);
        expect([s commit:@"repeat" token:p[@"token"] context:context(1)
                  editor:editor editorState:editorState now:2 perform:pop], @"rejected");
        assert(pops == 1);
        puts("PASS: production editor guard — post-read text/selection drift, replacement, composition/unavailable, released editor, repeated prepare");
        puts("PASS: production adaptive navigation session — identity, expiry, cancel, failed/duplicate/reentrant commit");
    }
}
