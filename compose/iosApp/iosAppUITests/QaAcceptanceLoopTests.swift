import XCTest

// QA acceptance loop harness (agent-in-CI, iOS leg).
//
// One xcodebuild `test-without-building` invocation hosts the WHOLE acceptance
// run: the app launches ONCE and this test then serves actions from the host
// runner (scripts/qa/acceptance_agent.mjs --platform ios) over a file
// protocol, so multi-turn flows like login keep their UI state. This is what
// the per-batch IosUseInteractionTests harness cannot do — XCUIApplication
// .launch() restarts the app on every batch.
//
// Protocol (dir = $QA_LOOP_IO_DIR, a host path — simulator processes share the
// host filesystem):
//   host writes  <dir>/cmd-<n>.json   {"type":"tap","x":0.5,"y":0.5,...}
//   test writes  <dir>/res-<n>.json   {"ok":true,"data":...} / {"ok":false,"error":...}
//   n starts at 1 and increments by exactly 1; "finish" ends the loop.
// Files are written to a .tmp path then renamed, so a reader never sees a
// partial file. Coordinates are NORMALIZED (0..1); "describe" frames are in
// POINTS — the host converts using the screenshot pixel size it already has.
//
// Screenshots are NOT served here: the host takes them via
// `xcrun simctl io <udid> screenshot`, which needs no app cooperation.
final class QaAcceptanceLoopTests: XCTestCase {
    private struct Action: Decodable {
        let type: String
        let text: String?
        let x: Double?
        let y: Double?
        let direction: String?
        let seconds: Double?
        let count: Int?
    }

    override func setUpWithError() throws {
        // This is a PERSISTENT server loop: one XCTest method serves many
        // actions for a whole acceptance run. A single failing interaction
        // (e.g. XCUIApplication.typeText hitting Kuikly's non-native text
        // field, which reports no native keyboard focus) must NOT abort the
        // method — that would strand the loop and time out every later action.
        // Keep going; each action reports its own ok/error result instead.
        continueAfterFailure = true
    }

    func testRunQaAcceptanceLoop() throws {
        let env = ProcessInfo.processInfo.environment
        guard let ioDir = env["QA_LOOP_IO_DIR"], !ioDir.isEmpty else {
            throw XCTSkip("QA_LOOP_IO_DIR not set — loop harness not requested")
        }
        let bundleIdentifier = env["QA_LOOP_BUNDLE"] ?? "build.raft.app"
        // Idle cap so an abandoned run (host runner crashed) cannot hold the
        // simulator forever; the host polls results far faster than this.
        let idleTimeout = Double(env["QA_LOOP_IDLE_TIMEOUT"] ?? "300") ?? 300

        var app = XCUIApplication(bundleIdentifier: bundleIdentifier)
        app.launch()
        writeReady(ioDir)

        let fm = FileManager.default
        var step = 1
        var idleSince = Date()
        while true {
            let cmdPath = "\(ioDir)/cmd-\(step).json"
            guard fm.fileExists(atPath: cmdPath) else {
                if Date().timeIntervalSince(idleSince) > idleTimeout {
                    writeResult(ioDir, step: step, payload: ["ok": false, "error": "loop idle timeout"])
                    return
                }
                Thread.sleep(forTimeInterval: 0.2)
                continue
            }
            idleSince = Date()
            var payload: [String: Any]
            do {
                let raw = try String(contentsOfFile: cmdPath, encoding: .utf8)
                let action = try JSONDecoder().decode(Action.self, from: Data(raw.utf8))
                if action.type == "finish" {
                    writeResult(ioDir, step: step, payload: ["ok": true])
                    return
                }
                let data = try perform(action, in: &app, bundleId: bundleIdentifier)
                payload = ["ok": true]
                if let data { payload["data"] = data }
            } catch {
                payload = ["ok": false, "error": String(describing: error).prefix(300).description]
            }
            writeResult(ioDir, step: step, payload: payload)
            step += 1
        }
    }

