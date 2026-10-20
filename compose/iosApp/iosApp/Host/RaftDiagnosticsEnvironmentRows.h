#import <Foundation/Foundation.h>

/// Stringifies an about-field value the same way RaftBridgeString does:
/// NSString is trimmed, stringValue-capable objects use trimmed stringValue,
/// everything else becomes "".
static inline NSString *RaftDiagnosticsEnvironmentValueString(id value) {
    if ([value isKindOfClass:NSString.class]) {
        return [(NSString *)value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [[value stringValue] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    }
    return @"";
}

/// Builds the "Environment" rows of the diagnostics export text.
///
/// When `omitEmptyRows` is YES, rows whose value is empty are skipped
/// entirely (no "Label: " shell line, no "--" placeholder). User-facing
/// copy/share surfaces pass YES so copied text never contains placeholders;
/// the diagnostics attachment passes NO so the evidence bundle keeps the
/// distinction between "field reported empty" and "field absent".
static inline NSString *RaftDiagnosticsEnvironmentRowsText(NSDictionary *about,
                                                           NSArray<NSDictionary *> *rows,
                                                           BOOL omitEmptyRows) {
    NSMutableString *text = [NSMutableString string];
    for (NSDictionary *row in rows) {
        NSString *value = RaftDiagnosticsEnvironmentValueString(about[row[@"key"]]);
        if (omitEmptyRows && value.length == 0) {
            continue;
        }
        [text appendFormat:@"%@: %@\n", row[@"label"], value.length > 0 ? value : @"--"];
    }
    return text;
}
