import SwiftUI
import shared
import SDWebImage
import SDWebImageSVGCoder
import UIKit
import UserNotifications

private enum RaftIosPushEnvironment: String {
    case sandbox
    case production
}

// Internal (not private) so RaftIosSceneDelegate can forward notification taps.
enum RaftIosPushHost {
    private static let remoteNotificationsStateName = Notification.Name("slockIosRemoteNotificationsState")
    private static let systemNotificationOpenName = Notification.Name("slockSystemNotificationOpen")
    private static let systemNotificationOpenReadyName = Notification.Name("slockSystemNotificationOpenReady")
    private static let systemNotificationOpenNotReadyName = Notification.Name("slockSystemNotificationOpenNotReady")
    private static var installationId: String?
    private static var remoteNotificationsObserver: NSObjectProtocol?
    private static var systemNotificationOpenReadyObserver: NSObjectProtocol?
    private static var systemNotificationOpenNotReadyObserver: NSObjectProtocol?
    private static var desiredRemoteNotificationsEnabled = false
    private static var remoteNotificationsGeneration: UInt64 = 0
    private static var systemNotificationOpenReadyCount = 0
    private static var pendingSystemNotificationOpens: [[String: String]] = []
    private static var pendingSystemNotificationOpenKeys: Set<String> = []

    static func install() {
        if remoteNotificationsObserver == nil {
            remoteNotificationsObserver = NotificationCenter.default.addObserver(
                forName: remoteNotificationsStateName,
                object: nil,
                queue: nil
            ) { notification in
                guard let enabled = notification.userInfo?["enabled"] as? Bool else {
                    RaftIosDiagnosticsRecord(
                        "warn",
                        "SlockIosPush",
                        "remote_notifications_state_rejected reason=missing_enabled"
                    )
                    return
                }
                setRemoteNotificationsEnabled(enabled)
            }
        }
        if systemNotificationOpenReadyObserver == nil {
            systemNotificationOpenReadyObserver = NotificationCenter.default.addObserver(
                forName: systemNotificationOpenReadyName,
                object: nil,
                queue: .main
            ) { _ in
                systemNotificationOpenReadyCount += 1
                drainPendingSystemNotificationOpens()
            }
        }
        if systemNotificationOpenNotReadyObserver == nil {
            systemNotificationOpenNotReadyObserver = NotificationCenter.default.addObserver(
                forName: systemNotificationOpenNotReadyName,
                object: nil,
                queue: .main
            ) { _ in
                systemNotificationOpenReadyCount = max(0, systemNotificationOpenReadyCount - 1)
            }
        }
        let candidate = UUID().uuidString.lowercased()
        installationId = RaftIosPushRegistrationBridge.shared.install(candidateInstallationId: candidate)
        RaftIosPushRegistrationBridge.shared.retryPendingRegistration()
        RaftIosDiagnosticsRecord(
            "info",
            "SlockIosPush",
            "push_registration_host_installed installationIdPresent=\(installationId?.isEmpty == false)"
        )
    }

    /// `aps.category` of every Raft alert push (contract §4.2). No actions yet.
    static let messageCategoryIdentifier = "RAFT_MESSAGE"

    static func registerNotificationCategories() {
        let category = UNNotificationCategory(
            identifier: messageCategoryIdentifier,
            actions: [],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// App icon badge (contract §1.4: the cross-server Activity sum). [completion] runs once applied.
    static func setBadgeCount(_ count: Int, completion: @escaping () -> Void = {}) {
        let count = max(0, count)
        if #available(iOS 16.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(count) { error in
                if let error {
                    RaftIosDiagnosticsRecord(
                        "warn",
                        "SlockIosPush",
                        "badge_set_failed errorType=\(String(describing: type(of: error)))"
                    )
                }
                completion()
            }
        } else {
            runOnMain {
                UIApplication.shared.applicationIconBadgeNumber = count
                completion()
            }
        }
    }

