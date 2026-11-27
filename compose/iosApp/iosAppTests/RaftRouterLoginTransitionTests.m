#import <XCTest/XCTest.h>
#import <SafariServices/SafariServices.h>
#import "../iosApp/Cmp/RaftPageRouter.h"
#import "../iosApp/Cmp/RaftPageViewController.h"

// Keep real UIKit containment without attaching the navigation stack to a
// window or loading a page view. These tests count transition requests; they do
// not claim that an on-screen animation or shared logout flow has completed.
@interface RaftRouterRecordingNavigationController : UINavigationController
@property (nonatomic, assign) NSUInteger replacementCount;
@property (nonatomic, assign) NSUInteger pushCount;
@property (nonatomic, assign) BOOL lastRequestedAnimation;
- (void)seedViewControllers:(NSArray<UIViewController *> *)viewControllers;
@end

@implementation RaftRouterRecordingNavigationController

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

@interface RaftRouterRecordingSourceController : UIViewController
@property (nonatomic, assign) NSUInteger presentationCount;
@property (nonatomic, strong) UIViewController *recordedPresentation;
@end

@implementation RaftRouterRecordingSourceController

- (void)presentViewController:(UIViewController *)viewControllerToPresent
                    animated:(BOOL)animated
                  completion:(void (^)(void))completion {
    self.presentationCount += 1;
    self.recordedPresentation = viewControllerToPresent;
}

@end

@interface RaftStubPresentedPageViewController : RaftPageViewController
@property (nonatomic, strong, nullable) UIViewController *stubPresentedViewController;
@end

@implementation RaftStubPresentedPageViewController

- (UIViewController *)presentedViewController {
    return self.stubPresentedViewController ?: [super presentedViewController];
}

@end

@interface RaftRouterLoginTransitionTests : XCTestCase
@property (nonatomic, strong) RaftPageRouter *router;
@end

@implementation RaftRouterLoginTransitionTests

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

- (RaftRouterRecordingNavigationController *)navigationWithPages:(NSArray<UIViewController *> *)pages {
    RaftRouterRecordingNavigationController *navigation =
        [[RaftRouterRecordingNavigationController alloc] initWithNavigationBarClass:nil toolbarClass:nil];
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
    XCTAssertTrue(login.raftIsLoginPage);
    XCTAssertFalse(login.isViewLoaded, @"Router admission must not load the shared login page");
    return login;
}

- (void)testAccountTerminalThenShellAuthLossKeepsFirstLoginInstance {
    RaftPageViewController *shell = [self renderPage:@"compose_slock_app_shell"];
    RaftRouterRecordingSourceController *account = [RaftRouterRecordingSourceController new];
    RaftRouterRecordingNavigationController *navigation = [self navigationWithPages:@[shell, account]];

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
    RaftRouterRecordingSourceController *account = [RaftRouterRecordingSourceController new];
    RaftRouterRecordingNavigationController *navigation =
        [[RaftRouterRecordingNavigationController alloc] initWithNavigationBarClass:nil toolbarClass:nil];
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
    RaftRouterRecordingNavigationController *navigation = [self navigationWithPages:@[shell, login]];

    [self replaceWithLoginFrom:shell];
    [self replaceWithLoginFrom:login];
    XCTAssertEqual(navigation.topViewController, login);
    XCTAssertEqual(navigation.replacementCount, 0u, @"Admission depends on the live top page, not past requests");
    XCTAssertEqual(navigation.pushCount, 0u);
    XCTAssertFalse(login.isViewLoaded);
}

