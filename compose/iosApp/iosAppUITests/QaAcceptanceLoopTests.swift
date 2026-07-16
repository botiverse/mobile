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
        continueAfterFailure = false
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

        let app = XCUIApplication(bundleIdentifier: bundleIdentifier)
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
                let data = try perform(action, in: app)
                payload = ["ok": true]
                if let data { payload["data"] = data }
            } catch {
                payload = ["ok": false, "error": String(describing: error).prefix(300).description]
            }
            writeResult(ioDir, step: step, payload: payload)
            step += 1
        }
    }

    private func perform(_ action: Action, in app: XCUIApplication) throws -> Any? {
        switch action.type {
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
            app.typeText(text)
        case "key":
            switch action.text {
            case "enter": app.typeText("\n")
            case "del": app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: max(action.count ?? 1, 1)))
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

    private func normalized(_ action: Action, in app: XCUIApplication) throws -> XCUICoordinate {
        guard let x = action.x, let y = action.y, x >= 0, x <= 1, y >= 0, y <= 1 else {
            throw HarnessError.bad("tap/long_press require normalized x/y in 0..1")
        }
        return app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
    }

    // The host-side dump_ui twin: one snapshot IPC, then a plain walk emitting
    // labelled/interactive nodes with frames in points. The node runner applies
    // the same semantic compression as the Android uiautomator dump.
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
            if editable || clickable || !label.isEmpty || !value.isEmpty || s.hasFocus {
                nodes.append([
                    "label": label,
                    "value": value,
                    "id": s.identifier,
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
