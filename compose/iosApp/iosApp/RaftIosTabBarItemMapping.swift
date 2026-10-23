import CoreGraphics
import Foundation

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

    init(
        key: String,
        title: String,
        systemImageName: String,
        selectedSystemImageName: String,
        badgeValue: String?,
        enabled: Bool,
        searchAction: Bool = false,
        imageAssetName: String? = nil
    ) {
        self.key = key
        self.title = title
        self.systemImageName = systemImageName
        self.selectedSystemImageName = selectedSystemImageName
        self.badgeValue = RaftIosTabBarItemMapping.badgeValue(fromShared: badgeValue)
        self.enabled = enabled
        self.searchAction = searchAction
        self.imageAssetName = imageAssetName
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
