// iPhone Duo simulator: open or close the device by asking SpringBoard's display tool service to
// swap displays (the hosted runner has no Simulator.app, whose Device menu does this). Built for
// the simulator and run inside it with `xcrun simctl spawn`. Store screenshots only.
//   duo_swap dump   - list SpringBoardServices' display tool classes and their methods
//   duo_swap swap   - swap the primary display (folded <-> open)
#import <Foundation/Foundation.h>
#import <dlfcn.h>
#import <objc/message.h>
#import <objc/runtime.h>

static void dumpClass(Class c) {
    printf("class %s : %s\n", class_getName(c), class_getSuperclass(c) ? class_getName(class_getSuperclass(c)) : "-");
    for (int meta = 0; meta < 2; meta++) {
        Class t = meta ? object_getClass((id)c) : c;
        unsigned n = 0;
        Method *ms = class_copyMethodList(t, &n);
        for (unsigned i = 0; i < n; i++) {
            printf("  %c%s %s\n", meta ? '+' : '-', sel_getName(method_getName(ms[i])), method_getTypeEncoding(ms[i]));
        }
        free(ms);
    }
    unsigned pn = 0;
    objc_property_t *ps = class_copyPropertyList(c, &pn);
    for (unsigned i = 0; i < pn; i++) printf("  @property %s %s\n", property_getName(ps[i]), property_getAttributes(ps[i]));
    free(ps);
}

static NSArray<Class> *interestingClasses(void) {
    NSMutableArray *out = [NSMutableArray array];
    int n = objc_getClassList(NULL, 0);
    Class *all = (Class *)malloc(sizeof(Class) * n);
    n = objc_getClassList(all, n);
    for (int i = 0; i < n; i++) {
        NSString *name = @(class_getName(all[i]));
        if ([name rangeOfString:@"DisplayTool"].location != NSNotFound ||
            ([name hasPrefix:@"SBS"] && ([name rangeOfString:@"Swap"].location != NSNotFound || [name rangeOfString:@"Hinge"].location != NSNotFound ||
                                         [name rangeOfString:@"Fold"].location != NSNotFound))) {
            [out addObject:all[i]];
        }
    }
    free(all);
    return out;
}

static void trySet(id obj, NSString *setter, double value) {
    SEL sel = NSSelectorFromString(setter);
    if (![obj respondsToSelector:sel]) return;
    const char *type = [obj methodSignatureForSelector:sel].numberOfArguments > 2 ? [[obj methodSignatureForSelector:sel] getArgumentTypeAtIndex:2] : "";
    printf("set %s (%s) = %g\n", setter.UTF8String, type, value);
    if (type[0] == 'd') ((void (*)(id, SEL, double))objc_msgSend)(obj, sel, value);
    else if (type[0] == 'f') ((void (*)(id, SEL, float))objc_msgSend)(obj, sel, (float)value);
    else if (type[0] == 'B' || type[0] == 'c') ((void (*)(id, SEL, BOOL))objc_msgSend)(obj, sel, value != 0);
    else ((void (*)(id, SEL, long))objc_msgSend)(obj, sel, (long)value);
}

int main(int argc, char **argv) {
    @autoreleasepool {
        dlopen("/System/Library/PrivateFrameworks/SpringBoardServices.framework/SpringBoardServices", RTLD_NOW);
        NSString *cmd = argc > 1 ? @(argv[1]) : @"dump";
        NSArray<Class> *classes = interestingClasses();
        if ([cmd isEqualToString:@"dump"]) {
            for (Class c in classes) dumpClass(c);
            return 0;
        }
        SEL swapSel = NSSelectorFromString(@"swapDisplayWithOptions:completion:");
        Class serviceClass = nil, optionsClass = nil;
        for (Class c in classes) {
            if (!serviceClass && class_getInstanceMethod(c, swapSel)) serviceClass = c;
            NSString *name = @(class_getName(c));
            if (!optionsClass && [name rangeOfString:@"Options"].location != NSNotFound &&
                ([name rangeOfString:@"Swap"].location != NSNotFound || [name rangeOfString:@"DisplayTool"].location != NSNotFound)) optionsClass = c;
        }
        printf("service=%s options=%s\n", serviceClass ? class_getName(serviceClass) : "nil", optionsClass ? class_getName(optionsClass) : "nil");
        if (!serviceClass) return 2;
        id service = [[serviceClass alloc] init];
        id options = optionsClass ? [[optionsClass alloc] init] : nil;
        double duration = argc > 2 ? atof(argv[2]) : 0.5;
        trySet(options, @"setDuration:", duration);
        trySet(options, @"setWaitForCompletion:", 1);
        printf("options=%s\n", [[options description] UTF8String]);
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        __block int status = 0;
        void (^completion)(id) = ^(id result) {
            printf("completion: %s\n", result ? [[result description] UTF8String] : "nil");
            if ([result isKindOfClass:NSError.class]) status = 1;
            dispatch_semaphore_signal(done);
        };
        ((void (*)(id, SEL, id, id))objc_msgSend)(service, swapSel, options, completion);
        long timedOut = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC));
        printf("swap returned (timedOut=%ld status=%d)\n", timedOut, status);
        return timedOut ? 3 : status;
    }
}
