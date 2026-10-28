#import "RaftPageRouter.h"
#import "RaftPageViewController.h"
#import "iosApp-Swift.h"
#import <objc/runtime.h>

static NSString *const RaftRouterReplaceCurrentPageKey = @"slockReplaceCurrentPage";
/// Shared `RAFT_OPEN_WITHOUT_ANIMATION`: the page takes the place of what its source shows over itself.
static NSString *const RaftRouterOpenWithoutAnimationKey = @"slockOpenWithoutAnimation";
static char RaftRouterRetiredByLoginKey;

static NSDictionary *RaftRouterIdentityPageData(NSDictionary *pageData) {
    if (![pageData isKindOfClass:NSDictionary.class]) {
        return @{};
    }
    static NSSet<NSString *> *blockedKeys;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        blockedKeys = [NSSet setWithArray:@[
            @"messages",
            @"tasks",
            @"members",
            @"files",
            @"attachments",
            @"profiles",
            @"plans",
            @"computers",
            @"suggestions",
            @"composerState",
            @"snapshot"
        ]];
    });
    NSMutableDictionary *sanitized = [pageData mutableCopy];
    for (NSString *key in blockedKeys) {
        if (sanitized[key] != nil) {
            [sanitized removeObjectForKey:key];
            NSLog(@"[RaftPageRouter][warn] dropped non-identity router payload key=%@", key);
        }
    }
    return sanitized;
}

static BOOL RaftRouterBool(id value) {
    if ([value isKindOfClass:NSNumber.class]) {
        return [(NSNumber *)value boolValue];
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        NSString *text = [[[value stringValue] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet] lowercaseString];
        return [text isEqualToString:@"true"] || [text isEqualToString:@"1"] || [text isEqualToString:@"yes"];
    }
    return NO;
}

@implementation RaftPageRouter

- (void)openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData controller:(UIViewController *)controller {
    NSDictionary *sanitizedPageData = RaftRouterIdentityPageData(pageData);
    UINavigationController *navigationController = controller.navigationController;
    BOOL replaceCurrent = RaftRouterBool(sanitizedPageData[RaftRouterReplaceCurrentPageKey]);
    if (replaceCurrent && [pageName isEqualToString:@"compose_login"]) {
        // A removed page can still have a window during its exit animation.
        // Retire its terminal callbacks before mutating UIKit containment.
        if ([objc_getAssociatedObject(controller, &RaftRouterRetiredByLoginKey) boolValue]) {
            return;
        }
        // Logout completion, mounted-page terminal observers and late auth-loss
        // requests can all target login. The first replacement owns the stack;
        // later requests must preserve that login instance and its input state.
        UIViewController *top = navigationController.topViewController;
        if ([top isKindOfClass:RaftPageViewController.class] &&
            ((RaftPageViewController *)top).raftIsLoginPage) {
            return;
        }
    }
    UIViewController *renderViewController = [RaftPageControllerFactory controllerForPageName:pageName pageData:sanitizedPageData];
    if (navigationController) {
        if (replaceCurrent && navigationController.viewControllers.count > 0) {
            NSMutableArray<UIViewController *> *viewControllers = [navigationController.viewControllers mutableCopy];
            if ([pageName isEqualToString:@"compose_login"]) {
                objc_setAssociatedObject(viewControllers.lastObject, &RaftRouterRetiredByLoginKey,
                                         @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            }
            [viewControllers removeLastObject];
            [viewControllers addObject:renderViewController];
            [navigationController setViewControllers:viewControllers animated:YES];
        } else {
            BOOL animated = !RaftRouterBool(sanitizedPageData[RaftRouterOpenWithoutAnimationKey]);
            [navigationController pushViewController:renderViewController animated:animated];
        }
        return;
    }
    UINavigationController *presentedNavigationController =
        [[UINavigationController alloc] initWithRootViewController:renderViewController];
    [controller presentViewController:presentedNavigationController animated:YES completion:nil];
}

- (void)closePage:(UIViewController *)controller {
    UINavigationController *navigationController = controller.navigationController;
    if (navigationController && navigationController.viewControllers.count > 1) {
        [navigationController popViewControllerAnimated:YES];
        return;
    }
    if (controller.presentingViewController) {
        [controller dismissViewControllerAnimated:YES completion:nil];
        return;
    }
    NSLog(@"[RaftPageRouter][warn] closePage no-op: missing navigation stack");
}

@end
