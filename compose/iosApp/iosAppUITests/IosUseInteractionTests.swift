import XCTest

// One-shot batch harness. ios-use tap/tap-text/interact no longer use this:
// a new XCTest process that only activate()s an already-running app can see
// Kuikly Home nodes and still fail to deliver the tap. Drive those actions
// through QaAcceptanceLoopTests instead.

final class IosUseInteractionTests: XCTestCase {
    private struct Action: Decodable {
        let type: String
        let text: String?
        let x: Double?
        let y: Double?
        let x2: Double?
        let y2: Double?
        let coordinateSpace: String?
        let direction: String?
        let seconds: Double?
        let count: Int?
        let waitAfter: Double?
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testRunIosUseActions() throws {
        let environment = ProcessInfo.processInfo.environment
        let bundleIdentifier = environment["IOS_USE_BUNDLE"] ?? "build.raft.app"
        let app = XCUIApplication(bundleIdentifier: bundleIdentifier)
        if app.state == .notRunning {
            app.launch()
        } else {
            app.activate()
        }

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
            try coordinate(for: action, in: app).tap()
        case "swipe":
            if action.x != nil || action.y != nil || action.x2 != nil || action.y2 != nil {
                let start = try coordinate(for: action, in: app)
                let end = try coordinate(for: action, in: app, useEnd: true)
                start.press(forDuration: 0.05, thenDragTo: end)
                return
            }
            switch action.direction {
            case "up": app.swipeUp()
            case "down": app.swipeDown()
            case "left": app.swipeLeft()
            case "right": app.swipeRight()
            default:
                XCTFail("swipe requires direction up/down/left/right")
            }
        case "text":
            guard let text = action.text, !text.isEmpty else {
                XCTFail("text requires text")
                return
            }
            try typeString(text, in: app)
        case "key":
            try typeKey(action, in: app)
        default:
            XCTFail("Unsupported ios-use action type: \(action.type)")
        }
    }

    private func coordinate(for action: Action, in app: XCUIApplication, useEnd: Bool = false) throws -> XCUICoordinate {
        guard let x = useEnd ? action.x2 : action.x, let y = useEnd ? action.y2 : action.y else {
            XCTFail("\(action.type) requires \(useEnd ? "x2/y2" : "x/y")")
            throw InteractionError.invalidCoordinate
        }
        switch action.coordinateSpace ?? "normalized" {
        case "normalized":
            guard (0.0...1.0).contains(x), (0.0...1.0).contains(y) else {
                XCTFail("normalized coordinates must be between 0.0 and 1.0")
                throw InteractionError.invalidCoordinate
            }
            return app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
        case "points":
            let frame = app.frame
            guard x >= 0, x <= frame.width, y >= 0, y <= frame.height, frame.width > 0, frame.height > 0 else {
                XCTFail("point coordinates must fit the app frame \(frame)")
                throw InteractionError.invalidCoordinate
            }
            return app.coordinate(withNormalizedOffset: CGVector(dx: x / frame.width, dy: y / frame.height))
        default:
            XCTFail("coordinateSpace must be normalized or points")
            throw InteractionError.invalidCoordinate
        }
    }

    private enum InteractionError: Error {
        case invalidCoordinate
        case missingKeyboardKey(String)
    }

    private func typeKey(_ action: Action, in app: XCUIApplication) throws {
        let keyboard = app.keyboards.firstMatch
        switch action.text {
        case "enter":
            guard tapKeyboardReturn(keyboard) else { throw InteractionError.missingKeyboardKey("Return/Done") }
        case "del":
            let count = min(max(action.count ?? 1, 1), 80)
            for _ in 0..<count {
                guard tapKey(keyboard, ["delete", "Delete", "Backspace", "backspace"]) else {
                    throw InteractionError.missingKeyboardKey("Delete")
                }
            }
        case "esc":
            if app.keyboards.count > 0 {
                let done = app.toolbars.buttons["Done"].firstMatch
                if done.exists {
                    done.tap()
                } else {
                    app.swipeDown()
                }
            }
        default:
            XCTFail("key requires enter|del|esc")
        }
    }

    private func typeString(_ text: String, in app: XCUIApplication) throws {
        let keyboard = app.keyboards.firstMatch
        guard keyboard.waitForExistence(timeout: 3.0) else {
            throw InteractionError.missingKeyboardKey("software keyboard")
        }
        for character in text {
            guard typeCharacter(character, keyboard: keyboard) else {
                throw InteractionError.missingKeyboardKey(String(character))
            }
        }
    }

    private func typeCharacter(_ character: Character, keyboard: XCUIElement) -> Bool {
        if character == "\n" { return tapKeyboardReturn(keyboard) }
        if character == " " { return tapKey(keyboard, ["space"]) }
        if character.isLetter {
            let wanted = String(character)
            let alternate = character.isUppercase ? wanted.lowercased() : wanted.uppercased()
            if tapExactKey(keyboard, wanted) { return true }
            if keyExists(keyboard, alternate) {
                _ = tapKey(keyboard, ["shift", "Shift", "capital letters", "Capital letters", "shift key"])
                return tapExactKey(keyboard, wanted)
            }
            return false
        }
        let switched = tapKey(keyboard, ["more", "numbers", "More", "Numbers"])
        let hit = tapExactKey(keyboard, String(character))
        if switched { _ = tapKey(keyboard, ["letters", "ABC", "more", "Letters"]) }
        return hit
    }

    private func keyExists(_ keyboard: XCUIElement, _ identifier: String) -> Bool {
        keyboard.keys[identifier].exists || keyboard.buttons[identifier].exists
    }

    private func tapExactKey(_ keyboard: XCUIElement, _ identifier: String) -> Bool {
        let key = keyboard.keys[identifier]
        if key.exists { key.tap(); return true }
        let button = keyboard.buttons[identifier]
        if button.exists { button.tap(); return true }
        return false
    }

    private func tapKey(_ keyboard: XCUIElement, _ identifiers: [String]) -> Bool {
        for identifier in identifiers where tapExactKey(keyboard, identifier) {
            return true
        }
        return false
    }

    private func tapKeyboardReturn(_ keyboard: XCUIElement) -> Bool {
        tapKey(keyboard, ["Return", "return", "Go", "Send", "Search", "Done", "Next", "next", "\n"])
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
