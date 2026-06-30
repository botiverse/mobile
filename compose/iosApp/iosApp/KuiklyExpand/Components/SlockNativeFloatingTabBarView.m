#import "SlockNativeFloatingTabBarView.h"

#import <OpenKuiklyIOSRender/KRComponentDefine.h>

static NSString *const SlockNativeFloatingTabBarKeysProp = @"keysText";
static NSString *const SlockNativeFloatingTabBarLabelsProp = @"labelsText";
static NSString *const SlockNativeFloatingTabBarIconsProp = @"iconsText";
static NSString *const SlockNativeFloatingTabBarEnabledProp = @"enabledText";
static NSString *const SlockNativeFloatingTabBarSelectedKeyProp = @"selectedKey";
static NSString *const SlockNativeFloatingTabBarSelectProp = @"select";
static CGFloat const SlockNativeFloatingTabBarDockHeight = 56.0;
static CGFloat const SlockNativeFloatingTabBarSideMargin = 18.0;
static CGFloat const SlockNativeFloatingTabBarCornerRadius = 28.0;
static CGFloat const SlockNativeFloatingTabBarHorizontalPadding = 8.0;
static CGFloat const SlockNativeFloatingTabBarVerticalPadding = 8.0;
static CGFloat const SlockNativeFloatingTabBarIconSize = 18.0;
static NSInteger const SlockNativeFloatingTabBarSelectedTag = 1001;
static NSInteger const SlockNativeFloatingTabBarIconTag = 1002;
static NSInteger const SlockNativeFloatingTabBarLabelTag = 1003;

static NSString *SlockNativeFloatingTabBarSeparator(void) {
    static NSString *separator;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        unichar character = 0x001F;
        separator = [[NSString alloc] initWithCharacters:&character length:1];
    });
    return separator;
}

@interface SlockNativeFloatingTabBarView ()

@property (nonatomic, strong) UIVisualEffectView *materialView;
@property (nonatomic, strong) UIView *tintView;
@property (nonatomic, strong) NSMutableArray<UIControl *> *itemControls;
@property (nonatomic, copy) NSArray<NSString *> *keys;
@property (nonatomic, copy) NSArray<NSString *> *labels;
@property (nonatomic, copy) NSArray<NSString *> *icons;
@property (nonatomic, copy) NSArray<NSString *> *enabled;
@property (nonatomic, copy) NSString *selectedKey;
@property (nonatomic, copy) KuiklyRenderCallback selectCallback;

@end

@implementation SlockNativeFloatingTabBarView

@synthesize hr_rootView;

- (instancetype)init {
    if (self = [super initWithFrame:CGRectZero]) {
        _keys = @[];
        _labels = @[];
        _icons = @[];
        _enabled = @[];
        _selectedKey = @"";
        self.backgroundColor = UIColor.clearColor;
        self.opaque = NO;
        self.userInteractionEnabled = YES;

        UIBlurEffect *blurEffect;
        if (@available(iOS 13.0, *)) {
            blurEffect = [UIBlurEffect effectWithStyle:UIBlurEffectStyleSystemUltraThinMaterial];
        } else {
            blurEffect = [UIBlurEffect effectWithStyle:UIBlurEffectStyleLight];
        }
        _materialView = [[UIVisualEffectView alloc] initWithEffect:blurEffect];
        _itemControls = [[NSMutableArray alloc] init];

        _materialView.userInteractionEnabled = YES;
        _materialView.clipsToBounds = YES;
        _materialView.layer.cornerRadius = SlockNativeFloatingTabBarCornerRadius;
        if (@available(iOS 13.0, *)) {
            _materialView.layer.cornerCurve = kCACornerCurveContinuous;
        }
        _materialView.layer.borderWidth = 1.0;
        _materialView.layer.borderColor = [UIColor colorWithWhite:1.0 alpha:0.46].CGColor;
        [self addSubview:_materialView];

        _tintView = [[UIView alloc] initWithFrame:CGRectZero];
        _tintView.backgroundColor = [UIColor colorWithWhite:1.0 alpha:0.18];
        _tintView.userInteractionEnabled = NO;
        [_materialView.contentView addSubview:_tintView];

        self.layer.shadowColor = UIColor.blackColor.CGColor;
        self.layer.shadowOpacity = 0.16;
        self.layer.shadowRadius = 18.0;
        self.layer.shadowOffset = CGSizeMake(0.0, 8.0);
        self.layer.masksToBounds = NO;
    }
    return self;
}

