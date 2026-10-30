import Foundation
import UserNotifications

/// One `notification:retract` (socket) or APNs `type: "retract"` push: close the delivered notifications of
/// `groupKey` whose `seq <= throughSeq` (the whole group when `throughSeq` is nil). Contract
/// docs/contracts/notifications.md §4.2 / §5.
struct RaftIosNotificationRetract: Equatable {
    let serverId: String
    let groupKey: String
    let conversationId: String
    /// nil = every notification of the group.
    let throughSeq: Int64?
    /// Badge to apply afterwards (APNs only; the socket event carries none).
    let badge: Int?
}

/// A background (`content-available`) push this app acts on. Alert pushes are not represented.
enum RaftIosBackgroundPush: Equatable {
    case sync(badge: Int?)
    case retract(RaftIosNotificationRetract)
}

/// Pure parsing / matching for notification retraction, plus a small applier over
/// `UNUserNotificationCenter`. No `shared` / UIKit dependency so unit tests compile it directly.
enum RaftIosNotificationRetraction {
    static let socketEventName = "notification:retract"

    // MARK: Parsing

    /// APNs background push (`type: "sync" | "retract"`). Any other `type` (including `alert`) returns nil.
    static func parseBackgroundPush(_ userInfo: [AnyHashable: Any]) -> RaftIosBackgroundPush? {
        switch string(userInfo["type"])?.lowercased() {
        case "sync":
            return .sync(badge: badge(userInfo["badge"]))
        case "retract":
            return parseRetract(userInfo).map { .retract($0) }
        default:
            return nil
        }
    }

    /// Socket `notification:retract` raw JSON payload (no `type` key, no badge).
    static func parseSocketRetract(rawPayload: String) -> RaftIosNotificationRetract? {
        guard
            let data = rawPayload.data(using: .utf8),
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        return parseRetract(object)
    }

    /// Retract body shared by both transports. Needs a group: `groupKey`, or `serverId` + `conversationId`.
    /// `throughSeq` null / absent / empty string = whole group; any other non-integer value rejects the payload.
    static func parseRetract(_ payload: [AnyHashable: Any]) -> RaftIosNotificationRetract? {
        let serverId = string(payload["serverId"]) ?? ""
        let conversationId = string(payload["conversationId"]) ?? ""
        guard let groupKey = string(payload["groupKey"]) ?? groupKey(serverId: serverId, conversationId: conversationId) else {
            return nil
        }
        let throughSeq: Int64?
        let rawThrough = payload["throughSeq"]
        if rawThrough == nil || rawThrough is NSNull || (rawThrough as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true {
            throughSeq = nil
        } else if let value = int64(rawThrough) {
            throughSeq = value
        } else {
            return nil
        }
        return RaftIosNotificationRetract(
            serverId: serverId,
            groupKey: groupKey,
            conversationId: conversationId,
            throughSeq: throughSeq,
            badge: badge(payload["badge"])
        )
    }

    // MARK: Matching

    /// Identifiers of delivered notifications the retract closes. A notification's group is its
    /// `userInfo.groupKey`, else `serverId:conversationId`; one without a group never matches. In a matching
    /// group, a notification without `seq` is removed as well.
    static func identifiersToRemove(
        delivered: [(identifier: String, userInfo: [AnyHashable: Any])],
        retract: RaftIosNotificationRetract
    ) -> [String] {
        delivered.compactMap { item in
            let userInfo = item.userInfo
            let itemGroup = string(userInfo["groupKey"])
                ?? groupKey(serverId: string(userInfo["serverId"]) ?? "", conversationId: string(userInfo["conversationId"]) ?? "")
            guard itemGroup == retract.groupKey else { return nil }
            guard let through = retract.throughSeq, let seq = int64(userInfo["seq"]) else {
                return item.identifier
            }
            return seq <= through ? item.identifier : nil
        }
    }

    // MARK: Applying

    /// Removes the matching delivered notifications, then applies `retract.badge` through [badgeSetter]
    /// (skipped when nil or the payload has no badge), then calls [completion] with the removed count.
    static func apply(
        _ retract: RaftIosNotificationRetract,
        center: UNUserNotificationCenter = .current(),
        badgeSetter: ((Int, @escaping () -> Void) -> Void)? = nil,
        completion: @escaping (Int) -> Void = { _ in }
    ) {
        center.getDeliveredNotifications { notifications in
            let delivered = notifications.map {
                (identifier: $0.request.identifier, userInfo: $0.request.content.userInfo)
            }
            let identifiers = identifiersToRemove(delivered: delivered, retract: retract)
            if !identifiers.isEmpty {
                center.removeDeliveredNotifications(withIdentifiers: identifiers)
            }
            guard let badge = retract.badge, let badgeSetter else {
                completion(identifiers.count)
                return
            }
            badgeSetter(badge) { completion(identifiers.count) }
        }
    }

    // MARK: Value helpers

    static func groupKey(serverId: String, conversationId: String) -> String? {
        serverId.isEmpty || conversationId.isEmpty ? nil : "\(serverId):\(conversationId)"
    }

    static func string(_ value: Any?) -> String? {
        guard let value = value as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Integer from Int / Int64 / NSNumber (not a boolean, integral only) / decimal string.
    static func int64(_ value: Any?) -> Int64? {
        // NSNumber first: a boolean (CFBoolean, also what a Swift Bool bridges to) would otherwise
        // bridge to Int. Do not test `is Bool`: NSNumber(1) matches it.
        switch value {
        case let value as NSNumber:
            if CFGetTypeID(value) == CFBooleanGetTypeID() { return nil }
            let double = value.doubleValue
            guard double.isFinite, double.rounded() == double else { return nil }
            return value.int64Value
        case let value as Int:
            return Int64(value)
        case let value as Int64:
            return value
        case let value as String:
            return Int64(value.trimmingCharacters(in: .whitespacesAndNewlines))
        default:
            return nil
        }
    }

    static func badge(_ value: Any?) -> Int? {
        guard let value = int64(value) else { return nil }
        return Int(clamping: max(0, value))
    }
}

/// Client -> server `presence:activity` (contract §5): `active: true` while the app is in the foreground
/// (on connect and every [heartbeatInterval] seconds), `active: false` when it goes to the background.
enum RaftIosPresenceActivity {
    static let eventName = "presence:activity"
    static let heartbeatInterval: TimeInterval = 60

    static func payload(active: Bool) -> [String: Any] {
        active
            ? ["active": true, "idleSeconds": 0]
            : ["active": false, "idleSeconds": NSNull()]
    }
}