    /// APNs background push (`content-available`): `sync` sets the badge, `retract` removes the
    /// group's delivered notifications and then sets the badge. Alert / unknown types: `.noData`.
    static func handleBackgroundPush(
        userInfo: [AnyHashable: Any],
        completion: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let push = RaftIosNotificationRetraction.parseBackgroundPush(userInfo) else {
            completion(.noData)
            return
        }
        switch push {
        case .sync(let badge):
            guard let badge else {
                RaftIosDiagnosticsRecord("info", "SlockIosPush", "background_sync_skipped reason=missing_badge")
                completion(.noData)
                return
            }
            setBadgeCount(badge) {
                RaftIosDiagnosticsRecord("info", "SlockIosPush", "background_sync_applied badge=\(badge)")
                completion(.newData)
            }
        case .retract(let retract):
            RaftIosNotificationRetraction.apply(
                retract,
                badgeSetter: { badge, done in setBadgeCount(badge, completion: done) }
            ) { removed in
                RaftIosDiagnosticsRecord(
                    "info",
                    "SlockIosPush",
                    "background_retract_applied removed=\(removed) wholeGroup=\(retract.throughSeq == nil) " +
                        "badgeApplied=\(retract.badge != nil)"
                )
                completion(removed > 0 || retract.badge != nil ? .newData : .noData)
            }
        }
    }

    private static func setRemoteNotificationsEnabled(_ enabled: Bool) {
        runOnMain {
            desiredRemoteNotificationsEnabled = enabled
            remoteNotificationsGeneration &+= 1
            let generation = remoteNotificationsGeneration
            if !enabled {
                UIApplication.shared.unregisterForRemoteNotifications()
                RaftIosDiagnosticsRecord(
                    "info",
                    "SlockIosPush",
                    "remote_notifications_state_applied enabled=false"
                )
                return
            }
            enableRemoteNotifications(generation: generation)
        }
    }