- (void)hrv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    KUIKLY_SET_CSS_COMMON_PROP;
    if ([propKey isEqualToString:SlockNativeFloatingTabBarKeysProp]) {
        self.keys = [self splitValue:propValue];
        [self reloadItems];
    } else if ([propKey isEqualToString:SlockNativeFloatingTabBarLabelsProp]) {
        self.labels = [self splitValue:propValue];
        [self reloadItems];
    } else if ([propKey isEqualToString:SlockNativeFloatingTabBarIconsProp]) {
        self.icons = [self splitValue:propValue];
        [self reloadItems];
    } else if ([propKey isEqualToString:SlockNativeFloatingTabBarEnabledProp]) {
        self.enabled = [self splitValue:propValue];
        [self reloadItems];
    } else if ([propKey isEqualToString:SlockNativeFloatingTabBarSelectedKeyProp]) {
        self.selectedKey = [self stringFromValue:propValue];
        [self applySelectedKey];
    } else if ([propKey isEqualToString:SlockNativeFloatingTabBarSelectProp]) {
        self.selectCallback = (KuiklyRenderCallback)propValue;
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    CGFloat width = CGRectGetWidth(self.bounds);
    CGFloat y = MAX(0.0, CGRectGetHeight(self.bounds) - SlockNativeFloatingTabBarDockHeight);
    CGFloat x = MIN(SlockNativeFloatingTabBarSideMargin, width / 4.0);
    self.materialView.frame = CGRectMake(x, y, MAX(0.0, width - x * 2.0), SlockNativeFloatingTabBarDockHeight);
    self.tintView.frame = self.materialView.contentView.bounds;
    [self layoutItemControls];
}

- (BOOL)pointInside:(CGPoint)point withEvent:(UIEvent *)event {
    return CGRectContainsPoint(self.materialView.frame, point);
}

- (void)tabItemSelected:(UIControl *)control {
    NSUInteger index = (NSUInteger)control.tag;
    if (index >= self.keys.count) {
        return;
    }
    NSString *key = self.keys[index];
    self.selectedKey = key;
    [self applySelectedKey];
    if (self.selectCallback) {
        self.selectCallback(@{ @"key": key ?: @"", @"index": @(index) });
    }
}

#pragma mark - Private

- (void)reloadItems {
    for (UIControl *control in self.itemControls) {
        [control removeFromSuperview];
    }
    [self.itemControls removeAllObjects];

    for (NSUInteger index = 0; index < self.keys.count; index++) {
        NSString *key = self.keys[index];
        if (key.length == 0) {
            continue;
        }
        NSString *label = index < self.labels.count ? self.labels[index] : key;
        NSString *icon = index < self.icons.count ? self.icons[index] : @"";
        UIImage *image = [self imageForIconName:icon];
        BOOL enabled = index >= self.enabled.count || ![self.enabled[index] isEqualToString:@"0"];

        UIControl *control = [[UIControl alloc] initWithFrame:CGRectZero];
        control.tag = (NSInteger)index;
        control.enabled = enabled;
        control.clipsToBounds = YES;
        control.layer.cornerRadius = 16.0;
        if (@available(iOS 13.0, *)) {
            control.layer.cornerCurve = kCACornerCurveContinuous;
        }
        [control addTarget:self action:@selector(tabItemSelected:) forControlEvents:UIControlEventTouchUpInside];

        UIView *selectedBackground = [[UIView alloc] initWithFrame:CGRectZero];
        selectedBackground.tag = SlockNativeFloatingTabBarSelectedTag;
        selectedBackground.userInteractionEnabled = NO;
        selectedBackground.backgroundColor = [UIColor colorWithRed:1.0 green:0.831 blue:0.251 alpha:0.18];
        selectedBackground.layer.cornerRadius = 16.0;
        if (@available(iOS 13.0, *)) {
            selectedBackground.layer.cornerCurve = kCACornerCurveContinuous;
        }
        [control addSubview:selectedBackground];

        UIImageView *imageView = [[UIImageView alloc] initWithImage:image];
        imageView.tag = SlockNativeFloatingTabBarIconTag;
        imageView.userInteractionEnabled = NO;
        imageView.contentMode = UIViewContentModeScaleAspectFit;
        [control addSubview:imageView];

        UILabel *labelView = [[UILabel alloc] initWithFrame:CGRectZero];
        labelView.tag = SlockNativeFloatingTabBarLabelTag;
        labelView.userInteractionEnabled = NO;
        labelView.text = [label uppercaseString];
        labelView.textAlignment = NSTextAlignmentCenter;
        labelView.font = [UIFont systemFontOfSize:9.0 weight:UIFontWeightMedium];
        labelView.adjustsFontSizeToFitWidth = YES;
        labelView.minimumScaleFactor = 0.72;
        labelView.lineBreakMode = NSLineBreakByTruncatingTail;
        [control addSubview:labelView];

        [self.materialView.contentView addSubview:control];
        [self.itemControls addObject:control];
    }
    [self setNeedsLayout];
    [self applySelectedKey];
}

