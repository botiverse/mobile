#import "SlockSandboxWebViewNavigationCommit.h"

void SlockSandboxWebViewPublishNavigationCommit(
    id activeWebView,
    id callbackWebView,
    SlockSandboxWebViewNavigationCommitHandler handler
) {
    if (activeWebView == callbackWebView) {
        handler();
    }
}
