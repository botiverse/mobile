import CoreGraphics
import Foundation
import UIKit

/// One tab as `RaftIosTabBarController` puts it on a `UITabBarItem`. Built from the shared
/// `RaftNativeTabBarItem`; kept free of the `shared` framework so iosAppTests can exercise the
/// mapping directly.
struct RaftIosTabBarItemSpec: Equatable {
    let key: String
    let title: String
    let systemImageName: String
    let selectedSystemImageName: String
    /// `UITabBarItem.badgeValue`: a count ("3", "99+"), or nil for no badge. Never "": UIKit
    /// has no small-dot badge and draws an empty value as a full-size empty badge. Shared turns
    /// a tab's attention dot into its count for this bar (`nativeBadgeCount`).
    let badgeValue: String?
    let enabled: Bool
    /// The Search action (never the selected item; a tap opens search over the current tab).
    let searchAction: Bool
    /// Asset-catalog name of the tab's Lucide icon (the web's glyph); nil uses the SF Symbols.
    let imageAssetName: String?
    /// Opens a panel, never selected (the current-server tab): the iOS 26 server button.
    let opensPanel: Bool
    /// Screen-reader label; nil = [title].
    let accessibilityLabel: String?
    /// The server's initial for the ink tile when there is no avatar image.
    let avatarFallbackLabel: String?

    init(
        key: String,
        title: String,
        systemImageName: String,
        selectedSystemImageName: String,
        badgeValue: String?,
        enabled: Bool,
        searchAction: Bool = false,
        imageAssetName: String? = nil,
        opensPanel: Bool = false,
        accessibilityLabel: String? = nil,
        avatarFallbackLabel: String? = nil
    ) {
        self.key = key
        self.title = title
        self.systemImageName = systemImageName
        self.selectedSystemImageName = selectedSystemImageName
        self.badgeValue = RaftIosTabBarItemMapping.badgeValue(fromShared: badgeValue)
        self.enabled = enabled
        self.searchAction = searchAction
        self.imageAssetName = imageAssetName
        self.opensPanel = opensPanel
        self.accessibilityLabel = accessibilityLabel
        self.avatarFallbackLabel = avatarFallbackLabel
    }
}

/// Pure UIKit-independent rules of the native tab bar host. Shared owns the tab list, order,
/// labels, badge counts and their "99+" formatting; this only adapts them to UITabBar.
enum RaftIosTabBarItemMapping {
    /// Above this count a compact `UITabBarController` inserts its own "More" tab, which cannot
    /// host the shared shell. Extra tabs are dropped (and logged) instead.
    static let maximumTabCount = 5

    /// SF Symbol used when a mapped symbol does not exist on the running iOS version.
    static let fallbackSystemImageName = "circle"

    /// Shared already formats the badge (nil at 0, "99+" cap); blank or "0" still hides it.
    /// Never "": UIKit draws an empty `badgeValue` as a full-size red badge (iOS 15 to 26), not
    /// a small dot, which is how the attention dot used to look too big.
    static func badgeValue(fromShared value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              trimmed != "0" else {
            return nil
        }
        return trimmed
    }

    /// Tabs in shared order, first occurrence of a key wins, capped at `maximumTabCount`.
    static func displayItems(_ items: [RaftIosTabBarItemSpec]) -> [RaftIosTabBarItemSpec] {
        var seen = Set<String>()
        let unique = items.filter { !$0.key.isEmpty && seen.insert($0.key).inserted }
        return Array(unique.prefix(maximumTabCount))
    }

    /// Index of the shared selection in the displayed keys; nil keeps the current selection
    /// (shared selected a destination that is not a bottom tab, e.g. rail Search).
    static func selectedIndex(of selectedKey: String?, in keys: [String]) -> Int? {
        guard let selectedKey = selectedKey else { return nil }
        return keys.firstIndex(of: selectedKey)
    }

    /// The tab child controllers are rebuilt only when the key list (set or order) changes;
    /// label, icon, badge and enabled updates are applied to the existing items in place.
    static func needsRebuild(currentKeys: [String], nextKeys: [String]) -> Bool {
        currentKeys != nextKeys
    }