    private func perform(_ action: Action, in app: inout XCUIApplication, bundleId: String) throws -> Any? {
        switch action.type {
        case "launch":
            // (Re)launch THROUGH XCTest so the XCUIApplication handle rebinds
            // to the new process. An external `xcrun simctl launch` starts the
            // process but leaves this handle pointing at the dead one — every
            // later action then fails "Application <bundle> is not running"
            // (task #8, case-06 feedback submit termination).
            if app.state == .runningForeground || app.state == .runningBackground {
                app.terminate()
            }
            app = XCUIApplication(bundleIdentifier: bundleId)
            app.launch()
            let deadline = Date().addingTimeInterval(30)
            while app.state != .runningForeground && Date() < deadline {
                Thread.sleep(forTimeInterval: 0.5)
            }
            guard app.state == .runningForeground else {
                throw HarnessError.bad("launch: app did not reach foreground (state=\(app.state.rawValue))")
            }
            return ["state": "runningForeground"]
        case "system_alert_allow_photos_full_access":
            // Deterministic permission bootstrap (task #26, iOS 26 consent
            // extension): the SpringBoard Photos alert is outside the app's
            // accessibility tree, so it is resolved through XCUITest against
            // com.apple.springboard — native element taps only, never
            // coordinates. Fail-closed on every contract violation: no alert,
            // multiple alerts, no exact "Allow Full Access" button, duplicate
            // buttons, or the alert surviving the tap.
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let alerts = springboard.alerts
            let alertCount = alerts.count
            guard alertCount == 1 else {
                throw HarnessError.bad("system_alert: expected exactly one SpringBoard alert, found \(alertCount)")
            }
            let alert = alerts.element(boundBy: 0)
            let title = alert.label
            guard title.localizedCaseInsensitiveContains("Photo Library") else {
                throw HarnessError.bad("system_alert: the only alert is not a Photos permission alert (title: \(title))")
            }
            let allow = alert.buttons["Allow Full Access"]
            let allButtons = alert.buttons.allElementsBoundByIndex.map { $0.label }
            guard allow.exists else {
                throw HarnessError.bad("system_alert: no exact 'Allow Full Access' button (buttons: \(allButtons))")
            }
            let allowCount = alert.buttons.allElementsBoundByIndex.filter { $0.label == "Allow Full Access" }.count
            guard allowCount == 1 else {
                throw HarnessError.bad("system_alert: duplicate 'Allow Full Access' buttons (\(allowCount))")
            }
            let bounds = allow.frame
            allow.tap()
            Thread.sleep(forTimeInterval: 2.0)
            guard !alert.exists else {
                throw HarnessError.bad("system_alert: Photos alert still present after tapping Allow Full Access")
            }
            return [
                "alertCount": alertCount,
                "alertTitle": title,
                "button": "Allow Full Access",
                "buttonBounds": "[\(Int(bounds.minX)),\(Int(bounds.minY))][\(Int(bounds.maxX)),\(Int(bounds.maxY))]",
                "allButtons": allButtons,
                "dismissed": true,
            ]
        case "wait":
            Thread.sleep(forTimeInterval: min(max(action.seconds ?? 1.0, 0.0), 10.0))
        case "tap":
            let c = try normalized(action, in: app)
            c.tap()
        case "long_press":
            let c = try normalized(action, in: app)
            c.press(forDuration: 0.8)
        case "swipe":
            switch action.direction {
            case "up": app.swipeUp()
            case "down": app.swipeDown()
            case "left": app.swipeLeft()
            case "right": app.swipeRight()
            default: throw HarnessError.bad("swipe requires direction up/down/left/right")
            }
        case "text":
            guard let text = action.text, !text.isEmpty else { throw HarnessError.bad("text requires text") }
            try typeString(text, in: app)
        case "key":
            switch action.text {
            case "enter":
                guard tapKeyboardReturn(app.keyboards.firstMatch) else {
                    throw HarnessError.bad("key enter: no Return/Done key is available")
                }
            case "del":
                // Tap the on-screen delete key, NOT app.typeText (which retries
                // the native keyboard-focus assertion ~1s each on Kuikly fields
                // and hangs the loop — the case-02 `clear` failure). Bounded so a
                // missing key or over-count can't run long.
                let n = min(max(action.count ?? 1, 1), 80)
                let kb = app.keyboards.firstMatch
                for _ in 0..<n {
                    if !tapKey(kb, ["delete", "Delete", "Backspace", "backspace"]) { break }
                }
            case "back":
                // iOS has no BACK key; the platform idiom is the left-edge swipe.
                let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5))
                let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5))
                start.press(forDuration: 0.05, thenDragTo: end)
            case "esc":
                // Dismiss the keyboard if visible; harmless no-op otherwise.
                if app.keyboards.count > 0 {
                    app.toolbars.buttons["Done"].firstMatch.exists
                        ? app.toolbars.buttons["Done"].firstMatch.tap()
                        : app.swipeDown()
                }
            default: throw HarnessError.bad("key requires enter|del|back|esc")
            }
        case "describe":
            return try describeTree(app)
        default:
            throw HarnessError.bad("unsupported action type \(action.type)")
        }
        return nil
    }

    // Kuikly's iOS text field is not a native UITextField, so
    // XCUIApplication.typeText fails its "keyboard focus" assertion even with
    // the keyboard visibly up (see case-02/case-06). We post real key events by
    // tapping on-screen keyboard keys instead. There is NO typeText fallback:
    // it retries the focus assertion ~1s per char and would blow the host's 90s
    // per-action budget, hanging the whole loop. If any character could not be
    // tapped, the action FAILS with the skipped chars listed (never silently
    // types a partial string and lets a case go green on it).
    private func typeString(_ s: String, in app: XCUIApplication) throws {
        let kb = app.keyboards.firstMatch
        guard kb.waitForExistence(timeout: 3.0) else {
            throw HarnessError.bad("text: no keyboard on screen — tap the input field to focus it first")
        }
        var typed = 0
        var skipped: [String] = []
        for ch in s {
            if typeChar(ch, kb: kb) { typed += 1 } else { skipped.append(String(ch)) }
        }
        if typed != s.count {
            throw HarnessError.bad("text: typed \(typed)/\(s.count); no keyboard key for [\(skipped.joined())]")
        }
    }

    @discardableResult
    private func typeChar(_ ch: Character, kb: XCUIElement) -> Bool {
        if ch == "\n" { return tapKeyboardReturn(kb) }
        if ch == " " { return tapKey(kb, ["space"]) }
        // Letters: tap the key with the EXACT desired case. iOS auto-capitalizes
        // the first letter of a sentence, so a lowercase char may only be present
        // as its uppercase label (and vice-versa). If the desired-case key is
        // absent but the other case exists, toggle shift and retry — never accept
        // the wrong-case key (that would type the wrong letter).
        if ch.isLetter {
            let want = String(ch)
            let other = ch.isUppercase ? want.lowercased() : want.uppercased()
            if tapExactKey(kb, want) { return true }
            if keyExists(kb, other) {
                _ = tapKey(kb, ["shift", "Shift", "capital letters", "Capital letters", "shift key"])
                if tapExactKey(kb, want) { return true }
            }
            return false
        }
        // Digits / symbols live on the numbers plane behind the "more" toggle.
        let switched = tapKey(kb, ["more", "numbers", "More", "Numbers"])
        let hit = tapExactKey(kb, String(ch))
        if switched { _ = tapKey(kb, ["letters", "ABC", "more", "Letters"]) }
        return hit
    }

    private func keyExists(_ kb: XCUIElement, _ id: String) -> Bool {
        return kb.keys[id].exists || kb.buttons[id].exists
    }

    @discardableResult
    private func tapExactKey(_ kb: XCUIElement, _ id: String) -> Bool {
        let k = kb.keys[id]
        if k.exists { k.tap(); return true }
        let b = kb.buttons[id]
        if b.exists { b.tap(); return true }
        return false
    }

    // Tap the first existing key/button among the identifiers. Uses .exists
    // (fast, no retry) + .tap(); NO .isHittable gate (it reads false on some
    // Kuikly-hosted keys that are still tappable) and NO typeText (slow).
    @discardableResult
    private func tapKey(_ kb: XCUIElement, _ ids: [String]) -> Bool {
        for id in ids {
            let k = kb.keys[id]
            if k.exists { k.tap(); return true }
            let b = kb.buttons[id]
            if b.exists { b.tap(); return true }
        }
        return false
    }

    private func tapKeyboardReturn(_ kb: XCUIElement) -> Bool {
        return tapKey(kb, ["Return", "return", "Go", "Send", "Search", "Done", "Next", "next", "\n"])
    }

    private func normalized(_ action: Action, in app: XCUIApplication) throws -> XCUICoordinate {
        guard let x = action.x, let y = action.y, x >= 0, x <= 1, y >= 0, y <= 1 else {
            throw HarnessError.bad("tap/long_press require normalized x/y in 0..1")
        }
        return app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
    }

    // The host-side dump_ui twin: one snapshot IPC, then a plain walk emitting
    // labelled/interactive nodes with frames in points. The node runner applies
    // the same semantic compression as the Android uiautomator dump.
    // The host-side dump_ui twin: one snapshot IPC, then a plain walk emitting
    // labelled/interactive nodes with frames in points. The node runner applies
    // the same semantic compression as the Android uiautomator dump. Task #26
    // static review: also carry the verbatim identifier, elementType (raw +
    // readable), isEnabled and isSelected, and keep selected-but-unlabelled
    // nodes — the cell-selection contract is defined on exactly those fields.
    private func describeTree(_ app: XCUIApplication) throws -> [[String: Any]] {
        let snapshot = try app.snapshot()
        var nodes: [[String: Any]] = []
        func walk(_ s: XCUIElementSnapshot) {
            let type = s.elementType
            let label = s.label
            let value = (s.value as? String) ?? ""
            let editable = type == .textField || type == .secureTextField || type == .textView || type == .searchField
            let clickable = type == .button || type == .cell || type == .link || type == .segmentedControl
                || type == .switch || type == .checkBox || type == .menuItem || type == .tabBar
            if editable || clickable || !label.isEmpty || !value.isEmpty || s.hasFocus || s.isSelected {
                nodes.append([
                    "label": label,
                    "value": value,
                    "id": s.identifier,
                    "type": readableType(type),
                    "typeRaw": Int(type.rawValue),
                    "enabled": s.isEnabled,
                    "selected": s.isSelected,
                    "editable": editable,
                    "clickable": clickable,
                    "focused": s.hasFocus,
                    "frame": [s.frame.origin.x, s.frame.origin.y, s.frame.size.width, s.frame.size.height],
                ])
            }
            for child in s.children { walk(child) }
        }
        walk(snapshot)
        return nodes
    }

    private func readableType(_ type: XCUIElement.ElementType) -> String {
        switch type {
        case .button: return "button"
        case .cell: return "cell"
        case .image: return "image"
        case .staticText: return "staticText"
        case .textField: return "textField"
        case .secureTextField: return "secureTextField"
        case .textView: return "textView"
        case .searchField: return "searchField"
        case .link: return "link"
        case .segmentedControl: return "segmentedControl"
        case .switch: return "switch"
        case .checkBox: return "checkBox"
        case .menuItem: return "menuItem"
        case .tabBar: return "tabBar"
        case .window: return "window"
        case .scrollBar: return "scrollBar"
        case .toolbar: return "toolbar"
        default: return "other"
        }
    }

    private func writeReady(_ ioDir: String) {
        write(json: ["ok": true, "ready": true], to: "\(ioDir)/ready.json")
    }

    private func writeResult(_ ioDir: String, step: Int, payload: [String: Any]) {
        write(json: payload, to: "\(ioDir)/res-\(step).json")
    }

    // tmp + rename so the polling host never reads a half-written file.
    private func write(json: [String: Any], to path: String) {
        guard let data = try? JSONSerialization.data(withJSONObject: json) else { return }
        let tmp = path + ".tmp"
        try? data.write(to: URL(fileURLWithPath: tmp))
        try? FileManager.default.removeItem(atPath: path)
        try? FileManager.default.moveItem(atPath: tmp, toPath: path)
    }

    private enum HarnessError: Error {
        case bad(String)
    }
}
