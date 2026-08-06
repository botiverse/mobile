#import <XCTest/XCTest.h>

#import "SlockSandboxWebViewNavigationCommit.h"

@interface SlockSandboxWebViewTests : XCTestCase
@end

@implementation SlockSandboxWebViewTests

- (void)testActiveWebViewPublishesCommitExactlyOnce {
    NSObject *activeWebView = [[NSObject alloc] init];
    __block NSInteger commits = 0;
    SlockSandboxWebViewPublishNavigationCommit(activeWebView, activeWebView, ^{
        commits += 1;
    });

    XCTAssertEqual(commits, 1);
}

- (void)testDetachedWebViewCannotPublishACommit {
    NSObject *activeWebView = [[NSObject alloc] init];
    NSObject *staleWebView = [[NSObject alloc] init];
    __block NSInteger commits = 0;
    SlockSandboxWebViewPublishNavigationCommit(activeWebView, staleWebView, ^{
        commits += 1;
    });

    XCTAssertEqual(commits, 0);
}

@end
