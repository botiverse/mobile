import UIKit
import XCTest

// Persistent XCUITest loop for QA acceptance AND ios-use interactions.
//
// One xcodebuild `test-without-building` invocation hosts the WHOLE run: the
// app launches ONCE and this test then serves actions from the host over a
// file protocol, so multi-turn flows like login keep their UI state. ios-use
// tap/tap-text/interact now speak this same loop. The per-batch
// IosUseInteractionTests harness cannot keep UI state or deliver taps into
// Kuikly Home — a new XCTest process that only activate()s an already-running
// app finds the nodes but the taps do not reach Kuikly.
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
        // task #232/#233: press-and-drag needs an END point and a hold duration.
        // Whole-screen swipeLeft/Right cannot express "start at the screen edge"
        // (edge-back) or "drag a short distance and expect nothing to happen" —
        // substituting one for the other tests a different gesture than the case
        // specifies, which is worst when the criterion is an absence.
        let x2: Double?
        let y2: Double?
        let durationSeconds: Double?
        // Speed of the drag segment, in points per second. Without this the
        // segment always runs at XCUITest's default (fast) speed and a case that
        // specifies a SLOW drag silently becomes a flick.
        let velocityPointsPerSecond: Double?
        let holdSeconds: Double?
        let releaseHoldSeconds: Double?
        // ios-use / slock_ios_simctl may send window-point coordinates; the
        // historical one-shot IosUseInteractionTests accepted both spaces.
        let coordinateSpace: String?
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
        // task #232/#233: let a scripted case drive a fixture page DIRECTLY, with no
        // login. iosApp/ContentView.swift renders whatever SLOCK_VISUAL_FIXTURE_PAGE
        // names, but that variable has to reach the APP — the runner's own
        // environment is a different process, so SIMCTL_CHILD_* never arrives here.
        // Absent the variable this is a no-op and the normal login path is unchanged.
        if let fixturePage = env["QA_LOOP_FIXTURE_PAGE"], !fixturePage.isEmpty {
            app.launchEnvironment["SLOCK_VISUAL_FIXTURE_PAGE"] = fixturePage
            if let fixtureData = env["QA_LOOP_FIXTURE_PAGE_DATA"], !fixtureData.isEmpty {
                app.launchEnvironment["SLOCK_VISUAL_FIXTURE_PAGE_DATA"] = fixtureData
            }
        }
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
        case "wait":
            Thread.sleep(forTimeInterval: min(max(action.seconds ?? 1.0, 0.0), 10.0))
        case "tap":
            let c = try coordinate(action, in: app)
            c.tap()
        case "long_press":
            let c = try coordinate(action, in: app)
            c.press(forDuration: 0.8)
        case "tapText":
            guard let text = action.text, !text.isEmpty else {
                throw HarnessError.bad("tapText requires text")
            }
            return try tapText(text, in: app)
        case "drag":
            // Start -> end, with an explicit hold duration.
            // A longer duration is a slower drag, which is what distinguishes an
            // interactive-pop edge gesture and a short rebound from a flick.
            let from = try coordinate(action, in: app)
            let to = try coordinate(action, in: app, useEnd: true)
            let hold = min(max(action.holdSeconds ?? 0.05, 0.0), 10.0)
            // ⚠️ press(forDuration:thenDragTo:) holds, then drags at XCUITest's
            // DEFAULT speed. Run 33161205106 group 4 asked for a slow 10%-width
            // drag and got "long-press 1s, then flick" — and a flick turning the
            // page is correct pager physics, so the harness manufactured a
            // product FAIL. @CC-Cata caught it. Speed is the parameter that case
            // turns on, so it has to be expressed, not implied by a hold.
            let fromXY = [action.x ?? 0, action.y ?? 0]
            let toXY = [action.x2 ?? 0, action.y2 ?? 0]
            if let velocity = action.velocityPointsPerSecond, velocity > 0 {
                from.press(forDuration: hold,
                           thenDragTo: to,
                           withVelocity: XCUIGestureVelocity(rawValue: velocity),
                           thenHoldForDuration: min(max(action.releaseHoldSeconds ?? 0.1, 0.0), 5.0))
                return ["from": fromXY, "to": toXY,
                        "holdSeconds": hold, "velocityPointsPerSecond": velocity]
            }
            from.press(forDuration: hold, thenDragTo: to)
            return ["from": fromXY, "to": toXY, "holdSeconds": hold, "velocity": "default"]
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

    private func frame4(_ n: [String: Any]) -> [CGFloat] {
        if let frame = n["frame"] as? [CGFloat], frame.count == 4 { return frame }
        if let frame = n["frame"] as? [Double], frame.count == 4 { return frame.map { CGFloat($0) } }
        if let frame = n["frame"] as? [NSNumber], frame.count == 4 { return frame.map { CGFloat(truncating: $0) } }
        if let frame = n["frame"] as? [Any], frame.count == 4 {
            return frame.map { value in
                if let number = value as? NSNumber { return CGFloat(truncating: number) }
                if let number = value as? Double { return CGFloat(number) }
                return 0
            }
        }
        return [0, 0, 0, 0]
    }

    private func coordinate(_ action: Action, in app: XCUIApplication, useEnd: Bool = false) throws -> XCUICoordinate {
        guard let x = useEnd ? action.x2 : action.x, let y = useEnd ? action.y2 : action.y else {
            throw HarnessError.bad("\(action.type) requires \(useEnd ? "x2/y2" : "x/y")")
        }
        switch action.coordinateSpace ?? "normalized" {
        case "normalized":
            guard (0.0...1.0).contains(x), (0.0...1.0).contains(y) else {
                throw HarnessError.bad("normalized coordinates must be between 0.0 and 1.0")
            }
            return app.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
        case "points":
            let frame = app.frame
            guard frame.width > 0, frame.height > 0, x >= 0, y >= 0, x <= frame.width, y <= frame.height else {
                throw HarnessError.bad("point coordinates must fit the app frame \(frame)")
            }
            return app.coordinate(withNormalizedOffset: CGVector(dx: x / frame.width, dy: y / frame.height))
        default:
            throw HarnessError.bad("coordinateSpace must be normalized or points")
        }
    }

    // Find a labelled node and tap its center through app-normalized coordinates.
    // Do NOT use XCUIElement.tap() or isHittable: Kuikly Home rows exist in the
    // snapshot but often report isHittable=false, and a one-shot XCTest session
    // that only activate()s an already-running app does not deliver those taps.
    // The persistent loop launched this app, so coordinate taps reach Kuikly.
    private func tapText(_ wanted: String, in app: XCUIApplication) throws -> Any? {
        let exact = wanted.trimmingCharacters(in: .whitespacesAndNewlines)
        let needle = exact.lowercased()
        let deadline = Date().addingTimeInterval(8)
        var lastCount = 0
        while Date() < deadline {
            let nodes = try describeTree(app)
            lastCount = nodes.count
            func lab(_ n: [String: Any]) -> String {
                let label = (n["label"] as? String) ?? ""
                let identifier = (n["id"] as? String) ?? ""
                return label.isEmpty ? identifier : label
            }
            func val(_ n: [String: Any]) -> String { (n["value"] as? String) ?? "" }
            func area(_ n: [String: Any]) -> CGFloat {
                let frame = frame4(n)
                return max(1, frame[2]) * max(1, frame[3])
            }
            var matches = nodes.filter {
                lab($0).trimmingCharacters(in: .whitespacesAndNewlines) == exact
                    || val($0).trimmingCharacters(in: .whitespacesAndNewlines) == exact
            }
            if matches.isEmpty {
                matches = nodes.filter { lab($0).lowercased() == needle || val($0).lowercased() == needle }
            }
            if matches.isEmpty {
                matches = nodes.filter {
                    lab($0).lowercased().contains(needle) || val($0).lowercased().contains(needle)
                }
            }
            if let pick = (matches.filter { ($0["clickable"] as? Bool) == true }.isEmpty
                           ? matches : matches.filter { ($0["clickable"] as? Bool) == true })
                .min(by: { area($0) < area($1) }) {
                let frame = frame4(pick)
                let appFrame = app.frame
                guard appFrame.width > 0, appFrame.height > 0 else {
                    throw HarnessError.bad("tapText: app frame is empty")
                }
                let nx = (frame[0] + frame[2] / 2 - appFrame.origin.x) / appFrame.width
                let ny = (frame[1] + frame[3] / 2 - appFrame.origin.y) / appFrame.height
                app.coordinate(withNormalizedOffset: CGVector(dx: nx, dy: ny)).tap()
                return [
                    "label": lab(pick),
                    "x": nx,
                    "y": ny,
                    "matches": matches.count,
                ]
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        throw HarnessError.bad("tapText: no on-screen node matches \"\(wanted)\" (\(lastCount) labelled nodes)")
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

// Signed-in home screenshot on a chosen simulator runtime (ios-simulator-app.yml,
// home_screenshot=true). Lives in this file on purpose: the workflow copies this one file onto
// an older product ref (e.g. a release branch) before building, and a new file would also need
// a project.pbxproj entry there.
//
// Deterministic, no typing into the web page. This test and the workflow's host script
// (scripts/ci/ios_home_shot_host.mjs) take turns over files in $HOME_SHOT_IO_DIR:
//   1. test: tick the terms box, tap "Sign in to Raft"     -> signin-tapped.json
//      (the app starts a real app-login request and opens the web sign-in sheet)
//   2. host: read that request id from the app's container, approve it with the QA account
//      (CI secrets, API), `simctl openurl` the callback        -> callback-opened.json
//   3. test: confirm SpringBoard's "Open in “Raft”?", wait for the native tab bar (pick OranixSlockTest if a server picker shows),
//      dismiss the leftover sign-in sheet / system alerts       -> home-ready.json
//   4. host: `simctl io screenshot`                            -> shot-done.json
final class SignedInHomeShotTests: XCTestCase {
    private var ioDir = ""

    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    func testSignedInHomeWithNativeTabBar() throws {
        let env = ProcessInfo.processInfo.environment
        guard let dir = env["HOME_SHOT_IO_DIR"], !dir.isEmpty else {
            throw XCTSkip("HOME_SHOT_IO_DIR not set - home screenshot not requested")
        }
        ioDir = dir
        let app = XCUIApplication(bundleIdentifier: env["HOME_SHOT_BUNDLE"] ?? "build.raft.app")
        // iOS native navigation for this launch (Debug builds read RAFT_NATIVE_NAV=1|0); unset
        // leaves the app's own decision (server flag, default off).
        if let nativeNav = env["HOME_SHOT_NATIVE_NAV"], !nativeNav.isEmpty {
            app.launchEnvironment["RAFT_NATIVE_NAV"] = nativeNav
        }
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

        // 1. Sign-in page: tick the terms box, then the button.
        let signIn = element(app, label: "Sign in to Raft")
        guard signIn.waitForExistence(timeout: 120) else {
            fail("signin-tapped", "no \"Sign in to Raft\" on screen", app)
            return
        }
        let agreement = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH[c] %@", "I agree")).firstMatch
        guard agreement.waitForExistence(timeout: 10) else {
            fail("signin-tapped", "no terms agreement text", app)
            return
        }
        // The 18pt box sits 10pt left of the text, 2pt below its top (AuthTermsAgreement);
        // the text itself carries the Terms / Privacy links, so do not tap it.
        let a = agreement.frame
        tap(app, x: a.minX - 19, y: a.minY + 11)
        Thread.sleep(forTimeInterval: 1)
        tap(app, x: signIn.frame.midX, y: signIn.frame.midY)
        write("signin-tapped", ["ok": true])

        // 2. The host approves the request and opens the callback.
        guard waitFor("callback-opened", timeout: 240) != nil else {
            fail("home-ready", "host never opened the callback", app)
            return
        }

        // 3. Home: the system tab bar. Until the web sign-in sheet is closed, the shell under it is
        // hidden from accessibility (the sheet is modal), so: confirm "Open in “Raft”?" for the
        // callback, give the app a moment to exchange the code, then close the sheet, then look
        // for the tab bar. A server picker may come first; first-run alerts may follow.
        let tabBar = app.tabBars.firstMatch
        let safari = XCUIApplication(bundleIdentifier: "com.apple.SafariViewService")
        let deadline = Date().addingTimeInterval(150)
        var pickedServer = false
        var openedAt: Date?
        var sheetClosed = false
        var closeAttempts = 0
        var events: [String] = []
        while Date() < deadline {
            if let answered = answerAlert(springboard: springboard, app: app, events: &events) {
                if answered == "Open" { openedAt = Date() }
                Thread.sleep(forTimeInterval: 1)
                continue
            }
            if !sheetClosed, closeAttempts < 2, !(tabBar.exists && tabBar.frame.height > 0),
               let at = openedAt, Date().timeIntervalSince(at) > Double(6 + 6 * closeAttempts) {
                closeAttempts += 1
                sheetClosed = closeWebSheet(app: app, safari: safari)
                events.append("closed sheet: \(sheetClosed)")
                Thread.sleep(forTimeInterval: 2)
                continue
            }
            if tabBar.exists && tabBar.frame.height > 0 { break }
            let server = element(app, label: "OranixSlockTest")
            if !pickedServer && server.exists && server.frame.height > 0 {
                tap(app, x: server.frame.midX, y: server.frame.midY)
                pickedServer = true
                events.append("picked OranixSlockTest")
            }
            Thread.sleep(forTimeInterval: 1)
        }
        guard tabBar.exists else {
            fail("home-ready", "no native tab bar after the callback (\(events.joined(separator: "; ")))", app)
            return
        }
        // Late first-run alerts.
        for _ in 0..<5 {
            _ = answerAlert(springboard: springboard, app: app, events: &events)
            Thread.sleep(forTimeInterval: 1)
        }
        // Let avatars, badges and the channel list settle.
        Thread.sleep(forTimeInterval: 8)

        let buttons = tabBar.buttons.allElementsBoundByIndex.map { b -> [String: Any] in
            let f = b.frame
            return [
                "label": b.label, "identifier": b.identifier, "selected": b.isSelected,
                "frame": [f.origin.x, f.origin.y, f.size.width, f.size.height],
            ]
        }
        let f = tabBar.frame
        write("home-ready", [
            "ok": true,
            "ios": UIDevice.current.systemVersion,
            "device": UIDevice.current.name,
            "pickedServer": pickedServer,
            "events": events,
            "tabBarFrame": [f.origin.x, f.origin.y, f.size.width, f.size.height],
            "tabBarButtons": buttons,
        ])
        attach(app, name: "home")
        // 4. Keep the app up until the host has its screenshot.
        _ = waitFor("shot-done", timeout: 120)
        if env["HOME_SHOT_ARTIFACT"] == "1" {
            artifactDesktopMode(app)
        }
    }

    // Optional (HOME_SHOT_ARTIFACT=1): open an Artifact the host found (it opens the message that
    // links it with `simctl openurl`), screenshot it, turn on Desktop Mode from the ⋯ menu,
    // screenshot again, then pinch to zoom in. The host takes each screenshot on the `-ready` file.
    private func artifactDesktopMode(_ app: XCUIApplication) {
        guard let data = waitFor("artifact-target", timeout: 180),
              let target = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let title = target["title"] as? String, !title.isEmpty else {
            fail("artifact-open", "host found no Artifact", app)
            return
        }
        // The message's Artifact chip or card: a small element whose label has the title (the
        // message text and the whole list carry it too, so not the first match). Prefer the last
        // one above the composer.
        _ = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
            .waitForExistence(timeout: 60)
        Thread.sleep(forTimeInterval: 3)
        let composerTop = app.textViews.firstMatch.exists ? app.textViews.firstMatch.frame.minY : app.frame.maxY
        let candidates = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", title)).allElementsBoundByIndex
            .filter { $0.frame.height > 10 && $0.frame.height < 260 && $0.frame.maxY < composerTop && $0.frame.minY > 120 }
        guard let chip = candidates.last else {
            fail("artifact-open", "no chip for \"\(title)\" in the opened message", app)
            return
        }
        write("artifact-chip", ["label": chip.label, "frame": [chip.frame.minX, chip.frame.minY, chip.frame.width, chip.frame.height]])
        tap(app, x: chip.frame.midX, y: chip.frame.midY)
        // The viewer's WebView, then its title bar ⋯ (tooltip "More").
        _ = app.webViews.firstMatch.waitForExistence(timeout: 60)
        let more = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "More")).firstMatch
        guard more.waitForExistence(timeout: 60) else {
            fail("artifact-open", "no ⋯ More in the Artifact viewer (Desktop Mode not offered?)", app)
            return
        }
        Thread.sleep(forTimeInterval: 12)
        attach(app, name: "artifact")
        write("artifact-open", ["ok": true])
        _ = waitFor("artifact-open-shot", timeout: 60)

        tap(app, x: more.frame.midX, y: more.frame.midY)
        let item = element(app, label: "Desktop Mode")
        guard item.waitForExistence(timeout: 10) else {
            fail("artifact-desktop", "no Desktop Mode item in the ⋯ menu", app)
            return
        }
        tap(app, x: item.frame.midX, y: item.frame.midY)
        Thread.sleep(forTimeInterval: 15)
        attach(app, name: "artifact-desktop")
        write("artifact-desktop", ["ok": true])
        _ = waitFor("artifact-desktop-shot", timeout: 60)

        // Pinch zoom: the fitted desktop page must still zoom in. Under Compose the WKWebView is
        // not always exposed as a web view, so pinch the app itself: its centre is the page.
        let web = app.webViews.firstMatch
        (web.exists ? web : app).pinch(withScale: 2.5, velocity: 2)
        Thread.sleep(forTimeInterval: 3)
        attach(app, name: "artifact-desktop-zoomed")
        write("artifact-zoomed", ["ok": true, "webView": web.exists])
        _ = waitFor("artifact-zoomed-shot", timeout: 60)

        // Activity -> the Artifact's row (#4316), if Activity lists it: back to the tab bar first.
        let tabBar = app.tabBars.firstMatch
        for _ in 0..<4 {
            if tabBar.exists && tabBar.frame.height > 0 && tabBar.isHittable { break }
            let back = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Back", "Navigate up")).firstMatch
            if back.exists { back.tap() } else { tap(app, x: 32, y: 100) }
            Thread.sleep(forTimeInterval: 2)
        }
        let activityTab = tabBar.buttons["Activity"]
        guard activityTab.exists else {
            write("activity-open", ["ok": false, "error": "no Activity tab"])
            return
        }
        activityTab.tap()
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
        guard row.waitForExistence(timeout: 20) else {
            attach(app, name: "activity")
            write("activity-open", ["ok": false, "error": "Activity lists no row for the Artifact"])
            _ = waitFor("activity-open-shot", timeout: 60)
            return
        }
        tap(app, x: row.frame.midX, y: row.frame.midY)
        let opened = app.webViews.firstMatch.waitForExistence(timeout: 30)
        Thread.sleep(forTimeInterval: 8)
        attach(app, name: "activity-artifact")
        write("activity-open", ["ok": true, "viewerWebView": opened])
        _ = waitFor("activity-open-shot", timeout: 60)
    }

    // One system alert, if any: "Open in “Raft”?" (the callback) -> Open; the web sign-in
    // prompt "“Raft” Wants to Use … to Sign In" -> Cancel (no sheet needed: the request was
    // approved through the API, and closing the browser keeps the pending request); anything
    // else (first-run permissions) -> Allow / OK. Returns the button tapped.
    private func answerAlert(springboard: XCUIApplication, app: XCUIApplication, events: inout [String]) -> String? {
        for host in [springboard, app] {
            let alert = host.alerts.firstMatch
            guard alert.exists else { continue }
            let label = alert.label
            let order: [String]
            if label.hasPrefix("Open in") {
                order = ["Open"]
            } else if label.localizedCaseInsensitiveContains("to Sign In") {
                order = ["Cancel"]
            } else {
                order = ["Allow", "OK", "Don’t Allow", "Don't Allow", "Cancel"]
            }
            if let button = order.map({ alert.buttons[$0] }).first(where: { $0.exists }) {
                let name = button.label
                events.append("alert \(label): \(name)")
                button.tap()
                return name
            }
        }
        return nil
    }

    // The web sign-in sheet's close (X) button: by label when the sheet exposes one, else its
    // spot in the sheet's top-left corner.
    private func closeWebSheet(app: XCUIApplication, safari: XCUIApplication) -> Bool {
        // Query SafariViewService only while it runs: a snapshot of a gone process fails the test
        // (kAXErrorServerNotFound, runs 41845886545 and 41877110876).
        let safariUp = safari.state == .runningForeground || safari.state == .runningBackground
        for host in safariUp ? [safari, app] : [app] {
            for label in ["Cancel", "Close", "Done"] {
                let button = host.buttons[label].firstMatch
                if button.exists && button.frame.minY < 200 {
                    button.tap()
                    return true
                }
            }
        }
        let host = safariUp && safari.exists ? safari : app
        host.coordinate(withNormalizedOffset: CGVector(dx: 0.095, dy: 0.115)).tap()
        return false
    }

    private func element(_ app: XCUIApplication, label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    // Coordinate taps: Compose nodes can report isHittable=false yet take a tap at their frame.
    private func tap(_ app: XCUIApplication, x: CGFloat, y: CGFloat) {
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: x, dy: y)).tap()
    }

    private func attach(_ app: XCUIApplication, name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "\(name)-tree"
        tree.lifetime = .keepAlways
        add(tree)
    }

    private func fail(_ stage: String, _ reason: String, _ app: XCUIApplication) {
        attach(app, name: "failure-\(stage)")
        write(stage, ["ok": false, "error": reason])
        XCTFail(reason)
    }

    private func waitFor(_ name: String, timeout: TimeInterval) -> Data? {
        let path = "\(ioDir)/\(name).json"
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let data = FileManager.default.contents(atPath: path) { return data }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return nil
    }

    // tmp + rename so the polling host never reads a half-written file.
    private func write(_ name: String, _ json: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: json) else { return }
        let path = "\(ioDir)/\(name).json"
        try? data.write(to: URL(fileURLWithPath: path + ".tmp"))
        try? FileManager.default.removeItem(atPath: path)
        try? FileManager.default.moveItem(atPath: path + ".tmp", toPath: path)
    }
}
