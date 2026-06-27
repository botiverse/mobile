#import "KRRouterHandler.h"
#import "KuiklyRenderViewController.h"

static NSString *const SlockRouterReplaceCurrentPageKey = @"slockReplaceCurrentPage";

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
            NSLog(@"[SlockKuikly][warn] dropped non-identity router payload key=%@", key);
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

@implementation KRRouterHandler

+ (void)load {
    [KRRouterModule registerRouterHandler:[self new]];
}

- (void)openPageWithName:(NSString *)pageName pageData:(NSDictionary *)pageData controller:(UIViewController *)controller {
    NSDictionary *sanitizedPageData = SlockRouterIdentityPageData(pageData);
    KuiklyRenderViewController *renderViewController = [[KuiklyRenderViewController alloc] initWithPageName:pageName pageData:sanitizedPageData];
    UINavigationController *navigationController = controller.navigationController;
    if (navigationController) {
        if (SlockRouterBool(sanitizedPageData[SlockRouterReplaceCurrentPageKey]) && navigationController.viewControllers.count > 0) {
            NSMutableArray<UIViewController *> *viewControllers = [navigationController.viewControllers mutableCopy];
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
    NSLog(@"[SlockKuikly][warn] router closePage no-op: missing navigation stack");
}

@end
