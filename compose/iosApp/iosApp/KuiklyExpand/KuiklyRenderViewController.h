#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

@interface KuiklyRenderViewController : UIViewController

/** Native admission bit only; the identity-only Forward entry remains in the shared store. */
@property (nonatomic, assign) BOOL slockConversationForwardGestureEnabled;
@property (nonatomic, assign, readonly) BOOL slockIsAppShellPage;
@property (nonatomic, assign, readonly) BOOL slockIsLoginPage;

/*
 * @brief 创建实例对应的初始化方法.
 * @param pageName 页面名 （对应的值为kotlin侧页面注解 @Page("xxxx")中的xxx名）
 * @param params 页面对应的参数（kotlin侧可通过pageData.params获取）
 * @return 返回KuiklyRenderViewController实例
 */
- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData;

/** Dormant A4 bridge: prepare/commit/cancel. Only the controller-owned root may call it. */
- (NSDictionary *)slockAdaptiveNavigation:(NSDictionary *)params root:(UIView *)root;

/** Emits an intent-only pager event; shared code revalidates and rebuilds the route. */
- (void)slockRequestConversationForward;
@end

NS_ASSUME_NONNULL_END
