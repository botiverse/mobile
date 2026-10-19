#import <XCTest/XCTest.h>
#import "../iosApp/Cmp/RaftPageRouter.h"
#import "../iosApp/Cmp/RaftPageViewController.h"

// Keep real UIKit containment without attaching the navigation stack to a
// window or loading a page view. These tests count transition requests; they do
// not claim that an on-screen animation or shared logout flow has completed.
@interface SlockRouterRecordingNavigationController : UINavigationController
@property (nonatomic, assign) NSUInteger replacementCount;
@property (nonatomic, assign) NSUInteger pushCount;
@property (nonatomic, assign) BOOL lastRequestedAnimation;
- (void)seedViewControllers:(NSArray<UIViewController *> *)viewControllers;
@end

@implementation SlockRouterRecordingNavigationController

- (void)seedViewControllers:(NSArray<UIViewController *> *)viewControllers {
    [super setViewControllers:viewControllers animated:NO];
    self.replacementCount = 0;
    self.pushCount = 0;
}

- (void)setViewControllers:(NSArray<UIViewController *> *)viewControllers animated:(BOOL)animated {
    self.replacementCount += 1;
    self.lastRequestedAnimation = animated;
    [super setViewControllers:viewControllers animated:NO];
}

- (void)pushViewController:(UIViewController *)viewController animated:(BOOL)animated {
    self.pushCount += 1;
    self.lastRequestedAnimation = animated;
    // The production full-screen-pop push hook loads navigation views. This
    // recorder observes the push request and uses public UIKit containment to
    // apply its resulting stack without exercising that unrelated gesture hook.
    [super setViewControllers:[self.viewControllers arrayByAddingObject:viewController] animated:NO];
}

@end

@interface SlockRouterRecordingSourceController : UIViewController
@property (nonatomic, assign) NSUInteger presentationCount;
@property (nonatomic, strong) UIViewController *recordedPresentation;
@end

@implementation SlockRouterRecordingSourceController

- (void)presentViewController:(UIViewController *)viewControllerToPresent
                    animated:(BOOL)animated
                  completion:(void (^)(void))completion {
    self.presentationCount += 1;
    self.recordedPresentation = viewControllerToPresent;
}

@end

@interface SlockRouterLoginTransitionTests : XCTestCase
@property (nonatomic, strong) RaftPageRouter *router;
@end

@implementation SlockRouterLoginTransitionTests

- (void)setUp {
    [super setUp];
    self.router = [RaftPageRouter new];
}

- (void)tearDown {
    self.router = nil;
    [super tearDown];
}

- (RaftPageViewController *)renderPage:(NSString *)pageName {
    RaftPageViewController *page =
        [[RaftPageViewController alloc] initWithPageName:pageName pageData:@{}];
    XCTAssertFalse(page.isViewLoaded, @"Constructing a route must not start shared rendering");
    return page;
}

- (SlockRouterRecordingNavigationController *)navigationWithPages:(NSArray<UIViewController *> *)pages {
    SlockRouterRecordingNavigationController *navigation =
        [[SlockRouterRecordingNavigationController alloc] initWithNavigationBarClass:nil toolbarClass:nil];
    [navigation seedViewControllers:pages];
    XCTAssertEqualObjects(navigation.viewControllers, pages);
    for (UIViewController *page in pages) {
        XCTAssertEqual(page.navigationController, navigation, @"Use real UIKit containment");
        XCTAssertFalse(page.isViewLoaded, @"The fixture must not start shared rendering");
    }
    return navigation;
}

- (void)replaceWithLoginFrom:(UIViewController *)source {
    [self.router openPageWithName:@"compose_login"
                       pageData:@{@"slockReplaceCurrentPage": @YES}
                     controller:source];
}

