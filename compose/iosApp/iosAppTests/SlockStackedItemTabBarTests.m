#import <XCTest/XCTest.h>

#import "SlockStackedItemTabBar.h"

@interface SlockStackedItemTabBarTests : XCTestCase
@end


@implementation SlockStackedItemTabBarTests

- (void)testUsesCompactTraitForNativeStackedItemPresentation {
    UIViewController *regularHost = [[UIViewController alloc] init];
    SlockStackedItemTabBar *tabBar = [[SlockStackedItemTabBar alloc] initWithFrame:CGRectMake(0, 0, 1024, 92)];

    if (@available(iOS 17.0, *)) {
        regularHost.traitOverrides.horizontalSizeClass = UIUserInterfaceSizeClassRegular;
        [regularHost.view addSubview:tabBar];
        [tabBar slock_updateStackedItemPresentationForUserInterfaceIdiom:UIUserInterfaceIdiomPad];
        [regularHost.view layoutIfNeeded];
        XCTAssertEqual(tabBar.traitOverrides.horizontalSizeClass, UIUserInterfaceSizeClassCompact);
        XCTAssertTrue([tabBar.traitOverrides containsTrait:UITraitHorizontalSizeClass.class]);
    }
}

- (void)testPhoneDoesNotOverrideHorizontalSizeClass {
    SlockStackedItemTabBar *tabBar = [[SlockStackedItemTabBar alloc] initWithFrame:CGRectMake(0, 0, 844, 92)];

    if (@available(iOS 17.0, *)) {
        [tabBar slock_updateStackedItemPresentationForUserInterfaceIdiom:UIUserInterfaceIdiomPad];
        XCTAssertTrue([tabBar.traitOverrides containsTrait:UITraitHorizontalSizeClass.class]);

        [tabBar slock_updateStackedItemPresentationForUserInterfaceIdiom:UIUserInterfaceIdiomPhone];
        XCTAssertFalse([tabBar.traitOverrides containsTrait:UITraitHorizontalSizeClass.class]);
    }
}

- (void)testKeepsNativeTabBarItemsAndSelectionContract {
    SlockStackedItemTabBar *tabBar = [[SlockStackedItemTabBar alloc] initWithFrame:CGRectMake(0, 0, 1024, 92)];
    UITabBarItem *home = [[UITabBarItem alloc] initWithTitle:@"Home" image:nil tag:0];
    UITabBarItem *tasks = [[UITabBarItem alloc] initWithTitle:@"Tasks" image:nil tag:1];
    tasks.enabled = NO;

    tabBar.items = @[home, tasks];
    tabBar.selectedItem = home;

    XCTAssertEqualObjects(tabBar.items, (@[home, tasks]));
    XCTAssertEqual(tabBar.selectedItem, home);
    XCTAssertFalse(tabBar.items[1].isEnabled);
}

@end
