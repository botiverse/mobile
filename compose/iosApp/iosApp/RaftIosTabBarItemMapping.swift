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
    /// `UITabBarItem.badgeValue`: nil hides the badge.
    let badgeValue: String?
    let enabled: Bool

    init(
        key: String,
        title: String,
        systemImageName: String,
        selectedSystemImageName: String,
        badgeValue: String?,
        enabled: Bool
    ) {
        self.key = key
        self.title = title
        self.systemImageName = systemImageName
        self.selectedSystemImageName = selectedSystemImageName
        self.badgeValue = RaftIosTabBarItemMapping.badgeValue(fromShared: badgeValue)
        self.enabled = enabled
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
