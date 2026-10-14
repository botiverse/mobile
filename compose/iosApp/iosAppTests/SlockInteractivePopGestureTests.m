#import <XCTest/XCTest.h>
#import "../iosApp/FDFullscreenPopGesture/UINavigationController+FDFullscreenPopGesture.h"

// Supply deterministic touch/motion input to the real navigation delegate.
// Recognition ordering is asserted through UIKit's delegate contract; these
// tests do not synthesize a device gesture or claim transition completion.
@interface SlockInteractivePopTestTouch : UITouch
@property (nonatomic, strong) UIView *hitView;
@property (nonatomic, assign) CGPoint navigationLocation;
@end

@implementation SlockInteractivePopTestTouch
- (UIView *)view { return self.hitView; }
- (CGPoint)locationInView:(UIView *)view { return self.navigationLocation; }
@end

@interface SlockInteractivePopTestPan : UIPanGestureRecognizer
@property (nonatomic, assign) CGPoint testTranslation;
@property (nonatomic, assign) CGPoint testVelocity;
@end

@implementation SlockInteractivePopTestPan
- (CGPoint)translationInView:(UIView *)view { return self.testTranslation; }
- (CGPoint)velocityInView:(UIView *)view { return self.testVelocity; }
@end

@interface SlockInteractivePopGestureTests : XCTestCase
@property (nonatomic, strong) UINavigationController *navigation;
@property (nonatomic, strong) UIViewController *page;
@property (nonatomic, strong) SlockInteractivePopTestPan *pan;
@property (nonatomic, strong) id<UIGestureRecognizerDelegate> popDelegate;
@end

@implementation SlockInteractivePopGestureTests

- (void)setUp {
    [super setUp];
    self.navigation = [[UINavigationController alloc] initWithRootViewController:[UIViewController new]];
    self.navigation.view.frame = CGRectMake(0, 0, 402, 874);
    self.page = [UIViewController new];
    self.page.fd_interactivePopAllowsFullScreen = YES;
    [self.navigation pushViewController:self.page animated:NO];
    [self.navigation.view layoutIfNeeded];
    self.popDelegate = self.navigation.fd_fullscreenPopGestureRecognizer.delegate;
    XCTAssertNotNil(self.popDelegate, @"The production push hook must install its delegate");
    self.pan = [SlockInteractivePopTestPan new];
    self.pan.testTranslation = CGPointMake(30, 0);
    self.pan.testVelocity = CGPointMake(300, 0);
    [self.navigation.view addGestureRecognizer:self.pan];
}

- (void)tearDown {
    self.popDelegate = nil;
    self.pan = nil;
    self.page = nil;
    self.navigation = nil;
    [super tearDown];
}

- (BOOL)receiveTouchInView:(UIView *)view x:(CGFloat)x {
    SlockInteractivePopTestTouch *touch = [SlockInteractivePopTestTouch new];
    touch.hitView = view;
    touch.navigationLocation = CGPointMake(x, 400);
    return [self.popDelegate gestureRecognizer:self.pan shouldReceiveTouch:touch];
}

- (BOOL)contentPanWaitsForPop:(UIPanGestureRecognizer *)contentPan {
    return [self.popDelegate gestureRecognizer:self.pan
        shouldBeRequiredToFailByGestureRecognizer:contentPan];
}

- (UIScrollView *)addScrollViewWithContentSize:(CGSize)contentSize toView:(UIView *)parent {
    UIScrollView *scroll = [[UIScrollView alloc] initWithFrame:CGRectMake(0, 0, 402, 680)];
    scroll.contentInsetAdjustmentBehavior = UIScrollViewContentInsetAdjustmentNever;
    scroll.contentSize = contentSize;
    [parent addSubview:scroll];
    return scroll;
}

- (void)testAllAncestorContentPansWaitForAnAdmittedCenterBackSwipe {
    // Reproduces Activity's hierarchy: a horizontal pager at its leading edge
    // encloses a vertical list. Ordering only the inner list is insufficient.
    UIScrollView *pager = [self addScrollViewWithContentSize:CGSizeMake(1206, 696) toView:self.page.view];
    UIScrollView *list = [self addScrollViewWithContentSize:CGSizeMake(402, 238) toView:pager];
    XCTAssertTrue([self receiveTouchInView:list x:100]);
    XCTAssertTrue([self.popDelegate gestureRecognizerShouldBegin:self.pan]);
    for (UIScrollView *scroll in @[list, pager]) {
        XCTAssertTrue([self contentPanWaitsForPop:scroll.panGestureRecognizer],
                      @"Both inner list and outer pager must wait for admitted pop");
    }
}

- (void)testEdgeOnlyPagesDoNotTakeCenterPansButRetainEdgeBack {
    self.page.fd_interactivePopAllowsFullScreen = NO;
    UIScrollView *list = [self addScrollViewWithContentSize:CGSizeMake(402, 1000) toView:self.page.view];
    const CGFloat starts[] = {100, 10};
    const BOOL expected[] = {NO, YES};
    for (NSUInteger i = 0; i < 2; i++) {
        XCTAssertTrue([self receiveTouchInView:list x:starts[i]]);
        XCTAssertEqual([self.popDelegate gestureRecognizerShouldBegin:self.pan], expected[i]);
        XCTAssertEqual([self contentPanWaitsForPop:list.panGestureRecognizer], expected[i]);
    }
}

