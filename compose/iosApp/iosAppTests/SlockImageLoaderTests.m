#import <XCTest/XCTest.h>
#import "../iosApp/KuiklyExpand/Handler/KuiklyRenderComponentExpandHandler.h"

@interface SlockImageLoaderTests : XCTestCase
@end

@implementation SlockImageLoaderTests

- (void)testUnavailableManagedImageReportsFailureInsteadOfHanging {
    KuiklyRenderComponentExpandHandler *loader = [KuiklyRenderComponentExpandHandler new];
    UIImageView *view = [UIImageView new];
    __block NSUInteger calls = 0;
    NSString *source = @"slock-avatar-render-v1:invalid";
    [loader hr_setImageWithUrl:source forImageView:view complete:^(UIImage *image, NSError *error, NSURL *imageURL) {
        calls++;
        XCTAssertNil(image);
        XCTAssertNotNil(error);
        XCTAssertEqualObjects(imageURL.absoluteString, source);
    }];
    XCTAssertEqual(calls, 1u);
    XCTAssertNil(view.image);
}

- (void)testMissingFileCompletesWithAnError {
    KuiklyRenderComponentExpandHandler *loader = [KuiklyRenderComponentExpandHandler new];
    UIImageView *view = [UIImageView new];
    NSString *source = [[NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString]] absoluteString];
    XCTestExpectation *done = [self expectationWithDescription:@"missing file completes"];
    done.assertForOverFulfill = YES;
    [loader hr_setImageWithUrl:source forImageView:view complete:^(UIImage *image, NSError *error, NSURL *imageURL) {
        XCTAssertNil(image);
        XCTAssertNotNil(error);
        XCTAssertEqualObjects(imageURL.absoluteString, source);
        [done fulfill];
    }];
    [self waitForExpectations:@[done] timeout:5];
}

- (void)testLocalImageStillRendersAndCompletes {
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:CGSizeMake(3, 4)];
    UIImage *fixture = [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
        [UIColor.redColor setFill];
        UIRectFill(CGRectMake(0, 0, 3, 4));
    }];
    NSURL *file = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingString:@".png"]]];
    XCTAssertTrue([UIImagePNGRepresentation(fixture) writeToURL:file atomically:YES]);
    KuiklyRenderComponentExpandHandler *loader = [KuiklyRenderComponentExpandHandler new];
    UIImageView *view = [UIImageView new];
    XCTestExpectation *done = [self expectationWithDescription:@"local image completes"];
    done.assertForOverFulfill = YES;
    [loader hr_setImageWithUrl:file.absoluteString forImageView:view complete:^(UIImage *image, NSError *error, NSURL *imageURL) {
        XCTAssertNotNil(image);
        XCTAssertNil(error);
        XCTAssertEqualObjects(imageURL, file);
        XCTAssertNotNil(view.image);
        [done fulfill];
    }];
    [self waitForExpectations:@[done] timeout:5];
    [[NSFileManager defaultManager] removeItemAtURL:file error:nil];
}

- (void)testNilSourceClearsWithoutStartingANewLoad {
    UIImageView *view = [UIImageView new];
    KuiklyRenderComponentExpandHandler *loader = [KuiklyRenderComponentExpandHandler new];
    [loader hr_setImageWithUrl:nil forImageView:view complete:^(UIImage *image, NSError *error, NSURL *imageURL) {
        XCTFail(@"Clearing a source must not publish a new load result");
    }];
    XCTAssertNil(view.image);
}
@end
