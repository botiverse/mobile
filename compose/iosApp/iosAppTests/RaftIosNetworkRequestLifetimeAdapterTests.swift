import XCTest
import UIKit

final class RaftIosNetworkRequestLifetimeAdapterTests: XCTestCase {
    func testInstallPublishesVisibleBaselineForActiveAndInactiveStates() {
        for state in [UIApplication.State.active, .inactive] {
            let notificationCenter = NotificationCenter()
            let recorder = VisibilityRecorder()
            let adapter = makeAdapter(
                notificationCenter: notificationCenter,
                state: state,
                recorder: recorder
            )

            adapter.install()

            XCTAssertEqual(recorder.values, [true], "state=\(state.rawValue)")
        }
    }

    func testInstallPublishesHiddenBaselineForBackgroundState() {
        let notificationCenter = NotificationCenter()
        let recorder = VisibilityRecorder()
        let adapter = makeAdapter(
            notificationCenter: notificationCenter,
            state: .background,
            recorder: recorder
        )

        adapter.install()

        XCTAssertEqual(recorder.values, [false])
    }

    func testInstallIsIdempotentAndLifecycleEdgesAreDeduplicated() {
        let notificationCenter = NotificationCenter()
        var stateReads = 0
        let recorder = VisibilityRecorder()
        let adapter = RaftIosNetworkRequestLifetimeAdapter(
            notificationCenter: notificationCenter,
            applicationState: {
                stateReads += 1
                return .active
            },
            visibilitySink: { recorder.values.append($0) }
        )

        adapter.install()
        adapter.install()
        notificationCenter.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        notificationCenter.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        notificationCenter.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        notificationCenter.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        notificationCenter.post(name: UIApplication.willEnterForegroundNotification, object: nil)

        XCTAssertEqual(stateReads, 1)
        XCTAssertEqual(recorder.values, [true, false, true])
    }

    func testActiveAndResignNotificationsDoNotDriveNetworkLifetime() {
        let notificationCenter = NotificationCenter()
        let recorder = VisibilityRecorder()
        let adapter = makeAdapter(
            notificationCenter: notificationCenter,
            state: .active,
            recorder: recorder
        )

        adapter.install()
        notificationCenter.post(name: UIApplication.willResignActiveNotification, object: nil)
        notificationCenter.post(name: UIApplication.didBecomeActiveNotification, object: nil)

        XCTAssertEqual(recorder.values, [true])
    }

    func testBackgroundPostedLifecycleNotificationDeliversSinkOnMainQueue() {
        let notificationCenter = NotificationCenter()
        let delivered = expectation(description: "main-queue visibility sink")
        var observations: [(Bool, Bool)] = []
        let adapter = RaftIosNetworkRequestLifetimeAdapter(
            notificationCenter: notificationCenter,
            applicationState: { .active },
            visibilitySink: { foreground in
                observations.append((foreground, Thread.isMainThread))
                if !foreground {
                    delivered.fulfill()
                }
            }
        )
        adapter.install()

        DispatchQueue.global(qos: .userInitiated).async {
            notificationCenter.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        }

        wait(for: [delivered], timeout: 1.0)
        XCTAssertEqual(observations.map(\.0), [true, false])
        XCTAssertEqual(observations.map(\.1), [true, true])
    }

    func testDeinitRemovesObservers() {
        let notificationCenter = NotificationCenter()
        let recorder = VisibilityRecorder()
        weak var weakAdapter: RaftIosNetworkRequestLifetimeAdapter?

        autoreleasepool {
            let adapter = makeAdapter(
                notificationCenter: notificationCenter,
                state: .active,
                recorder: recorder
            )
            weakAdapter = adapter
            adapter.install()
        }

        XCTAssertNil(weakAdapter)
        notificationCenter.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        XCTAssertEqual(recorder.values, [true])
    }

    private func makeAdapter(
        notificationCenter: NotificationCenter,
        state: UIApplication.State,
        recorder: VisibilityRecorder
    ) -> RaftIosNetworkRequestLifetimeAdapter {
        RaftIosNetworkRequestLifetimeAdapter(
            notificationCenter: notificationCenter,
            applicationState: { state },
            visibilitySink: { recorder.values.append($0) }
        )
    }

    private final class VisibilityRecorder {
        var values: [Bool] = []
    }
}
