#import "SlockCmpBridgeHost.h"
#import "HRBridgeModule.h"

static NSString *_Nullable SlockCmpBridgeResultJson(id _Nullable result) {
    if (result == nil || result == NSNull.null) {
        return nil;
    }
    if ([result isKindOfClass:NSString.class]) {
        return (NSString *)result;
    }
    if (![NSJSONSerialization isValidJSONObject:result]) {
        NSLog(@"[SlockCmpHost][warn] dropped non-JSON bridge result class=%@", NSStringFromClass([result class]));
        return nil;
    }
    NSData *data = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
    return data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : nil;
}

@implementation SlockCmpBridgeHost {
    HRBridgeModule *_module;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _module = [[HRBridgeModule alloc] init];
    }
    return self;
}

- (void)callMethod:(NSString *)method
        paramsJson:(NSString *)paramsJson
          callback:(id<SharedSlockCmpIosBridgeCallback> _Nullable)callback {
    dispatch_block_t invoke = ^{
        SEL selector = NSSelectorFromString([method stringByAppendingString:@":"]);
        if (![self->_module respondsToSelector:selector]) {
            NSLog(@"[SlockCmpHost][warn] bridge method not implemented: %@", method);
            [callback deliverJson:@"{\"code\":-1,\"message\":\"Method not implemented\"}"];
            return;
        }
        NSMutableDictionary *args = [NSMutableDictionary dictionaryWithObject:paramsJson forKey:KR_PARAM_KEY];
        if (callback) {
            KuiklyRenderCallback renderCallback = ^(id _Nullable result) {
                [callback deliverJson:SlockCmpBridgeResultJson(result)];
            };
            args[KR_CALLBACK_KEY] = renderCallback;
        }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
        [self->_module performSelector:selector withObject:args];
#pragma clang diagnostic pop
    };
    // Kuikly invokes async module methods on the main thread; keep that contract.
    if (NSThread.isMainThread) {
        invoke();
    } else {
        dispatch_async(dispatch_get_main_queue(), invoke);
    }
}

@end
