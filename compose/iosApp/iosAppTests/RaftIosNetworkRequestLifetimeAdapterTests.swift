import XCTest
import UIKit

final class RaftIosNetworkRequestLifetimeAdapterTests: XCTestCase {
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
