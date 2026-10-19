#import <XCTest/XCTest.h>
#import "../iosApp/Host/HRBridgeModule.h"

@interface SlockIosOpenAttachmentTests : XCTestCase
@end

@implementation SlockIosOpenAttachmentTests

- (void)testSafeComponentStripsNonAlphanumeric {
    XCTAssertEqualObjects(SlockAttachmentSafeComponent(@"a/b:c"), @"a_b_c");
    XCTAssertEqualObjects(SlockAttachmentSafeComponent(@"id-9_x.y"), @"id-9_x.y");
    XCTAssertEqualObjects(SlockAttachmentSafeComponent(@""), @"");
}

- (void)testCacheKeyOwnerPlusAttachment {
    XCTAssertEqualObjects(SlockAttachmentCacheKey(@"srv1", @"att9"), @"srv1-att9");
    XCTAssertEqualObjects(SlockAttachmentCacheKey(@"", @"att9"), @"att9");
    XCTAssertEqualObjects(SlockAttachmentCacheKey(nil, @"att9"), @"att9");
}

- (void)testCacheKeyEmptyAttachmentIsEmpty {
    XCTAssertEqualObjects(SlockAttachmentCacheKey(@"srv1", @""), @"");
    XCTAssertEqualObjects(SlockAttachmentCacheKey(@"srv1", nil), @"");
}

- (void)testPresenterChoiceCapturedThenFrontmost {
    UIViewController *captured = [UIViewController new];
    UIViewController *frontmost = [UIViewController new];
    XCTAssertEqual(SlockSharePresenterController(captured, frontmost), captured);
    XCTAssertEqual(SlockSharePresenterController(nil, frontmost), frontmost);
    XCTAssertNil(SlockSharePresenterController(nil, nil));
}

@end
