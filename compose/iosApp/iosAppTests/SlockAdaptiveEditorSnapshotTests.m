#import <XCTest/XCTest.h>
#import "../iosApp/KuiklyExpand/SlockAdaptiveEditorSnapshot.h"

@interface SlockAdaptiveEditorSnapshotTests : XCTestCase
@end

@implementation SlockAdaptiveEditorSnapshotTests
- (void)testReadsLiveTextAndUTF16SelectionWithoutMutatingEditor {
    UITextView *editor = [[UITextView alloc] initWithFrame:CGRectMake(0, 0, 320, 100)];
    editor.text = @"A😀B";
    editor.selectedRange = NSMakeRange(1, 2);
    NSDictionary *snapshot = SlockAdaptiveEditorSnapshot(editor);
    XCTAssertEqualObjects(snapshot[@"text"], @"A😀B");
    XCTAssertEqualObjects(snapshot[@"selectionStart"], @1);
    XCTAssertEqualObjects(snapshot[@"selectionEnd"], @3);
    editor.text = @"new input";
    XCTAssertEqualObjects(snapshot[@"text"], @"A😀B");
    XCTAssertEqualObjects(SlockAdaptiveEditorSnapshot(editor)[@"text"], @"new input");
}
- (void)testMarkedCompositionIsUnavailableUntilCommitted {
    UITextView *editor = [[UITextView alloc] initWithFrame:CGRectMake(0, 0, 320, 100)];
    [editor setMarkedText:@"拼" selectedRange:NSMakeRange(1, 0)];
    XCTAssertNotNil(editor.markedTextRange);
    XCTAssertNil(SlockAdaptiveEditorSnapshot(editor));
    [editor unmarkText];
    XCTAssertNotNil(SlockAdaptiveEditorSnapshot(editor));
}
- (void)testMissingOrReadOnlyEditorIsUnavailable {
    XCTAssertNil(SlockAdaptiveEditorSnapshot(nil));
    UITextView *editor = [UITextView new];
    editor.editable = NO;
    XCTAssertNil(SlockAdaptiveEditorSnapshot(editor));
}
@end
