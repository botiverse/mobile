#import "SlockNativeFloatingTabBarView.h"

#import <OpenKuiklyIOSRender/KRComponentDefine.h>
#import <OpenKuiklyIOSRender/KuiklyRenderView.h>
#import <OpenKuiklyIOSRender/NSObject+KR.h>

// Real UIKit UITabBar-backed bottom tab bar. This replaces the previous
// hand-rolled UIVisualEffectView "dock" so iOS uses the native tab bar — on
// iOS 26 the system renders it with Liquid Glass automatically, matching the
// Kuikly demo's TabbarIOS / KRTabbarView(UITabBar) reference.
//
// The shared SlockAppBottomTabBarHost drives every platform through the same
// prop/event contract (keysText / labelsText / iconsText / enabledText /
// selectedKey / bottomSafeArea, joined by U+001F, plus a 'select' event that
// returns {key, index}); only the native rendering differs. UITabBar consumes
// touches within its own bounds, so unlike the OHOS forward view it needs no
// extra Compose touch blocker.

static NSString *const SlockNativeFloatingTabBarKeysProp = @"keysText";
static NSString *const SlockNativeFloatingTabBarLabelsProp = @"labelsText";
static NSString *const SlockNativeFloatingTabBarIconsProp = @"iconsText";
static NSString *const SlockNativeFloatingTabBarEnabledProp = @"enabledText";
static NSString *const SlockNativeFloatingTabBarSelectedKeyProp = @"selectedKey";
static NSString *const SlockNativeFloatingTabBarSelectProp = @"select";
static CGFloat const SlockNativeFloatingTabBarIconSize = 22.0;

static NSString *SlockNativeFloatingTabBarSeparator(void) {
    static NSString *separator;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        unichar character = 0x001F;
        separator = [[NSString alloc] initWithCharacters:&character length:1];
    });
    return separator;
}

@interface SlockNativeFloatingTabBarView () <UITabBarDelegate>

@property (nonatomic, strong) UITabBar *tabBar;
@property (nonatomic, copy) NSArray<NSString *> *keys;
@property (nonatomic, copy) NSArray<NSString *> *labels;
@property (nonatomic, copy) NSArray<NSString *> *icons;
@property (nonatomic, copy) NSArray<NSString *> *enabled;
@property (nonatomic, copy) NSString *selectedKey;
@property (nonatomic, copy) KuiklyRenderCallback selectCallback;
@property (nonatomic, assign) BOOL usesAdaptiveController;

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

        _tabBar = [[UITabBar alloc] initWithFrame:CGRectZero];
        _tabBar.delegate = self;
        // Let the system own the material/background so iOS 26 applies Liquid
        // Glass. Tint follows the app's black-on-yellow scheme.
        _tabBar.tintColor = UIColor.blackColor;
        if (@available(iOS 10.0, *)) {
            _tabBar.unselectedItemTintColor = [UIColor colorWithWhite:0.0 alpha:0.45];
        }
        [self addSubview:_tabBar];
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
    [self syncAdaptiveControllerIfAvailable];
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    [self syncAdaptiveControllerIfAvailable];
}

- (void)layoutSubviews {
    [super layoutSubviews];
    // Pin the tab bar to the bottom of the host at its intrinsic height so it
    // reads as a real bottom bar; the system extends the material under the
    // home indicator. Final geometry to be confirmed on device.
    CGSize fitting = [self.tabBar sizeThatFits:self.bounds.size];
    CGFloat barHeight = fitting.height > 0.0 ? fitting.height : CGRectGetHeight(self.bounds);
    barHeight = MIN(barHeight, CGRectGetHeight(self.bounds));
    self.tabBar.frame = CGRectMake(0.0,
                                   CGRectGetHeight(self.bounds) - barHeight,
                                   CGRectGetWidth(self.bounds),
                                   barHeight);
}

#pragma mark - UITabBarDelegate

- (void)tabBar:(UITabBar *)tabBar didSelectItem:(UITabBarItem *)item {
    NSInteger index = item.tag;
    if (index < 0 || index >= (NSInteger)self.keys.count) {
        return;
    }
    NSString *key = self.keys[index];
    self.selectedKey = key;
    if (self.selectCallback) {
        self.selectCallback(@{ @"key": key ?: @"", @"index": @(index) });
    }
}

#pragma mark - Private

- (void)reloadItems {
    if (self.usesAdaptiveController) {
        return;
    }
    NSMutableArray<UITabBarItem *> *items = [NSMutableArray array];
    for (NSUInteger index = 0; index < self.keys.count; index++) {
        NSString *key = self.keys[index];
        if (key.length == 0) {
            continue;
        }
        NSString *label = index < self.labels.count ? self.labels[index] : key;
        NSString *icon = index < self.icons.count ? self.icons[index] : @"";
        BOOL enabled = index >= self.enabled.count || ![self.enabled[index] isEqualToString:@"0"];

        UIImage *image = [self imageForIconName:icon];
        UITabBarItem *item = [[UITabBarItem alloc] initWithTitle:[label capitalizedString]
                                                            image:image
                                                    selectedImage:image];
        item.tag = (NSInteger)index;
        item.enabled = enabled;
        [items addObject:item];
    }
    self.tabBar.items = items;
    [self applySelectedKey];
}

- (void)syncAdaptiveControllerIfAvailable {
    if (@available(iOS 18.0, *)) {
        UIViewController *controller = self.hr_rootView.kr_viewController;
        while (controller && ![controller conformsToProtocol:@protocol(SlockNativeAdaptiveTabBarHosting)]) {
            controller = controller.parentViewController;
        }
        id<SlockNativeAdaptiveTabBarHosting> host = (id<SlockNativeAdaptiveTabBarHosting>)controller;
        if (host && [host slock_updateAdaptiveTabKeys:self.keys
                                               labels:self.labels
                                                icons:self.icons
                                              enabled:self.enabled
                                          selectedKey:self.selectedKey ?: @""
                                       selectCallback:self.selectCallback]) {
            self.usesAdaptiveController = YES;
            self.tabBar.hidden = YES;
            self.userInteractionEnabled = NO;
            return;
        }
    }
    if (self.usesAdaptiveController) {
        self.usesAdaptiveController = NO;
        self.tabBar.hidden = NO;
        self.userInteractionEnabled = YES;
        [self reloadItems];
    }
}

- (void)applySelectedKey {
    NSUInteger index = [self.keys indexOfObject:self.selectedKey ?: @""];
    if (index == NSNotFound && self.tabBar.items.count > 0) {
        index = 0;
    }
    if (index != NSNotFound && index < self.tabBar.items.count) {
        self.tabBar.selectedItem = self.tabBar.items[index];
    }
}

- (UIImage *)imageForIconName:(NSString *)iconName {
    NSString *assetName = [@"lucide-" stringByAppendingString:[self normalizedIconName:iconName]];
    UIImage *image = [UIImage imageNamed:assetName] ?: [UIImage imageNamed:@"lucide-circle"];
    UIImage *sized = [self resizedTemplateImage:image size:CGSizeMake(SlockNativeFloatingTabBarIconSize, SlockNativeFloatingTabBarIconSize)];
    return [sized imageWithRenderingMode:UIImageRenderingModeAlwaysTemplate];
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
