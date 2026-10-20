#import "RaftPageRouter.h"
#import "RaftPageViewController.h"
#import "iosApp-Swift.h"
#import <objc/runtime.h>

static NSString *const SlockRouterReplaceCurrentPageKey = @"slockReplaceCurrentPage";
static char SlockRouterRetiredByLoginKey;

static NSDictionary *SlockRouterIdentityPageData(NSDictionary *pageData) {
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

static BOOL SlockRouterBool(id value) {
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
    NSDictionary *sanitizedPageData = SlockRouterIdentityPageData(pageData);
    UINavigationController *navigationController = controller.navigationController;
    BOOL replaceCurrent = SlockRouterBool(sanitizedPageData[SlockRouterReplaceCurrentPageKey]);
    if (replaceCurrent && [pageName isEqualToString:@"compose_login"]) {
        // A removed page can still have a window during its exit animation.
        // Retire its terminal callbacks before mutating UIKit containment.
        if ([objc_getAssociatedObject(controller, &SlockRouterRetiredByLoginKey) boolValue]) {
            return;
        }
        // Logout completion, mounted-page terminal observers and late auth-loss
        // requests can all target login. The first replacement owns the stack;
        // later requests must preserve that login instance and its input state.
        UIViewController *top = navigationController.topViewController;
        if ([top isKindOfClass:RaftPageViewController.class] &&
            ((RaftPageViewController *)top).slockIsLoginPage) {
            return;
        }
    }
    UIViewController *renderViewController = [SlockPageControllerFactory controllerForPageName:pageName pageData:sanitizedPageData];
    if (navigationController) {
        if (replaceCurrent && navigationController.viewControllers.count > 0) {
            NSMutableArray<UIViewController *> *viewControllers = [navigationController.viewControllers mutableCopy];
            if ([pageName isEqualToString:@"compose_login"]) {
                objc_setAssociatedObject(viewControllers.lastObject, &SlockRouterRetiredByLoginKey,
                                         @YES, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            }
            [viewControllers removeLastObject];
            [viewControllers addObject:renderViewController];
            [navigationController setViewControllers:viewControllers animated:YES];
        } else {
            [navigationController pushViewController:renderViewController animated:YES];
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
