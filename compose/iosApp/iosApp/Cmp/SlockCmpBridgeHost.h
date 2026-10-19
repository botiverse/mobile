#import <Foundation/Foundation.h>
#import <shared/shared.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * HostCapabilities transport for Compose Multiplatform pages (Kotlin `SlockCmpIosBridge`).
 *
 * Forwards each call to a private HRBridgeModule, the same native implementation Kuikly pages use,
 * with the Kuikly module argument shape (KR_PARAM_KEY / KR_CALLBACK_KEY). The module has no Kuikly
 * root view, so presentation-dependent methods resolve the frontmost view controller of the key
 * window (HRBridgeModule's existing fallback).
 */
@interface SlockCmpBridgeHost : NSObject <SharedSlockCmpIosBridge>
@end

NS_ASSUME_NONNULL_END
