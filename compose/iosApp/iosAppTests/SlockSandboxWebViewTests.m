#import <WebKit/WebKit.h>
#import <XCTest/XCTest.h>

@interface SlockSandboxWebView : UIView <WKNavigationDelegate>
@end

@interface SlockSandboxWebViewTests : XCTestCase
@end

@implementation SlockSandboxWebViewTests

- (void)testMainDocumentCommitIsDistinctFromFinish {
    SlockSandboxWebView *view = [[SlockSandboxWebView alloc] init];
    __block NSInteger commits = 0;
    __block NSInteger finishes = 0;
    [view setValue:[^(__unused NSDictionary *params) {
        commits += 1;
    } copy] forKey:@"loadCommitCallback"];
    [view setValue:[^(__unused NSDictionary *params) {
        finishes += 1;
    } copy] forKey:@"loadSuccessCallback"];
    WKWebView *activeWebView = [view valueForKey:@"webView"];

    [view webView:activeWebView didCommitNavigation:nil];

    XCTAssertEqual(commits, 1);
    XCTAssertEqual(finishes, 0);

    [view webView:activeWebView didFinishNavigation:nil];

    XCTAssertEqual(commits, 1);
    XCTAssertEqual(finishes, 1);
}

- (void)testDetachedWebViewCannotPublishACommit {
    SlockSandboxWebView *view = [[SlockSandboxWebView alloc] init];
    __block NSInteger commits = 0;
    [view setValue:[^(__unused NSDictionary *params) {
        commits += 1;
    } copy] forKey:@"loadCommitCallback"];
    WKWebView *staleWebView = [[WKWebView alloc] initWithFrame:CGRectZero];

    [view webView:staleWebView didCommitNavigation:nil];

    XCTAssertEqual(commits, 0);
}

@end
