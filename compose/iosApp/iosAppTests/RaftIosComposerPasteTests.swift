import UIKit
import XCTest
import shared

/// Paste in the composer's editor with text on the clipboard: the edit menu / keyboard Paste is
/// offered (`canPerformAction(paste:)`) and pastes the text. 1.24.0 build 2 lost both on iPhone
/// (the composer's image paste wiring, #4609/#4619). Runs a real Compose text field in this host
/// app, with the image paste hook RaftIosAppDelegate installed at launch.
final class RaftIosComposerPasteTests: XCTestCase {
    private var window: UIWindow?
    private var savedItems: [[String: Any]] = []

    override func setUp() {
        super.setUp()
        savedItems = UIPasteboard.general.items
    }

    override func tearDown() {
        if let window {
            window.endEditing(true)
            window.isHidden = true
            window.rootViewController = nil
        }
        window = nil
        UIPasteboard.general.items = savedItems
        super.tearDown()
    }

    func testPlainFieldOffersAndPastesText() throws {
        try assertPastesText(composerClipboard: false, reportsFocus: false)
    }

    func testComposerClipboardOffersAndPastesText() throws {
        try assertPastesText(composerClipboard: true, reportsFocus: false)
    }

    func testComposerImagePasteHookOffersAndPastesText() throws {
        try assertPastesText(composerClipboard: false, reportsFocus: true)
    }

    /// The composer as shipped: its clipboard and the image paste hook together.
    func testComposerOffersAndPastesText() throws {
        try assertPastesText(composerClipboard: true, reportsFocus: true)
    }

    /// Only an image on the clipboard: the composer still offers Paste (it opens the image editor).
    func testComposerOffersPasteForAnImage() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
        UIPasteboard.general.items = []
        UIPasteboard.general.image = image
        let editor = try focusedEditor(composerClipboard: true, reportsFocus: true)
        XCTAssertTrue(
            waitFor(seconds: 3) { editor.canPerformAction(#selector(UIResponderStandardEditActions.paste(_:)), withSender: nil) },
            "Paste offered for an image (\(type(of: editor)))"
        )
    }

    private func assertPastesText(composerClipboard: Bool, reportsFocus: Bool, file: StaticString = #filePath, line: UInt = #line) throws {
        let text = "raft paste \(Int.random(in: 1000...9999))"
        UIPasteboard.general.items = []
        UIPasteboard.general.string = text
        let editor = try focusedEditor(composerClipboard: composerClipboard, reportsFocus: reportsFocus)
        let paste = #selector(UIResponderStandardEditActions.paste(_:))
        XCTAssertTrue(
            waitFor(seconds: 3) { editor.canPerformAction(paste, withSender: nil) },
            "Paste offered with text on the clipboard (\(type(of: editor)))",
            file: file, line: line
        )
        editor.perform(paste, with: nil)
        XCTAssertTrue(
            waitFor(seconds: 3) { self.editorText(editor) == text },
            "the clipboard's text pasted; editor has \(String(describing: editorText(editor)))",
            file: file, line: line
        )
    }

    private func focusedEditor(composerClipboard: Bool, reportsFocus: Bool) throws -> UIResponder {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: UIScreen.main.bounds)
        window.frame = UIScreen.main.bounds
        window.windowLevel = .alert + 1
        window.rootViewController = RaftIosComposerPasteProbeKt.raftComposerPasteProbeViewController(
            composerClipboard: composerClipboard,
            reportsFocus: reportsFocus
        )
        window.makeKeyAndVisible()
        self.window = window
        var editor: UIResponder?
        _ = waitFor(seconds: 10) {
            editor = Self.firstResponder(in: window)
            return editor != nil
        }
        let found = try XCTUnwrap(editor, "the probe's text field took the focus")
        // The host's focus capture runs on the next turns and 0.3 s later (RaftIosImagePaste).
        _ = waitFor(seconds: 0.6) { false }
        return found
    }

    private static func firstResponder(in view: UIView) -> UIResponder? {
        if view.isFirstResponder, view is UITextInput { return view }
        for subview in view.subviews {
            if let found = firstResponder(in: subview) { return found }
        }
        return nil
    }

    private func editorText(_ editor: UIResponder) -> String? {
        guard let input = editor as? UITextInput,
              let range = input.textRange(from: input.beginningOfDocument, to: input.endOfDocument)
        else { return nil }
        return input.text(in: range)
    }

    private func waitFor(seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        return condition()
    }
}
