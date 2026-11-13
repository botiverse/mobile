// iPhone Duo simulator: open or close the device by asking SpringBoard's display tool service
// (com.apple.springboard.sbdisplay.service) to swap displays. The hosted runner has no
// Simulator.app, whose Device menu does this. Built for the simulator (build.sh) and run inside it
// with `xcrun simctl spawn <udid> duo_swap swap`. Store screenshots only.
//   duo_swap dump   - BoardServices' connection classes, for debugging
//   duo_swap swap [interface identifier] [mach name]
//   duo_swap inspect - how SBSLockScreenService connects
#import <Foundation/Foundation.h>
#import <dlfcn.h>
#import <objc/message.h>
#import <objc/runtime.h>

@protocol SBSDisplayToolServiceServerInterface
- (oneway void)swapDisplayWithOptions:(NSDictionary *)options completion:(void (^)(NSError *error))completion;
@end

#define MSG(ret, ...) ((ret(*)(__VA_ARGS__))objc_msgSend)

static void dumpClass(const char *name) {
    Class c = objc_getClass(name);
    if (!c) { printf("class %s: missing\n", name); return; }
    printf("class %s\n", name);
    for (int meta = 0; meta < 2; meta++) {
        unsigned n = 0;
        Method *ms = class_copyMethodList(meta ? object_getClass((id)c) : c, &n);
        for (unsigned i = 0; i < n; i++) printf("  %c%s %s\n", meta ? '+' : '-', sel_getName(method_getName(ms[i])), method_getTypeEncoding(ms[i]));
        free(ms);
    }
}

static id call(id target, const char *sel) {
    SEL s = sel_registerName(sel);
    if (![target respondsToSelector:s]) { printf("! %s does not respond to %s\n", [[target description] UTF8String], sel); return nil; }
    return MSG(id, id, SEL)(target, s);
}

/// The first BSServiceConnectionEndpoint reachable from [obj] through object ivars (depth-limited).
static id findEndpoint(id obj, int depth) {
    if (!obj || depth > 4) return nil;
    if ([obj isKindOfClass:objc_getClass("BSServiceConnectionEndpoint")]) return obj;
    if ([obj respondsToSelector:sel_registerName("endpoint")]) {
        id e = MSG(id, id, SEL)(obj, sel_registerName("endpoint"));
        if ([e isKindOfClass:objc_getClass("BSServiceConnectionEndpoint")]) return e;
    }
    for (Class c = object_getClass(obj); c && c != NSObject.class; c = class_getSuperclass(c)) {
        unsigned n = 0;
        Ivar *ivars = class_copyIvarList(c, &n);
        for (unsigned i = 0; i < n; i++) {
            if (ivar_getTypeEncoding(ivars[i])[0] != '@') continue;
            id v = object_getIvar(obj, ivars[i]);
            printf("%*s%s.%s = %s\n", depth * 2, "", class_getName(c), ivar_getName(ivars[i]), [[[v description] substringToIndex:MIN((NSUInteger)200, [[v description] length])] UTF8String]);
            id e = findEndpoint(v, depth + 1);
            if (e) { free(ivars); return e; }
        }
        free(ivars);
    }
    return nil;
}

