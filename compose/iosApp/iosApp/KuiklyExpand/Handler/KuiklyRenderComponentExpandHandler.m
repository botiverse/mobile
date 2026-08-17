#import "KuiklyRenderComponentExpandHandler.h"
#import <SDWebImage/UIImageView+WebCache.h>
#import "iosApp-Swift.h"

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
    if ([[SlockIosAvatarCacheHost shared] isManagedRenderModel:url]) {
        [imageView sd_cancelCurrentImageLoad];
        imageView.image = [[SlockIosAvatarCacheHost shared] imageForRenderModel:url];
        return YES;
    }
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

/*
 * task #421 tail: Kuikly resolves `assets://` image loads — both the native
 * KRImageView and the Compose image cache (KRMemoryCacheModule →
 * KuiklyContextParam.urlForFileName) ask this hook first. moko/kmmresource
 * bundles MR assets (e.g. the reaction sprite atlas) into
 * `build.raft.app.resources.MR.bundle` (task #423), NOT the main bundle — and
 * Kuikly's default resolution only looks in the main bundle, so the atlas was
 * never found and the reaction glyphs fell back to emoji. Point MR asset lookups
 * at the MR bundle so they decode into real sprites.
 */
- (NSURL *)hr_customBundleUrlForFileName:(NSString *)fileName extension:(NSString *)fileExtension {
    return [self slock_mrBundleURLForRelativePath:fileName extension:fileExtension];
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

    // MR bundle fallback (kmmresource assets live in build.raft.app.resources.MR.bundle).
    NSURL *mrURL = [self slock_mrBundleURLForRelativePath:[relativePath stringByDeletingPathExtension]
                                                extension:[relativePath pathExtension]];
    if (mrURL != nil) {
        return [UIImage imageWithContentsOfFile:mrURL.path];
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

    // MR bundle fallback.
    NSURL *mrURL = [self slock_mrBundleURLForRelativePath:[relativePath stringByDeletingPathExtension]
                                                extension:[relativePath pathExtension]];
    if (mrURL != nil) {
        return mrURL;
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

// Locate build.raft.app.resources.MR.bundle (kmmresource copies it into the
// .app; see task #423). Cached — the bundle location is stable per launch.
- (NSBundle *)slock_mrResourceBundle {
    static NSBundle *mrBundle = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        NSURL *url = [[NSBundle mainBundle] URLForResource:@"build.raft.app.resources.MR" withExtension:@"bundle"];
        if (url != nil) {
            mrBundle = [NSBundle bundleWithURL:url];
        }
    });
    return mrBundle;
}

// Resolve an MR asset (relativePath is WITHOUT extension, e.g.
// "reactions/reaction_atlas_v1") to a file URL inside the MR bundle. moko
// flattens the originalPath's "/" to "+" on disk (reactions/reaction_atlas_v1 ->
// reactions+reaction_atlas_v1) and writes a macOS-style Contents/Resources
// layout, so try several name/layout candidates.
- (NSURL *)slock_mrBundleURLForRelativePath:(NSString *)relativePath extension:(NSString *)fileExtension {
    NSBundle *mrBundle = [self slock_mrResourceBundle];
    if (mrBundle == nil || relativePath.length == 0) {
        return nil;
    }
    NSString *ext = fileExtension.length > 0 ? fileExtension : nil;

    NSArray<NSString *> *candidateNames = @[
        [relativePath stringByReplacingOccurrencesOfString:@"/" withString:@"+"],
        relativePath,
        [relativePath lastPathComponent],
    ];
    NSArray<NSString *> *searchDirs = @[
        [mrBundle.bundlePath stringByAppendingPathComponent:@"Contents/Resources"],
        mrBundle.resourcePath ?: mrBundle.bundlePath,
        mrBundle.bundlePath,
    ];

    for (NSString *name in candidateNames) {
        NSURL *url = [mrBundle URLForResource:name withExtension:ext];
        if (url != nil) {
            return url;
        }
        NSString *fileName = ext.length > 0 ? [name stringByAppendingPathExtension:ext] : name;
        for (NSString *dir in searchDirs) {
            if (dir.length == 0) {
                continue;
            }
            NSString *candidate = [dir stringByAppendingPathComponent:fileName];
            if ([NSFileManager.defaultManager fileExistsAtPath:candidate]) {
                return [NSURL fileURLWithPath:candidate];
            }
        }
    }
    return nil;
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