    /// SF Symbol names to try in order for an item image.
    static func imageNameCandidates(preferred: String, fallback: String? = nil) -> [String] {
        var seen = Set<String>()
        return [preferred, fallback, fallbackSystemImageName]
            .compactMap { $0 }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    /// Height (points) of the band the tab bar covers, from its top edge to the bottom of its
    /// container: the content inset the shared shell reserves. Includes the home-indicator area.
    static func bottomBand(containerHeight: CGFloat, tabBarMinY: CGFloat) -> CGFloat {
        guard containerHeight.isFinite, tabBarMinY.isFinite else { return 0 }
        return max(0, min(containerHeight, containerHeight - tabBarMinY))
    }
}

/// One action per tap on a tab bar item.
///
/// UIKit can ask the delegate `tabBarController(_:shouldSelect:)` more than once for a single tap
/// (the iOS 18+ tab bar, Liquid Glass on iOS 26). Every other tab's action is idempotent (select
/// X twice is X), but the last tab toggles the server panel, so a second call closed it in the
/// same tap: "the last tab cannot be tapped". The gate drops a repeat of the same key from the
/// same touch sequence; a new finger-down is always a new tap. Without touch information
/// (VoiceOver, keyboard) a repeat within `repeatWindow` is dropped.
struct RaftIosTabTapGate {
    /// Repeat window when the call carries no touch (or a different source than the last one).
    static let repeatWindow: TimeInterval = 0.5
    /// How long one touch sequence can keep asking (a slow press).
    static let sameTouchWindow: TimeInterval = 1.5

    private var lastKey: String?
    private var lastAt: TimeInterval = -.greatestFiniteMagnitude
    private var lastTouch: TimeInterval?

    /// [touchBeganAt]: timestamp of the touch-down that hit the bar (nil when unknown).
    mutating func admit(key: String, now: TimeInterval, touchBeganAt: TimeInterval?) -> Bool {
        if key == lastKey {
            let elapsed = now - lastAt
            if let touch = touchBeganAt, let last = lastTouch {
                if touch == last && elapsed < Self.sameTouchWindow { return false }
            } else if elapsed < Self.repeatWindow {
                return false
            }
        }
        lastKey = key
        lastAt = now
        lastTouch = touchBeganAt
        return true
    }
}

/// The server's avatar for the server tab: its image clipped to a circle, or the app's initial
/// tile (ink with the initial in brand yellow, as the panel and Android draw it).
enum RaftIosServerAvatarImage {
    /// The avatar image's size; the system centres it in its round glass button.
    static let diameter: CGFloat = 40

    /// raft-ui `sticker-ink` (#141111).
    static let ink = UIColor(red: 0x14 / 255.0, green: 0x11 / 255.0, blue: 0x11 / 255.0, alpha: 1)
    /// raft-ui palette yellow (#FFD441), the initial's colour.
    static let brandYellow = UIColor(red: 1.0, green: 0xD4 / 255.0, blue: 0x41 / 255.0, alpha: 1)

    /// A UIImage from non-premultiplied ARGB pixels (row by row), or nil when they do not fit.
    static func image(argb: [Int32], width: Int, height: Int) -> UIImage? {
        guard width > 0, height > 0, argb.count == width * height else { return nil }
        let data = argb.withUnsafeBufferPointer { Data(buffer: $0) }
        guard let provider = CGDataProvider(data: data as CFData) else { return nil }
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.first.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        guard let cgImage = CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info, provider: provider,
            decode: nil, shouldInterpolate: true, intent: .defaultIntent
        ) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    /// [source] (aspect-filled) or, without it, the initial tile, clipped to a [diameter] circle.
    static func circle(_ source: UIImage?, fallbackLabel: String?, diameter: CGFloat) -> UIImage {
        let size = CGSize(width: diameter, height: diameter)
        let bounds = CGRect(origin: .zero, size: size)
        return UIGraphicsImageRenderer(size: size).image { _ in
            UIBezierPath(ovalIn: bounds).addClip()
            if let source = source, source.size.width > 0, source.size.height > 0 {
                let scale = max(diameter / source.size.width, diameter / source.size.height)
                let drawn = CGSize(width: source.size.width * scale, height: source.size.height * scale)
                source.draw(in: CGRect(
                    x: (diameter - drawn.width) / 2, y: (diameter - drawn.height) / 2,
                    width: drawn.width, height: drawn.height
                ))
                return
            }
            ink.setFill()
            UIRectFill(bounds)
            let label = (fallbackLabel?.isEmpty == false ? fallbackLabel! : "?") as NSString
            let font = UIFont.monospacedSystemFont(ofSize: (diameter * 0.45).rounded(), weight: .bold)
            let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: brandYellow]
            let textSize = label.size(withAttributes: attributes)
            label.draw(
                at: CGPoint(x: (diameter - textSize.width) / 2, y: (diameter - textSize.height) / 2),
                withAttributes: attributes
            )
        }.withRenderingMode(.alwaysOriginal)
    }
}

