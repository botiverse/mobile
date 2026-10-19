#import <XCTest/XCTest.h>
#import "../iosApp/Host/HRBridgeModule.h"

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

@end
