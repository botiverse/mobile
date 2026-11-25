import Foundation
import UIKit
import XCTest
import shared

/// Verifies `RaftIosTopPageResolver` (backing `RaftCmpPageNavigator.currentTopPage()`) on a real
/// `UINavigationController` stack — including off-main-thread calls (which previously returned nil
/// silently and broke `already_top_page` deduplication after backgrounding), modal presentations,
/// non-Raft controllers, pops back to the shell, and non-JSON host-value resilience.
final class RaftIosTopPageResolverTests: XCTestCase {
    private final class StubPageController: UIViewController {
        let pageName: String
        let pageData: [AnyHashable: Any]

        init(pageName: String, pageData: [AnyHashable: Any]) {
            self.pageName = pageName
            self.pageData = pageData
            super.init(nibName: nil, bundle: nil)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not supported")
        }
    }

    private final class StubPresentingNavigationController: UINavigationController {
        var fakePresented: UIViewController?

        override var presentedViewController: UIViewController? {
            fakePresented ?? super.presentedViewController
        }
    }

    private final class StubPresentingController: UIViewController {
        var fakePresented: UIViewController?

        override var presentedViewController: UIViewController? {
            fakePresented ?? super.presentedViewController
        }
    }

    private static let extractStub: (UIViewController) -> (pageName: String, pageData: [AnyHashable: Any])? = { vc in
        guard let page = vc as? StubPageController else { return nil }
        return (page.pageName, page.pageData)
    }

    private static let dmPayloadJson =
        #"{"instanceId":"shared-shell-thread-af67d772-4334-4966-8a70-3b661ed18567","surfaceKind":"dm","channelKind":"dm","threadId":"af67d772-4334-4966-8a70-3b661ed18567","sourceThreadId":"af67d772-4334-4966-8a70-3b661ed18567","streamChannelId":"af67d772-4334-4966-8a70-3b661ed18567","focusedMessageId":"19436486-1307-4539-975c-dbfd98aeec7b"}"#

    func testResolvesTopConversationOnMainThreadAndAfterPopBackToShell() {
        let shell = StubPageController(pageName: "compose_slock_app_shell", pageData: ["pageName": "compose_slock_app_shell"])
        let dm = StubPageController(
            pageName: "compose_thread",
            pageData: [
                "pageName": "compose_thread",
                "instanceId": "shared-shell-thread-af67d772-4334-4966-8a70-3b661ed18567",
                "payload": Self.dmPayloadJson,
            ]
        )
        let nav = UINavigationController(rootViewController: shell)
        nav.setViewControllers([shell, dm], animated: false)

        let top = RaftIosTopPageResolver.resolveTopPage(from: shell, pageExtractor: Self.extractStub)
        XCTAssertEqual(top?.pageName, "compose_thread")
        let parsed = try? JSONSerialization.jsonObject(with: Data((top?.pageDataJson ?? "{}").utf8)) as? [String: Any]
        XCTAssertEqual(parsed?["payload"] as? String, Self.dmPayloadJson)
        XCTAssertEqual(parsed?["instanceId"] as? String, "shared-shell-thread-af67d772-4334-4966-8a70-3b661ed18567")

        nav.setViewControllers([shell], animated: false)
        let afterPop = RaftIosTopPageResolver.resolveTopPage(from: shell, pageExtractor: Self.extractStub)
        XCTAssertEqual(afterPop?.pageName, "compose_slock_app_shell")
    }

