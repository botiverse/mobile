#import "SlockLucideIconView.h"

#import <OpenKuiklyIOSRender/KRComponentDefine.h>

static NSString *const SlockLucideIconNameProp = @"name";
static NSString *const SlockLucideIconColorProp = @"color";
static NSString *const SlockLucideIconFillProp = @"fill";
static NSString *const SlockLucideIconStrokeWidthProp = @"strokeWidth";
static NSString *const SlockLucideIconVerticalOffsetDpProp = @"verticalOffsetDp";

@interface SlockLucideIconView ()

@property (nonatomic, copy) NSString *iconName;
@property (nonatomic, strong) UIColor *iconColor;
@property (nonatomic, strong, nullable) UIColor *fillColor;
@property (nonatomic, strong) UIImageView *imageView;
@property (nonatomic, assign) CGFloat verticalOffset;

@end

@implementation SlockLucideIconView

@synthesize hr_rootView;

- (instancetype)init {
    if (self = [super initWithFrame:CGRectZero]) {
        _iconName = @"";
        _iconColor = UIColor.blackColor;
        self.backgroundColor = UIColor.clearColor;
        self.opaque = NO;

        _imageView = [[UIImageView alloc] initWithFrame:CGRectZero];
        _imageView.backgroundColor = UIColor.clearColor;
        _imageView.contentMode = UIViewContentModeScaleAspectFit;
        _imageView.tintColor = _iconColor;
        [self addSubview:_imageView];
    }
    return self;
}

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
    if ([propKey isEqualToString:SlockLucideIconNameProp]) {
        self.iconName = [self normalizedIconName:[self stringFromValue:propValue]];
        [self updateImage];
    } else if ([propKey isEqualToString:SlockLucideIconColorProp]) {
        self.iconColor = [self colorFromValue:propValue fallback:UIColor.blackColor];
        [self updateTint];
    } else if ([propKey isEqualToString:SlockLucideIconFillProp]) {
        NSString *rawFill = [self stringFromValue:propValue];
        self.fillColor = rawFill.length > 0 ? [self colorFromValue:propValue fallback:nil] : nil;
        [self updateImage];
        [self updateTint];
    } else if ([propKey isEqualToString:SlockLucideIconStrokeWidthProp]) {
        // iOS renders the same generated Lucide SVG assets as OHOS. SVG stroke width is
        // baked into the asset; dynamic stroke width remains Android/OHOS-only for now.
    } else if ([propKey isEqualToString:SlockLucideIconVerticalOffsetDpProp]) {
        self.verticalOffset = [propValue respondsToSelector:@selector(doubleValue)] ? [propValue doubleValue] : 0;
        [self setNeedsLayout];
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    self.imageView.frame = CGRectOffset(self.bounds, 0, self.verticalOffset);
}

#pragma mark - Private

- (void)updateImage {
    UIImage *image = [UIImage imageNamed:[self assetNameForIconName:self.iconName]];
    if (!image) {
        image = [UIImage imageNamed:@"lucide-info"];
    }
    self.imageView.image = [image imageWithRenderingMode:UIImageRenderingModeAlwaysTemplate];
}

- (void)updateTint {
    self.imageView.tintColor = self.fillColor ?: self.iconColor ?: UIColor.blackColor;
}

- (NSString *)assetNameForIconName:(NSString *)name {
    if (self.fillColor && [name isEqualToString:@"bookmark"]) {
        return @"lucide-filled-bookmark";
    }
    if (name.length == 0) {
        return @"lucide-info";
    }
    return [NSString stringWithFormat:@"lucide-%@", name];
}

- (NSString *)stringFromValue:(id)value {
    if ([value isKindOfClass:NSString.class]) {
        return value;
    }
    if ([value respondsToSelector:@selector(stringValue)]) {
        return [value stringValue];
    }
    return @"";
}

- (UIColor *)colorFromValue:(id)value fallback:(UIColor *)fallback {
    if ([value isKindOfClass:UIColor.class]) {
        return value;
    }
    NSString *raw = [[self stringFromValue:value] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
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

- (NSString *)normalizedIconName:(NSString *)name {
    NSMutableString *result = [NSMutableString string];
    for (NSUInteger index = 0; index < name.length; index++) {
        unichar ch = [name characterAtIndex:index];
        if (ch == '_') {
            [result appendString:@"-"];
        } else if ([[NSCharacterSet uppercaseLetterCharacterSet] characterIsMember:ch]) {
            if (index > 0 && result.length > 0 && ![[result substringFromIndex:result.length - 1] isEqualToString:@"-"]) {
                [result appendString:@"-"];
            }
            [result appendString:[[NSString stringWithCharacters:&ch length:1] lowercaseString]];
        } else {
            [result appendString:[[NSString stringWithCharacters:&ch length:1] lowercaseString]];
        }
    }
    return [result stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
}

@end
