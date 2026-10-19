#import "SlockCmpBridgeHost.h"
#import "HRBridgeModule.h"

static NSString *_Nullable SlockCmpBridgeResultJson(id _Nullable result) {
    if (result == nil || result == NSNull.null) {
        return nil;
    }
    if ([result isKindOfClass:NSString.class]) {
        return (NSString *)result;
    }
    if ([result isKindOfClass:NSNumber.class]) {
        return [(NSNumber *)result stringValue];
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

- (UIViewController *)hostController {
    return _module.slockHostController;
}

- (void)setHostController:(UIViewController *)hostController {
    _module.slockHostController = hostController;
}

// Kotlin/Native exports SlockCmpIosBridge.call(method:paramsJson:callback:) with a mangled
// `callback_:` label (the CI crash "unrecognized selector callMethod:paramsJson:callback_:").
- (void)callMethod:(NSString *)method
        paramsJson:(NSString *)paramsJson
         callback_:(id<SharedSlockCmpIosBridgeCallback> _Nullable)callback {
    dispatch_block_t invoke = ^{
        SEL selector = NSSelectorFromString([method stringByAppendingString:@":"]);
        if (![self->_module respondsToSelector:selector]) {
            NSLog(@"[SlockCmpHost][warn] bridge method not implemented: %@", method);
            [callback deliverJson:@"{\"code\":-1,\"message\":\"Method not implemented\"}"];
            return;
        }
        NSMutableDictionary *args = [NSMutableDictionary dictionaryWithObject:paramsJson ?: @"" forKey:RaftBridgeParamKey];
        if (callback) {
            RaftBridgeCallback bridgeCallback = ^(id _Nullable result) {
                [callback deliverJson:SlockCmpBridgeResultJson(result)];
            };
            args[RaftBridgeCallbackKey] = bridgeCallback;
        }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
        // Never read the return value here: most bridge methods return void.
        [self->_module performSelector:selector withObject:args];
#pragma clang diagnostic pop
    };
    // Async bridge methods run on the main thread (the Kuikly module contract).
    if (NSThread.isMainThread) {
        invoke();
    } else {
        dispatch_async(dispatch_get_main_queue(), invoke);
    }
}

- (NSString *_Nullable)callSyncMethod:(NSString *)method paramsJson:(NSString *)paramsJson {
    if (![HRBridgeModule isSynchronousMethod:method]) {
        return nil;
    }
    SEL selector = NSSelectorFromString([method stringByAppendingString:@":"]);
    if (![_module respondsToSelector:selector]) {
        return nil;
    }
    NSDictionary *args = @{ RaftBridgeParamKey: paramsJson ?: @"" };
    // Every synchronous method returns an object (id / NSString *).
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
    id result = [_module performSelector:selector withObject:args];
#pragma clang diagnostic pop
    return SlockCmpBridgeResultJson(result) ?: @"";
}

@end
