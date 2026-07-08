#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KRBaseModule.h>
NS_ASSUME_NONNULL_BEGIN

@interface HRBridgeModule : KRBaseModule

@end

FOUNDATION_EXTERN void SlockIosDiagnosticsRecord(NSString *level, NSString *tag, NSString *message);
FOUNDATION_EXTERN void SlockIosInstallQuiverReporting(void);
FOUNDATION_EXTERN NSArray<NSString *> *SlockIosPrepareDiagnosticsAttachmentPaths(NSString *reason);
FOUNDATION_EXTERN NSArray<NSString *> *SlockIosPrepareDiagnosticsAttachmentPathsForCrash(int64_t crashAtMillis);
FOUNDATION_EXTERN NSString * _Nullable SlockIosPrepareDiagnosticsCrashAttachmentPath(void);

NS_ASSUME_NONNULL_END