    private static func enableRemoteNotifications(generation: UInt64) {
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.getNotificationSettings { settings in
            let status = settings.authorizationStatus
            RaftIosDiagnosticsRecord(
                "info",
                "SlockIosPush",
                "notification_permission_status status=\(authorizationStatusName(status)) trigger=authenticated_session"
            )
            reportOsPermission(status)
            guard status == .notDetermined else {
                registerForRemoteNotificationsIfCurrent(generation: generation)
                return
            }
            notificationCenter.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
                if let error {
                    RaftIosDiagnosticsRecord(
                        "warn",
                        "SlockIosPush",
                        "notification_permission_request_failed errorType=\(String(describing: type(of: error)))"
                    )
                } else {
                    RaftIosDiagnosticsRecord(
                        "info",
                        "SlockIosPush",
                        "notification_permission_request_finished granted=\(granted)"
                    )
                }
                refreshOsPermission()
                registerForRemoteNotificationsIfCurrent(generation: generation)
            }
        }
    }

    private static func registerForRemoteNotificationsIfCurrent(generation: UInt64) {
        runOnMain {
            guard desiredRemoteNotificationsEnabled, remoteNotificationsGeneration == generation else {
                RaftIosDiagnosticsRecord(
                    "info",
                    "SlockIosPush",
                    "remote_notifications_registration_skipped reason=stale_desired_state"
                )
                return
            }
            // APNs token registration is intentionally independent from visible
            // alert permission. Keeping the token current means a user who later
            // enables notifications in Settings does not need another login.
            UIApplication.shared.registerForRemoteNotifications()
            RaftIosDiagnosticsRecord(
                "info",
                "SlockIosPush",
                "remote_notifications_state_applied enabled=true"
            )
        }
    }

    /// The registration's `osPermission` (slock docs/contracts/notifications.md §3.1): `denied` makes
    /// the server send this device only sync / retract pushes. Shared re-registers when it changes.
    static func reportOsPermission(_ status: UNAuthorizationStatus) {
        let value: String
        switch status {
        case .authorized, .ephemeral:
            value = "granted"
        case .denied:
            value = "denied"
        case .provisional:
            value = "provisional"
        default:
            value = "unknown"
        }
        RaftPushDeviceStateHost.shared.updateOsPermission(value: value)
    }

    /// Re-reads the permission (app foreground: it may have changed in Settings meanwhile).
    static func refreshOsPermission() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            reportOsPermission(settings.authorizationStatus)
        }
    }

    private static func authorizationStatusName(_ status: UNAuthorizationStatus) -> String {
        switch status {
        case .notDetermined:
            return "not_determined"
        case .denied:
            return "denied"
        case .authorized:
            return "authorized"
        case .provisional:
            return "provisional"
        case .ephemeral:
            return "ephemeral"
        @unknown default:
            return "unknown"
        }
    }

    private static func runOnMain(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    static func didRegister(deviceToken: Data) {
        guard installationId?.isEmpty == false else {
            RaftIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_installation_id")
            return
        }
        guard let environment = signedPushEnvironment() else {
            RaftIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_or_invalid_aps_environment")
            return
        }
        guard let topic = Bundle.main.bundleIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines), !topic.isEmpty else {
            RaftIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_topic")
            return
        }
        let appVersion =
            (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !appVersion.isEmpty else {
            RaftIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_app_version")
            return
        }
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        RaftIosPushRegistrationBridge.shared.updateRegistration(
            deviceToken: token,
            topic: topic,
            env: environment.rawValue,
            appVersion: appVersion
        )
        RaftIosDiagnosticsRecord(
            "info",
            "SlockIosPush",
            "push_token_received env=\(environment.rawValue) topicPresent=true tokenBytes=\(deviceToken.count)"
        )
    }

    static func didFailToRegister(error: Error) {
        RaftIosDiagnosticsRecord(
            "warn",
            "SlockIosPush",
            "push_token_registration_failed errorType=\(String(describing: type(of: error)))"
        )
    }

    static func acceptSystemNotificationOpen(
        userInfo: [AnyHashable: Any],
        categoryIdentifier: String? = nil,
        shellInstanceId: String? = nil,
        source: String
    ) {
        // Product analytics: this open came from a notification (`app_opened.via`). Reported
        // before the activation it belongs to, so not deferred to the main queue.
        RaftIosProductEventsHost.shared.noteNotificationOpen()
        runOnMain {
            guard let payload = sanitizedSystemNotificationOpenPayload(
                userInfo: userInfo,
                categoryIdentifier: categoryIdentifier,
                shellInstanceId: shellInstanceId
            ) else {
                RaftIosDiagnosticsRecord(
                    "warn",
                    "SlockIosPush",
                    "system_notification_open_rejected reason=invalid_identity source=\(source)"
                )
                return
            }
            let key = systemNotificationOpenKey(payload)
            guard !pendingSystemNotificationOpenKeys.contains(key) else {
                RaftIosDiagnosticsRecord(
                    "info",
                    "SlockIosPush",
                    "system_notification_open_skipped reason=duplicate source=\(source)"
                )
                return
            }
            if systemNotificationOpenReadyCount > 0 {
                dispatchSystemNotificationOpen(payload, source: source)
                return
            }
            pendingSystemNotificationOpenKeys.insert(key)
            pendingSystemNotificationOpens.append(payload)
            if pendingSystemNotificationOpens.count > 8 {
                let removed = pendingSystemNotificationOpens.removeFirst()
                pendingSystemNotificationOpenKeys.remove(systemNotificationOpenKey(removed))
            }
            RaftIosDiagnosticsRecord(
                "info",
                "SlockIosPush",
                "system_notification_open_queued source=\(source)"
            )
        }
    }

    static func targetsVisibleConversation(userInfo: [AnyHashable: Any]) -> Bool {
        func stringValue(_ key: String) -> String? {
            userInfo[key] as? String
        }
        return RaftVisibleConversationRegistryKt.raftSystemNotificationTargetsVisibleConversation(
            serverId: stringValue("serverId"),
            kind: stringValue("kind"),
            channelId: stringValue("channelId"),
            threadId: stringValue("threadId"),
            parentChannelId: stringValue("parentChannelId"),
            parentMessageId: stringValue("parentMessageId"),
            messageId: stringValue("messageId"),
            category: (userInfo["aps"] as? [AnyHashable: Any])?["category"] as? String
        )
    }

    /// Conversation target, or (message-less reminder / notice / test, `agent` route) the server's Activity
    /// target (`slockNotificationTarget: "activity"`). See `RaftIosNotificationOpen`.
    private static func sanitizedSystemNotificationOpenPayload(
        userInfo: [AnyHashable: Any],
        categoryIdentifier: String?,
        shellInstanceId: String? = nil
    ) -> [String: String]? {
        RaftIosNotificationOpen.sanitizedPayload(
            userInfo: userInfo,
            categoryIdentifier: categoryIdentifier,
            shellInstanceId: shellInstanceId
        )
    }

    private static func systemNotificationOpenKey(_ payload: [String: String]) -> String {
        RaftIosNotificationOpen.dedupeKey(payload)
    }

    private static func drainPendingSystemNotificationOpens() {
        let pending = pendingSystemNotificationOpens
        pendingSystemNotificationOpens.removeAll(keepingCapacity: true)
        for payload in pending {
            dispatchSystemNotificationOpen(payload, source: "ready_drain")
        }
    }

    private static func dispatchSystemNotificationOpen(_ payload: [String: String], source: String) {
        pendingSystemNotificationOpenKeys.remove(systemNotificationOpenKey(payload))
        NotificationCenter.default.post(
            name: systemNotificationOpenName,
            object: nil,
            userInfo: payload
        )
        RaftIosDiagnosticsRecord(
            "info",
            "SlockIosPush",
            "system_notification_open_dispatched source=\(source)"
        )
    }

    private static func signedPushEnvironment() -> RaftIosPushEnvironment? {
        // `SlockAPNSEnvironment` and the app's `aps-environment` entitlement are
        // expanded from the same APS_ENVIRONMENT build setting. This keeps the
        // runtime decision tied to signing configuration instead of DEBUG/Release
        // conditionals; distribution/TestFlight/App Store all use `production`.
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "SlockAPNSEnvironment") as? String else {
            return nil
        }
        switch raw.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines).lowercased() {
        case "development":
            return .sandbox
        case "production":
            return .production
        default:
            return nil
        }
    }
}

