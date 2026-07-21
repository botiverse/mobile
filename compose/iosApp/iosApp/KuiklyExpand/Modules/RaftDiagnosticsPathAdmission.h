#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

static inline BOOL RaftDiagnosticsIsSafeArchivePathComponent(NSString *component) {
    if (component.length == 0 || [component isEqualToString:@"."] || [component isEqualToString:@".."] ||
        [component containsString:@"/"] || [component containsString:@"\\"] ||
        [component.lowercaseString containsString:@"slock"]) {
        return NO;
    }
    return YES;
}

static inline NSString *_Nullable RaftDiagnosticsCanonicalDescendantRegularFilePath(
    NSFileManager *fileManager,
    NSString *root,
    NSString *path
) {
    NSString *standardRoot = root.stringByStandardizingPath;
    NSString *standardPath = path.stringByStandardizingPath;
    NSString *standardRootPrefix = [standardRoot stringByAppendingString:@"/"];
    if (![standardPath hasPrefix:standardRootPrefix]) {
        return nil;
    }
    if ([fileManager destinationOfSymbolicLinkAtPath:standardRoot error:nil] != nil) {
        return nil;
    }
    NSString *relativePath = [standardPath substringFromIndex:standardRootPrefix.length];
    NSArray<NSString *> *components = [relativePath componentsSeparatedByString:@"/"];
    NSString *cursor = standardRoot;
    for (NSUInteger index = 0; index < components.count; index++) {
        NSString *component = components[index];
        if (!RaftDiagnosticsIsSafeArchivePathComponent(component)) {
            return nil;
        }
        cursor = [cursor stringByAppendingPathComponent:component];
        if ([fileManager destinationOfSymbolicLinkAtPath:cursor error:nil] != nil) {
            return nil;
        }
        NSDictionary<NSFileAttributeKey, id> *attributes = [fileManager attributesOfItemAtPath:cursor error:nil];
        NSString *expectedType = index + 1 == components.count ? NSFileTypeRegular : NSFileTypeDirectory;
        if (![attributes[NSFileType] isEqual:expectedType]) {
            return nil;
        }
    }
    NSString *canonicalRoot = standardRoot.stringByResolvingSymlinksInPath;
    NSString *canonicalPath = standardPath.stringByResolvingSymlinksInPath;
    NSString *canonicalRootPrefix = [canonicalRoot stringByAppendingString:@"/"];
    if (![canonicalPath hasPrefix:canonicalRootPrefix]) {
        return nil;
    }
    NSString *canonicalRelativePath = [canonicalPath substringFromIndex:canonicalRootPrefix.length];
    for (NSString *component in [canonicalRelativePath componentsSeparatedByString:@"/"]) {
        if (!RaftDiagnosticsIsSafeArchivePathComponent(component)) {
            return nil;
        }
    }
    NSDictionary<NSFileAttributeKey, id> *canonicalAttributes =
        [fileManager attributesOfItemAtPath:canonicalPath error:nil];
    return [canonicalAttributes[NSFileType] isEqual:NSFileTypeRegular] ? canonicalPath : nil;
}

NS_ASSUME_NONNULL_END
