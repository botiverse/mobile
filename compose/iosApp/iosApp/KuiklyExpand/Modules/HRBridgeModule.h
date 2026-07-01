#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KRBaseModule.h>
NS_ASSUME_NONNULL_BEGIN

@interface HRBridgeModule : KRBaseModule

@end

FOUNDATION_EXTERN void SlockIosDiagnosticsRecord(NSString *level, NSString *tag, NSString *message);

NS_ASSUME_NONNULL_END
