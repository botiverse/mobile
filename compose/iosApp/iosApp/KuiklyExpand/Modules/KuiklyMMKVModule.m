#import "KuiklyMMKVModule.h"

#import <MMKV/MMKV.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>

static NSDictionary *SlockMMKVDictionaryFromValue(id value) {
    if ([value isKindOfClass:NSDictionary.class]) {
        return (NSDictionary *)value;
    }
    if (![value isKindOfClass:NSString.class]) {
        return @{};
    }
    NSString *text = (NSString *)value;
    NSDictionary *kuiklyDictionary = [text hr_stringToDictionary];
    if ([kuiklyDictionary isKindOfClass:NSDictionary.class]) {
        return kuiklyDictionary;
    }
    NSData *data = [text dataUsingEncoding:NSUTF8StringEncoding];
    if (!data) {
        return @{};
    }
    id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [object isKindOfClass:NSDictionary.class] ? object : @{};
}

static NSDictionary *SlockMMKVParams(NSDictionary *args) {
    return SlockMMKVDictionaryFromValue(args[KR_PARAM_KEY]);
}

static NSString *SlockMMKVString(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return (NSString *)value;
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [value stringValue];
    }
    return @"";
}

static BOOL SlockMMKVBool(id value) {
    if ([value isKindOfClass:NSNumber.class]) {
        return [(NSNumber *)value boolValue];
    }
    NSString *text = [SlockMMKVString(value) lowercaseString];
    return [text isEqualToString:@"true"] || [text isEqualToString:@"1"] || [text isEqualToString:@"yes"];
}

@implementation KuiklyMMKVModule

+ (void)load {
    [self ensureMMKVInitialized];
}

- (instancetype)init {
    self = [super init];
    if (self) {
        [KuiklyMMKVModule ensureMMKVInitialized];
    }
    return self;
}

+ (void)ensureMMKVInitialized {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        [MMKV initializeMMKV:nil];
    });
}

- (MMKV *)storeForMmapID:(NSString *)mmapID {
    [KuiklyMMKVModule ensureMMKVInitialized];
    if (mmapID.length == 0) {
        return [MMKV defaultMMKV];
    }
    return [MMKV mmkvWithID:mmapID] ?: [MMKV defaultMMKV];
}

- (NSString *)encode:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    NSString *key = SlockMMKVString(params[@"key"]);
    if (key.length == 0) {
        return @"";
    }
    MMKV *store = [self storeForMmapID:SlockMMKVString(params[@"mmapID"])];
    NSString *type = SlockMMKVString(params[@"type"]);
    id value = params[@"value"];
    if ([type isEqualToString:@"string"]) {
        [store setString:SlockMMKVString(value) forKey:key];
    } else if ([type isEqualToString:@"int"] || [type isEqualToString:@"long"]) {
        [store setInt64:[value longLongValue] forKey:key];
    } else if ([type isEqualToString:@"float"] || [type isEqualToString:@"double"]) {
        [store setDouble:[value doubleValue] forKey:key];
    } else if ([type isEqualToString:@"bool"]) {
        [store setBool:SlockMMKVBool(value) forKey:key];
    } else if ([type isEqualToString:@"bytes"]) {
        NSData *data = [[NSData alloc] initWithBase64EncodedString:SlockMMKVString(value) options:0];
        if (data) {
            [store setData:data forKey:key];
        }
    }
    return @"";
}

- (NSString *)decode:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    NSString *key = SlockMMKVString(params[@"key"]);
    if (key.length == 0) {
        return @"";
    }
    MMKV *store = [self storeForMmapID:SlockMMKVString(params[@"mmapID"])];
    NSString *type = SlockMMKVString(params[@"type"]);
    id defaultValue = params[@"defaultValue"];
    if ([type isEqualToString:@"string"]) {
        return [store getStringForKey:key defaultValue:SlockMMKVString(defaultValue)] ?: SlockMMKVString(defaultValue);
    }
    if ([type isEqualToString:@"int"] || [type isEqualToString:@"long"]) {
        return [@([store getInt64ForKey:key defaultValue:[defaultValue longLongValue]]) stringValue];
    }
    if ([type isEqualToString:@"float"] || [type isEqualToString:@"double"]) {
        return [@([store getDoubleForKey:key defaultValue:[defaultValue doubleValue]]) stringValue];
    }
    if ([type isEqualToString:@"bool"]) {
        return [store getBoolForKey:key defaultValue:SlockMMKVBool(defaultValue)] ? @"true" : @"false";
    }
    if ([type isEqualToString:@"bytes"]) {
        NSData *data = [store getDataForKey:key];
        return data ? [data base64EncodedStringWithOptions:0] : @"";
    }
    return @"";
}

- (NSString *)containsKey:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    NSString *key = SlockMMKVString(params[@"key"]);
    if (key.length == 0) {
        return @"false";
    }
    return [[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] containsKey:key] ? @"true" : @"false";
}

- (NSString *)removeValueForKey:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    NSString *key = SlockMMKVString(params[@"key"]);
    if (key.length > 0) {
        MMKV *store = [self storeForMmapID:SlockMMKVString(params[@"mmapID"])];
        [store removeValueForKey:key];
    }
    return @"";
}

- (NSString *)removeValuesForKeys:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    NSArray *keys = [params[@"keys"] isKindOfClass:NSArray.class] ? params[@"keys"] : @[];
    [[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] removeValuesForKeys:keys];
    return @"";
}

- (NSString *)allKeys:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];
    NSArray *keys = [[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] allKeys] ?: @[];
    if (callback) {
        callback(@{ @"keys": [keys componentsJoinedByString:@","] });
    }
    return @"";
}

- (NSString *)count:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    NSUInteger count = [[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] count];
    return [@(count) stringValue];
}

- (NSString *)totalSize:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    return [@([[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] totalSize]) stringValue];
}

- (NSString *)actualSize:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    return [@([[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] actualSize]) stringValue];
}

- (NSString *)clearAll:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    [[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] clearAll];
    return @"";
}

- (NSString *)trim:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    [[self storeForMmapID:SlockMMKVString(params[@"mmapID"])] trim];
    return @"";
}

- (NSString *)batchEncode:(NSDictionary *)args {
    NSDictionary *params = SlockMMKVParams(args);
    NSArray *entries = [params[@"entries"] isKindOfClass:NSArray.class] ? params[@"entries"] : @[];
    NSMutableDictionary *entryArgs = [params mutableCopy];
    for (id entry in entries) {
        if (![entry isKindOfClass:NSDictionary.class]) {
            continue;
        }
        [entryArgs addEntriesFromDictionary:(NSDictionary *)entry];
        [self encode:@{ KR_PARAM_KEY: entryArgs }];
    }
    return @"";
}

@end
