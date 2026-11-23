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
/// Hands' deferred launch work (pending crash uploads + device ping); called by
/// RaftIosHandsLaunchUploads only once every signed-in server answered privacy mode off.
FOUNDATION_EXTERN void RaftIosRunHandsLaunchUploads(void);
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
/**
 * Picks the cached file to reuse for an export, given the files already in the
 * persistent store directory for that attachment.
 *
 * The export path (Save to Files / Save to Photos) downloads the attachment again
 * even when a durable local copy already exists. This decides which of the stored
 * files may be reused instead, so the download can be skipped:
 * - a file whose name matches `requestedFileName` (after the same sanitizing the
 *   store applies) wins;
 * - otherwise the first stored file is used, since the store holds one file per
 *   attachment;
 * - `nil` when the directory holds no usable file, or when the stored file is
 *   still an in-progress `.part-` download.
 *
 * Pure function for tests.
 */
FOUNDATION_EXTERN NSString * _Nullable RaftAttachmentReusableExportFileName(
    NSArray<NSString *> * _Nullable storedFileNames,
    NSString * _Nullable requestedFileName);
typedef void (^RaftPickerPhaseDone)(void);

/**
 * Coordinates dismissing a presented attachment picker in parallel with asynchronous export/cache
 * work (#wg-mobile-feedback:#268): runs `completion` on `completionQueue` (defaults to main) once
 * both `dismiss` and `exportWork` have finished so opening the editor does not race the picker
 * dismissal transition into `RaftIosPageOpenGate` `open_queued`. Pure coordination helper for tests.
 */
FOUNDATION_EXTERN void RaftRunAfterPickerDismissAndWork(
    void (^ _Nullable dismiss)(RaftPickerPhaseDone _Nonnull dismissCompleted),
    void (^ _Nullable exportWork)(RaftPickerPhaseDone _Nonnull workCompleted),
    dispatch_queue_t _Nullable completionQueue,
    void (^ _Nonnull completion)(void));

/**
 * Persists a freshly exported attachment into `<storeRoot>/<ownerKey>-<safe(attachmentId)>/<safe(fileName)>`
 * so tapping "Open" on the post-save toast (or in Storage › Downloads) opens the local copy without
 * re-downloading. Moves `sourceURL` into place when `moveSource` is YES (falling back to copy across
 * volumes) via an atomic `.part-` sibling rename, then removes stale differently-named files in that
 * attachment's cache directory. Returns YES on success.
 *
 * Pure filesystem helper for tests.
 */
FOUNDATION_EXTERN BOOL RaftPersistExportedAttachmentToCache(
    NSString * _Nullable storeRoot,
    NSString * _Nullable ownerKey,
    NSString * _Nullable attachmentId,
    NSString * _Nullable fileName,
    NSURL * _Nullable sourceURL,
    BOOL moveSource);

NS_ASSUME_NONNULL_END
