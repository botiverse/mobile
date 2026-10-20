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

@end
