#import <UIKit/UIKit.h>
#import <shared/shared.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * HostCapabilities transport of one CMP page (Kotlin `RaftCmpIosBridge`).
 *
 * Forwards each call to the page's own HRBridgeModule with the bridge argument shape
 * (RaftBridgeParamKey / RaftBridgeCallbackKey). Asynchronous methods run on the main thread;
 * the synchronous ones (+[HRBridgeModule isSynchronousMethod:]) answer on the calling thread.
 * [hostController] is the page (RaftPageViewController) the module presents from and navigates.
 */
@interface RaftCmpBridgeHost : NSObject <SharedRaftCmpIosBridge>

/** The page this bridge belongs to. Weak: the page owns its bridge (through the CMP host). */
@property (nonatomic, weak, nullable) UIViewController *hostController;

@end

NS_ASSUME_NONNULL_END
