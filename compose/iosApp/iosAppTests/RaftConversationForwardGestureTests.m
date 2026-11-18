#import <XCTest/XCTest.h>
#import <UIKit/UIGestureRecognizerSubclass.h>
#import "../iosApp/Cmp/RaftPageViewController.h"
#import "../iosApp/FDFullscreenPopGesture/UINavigationController+FDFullscreenPopGesture.h"

// The iPhone conversation Forward gesture across a real push / pop of the FD navigation hook:
// the shell's admission bit must reach the navigation controller's right-edge recognizer again
// once the shell is back on top, and the shared shell must be told it is back (the signal that
// offers the conversation just left to Forward). Page views are never loaded: these tests do not
// render Compose or synthesize a touch.
@interface RaftConversationForwardGestureTests : XCTestCase
@property (nonatomic, strong) RaftPageViewController *shell;
@property (nonatomic, strong) UINavigationController *navigation;
@property (nonatomic, strong) NSMutableArray<NSString *> *shellEvents;
@end

@implementation RaftConversationForwardGestureTests

- (void)setUp {
    [super setUp];
    self.shellEvents = [NSMutableArray array];
    self.shell = [[RaftPageViewController alloc] initWithPageName:@"compose_slock_app_shell" pageData:@{}];
    __weak typeof(self) weakSelf = self;
    [self.shell raftAttachContentController:[UIViewController new]
                                  sendEvent:^(NSString *event, __unused NSDictionary *data) {
        [weakSelf.shellEvents addObject:event];
    }];
    self.navigation = [[UINavigationController alloc] initWithRootViewController:self.shell];
    self.navigation.view.frame = CGRectMake(0, 0, 402, 874);
}

- (void)tearDown {
    self.navigation = nil;
    self.shell = nil;
    self.shellEvents = nil;
    [super tearDown];
}

- (BOOL)forwardRecognizerLive {
    RaftConversationForwardPanGestureRecognizer *recognizer = self.navigation.fd_conversationForwardGestureRecognizer;
    return recognizer.enabled && recognizer.view == self.navigation.view;
}

- (void)openAndCloseConversation {
    RaftPageViewController *conversation =
        [[RaftPageViewController alloc] initWithPageName:@"compose_thread" pageData:@{}];
    [self.navigation pushViewController:conversation animated:NO];
    XCTAssertEqual(self.navigation.topViewController, conversation);
    XCTAssertFalse([self forwardRecognizerLive], @"No Forward recognizer while a conversation is on top");
    [self.navigation popViewControllerAnimated:NO];
    XCTAssertEqual(self.navigation.topViewController, self.shell);
    // UIKit calls viewDidAppear only in a window; this is the step it runs for the shell.
    [self.shell raftAppShellDidAppear];
}

- (void)testBackOnTheShellReArmsTheForwardRecognizerAndTellsTheSharedShell {
    self.shell.raftConversationForwardGestureEnabled = YES;
    XCTAssertTrue([self forwardRecognizerLive]);

    [self openAndCloseConversation];

    XCTAssertTrue([self forwardRecognizerLive],
                  @"The push detached the recognizer; the shell's own bit must re-attach it on return");
    XCTAssertTrue([self.shellEvents containsObject:@"slockAppShellHostDidAppear"],
                  @"Shared promotes the conversation just left to Forward on this event");
}

// QA run 44356101019 / artin on an iPhone: a UIScreenEdgePanGestureRecognizer never began for a
// swipe that started 2 pt from the right edge (it stayed `possible`, or `failed` while Compose's
// recognizers were `possible`). Forward is a plain pan admitted by where the touch starts, and no
// other recognizer (Compose's touch recognizer included) may prevent it.
- (void)testForwardRecognizerIsAnEdgeAdmittedPanThatNothingPrevents {
    self.shell.raftConversationForwardGestureEnabled = YES;
    RaftConversationForwardPanGestureRecognizer *recognizer = self.navigation.fd_conversationForwardGestureRecognizer;
    XCTAssertFalse([recognizer isKindOfClass:UIScreenEdgePanGestureRecognizer.class]);
    XCTAssertTrue([recognizer isKindOfClass:UIPanGestureRecognizer.class]);
    XCTAssertEqual(recognizer.maximumNumberOfTouches, 1u);
    XCTAssertFalse(recognizer.cancelsTouchesInView);
    UIPanGestureRecognizer *other = [[UIPanGestureRecognizer alloc] init];
    XCTAssertFalse([recognizer canBePreventedByGestureRecognizer:other]);
    XCTAssertNotNil(recognizer.delegate);
}

- (void)testSettingOffKeepsTheRecognizerDetachedAfterReturning {
    self.shell.raftConversationForwardGestureEnabled = NO;

    [self openAndCloseConversation];

    XCTAssertFalse([self forwardRecognizerLive], @"Off: returning to Home never arms Forward");
}

- (void)testOnlyTheShellSendsTheReturnSignal {
    NSMutableArray<NSString *> *pageEvents = [NSMutableArray array];
    RaftPageViewController *conversation =
        [[RaftPageViewController alloc] initWithPageName:@"compose_thread" pageData:@{}];
    [conversation raftAttachContentController:[UIViewController new]
                                    sendEvent:^(NSString *event, __unused NSDictionary *data) {
        [pageEvents addObject:event];
    }];
    [self.navigation pushViewController:conversation animated:NO];

    [conversation raftAppShellDidAppear];

    XCTAssertFalse([pageEvents containsObject:@"slockAppShellHostDidAppear"]);
    XCTAssertFalse([self forwardRecognizerLive]);
}

@end