- (void)testRetiredSourceIsIgnoredWhetherOrNotItsViewWasLoaded {
    for (NSNumber *loadView in @[@NO, @YES]) {
        RaftRouterRecordingSourceController *source = [RaftRouterRecordingSourceController new];
        RaftRouterRecordingNavigationController *navigation = [self navigationWithPages:@[source]];
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
    RaftRouterRecordingSourceController *source = [RaftRouterRecordingSourceController new];
    XCTAssertNil(source.navigationController);

    [self replaceWithLoginFrom:source];
    XCTAssertEqual(source.presentationCount, 1u, @"An ordinary source keeps the existing modal fallback");
    XCTAssertTrue([source.recordedPresentation isKindOfClass:UINavigationController.class]);
    if ([source.recordedPresentation isKindOfClass:UINavigationController.class]) {
        UINavigationController *presentedNavigation = (UINavigationController *)source.recordedPresentation;
        XCTAssertTrue([presentedNavigation.topViewController isKindOfClass:RaftPageViewController.class]);
        if ([presentedNavigation.topViewController isKindOfClass:RaftPageViewController.class]) {
            XCTAssertTrue(((RaftPageViewController *)presentedNavigation.topViewController).raftIsLoginPage);
        }
        XCTAssertEqual(presentedNavigation.viewControllers.count, 1u);
    }
    XCTAssertNil(source.presentedViewController, @"The recorder must not actually present a modal");
}

- (void)testAnotherLogoutCycleCanReplaceAfterTopPageChanges {
    UIViewController *source = [UIViewController new];
    RaftRouterRecordingNavigationController *navigation = [self navigationWithPages:@[source]];
    [self replaceWithLoginFrom:source];
    RaftPageViewController *firstLogin = [self loginInNavigation:navigation];

    [self.router openPageWithName:@"compose_slock_app_shell"
                       pageData:@{@"slockReplaceCurrentPage": @YES}
                     controller:firstLogin];
    RaftPageViewController *newShell = (RaftPageViewController *)navigation.topViewController;
    XCTAssertTrue(newShell.raftIsAppShellPage);
    XCTAssertFalse(newShell.raftIsLoginPage);
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
    RaftRouterRecordingNavigationController *firstNavigation = [self navigationWithPages:@[firstSource]];
    RaftRouterRecordingNavigationController *secondNavigation = [self navigationWithPages:@[secondSource]];

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
    RaftRouterRecordingNavigationController *navigation = [self navigationWithPages:@[source]];
    [self.router openPageWithName:@"compose_slock_app_shell"
                       pageData:@{@"slockReplaceCurrentPage": @YES}
                     controller:source];
    UIViewController *firstShell = navigation.topViewController;
    [self.router openPageWithName:@"compose_slock_app_shell"
                       pageData:@{@"slockReplaceCurrentPage": @YES}
                     controller:firstShell];
    RaftPageViewController *secondShell = (RaftPageViewController *)navigation.topViewController;
    XCTAssertNotEqual(secondShell, firstShell);
    XCTAssertTrue(secondShell.raftIsAppShellPage);
    XCTAssertFalse(secondShell.raftIsLoginPage);
    XCTAssertEqual(navigation.replacementCount, 2u);
    XCTAssertEqual(navigation.pushCount, 0u);
    XCTAssertEqual(navigation.viewControllers.count, 1u);
    XCTAssertFalse(secondShell.isViewLoaded);
}

- (void)testLoginPushesAreNotCoalescedWithExistingLogin {
    RaftPageViewController *initialLogin = [self renderPage:@"compose_login"];
    RaftRouterRecordingNavigationController *navigation = [self navigationWithPages:@[initialLogin]];
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

- (void)testReplaceCurrentHonorsOpenWithoutAnimation {
    RaftPageViewController *shell = [self renderPage:@"compose_slock_app_shell"];
    RaftPageViewController *pendingTask = [self renderPage:@"compose_task_pending"];
    RaftRouterRecordingNavigationController *navigation = [self navigationWithPages:@[shell, pendingTask]];

    [self.router openPageWithName:@"compose_thread"
                         pageData:@{
                             @"slockReplaceCurrentPage": @YES,
                             @"slockOpenWithoutAnimation": @YES,
                         }
                       controller:pendingTask];
    XCTAssertEqual(navigation.replacementCount, 1u);
    XCTAssertEqual(navigation.pushCount, 0u);
    XCTAssertFalse(navigation.lastRequestedAnimation,
                   @"Replacing a pending task page with its conversation must honor slockOpenWithoutAnimation");
    XCTAssertEqual(navigation.viewControllers.count, 2u);
    XCTAssertEqual(navigation.viewControllers.firstObject, shell);
    XCTAssertNotEqual(navigation.topViewController, pendingTask);

    UIViewController *thread = navigation.topViewController;
    [self.router openPageWithName:@"compose_thread"
                         pageData:@{@"slockReplaceCurrentPage": @YES}
                       controller:thread];
    XCTAssertEqual(navigation.replacementCount, 2u);
    XCTAssertTrue(navigation.lastRequestedAnimation,
                  @"Replacement without slockOpenWithoutAnimation keeps animated=YES");
}

- (void)testReplaceCurrentReplacesSourceControllerIndexNotUnrelatedTop {
    RaftPageViewController *shell = [self renderPage:@"compose_slock_app_shell"];
    RaftPageViewController *pendingTask = [self renderPage:@"compose_task_pending"];
    RaftPageViewController *coveringPage = [self renderPage:@"compose_thread"];
    RaftRouterRecordingNavigationController *navigation =
        [self navigationWithPages:@[shell, pendingTask, coveringPage]];

    [self.router openPageWithName:@"compose_thread"
                         pageData:@{
                             @"slockReplaceCurrentPage": @YES,
                             @"slockOpenWithoutAnimation": @YES,
                         }
                       controller:pendingTask];
    XCTAssertEqual(navigation.replacementCount, 1u);
    XCTAssertEqual(navigation.viewControllers.count, 3u);
    XCTAssertEqual(navigation.viewControllers[0], shell);
    XCTAssertNotEqual(navigation.viewControllers[1], pendingTask,
                      @"Source controller at index 1 must be replaced in place");
    XCTAssertEqual(navigation.viewControllers[2], coveringPage,
                   @"Covering page above the source controller must stay untouched");

    // Replacing again from the already-removed pendingTask with slockOpenWithoutAnimation is a safe no-op.
    [self.router openPageWithName:@"compose_thread"
                         pageData:@{
                             @"slockReplaceCurrentPage": @YES,
                             @"slockOpenWithoutAnimation": @YES,
                         }
                       controller:pendingTask];
    XCTAssertEqual(navigation.replacementCount, 1u,
                   @"Detached source with slockOpenWithoutAnimation must not replace another controller on the stack");

    // Ordinary slockReplaceCurrentPage (without slockOpenWithoutAnimation) from an embedded child
    // controller keeps replacing the top stack entry so container-hosted flows (auth/OAuth/gates) stay intact.
    UIViewController *embeddedChild = [UIViewController new];
    [coveringPage addChildViewController:embeddedChild];
    [embeddedChild didMoveToParentViewController:coveringPage];
    XCTAssertEqual(embeddedChild.navigationController, navigation);
    XCTAssertEqual([navigation.viewControllers indexOfObjectIdenticalTo:embeddedChild], NSNotFound);

    [self.router openPageWithName:@"compose_slock_app_shell"
                         pageData:@{@"slockReplaceCurrentPage": @YES}
                       controller:embeddedChild];
    XCTAssertEqual(navigation.replacementCount, 2u);
    XCTAssertTrue(navigation.lastRequestedAnimation);
    XCTAssertEqual(navigation.viewControllers.count, 3u);
    XCTAssertNotEqual(navigation.topViewController, coveringPage,
                      @"Ordinary replaceCurrent from an embedded child controller must still replace the top entry");
    XCTAssertTrue(((RaftPageViewController *)navigation.topViewController).raftIsAppShellPage);
}

- (void)testReplaceShellRootReplacesExistingAppShellAndChooserWithSingleRoot {
    RaftPageViewController *oldShell = [self renderPage:@"compose_slock_app_shell"];
    RaftPageViewController *chooseServer = [self renderPage:@"compose_auth_choose_server"];
    RaftRouterRecordingNavigationController *navigation =
        [self navigationWithPages:@[oldShell, chooseServer]];

    [self.router openPageWithName:@"compose_slock_app_shell"
                         pageData:@{
                             @"slockReplaceCurrentPage": @YES,
                             @"slockReplaceShellRoot": @YES,
                             @"slockShellServerId": @"server-b",
                         }
                       controller:chooseServer];
    XCTAssertEqual(navigation.replacementCount, 1u);
    XCTAssertEqual(navigation.pushCount, 0u);
    XCTAssertEqual(navigation.viewControllers.count, 1u,
                   @"Replacing shell root after creating/selecting a server must not stack two AppShells");
    RaftPageViewController *newShell = (RaftPageViewController *)navigation.topViewController;
    XCTAssertNotEqual(newShell, oldShell);
    XCTAssertNotEqual(newShell, chooseServer);
    XCTAssertTrue(newShell.raftIsAppShellPage);
}

- (void)testViewWillDisappearSkipsCoverHookWhenPresentingSafariViewController {
    RaftStubPresentedPageViewController *page =
        [[RaftStubPresentedPageViewController alloc] initWithPageName:@"compose_settings_computers" pageData:@{}];
    __block NSUInteger coverCount = 0;
    page.raftPageWillBeCoveredHook = ^{
        coverCount += 1;
    };

    // Presenting SFSafariViewController (e.g. runtime OAuth "Open Sign-in Page") must keep the
    // page's open sign-in dialog and Computer login session alive until the user closes Safari.
    page.stubPresentedViewController =
        [[SFSafariViewController alloc] initWithURL:[NSURL URLWithString:@"https://accounts.google.com/o/oauth2/v2/auth"]];
    [page viewWillDisappear:NO];
    XCTAssertEqual(coverCount, 0u,
                   @"Presenting SFSafariViewController must not fire raftPageWillBeCoveredHook");

    // A normal navigation push (no Safari modal) and a non-Safari modal presentation still fire it.
    page.stubPresentedViewController = nil;
    [page viewWillDisappear:NO];
    XCTAssertEqual(coverCount, 1u,
                   @"A normal page cover must still fire raftPageWillBeCoveredHook");

    page.stubPresentedViewController = [UINavigationController new];
    [page viewWillDisappear:NO];
    XCTAssertEqual(coverCount, 2u,
                   @"A non-Safari modal presentation must still fire raftPageWillBeCoveredHook");
}

- (void)testNativeNavBarInterceptsBackDisablesSystemInteractivePopGestureUntilCleared {
    RaftPageViewController *page = [self renderPage:@"compose_thread"];
    XCTAssertFalse([page raftShouldEnableSystemInteractivePopGesture],
                   @"Pages without native nav bar rely on FDFullscreenPopGesture, not system edge pop");

    page.raftUsesNativeNavBar = YES;
    XCTAssertTrue([page raftShouldEnableSystemInteractivePopGesture],
                  @"Normal pushed native-nav pages allow UIKit interactive pop");

    page.raftNativeNavBarInterceptsBack = YES;
    XCTAssertFalse([page raftShouldEnableSystemInteractivePopGesture],
                   @"Intercepting Back (e.g. open phone Tasks/Files cover) must yield system edge pop to the cover");

    page.raftNativeNavBarInterceptsBack = NO;
    XCTAssertTrue([page raftShouldEnableSystemInteractivePopGesture],
                  @"Clearing Back interception must restore UIKit interactive pop");
}

@end