- (void)testRootAndDisabledPagesDoNotTakeContentPans {
    UIScrollView *list = [self addScrollViewWithContentSize:CGSizeMake(402, 1000) toView:self.page.view];
    self.page.fd_interactivePopDisabled = YES;
    XCTAssertTrue([self receiveTouchInView:list x:10]);
    XCTAssertFalse([self.popDelegate gestureRecognizerShouldBegin:self.pan]);
    XCTAssertFalse([self contentPanWaitsForPop:list.panGestureRecognizer]);

    [self.navigation popViewControllerAnimated:NO];
    UIViewController *root = self.navigation.topViewController;
    root.fd_interactivePopAllowsFullScreen = YES;
    [root.view addSubview:list];
    XCTAssertTrue([self receiveTouchInView:list x:10]);
    XCTAssertFalse([self.popDelegate gestureRecognizerShouldBegin:self.pan]);
    XCTAssertFalse([self contentPanWaitsForPop:list.panGestureRecognizer]);
}

- (void)testTextInputClearsPriorAdmissionAndKeepsItsGestures {
    UIScrollView *list = [self addScrollViewWithContentSize:CGSizeMake(402, 1000) toView:self.page.view];
    XCTAssertTrue([self receiveTouchInView:list x:10]);
    XCTAssertTrue([self contentPanWaitsForPop:list.panGestureRecognizer]);
    UITextField *field = [[UITextField alloc] initWithFrame:CGRectMake(0, 0, 200, 40)];
    [list addSubview:field];
    XCTAssertFalse([self receiveTouchInView:field x:10]);
    XCTAssertFalse([self.popDelegate gestureRecognizerShouldBegin:self.pan]);
    XCTAssertFalse([self contentPanWaitsForPop:list.panGestureRecognizer]);
}

- (void)testScrollableHorizontalContentKeepsRightwardMotionUntilLeadingBoundary {
    UIScrollView *scroll = [self addScrollViewWithContentSize:CGSizeMake(1206, 680) toView:self.page.view];
    scroll.contentInset = UIEdgeInsetsMake(0, 20, 0, 0);
    const CGFloat offsets[] = {-20, 100};
    const BOOL expectedPop[] = {YES, NO};
    for (NSUInteger i = 0; i < 2; i++) {
        scroll.contentOffset = CGPointMake(offsets[i], 0);
        XCTAssertTrue([self receiveTouchInView:scroll x:100]);
        XCTAssertEqual([self.popDelegate gestureRecognizerShouldBegin:self.pan], expectedPop[i],
                       @"A consuming horizontal scroller must make pop fail and release its pan");
    }
}

- (void)testNonBackMotionReleasesContentGestures {
    UIScrollView *list = [self addScrollViewWithContentSize:CGSizeMake(402, 1000) toView:self.page.view];
    const struct {
        CGPoint translation;
        CGPoint velocity;
    } motions[] = {
        { {0, 30}, {0, 300} },
        // Current vertical motion must win even after initial horizontal drift.
        { {30, 0}, {0, 300} },
        { {-30, 0}, {-300, 0} },
        // With no meaningful velocity, use the translation's direction.
        { {0, 30}, {0, 0} },
    };
    for (NSUInteger i = 0; i < sizeof(motions) / sizeof(motions[0]); i++) {
        self.pan.testTranslation = motions[i].translation;
        self.pan.testVelocity = motions[i].velocity;
        XCTAssertTrue([self receiveTouchInView:list x:100]);
        XCTAssertFalse([self.popDelegate gestureRecognizerShouldBegin:self.pan]);
    }
}

- (void)testPriorityDoesNotApplyToCustomUnrelatedOrScreenEdgeRecognizers {
    XCTAssertTrue([self receiveTouchInView:self.page.view x:100]);
    UIPanGestureRecognizer *customContentPan = [UIPanGestureRecognizer new];
    [self.page.view addGestureRecognizer:customContentPan];
    XCTAssertFalse([self contentPanWaitsForPop:customContentPan]);

    UIScrollView *scroll = [self addScrollViewWithContentSize:CGSizeMake(402, 1000) toView:self.page.view];
    UIPanGestureRecognizer *customScrollPan = [UIPanGestureRecognizer new];
    [scroll addGestureRecognizer:customScrollPan];
    XCTAssertFalse([self contentPanWaitsForPop:customScrollPan],
                   @"Only the scroll view's own pan participates, not arbitrary attached gestures");

    UIScreenEdgePanGestureRecognizer *edge = [UIScreenEdgePanGestureRecognizer new];
    [self.page.view addGestureRecognizer:edge];
    XCTAssertFalse([self contentPanWaitsForPop:edge]);
    UIView *unrelatedView = [UIView new];
    UIPanGestureRecognizer *unrelatedPan = [UIPanGestureRecognizer new];
    [unrelatedView addGestureRecognizer:unrelatedPan];
    XCTAssertFalse([self contentPanWaitsForPop:unrelatedPan]);
}

@end