private extension String {
    func takeIfNotEmpty() -> String? {
        isEmpty ? nil : self
    }
}

// Internal (not private) so RaftIosSceneDelegate can forward shortcut items.
enum RaftIosDesktopShortcutHost {
    private static let desktopShortcutOpenName = Notification.Name("slockDesktopShortcutOpen")
    private static let desktopShortcutOpenReadyName = Notification.Name("slockDesktopShortcutOpenReady")
    private static let desktopShortcutOpenNotReadyName = Notification.Name("slockDesktopShortcutOpenNotReady")
    private static var desktopShortcutOpenReadyObserver: NSObjectProtocol?
    private static var desktopShortcutOpenNotReadyObserver: NSObjectProtocol?
    private static var desktopShortcutOpenReadyCount = 0
    private static var pendingShortcutOpens: [[String: String]] = []

    static func install() {
        if desktopShortcutOpenReadyObserver == nil {
            desktopShortcutOpenReadyObserver = NotificationCenter.default.addObserver(
                forName: desktopShortcutOpenReadyName,
                object: nil,
                queue: .main
            ) { _ in
                desktopShortcutOpenReadyCount += 1
                drainPendingShortcutOpens()
            }
        }
        if desktopShortcutOpenNotReadyObserver == nil {
            desktopShortcutOpenNotReadyObserver = NotificationCenter.default.addObserver(
                forName: desktopShortcutOpenNotReadyName,
                object: nil,
                queue: .main
            ) { _ in
                desktopShortcutOpenReadyCount = max(0, desktopShortcutOpenReadyCount - 1)
            }
        }
    }

