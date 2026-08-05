#import "SlockStackedItemTabBar.h"

@implementation SlockStackedItemTabBar

- (instancetype)initWithFrame:(CGRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        [self slock_applyStackedItemPresentation];
    }
    return self;
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    [self slock_applyStackedItemPresentation];
}

- (void)slock_applyStackedItemPresentation {
    [self slock_updateStackedItemPresentationForUserInterfaceIdiom:UIDevice.currentDevice.userInterfaceIdiom];
}

- (void)slock_updateStackedItemPresentationForUserInterfaceIdiom:(UIUserInterfaceIdiom)userInterfaceIdiom {
    if (@available(iOS 17.0, *)) {
        if (userInterfaceIdiom == UIUserInterfaceIdiomPad) {
            self.traitOverrides.horizontalSizeClass = UIUserInterfaceSizeClassCompact;
        } else {
            [self.traitOverrides removeTrait:UITraitHorizontalSizeClass.class];
        }
    }
}

@end
