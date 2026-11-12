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
/// hooked. The hooks act only for that one instance; every other instance of the class (and every
/// other view: web content, other fields) runs the original implementation unchanged.
///
/// The clipboard is read inside `paste(_:)`, the system paste action, so iOS shows no
/// "Allow Paste" prompt; the images are downscaled and encoded off the main thread and handed to
/// the composer (`raftComposerImagesPasted`). ⌘V can also reach the composer's key handler; one
/// claim per press (`raftComposerImagePasteClaim`) keeps it to one paste.
enum RaftIosImagePaste {
    /// Longest side of a pasted image; larger ones are downscaled on read.
    static let maxPixelSize = 4096
    private static var hooked = Set<ObjectIdentifier>()
    /// The composer's editor view while the composer has focus; the hooks act only for it.
    private static weak var composerEditor: UIResponder?
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

    private static func isComposerEditor(_ view: AnyObject) -> Bool {
        composerFocused && composerEditor != nil && view === composerEditor
    }

    private static func hook(_ cls: AnyClass) {
        guard hooked.insert(ObjectIdentifier(cls)).inserted else { return }
        RaftIosDiagnosticsRecord("info", "RaftImagePaste", "hook class=\(NSStringFromClass(cls))")

        let canSelector = #selector(UIResponder.canPerformAction(_:withSender:))
        if let method = class_getInstanceMethod(cls, canSelector) {
            typealias Can = @convention(c) (AnyObject, Selector, Selector, Any?) -> Bool
            let original = unsafeBitCast(method_getImplementation(method), to: Can.self)
            let replacement: @convention(block) (AnyObject, Selector, Any?) -> Bool = { view, action, sender in
                if action == #selector(UIResponderStandardEditActions.paste(_:)) && isComposerEditor(view)
                    && RaftIosComposerImagePasteKt.raftIosClipboardHoldsOnlyImages() {
                    return true
                }
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
            guard isComposerEditor(view), RaftIosComposerImagePasteKt.raftIosClipboardHoldsOnlyImages() else {
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

    /// Inside the system paste action: read now (no prompt), encode off the main thread.
    private static func pasteImagesIntoComposer() {
        guard RaftIosComposerImagePasteKt.raftComposerImagePasteClaim() else { return }
        let sources = captureClipboardImages()
        RaftIosDiagnosticsRecord("info", "RaftImagePaste", "paste images=\(sources.count)")
        guard !sources.isEmpty else { return }
        DispatchQueue.global(qos: .userInitiated).async {
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

    /// The clipboard's images as stored (their original bytes where there are some), at most
    /// `raftIosPasteMaxImages()`.
    fileprivate static func captureClipboardImages() -> [Source] {
        let board = UIPasteboard.general
        let limit = Int(RaftIosComposerImagePasteKt.raftIosPasteMaxImages())
        var sources: [Source] = []
        for (index, types) in (board.types(forItemSet: nil) ?? []).enumerated() {
            if sources.count >= limit { break }
            let imageType = types.first { UTType($0)?.conforms(to: .image) == true }
            guard let imageType,
                  let data = board.data(forPasteboardType: imageType, inItemSet: IndexSet(integer: index))?.first
            else { continue }
            sources.append(.data(data))
        }
        if sources.isEmpty {
            sources = (board.images ?? []).prefix(limit).map { .image($0) }
        }
        return sources
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

/// Blocking clipboard read for the composer's own paste paths (its callout menu, ⌘V when the
/// system paste action did not take it). Called off the main thread.
private final class RaftIosPastedImageClipboardReader: NSObject, RaftIosPastedImageReader {
    func read() -> [RaftIosPastedImage] {
        RaftIosImagePaste.encode(RaftIosImagePaste.captureClipboardImages())
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