    func testResolvesTopConversationWhenCalledOffMainThread() {
        let shell = StubPageController(pageName: "compose_slock_app_shell", pageData: ["pageName": "compose_slock_app_shell"])
        let dm = StubPageController(
            pageName: "compose_thread",
            pageData: [
                "pageName": "compose_thread",
                "instanceId": "shared-shell-thread-af67d772-4334-4966-8a70-3b661ed18567",
                "payload": Self.dmPayloadJson,
            ]
        )
        let nav = UINavigationController(rootViewController: shell)
        nav.setViewControllers([shell, dm], animated: false)

        let expectation = expectation(description: "off-main resolveTopPage")
        var resolved: RaftCmpIosTopPage?
        DispatchQueue.global(qos: .userInitiated).async {
            XCTAssertFalse(Thread.isMainThread)
            resolved = RaftIosTopPageResolver.resolveTopPage(from: shell, pageExtractor: Self.extractStub)
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 5.0)

        XCTAssertNotNil(resolved, "off-main calls must hop to main instead of silently returning nil")
        XCTAssertEqual(resolved?.pageName, "compose_thread")
        let parsed = try? JSONSerialization.jsonObject(with: Data((resolved?.pageDataJson ?? "{}").utf8)) as? [String: Any]
        XCTAssertEqual(parsed?["payload"] as? String, Self.dmPayloadJson)
    }

    func testNonRaftTopOrCoveringModalReportsEmptyNonNilTopPage() {
        let shell = StubPageController(pageName: "compose_slock_app_shell", pageData: [:])
        let dm = StubPageController(pageName: "compose_thread", pageData: ["payload": Self.dmPayloadJson])
        let nav = StubPresentingNavigationController(rootViewController: shell)

        // Non-Raft UIViewController pushed onto the stack.
        nav.setViewControllers([shell, dm, UIViewController()], animated: false)
        let nonRaftTop = RaftIosTopPageResolver.resolveTopPage(from: shell, pageExtractor: Self.extractStub)
        XCTAssertNotNil(nonRaftTop)
        XCTAssertEqual(nonRaftTop?.pageName, "")

        // Non-Raft modal presented over the navigation controller.
        nav.setViewControllers([shell, dm], animated: false)
        nav.fakePresented = UIViewController()
        let coveredByModal = RaftIosTopPageResolver.resolveTopPage(from: shell, pageExtractor: Self.extractStub)
        XCTAssertNotNil(coveredByModal)
        XCTAssertEqual(coveredByModal?.pageName, "")

        // Modal UINavigationController presenting another Raft page reports that modal's top page.
        let modalSettings = StubPageController(pageName: "compose_thread_settings", pageData: ["pageName": "compose_thread_settings"])
        nav.fakePresented = UINavigationController(rootViewController: modalSettings)
        let modalNavTop = RaftIosTopPageResolver.resolveTopPage(from: shell, pageExtractor: Self.extractStub)
        XCTAssertEqual(modalNavTop?.pageName, "compose_thread_settings")
    }

    func testDetachedControllerReturnsNilAndPresentedNavFallbackResolves() {
        let detached = StubPresentingController()
        XCTAssertNil(RaftIosTopPageResolver.resolveTopPage(from: detached, pageExtractor: Self.extractStub))

        let presentedDm = StubPageController(pageName: "compose_thread", pageData: ["payload": Self.dmPayloadJson])
        detached.fakePresented = UINavigationController(rootViewController: presentedDm)
        let resolved = RaftIosTopPageResolver.resolveTopPage(from: detached, pageExtractor: Self.extractStub)
        XCTAssertEqual(resolved?.pageName, "compose_thread")
    }

    func testJsonTextPreservesRouteIdentityEvenWhenHostValueIsNonJson() {
        let dirty: [AnyHashable: Any] = [
            "pageName": "compose_thread",
            "instanceId": "shared-shell-thread-af67d772-4334-4966-8a70-3b661ed18567",
            "payload": Self.dmPayloadJson,
            "slockBadFloat": Double.nan,
        ]
        let text = RaftIosTopPageResolver.jsonText(dirty)
        let parsed = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        XCTAssertEqual(parsed?["pageName"] as? String, "compose_thread")
        XCTAssertEqual(parsed?["instanceId"] as? String, "shared-shell-thread-af67d772-4334-4966-8a70-3b661ed18567")
        XCTAssertEqual(parsed?["payload"] as? String, Self.dmPayloadJson)
        XCTAssertNil(parsed?["slockBadFloat"])
    }
}
