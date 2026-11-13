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
    //
    // With only images on the clipboard the menu has exactly one Paste, as WeChat / Slack / Messages
    // do: the system's where the menu UIKit builds has it (iPad), else the composer's own, titled as
    // the system's (an empty composer on iPhone, where CMP's menu had nothing to show). Either one
    // opens the image in the composer.

    /// (a) Empty composer, only an image on the clipboard: one Paste on screen, and choosing it opens
    /// the image in the composer (a host image paste is delivered).
    func testEmptyComposerMenuOffersOnePasteForAnImage() throws {
        try assertMenuPastesImage(initialText: "")
    }

    /// (a) Text in the composer, only an image on the clipboard: Select All and one Paste.
    func testComposerWithTextMenuOffersOnePasteForAnImage() throws {
        try assertMenuPastesImage(initialText: "hello")
    }

    /// (b) The menu UIKit builds has the system's Paste (as on iPad, iOS 26.1), only an image on the
    /// clipboard: that Paste is the only one, the composer adds none, and choosing it (UIKit sends
    /// `paste:` to the first responder) opens the image in the composer.
    func testSystemPasteIsTheOnlyPasteForAnImage() throws {
        guard #available(iOS 16.0, *) else { throw XCTSkip("UIEditMenuInteraction is iOS 16+") }
        UIPasteboard.general.items = []
        UIPasteboard.general.image = Self.redImage()
        let editor = try focusedEditor(composerClipboard: false, reportsFocus: true)
        // Compose builds its menu for a pending toolbar request: make one, as a long press does.
        Self.log("b-real", try showEditMenu())
        let delegate = try XCTUnwrap(editor as? UIEditMenuInteractionDelegate, "the editor is the edit menu's delegate")
        let systemPaste = UICommand(title: "Paste", action: Self.paste)
        let suggested: [UIMenuElement] = [
            UIMenu(title: "", options: .displayInline, children: [
                UICommand(title: "Select All", action: #selector(UIResponderStandardEditActions.selectAll(_:))),
                systemPaste,
            ]),
        ]
        let interaction = UIEditMenuInteraction(delegate: nil)
        let configuration = UIEditMenuConfiguration(identifier: nil, sourcePoint: .zero)
        let menu = try XCTUnwrap(
            delegate.editMenuInteraction?(interaction, menuFor: configuration, suggestedActions: suggested),
            "a menu"
        )
        let pastes = Self.pasteItems(in: menu.children)
        XCTAssertEqual(pastes.count, 1, "one Paste: \(Self.describe(menu))")
        XCTAssertTrue((pastes.first as? UICommand).map { $0.action == Self.paste && $0.title == systemPaste.title } == true,
                      "the system's Paste kept: \(Self.describe(menu))")
        XCTAssertFalse(Self.actions(in: menu.children).contains { $0.identifier == Self.composerPasteIdentifier },
                       "the composer adds no Paste: \(Self.describe(menu))")

        let before = RaftIosComposerPasteProbeKt.raftComposerPasteProbeImagePasteCount()
        XCTAssertTrue(UIApplication.shared.sendAction(Self.paste, to: nil, from: systemPaste, for: nil), "paste: has a target")
        XCTAssertTrue(
            waitFor(seconds: 5) { RaftIosComposerPasteProbeKt.raftComposerPasteProbeImagePasteCount() > before },
            "the image went to the composer (image editor flow)"
        )
        XCTAssertEqual(editorText(editor), "", "the field's text unchanged")
    }

    /// (c) Text on the clipboard: the system's Paste only, the composer adds none.
    func testComposerMenuKeepsSystemPasteForText() throws {
        guard #available(iOS 16.0, *) else { throw XCTSkip("UIEditMenuInteraction is iOS 16+") }
        UIPasteboard.general.items = []
        UIPasteboard.general.string = "raft menu text"
        let editor = try focusedEditor(composerClipboard: true, reportsFocus: true)
        let shown = try showEditMenu()
        let menu = shown.menu
        Self.log("text", shown)
        let pastes = Self.pasteItems(in: menu.children)
        XCTAssertEqual(pastes.count, 1, "one Paste: \(Self.describe(menu))")
        XCTAssertTrue((pastes.first as? UICommand)?.action == Self.paste, "the system's Paste: \(Self.describe(menu))")
        XCTAssertFalse(Self.actions(in: menu.children).contains { $0.identifier == Self.composerPasteIdentifier },
                       "the composer adds no Paste: \(Self.describe(menu))")
        XCTAssertTrue(waitFor(seconds: 3) { self.visiblePasteLabels().count == 1 },
                      "one Paste on screen; visible \(visibleMenuLabels())")
        _ = editor
    }

    // MARK: DIAG (temporary): what UIKit shows for paste: with only an image on the clipboard

    func testDiagUIKitPasteVisibility() throws {
        guard #available(iOS 16.0, *) else { throw XCTSkip("iOS 16+") }
        UIPasteboard.general.items = []
        UIPasteboard.general.image = Self.redImage()
        let modes: [(String, DiagView.Mode, Bool)] = [
            ("passthrough", .passthrough, false),
            ("passthrough+pasteConfig", .passthrough, true),
            ("command", .command, false),
            ("command+pasteConfig", .command, true),
            ("action", .action, false),
        ]
        for (name, mode, config) in modes {
            let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: UIScreen.main.bounds)
            window.frame = UIScreen.main.bounds
            window.windowLevel = .alert + 1
            let controller = UIViewController()
            let view = DiagView(frame: CGRect(x: 20, y: 200, width: 300, height: 60))
            view.mode = mode
            if config { view.pasteConfiguration = UIPasteConfiguration(forAccepting: UIImage.self) }
            controller.view.addSubview(view)
            window.rootViewController = controller
            window.makeKeyAndVisible()
            _ = view.becomeFirstResponder()
            _ = waitFor(seconds: 0.5) { false }
            view.interaction.presentEditMenu(with: UIEditMenuConfiguration(identifier: nil, sourcePoint: CGPoint(x: 150, y: 30)))
            _ = waitFor(seconds: 2) { false }
            print("RAFTDIAG view \(name): firstResponder=\(view.isFirstResponder) suggested=[\(Self.describe(view.suggested))] returned=[\(view.returned.map(Self.describe) ?? "nil")] canPaste=\(view.canPasteAsked) visible=\(visibleMenuLabels())")
            view.interaction.dismissMenu()
            _ = waitFor(seconds: 0.5) { false }
            window.isHidden = true
            window.rootViewController = nil
        }
        // The composer editor with a paste configuration accepting images.
        let editor = try focusedEditor(composerClipboard: false, reportsFocus: true)
        print("RAFTDIAG editor class=\(type(of: editor)) pasteConfiguration=\(String(describing: editor.pasteConfiguration?.acceptableTypeIdentifiers))")
        let plain = try showEditMenu()
        _ = waitFor(seconds: 1.5) { false }
        Self.log("editor-plain visible=\(visibleMenuLabels())", plain)
        editor.pasteConfiguration = UIPasteConfiguration(acceptableTypeIdentifiers: ["public.image", "public.utf8-plain-text"])
        let configured = try showEditMenu()
        _ = waitFor(seconds: 1.5) { false }
        Self.log("editor-pasteConfig visible=\(visibleMenuLabels())", configured)
    }

    private static let paste = #selector(UIResponderStandardEditActions.paste(_:))
    private static let composerPasteIdentifier = UIAction.Identifier("build.raft.composer.paste")

    /// The title the composer gives its own Paste (RaftIosImagePaste.pasteTitle): UIKit's, else the app's.
    private static var expectedPasteTitle: String {
        let missing = "\u{0}"
        for bundle in [Bundle(for: UIView.self), Bundle(identifier: "com.apple.UIKitCore"), Bundle(identifier: "com.apple.UIKit")] {
            guard let bundle else { continue }
            let title = bundle.localizedString(forKey: "Paste", value: missing, table: nil)
            if title != missing, !title.isEmpty { return title }
        }
        return RaftIosComposerImagePasteKt.raftComposerPasteLabel()
    }

    private func assertMenuPastesImage(initialText: String, file: StaticString = #filePath, line: UInt = #line) throws {
        guard #available(iOS 16.0, *) else { throw XCTSkip("UIEditMenuInteraction is iOS 16+") }
        UIPasteboard.general.items = []
        UIPasteboard.general.image = Self.redImage()
        let editor = try focusedEditor(composerClipboard: false, reportsFocus: true, initialText: initialText)
        let shown = try showEditMenu(file: file, line: line)
        let menu = shown.menu
        Self.log("image initialText=\(initialText.count)", shown)
        let pastes = Self.pasteItems(in: menu.children)
        XCTAssertEqual(pastes.count, 1, "one Paste: \(Self.describe(menu))", file: file, line: line)
        let paste = try XCTUnwrap(pastes.first, file: file, line: line)
        let title = (paste as? UICommand)?.title ?? (paste as? UIAction)?.title ?? ""
        if let ours = paste as? UIAction, ours.identifier == Self.composerPasteIdentifier {
            // No system Paste in the menu: ours, titled as the system's.
            XCTAssertEqual(ours.title, Self.systemPasteTitle(in: shown.suggested) ?? Self.expectedPasteTitle,
                           "titled as the system's Paste", file: file, line: line)
        }
        XCTAssertTrue(
            waitFor(seconds: 3) { self.visiblePasteLabels() == [title] },
            "exactly one Paste (\(title)) on screen; visible \(visibleMenuLabels())",
            file: file, line: line
        )
        // Choosing it runs it as UIKit does on a tap: our action's handler, or `paste:` sent to the
        // first responder for the system's command.
        let before = RaftIosComposerPasteProbeKt.raftComposerPasteProbeImagePasteCount()
        if let action = paste as? UIAction {
            Self.run(action)
        } else if let command = paste as? UICommand {
            XCTAssertTrue(UIApplication.shared.sendAction(command.action, to: nil, from: command, for: nil),
                          "paste: has a target", file: file, line: line)
        }
        XCTAssertTrue(
            waitFor(seconds: 5) { RaftIosComposerPasteProbeKt.raftComposerPasteProbeImagePasteCount() > before },
            "the image went to the composer (image editor flow)",
            file: file, line: line
        )
        XCTAssertEqual(editorText(editor), initialText, "the field's text unchanged", file: file, line: line)
    }

    private struct ShownMenu {
        let menu: UIMenu
        let suggested: [UIMenuElement]
        let original: UIMenu?
    }

    /// Shows the probe field's menu as CMP does after a long press and returns the menu UIKit got
    /// from the editor (RaftIosImagePaste's hook posts it, with UIKit's suggested actions and
    /// Compose's own menu).
    @available(iOS 16.0, *)
    private func showEditMenu(file: StaticString = #filePath, line: UInt = #line) throws -> ShownMenu {
        var received: ShownMenu?
        let observer = NotificationCenter.default.addObserver(
            forName: Notification.Name("RaftComposerEditMenu"), object: nil, queue: .main
        ) { note in
            guard let menu = note.object as? UIMenu else { return }
            received = ShownMenu(
                menu: menu,
                suggested: note.userInfo?["suggested"] as? [UIMenuElement] ?? [],
                original: note.userInfo?["original"] as? UIMenu
            )
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        XCTAssertTrue(waitFor(seconds: 3) { RaftIosComposerPasteProbeKt.raftComposerPasteProbeShowEditMenu() },
                      "probe laid out", file: file, line: line)
        _ = waitFor(seconds: 5) { received != nil }
        return try XCTUnwrap(received, "UIKit asked the composer editor for its edit menu", file: file, line: line)
    }

    private static func log(_ label: String, _ shown: ShownMenu) {
        print("RAFTDIAG \(label): suggested=[\(describe(shown.suggested))] original=[\(shown.original.map(describe) ?? "nil")] result=[\(describe(shown.menu))]")
    }

    /// Every Paste in [elements]: the system's (`paste:` command, or an action UIKit identifies as
    /// paste) and the composer's own.
    private static func pasteItems(in elements: [UIMenuElement]) -> [UIMenuElement] {
        elements.flatMap { element -> [UIMenuElement] in
            if let command = element as? UICommand { return command.action == paste ? [command] : [] }
            if let action = element as? UIAction {
                let id = action.identifier.rawValue.lowercased()
                return action.identifier == composerPasteIdentifier || id == "paste" || id.hasSuffix(".paste") ? [action] : []
            }
            if let menu = element as? UIMenu { return pasteItems(in: menu.children) }
            return []
        }
    }

    private static func systemPasteTitle(in elements: [UIMenuElement]) -> String? {
        for element in pasteItems(in: elements) {
            if let command = element as? UICommand, !command.title.isEmpty { return command.title }
            if let action = element as? UIAction, action.identifier != composerPasteIdentifier, !action.title.isEmpty { return action.title }
        }
        return nil
    }

    /// The distinct Paste labels on screen (any label that says paste, in English or the expected title).
    private func visiblePasteLabels() -> Set<String> {
        let expected = Self.expectedPasteTitle
        return Set(visibleMenuLabels().filter { $0 == expected || $0.localizedCaseInsensitiveContains("paste") })
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

    private static func describe(_ menu: UIMenu) -> String { describe(menu.children) }

    private static func describe(_ elements: [UIMenuElement]) -> String {
        func walk(_ elements: [UIMenuElement]) -> [String] {
            elements.flatMap { element -> [String] in
                if let command = element as? UICommand { return ["\(command.title)(\(NSStringFromSelector(command.action)))"] }
                if let action = element as? UIAction { return ["\(action.title)[\(action.identifier.rawValue)]"] }
                if let submenu = element as? UIMenu { return walk(submenu.children) }
                return [element.title]
            }
        }
        return walk(elements).joined(separator: ", ")
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

/// DIAG (temporary): a plain view with an edit menu that answers YES to paste:.
@available(iOS 16.0, *)
private final class DiagView: UIView, UIEditMenuInteractionDelegate {
    enum Mode { case passthrough, command, action }
    var mode: Mode = .passthrough
    var suggested: [UIMenuElement] = []
    var returned: UIMenu?
    var canPasteAsked = 0
    lazy var interaction = UIEditMenuInteraction(delegate: self)

    override init(frame: CGRect) {
        super.init(frame: frame)
        addInteraction(interaction)
        backgroundColor = .systemGray5
    }

    required init?(coder: NSCoder) { fatalError() }

    override var canBecomeFirstResponder: Bool { true }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(UIResponderStandardEditActions.paste(_:)) {
            canPasteAsked += 1
            return true
        }
        return false
    }

    override func paste(_ sender: Any?) {}

    func editMenuInteraction(_ interaction: UIEditMenuInteraction, menuFor configuration: UIEditMenuConfiguration,
                             suggestedActions: [UIMenuElement]) -> UIMenu? {
        suggested = suggestedActions
        let menu: UIMenu
        switch mode {
        case .passthrough: menu = UIMenu(children: suggestedActions)
        case .command: menu = UIMenu(children: [UICommand(title: "DiagPasteCmd", action: #selector(UIResponderStandardEditActions.paste(_:)))])
        case .action: menu = UIMenu(children: [UIAction(title: "DiagPasteAct") { _ in }])
        }
        returned = menu
        return menu
    }
}