    static func acceptShortcutItem(
        _ shortcutItem: UIApplicationShortcutItem,
        shellInstanceId: String? = nil,
        source: String
    ) {
        let userInfo = shortcutItem.userInfo ?? [:]
        guard let serverId = userInfo["slockShortcutServerId"] as? String, !serverId.isEmpty,
              let targetId = userInfo["slockShortcutTargetId"] as? String, !targetId.isEmpty else {
            return
        }
        let targetKind = userInfo["slockShortcutTargetKind"] as? String ?? "channel"
        let launchId = "\(serverId)_\(targetId)_\(Int64(Date().timeIntervalSince1970 * 1000))"
        var payload: [String: String] = [
            "slockShortcutServerId": serverId,
            "slockShortcutTargetId": targetId,
            "slockShortcutTargetKind": targetKind,
            "slockShortcutLaunchId": launchId,
            "slockShortcutTitle": shortcutItem.localizedTitle
        ]
        // task #483: a scene-scoped shortcut targets only that shell.
        if let targetShellId = shellInstanceId?
            .trimmingCharacters(in: .whitespacesAndNewlines).takeIfNotEmpty() {
            payload["slockShellInstanceId"] = targetShellId
        }

        if Thread.isMainThread {
            handlePayload(payload, source: source)
        } else {
            DispatchQueue.main.async {
                handlePayload(payload, source: source)
            }
        }
    }

    private static func handlePayload(_ payload: [String: String], source: String) {
        if desktopShortcutOpenReadyCount > 0 {
            dispatchShortcutOpen(payload, source: source)
        } else {
            pendingShortcutOpens.append(payload)
        }
    }

    private static func drainPendingShortcutOpens() {
        let pending = pendingShortcutOpens
        pendingShortcutOpens.removeAll(keepingCapacity: true)
        for payload in pending {
            dispatchShortcutOpen(payload, source: "ready_drain")
        }
    }

    private static func dispatchShortcutOpen(_ payload: [String: String], source: String) {
        NotificationCenter.default.post(
            name: desktopShortcutOpenName,
            object: nil,
            userInfo: payload
        )
        RaftIosDiagnosticsRecord(
            "info",
            "SlockIosShortcut",
            "desktop_shortcut_open_dispatched source=\(source)"
        )
    }
}

