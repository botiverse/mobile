import UIKit
import shared

/// A page's iPhone Duo side column, as `RaftSideActionHostBridge` publishes it (wire version 1), made
/// into navigation bar items for the system's vertical bar (RaftIosSideActionHost), each wearing the
/// design-system (Lucide) glyph the Compose column draws: Back's group (`primary`), then the
/// visible actions and the ⋯ (`ellipsis`, a `UIMenu` of the overflowed ones), in listed order; each action's `UIAction` carries its id as identifier and reports it to `onTap`.
/// The host puts them in the vertical bar (`RaftIosMakeBarButtonItemsVerticalPreferred`: the
/// 27.1-only `axisBehavior`, behind an SDK guard in RaftIosDuoRuntime.m).
///
/// Attention: a button wears the indicator and VoiceOver reads its `attentionDescription` as the
/// value; a ⋯ row shows the description as its subtitle (a menu row has no badge), and the ⋯ wears
/// the indicator. Delete (`trash`) is a destructive action.
@available(iOS 27.1, *)
enum RaftIosSideActionItems {
    /// Nil when [json] is not a column, or one of the buttons or rows it shows has neither a glyph
    /// the app ships nor a symbol this system has: the host then hands the column back to the page
    /// rather than show an empty or partial one.
    static func make(
        json: String,
        onTap: @escaping (String) -> Void
    ) -> (leading: [UIBarButtonItem], trailing: [UIBarButtonItem])? {
        guard let column = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return nil }
        let actions = column["actions"] as? [[String: Any]] ?? []
        let byId = Dictionary(actions.map { ($0["id"] as? String ?? "", $0) }, uniquingKeysWith: { first, _ in first })
        let listed = { (key: String) in (column[key] as? [String] ?? []).compactMap { byId[$0] } }
        let primary = column["primary"] as? [[String: Any]] ?? []
        let visible = listed("visibleActionIds")
        let overflow = listed("overflowActionIds")

        func symbol(_ item: [String: Any]) -> String { item["systemSymbol"] as? String ?? "" }
        // The design-system (Lucide) glyph the Compose column draws (`iconName`, template asset
        // `lucide-<name>`, tinted by the glass like a symbol), at the bar's / menu's icon size;
        // the SF Symbol only when the app does not ship that glyph (artin, dm #128).
        func image(_ item: [String: Any], pointSize: CGFloat = RaftNativeNavIcon.barPointSize) -> UIImage? {
            let asset = (item["iconName"] as? String).flatMap { $0.isEmpty ? nil : "lucide-\($0)" }
            return RaftNativeNavIcon.image(asset: asset, symbol: symbol(item), pointSize: pointSize)
        }
        guard (primary + visible + overflow).allSatisfy({ image($0) != nil }) else { return nil }
        func wantsAttention(_ item: [String: Any]) -> Bool { item["attention"] as? Bool == true }
        func attentionDescription(_ item: [String: Any]) -> String? {
            wantsAttention(item) ? item["attentionDescription"] as? String : nil
        }

        // A button shows its symbol only (its label is for VoiceOver); a ⋯ row shows both.
        func action(_ item: [String: Any], titled: Bool) -> UIAction {
            let id = item["id"] as? String ?? ""
            var attributes: UIMenuElement.Attributes = []
            if item["enabled"] as? Bool == false { attributes.insert(.disabled) }
            if symbol(item).hasPrefix("trash") { attributes.insert(.destructive) }
            let action = UIAction(
                title: titled ? item["label"] as? String ?? "" : "",
                image: image(item, pointSize: titled ? RaftNativeNavIcon.menuPointSize : RaftNativeNavIcon.barPointSize),
                identifier: UIAction.Identifier(id),
                attributes: attributes
            ) { _ in onTap(id) }
            if titled { action.subtitle = attentionDescription(item) }
            return action
        }
        func button(_ item: [String: Any]) -> UIBarButtonItem {
            let button = UIBarButtonItem(primaryAction: action(item, titled: false))
            button.accessibilityLabel = item["label"] as? String
            button.accessibilityValue = attentionDescription(item)
            button.isEnabled = item["enabled"] as? Bool ?? true
            if wantsAttention(item) { button.badge = MainActor.assumeIsolated { .indicator() } }
            return button
        }

        let leading = primary.map(button)
        var trailing = visible.map(button)
        if !overflow.isEmpty {
            let more = UIBarButtonItem(title: nil, image: RaftNativeNavIcon.image(asset: "lucide-ellipsis", symbol: "ellipsis"), primaryAction: nil, menu: UIMenu(children: overflow.map { action($0, titled: true) }))
            more.accessibilityLabel = column["overflowLabel"] as? String
            if column["overflowAttention"] as? Bool == true {
                more.badge = MainActor.assumeIsolated { .indicator() }
                more.accessibilityValue = overflow.compactMap(attentionDescription).joined(separator: ", ").nilIfEmpty
            }
            trailing.append(more)
        }
        return (leading, trailing)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// The bridge's listener for one RaftIosSideActionHost, holding the host weakly. The bridge stays
/// in the global RaftSideActionHostRegistry until the host's deinit unregisters it; were the bridge
/// to hold the host itself, that deinit would never run and every page would leak its host.
final class RaftIosWeakSideActionListener: NSObject, RaftSideActionHostListener {
    private weak var target: RaftSideActionHostListener?

    init(_ target: RaftSideActionHostListener) {
        self.target = target
    }

    func onSideActionColumnChanged(json: String) {
        target?.onSideActionColumnChanged(json: json)
    }

    func onSideActionColumnCleared() {
        target?.onSideActionColumnCleared()
    }
}

// Lives here, not in ContentView.swift: this file is also compiled into iosAppTests, which does
// not build ContentView.swift (release/1.25.0 full CI, iOS compile: RaftNativeNavIcon not in scope).
/// The design-system (Lucide) glyphs of the bar, from the asset catalog (`lucide-<name>`, template
/// SVGs generated with the Compose catalog), drawn at the Compose head's icon size; SF Symbols only
/// when a glyph has no asset (artin, task #98: the bar's SF Symbols were not our icons).
enum RaftNativeNavIcon {
    /// The Compose head's / side column's icon size (RaftSideActionColumnDefaults.IconSize).
    static let barPointSize: CGFloat = 22
    static let menuPointSize: CGFloat = 20
    private static var cache: [String: UIImage] = [:]

    /// [asset] (template, tinted by the bar) at [pointSize], else the SF Symbol [symbol], else nil.
    static func image(asset: String?, symbol: String?, pointSize: CGFloat = barPointSize) -> UIImage? {
        if let asset, let image = lucide(asset, pointSize: pointSize) {
            return image
        }
        return symbol.flatMap { UIImage(systemName: $0) }
    }

    static func lucide(_ name: String, pointSize: CGFloat) -> UIImage? {
        let key = "\(name)@\(pointSize)"
        if let cached = cache[key] { return cached }
        guard let source = UIImage(named: name) else { return nil }
        let size = CGSize(width: pointSize, height: pointSize)
        // The SVG keeps its vector data, so it re-renders sharp at this size.
        let image = UIGraphicsImageRenderer(size: size).image { _ in
            source.draw(in: CGRect(origin: .zero, size: size))
        }.withRenderingMode(.alwaysTemplate)
        cache[key] = image
        return image
    }
}
