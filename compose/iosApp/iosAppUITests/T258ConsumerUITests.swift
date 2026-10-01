import XCTest

/// Task #258 iOS consumer evidence: drive sign-in + inline task chip combined
/// detail panel on the iOS simulator at exact H=3ba724328c0a362a11496483165edfda938c3c54.
/// Taps use normalized coordinates (Kuikly canvas UI is not accessibility-
/// introspectable); screenshots are written directly to /tmp for the evidence packet.
final class T258ConsumerUITests: XCTestCase {

    private func shot(_ name: String, app: XCUIApplication? = nil) {
        let img = XCUIScreen.main.screenshot()
        let att = XCTAttachment(screenshot: img)
        att.name = name
        att.lifetime = .keepAlways
        add(att)
    }

    private func tap(_ app: XCUIApplication, x: Double, y: Double) {
        let c = app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
        c.tap()
    }

    @discardableResult
    private func focusAndType(_ app: XCUIApplication, x: Double, y: Double, text: String) -> Bool {
        for _ in 0..<3 {
            tap(app, x: x, y: y)
            if app.keyboards.element(boundBy: 0).waitForExistence(timeout: 3) {
                app.typeText(text)
                return true
            }
        }
        return false
    }

    func testT258CombinedPanelFlow() {
        let app = XCUIApplication()
        app.launch()

        let creds = try! String(contentsOfFile: "/tmp/t258-qa-cred.txt", encoding: .utf8)
            .split(separator: "\n").map(String.init)
        XCTAssertTrue(creds.count == 2)

        sleep(12)
        // Dismiss any notification permission alert overlaying sign-in.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allowEarly = springboard.buttons["Allow"]
        if allowEarly.waitForExistence(timeout: 3) { allowEarly.tap(); sleep(2) }
        shot("1-signin", app: app)
        // Email field, then password, then Sign In (normalized from 1179x2556 screenshot).
        focusAndType(app, x: 0.481, y: 0.332, text: creds[0])
        tap(app, x: 0.481, y: 0.419)
        sleep(1)
        focusAndType(app, x: 0.481, y: 0.419, text: creds[1])
        shot("2-filled", app: app)
        tap(app, x: 0.481, y: 0.487)
        sleep(8)
        // Dismiss the notifications permission alert if it appeared.
        if allowEarly.waitForExistence(timeout: 4) { allowEarly.tap() }
        sleep(4)
        shot("3-after-login", app: app)

        // Choose server: tap the OranixSlockTest row.
        tap(app, x: 0.45, y: 0.36)
        sleep(10)
        shot("4-channel", app: app)

        // Back to the channel list, then open #all.
        tap(app, x: 0.075, y: 0.107)
        sleep(6)
        shot("4b-channel-list", app: app)

        // Open #all from the sidebar list (expand CHANNELS if collapsed, then row).
        tap(app, x: 0.053, y: 0.327)
        sleep(3)
        shot("4c-list-after-expand", app: app)
        tap(app, x: 0.20, y: 0.391)
        sleep(20)
        shot("5-channel-open", app: app)
        sleep(30)
        shot("5a-channel-50s", app: app)
        sleep(30)
        shot("5b-channel-80s", app: app)
        sleep(30)
        shot("5c-channel-110s", app: app)

        // Scroll chat to bottom and wait for history to load, then open the chip panel.
        tap(app, x: 0.5, y: 0.5)
        sleep(10)
        tap(app, x: 0.22, y: 0.845)
        sleep(8)
        shot("6-panel", app: app)

        // Assignee: tap Unassigned row region.
        tap(app, x: 0.21, y: 0.655)
        sleep(5)
        shot("7-unassigned", app: app)
    }
}
