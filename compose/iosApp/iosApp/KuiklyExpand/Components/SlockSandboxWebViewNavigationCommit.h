#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^SlockSandboxWebViewNavigationCommitHandler)(void);

FOUNDATION_EXPORT void SlockSandboxWebViewPublishNavigationCommit(
    id activeWebView,
    id callbackWebView,
    SlockSandboxWebViewNavigationCommitHandler handler
);

NS_ASSUME_NONNULL_END
