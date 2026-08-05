#import <UIKit/UIKit.h>
#import <OpenKuiklyIOSRender/KuiklyRenderViewExportProtocol.h>

NS_ASSUME_NONNULL_BEGIN

@protocol SlockNativeAdaptiveTabBarHosting <NSObject>

- (BOOL)slock_updateAdaptiveTabKeys:(NSArray<NSString *> *)keys
                             labels:(NSArray<NSString *> *)labels
                              icons:(NSArray<NSString *> *)icons
                            enabled:(NSArray<NSString *> *)enabled
                        selectedKey:(NSString *)selectedKey
                     selectCallback:(nullable KuiklyRenderCallback)selectCallback;

@end

@interface SlockNativeFloatingTabBarView : UIView <KuiklyRenderViewExportProtocol>

@end

NS_ASSUME_NONNULL_END
