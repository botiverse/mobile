#import <XCTest/XCTest.h>
#import "../iosApp/Host/HRBridgeModule.h"

@interface RaftShareControllerFailureReasonTests : XCTestCase
@end

@implementation RaftShareControllerFailureReasonTests

- (void)testDetachedRootWhenRootViewHasNoWindow {
    NSString *reason = RaftShareControllerFailureReason(NO, YES);
    XCTAssertEqualObjects(reason, @"detached_root");
}

- (void)testDetachedRootTakesPrecedenceOverSceneState {
    NSString *reason = RaftShareControllerFailureReason(NO, NO);
    XCTAssertEqualObjects(reason, @"detached_root");
}

- (void)testSceneInactiveWhenNoForegroundActiveScene {
    NSString *reason = RaftShareControllerFailureReason(YES, NO);
    XCTAssertEqualObjects(reason, @"scene_inactive");
}

- (void)testNoControllerFallback {
    NSString *reason = RaftShareControllerFailureReason(YES, YES);
    XCTAssertEqualObjects(reason, @"no_controller");
}

@end
