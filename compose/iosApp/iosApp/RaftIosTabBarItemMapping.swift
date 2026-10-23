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

    /// The tabs the system bar shows, and the panel tab shown as the separate server button.
    /// With [separatePanelButton] (iOS 26) the first panel tab leaves the bar, so pressing it
    /// never moves the bar's Liquid Glass selection; before iOS 26 it stays the last tab.
    static func splitPanelButton(
        _ items: [RaftIosTabBarItemSpec],
        separatePanelButton: Bool
    ) -> (tabs: [RaftIosTabBarItemSpec], panel: RaftIosTabBarItemSpec?) {
        guard separatePanelButton, let index = items.firstIndex(where: \.opensPanel) else {
            return (items, nil)
        }
        var tabs = items
        let panel = tabs.remove(at: index)
        return (tabs, panel)
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

/// The iOS 26 server button beside the Liquid Glass tab bar (like Music's separate Search).
///
/// Measured, not public: the system's separated circle, spacing and margins are not exposed, so
/// these follow the iOS 26 iPhone bar (a 62pt-tall pill, about 21pt from the screen edges).
/// They need a device check.
enum RaftIosServerButtonLayout {
    /// The round button: the height of the bar's pill.
    static let diameter: CGFloat = 62
    /// Between the bar's pill and the button.
    static let spacing: CGFloat = 8
    /// The button's trailing edge from the safe area: the pill's own side margin.
    static let trailingMargin: CGFloat = 21
    /// The avatar, clipped to a circle, centred in the glass.
    static let avatarDiameter: CGFloat = 40

    /// The width left to the system tab bar's container: the bar lays its pill out inside it
    /// (margins and safe areas included), so the pill and the button can never overlap.
    static func tabBarContainerWidth(containerWidth: CGFloat, showsButton: Bool) -> CGFloat {
        guard showsButton else { return max(0, containerWidth) }
        return max(0, containerWidth - diameter - spacing)
    }

    /// The button's frame in the container. [barFrame] is the system tab bar's frame and
    /// [barBottomInset] its bottom safe-area inset: the pill is centred in the bar above it.
    static func buttonFrame(
        containerWidth: CGFloat,
        safeAreaRight: CGFloat,
        barFrame: CGRect,
        barBottomInset: CGFloat
    ) -> CGRect {
        let contentHeight = max(0, barFrame.height - barBottomInset)
        let centerY = barFrame.minY + contentHeight / 2
        let x = containerWidth - max(0, safeAreaRight) - trailingMargin - diameter
        return CGRect(x: x.rounded(), y: (centerY - diameter / 2).rounded(), width: diameter, height: diameter)
    }
}

/// The server's avatar for the button: its image clipped to a circle, or the app's initial tile
/// (ink with the initial in brand yellow, as the panel and Android draw it).
enum RaftIosServerAvatarImage {
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

/// The separate round Liquid Glass server button (iOS 26): the system glass button style with
/// the server's avatar in a circle inside it. No badge (artin 10-23). A tap toggles the server
/// panel; it is a plain button, so it never takes or moves the tab bar's selection.
@available(iOS 26.0, *)
final class RaftIosServerTabButton: UIButton {
    private let avatarView = UIImageView()

    static func make() -> RaftIosServerTabButton {
        let button = RaftIosServerTabButton(type: .system)
        var configuration = UIButton.Configuration.glass()
        configuration.cornerStyle = .capsule
        button.configuration = configuration
        button.avatarView.isUserInteractionEnabled = false
        button.avatarView.contentMode = .scaleAspectFit
        button.avatarView.isAccessibilityElement = false
        button.addSubview(button.avatarView)
        button.isAccessibilityElement = true
        button.accessibilityTraits = .button
        return button
    }

    /// Label, identifier and avatar from [spec]; [avatar] is the decoded server image, if any.
    func apply(_ spec: RaftIosTabBarItemSpec, avatar: UIImage?) {
        accessibilityLabel = spec.accessibilityLabel ?? spec.title
        accessibilityIdentifier = "raft-tab-\(spec.key)"
        isEnabled = spec.enabled
        avatarView.image = RaftIosServerAvatarImage.circle(
            avatar,
            fallbackLabel: spec.avatarFallbackLabel,
            diameter: RaftIosServerButtonLayout.avatarDiameter
        )
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let edge = RaftIosServerButtonLayout.avatarDiameter
        avatarView.frame = CGRect(x: (bounds.width - edge) / 2, y: (bounds.height - edge) / 2, width: edge, height: edge)
        bringSubviewToFront(avatarView)
    }
}

