#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

API_AVAILABLE(ios(18.0))
@interface SlockAdaptiveSystemTabBarController : UITabBarController

- (void)slock_updateAdaptivePresentationForHorizontalSizeClass:(UIUserInterfaceSizeClass)horizontalSizeClass;

@end


NS_ASSUME_NONNULL_END
