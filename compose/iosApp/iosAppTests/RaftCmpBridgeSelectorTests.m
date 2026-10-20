#import <XCTest/XCTest.h>
#import <objc/runtime.h>

// Guards the Objective-C side of the Kotlin `RaftCmpIosBridge` interface. Kotlin/Native picks the
// exported selectors (and mangles them on collisions, e.g. `callback_:`); if RaftCmpBridgeHost
// stops answering one of them, the first bridge call crashes with "unrecognized selector". Both
// the class and the protocol are looked up at runtime, so this file needs neither the shared
// framework header nor the app's headers.
@interface RaftCmpBridgeSelectorTests : XCTestCase
@end

@implementation RaftCmpBridgeSelectorTests

- (void)testBridgeHostAnswersEveryRequiredProtocolSelector {
    Class hostClass = NSClassFromString(@"RaftCmpBridgeHost");
    XCTAssertNotNil(hostClass, @"RaftCmpBridgeHost is not linked into the host app");
    Protocol *bridgeProtocol = objc_getProtocol("SharedRaftCmpIosBridge");
    XCTAssertNotNil(bridgeProtocol, @"the shared framework no longer exports SharedRaftCmpIosBridge");
    if (hostClass == Nil || bridgeProtocol == nil) {
        return;
    }
    XCTAssertTrue(class_conformsToProtocol(hostClass, bridgeProtocol));

    unsigned int count = 0;
    struct objc_method_description *methods = protocol_copyMethodDescriptionList(bridgeProtocol, YES, YES, &count);
    XCTAssertGreaterThan(count, 0u, @"SharedRaftCmpIosBridge exports no required instance methods");
    for (unsigned int index = 0; index < count; index++) {
        SEL selector = methods[index].name;
        XCTAssertTrue([hostClass instancesRespondToSelector:selector],
                      @"RaftCmpBridgeHost does not implement %@", NSStringFromSelector(selector));
    }
    free(methods);
}

- (void)testBridgeCallSelectorIsPinned {
    // Kotlin pins this selector with @ObjCName; a change here is a contract change for the host.
    Protocol *bridgeProtocol = objc_getProtocol("SharedRaftCmpIosBridge");
    XCTAssertNotNil(bridgeProtocol);
    if (bridgeProtocol == nil) {
        return;
    }
    struct objc_method_description call =
        protocol_getMethodDescription(bridgeProtocol, NSSelectorFromString(@"callMethod:paramsJson:resultSink:"), YES, YES);
    XCTAssertTrue(call.name != NULL, @"SharedRaftCmpIosBridge no longer declares callMethod:paramsJson:resultSink:");
    struct objc_method_description callSync =
        protocol_getMethodDescription(bridgeProtocol, NSSelectorFromString(@"callSyncMethod:paramsJson:"), YES, YES);
    XCTAssertTrue(callSync.name != NULL, @"SharedRaftCmpIosBridge no longer declares callSyncMethod:paramsJson:");
}

@end