- (RaftPageViewController *)loginInNavigation:(UINavigationController *)navigation {
    UIViewController *top = navigation.topViewController;
    XCTAssertTrue([top isKindOfClass:RaftPageViewController.class]);
    if (![top isKindOfClass:RaftPageViewController.class]) {
        return nil;
    }
    RaftPageViewController *login = (RaftPageViewController *)top;
    XCTAssertTrue(login.slockIsLoginPage);
    XCTAssertFalse(login.isViewLoaded, @"Router admission must not load the shared login page");
    return login;
}

- (void)testAccountTerminalThenShellAuthLossKeepsFirstLoginInstance {
    RaftPageViewController *shell = [self renderPage:@"compose_slock_app_shell"];
    SlockRouterRecordingSourceController *account = [SlockRouterRecordingSourceController new];
    SlockRouterRecordingNavigationController *navigation = [self navigationWithPages:@[shell, account]];

    [self replaceWithLoginFrom:account];
    RaftPageViewController *login = [self loginInNavigation:navigation];
    XCTAssertEqual(navigation.replacementCount, 1u);
    XCTAssertTrue(navigation.lastRequestedAnimation);
    XCTAssertEqual(navigation.viewControllers.count, 2u);
    XCTAssertEqual(navigation.viewControllers.firstObject, shell);
    XCTAssertNil(account.navigationController);

    // The underlying mounted shell observes auth loss after Account was removed.
    [self replaceWithLoginFrom:shell];
    [self replaceWithLoginFrom:shell];
    XCTAssertEqual(navigation.topViewController, login, @"Preserve the first login's identity and input state");
    XCTAssertEqual(navigation.replacementCount, 1u, @"Only the first terminal event requests an animation");
    XCTAssertEqual(navigation.pushCount, 0u);
    XCTAssertEqual(navigation.viewControllers.count, 2u);

    // Account's asynchronous logout completion can arrive after both observers.
    [self replaceWithLoginFrom:account];
    XCTAssertEqual(account.presentationCount, 0u, @"A removed source cannot open a second modal login");
    XCTAssertEqual(navigation.topViewController, login);
    XCTAssertEqual(navigation.replacementCount, 1u);
    XCTAssertFalse(account.isViewLoaded);
}

- (void)testRetiredAccountCannotPresentWhileItsOutgoingViewStillHasAWindow {
    UIViewController *shell = [UIViewController new];
    SlockRouterRecordingSourceController *account = [SlockRouterRecordingSourceController new];
    SlockRouterRecordingNavigationController *navigation =
        [[SlockRouterRecordingNavigationController alloc] initWithNavigationBarClass:nil toolbarClass:nil];
    [navigation seedViewControllers:@[shell, account]];
    XCTAssertEqual(account.navigationController, navigation);
    XCTAssertFalse(navigation.isViewLoaded);

    // Model an outgoing transition view retained by UIKit after containment
    // changes. This window stays hidden and never becomes key; the navigation
    // stack and the new login are never mounted into it.
    UIWindow *transitionWindow = [[UIWindow alloc] initWithFrame:CGRectMake(0, 0, 320, 640)];
    [transitionWindow addSubview:account.view];
    XCTAssertTrue(transitionWindow.isHidden);
    XCTAssertFalse(transitionWindow.isKeyWindow);
    XCTAssertEqual(account.viewIfLoaded.window, transitionWindow);

    [self replaceWithLoginFrom:account];
    RaftPageViewController *login = [self loginInNavigation:navigation];
    XCTAssertEqual(navigation.replacementCount, 1u);
    XCTAssertNil(account.navigationController);
    XCTAssertNil(account.presentingViewController);
    XCTAssertEqual(account.viewIfLoaded.window, transitionWindow,
                   @"The late completion must exercise a retired source whose view still has a window");

    [self replaceWithLoginFrom:account];
    XCTAssertEqual(account.presentationCount, 0u,
                   @"Retirement must block a modal login during the outgoing view's transition lifetime");
    XCTAssertEqual(navigation.replacementCount, 1u);
    XCTAssertEqual(navigation.topViewController, login);
    XCTAssertFalse(login.isViewLoaded);
    XCTAssertTrue(transitionWindow.isHidden);
    XCTAssertFalse(transitionWindow.isKeyWindow);
    [account.viewIfLoaded removeFromSuperview];
}

