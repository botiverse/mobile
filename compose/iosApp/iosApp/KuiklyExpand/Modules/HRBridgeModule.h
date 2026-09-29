#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KRBaseModule.h>
NS_ASSUME_NONNULL_BEGIN

@interface HRBridgeModule : KRBaseModule

@end

FOUNDATION_EXTERN void SlockIosDiagnosticsRecord(NSString *level, NSString *tag, NSString *message);
FOUNDATION_EXTERN void SlockIosDiagnosticsRecordWithContext(
    NSString *level,
    NSString *tag,
    NSString *message,
    int64_t timestampMillis,
    BOOL emitConsole,
    BOOL forceCritical
);
FOUNDATION_EXTERN void SlockIosDiagnosticsRecordCriticalSynchronously(NSString *tag, NSString *message);
FOUNDATION_EXTERN void SlockIosDiagnosticsRecordUnhandledException(
    NSString *pageName,
    NSInteger mode,
    NSString *reason,
    NSString *stack
);
FOUNDATION_EXTERN void SlockIosInstallHandsReporting(void);
/** Installs the bridge-free temporary-file retirement handler used by shared draft persistence. */
FOUNDATION_EXTERN void SlockIosInstallThreadComposerTemporaryFileRetirement(void);
FOUNDATION_EXTERN NSArray<NSString *> *SlockIosPrepareDiagnosticsAttachmentPaths(NSString *reason);
FOUNDATION_EXTERN NSArray<NSString *> *SlockIosPrepareDiagnosticsAttachmentPathsForCrash(int64_t crashAtMillis);
FOUNDATION_EXTERN NSString * _Nullable SlockIosPrepareDiagnosticsCrashAttachmentPath(void);
/**
 * Classifies why an attachment share had no presenter. Pure function so it can be
 * unit-tested without a live app/scene. Returns "detached_root" when the render
 * page already left the window, "scene_inactive" when no foreground-active
 * UIWindowScene exists, otherwise "no_controller".
 */
FOUNDATION_EXTERN NSString *SlockShareControllerFailureReason(
    BOOL hasRootViewWindow,
    BOOL hasForegroundActiveScene
);
/**
 * Picks which controller to present the share sheet on, mirroring Signal's
 * frontmost-or-captured order: prefer the presenter captured at tap time, else
 * the current frontmost controller, else nil. Pure function for tests.
 */
FOUNDATION_EXTERN UIViewController * _Nullable SlockSharePresenterController(
    UIViewController * _Nullable capturedPresenter,
    UIViewController * _Nullable frontmostPresenter
);
/**
 * Sanitizes a single path component for the persistent attachment store key.
 * Pure function for tests.
 */
FOUNDATION_EXTERN NSString *SlockAttachmentSafeComponent(NSString *raw);
/**
 * Builds the persistent attachment cache key `<ownerKey>-<safe(attachmentId)>`
 * (OHOS SlockOhosPrivateAttachmentStore scheme; empty owner yields just the id).
 */
FOUNDATION_EXTERN NSString *SlockAttachmentCacheKey(NSString *ownerKey, NSString *attachmentId);

NS_ASSUME_NONNULL_END
