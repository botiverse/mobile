import UIKit
import XCTest

/// task #151 follow-up: the iOS router's push rules (RaftIosPageOpenGate) on a real navigation
/// stack. A notification (or second tap) for the page on top opens nothing; during a transition a
/// different page waits for it (latest wins) and the same page queued twice opens once.
final class RaftIosPageOpenGateTests: XCTestCase {
    /// Applies pushes without animation (no window) and can pretend a transition is running.
    private final class RecordingNavigationController: UINavigationController {
        var pushCount = 0
        var fakeCoordinator: FakeTransitionCoordinator?

        override var transitionCoordinator: UIViewControllerTransitionCoordinator? { fakeCoordinator }

        override func pushViewController(_ viewController: UIViewController, animated: Bool) {
            pushCount += 1
            super.setViewControllers(viewControllers + [viewController], animated: false)
        }
    }

    /// A running transition: [finish] runs the completions UIKit would run when it ends.
    private final class FakeTransitionCoordinator: NSObject, UIViewControllerTransitionCoordinator {
        private var completions: [(UIViewControllerTransitionCoordinatorContext) -> Void] = []
        var completionCount: Int { completions.count }

        func finish() {
            let pending = completions
            completions = []
            pending.forEach { $0(self) }
        }

        func animate(
            alongsideTransition animation: ((UIViewControllerTransitionCoordinatorContext) -> Void)?,
            completion: ((UIViewControllerTransitionCoordinatorContext) -> Void)? = nil
        ) -> Bool {
            if let completion { completions.append(completion) }
            return true
        }

        func animateAlongsideTransition(
            in view: UIView?,
            animation: ((UIViewControllerTransitionCoordinatorContext) -> Void)?,
            completion: ((UIViewControllerTransitionCoordinatorContext) -> Void)? = nil
        ) -> Bool {
            animate(alongsideTransition: animation, completion: completion)
        }

        func notifyWhenInteractionEnds(_ handler: @escaping (UIViewControllerTransitionCoordinatorContext) -> Void) {}
        func notifyWhenInteractionChanges(_ handler: @escaping (UIViewControllerTransitionCoordinatorContext) -> Void) {}

        var isAnimated: Bool { true }
        var presentationStyle: UIModalPresentationStyle { .none }
        var initiallyInteractive: Bool { false }
        var isInterruptible: Bool { false }
        var isInteractive: Bool { false }
        var isCancelled: Bool { false }
        var transitionDuration: TimeInterval { 0.35 }
        var percentComplete: CGFloat { 0 }
        var completionVelocity: CGFloat { 1 }
        var completionCurve: UIView.AnimationCurve { .easeInOut }
        var containerView: UIView { UIView() }
        var targetTransform: CGAffineTransform { .identity }
        func viewController(forKey key: UITransitionContextViewControllerKey) -> UIViewController? { nil }
        func view(forKey key: UITransitionContextViewKey) -> UIView? { nil }
    }

    private var savedSameRoute: ((RaftIosPageRoute, RaftIosPageRoute) -> Bool)!
    private var savedLog: ((String) -> Void)!
    private var savedScheduleTransitionEnd: ((@escaping () -> Void) -> Void)!
    private var deferredTransitionEnds: [() -> Void] = []
    private var logged: [String] = []
    private var navigation: RecordingNavigationController!

    override func setUp() {
        super.setUp()
        savedSameRoute = RaftIosPageOpenGate.sameRoute
        savedLog = RaftIosPageOpenGate.log
        savedScheduleTransitionEnd = RaftIosPageOpenGate.scheduleTransitionEnd
        deferredTransitionEnds = []
        // The shared identity compares the thread by thread id, not per-open facts: same here.
        RaftIosPageOpenGate.sameRoute = { a, b in
            a.pageName == b.pageName && Self.threadId(a) == Self.threadId(b)
        }
        RaftIosPageOpenGate.log = { [weak self] line in self?.logged.append(line) }
        RaftIosPageOpenGate.scheduleTransitionEnd = { [weak self] work in
            self?.deferredTransitionEnds.append(work)
        }
        navigation = RecordingNavigationController(rootViewController: UIViewController())
        navigation.pushCount = 0
    }

    override func tearDown() {
        RaftIosPageOpenGate.sameRoute = savedSameRoute
        RaftIosPageOpenGate.log = savedLog
        RaftIosPageOpenGate.scheduleTransitionEnd = savedScheduleTransitionEnd
        deferredTransitionEnds = []
        navigation = nil
        super.tearDown()
    }

