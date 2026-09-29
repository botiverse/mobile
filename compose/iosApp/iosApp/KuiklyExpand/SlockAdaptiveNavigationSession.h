#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** Main-thread, one-source transaction. No UI or product-state ownership. */
@interface SlockAdaptiveNavigationSession : NSObject
- (NSDictionary *)prepare:(NSString *)requestId context:(NSDictionary *)context
                  editor:(nullable id)editor editorState:(nullable NSDictionary *)editorState
                     now:(NSTimeInterval)now;
- (NSDictionary *)commit:(NSString *)requestId token:(NSString *)token
                 context:(NSDictionary *)context
                  editor:(nullable id)editor editorState:(nullable NSDictionary *)editorState
                     now:(NSTimeInterval)now
                 perform:(BOOL (^)(void))perform;
- (NSDictionary *)cancel:(NSString *)requestId token:(NSString *)token;
@end

NS_ASSUME_NONNULL_END
