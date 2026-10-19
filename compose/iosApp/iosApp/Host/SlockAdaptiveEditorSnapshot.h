#import <UIKit/UIKit.h>

/** Main-thread snapshot for a committed (non-marked) native draft. No text is logged. */
static inline NSDictionary *SlockAdaptiveEditorSnapshot(UITextView *editor) {
    NSCAssert(NSThread.isMainThread, @"Editor snapshots require the main thread");
    // Popping a composing editor can itself finalize input. Defer adoption until composition
    // has ended rather than persisting an intermediate value and resigning it during pop.
    if (!editor || !editor.editable || editor.markedTextRange != nil) return nil;
    NSString *text = [editor.text copy];
    NSRange selection = editor.selectedRange;
    if (!text || selection.location == NSNotFound || selection.location > text.length ||
        selection.length > text.length - selection.location) return nil;
    return @{ @"text": text, @"selectionStart": @(selection.location),
              @"selectionEnd": @(NSMaxRange(selection)) };
}
