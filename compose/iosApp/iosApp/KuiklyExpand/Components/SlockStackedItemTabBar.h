#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// Keeps UIKit's native tab bar/material while asking it to use the compact
/// stacked item presentation (icon above title) on regular-width iPad hosts.
@interface SlockStackedItemTabBar : UITabBar

/// Exposed for exact platform-policy tests; production calls this with the
/// current device idiom whenever the view enters a window.
- (void)slock_updateStackedItemPresentationForUserInterfaceIdiom:(UIUserInterfaceIdiom)userInterfaceIdiom;

@end

NS_ASSUME_NONNULL_END
