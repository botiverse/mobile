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

@end
