#import "KuiklyRenderComponentExpandHandler.h"
#import <SDWebImage/UIImageView+WebCache.h>

@implementation KuiklyRenderComponentExpandHandler

+ (void)load {
    // 注册自定义实现
    [KuiklyRenderBridge registerComponentExpandHandler:[self new]];
}

/*
 * 自定义实现设置图片
 * @param url 设置的图片url，如果url为nil，则是取消图片设置，需要view.image = nil
 * @return 是否处理该图片设置，返回值为YES，则交给该代理实现，否则sdk内部自己处理
 */
- (BOOL)hr_setImageWithUrl:(NSString *)url forImageView:(UIImageView *)imageView {
    UIImage *bundledAssetImage = [self slock_bundledImageForAssetURLString:url];
    if (bundledAssetImage != nil) {
        [imageView sd_cancelCurrentImageLoad];
        imageView.image = bundledAssetImage;
        return YES;
    }

    NSURL *resolvedURL = [self slock_resolvedImageURLForURLString:url];
    [imageView sd_setImageWithURL:resolvedURL];
    return YES;
}

- (UIImage *)slock_bundledImageForAssetURLString:(NSString *)url {
    NSString *relativePath = [self slock_relativeAssetPathForURLString:url];
    if (relativePath.length == 0) {
        return nil;
    }

    NSString *catalogName = [[relativePath stringByDeletingPathExtension] stringByReplacingOccurrencesOfString:@"/" withString:@"-"];
    UIImage *catalogImage = [UIImage imageNamed:catalogName];
    if (catalogImage != nil) {
        return catalogImage;
    }

    NSString *resourcePath = [NSBundle.mainBundle.resourcePath stringByAppendingPathComponent:relativePath];
    if ([NSFileManager.defaultManager fileExistsAtPath:resourcePath]) {
        return [UIImage imageWithContentsOfFile:resourcePath];
    }
    return nil;
}

- (NSURL *)slock_resolvedImageURLForURLString:(NSString *)url {
    if (url == nil || url.length == 0) {
        return nil;
    }
    NSString *relativePath = [self slock_relativeAssetPathForURLString:url];
    if (relativePath.length == 0) {
        return [NSURL URLWithString:url];
    }

    NSURL *bundleURL = [NSBundle.mainBundle URLForResource:relativePath withExtension:nil];
    if (bundleURL != nil) {
        return bundleURL;
    }

    NSString *resourcePath = [NSBundle.mainBundle.resourcePath stringByAppendingPathComponent:relativePath];
    if ([NSFileManager.defaultManager fileExistsAtPath:resourcePath]) {
        return [NSURL fileURLWithPath:resourcePath];
    }

    return nil;
}

- (NSString *)slock_relativeAssetPathForURLString:(NSString *)url {
    static NSString *const assetsPrefix = @"assets://";
    if (url == nil || ![url hasPrefix:assetsPrefix]) {
        return nil;
    }

    NSString *relativePath = [url substringFromIndex:assetsPrefix.length];
    while ([relativePath hasPrefix:@"/"]) {
        relativePath = [relativePath substringFromIndex:1];
    }
    return relativePath;
}
/*
 * 自定义实现设置颜值
 * @param value 设置的颜色值
 * @return 完成自定义处理的颜色对象
 */
- (UIColor *)hr_colorWithValue:(NSString *)value {
    return nil;
}

@end
