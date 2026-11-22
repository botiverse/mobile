import Foundation
import ImageIO
import shared
import UIKit
import UniformTypeIdentifiers

/// Paste (edit menu or ⌘V) in the composer with only images on the clipboard opens them in the
/// composer's image editor, as picked photos do. The editor is Compose's native text view, which
/// pastes text only, so its menu offers no Paste for an image.
///
/// Scope: only the composer's editor view. When the composer reports focus
/// (RaftIosComposerImagePasteHost's focus listener, also on keyboardWillShow while it has focus,
/// so an iPad hardware keyboard that shows no software keyboard works too), the first responder is
/// recorded as that editor and its class gets `canPerformAction(_:withSender:)` / `paste(_:)`
/// hooked. The hooks act only for the composer's editor; every other instance of the class (and
/// every other view: web content, other fields) runs the original implementation unchanged.
///
/// The editor view is Compose's per-session text input view: Compose makes a new one each time
/// the field's input session restarts (its keyboard options or state change, a tap restarts a
/// session) without the composer's focus changing, and with a hardware keyboard no keyboard
/// notification follows. So the editor is not pinned to the instance seen at focus: while the
/// composer has focus, the first responder of the hooked class in the composer's Compose view
/// (the recorded editor's superview) is the composer's editor (`isComposerEditor`). Pinned to
/// the first instance, 1.25.0 lost the menu's Paste and ⌘V for images once the session restarted.
///
/// The editor is also Compose's edit menu view (CMPEditMenuView, iOS 16+: UIEditMenuInteraction).
/// CMP 1.12 offers Paste only for text, and on iOS it never shows Compose's custom menu items
/// (`appendTextContextMenuComponents` needs the new context menu, which is off on iOS). Its menu is
/// UIKit's suggested actions, which UIKit filters before showing, and whether it shows the system's
/// Paste for images-only (the `canPerformAction` hook answers YES) varies: not in an empty field on
/// iPhone (iOS 27; the long press showed no menu), yes on iPad (iOS 26.1, next to the app's item).
/// The hooked `editMenuInteraction(_:menuFor:suggestedActions:)` keeps the menu to exactly one
/// Paste while the clipboard holds only images (`composerEditMenu`): the composer's own, in the
/// system Paste's place and with its title, which sends the editor `paste(_:)`
/// (RaftIosComposerPasteTests checks what is on screen). It is not the system's paste action, so
/// its read of another app's clipboard can show iOS's "Allow Paste" prompt (unless the user chose
/// Allow in Settings); ⌘V is the system's and does not.
///
/// The clipboard is read inside `paste(_:)`, for ⌘V the system paste action, so iOS shows no
/// "Allow Paste" prompt; the images are downscaled and encoded off the main thread and handed to
/// the composer (`raftComposerImagesPasted`). ⌘V is this path's alone: the composer's key handler
/// leaves it to the system paste action on iOS (`raftPlatformComposerKeyHandlerPastesAttachments`),
/// since its own read would be outside the paste action. One claim per press
/// (`raftComposerImagePasteClaim`) still keeps a menu tap or key press to one paste. Each step logs
/// (`RaftImagePaste` lines and `composer_image_paste_claim` / `composer_paste_key`), kinds and
/// counts only.
enum RaftIosImagePaste {
    /// Longest side of a pasted image; larger ones are downscaled on read.
    static let maxPixelSize = 4096
    private static var hooked = Set<ObjectIdentifier>()
    /// The composer's editor view while the composer has focus; the hooks act only for it.
    private static weak var composerEditor: UIResponder?
    /// The Compose view the composer's editor lives in (where Compose adds each session's editor).
    private static weak var composerHost: UIView?
    private static var composerFocused = false

    /// At launch (RaftIosAppDelegate).
    static func install() {
        RaftIosComposerImagePasteHost.shared.reader = RaftIosPastedImageClipboardReader()
        RaftIosComposerImagePasteHost.shared.setFocusListener(listener: RaftIosComposerEditorFocus())
        NotificationCenter.default.addObserver(
            forName: UIResponder.keyboardWillShowNotification, object: nil, queue: .main
        ) { _ in
            if composerFocused { captureComposerEditor() }
        }
    }

