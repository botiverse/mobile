#import "SlockQuiverDeviceId.h"

static NSString *const SlockQuiverDeviceIdDefaultsKey = @"quiver_device_id";

@implementation SlockQuiverDeviceId

+ (NSString *)deviceId {
    static NSString *cached = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
        NSString *stored = [defaults stringForKey:SlockQuiverDeviceIdDefaultsKey];
        if (stored.length == 0) {
            stored = NSUUID.UUID.UUIDString.lowercaseString;
            [defaults setObject:stored forKey:SlockQuiverDeviceIdDefaultsKey];
        }
        cached = stored;
    });
    return cached;
}

@end
