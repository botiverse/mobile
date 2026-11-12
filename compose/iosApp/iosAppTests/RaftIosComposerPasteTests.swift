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

    // MARK: The edit menu a long press shows (iOS 16+: UIEditMenuInteraction on CMPEditMenuView)

    /// Empty composer, only an image on the clipboard: CMP offers no item at all, so before the
    /// host's menu hook a long press showed nothing. The menu now offers Paste Image, it is on
    /// screen, and choosing it opens the image in the composer (a host image paste is delivered).
    func testEmptyComposerMenuOffersPasteImageForAnImage() throws {
        try assertMenuPastesImage(initialText: "")
    }

    /// Text in the composer, only an image on the clipboard: Select All and Paste Image.
    func testComposerWithTextMenuOffersPasteImageForAnImage() throws {
        try assertMenuPastesImage(initialText: "hello")
    }

    /// Text on the clipboard: the system's Paste, and no Paste Image next to it.
    func testComposerMenuKeepsSystemPasteForText() throws {
        guard #available(iOS 16.0, *) else { throw XCTSkip("UIEditMenuInteraction is iOS 16+") }
        UIPasteboard.general.items = []
        UIPasteboard.general.string = "raft menu text"
        let editor = try focusedEditor(composerClipboard: true, reportsFocus: true)
        let menu = try showEditMenu()
        let paste = #selector(UIResponderStandardEditActions.paste(_:))
        let commands = Self.commands(in: menu.children)
        XCTAssertTrue(commands.contains { $0.action == paste }, "Paste offered: \(Self.describe(menu))")
        XCTAssertFalse(Self.actions(in: menu.children).contains { $0.title == Self.pasteImageTitle }, "no Paste Image: \(Self.describe(menu))")
        XCTAssertTrue(waitFor(seconds: 3) { self.visibleMenuLabels().contains { $0.localizedCaseInsensitiveContains("paste") } },
                      "Paste on screen; visible \(visibleMenuLabels())")
        XCTAssertFalse(visibleMenuLabels().contains(Self.pasteImageTitle), "no Paste Image on screen")
        _ = editor
    }

    private static var pasteImageTitle: String { RaftIosComposerImagePasteKt.raftComposerPasteImageLabel() }

    private func assertMenuPastesImage(initialText: String, file: StaticString = #filePath, line: UInt = #line) throws {
        guard #available(iOS 16.0, *) else { throw XCTSkip("UIEditMenuInteraction is iOS 16+") }
        UIPasteboard.general.items = []
        UIPasteboard.general.image = Self.redImage()
        let editor = try focusedEditor(composerClipboard: false, reportsFocus: true, initialText: initialText)
        let menu = try showEditMenu(file: file, line: line)
        let pasteImage = Self.actions(in: menu.children).first { $0.title == Self.pasteImageTitle }
        XCTAssertNotNil(pasteImage, "Paste Image: \(Self.describe(menu))", file: file, line: line)
        XCTAssertTrue(
            waitFor(seconds: 3) { self.visibleMenuLabels().contains(Self.pasteImageTitle) },
            "Paste Image on screen; visible \(visibleMenuLabels())",
            file: file, line: line
        )
        // Choosing it runs its handler (as UIKit does on a tap).
        let before = RaftIosComposerPasteProbeKt.raftComposerPasteProbeImagePasteCount()
        if let pasteImage { Self.run(pasteImage) }
        XCTAssertTrue(
            waitFor(seconds: 5) { RaftIosComposerPasteProbeKt.raftComposerPasteProbeImagePasteCount() > before },
            "the image went to the composer (image editor flow)",
            file: file, line: line
        )
        XCTAssertEqual(editorText(editor), initialText, "the field's text unchanged", file: file, line: line)
    }

    /// Shows the probe field's menu as CMP does after a long press and returns the menu UIKit got
    /// from the editor (RaftIosImagePaste's hook posts it).
    @available(iOS 16.0, *)
    private func showEditMenu(file: StaticString = #filePath, line: UInt = #line) throws -> UIMenu {
        var received: UIMenu?
        let observer = NotificationCenter.default.addObserver(
            forName: Notification.Name("RaftComposerEditMenu"), object: nil, queue: .main
        ) { received = $0.object as? UIMenu }
        defer { NotificationCenter.default.removeObserver(observer) }
        XCTAssertTrue(waitFor(seconds: 3) { RaftIosComposerPasteProbeKt.raftComposerPasteProbeShowEditMenu() },
                      "probe laid out", file: file, line: line)
        _ = waitFor(seconds: 5) { received != nil }
        return try XCTUnwrap(received, "UIKit asked the composer editor for its edit menu", file: file, line: line)
    }

    private static func commands(in elements: [UIMenuElement]) -> [UICommand] {
        elements.flatMap { element -> [UICommand] in
            if let command = element as? UICommand { return [command] }
            if let menu = element as? UIMenu { return commands(in: menu.children) }
            return []
        }
    }

    /// Runs [action]'s handler as a tap does (UIAction has no public perform).
    private static func run(_ action: UIAction) {
        let perform = NSSelectorFromString("performWithSender:target:")
        if action.responds(to: perform) {
            action.perform(perform, with: nil, with: nil)
        } else if let handler = action.value(forKey: "handler") {
            typealias Handler = @convention(block) (UIAction) -> Void
            unsafeBitCast(handler as AnyObject, to: Handler.self)(action)
        } else {
            XCTFail("cannot run \(action.title)")
        }
    }

    private static func actions(in elements: [UIMenuElement]) -> [UIAction] {
        elements.flatMap { element -> [UIAction] in
            if let action = element as? UIAction { return [action] }
            if let menu = element as? UIMenu { return actions(in: menu.children) }
            return []
        }
    }

    private static func describe(_ menu: UIMenu) -> String {
        func walk(_ elements: [UIMenuElement]) -> [String] {
            elements.flatMap { element -> [String] in
                if let command = element as? UICommand { return ["\(command.title)(\(NSStringFromSelector(command.action)))"] }
                if let action = element as? UIAction { return ["\(action.title)[action]"] }
                if let submenu = element as? UIMenu { return walk(submenu.children) }
                return [element.title]
            }
        }
        return walk(menu.children).joined(separator: ", ")
    }

    /// Labels on screen in every window: the presented edit menu's items among them.
    private func visibleMenuLabels() -> [String] {
        var labels: [String] = []
        func collect(_ object: NSObject, depth: Int) {
            guard depth < 40 else { return }
            if let label = object.accessibilityLabel, !label.isEmpty { labels.append(label) }
            if let text = (object as? UILabel)?.text, !text.isEmpty { labels.append(text) }
            if let button = object as? UIButton, let title = button.configuration?.title ?? button.title(for: .normal) {
                labels.append(title)
            }
            if let view = object as? UIView {
                if view.isHidden || view.alpha < 0.01 { return }
                view.subviews.forEach { collect($0, depth: depth + 1) }
            }
        }
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        windows.filter { !$0.isHidden }.forEach { collect($0, depth: 0) }
        return labels
    }

    private static func redImage() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
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

    private func focusedEditor(composerClipboard: Bool, reportsFocus: Bool, initialText: String = "") throws -> UIResponder {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: UIScreen.main.bounds)
        window.frame = UIScreen.main.bounds
        window.windowLevel = .alert + 1
        window.rootViewController = RaftIosComposerPasteProbeKt.raftComposerPasteProbeViewController(
            composerClipboard: composerClipboard,
            reportsFocus: reportsFocus,
            initialText: initialText
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