final class RaftIosAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private let networkRequestLifetimeAdapter = RaftIosNetworkRequestLifetimeAdapter(
        notificationCenter: .default,
        applicationState: { UIApplication.shared.applicationState },
        visibilitySink: { foreground in
            _ = RaftNetworkRequestLifetimeKt.updateRaftProcessNetworkVisibility(foreground: foreground)
        }
    )

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        networkRequestLifetimeAdapter.install()
        // `xcrun simctl launch booted <bundle> -RaftHideEnvironmentIndicator YES` for store screenshots.
        RaftEnvironmentIndicatorKt.setRaftEnvironmentIndicatorHiddenForCapture(
            hidden: UserDefaults.standard.bool(forKey: "RaftHideEnvironmentIndicator")
        )
        RaftIosNetworkPathMonitor.shared.start()
        UNUserNotificationCenter.current().delegate = self
        RaftIosPushHost.registerNotificationCategories()
        // osPermission rides the push registration; a change in Settings re-registers on return.
        RaftIosPushHost.refreshOsPermission()
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            RaftIosPushHost.refreshOsPermission()
            // Review N1: close delivered alerts that an applied retract covers (a late alert posted after it).
            RaftIosRetractWatermarks.removeCoveredDelivered { removed in
                if removed > 0 {
                    RaftIosDiagnosticsRecord("info", "SlockIosPush", "retract_watermark_swept removed=\(removed)")
                }
            }
        }
        RaftIosDesktopShortcutHost.install()
        if let shortcutItem = launchOptions?[.shortcutItem] as? UIApplicationShortcutItem {
            RaftIosDesktopShortcutHost.acceptShortcutItem(shortcutItem, source: "cold_launch")
        }
        if let userInfo = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            RaftIosPushHost.acceptSystemNotificationOpen(
                userInfo: userInfo,
                source: "cold_launch"
            )
        }
        if let url = launchOptions?[.url] as? URL {
            DispatchQueue.main.async {
                RaftIosOpenURL.accept(url, source: "cold_launch")
            }
        }
        return true
    }

    // task #471: the scene manifest declares RaftIosSceneDelegate; URL opens
    // and shortcut actions are delivered to the scene, so the app-level
    // open:/performActionFor callbacks are intentionally not implemented.

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }

    func application(_ application: UIApplication, didDiscardSceneSessions sceneSessions: Set<UISceneSession>) {
        // Sessions discarded while the app was not running never hit
        // sceneDidDisconnect, so release their shell scopes here instead.
        for session in sceneSessions {
            let context = RaftIosShellWindowRegistry.unregister(session: session)
            guard let shellInstanceId = context?.shellInstanceId,
                  shellInstanceId != RaftIosShellWindowContract.legacyShellInstanceId else {
                continue
            }
            RaftShellStoreLaunchKt.releaseRaftShellStoreScope(
                shellInstanceId: shellInstanceId,
                serverId: context?.serverId
            )
            RaftIosDiagnosticsRecord(
                "info",
                "RaftIosShellWindow",
                "discarded_session_released shell=\(shellInstanceId)"
            )
        }
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        RaftIosPushHost.didRegister(deviceToken: deviceToken)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        RaftIosPushHost.didFailToRegister(error: error)
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        RaftIosPushHost.handleBackgroundPush(userInfo: userInfo, completion: completionHandler)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let userInfo = notification.request.content.userInfo
        // A Settings test (slock docs/contracts/notifications.md §6): confirm it is being shown.
        if let testId = userInfo["testId"] as? String, !testId.isEmpty {
            RaftNotificationTestAck.shared.ack(testId: testId, shown: true)
        }
        // Review N1: an alert that arrives after the retract covering it (out-of-order delivery) is dropped.
        if RaftIosRetractWatermarks.isCovered(
            userInfo: userInfo,
            postedAt: notification.date,
            watermarks: RaftIosRetractWatermarks.load(),
            now: Date()
        ) {
            RaftIosDiagnosticsRecord("info", "SlockIosPush", "system_notification_suppressed reason=retracted")
            completionHandler([])
            return
        }
        if RaftIosPushHost.targetsVisibleConversation(userInfo: userInfo) {
            // The user is already looking at this conversation: no banner or sound, same as the in-app banner.
            RaftIosDiagnosticsRecord("info", "SlockIosPush", "system_notification_suppressed reason=visible_conversation")
            completionHandler([.badge])
            return
        }
        // The in-app banner renders message notifications only: a reminder, account notice or test
        // (no message) keeps its system banner in the foreground.
        let raftCategory = (notification.request.content.userInfo["raftCategory"] as? String) ?? ""
        let messageLess = ["reminder", "system_notice", "test"].contains(raftCategory)
        if UIApplication.shared.applicationState == .active && !messageLess {
            // Review S7: in the foreground only the shared in-app banner shows (it gets the same
            // notification over the socket); the system copy goes to Notification Center silently.
            RaftIosDiagnosticsRecord("info", "SlockIosPush", "system_notification_will_present mode=list_only reason=foreground")
            completionHandler([.list, .badge])
            return
        }
        RaftIosDiagnosticsRecord("info", "SlockIosPush", "system_notification_will_present")
        completionHandler([.banner, .list, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        // task #483: a notification whose serverId maps to exactly one live
        // shell targets only that shell; anything else broadcasts as before.
        // Message-less notices (contract §4.1) may name the server only in `route`; the open payload itself
        // (conversation or Activity target) is built by RaftIosNotificationOpen.
        let serverId = RaftIosNotificationRetraction.string(content.userInfo["serverId"])
            ?? RaftIosNotificationRetraction.string((content.userInfo["route"] as? [AnyHashable: Any])?["serverId"])
        var shellInstanceId: String?
        if let serverId, !serverId.isEmpty {
            let matches = RaftIosShellWindowRegistry.contexts(forServerId: serverId)
            if matches.count == 1 {
                shellInstanceId = matches[0].shellInstanceId
            } else {
                RaftIosDiagnosticsRecord(
                    "info",
                    "SlockIosPush",
                    "system_notification_open_broadcast reason=no_unique_shell matches=\(matches.count)"
                )
            }
        }
        RaftIosPushHost.acceptSystemNotificationOpen(
            userInfo: content.userInfo,
            categoryIdentifier: content.categoryIdentifier,
            shellInstanceId: shellInstanceId,
            source: "notification_response"
        )
        completionHandler()
    }
}

private enum RaftIosSharedLogBridge {
    private static var observer: NSObjectProtocol?

    static func install() {
        guard observer == nil else {
            return
        }
        observer = NotificationCenter.default.addObserver(
            forName: Notification.Name("slockSharedLog"),
            object: nil,
            queue: nil
        ) { notification in
            let userInfo = notification.userInfo ?? [:]
            let level = userInfo["level"] as? String ?? "info"
            let tag = userInfo["tag"] as? String ?? "SlockShared"
            let message = userInfo["message"] as? String ?? ""
            let timestampMillis = (userInfo["timestampMillis"] as? NSNumber)?.int64Value ?? 0
            let forceCritical = (userInfo["critical"] as? NSNumber)?.boolValue ?? false
            RaftIosDiagnosticsRecordWithContext(level, tag, message, timestampMillis, true, forceCritical)
        }
    }
}

/// Records Kotlin unhandled exceptions (`RaftIosHostHooks` installs the runtime hook) through the
/// synchronous critical diagnostics path, so the record survives the termination that follows.
/// Runs on the throwing thread.
final class RaftIosUnhandledExceptionRecorder: NSObject, RaftIosUnhandledExceptionSink {
    func recordUnhandledException(reason: String, stack: String) {
        // TODO(cmp-switch): report the visible page name; the hook is process-wide and the
        // CMP runtime does not track the current page for it yet.
        RaftIosDiagnosticsRecordUnhandledException("unknown", 0, reason, stack)
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(RaftIosAppDelegate.self) private var appDelegate

    init() {
        // Capture failures in the startup work below, including the first
        // Kotlin call, storage setup and network transport initialization.
        RaftIosInstallHandsReporting()
        // CMP page routing: debug builds also admit the debug-only :ui-cmp pages.
        RaftPageControllerFactory.configure()
        // task #436: register the SVG coder with SDWebImage, which the durable avatar store
        // (RaftIosAvatarCacheProvider) decodes committed avatar bytes with; SVG avatars are
        // otherwise rejected (UIImage has no native SVG support).
        SDImageCodersManager.shared.addCoder(SDImageSVGCoder.shared)
        RaftIosAvatarCacheHost.shared.install()
        RaftIosAppScopeMMKV.install()
        // The font picker is gone (the design system fixes the faces); drop its stored choice
        // (same as RaftApplication on Android).
        _ = KeyValueStoreKt.raftClearLegacyFontPreference()
        RaftIosInstallThreadComposerTemporaryFileRetirement()
        RaftIosPushHost.install()
        RaftIosSharedLogBridge.install()
        // Every page renders through Compose Multiplatform: install the :shared page registry
        // (RaftPageRegistry) and the host hooks its image loader reads (managed avatar bytes),
        // before the first page.
        // Kotlin unhandled exceptions are recorded into diagnostics before termination.
        RaftIosHostHooks.shared.install(
            managedImages: RaftIosAvatarCacheHost.shared,
            unhandledExceptions: RaftIosUnhandledExceptionRecorder()
        )
        RaftIosDiagnosticsRecord("info", "SlockIosDiagnostics", "ios_diagnostics_initialized backend=jsonl")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