    fileprivate static func composerFocusChanged(_ focused: Bool) {
        composerFocused = focused
        guard focused else {
            composerEditor = nil
            composerHost = nil
            return
        }
        // The native editor becomes first responder after Compose's focus change, which may take
        // a frame or two: look now, on the next turn and once more shortly after.
        captureComposerEditor()
        DispatchQueue.main.async { captureComposerEditor() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { captureComposerEditor() }
    }

    private static func captureComposerEditor() {
        guard composerFocused else { return }
        guard let responder = currentFirstResponder(), responder is UITextInput else { return }
        // Never web content (WKContentView and friends), whatever has focus.
        guard !NSStringFromClass(type(of: responder)).hasPrefix("WK") else { return }
        if composerEditor !== responder {
            composerEditor = responder
        }
        if let host = responder.superview { composerHost = host }
        hook(type(of: responder))
    }

    /// The focused text view, found by walking the windows. Not a nil-targeted action: UIKit sends
    /// it to the first responder that `canPerformAction` it, and Compose's editor view
    /// (CMPEditMenuView) answers NO to every action but its edit menu's, so the action went to a
    /// container view and the editor was never hooked.
    static func currentFirstResponder() -> UIView? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .sorted { $0.isKeyWindow && !$1.isKeyWindow }
        for window in windows {
            if let found = firstResponder(in: window) { return found }
        }
        return nil
    }

    private static func firstResponder(in view: UIView) -> UIView? {
        if view.isFirstResponder { return view }
        for subview in view.subviews {
            if let found = firstResponder(in: subview) { return found }
        }
        return nil
    }

    /// [view] (an instance of a hooked class) is the composer's editor: the one recorded at focus,
    /// or, while the composer has focus, the first responder in the composer's Compose view (a
    /// restarted input session's new editor view). A match is recorded as the editor.
    static func isComposerEditor(_ view: AnyObject) -> Bool {
        guard composerFocused else { return false }
        if let editor = composerEditor, view === editor { return true }
        guard let view = view as? UIView, view.isFirstResponder, view is UITextInput,
              !NSStringFromClass(type(of: view)).hasPrefix("WK")
        else { return false }
        if let host = composerHost, view.superview !== host { return false }
        composerEditor = view
        if let host = view.superview { composerHost = host }
        RaftIosDiagnosticsRecord("info", "RaftImagePaste", "editor rebound class=\(NSStringFromClass(type(of: view)))")
        return true
    }

    private static func hook(_ cls: AnyClass) {
        guard hooked.insert(ObjectIdentifier(cls)).inserted else { return }
        RaftIosDiagnosticsRecord("info", "RaftImagePaste", "hook class=\(NSStringFromClass(cls))")

        let canSelector = #selector(UIResponder.canPerformAction(_:withSender:))
        if let method = class_getInstanceMethod(cls, canSelector) {
            typealias Can = @convention(c) (AnyObject, Selector, Selector, Any?) -> Bool
            let original = unsafeBitCast(method_getImplementation(method), to: Can.self)
            let replacement: @convention(block) (AnyObject, Selector, Any?) -> Bool = { view, action, sender in
                guard action == #selector(UIResponderStandardEditActions.paste(_:)), isComposerEditor(view) else {
                    return original(view, canSelector, action, sender)
                }
                let onlyImages = RaftIosComposerImagePasteKt.raftIosClipboardHoldsOnlyImages()
                let result = onlyImages || original(view, canSelector, action, sender)
                RaftIosDiagnosticsRecord(
                    "info", "RaftImagePaste",
                    "canPerformAction paste composer=true \(clipboardSummary()) onlyImages=\(onlyImages) " +
                        "sender=\(senderKind(sender)) result=\(result)"
                )
                return result
            }
            let imp = imp_implementationWithBlock(replacement)
            if !class_addMethod(cls, canSelector, imp, method_getTypeEncoding(method)) {
                method_setImplementation(method, imp)
            }
        }

        if #available(iOS 16.0, *) { hookEditMenu(cls) }

