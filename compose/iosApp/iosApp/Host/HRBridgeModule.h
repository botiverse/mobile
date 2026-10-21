#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN

/** Bridge call argument keys: the params (JSON text or dictionary) and the optional result callback. */
FOUNDATION_EXTERN NSString *const RaftBridgeParamKey;
FOUNDATION_EXTERN NSString *const RaftBridgeCallbackKey;
/** Result callback of an asynchronous bridge method (JSON-compatible object, string, or nil). */
typedef void (^RaftBridgeCallback)(id _Nullable result);

/**
 * The native side of the `HRBridgeModule` bridge protocol (shared `BridgeModule` /
 * `BridgeWireHostCapabilities`), one instance per page. Each method `-name:(NSDictionary *)args`
 * reads its params from args[RaftBridgeParamKey] and answers through args[RaftBridgeCallbackKey];
 * the synchronous methods ([HRBridgeModule isSynchronousMethod:]) return their value instead.
 * Driven by RaftCmpBridgeHost.
 */
@interface HRBridgeModule : NSObject

/**
 * The page this module belongs to (a RaftPageViewController): presentation anchor, navigation
 * stack, share-image capture root and the owner of page-bound native state (adaptive navigation,
 * conversation forward gesture). Weak: the page owns its bridge.
 */
@property (nonatomic, weak, nullable) UIViewController *raftHostController;

/** Methods answered synchronously on the calling thread (cheap and thread-safe). */
+ (BOOL)isSynchronousMethod:(NSString *)method;

@end

FOUNDATION_EXTERN void RaftIosDiagnosticsRecord(NSString *level, NSString *tag, NSString *message);
FOUNDATION_EXTERN void RaftIosDiagnosticsRecordWithContext(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
);
FOUNDATION_EXTERN void RaftIosDiagnosticsRecordCriticalSynchronously(NSString *tag, NSString *message);
FOUNDATION_EXTERN void RaftIosDiagnosticsRecordUnhandledException(
    NSString *pageName,
    NSInteger mode,
    NSString *reason,
    NSString *stack
);
FOUNDATION_EXTERN void RaftIosInstallHandsReporting(void);
/** Installs the bridge-free temporary-file retirement handler used by shared draft persistence. */
FOUNDATION_EXTERN void RaftIosInstallThreadComposerTemporaryFileRetirement(void);
FOUNDATION_EXTERN NSArray<NSString *> *RaftIosPrepareDiagnosticsAttachmentPaths(NSString *reason);
FOUNDATION_EXTERN NSArray<NSString *> *RaftIosPrepareDiagnosticsAttachmentPathsForCrash(int64_t crashAtMillis);
FOUNDATION_EXTERN NSString * _Nullable RaftIosPrepareDiagnosticsCrashAttachmentPath(void);
/**
 * Classifies why an attachment share had no presenter. Pure function so it can be
 * unit-tested without a live app/scene. Returns "detached_root" when the render
 * page already left the window, "scene_inactive" when no foreground-active
 * UIWindowScene exists, otherwise "no_controller".
 */
FOUNDATION_EXTERN NSString *RaftShareControllerFailureReason(
    BOOL hasRootViewWindow,
    BOOL hasForegroundActiveScene
);
/**
 * Picks which controller to present the share sheet on, mirroring Signal's
 * frontmost-or-captured order: prefer the presenter captured at tap time, else
 * the current frontmost controller, else nil. Pure function for tests.
 */
FOUNDATION_EXTERN UIViewController * _Nullable RaftSharePresenterController(
    UIViewController * _Nullable capturedPresenter,
    UIViewController * _Nullable frontmostPresenter
);
/**
 * Sanitizes a single path component for the persistent attachment store key.
 * Pure function for tests.
 */
FOUNDATION_EXTERN NSString *RaftAttachmentSafeComponent(NSString *raw);
/**
 * Builds the persistent attachment cache key `<ownerKey>-<safe(attachmentId)>`
 * (empty owner yields just the id).
 */
FOUNDATION_EXTERN NSString *RaftAttachmentCacheKey(NSString *ownerKey, NSString *attachmentId);

NS_ASSUME_NONNULL_END
