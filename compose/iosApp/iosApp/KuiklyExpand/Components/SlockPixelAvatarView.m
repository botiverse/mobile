#import "SlockPixelAvatarView.h"

#import <OpenKuiklyIOSRender/KRComponentDefine.h>

static NSString *const SlockPixelAvatarBackgroundProp = @"background";
static NSString *const SlockPixelAvatarSpansProp = @"spans";
static NSString *const SlockPixelAvatarCacheKeyProp = @"cacheKey";
static NSInteger const SlockPixelAvatarGridCellCount = 8;

@interface SlockPixelAvatarSpan : NSObject

@property (nonatomic, assign) NSInteger rowIndex;
@property (nonatomic, assign) NSInteger startColumnIndex;
@property (nonatomic, assign) NSInteger columnCount;
@property (nonatomic, strong) UIColor *color;

@end

@implementation SlockPixelAvatarSpan
@end

@interface SlockPixelAvatarView ()

@property (nonatomic, strong) UIColor *avatarBackgroundColor;
@property (nonatomic, copy) NSArray<SlockPixelAvatarSpan *> *spans;
@property (nonatomic, copy) NSString *cacheKey;

@end

@implementation SlockPixelAvatarView

@synthesize hr_rootView;

- (instancetype)init {
    if (self = [super initWithFrame:CGRectZero]) {
        _avatarBackgroundColor = UIColor.clearColor;
        _spans = @[];
        _cacheKey = @"";
        self.backgroundColor = UIColor.clearColor;
        self.opaque = NO;
        self.contentMode = UIViewContentModeRedraw;
    }
    return self;
}

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
    if ([propKey isEqualToString:SlockPixelAvatarBackgroundProp]) {
        self.avatarBackgroundColor = [self colorFromString:[self stringFromValue:propValue] fallback:UIColor.clearColor];
        [self setNeedsDisplay];
    } else if ([propKey isEqualToString:SlockPixelAvatarSpansProp]) {
        self.spans = [self spansFromString:[self stringFromValue:propValue]];
        [self setNeedsDisplay];
    } else if ([propKey isEqualToString:SlockPixelAvatarCacheKeyProp]) {
        self.cacheKey = [self stringFromValue:propValue];
    }
}

- (void)drawRect:(CGRect)rect {
    [super drawRect:rect];
    CGContextRef context = UIGraphicsGetCurrentContext();
    if (!context || CGRectGetWidth(self.bounds) <= 0 || CGRectGetHeight(self.bounds) <= 0) {
        return;
    }
    CGContextSetShouldAntialias(context, false);
    CGContextSetFillColorWithColor(context, self.avatarBackgroundColor.CGColor);
    CGContextFillRect(context, self.bounds);
    for (SlockPixelAvatarSpan *span in self.spans) {
        CGRect spanRect = CGRectMake([self gridEdgeForIndex:span.startColumnIndex extent:CGRectGetWidth(self.bounds)],
                                     [self gridEdgeForIndex:span.rowIndex extent:CGRectGetHeight(self.bounds)],
                                     [self gridEdgeForIndex:span.startColumnIndex + span.columnCount extent:CGRectGetWidth(self.bounds)] - [self gridEdgeForIndex:span.startColumnIndex extent:CGRectGetWidth(self.bounds)],
                                     [self gridEdgeForIndex:span.rowIndex + 1 extent:CGRectGetHeight(self.bounds)] - [self gridEdgeForIndex:span.rowIndex extent:CGRectGetHeight(self.bounds)]);
        CGContextSetFillColorWithColor(context, span.color.CGColor);
        CGContextFillRect(context, spanRect);
    }
}

#pragma mark - Private

- (NSString *)stringFromValue:(id)value {
    if ([value isKindOfClass:NSString.class]) {
        return value;
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [value stringValue];
    }
    return @"";
}

- (NSArray<SlockPixelAvatarSpan *> *)spansFromString:(NSString *)rawSpans {
    if (rawSpans.length == 0) {
        return @[];
    }
    NSMutableArray<SlockPixelAvatarSpan *> *parsed = [NSMutableArray array];
    for (NSString *rawSpan in [rawSpans componentsSeparatedByString:@";"]) {
        NSArray<NSString *> *parts = [rawSpan componentsSeparatedByString:@","];
        if (parts.count != 4) {
            continue;
        }
        SlockPixelAvatarSpan *span = [[SlockPixelAvatarSpan alloc] init];
        span.rowIndex = parts[0].integerValue;
        span.startColumnIndex = parts[1].integerValue;
        span.columnCount = parts[2].integerValue;
        span.color = [self colorFromString:parts[3] fallback:UIColor.clearColor];
        [parsed addObject:span];
    }
    return parsed;
}

- (UIColor *)colorFromString:(NSString *)value fallback:(UIColor *)fallback {
    NSString *raw = [value stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (raw.length == 0) {
        return fallback;
    }
    if ([raw hasPrefix:@"#"]) {
        raw = [raw substringFromIndex:1];
    }
    unsigned long long rgba = 0;
    NSScanner *scanner = [NSScanner scannerWithString:raw];
    if (![scanner scanHexLongLong:&rgba]) {
        return fallback;
    }
    CGFloat a = 1.0;
    CGFloat r = 0.0;
    CGFloat g = 0.0;
    CGFloat b = 0.0;
    if (raw.length == 8) {
        a = ((rgba >> 24) & 0xFF) / 255.0;
        r = ((rgba >> 16) & 0xFF) / 255.0;
        g = ((rgba >> 8) & 0xFF) / 255.0;
        b = (rgba & 0xFF) / 255.0;
    } else if (raw.length == 6) {
        r = ((rgba >> 16) & 0xFF) / 255.0;
        g = ((rgba >> 8) & 0xFF) / 255.0;
        b = (rgba & 0xFF) / 255.0;
    } else {
        return fallback;
    }
    return [UIColor colorWithRed:r green:g blue:b alpha:a];
}

- (CGFloat)gridEdgeForIndex:(NSInteger)index extent:(CGFloat)extent {
    if (index <= 0) {
        return 0;
    }
    if (index >= SlockPixelAvatarGridCellCount) {
        return extent;
    }
    return round(extent * index / SlockPixelAvatarGridCellCount);
}

@end
