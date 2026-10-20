#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

/**
 * Page navigation for CMP pages (`RouterModule.openPage` / `closePage`, through each page's
 * `RaftCmpPageNavigator`). The former Kuikly `KRRouterHandler`: identity-only pageData,
 * `slockReplaceCurrentPage` replacement and the login replacement dedupe. Every page is created
 * by `RaftPageControllerFactory` (a `RaftPageViewController` hosting the CMP page).
 * Main thread only.
 */
@interface RaftPageRouter : NSObject

- (void)openPageWithName:(NSString *)pageName
                pageData:(NSDictionary *)pageData
              controller:(UIViewController *)controller;

- (void)closePage:(UIViewController *)controller;

@end

NS_ASSUME_NONNULL_END
