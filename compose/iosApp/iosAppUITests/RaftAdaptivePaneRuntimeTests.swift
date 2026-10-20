import XCTest

final class RaftAdaptivePaneRuntimeTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testPublishesAcrossPortraitAndLandscapeWindowTransitions() {
        let device = XCUIDevice.shared
        device.orientation = .portrait

        let app = XCUIApplication(bundleIdentifier: "build.raft.app")
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20))
        XCTAssertGreaterThan(app.frame.height, app.frame.width)
        addScreenshot(named: "adaptive-pane-portrait")

        device.orientation = .landscapeLeft
        let landscapeDeadline = Date().addingTimeInterval(10)
        while app.frame.width <= app.frame.height, Date() < landscapeDeadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        addScreenshot(named: "adaptive-pane-landscape")

        device.orientation = .portrait
    }

    private func addScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
