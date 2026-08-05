#import "SlockAdaptiveSystemTabBarController.h"

@implementation SlockAdaptiveSystemTabBarController

- (void)viewDidLoad {
    [super viewDidLoad];
    [self slock_updateAdaptivePresentation];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self slock_updateAdaptivePresentation];
}

- (void)traitCollectionDidChange:(UITraitCollection *)previousTraitCollection {
    [super traitCollectionDidChange:previousTraitCollection];
    if (previousTraitCollection.horizontalSizeClass != self.traitCollection.horizontalSizeClass) {
        [self slock_updateAdaptivePresentation];
    }
}

- (void)slock_updateAdaptivePresentation {
    [self slock_updateAdaptivePresentationForHorizontalSizeClass:self.traitCollection.horizontalSizeClass];
}

- (void)slock_updateAdaptivePresentationForHorizontalSizeClass:(UIUserInterfaceSizeClass)horizontalSizeClass {
    BOOL regular = horizontalSizeClass == UIUserInterfaceSizeClassRegular;
    self.mode = regular ? UITabBarControllerModeTabSidebar : UITabBarControllerModeTabBar;
    self.sidebar.preferredLayout = UITabBarControllerSidebarLayoutTile;
    self.sidebar.hidden = !regular;
}

@end