    private func drainDeferredTransitionEnds() {
        while !deferredTransitionEnds.isEmpty {
            let next = deferredTransitionEnds.removeFirst()
            next()
        }
    }

    private static func threadId(_ route: RaftIosPageRoute) -> String? {
        let object = try? JSONSerialization.jsonObject(with: Data(route.pageDataJson.utf8))
        return (object as? [String: Any])?["threadId"] as? String
    }

    @discardableResult
    private func open(_ pageName: String, thread: String, now: Int = 0) -> Bool {
        RaftIosPageOpenGate.open(
            pageName: pageName,
            pageData: ["threadId": thread, "nowEpochMillis": now],
            navigationController: navigation
        ) { [navigation] in
            let page = UIViewController()
            page.title = "\(pageName) \(thread)"
            navigation?.pushViewController(page, animated: true)
            return page
        }
    }

    private var titles: [String] { navigation.viewControllers.dropFirst().compactMap(\.title) }

    func testTheRouteOnTopIsNotPushedAgain() {
        XCTAssertTrue(open("compose_thread", thread: "t1", now: 1))
        XCTAssertFalse(open("compose_thread", thread: "t1", now: 2), "a notification for the thread shown")
        XCTAssertEqual(navigation.pushCount, 1)
        XCTAssertEqual(titles, ["compose_thread t1"])
        XCTAssertTrue(logged.contains("open_dropped page=compose_thread reason=same_route_on_top"))
    }

    func testDuringATransitionTheLatestDifferentRouteOpensWhenItEnds() {
        open("compose_thread", thread: "t1")
        let coordinator = FakeTransitionCoordinator()
        navigation.fakeCoordinator = coordinator
        XCTAssertFalse(open("compose_thread", thread: "t1"), "the push still sliding in")
        XCTAssertFalse(open("compose_thread", thread: "t2"))
        XCTAssertFalse(open("compose_thread", thread: "t3"))
        XCTAssertFalse(open("compose_thread", thread: "t3"))
        XCTAssertEqual(coordinator.completionCount, 1, "one completion per transition")
        XCTAssertEqual(navigation.pushCount, 1)

        // Real UIKit keeps navigationController.transitionCoordinator non-nil until after
        // completion closures return; draining on the next main-queue turn must push cleanly.
        coordinator.finish()
        XCTAssertEqual(navigation.pushCount, 1, "must not push synchronously inside coordinator completion")
        navigation.fakeCoordinator = nil
        drainDeferredTransitionEnds()
        XCTAssertEqual(titles, ["compose_thread t1", "compose_thread t3"])
        XCTAssertTrue(logged.contains("open_dropped page=compose_thread reason=superseded_in_transition"))
        XCTAssertTrue(logged.contains("open_dropped page=compose_thread reason=same_route_queued"))
    }

    func testStaleCompletedCoordinatorStillAttachedDoesNotWedgeQueuedOpen() {
        open("compose_thread", thread: "t1")
        let coordinator = FakeTransitionCoordinator()
        navigation.fakeCoordinator = coordinator
        XCTAssertFalse(open("compose_thread", thread: "t2"))

        // Even if the same finished coordinator is still attached when the deferred drain runs,
        // the gate ignores the already-completed coordinator instead of re-attaching a dead handler.
        coordinator.finish()
        drainDeferredTransitionEnds()
        XCTAssertEqual(titles, ["compose_thread t1", "compose_thread t2"])
        XCTAssertEqual(coordinator.completionCount, 0, "must not re-register on the completed coordinator")

        // Subsequent transitions continue to work without wedging.
        let secondCoordinator = FakeTransitionCoordinator()
        navigation.fakeCoordinator = secondCoordinator
        XCTAssertFalse(open("compose_thread", thread: "t3"))
        XCTAssertEqual(secondCoordinator.completionCount, 1)
        secondCoordinator.finish()
        navigation.fakeCoordinator = nil
        drainDeferredTransitionEnds()
        XCTAssertEqual(titles, ["compose_thread t1", "compose_thread t2", "compose_thread t3"])
    }

    func testBackThenTheSameRowOpensAgain() {
        open("compose_thread", thread: "t1")
        navigation.popViewController(animated: false)
        XCTAssertTrue(open("compose_thread", thread: "t1"))
        XCTAssertEqual(titles, ["compose_thread t1"])
        XCTAssertEqual(navigation.pushCount, 2)
    }
}
