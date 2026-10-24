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

/// Snapshot leaf name that identifies the launch a log came from, e.g.
/// `launch-20261024T003012Z-raft-diagnostics.jsonl.1` for session dir `launch-<epochMs>-<uuid>`.
/// Chronological order is lexical. Non-launch (flat) logs and unparseable session names keep the bare leaf
/// (unparseable ones get the sanitized session name as prefix). Only [A-Za-z0-9._-] is emitted.
static inline NSString *RaftDiagnosticsSnapshotLogFileName(NSString *sessionName, NSString *leaf) {
    if (![sessionName hasPrefix:@"launch-"]) return leaf;
    NSString *rest = [sessionName substringFromIndex:7];
    NSString *prefix = nil;
    NSRange dash = [rest rangeOfString:@"-"];
    NSString *millisText = dash.location == NSNotFound ? rest : [rest substringToIndex:dash.location];
    long long millis = millisText.length > 0 ? millisText.longLongValue : 0;
    BOOL digits = millisText.length > 0 &&
        [millisText rangeOfCharacterFromSet:NSCharacterSet.decimalDigitCharacterSet.invertedSet].location == NSNotFound;
    if (digits && millis > 0) {
        NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
        formatter.locale = [NSLocale localeWithLocaleIdentifier:@"en_US_POSIX"];
        formatter.timeZone = [NSTimeZone timeZoneForSecondsFromGMT:0];
        formatter.dateFormat = @"yyyyMMdd'T'HHmmss'Z'";
        prefix = [@"launch-" stringByAppendingString:
            [formatter stringFromDate:[NSDate dateWithTimeIntervalSince1970:(NSTimeInterval)millis / 1000.0]]];
    } else {
        NSCharacterSet *allowed = [NSCharacterSet characterSetWithCharactersInString:
            @"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"];
        NSMutableString *safe = [NSMutableString string];
        for (NSUInteger i = 0; i < sessionName.length && safe.length < 48; i++) {
            unichar c = [sessionName characterAtIndex:i];
            [safe appendFormat:@"%C", [allowed characterIsMember:c] ? c : '_'];
        }
        prefix = safe;
    }
    return [NSString stringWithFormat:@"%@-%@", prefix, leaf];
}

NS_ASSUME_NONNULL_END