- (void)applySelectedKey {
    NSUInteger index = [self.keys indexOfObject:self.selectedKey ?: @""];
    if (index == NSNotFound && self.keys.count > 0) {
        index = 0;
    }
    for (UIControl *control in self.itemControls) {
        BOOL selected = (NSUInteger)control.tag == index;
        BOOL enabled = control.enabled;
        UIView *selectedBackground = [control viewWithTag:SlockNativeFloatingTabBarSelectedTag];
        UIImageView *imageView = (UIImageView *)[control viewWithTag:SlockNativeFloatingTabBarIconTag];
        UILabel *labelView = (UILabel *)[control viewWithTag:SlockNativeFloatingTabBarLabelTag];
        UIColor *color = enabled
            ? [UIColor colorWithWhite:0.0 alpha:(selected ? 1.0 : 0.45)]
            : [UIColor colorWithWhite:0.0 alpha:0.26];
        selectedBackground.hidden = !selected;
        imageView.tintColor = color;
        labelView.textColor = color;
        labelView.font = [UIFont systemFontOfSize:9.0 weight:(selected ? UIFontWeightBold : UIFontWeightMedium)];
    }
}

- (void)layoutItemControls {
    CGRect bounds = self.materialView.contentView.bounds;
    CGFloat availableWidth = MAX(0.0, CGRectGetWidth(bounds) - SlockNativeFloatingTabBarHorizontalPadding * 2.0);
    CGFloat availableHeight = MAX(0.0, CGRectGetHeight(bounds) - SlockNativeFloatingTabBarVerticalPadding * 2.0);
    NSUInteger count = self.itemControls.count;
    if (count == 0 || availableWidth <= 0.0 || availableHeight <= 0.0) {
        return;
    }
    CGFloat itemWidth = availableWidth / (CGFloat)count;
    for (NSUInteger itemIndex = 0; itemIndex < count; itemIndex++) {
        UIControl *control = self.itemControls[itemIndex];
        control.frame = CGRectMake(
            SlockNativeFloatingTabBarHorizontalPadding + itemWidth * itemIndex,
            SlockNativeFloatingTabBarVerticalPadding,
            itemWidth,
            availableHeight
        );
        UIView *selectedBackground = [control viewWithTag:SlockNativeFloatingTabBarSelectedTag];
        UIImageView *imageView = (UIImageView *)[control viewWithTag:SlockNativeFloatingTabBarIconTag];
        UILabel *labelView = (UILabel *)[control viewWithTag:SlockNativeFloatingTabBarLabelTag];
        selectedBackground.frame = control.bounds;
        CGFloat iconX = (CGRectGetWidth(control.bounds) - SlockNativeFloatingTabBarIconSize) / 2.0;
        imageView.frame = CGRectMake(iconX, 3.0, SlockNativeFloatingTabBarIconSize, SlockNativeFloatingTabBarIconSize);
        labelView.frame = CGRectMake(2.0, 24.0, MAX(0.0, CGRectGetWidth(control.bounds) - 4.0), 13.0);
    }
}

- (UIImage *)imageForIconName:(NSString *)iconName {
    NSString *assetName = [@"lucide-" stringByAppendingString:[self normalizedIconName:iconName]];
    UIImage *image = [UIImage imageNamed:assetName] ?: [UIImage imageNamed:@"lucide-circle"];
    return [[self resizedTemplateImage:image size:CGSizeMake(SlockNativeFloatingTabBarIconSize, SlockNativeFloatingTabBarIconSize)] imageWithRenderingMode:UIImageRenderingModeAlwaysTemplate];
}

- (UIImage *)resizedTemplateImage:(UIImage *)image size:(CGSize)size {
    if (!image) {
        return nil;
    }
    UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
    format.scale = UIScreen.mainScreen.scale;
    format.opaque = NO;
    UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:size format:format];
    return [renderer imageWithActions:^(UIGraphicsImageRendererContext *context) {
        [image drawInRect:CGRectMake(0.0, 0.0, size.width, size.height)];
    }];
}

- (NSString *)normalizedIconName:(NSString *)name {
    NSMutableString *result = [[NSMutableString alloc] init];
    for (NSUInteger index = 0; index < name.length; index++) {
        unichar character = [name characterAtIndex:index];
        if (character == '_') {
            [result appendString:@"-"];
        } else if ([[NSCharacterSet uppercaseLetterCharacterSet] characterIsMember:character]) {
            if (index > 0) {
                [result appendString:@"-"];
            }
            [result appendString:[[NSString stringWithCharacters:&character length:1] lowercaseString]];
        } else {
            [result appendString:[[NSString stringWithCharacters:&character length:1] lowercaseString]];
        }
    }
    return result;
}

- (NSArray<NSString *> *)splitValue:(id)value {
    NSString *text = [self stringFromValue:value];
    if (text.length == 0) {
        return @[];
    }
    return [text componentsSeparatedByString:SlockNativeFloatingTabBarSeparator()];
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

@end
