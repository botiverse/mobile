#import <XCTest/XCTest.h>
#import "../iosApp/KuiklyExpand/Modules/HRBridgeModule.h"

@interface SlockShareControllerFailureReasonTests : XCTestCase
@end

@implementation SlockShareControllerFailureReasonTests

- (void)testDetachedRootWhenRootViewHasNoWindow {
    NSString *reason = SlockShareControllerFailureReason(NO, YES);
    XCTAssertEqualObjects(reason, @"detached_root");
}

- (void)testDetachedRootTakesPrecedenceOverSceneState {
    NSString *reason = SlockShareControllerFailureReason(NO, NO);
    XCTAssertEqualObjects(reason, @"detached_root");
}

- (void)testSceneInactiveWhenNoForegroundActiveScene {
    NSString *reason = SlockShareControllerFailureReason(YES, NO);
    XCTAssertEqualObjects(reason, @"scene_inactive");
}

- (void)testNoControllerFallback {
    NSString *reason = SlockShareControllerFailureReason(YES, YES);
    XCTAssertEqualObjects(reason, @"no_controller");
}

- (void)testPresenterChoicePrefersCaptured {
    UIViewController *captured = [[UIViewController alloc] init];
    UIViewController *frontmost = [[UIViewController alloc] init];
    XCTAssertEqual(
        SlockSharePresenterController(captured, frontmost), captured,
        "tap-time captured presenter wins over frontmost");
}

- (void)testPresenterChoiceFallsBackToFrontmost {
    UIViewController *frontmost = [[UIViewController alloc] init];
    XCTAssertEqual(
        SlockSharePresenterController(nil, frontmost), frontmost,
        "nil captured presenter falls back to frontmost");
}

- (void)testPresenterChoiceNilWhenBothMissing {
    XCTAssertNil(
        SlockSharePresenterController(nil, nil),
        "both nil -> nil, retry/fail path");
}

@end
