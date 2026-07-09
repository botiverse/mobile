import XCTest

final class IosUseInteractionTests: XCTestCase {
    private struct Action: Decodable {
        let type: String
        let text: String?
        let x: Double?
        let y: Double?
        let direction: String?
        let seconds: Double?
        let waitAfter: Double?
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testRunIosUseActions() throws {
        let environment = ProcessInfo.processInfo.environment
        let bundleIdentifier = environment["IOS_USE_BUNDLE"] ?? "build.raft.app"
        let app = XCUIApplication(bundleIdentifier: bundleIdentifier)
        app.launch()

        let actions = try Self.decodeActions(environment["IOS_USE_ACTIONS"])
        for action in actions {
            try perform(action, in: app)
            if let waitAfter = action.waitAfter, waitAfter > 0 {
                Thread.sleep(forTimeInterval: waitAfter)
            }
        }
    }

    private static func decodeActions(_ raw: String?) throws -> [Action] {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return []
        }
        let data = Data(raw.utf8)
        do {
            return try JSONDecoder().decode([Action].self, from: data)
        } catch {
            XCTFail("Invalid IOS_USE_ACTIONS JSON: \(error)")
            throw error
        }
    }

    private func perform(_ action: Action, in app: XCUIApplication) throws {
        switch action.type {
        case "wait":
            Thread.sleep(forTimeInterval: max(action.seconds ?? 1.0, 0.0))
        case "tapText":
            guard let text = action.text, !text.isEmpty else {
                XCTFail("tapText requires text")
                return
            }
            try tapElement(labeled: text, in: app)
        case "tap":
            guard let x = action.x, let y = action.y else {
                XCTFail("tap requires x and y")
                return
            }
            app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
        case "swipe":
            switch action.direction {
            case "up": app.swipeUp()
            case "down": app.swipeDown()
            case "left": app.swipeLeft()
            case "right": app.swipeRight()
            default:
                XCTFail("swipe requires direction up/down/left/right")
            }
        default:
            XCTFail("Unsupported ios-use action type: \(action.type)")
        }
    }

    private func tapElement(labeled text: String, in app: XCUIApplication) throws {
        let candidates: [XCUIElement] = [
            app.buttons[text],
            app.staticTexts[text],
            app.cells[text],
            app.otherElements[text],
            app.descendants(matching: .any)[text],
        ]
        for element in candidates where element.waitForExistence(timeout: 8.0) {
            element.tap()
            return
        }
        XCTFail("Could not find tappable element labeled '\(text)'")
    }
}
