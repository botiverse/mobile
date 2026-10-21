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
    /// `UITabBarItem.badgeValue`: nil hides the badge, "" draws the dot-only badge.
    let badgeValue: String?
    let enabled: Bool
    /// The Search action (never the selected item; a tap opens search over the current tab).
    let searchAction: Bool
    /// Asset-catalog name of the tab's Lucide icon (the web's glyph); nil uses the SF Symbols.
    let imageAssetName: String?
    /// Numbered badge colours (0xAARRGGBB) from shared: the raft-ui pink unread count with ink
    /// figures. nil keeps the system badge look; the dot-only badge always does.
    let countBadgeFillArgb: Int64?
    let countBadgeTextArgb: Int64?

    init(
        key: String,
        title: String,
        systemImageName: String,
        selectedSystemImageName: String,
        badgeValue: String?,
        enabled: Bool,
        attentionDot: Bool = false,
        searchAction: Bool = false,
        imageAssetName: String? = nil,
        countBadgeFillArgb: Int64? = nil,
        countBadgeTextArgb: Int64? = nil
    ) {
        self.key = key
        self.title = title
        self.systemImageName = systemImageName
        self.selectedSystemImageName = selectedSystemImageName
        self.badgeValue = RaftIosTabBarItemMapping.badgeValue(fromShared: badgeValue, attentionDot: attentionDot)
        self.enabled = enabled
        self.searchAction = searchAction
        self.imageAssetName = imageAssetName
        self.countBadgeFillArgb = countBadgeFillArgb
        self.countBadgeTextArgb = countBadgeTextArgb
    }

    /// The badge shows a number (not nothing, not the dot-only badge): it takes the count colours.
    var showsCountBadge: Bool {
        RaftIosTabBarItemMapping.isCountBadge(badgeValue)
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
    /// A tab-bar attention dot (raft-design MobileNav / Badge: a dot, never a number; shared
    /// never sets it on the selected tab) becomes an empty `badgeValue`, which UIKit draws as
    /// the small dot-only badge.
    static func badgeValue(fromShared value: String?, attentionDot: Bool = false) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              trimmed != "0" else {
            return attentionDot ? "" : nil
        }
        return trimmed
    }

    /// A numbered badge (the Activity unread count); nil and "" (the dot) are not.
    static func isCountBadge(_ badgeValue: String?) -> Bool {
        guard let value = badgeValue else { return false }
        return !value.isEmpty
    }

    /// 0xAARRGGBB -> (red, green, blue, alpha) in 0...1.
    static func rgba(fromArgb argb: Int64) -> (CGFloat, CGFloat, CGFloat, CGFloat) {
        let value = UInt32(truncatingIfNeeded: argb)
        return (
            CGFloat((value >> 16) & 0xFF) / 255,
            CGFloat((value >> 8) & 0xFF) / 255,
            CGFloat(value & 0xFF) / 255,
            CGFloat((value >> 24) & 0xFF) / 255
        )
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
