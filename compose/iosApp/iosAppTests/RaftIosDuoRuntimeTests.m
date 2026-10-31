#import <XCTest/XCTest.h>
#import "../iosApp/Cmp/RaftIosDuoGeometry.h"

// A view that answers the iOS 27 `reservedRegionsOfKind:options:` selector with an array it owns,
// as UIKit does on iOS 27.2.
@interface RaftIosDuoRuntimeFakeView : UIView
@property (nonatomic, strong) NSArray *regions;
@end

@implementation RaftIosDuoRuntimeFakeView
- (NSArray *)reservedRegionsOfKind:(id)kind options:(NSUInteger)options {
    return self.regions;
}
@end

@interface RaftIosDuoRuntimeTests : XCTestCase
@end

@implementation RaftIosDuoRuntimeTests

// 1.17.1 crashed at launch on iOS 27.2: the returned array was read into a __strong variable
// through NSInvocation, so ARC released an object it never retained. The caller's reference
// must be left exactly as it was.
- (void)testReservedRegionsDoesNotOverReleaseTheReturnedArray {
    RaftIosDuoRuntimeFakeView *view = [[RaftIosDuoRuntimeFakeView alloc] initWithFrame:CGRectZero];
    view.regions = [NSArray arrayWithObjects:[NSObject new], [NSObject new], nil];
    CFIndex before = CFGetRetainCount((__bridge CFTypeRef)view.regions);

    for (int i = 0; i < 3; i++) {
        @autoreleasepool {
            NSArray *found = RaftIosReservedRegionsForKind(view, [NSObject new]);
            XCTAssertEqual(found.count, 2u);
        }
    }

    XCTAssertEqual(CFGetRetainCount((__bridge CFTypeRef)view.regions), before);
}

- (void)testReservedRegionsIsEmptyWithoutTheSelector {
    XCTAssertEqualObjects(RaftIosReservedRegionsForKind([UIView new], [NSObject new]), @[]);
    XCTAssertEqualObjects(RaftIosReservedRegionsForKind(nil, [NSObject new]), @[]);
}

@end
