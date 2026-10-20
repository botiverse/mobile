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

NS_ASSUME_NONNULL_END