- (void)testExistingLoginSuppressesReplacementWithoutPriorRouterRequest {
    RaftPageViewController *shell = [self renderPage:@"compose_slock_app_shell"];
    RaftPageViewController *login = [self renderPage:@"compose_login"];
    SlockRouterRecordingNavigationController *navigation = [self navigationWithPages:@[shell, login]];

    [self replaceWithLoginFrom:shell];
    [self replaceWithLoginFrom:login];
    XCTAssertEqual(navigation.topViewController, login);
    XCTAssertEqual(navigation.replacementCount, 0u, @"Admission depends on the live top page, not past requests");
    XCTAssertEqual(navigation.pushCount, 0u);
    XCTAssertFalse(login.isViewLoaded);
}

- (void)testRetiredSourceIsIgnoredWhetherOrNotItsViewWasLoaded {
    for (NSNumber *loadView in @[@NO, @YES]) {
        SlockRouterRecordingSourceController *source = [SlockRouterRecordingSourceController new];
        SlockRouterRecordingNavigationController *navigation = [self navigationWithPages:@[source]];
        if (loadView.boolValue) {
            [source loadViewIfNeeded];
        }
        [self replaceWithLoginFrom:source];
        RaftPageViewController *login = [self loginInNavigation:navigation];
        XCTAssertEqual(navigation.replacementCount, 1u);
        XCTAssertNil(source.navigationController);
        XCTAssertNil(source.presentingViewController);
        XCTAssertNil(source.viewIfLoaded.window);

        [self replaceWithLoginFrom:source];
        [self replaceWithLoginFrom:source];
        XCTAssertEqual(source.presentationCount, 0u);
        XCTAssertEqual(navigation.replacementCount, 1u);
        XCTAssertEqual(navigation.topViewController, login);
        XCTAssertEqual(source.isViewLoaded, loadView.boolValue, @"Admission must not load a retired source's view");
    }
}

- (void)testUnretiredSourceWithoutNavigationKeepsPresentationFallback {
    SlockRouterRecordingSourceController *source = [SlockRouterRecordingSourceController new];
    XCTAssertNil(source.navigationController);

    [self replaceWithLoginFrom:source];
    XCTAssertEqual(source.presentationCount, 1u, @"An ordinary source keeps the existing modal fallback");
    XCTAssertTrue([source.recordedPresentation isKindOfClass:UINavigationController.class]);
    if ([source.recordedPresentation isKindOfClass:UINavigationController.class]) {
        UINavigationController *presentedNavigation = (UINavigationController *)source.recordedPresentation;
        XCTAssertTrue([presentedNavigation.topViewController isKindOfClass:RaftPageViewController.class]);
        if ([presentedNavigation.topViewController isKindOfClass:RaftPageViewController.class]) {
            XCTAssertTrue(((RaftPageViewController *)presentedNavigation.topViewController).slockIsLoginPage);
        }
        XCTAssertEqual(presentedNavigation.viewControllers.count, 1u);
    }
    XCTAssertNil(source.presentedViewController, @"The recorder must not actually present a modal");
}

- (void)testAnotherLogoutCycleCanReplaceAfterTopPageChanges {
    UIViewController *source = [UIViewController new];
    SlockRouterRecordingNavigationController *navigation = [self navigationWithPages:@[source]];
    [self replaceWithLoginFrom:source];
    RaftPageViewController *firstLogin = [self loginInNavigation:navigation];

    [self.router openPageWithName:@"compose_slock_app_shell"
                       pageData:@{@"slockReplaceCurrentPage": @YES}
                     controller:firstLogin];
    RaftPageViewController *newShell = (RaftPageViewController *)navigation.topViewController;
    XCTAssertTrue(newShell.slockIsAppShellPage);
    XCTAssertFalse(newShell.slockIsLoginPage);
    XCTAssertEqual(navigation.replacementCount, 2u);

    [self replaceWithLoginFrom:newShell];
    RaftPageViewController *secondLogin = [self loginInNavigation:navigation];
    XCTAssertNotEqual(secondLogin, firstLogin, @"A later authenticated cycle must receive a new login page");
    XCTAssertEqual(navigation.replacementCount, 3u);
    XCTAssertEqual(navigation.viewControllers.count, 1u);
    [self replaceWithLoginFrom:secondLogin];
    XCTAssertEqual(navigation.topViewController, secondLogin);
    XCTAssertEqual(navigation.replacementCount, 3u);
}

