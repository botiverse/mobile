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
            guard notificationGroupKey(userInfo) == retract.groupKey else { return nil }
            guard let through = retract.throughSeq, let seq = int64(userInfo["seq"]) else {
                return item.identifier
            }
            return seq <= through ? item.identifier : nil
        }
    }

    /// A delivered / presented notification's group: `userInfo.groupKey`, else `serverId:conversationId`.
    static func notificationGroupKey(_ userInfo: [AnyHashable: Any]) -> String? {
        string(userInfo["groupKey"])
            ?? groupKey(serverId: string(userInfo["serverId"]) ?? "", conversationId: string(userInfo["conversationId"]) ?? "")
    }

    // MARK: Applying

    /// Records the retract's watermark (`RaftIosRetractWatermarks`, so a late alert of the group is dropped
    /// too), removes the matching delivered notifications plus any other delivered one a watermark covers,
    /// then applies `retract.badge` through [badgeSetter] (skipped when nil or the payload has no badge),
    /// then calls [completion] with the removed count.
    static func apply(
        _ retract: RaftIosNotificationRetract,
        center: UNUserNotificationCenter = .current(),
        defaults: UserDefaults = .standard,
        badgeSetter: ((Int, @escaping () -> Void) -> Void)? = nil,
        completion: @escaping (Int) -> Void = { _ in }
    ) {
        let watermarks = RaftIosRetractWatermarks.record(retract, defaults: defaults)
        center.getDeliveredNotifications { notifications in
            let delivered = notifications.map {
                (identifier: $0.request.identifier, userInfo: $0.request.content.userInfo)
            }
            var identifiers = identifiersToRemove(delivered: delivered, retract: retract)
            let matched = Set(identifiers)
            identifiers += RaftIosRetractWatermarks.coveredIdentifiers(
                delivered: notifications.map(RaftIosRetractWatermarks.deliveredItem),
                watermarks: watermarks,
                now: Date()
            ).filter { !matched.contains($0) }
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

/// "Retracted through" mark of one group (review N1): alert and retract pushes can arrive out of order
/// (APNs priority 10 vs 5, socket vs APNs), so an alert with `seq <= throughSeq` may land after its retract.
struct RaftIosRetractWatermark: Equatable {
    /// nil = the whole group ("all").
    let throughSeq: Int64?
    let recordedAt: Date
}

/// Persisted per-`groupKey` watermarks of applied retracts (UserDefaults). Pure merge / coverage logic plus
/// thin load / save / sweep wrappers.
///
/// Coverage of a notification of group G with watermark W (only while W is younger than [lifetime]):
/// - W has `throughSeq` and the notification has a `seq`: covered iff `seq <= throughSeq`.
/// - Otherwise (W is "all", or the notification has no `seq`): covered iff the notification was posted no later
///   than `W.recordedAt + lateArrivalGrace`. A seq cannot tell an old alert from a new one under "all", and a
///   late alert is by definition posted after the retract; the short grace catches the out-of-order arrival
///   without suppressing genuinely new alerts of that group for days. Unknown post date: not covered.
enum RaftIosRetractWatermarks {
    static let defaultsKey = "raftIosRetractWatermarks.v1"
    static let lifetime: TimeInterval = 3 * 24 * 60 * 60
    static let maxEntries = 200
    static let lateArrivalGrace: TimeInterval = 60

    private static let lock = NSLock()

    // MARK: Pure

    /// [watermarks] with the retract merged in (a newer numeric mark keeps the larger `throughSeq`; "all" or
    /// numeric after "all" replaces it; `recordedAt` = [now]), expired entries dropped, and the oldest entries
    /// evicted beyond [maxEntries].
    static func recording(
        groupKey: String,
        throughSeq: Int64?,
        into watermarks: [String: RaftIosRetractWatermark],
        now: Date
    ) -> [String: RaftIosRetractWatermark] {
        var result = pruned(watermarks, now: now)
        var through = throughSeq
        if let new = throughSeq, let old = result[groupKey]?.throughSeq {
            through = max(new, old)
        }
        result[groupKey] = RaftIosRetractWatermark(throughSeq: through, recordedAt: now)
        if result.count > maxEntries {
            let oldest = result.sorted { $0.value.recordedAt < $1.value.recordedAt }.prefix(result.count - maxEntries)
            for (key, _) in oldest { result.removeValue(forKey: key) }
        }
        return result
    }

    /// Drops entries older than [lifetime] (and ones recorded in the future, i.e. after a clock change).
    static func pruned(_ watermarks: [String: RaftIosRetractWatermark], now: Date) -> [String: RaftIosRetractWatermark] {
        watermarks.filter { isLive($0.value, now: now) }
    }

    static func isCovered(
        groupKey: String?,
        seq: Int64?,
        postedAt: Date? = nil,
        watermarks: [String: RaftIosRetractWatermark],
        now: Date
    ) -> Bool {
        guard let groupKey, let mark = watermarks[groupKey], isLive(mark, now: now) else { return false }
        if let through = mark.throughSeq, let seq {
            return seq <= through
        }
        guard let postedAt else { return false }
        return postedAt <= mark.recordedAt.addingTimeInterval(lateArrivalGrace)
    }

    static func isCovered(
        userInfo: [AnyHashable: Any],
        postedAt: Date?,
        watermarks: [String: RaftIosRetractWatermark],
        now: Date
    ) -> Bool {
        isCovered(
            groupKey: RaftIosNotificationRetraction.notificationGroupKey(userInfo),
            seq: RaftIosNotificationRetraction.int64(userInfo["seq"]),
            postedAt: postedAt,
            watermarks: watermarks,
            now: now
        )
    }

    /// Identifiers of [delivered] notifications a watermark covers.
    static func coveredIdentifiers(
        delivered: [(identifier: String, userInfo: [AnyHashable: Any], date: Date)],
        watermarks: [String: RaftIosRetractWatermark],
        now: Date
    ) -> [String] {
        guard !watermarks.isEmpty else { return [] }
        return delivered.compactMap { item in
            isCovered(userInfo: item.userInfo, postedAt: item.date, watermarks: watermarks, now: now)
                ? item.identifier : nil
        }
    }

    static func deliveredItem(_ notification: UNNotification) -> (identifier: String, userInfo: [AnyHashable: Any], date: Date) {
        (notification.request.identifier, notification.request.content.userInfo, notification.date)
    }

    private static func isLive(_ mark: RaftIosRetractWatermark, now: Date) -> Bool {
        let age = now.timeIntervalSince(mark.recordedAt)
        return age >= -lateArrivalGrace && age < lifetime
    }

    // MARK: Persistence

    /// Stored as `[groupKey: ["at": seconds since 1970, "through": Int64 (absent = all)]]`.
    static func load(defaults: UserDefaults = .standard, now: Date = Date()) -> [String: RaftIosRetractWatermark] {
        lock.lock()
        defer { lock.unlock() }
        return pruned(loadUnlocked(defaults), now: now)
    }

    /// Records [retract] and returns the stored (pruned) watermarks.
    @discardableResult
    static func record(
        _ retract: RaftIosNotificationRetract,
        defaults: UserDefaults = .standard,
        now: Date = Date()
    ) -> [String: RaftIosRetractWatermark] {
        lock.lock()
        defer { lock.unlock() }
        let updated = recording(
            groupKey: retract.groupKey,
            throughSeq: retract.throughSeq,
            into: loadUnlocked(defaults),
            now: now
        )
        defaults.set(updated.mapValues(encode), forKey: defaultsKey)
        return updated
    }

    /// Removes delivered notifications a stored watermark covers (app activation: a retract may have been
    /// applied while a late alert was already posted, or the background retract was never delivered first).
    static func removeCoveredDelivered(
        center: UNUserNotificationCenter = .current(),
        defaults: UserDefaults = .standard,
        completion: @escaping (Int) -> Void = { _ in }
    ) {
        let watermarks = load(defaults: defaults)
        guard !watermarks.isEmpty else {
            completion(0)
            return
        }
        center.getDeliveredNotifications { notifications in
            let identifiers = coveredIdentifiers(
                delivered: notifications.map(deliveredItem),
                watermarks: watermarks,
                now: Date()
            )
            if !identifiers.isEmpty {
                center.removeDeliveredNotifications(withIdentifiers: identifiers)
            }
            completion(identifiers.count)
        }
    }

    private static func loadUnlocked(_ defaults: UserDefaults) -> [String: RaftIosRetractWatermark] {
        guard let raw = defaults.dictionary(forKey: defaultsKey) else { return [:] }
        var result: [String: RaftIosRetractWatermark] = [:]
        for (key, value) in raw {
            guard let entry = value as? [String: Any], let at = (entry["at"] as? NSNumber)?.doubleValue else { continue }
            let through = RaftIosNotificationRetraction.int64(entry["through"])
            if entry["through"] != nil && through == nil { continue }
            result[key] = RaftIosRetractWatermark(throughSeq: through, recordedAt: Date(timeIntervalSince1970: at))
        }
        return result
    }

    private static func encode(_ mark: RaftIosRetractWatermark) -> [String: Any] {
        var entry: [String: Any] = ["at": mark.recordedAt.timeIntervalSince1970]
        if let through = mark.throughSeq { entry["through"] = NSNumber(value: through) }
        return entry
    }
}

/// System-notification tap -> the `slockSystemNotificationOpen` payload shared opens (contract §4.1, §4.5).
/// Pure: no UIKit / shared dependency.
enum RaftIosNotificationOpen {
    /// `slockNotificationTarget` value: "switch to `slockNotificationServerId` and open its Activity tab".
    static let activityTarget = "activity"

    /// A conversation target when the legacy top-level fields or a `channel` / `dm` / `thread` / `task`
    /// `route` yield one (unchanged output). Otherwise, for a payload that names a server (a message-less
    /// `reminder` / `system_notice` / `test`, an `agent` route, `route: null` + `routeUrl: /s/<slug>/activity`),
    /// an Activity target of that server. nil without a server, and for background `sync` / `retract` pushes
    /// (a background launch also carries `launchOptions[.remoteNotification]`).
    static func sanitizedPayload(
        userInfo: [AnyHashable: Any],
        categoryIdentifier: String? = nil,
        shellInstanceId: String? = nil
    ) -> [String: String]? {
        func stringValue(_ key: String) -> String? {
            RaftIosNotificationRetraction.string(userInfo[key])
        }
        if let type = stringValue("type")?.lowercased(), type == "sync" || type == "retract" {
            return nil
        }

        let supportedKinds: Set<String> = ["channel", "dm", "thread"]
        // Unified notifications (docs/contracts/notifications.md §4.5): newer servers also send a `route`
        // object. Legacy fields are derived from it; explicit valid top-level legacy fields still win, so
        // older servers keep working unchanged.
        let route = userInfo["route"] as? [AnyHashable: Any]
        func routeValue(_ key: String) -> String? {
            RaftIosNotificationRetraction.string(route?[key])
        }
        var routeKind: String?
        var routeChannelId: String?
        var routeThreadId: String?
        var routeParentChannelId: String?
        var routeParentMessageId: String?
        var routeTaskId: String?
        var routeTaskConversationChannelId: String?
        var routeTaskMessageId: String?
        let routeType = routeValue("type")
        switch routeType {
        case "channel", "dm":
            routeKind = routeType
            routeChannelId = routeValue("channelId")
        case "thread":
            routeKind = "thread"
            routeParentChannelId = routeValue("parentChannelId")
            routeParentMessageId = routeValue("parentMessageId")
            routeChannelId = stringValue("conversationId") ?? stringValue("threadId") ?? stringValue("channelId")
            routeThreadId = routeChannelId
        case "task":
            if let parentChannelType = routeValue("parentChannelType"), ["channel", "dm"].contains(parentChannelType) {
                routeKind = parentChannelType
            }
            routeChannelId = routeValue("parentChannelId")
            routeTaskId = routeValue("taskId")
            routeTaskConversationChannelId = stringValue("conversationId") ?? stringValue("taskConversationChannelId")
            routeTaskMessageId = routeValue("messageId") ?? stringValue("taskMessageId")
        default:
            // `agent` (and any unknown type): no conversation open target on mobile yet; falls back to the
            // Activity target below.
            break
        }
        let serverId = stringValue("serverId") ?? routeValue("serverId")
        let raftCategory = stringValue("raftCategory")
        let targetShellId =
            RaftIosNotificationRetraction.string(shellInstanceId) ?? stringValue("slockShellInstanceId")
        let legacyKind = stringValue("kind").flatMap { supportedKinds.contains($0) ? $0 : nil }
        guard
            let serverId,
            let channelId = stringValue("channelId") ?? routeChannelId,
            let messageId = routeValue("messageId")
                ?? stringValue("messageId")
                ?? (routeType == "task" ? stringValue("taskMessageId") : nil),
            let kind = legacyKind ?? routeKind,
            supportedKinds.contains(kind)
        else {
            guard let serverId else { return nil }
            var payload = [
                "slockNotificationServerId": serverId,
                "slockNotificationTarget": activityTarget,
            ]
            // Dedupe identity only (see `dedupeKey`): distinct reminders / notices of one server stay distinct.
            if let id = stringValue("id") { payload["slockNotificationId"] = id }
            if let groupKey = stringValue("groupKey") { payload["slockNotificationGroupKey"] = groupKey }
            if let raftCategory { payload["slockNotificationRaftCategory"] = raftCategory }
            if let targetShellId { payload["slockShellInstanceId"] = targetShellId }
            return payload
        }
        let threadId = stringValue("threadId") ?? routeThreadId ?? channelId
        var payload = [
            "slockNotificationServerId": serverId,
            "slockNotificationThreadId": threadId,
            "slockNotificationChannelId": channelId,
            "slockNotificationMessageId": messageId,
            "slockNotificationKind": kind,
        ]
        if let parentChannelId = stringValue("parentChannelId") ?? routeParentChannelId {
            payload["slockNotificationParentChannelId"] = parentChannelId
        }
        if let parentMessageId = stringValue("parentMessageId") ?? routeParentMessageId {
            payload["slockNotificationParentMessageId"] = parentMessageId
        }
        // Task v2 task-reply push (slock #11445): top-level custom keys next to `channelId`; shared
        // `parseRaftTaskConversationPushTarget` decides the route. A `task` route supplies them too.
        if let taskConversationChannelId = routeTaskConversationChannelId ?? stringValue("taskConversationChannelId") {
            payload["slockNotificationTaskConversationChannelId"] = taskConversationChannelId
            if let taskId = stringValue("taskId") ?? routeTaskId {
                payload["slockNotificationTaskId"] = taskId
            }
            if let taskMessageId = routeTaskMessageId ?? stringValue("taskMessageId") {
                payload["slockNotificationTaskMessageId"] = taskMessageId
            }
        }
        if let raftCategory {
            payload["slockNotificationRaftCategory"] = raftCategory
        }
        let category =
            RaftIosNotificationRetraction.string(categoryIdentifier)
                ?? RaftIosNotificationRetraction.string((userInfo["aps"] as? [AnyHashable: Any])?["category"])
        if let category {
            payload["slockNotificationCategory"] = category
        }
        // task #483: explicit shell target wins; a `slockShellInstanceId` key
        // already present in userInfo also carries through so a future
        // server-authored payload targets a shell without a scene.
        if let targetShellId {
            payload["slockShellInstanceId"] = targetShellId
        }
        return payload
    }

    /// Pending-open dedupe key. A conversation open: server | thread | message. An open without a message
    /// (Activity target): also the notification `id` / `groupKey`, so distinct notices do not collapse.
    static func dedupeKey(_ payload: [String: String]) -> String {
        var parts = [
            payload["slockNotificationServerId"] ?? "",
            payload["slockNotificationThreadId"] ?? "",
            payload["slockNotificationMessageId"] ?? "",
        ]
        if payload["slockNotificationMessageId"] == nil {
            parts += [
                payload["slockNotificationTarget"] ?? "",
                payload["slockNotificationId"] ?? "",
                payload["slockNotificationGroupKey"] ?? "",
            ]
        }
        return parts.joined(separator: "|")
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
