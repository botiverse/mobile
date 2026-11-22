#import <XCTest/XCTest.h>
#import "../iosApp/Host/HRBridgeModule.h"

@interface RaftIosOpenAttachmentTests : XCTestCase
@end

@implementation RaftIosOpenAttachmentTests

- (void)testSafeComponentStripsNonAlphanumeric {
    XCTAssertEqualObjects(RaftAttachmentSafeComponent(@"a/b:c"), @"a_b_c");
    XCTAssertEqualObjects(RaftAttachmentSafeComponent(@"id-9_x.y"), @"id-9_x.y");
    XCTAssertEqualObjects(RaftAttachmentSafeComponent(@""), @"");
}

- (void)testCacheKeyOwnerPlusAttachment {
    XCTAssertEqualObjects(RaftAttachmentCacheKey(@"srv1", @"att9"), @"srv1-att9");
    XCTAssertEqualObjects(RaftAttachmentCacheKey(@"", @"att9"), @"att9");
    XCTAssertEqualObjects(RaftAttachmentCacheKey(nil, @"att9"), @"att9");
}

- (void)testCacheKeyEmptyAttachmentIsEmpty {
    XCTAssertEqualObjects(RaftAttachmentCacheKey(@"srv1", @""), @"");
    XCTAssertEqualObjects(RaftAttachmentCacheKey(@"srv1", nil), @"");
}

- (void)testPresenterChoiceCapturedThenFrontmost {
    UIViewController *captured = [UIViewController new];
    UIViewController *frontmost = [UIViewController new];
    XCTAssertEqual(RaftSharePresenterController(captured, frontmost), captured);
    XCTAssertEqual(RaftSharePresenterController(nil, frontmost), frontmost);
    XCTAssertNil(RaftSharePresenterController(nil, nil));
}

- (void)testReusableExportFilePrefersRequestedName {
    XCTAssertEqualObjects(
        RaftAttachmentReusableExportFileName(@[ @"other.bin", @"photo.png" ], @"photo.png"),
        @"photo.png");
}

- (void)testReusableExportFileFallsBackToFirstStoredFile {
    XCTAssertEqualObjects(
        RaftAttachmentReusableExportFileName(@[ @"only.bin" ], @"photo.png"),
        @"only.bin");
}

- (void)testReusableExportFileSkipsInProgressDownload {
    XCTAssertNil(
        RaftAttachmentReusableExportFileName(@[ @"photo.png.part-1234" ], @"photo.png"));
}

- (void)testReusableExportFileIsNilWhenNothingStored {
    XCTAssertNil(RaftAttachmentReusableExportFileName(@[], @"photo.png"));
    XCTAssertNil(RaftAttachmentReusableExportFileName(nil, @"photo.png"));
}

- (void)testReusableExportFileSanitizesRequestedName {
    // The store keys files by the sanitized name, so the request has to be sanitized the same way.
    XCTAssertEqualObjects(
        RaftAttachmentReusableExportFileName(@[ @"a_b_c.png" ], @"a/b:c.png"),
        @"a_b_c.png");
}

- (void)testRunAfterPickerDismissAndWorkWaitsForDismissWhenWorkFinishesFirst {
    __block void (^finishDismiss)(void) = nil;
    __block NSInteger completions = 0;
    XCTestExpectation *done = [self expectationWithDescription:@"completion after dismiss"];
    RaftRunAfterPickerDismissAndWork(
        ^(void (^dismissCompleted)(void)) {
            finishDismiss = [dismissCompleted copy];
        },
        ^(void (^workCompleted)(void)) {
            workCompleted();
        },
        dispatch_get_main_queue(),
        ^{
            completions += 1;
            [done fulfill];
        }
    );
    [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    XCTAssertEqual(completions, 0, @"completion must wait for picker dismissal");
    XCTAssertNotNil(finishDismiss);
    finishDismiss();
    [self waitForExpectations:@[ done ] timeout:5.0];
    XCTAssertEqual(completions, 1);
}

- (void)testRunAfterPickerDismissAndWorkWaitsForWorkWhenDismissFinishesFirst {
    __block void (^finishWork)(void) = nil;
    __block NSInteger completions = 0;
    XCTestExpectation *done = [self expectationWithDescription:@"completion after work"];
    RaftRunAfterPickerDismissAndWork(
        ^(void (^dismissCompleted)(void)) {
            dismissCompleted();
        },
        ^(void (^workCompleted)(void)) {
            finishWork = [workCompleted copy];
        },
        dispatch_get_main_queue(),
        ^{
            completions += 1;
            [done fulfill];
        }
    );
    [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    XCTAssertEqual(completions, 0, @"completion must wait for export work");
    XCTAssertNotNil(finishWork);
    finishWork();
    [self waitForExpectations:@[ done ] timeout:5.0];
    XCTAssertEqual(completions, 1);
}

- (void)testRunAfterPickerDismissAndWorkGuardsDuplicateCallbacksAndNilPhases {
    XCTestExpectation *doneBoth = [self expectationWithDescription:@"guarded double callbacks"];
    __block NSInteger bothCompletions = 0;
    RaftRunAfterPickerDismissAndWork(
        ^(void (^dismissCompleted)(void)) {
            dismissCompleted();
            dismissCompleted();
        },
        ^(void (^workCompleted)(void)) {
            workCompleted();
            workCompleted();
        },
        dispatch_get_main_queue(),
        ^{
            bothCompletions += 1;
            [doneBoth fulfill];
        }
    );
    XCTestExpectation *doneNilWork = [self expectationWithDescription:@"nil work"];
    __block NSInteger nilWorkCompletions = 0;
    RaftRunAfterPickerDismissAndWork(
        ^(void (^dismissCompleted)(void)) {
            dismissCompleted();
        },
        nil,
        dispatch_get_main_queue(),
        ^{
            nilWorkCompletions += 1;
            [doneNilWork fulfill];
        }
    );
    XCTestExpectation *doneNilDismiss = [self expectationWithDescription:@"nil dismiss"];
    __block NSInteger nilDismissCompletions = 0;
    RaftRunAfterPickerDismissAndWork(
        nil,
        ^(void (^workCompleted)(void)) {
            workCompleted();
        },
        dispatch_get_main_queue(),
        ^{
            nilDismissCompletions += 1;
            [doneNilDismiss fulfill];
        }
    );
    [self waitForExpectations:@[ doneBoth, doneNilWork, doneNilDismiss ] timeout:5.0];
    XCTAssertEqual(bothCompletions, 1);
    XCTAssertEqual(nilWorkCompletions, 1);
    XCTAssertEqual(nilDismissCompletions, 1);
}

- (void)testRunAfterPickerDismissAndWorkDefaultsToMainQueueForOffMainWork {
    XCTestExpectation *done = [self expectationWithDescription:@"default main queue delivery"];
    __block BOOL completedOnMain = NO;
    RaftRunAfterPickerDismissAndWork(
        ^(void (^dismissCompleted)(void)) {
            dismissCompleted();
        },
        ^(void (^workCompleted)(void)) {
            dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
                workCompleted();
            });
        },
        nil,
        ^{
            completedOnMain = [NSThread isMainThread];
            [done fulfill];
        }
    );
    [self waitForExpectations:@[ done ] timeout:5.0];
    XCTAssertTrue(completedOnMain, @"nil completionQueue must deliver completion on the main queue");
}

@end