- (void)testLoginAdmissionIsIndependentForSeparateNavigationStacks {
    UIViewController *firstSource = [UIViewController new];
    UIViewController *secondSource = [UIViewController new];
    SlockRouterRecordingNavigationController *firstNavigation = [self navigationWithPages:@[firstSource]];
    SlockRouterRecordingNavigationController *secondNavigation = [self navigationWithPages:@[secondSource]];

    [self replaceWithLoginFrom:firstSource];
    RaftPageViewController *firstLogin = [self loginInNavigation:firstNavigation];
    [self replaceWithLoginFrom:firstLogin];
    [self replaceWithLoginFrom:secondSource];
    RaftPageViewController *secondLogin = [self loginInNavigation:secondNavigation];
    XCTAssertNotEqual(firstLogin, secondLogin);
    XCTAssertEqual(firstNavigation.replacementCount, 1u);
    XCTAssertEqual(secondNavigation.replacementCount, 1u, @"Another stack's login must not suppress this transition");
    XCTAssertEqual(firstNavigation.topViewController, firstLogin);
    XCTAssertEqual(firstNavigation.viewControllers.count, 1u);
    XCTAssertEqual(secondNavigation.viewControllers.count, 1u);
}

- (void)testRepeatedNonLoginReplacementsRemainAllowed {
    UIViewController *source = [UIViewController new];
    SlockRouterRecordingNavigationController *navigation = [self navigationWithPages:@[source]];
    [self.router openPageWithName:@"compose_slock_app_shell"
                       pageData:@{@"slockReplaceCurrentPage": @YES}
                     controller:source];
    UIViewController *firstShell = navigation.topViewController;
    [self.router openPageWithName:@"compose_slock_app_shell"
                       pageData:@{@"slockReplaceCurrentPage": @YES}
                     controller:firstShell];
    RaftPageViewController *secondShell = (RaftPageViewController *)navigation.topViewController;
    XCTAssertNotEqual(secondShell, firstShell);
    XCTAssertTrue(secondShell.slockIsAppShellPage);
    XCTAssertFalse(secondShell.slockIsLoginPage);
    XCTAssertEqual(navigation.replacementCount, 2u);
    XCTAssertEqual(navigation.pushCount, 0u);
    XCTAssertEqual(navigation.viewControllers.count, 1u);
    XCTAssertFalse(secondShell.isViewLoaded);
}

- (void)testLoginPushesAreNotCoalescedWithExistingLogin {
    RaftPageViewController *initialLogin = [self renderPage:@"compose_login"];
    SlockRouterRecordingNavigationController *navigation = [self navigationWithPages:@[initialLogin]];
    for (NSDictionary *pageData in @[@{}, @{@"slockReplaceCurrentPage": @NO}]) {
        UIViewController *priorLogin = navigation.topViewController;
        [self.router openPageWithName:@"compose_login" pageData:pageData controller:priorLogin];
        RaftPageViewController *nextLogin = [self loginInNavigation:navigation];
        XCTAssertNotEqual(nextLogin, priorLogin);
        XCTAssertTrue(navigation.lastRequestedAnimation);
    }
    XCTAssertEqual(navigation.pushCount, 2u);
    XCTAssertEqual(navigation.replacementCount, 0u);
    XCTAssertEqual(navigation.viewControllers.count, 3u);
    XCTAssertEqual(navigation.viewControllers.firstObject, initialLogin);
}

@end
