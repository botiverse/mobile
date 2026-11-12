import Foundation
import shared
import UIKit

/// Paste in the composer's long-press edit menu with only images on the clipboard attaches them. The
/// editor is Compose's native text view, which pastes text only, so its menu offers no Paste. Its
/// class gets `canPerformAction(_:withSender:)` and `paste(_:)` hooked the first time it brings up
/// the keyboard; the paste goes to the focused composer (ThreadComposerImagePasteRequests), which
/// reads the images (RaftPlatformCapabilities.ios.kt). The composer's own callout menu goes through
/// its clipboard instead (ThreadComposerImagePasteClipboard). The keyboard's "Paste from …"
/// suggestion calls neither, so it still does nothing for an image.
enum RaftIosImagePaste {
    private static var hooked = Set<ObjectIdentifier>()
    fileprivate static weak var firstResponder: UIResponder?

    /// At launch (RaftIosAppDelegate).
    static func install() {
        NotificationCenter.default.addObserver(
            forName: UIResponder.keyboardWillShowNotification, object: nil, queue: .main
        ) { _ in hookFirstResponder() }
    }

    private static var imagesOnly: Bool {
        let board = UIPasteboard.general
        return board.hasImages && !board.hasStrings
    }

    private static func hookFirstResponder() {
        firstResponder = nil
        UIApplication.shared.sendAction(#selector(UIResponder.raftCaptureFirstResponder), to: nil, from: nil, for: nil)
        guard let responder = firstResponder else { return }
        let cls: AnyClass = type(of: responder)
        guard hooked.insert(ObjectIdentifier(cls)).inserted else { return }
        // Other fields of the editor's class get the hook too; with only images on the clipboard
        // their paste did nothing before and still does (no composer is focused).
        RaftIosDiagnosticsRecord("info", "RaftImagePaste", "hook class=\(NSStringFromClass(cls))")

        let canSelector = #selector(UIResponder.canPerformAction(_:withSender:))
        if let method = class_getInstanceMethod(cls, canSelector) {
            typealias Can = @convention(c) (AnyObject, Selector, Selector, Any?) -> Bool
            let original = unsafeBitCast(method_getImplementation(method), to: Can.self)
            let replacement: @convention(block) (AnyObject, Selector, Any?) -> Bool = { view, action, sender in
                if action == #selector(UIResponderStandardEditActions.paste(_:)) && imagesOnly { return true }
                return original(view, canSelector, action, sender)
            }
            let imp = imp_implementationWithBlock(replacement)
            if !class_addMethod(cls, canSelector, imp, method_getTypeEncoding(method)) {
                method_setImplementation(method, imp)
            }
        }

        let pasteSelector = #selector(UIResponderStandardEditActions.paste(_:))
        typealias Paste = @convention(c) (AnyObject, Selector, Any?) -> Void
        let method = class_getInstanceMethod(cls, pasteSelector)
        let original = method.map { unsafeBitCast(method_getImplementation($0), to: Paste.self) }
        let replacement: @convention(block) (AnyObject, Any?) -> Void = { view, sender in
            guard imagesOnly else {
                original?(view, pasteSelector, sender)
                return
            }
            RaftIosComposerImagePasteKt.raftRequestComposerImagePaste()
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
}

extension UIResponder {
    @objc fileprivate func raftCaptureFirstResponder() { RaftIosImagePaste.firstResponder = self }
}
