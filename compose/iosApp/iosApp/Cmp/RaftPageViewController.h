#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

/**
 * The page host on iOS: one per pushed/presented page (the former KuiklyRenderViewController).
 *
 * Hosts the page's Compose Multiplatform view controller as a child (created by
 * `SlockPageControllerFactory` and attached with -slockAttachContentController:sendEvent:) and keeps
 * the native host duties: the pageData host context (time, pixel safe areas, window id), live
 * window insets, adaptive pane facts, theme changes, the status bar style, the dormant adaptive
 * navigation transaction and the conversation forward gesture admission bit. Hardware keys (Tab
 * focus traversal) are handled by the Compose controller itself. Host -> page events go through the CMP page's
 * `RaftHostEvents` (pager events, the Kuikly `sendWithEvent` counterpart).
 */
@interface RaftPageViewController : UIViewController

@property (nonatomic, copy, readonly) NSString *slockPageName;
/** The page's pageData with the host context merged in (what the CMP page is created with). */
@property (nonatomic, copy, readonly) NSDictionary *slockPageData;
/** Native admission bit only; the identity-only Forward entry remains in the shared store. */
@property (nonatomic, assign) BOOL slockConversationForwardGestureEnabled;
@property (nonatomic, assign, readonly) BOOL slockIsAppShellPage;
@property (nonatomic, assign, readonly) BOOL slockIsLoginPage;

/**
 * @param pageName the page name (the former Kuikly `@Page` name)
 * @param pageData the page's pageData; host context keys are added when missing
 */
- (instancetype)initWithPageName:(NSString *)pageName pageData:(NSDictionary *)pageData NS_DESIGNATED_INITIALIZER;
- (instancetype)initWithNibName:(nullable NSString *)nibNameOrNil bundle:(nullable NSBundle *)nibBundleOrNil NS_UNAVAILABLE;
- (nullable instancetype)initWithCoder:(NSCoder *)coder NS_UNAVAILABLE;
- (instancetype)init NS_UNAVAILABLE;

/**
 * Installs the page content once, before the controller is shown: [content] becomes a child view
 * controller filling this controller's view (embedded when the view loads), and [sendEvent]
 * delivers host -> page events (pager events) to it.
 */
- (void)slockAttachContentController:(UIViewController *)content
                           sendEvent:(void (^)(NSString *event, NSDictionary *data))sendEvent
    NS_SWIFT_NAME(attachContent(_:sendEvent:));

/** Host -> page event (a pager event on the CMP page). No-op before content is attached. */
- (void)slockSendEvent:(NSString *)event data:(NSDictionary *)data;

/** Page -> host: the page's resolved appearance (drives the status bar style). */
- (void)slockSetDarkTheme:(BOOL)dark;

/** Dormant A4 bridge: prepare/commit/cancel. Only this page's own bridge module may call it. */
- (NSDictionary *)slockAdaptiveNavigation:(NSDictionary *)params;

/** Emits an intent-only pager event; shared code revalidates and rebuilds the route. */
- (void)slockRequestConversationForward;

@end

NS_ASSUME_NONNULL_END