        let pasteSelector = #selector(UIResponderStandardEditActions.paste(_:))
        typealias Paste = @convention(c) (AnyObject, Selector, Any?) -> Void
        let method = class_getInstanceMethod(cls, pasteSelector)
        let original = method.map { unsafeBitCast(method_getImplementation($0), to: Paste.self) }
        let replacement: @convention(block) (AnyObject, Any?) -> Void = { view, sender in
            let composer = isComposerEditor(view)
            let onlyImages = composer && RaftIosComposerImagePasteKt.raftIosClipboardHoldsOnlyImages()
            if composer {
                RaftIosDiagnosticsRecord(
                    "info", "RaftImagePaste",
                    "paste: composer=true \(clipboardSummary()) onlyImages=\(onlyImages) " +
                        "sender=\(senderKind(sender)) route=\(onlyImages ? "image" : "original")"
                )
            }
            guard onlyImages else {
                original?(view, pasteSelector, sender)
                return
            }
            pasteImagesIntoComposer()
        }
        let imp = imp_implementationWithBlock(replacement)
        if let method {
            if !class_addMethod(cls, pasteSelector, imp, method_getTypeEncoding(method)) {
                method_setImplementation(method, imp)
            }
        } else {
            class_addMethod(cls, pasteSelector, imp, "v@:@")
        }
    }

    /// The composer editor's edit menu (iOS 16+): Compose's menu, with one Paste while the clipboard
    /// holds only images. Posted as `composerEditMenuNotification` (RaftIosComposerPasteTests).
    @available(iOS 16.0, *)
    private static func hookEditMenu(_ cls: AnyClass) {
        let selector = NSSelectorFromString("editMenuInteraction:menuForConfiguration:suggestedActions:")
        guard let method = class_getInstanceMethod(cls, selector) else { return }
        typealias MenuFor = @convention(c) (AnyObject, Selector, UIEditMenuInteraction, UIEditMenuConfiguration, NSArray) -> UIMenu?
        let original = unsafeBitCast(method_getImplementation(method), to: MenuFor.self)
        let replacement: @convention(block) (AnyObject, UIEditMenuInteraction, UIEditMenuConfiguration, NSArray) -> UIMenu? = {
            view, interaction, configuration, suggested in
            let menu = original(view, selector, interaction, configuration, suggested)
            guard isComposerEditor(view) else { return menu }
            let result = composerEditMenu(menu, suggested: suggested as? [UIMenuElement] ?? [], editor: view)
            var info: [String: Any] = ["suggested": suggested]
            if let menu { info["original"] = menu }
            NotificationCenter.default.post(name: composerEditMenuNotification, object: result, userInfo: info)
            return result
        }
        let imp = imp_implementationWithBlock(replacement)
        if !class_addMethod(cls, selector, imp, method_getTypeEncoding(method)) {
            method_setImplementation(method, imp)
        }
    }

    /// Posted with the composer editor's edit menu (a UIMenu, or nil) each time UIKit asks for it;
    /// userInfo: "suggested" (UIKit's suggested actions), "original" (Compose's menu, when it built one).
    static let composerEditMenuNotification = Notification.Name("RaftComposerEditMenu")

    private static let pasteSelector = #selector(UIResponderStandardEditActions.paste(_:))

    /// The system's Paste in a menu: the standard edit command (`paste:`), or an action UIKit
    /// identifies as paste.
    private static func isSystemPaste(_ element: UIMenuElement) -> Bool {
        if let command = element as? UICommand { return command.action == pasteSelector }
        if let action = element as? UIAction {
            guard action.identifier != pasteIdentifier else { return false }
            let id = action.identifier.rawValue.lowercased()
            return id == "paste" || id.hasSuffix(".paste")
        }
        return false
    }

    private static func systemPaste(in elements: [UIMenuElement]) -> UIMenuElement? {
        for element in elements {
            if isSystemPaste(element) { return element }
            if let menu = element as? UIMenu, let found = systemPaste(in: menu.children) { return found }
        }
        return nil
    }

    /// UIKit's own title for Paste (the one its edit menu shows, in the language UIKit uses), or
    /// the app's "Paste" when UIKit's strings do not have it.
    static var pasteTitle: String {
        let missing = "\u{0}"
        for bundle in [Bundle(for: UIView.self), Bundle(identifier: "com.apple.UIKitCore"), Bundle(identifier: "com.apple.UIKit")] {
            guard let bundle else { continue }
            let title = bundle.localizedString(forKey: "Paste", value: missing, table: nil)
            if title != missing, !title.isEmpty { return title }
        }
        return RaftIosComposerImagePasteKt.raftComposerPasteLabel()
    }

    /// Only images on the clipboard: exactly one Paste, the composer's, where the system's Paste is
    /// in the menu (in its place) or first. Not the system's own: whether UIKit shows a `paste:`
    /// command for images-only cannot be told from the menu. In CI (iPhone, iOS 27) it hid it in an
    /// empty field and showed it in one with text, for the composer and a plain view alike, even
    /// with `canPerformAction` YES and a paste configuration accepting images; on iPad (iOS 26.1)
    /// it showed it next to "Paste Image". So the system's Paste is dropped and ours, titled as it
    /// (UIKit's own title), always shows. Choosing it sends `paste(_:)` to the editor, the path ⌘V
    /// takes, which the hook routes to the image editor. Text on the clipboard (alone or with
    /// images) keeps Compose's menu as it is: the system's Paste pastes the text.
    @available(iOS 16.0, *)
    private static func composerEditMenu(_ menu: UIMenu?, suggested: [UIMenuElement], editor: AnyObject) -> UIMenu? {
        guard RaftIosComposerImagePasteKt.raftIosClipboardHoldsOnlyImages() else { return menu }
        let system = menu.flatMap { systemPaste(in: $0.children) } ?? systemPaste(in: suggested)
        let systemTitle = (system as? UICommand)?.title ?? (system as? UIAction)?.title
        let item = UIAction(
            title: systemTitle.flatMap { $0.isEmpty ? nil : $0 } ?? pasteTitle,
            identifier: pasteIdentifier
        ) { [weak editor] action in
            guard let editor = editor as? UIResponder else { return }
            editor.perform(pasteSelector, with: action)
        }
        guard let menu else { return UIMenu(title: "", children: [item]) }
        var placed = false
        func replacingPaste(_ elements: [UIMenuElement]) -> [UIMenuElement] {
            elements.compactMap { (element: UIMenuElement) -> UIMenuElement? in
                if isSystemPaste(element) {
                    if placed { return nil }
                    placed = true
                    return item
                }
                guard let submenu = element as? UIMenu else { return element }
                let children = replacingPaste(submenu.children)
                return children.isEmpty ? nil : submenu.replacingChildren(children)
            }
        }
        let children = replacingPaste(menu.children)
        return menu.replacingChildren(placed ? children : [item] + children)
    }

    private static let pasteIdentifier = UIAction.Identifier("build.raft.composer.paste")

    /// The clipboard for a diagnostics line: kinds and item count only. None of these read the
    /// clipboard's content, so none shows the "Allow Paste" prompt.
    private static func clipboardSummary() -> String {
        let board = UIPasteboard.general
        return "hasImages=\(board.hasImages) hasStrings=\(board.hasStrings) items=\(board.numberOfItems)"
    }

    /// What sent an edit action, for a diagnostics line: ⌘V (a key command), a menu item, or nil.
    private static func senderKind(_ sender: Any?) -> String {
        guard let sender else { return "nil" }
        return NSStringFromClass(type(of: sender as AnyObject))
    }

    /// Inside `paste(_:)`: from ⌘V or the system's Paste (no prompt), or from the composer's own
    /// Paste item (iOS may prompt): capture raw pasteboard payloads synchronously inside `paste(_:)`
    /// so iOS 16+ keeps the paste-action exemption, then validate with ImageIO and encode off the
    /// main thread.
    private static func pasteImagesIntoComposer() {
        // Logs `composer_image_paste_claim route=system_paste granted=…` (ThreadComposerImagePaste.kt).
        guard RaftIosComposerImagePasteKt.raftComposerImagePasteClaim() else {
            RaftIosDiagnosticsRecord("info", "RaftImagePaste", "paste dropped reason=claimed_by_other_route")
            return
        }
        let snapshot = captureClipboardSnapshot()
        DispatchQueue.global(qos: .userInitiated).async {
            let sources = resolveClipboardImages(snapshot)
            RaftIosDiagnosticsRecord("info", "RaftImagePaste", "paste images=\(sources.count)")
            guard !sources.isEmpty else { return }
            let images = encode(sources)
            DispatchQueue.main.async {
                RaftIosComposerImagePasteKt.raftComposerImagesPasted(images: images)
            }
        }
    }

    fileprivate enum Source {
        case data(Data)
        case image(UIImage)
    }

    fileprivate enum ClipboardTypeCapture {
        case data(type: String, data: Data)
        case missing(type: String)
    }

    fileprivate struct ClipboardItemCandidates {
        let index: Int
        let hasImageType: Bool
        let captures: [ClipboardTypeCapture]
    }

    fileprivate struct ClipboardSnapshot {
        let limit: Int
        let itemTypes: [[String]]
        let items: [ClipboardItemCandidates]
        let wholeCandidates: [(type: String, data: Data)]
        let fallbackImages: [UIImage]
    }

    /// Reads raw pasteboard payloads without running ImageIO (`CGImageSourceCreateWithData`) on the
    /// main thread. Each item's image types are queried in `imageTypesInReadOrder` (encoded public
    /// formats first, UIKit's in-process `com.apple.uikit.image` last); once an item yields plausible
    /// encoded image bytes we stop reading redundant types for that item.
    /// Authoritative ImageIO validation happens in `resolveClipboardImages(_:)` off the main thread.
    fileprivate static func captureClipboardSnapshot() -> ClipboardSnapshot {
        let limit = max(0, Int(RaftIosComposerImagePasteKt.raftIosPasteMaxImages()))
        guard limit > 0 else {
            return ClipboardSnapshot(limit: 0, itemTypes: [], items: [], wholeCandidates: [], fallbackImages: [])
        }
        let board = UIPasteboard.general
        let itemTypes = board.types(forItemSet: nil) ?? []
        var items: [ClipboardItemCandidates] = []
        var itemsWithRecognizedHeader = 0
        for (index, types) in itemTypes.enumerated() {
            if items.count >= limit { break }
            let orderedTypes = imageTypesInReadOrder(types)
            guard !orderedTypes.isEmpty else { continue }
            let set = IndexSet(integer: index)
            var captures: [ClipboardTypeCapture] = []
            var recognized = false
            for type in orderedTypes {
                if let data = board.data(forPasteboardType: type, inItemSet: set)?.first, !data.isEmpty {
                    captures.append(.data(type: type, data: data))
                    if looksLikeEncodedImageHeader(data) {
                        recognized = true
                        break
                    }
                } else {
                    captures.append(.missing(type: type))
                }
            }
            if recognized { itemsWithRecognizedHeader += 1 }
            items.append(
                ClipboardItemCandidates(
                    index: index,
                    hasImageType: true,
                    captures: captures
                )
            )
        }
        var wholeCandidates: [(type: String, data: Data)] = []
        var fallbackImages: [UIImage] = []
        // Only query whole-pasteboard and UIImage fallbacks when per-item reads did not already
        // find a recognized encoded image header (for example, `com.apple.uikit.image` payloads
        // that ImageIO cannot decode directly).
        if itemsWithRecognizedHeader == 0 {
            let types = imageTypesInReadOrder(Array(Set(itemTypes.flatMap { $0 }))) +
                [UTType.png.identifier, UTType.jpeg.identifier, UTType.heic.identifier]
            for type in uniqued(types) {
                if let data = board.data(forPasteboardType: type), !data.isEmpty {
                    wholeCandidates.append((type: type, data: data))
                    if looksLikeEncodedImageHeader(data) {
                        break
                    }
                }
            }
            if wholeCandidates.allSatisfy({ !looksLikeEncodedImageHeader($0.data) }) {
                let batch = Array((board.images ?? []).prefix(limit))
                if !batch.isEmpty {
                    fallbackImages = batch
                } else if let image = board.image {
                    fallbackImages = [image]
                }
            }
        }
        return ClipboardSnapshot(
            limit: limit,
            itemTypes: itemTypes,
            items: items,
            wholeCandidates: wholeCandidates,
            fallbackImages: fallbackImages
        )
    }

    /// Validates captured clipboard payloads with ImageIO off the main thread, keeping the same
    /// precedence as before (per-item encoded bytes, then whole-pasteboard bytes, then `images` /
    /// `image`) and logging types and counts only.
    fileprivate static func resolveClipboardImages(_ snapshot: ClipboardSnapshot) -> [Source] {
        var sources: [Source] = []
        var perItem = 0
        var misses: [String] = []
        for item in snapshot.items {
            if sources.count >= snapshot.limit { break }
            var found = false
            for capture in item.captures {
                switch capture {
                case .missing(let type):
                    misses.append(type)
                case .data(let type, let data):
                    if isDecodableImage(data) {
                        sources.append(.data(data))
                        perItem += 1
                        found = true
                    } else {
                        misses.append(type)
                    }
                }
                if found { break }
            }
            if !found, item.hasImageType { misses.append("item\(item.index)") }
        }
        var whole = 0
        if sources.isEmpty {
            for candidate in snapshot.wholeCandidates {
                if isDecodableImage(candidate.data) {
                    sources.append(.data(candidate.data))
                    whole += 1
                    break
                }
            }
        }
        var images = 0
        if sources.isEmpty && !snapshot.fallbackImages.isEmpty {
            sources = snapshot.fallbackImages.prefix(snapshot.limit).map { .image($0) }
            images = sources.count
        }
        let typeList = snapshot.itemTypes.map { "[" + $0.joined(separator: ",") + "]" }.joined(separator: ",")
        RaftIosDiagnosticsRecord(
            "info", "RaftImagePaste",
            "read items=\(snapshot.itemTypes.count) types=\(typeList) perItem=\(perItem) whole=\(whole) images=\(images) " +
                "missed=\(misses.joined(separator: ","))"
        )
        return sources
    }

    /// Cheap O(1) magic-byte check used only to avoid reading redundant pasteboard representations
    /// on the main thread; authoritative validation runs off the main thread via ImageIO.
    static func looksLikeEncodedImageHeader(_ data: Data) -> Bool {
        // Require a minimal body past the header so truncated magic-byte-only stubs still fall
        // through to secondary representations or UIImage fallback.
        guard data.count >= 24 else { return false }
        let header = Array(data.prefix(12))
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if header[0] == 0x89, header[1] == 0x50, header[2] == 0x4E, header[3] == 0x47,
           header[4] == 0x0D, header[5] == 0x0A, header[6] == 0x1A, header[7] == 0x0A {
            return true
        }
        // JPEG: FF D8 FF
        if header[0] == 0xFF, header[1] == 0xD8, header[2] == 0xFF {
            return true
        }
        // GIF: "GIF8"
        if header[0] == 0x47, header[1] == 0x49, header[2] == 0x46, header[3] == 0x38 {
            return true
        }
        // WebP: "RIFF" .... "WEBP"
        if header[0] == 0x52, header[1] == 0x49, header[2] == 0x46, header[3] == 0x46,
           header[8] == 0x57, header[9] == 0x45, header[10] == 0x42, header[11] == 0x50 {
            return true
        }
        // HEIC / HEIF / AVIF (ISO Base Media File Format): .... "ftyp"
        if header[4] == 0x66, header[5] == 0x74, header[6] == 0x79, header[7] == 0x70 {
            return true
        }
        // JPEG 2000 JP2 box (00 00 00 0C "jP  ") or codestream (FF 4F FF 51)
        if (header[0] == 0x00 && header[1] == 0x00 && header[2] == 0x00 && header[3] == 0x0C &&
            header[4] == 0x6A && header[5] == 0x50 && header[6] == 0x20 && header[7] == 0x20) ||
           (header[0] == 0xFF && header[1] == 0x4F && header[2] == 0xFF && header[3] == 0x51) {
            return true
        }
        // TIFF: "II*\0" or "MM\0*"
        if (header[0] == 0x49 && header[1] == 0x49 && header[2] == 0x2A && header[3] == 0x00) ||
           (header[0] == 0x4D && header[1] == 0x4D && header[2] == 0x00 && header[3] == 0x2A) {
            return true
        }
        // BMP: "BM", ICO: 00 00 01 00, ICNS: "icns"
        if (header[0] == 0x42 && header[1] == 0x4D) ||
           (header[0] == 0x00 && header[1] == 0x00 && header[2] == 0x01 && header[3] == 0x00) ||
           (header[0] == 0x69 && header[1] == 0x63 && header[2] == 0x6E && header[3] == 0x73) {
            return true
        }
        return false
    }

    private static let uikitImageType = "com.apple.uikit.image"

    private static func isImageType(_ type: String) -> Bool {
        type == uikitImageType || UTType(type)?.conforms(to: .image) == true
    }

    /// [types]' image types: encoded ones (ImageIO reads their bytes) in the item's order, then
    /// any other image type, UIKit's in-process one last.
    static func imageTypesInReadOrder(_ types: [String]) -> [String] {
        let images = types.filter(isImageType)
        let encoded = images.filter { $0 != uikitImageType && $0.hasPrefix("public.") }
        let others = images.filter { $0 != uikitImageType && !$0.hasPrefix("public.") }
        let uikit = images.filter { $0 == uikitImageType }
        return encoded + others + uikit
    }

    private static func uniqued(_ types: [String]) -> [String] {
        var seen = Set<String>()
        return types.filter { seen.insert($0).inserted }
    }

    private static func isDecodableImage(_ data: Data) -> Bool {
        guard !data.isEmpty, let source = CGImageSourceCreateWithData(data as CFData, nil) else { return false }
        return CGImageSourceGetCount(source) > 0
    }

    /// Each image upright (EXIF orientation applied), no longer than `maxPixelSize` on its longest
    /// side, as JPEG, or PNG when it has transparency; written where picked attachments go
    /// (HRBridgeModule: Caches/Slock/PickedAttachments).
    fileprivate static func encode(_ sources: [Source]) -> [RaftIosPastedImage] {
        guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return [] }
        let directory = caches.appendingPathComponent("Slock/PickedAttachments", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return sources.compactMap { source in
            guard let image = uprightImage(source) else { return nil }
            let alpha: Bool
            switch image.alphaInfo {
            case .first, .last, .premultipliedFirst, .premultipliedLast: alpha = true
            default: alpha = false
            }
            let type: UTType = alpha ? .png : .jpeg
            let output = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(output, type.identifier as CFString, 1, nil) else { return nil }
            let properties: CFDictionary? = alpha ? nil : [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary
            CGImageDestinationAddImage(destination, image, properties)
            guard CGImageDestinationFinalize(destination) else { return nil }
            let id = UUID().uuidString
            let ext = alpha ? "png" : "jpg"
            let url = directory.appendingPathComponent("pasted-\(id).\(ext)")
            guard output.write(to: url, atomically: true) else { return nil }
            return RaftIosPastedImage(
                id: id,
                path: url.path,
                fileName: "Pasted image.\(ext)",
                mimeType: alpha ? "image/png" : "image/jpeg",
                sizeBytes: Int64(output.length)
            )
        }
    }

    private static func uprightImage(_ source: Source) -> CGImage? {
        switch source {
        case .data(let data):
            guard let imageSource = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
            // A thumbnail no larger than the cap, decoded at that size (never the full bitmap
            // first), with the EXIF orientation applied.
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            ]
            return CGImageSourceCreateThumbnailAtIndex(imageSource, 0, options as CFDictionary)
        case .image(let image):
            // Drawing applies imageOrientation; scale 1 so the size is in pixels.
            let pixelWidth = image.size.width * image.scale
            let pixelHeight = image.size.height * image.scale
            guard pixelWidth > 0, pixelHeight > 0 else { return nil }
            let factor = min(1, CGFloat(maxPixelSize) / max(pixelWidth, pixelHeight))
            let size = CGSize(width: (pixelWidth * factor).rounded(), height: (pixelHeight * factor).rounded())
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = false
            return UIGraphicsImageRenderer(size: size, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: size))
            }.cgImage
        }
    }
}

/// Blocking clipboard read for a composer paste that the host did not deliver (no pending images,
/// e.g. the composer's attachment paste event from another path). Called off the main thread.
private final class RaftIosPastedImageClipboardReader: NSObject, RaftIosPastedImageReader {
    func read() -> [RaftIosPastedImage] {
        let snapshot = RaftIosImagePaste.captureClipboardSnapshot()
        return RaftIosImagePaste.encode(RaftIosImagePaste.resolveClipboardImages(snapshot))
    }
}

private final class RaftIosComposerEditorFocus: NSObject, RaftIosComposerEditorFocusListener {
    func composerEditorFocusChanged(focused: Bool) {
        if Thread.isMainThread {
            RaftIosImagePaste.composerFocusChanged(focused)
        } else {
            DispatchQueue.main.async { RaftIosImagePaste.composerFocusChanged(focused) }
        }
    }
}