int main(int argc, char **argv) {
    @autoreleasepool {
        setvbuf(stdout, NULL, _IONBF, 0);
        dlopen("/System/Library/PrivateFrameworks/BoardServices.framework/BoardServices", RTLD_NOW);
        dlopen("/System/Library/PrivateFrameworks/SpringBoardServices.framework/SpringBoardServices", RTLD_NOW);
        NSString *cmd = argc > 1 ? @(argv[1]) : @"dump";
        if ([cmd isEqualToString:@"inspect"]) {
            // How SpringBoardServices' own lock screen client connects: endpoint and interface.
            id lock = [[objc_getClass("SBSLockScreenService") alloc] init];
            unsigned n = 0;
            Ivar *ivars = class_copyIvarList(object_getClass(lock), &n);
            for (unsigned i = 0; i < n; i++) {
                const char *type = ivar_getTypeEncoding(ivars[i]);
                printf("ivar %s %s\n", ivar_getName(ivars[i]), type);
                if (type[0] == '@') {
                    id v = object_getIvar(lock, ivars[i]);
                    printf("  = %s\n", [[v description] UTF8String]);
                    if ([v respondsToSelector:sel_registerName("endpoint")]) printf("  endpoint = %s\n", [[MSG(id, id, SEL)(v, sel_registerName("endpoint")) description] UTF8String]);
                    if ([v respondsToSelector:sel_registerName("interface")]) printf("  interface = %s\n", [[MSG(id, id, SEL)(v, sel_registerName("interface")) description] UTF8String]);
                    if ([v respondsToSelector:sel_registerName("debugDescription")]) printf("  debug = %s\n", [[v debugDescription] UTF8String]);
                }
            }
            free(ivars);
            dumpClass("SBSLockScreenService");
            return 0;
        }
        if ([cmd isEqualToString:@"dump"]) {
            const char *names[] = {"BSServiceConnectionEndpoint", "BSServiceConnection", "BSServiceInterface", "BSObjCProtocol",
                                   "BSServiceDispatchQueue", "BSServiceQueue", "BSMutableServiceInterface", "SBSLockScreenService"};
            for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++) dumpClass(names[i]);
            return 0;
        }
        NSString *machName = argc > 3 ? @(argv[3]) : @"com.apple.frontboard.systemappservices";
        if ([machName isEqualToString:@"auto"]) {
            // SpringBoardServices' own lock screen client knows which endpoint SpringBoard's services are on.
            id lock = [[objc_getClass("SBSLockScreenService") alloc] init];
            id lockEndpoint = findEndpoint(lock, 0);
            printf("lock screen endpoint=%s\n", [[lockEndpoint description] UTF8String]);
            if ([lockEndpoint respondsToSelector:sel_registerName("machName")]) machName = MSG(id, id, SEL)(lockEndpoint, sel_registerName("machName"));
            else if ([lockEndpoint respondsToSelector:sel_registerName("targetDescription")]) machName = MSG(id, id, SEL)(lockEndpoint, sel_registerName("targetDescription"));
            if ([lock respondsToSelector:sel_registerName("invalidate")]) MSG(void, id, SEL)(lock, sel_registerName("invalidate"));
            printf("machName=%s\n", machName.UTF8String);
            dumpClass("BSServiceConnectionEndpoint");
        }
        NSString *service = @"com.apple.springboard.sbdisplay.service";
        NSString *identifier = argc > 2 ? @(argv[2]) : service;
        Class Endpoint = objc_getClass("BSServiceConnectionEndpoint");
        id endpoint = MSG(id, id, SEL, id, id, id)((id)Endpoint, sel_registerName("endpointForMachName:service:instance:"), machName, service, nil);
        printf("endpoint=%s\n", [[endpoint description] UTF8String]);
        id connection = MSG(id, id, SEL, id)((id)objc_getClass("BSServiceConnection"), sel_registerName("connectionWithEndpoint:"), endpoint);
        printf("connection=%s\n", [[connection description] UTF8String]);
        id interface = MSG(id, id, SEL, id)((id)objc_getClass("BSMutableServiceInterface"), sel_registerName("interfaceWithIdentifier:"), identifier);
        id proto = MSG(id, id, SEL, Protocol *)((id)objc_getClass("BSObjCProtocol"), sel_registerName("protocolForProtocol:"), @protocol(SBSDisplayToolServiceServerInterface));
        MSG(void, id, SEL, id)(interface, sel_registerName("setServer:"), proto);
        printf("interface=%s\n", [[interface description] UTF8String]);
        // The configuration's target queue is a plain dispatch queue (activate wraps it with the
        // service quality); replies arrive there.
        id serviceQueue = dispatch_queue_create("duo_swap", DISPATCH_QUEUE_SERIAL);
        printf("serviceQueue=%s\n", [[serviceQueue description] UTF8String]);
        MSG(void, id, SEL, id)(connection, sel_registerName("configureConnection:"), ^(id config) {
            Class Quality = objc_getClass("BSServiceQuality");
            dumpClass("BSServiceQuality");
            const char *qualities[] = {"userInitiated", "userInteractive", "utility", "background"};
            for (size_t i = 0; Quality && i < 4; i++) {
                if ([Quality respondsToSelector:sel_registerName(qualities[i])]) {
                    id quality = MSG(id, id, SEL)((id)Quality, sel_registerName(qualities[i]));
                    printf("config setServiceQuality: %s\n", [[quality description] UTF8String]);
                    MSG(void, id, SEL, id)(config, sel_registerName("setServiceQuality:"), quality);
                    break;
                }
            }
            MSG(void, id, SEL, id)(config, sel_registerName("setInterface:"), interface);
            const char *queueSetters[] = {"setTargetQueue:", "setServiceQueue:", "setQueue:"};
            for (size_t i = 0; i < 3; i++) {
                if ([config respondsToSelector:sel_registerName(queueSetters[i])]) {
                    printf("config %s\n", queueSetters[i]);
                    MSG(void, id, SEL, id)(config, sel_registerName(queueSetters[i]), serviceQueue);
                    break;
                }
            }
            MSG(void, id, SEL, id)(config, sel_registerName("setInterruptionHandler:"), ^(id c) { printf("interrupted\n"); });
            MSG(void, id, SEL, id)(config, sel_registerName("setInvalidationHandler:"), ^(id c) { printf("invalidated\n"); });
        });
        MSG(void, id, SEL)(connection, sel_registerName("activate"));
        id remote = call(connection, "remoteTarget");
        printf("remote=%s\n", [[remote description] UTF8String]);
        if (!remote) return 2;
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        __block int status = 0;
        [(id<SBSDisplayToolServiceServerInterface>)remote swapDisplayWithOptions:@{} completion:^(NSError *error) {
            printf("completion error=%s\n", error ? [[error description] UTF8String] : "nil");
            status = error ? 1 : 0;
            dispatch_semaphore_signal(done);
        }];
        // The reply comes on the main queue: spin the run loop until it does.
        long timedOut = 1;
        for (int i = 0; i < 300; i++) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
            if (dispatch_semaphore_wait(done, DISPATCH_TIME_NOW) == 0) { timedOut = 0; break; }
        }
        printf("swap returned (timedOut=%ld status=%d)\n", timedOut, status);
        MSG(void, id, SEL)(connection, sel_registerName("invalidate"));
        return timedOut ? 3 : status;
    }
}
