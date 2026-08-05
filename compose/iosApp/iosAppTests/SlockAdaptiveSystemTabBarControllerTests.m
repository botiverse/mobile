#import <XCTest/XCTest.h>

#import "SlockAdaptiveSystemTabBarController.h"

API_AVAILABLE(ios(18.0))
@interface SlockAdaptiveSystemTabBarControllerTests : XCTestCase
@end

@implementation SlockAdaptiveSystemTabBarControllerTests

- (void)testRegularWidthUsesTiledSidebarAndCompactWidthUsesTabBar {
    SlockAdaptiveSystemTabBarController *controller = [[SlockAdaptiveSystemTabBarController alloc] init];

    [controller slock_updateAdaptivePresentationForHorizontalSizeClass:UIUserInterfaceSizeClassRegular];
    XCTAssertEqual(controller.mode, UITabBarControllerModeTabSidebar);
    XCTAssertEqual(controller.sidebar.preferredLayout, UITabBarControllerSidebarLayoutTile);
    XCTAssertFalse(controller.sidebar.isHidden);

    [controller slock_updateAdaptivePresentationForHorizontalSizeClass:UIUserInterfaceSizeClassCompact];
    XCTAssertEqual(controller.mode, UITabBarControllerModeTabBar);
    XCTAssertTrue(controller.sidebar.isHidden);
}

- (void)testLayoutReactsToTraitCollectionTransition {
    UIViewController *parent = [[UIViewController alloc] init];
    SlockAdaptiveSystemTabBarController *controller = [[SlockAdaptiveSystemTabBarController alloc] init];
    [parent addChildViewController:controller];

    [parent setOverrideTraitCollection:[UITraitCollection traitCollectionWithHorizontalSizeClass:UIUserInterfaceSizeClassRegular]
                forChildViewController:controller];
    [controller loadViewIfNeeded];
    [controller.view layoutIfNeeded];
    XCTAssertEqual(controller.mode, UITabBarControllerModeTabSidebar);
    XCTAssertFalse(controller.sidebar.isHidden);

    [parent setOverrideTraitCollection:[UITraitCollection traitCollectionWithHorizontalSizeClass:UIUserInterfaceSizeClassCompact]
                forChildViewController:controller];
    [controller.view setNeedsLayout];
    [controller.view layoutIfNeeded];
    XCTAssertEqual(controller.mode, UITabBarControllerModeTabBar);
    XCTAssertTrue(controller.sidebar.isHidden);
}

@end