/// A tab's icon: the web's Lucide glyph (template asset, tinted by the bar) when the app ships
/// it, SF Symbols otherwise. Lucide has no filled variants, so selection is the tint alone.
enum RaftIosTabIcon {
    static func images(for spec: RaftIosTabBarItemSpec) -> (normal: UIImage?, selected: UIImage?) {
        if let assetName = spec.imageAssetName,
           let asset = UIImage(named: assetName)?.withRenderingMode(.alwaysTemplate) {
            return (asset, asset)
        }
        return (
            symbol(RaftIosTabBarItemMapping.imageNameCandidates(preferred: spec.systemImageName)),
            symbol(RaftIosTabBarItemMapping.imageNameCandidates(
                preferred: spec.selectedSystemImageName,
                fallback: spec.systemImageName
            ))
        )
    }

    private static func symbol(_ names: [String]) -> UIImage? {
        for name in names {
            if let image = UIImage(systemName: name) { return image }
        }
        return nil
    }
}

/// The iOS 26 bar built from system tabs (`UITabBarController.tabs`). The current-server tab is
/// a `UISearchTab`: iOS 26 draws it as its own round Liquid Glass button beside the bar, like
/// Music's separate Search, so its position, size, glass and alignment are UIKit's. It shows the
/// server's avatar (an original-colour image) and never takes the selection: the delegate
/// refuses every selection and runs the shell's handler instead (it opens the server panel).
@available(iOS 26.0, *)
final class RaftIosSystemTabs {
    private var tabsByKey: [String: UITab] = [:]
    private var keys: [String] = []
    /// Content for a tab: an empty, transparent placeholder (the shell draws the page).
    var makeContent: (String) -> UIViewController = { _ in
        let content = UIViewController()
        content.view.backgroundColor = .clear
        return content
    }

    /// The shell key of [tab], if it is one of these tabs.
    func key(for tab: UITab) -> String? {
        tabsByKey.first { $0.value === tab }?.key
    }

    func tab(for key: String) -> UITab? {
        tabsByKey[key]
    }

    /// Builds or updates [controller]'s tabs from [specs]; [panelImage] is the server's avatar
    /// (nil = its initial tile). Tabs are rebuilt only when the key list or a tab's kind changes.
    func apply(_ specs: [RaftIosTabBarItemSpec], panelImage: UIImage?, to controller: UITabBarController) {
        let nextKeys = specs.map(\.key)
        let kindChanged = specs.contains { spec in
            guard let tab = tabsByKey[spec.key] else { return false }
            return (tab is UISearchTab) != spec.opensPanel
        }
        if nextKeys != keys || kindChanged {
            var next: [String: UITab] = [:]
            let make = makeContent
            let tabs: [UITab] = specs.map { spec in
                if let existing = tabsByKey[spec.key], (existing is UISearchTab) == spec.opensPanel {
                    next[spec.key] = existing
                    return existing
                }
                let key = spec.key
                let tab: UITab = spec.opensPanel
                    ? UISearchTab { _ in make(key) }
                    : UITab(title: spec.title, image: nil, identifier: key) { _ in make(key) }
                next[spec.key] = tab
                return tab
            }
            tabsByKey = next
            keys = nextKeys
            controller.setTabs(tabs, animated: false)
        }
        for spec in specs {
            guard let tab = tabsByKey[spec.key] else { continue }
            if tab.title != spec.title { tab.title = spec.title }
            tab.accessibilityLabel = spec.accessibilityLabel ?? spec.title
            if spec.opensPanel {
                // No badge on the server tab (artin 10-23).
                tab.image = RaftIosServerAvatarImage.circle(
                    panelImage,
                    fallbackLabel: spec.avatarFallbackLabel,
                    diameter: RaftIosServerAvatarImage.diameter
                )
                if tab.badgeValue != nil { tab.badgeValue = nil }
            } else {
                tab.image = RaftIosTabIcon.images(for: spec).normal
                if tab.badgeValue != spec.badgeValue { tab.badgeValue = spec.badgeValue }
            }
        }
    }

    /// Shows [key] as the selected page tab (programmatic: the delegate is not asked).
    func select(_ key: String?, in controller: UITabBarController) {
        guard let key = key, let tab = tabsByKey[key], !(tab is UISearchTab), controller.selectedTab !== tab else {
            return
        }
        controller.selectedTab = tab
    }
}
