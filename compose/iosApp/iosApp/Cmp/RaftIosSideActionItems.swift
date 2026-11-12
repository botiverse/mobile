import UIKit

/// A page's iPhone Duo side column, as `RaftSideActionHostBridge` publishes it (wire version 1), made
/// into navigation bar items for the system's vertical bar (RaftIosSideActionHost): Back's group
/// (`primary`), then the visible actions and the ⋯ (`ellipsis`, a `UIMenu` of the overflowed ones),
/// in listed order; each action's `UIAction` carries its id as identifier and reports it to `onTap`.
/// The host puts them in the vertical bar (`RaftIosMakeBarButtonItemsVerticalPreferred`: the
/// 27.1-only `axisBehavior`, behind an SDK guard in RaftIosDuoRuntime.m).
@available(iOS 27.1, *)
enum RaftIosSideActionItems {
    static func make(
        json: String,
        onTap: @escaping (String) -> Void
    ) -> (leading: [UIBarButtonItem], trailing: [UIBarButtonItem])? {
        guard let column = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return nil }
        let actions = column["actions"] as? [[String: Any]] ?? []
        let byId = Dictionary(actions.map { ($0["id"] as? String ?? "", $0) }, uniquingKeysWith: { first, _ in first })
        let listed = { (key: String) in (column[key] as? [String] ?? []).compactMap { byId[$0] } }

        // A button shows its symbol only (its label is for VoiceOver); a ⋯ row shows both.
        func action(_ item: [String: Any], titled: Bool) -> UIAction {
            let id = item["id"] as? String ?? ""
            return UIAction(
                title: titled ? item["label"] as? String ?? "" : "",
                image: UIImage(systemName: item["systemSymbol"] as? String ?? ""),
                identifier: UIAction.Identifier(id),
                attributes: item["enabled"] as? Bool == false ? .disabled : []
            ) { _ in onTap(id) }
        }
        func button(_ item: [String: Any]) -> UIBarButtonItem {
            let button = UIBarButtonItem(primaryAction: action(item, titled: false))
            button.accessibilityLabel = item["label"] as? String
            button.isEnabled = item["enabled"] as? Bool ?? true
            if item["attention"] as? Bool == true { button.badge = MainActor.assumeIsolated { .indicator() } }
            return button
        }

        let leading = (column["primary"] as? [[String: Any]] ?? []).map(button)
        var trailing = listed("visibleActionIds").map(button)
        let overflow = listed("overflowActionIds")
        if !overflow.isEmpty {
            let more = UIBarButtonItem(title: nil, image: UIImage(systemName: "ellipsis"), primaryAction: nil, menu: UIMenu(children: overflow.map { action($0, titled: true) }))
            more.accessibilityLabel = column["overflowLabel"] as? String
            if column["overflowAttention"] as? Bool == true { more.badge = MainActor.assumeIsolated { .indicator() } }
            trailing.append(more)
        }
        return (leading, trailing)
    }
}
