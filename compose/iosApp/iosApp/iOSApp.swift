import SwiftUI
import shared
import SDWebImage
import SDWebImageSVGCoder
import UIKit
import UserNotifications

private enum SlockIosPushEnvironment: String {
    case sandbox
    case production
}

private enum SlockIosPushHost {
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
                    SlockIosDiagnosticsRecord(
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
        installationId = SlockIosPushRegistrationBridge.shared.install(candidateInstallationId: candidate)
        SlockIosPushRegistrationBridge.shared.retryPendingRegistration()
        SlockIosDiagnosticsRecord(
            "info",
            "SlockIosPush",
            "push_registration_host_installed installationIdPresent=\(installationId?.isEmpty == false)"
        )
    }

    private static func setRemoteNotificationsEnabled(_ enabled: Bool) {
        runOnMain {
            desiredRemoteNotificationsEnabled = enabled
            remoteNotificationsGeneration &+= 1
            let generation = remoteNotificationsGeneration
            if !enabled {
                UIApplication.shared.unregisterForRemoteNotifications()
                SlockIosDiagnosticsRecord(
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
            SlockIosDiagnosticsRecord(
                "info",
                "SlockIosPush",
                "notification_permission_status status=\(authorizationStatusName(status)) trigger=authenticated_session"
            )
            guard status == .notDetermined else {
                registerForRemoteNotificationsIfCurrent(generation: generation)
                return
            }
            notificationCenter.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
                if let error {
                    SlockIosDiagnosticsRecord(
                        "warn",
                        "SlockIosPush",
                        "notification_permission_request_failed errorType=\(String(describing: type(of: error)))"
                    )
                } else {
                    SlockIosDiagnosticsRecord(
                        "info",
                        "SlockIosPush",
                        "notification_permission_request_finished granted=\(granted)"
                    )
                }
                registerForRemoteNotificationsIfCurrent(generation: generation)
            }
        }
    }

    private static func registerForRemoteNotificationsIfCurrent(generation: UInt64) {
        runOnMain {
            guard desiredRemoteNotificationsEnabled, remoteNotificationsGeneration == generation else {
                SlockIosDiagnosticsRecord(
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
            SlockIosDiagnosticsRecord(
                "info",
                "SlockIosPush",
                "remote_notifications_state_applied enabled=true"
            )
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
            SlockIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_installation_id")
            return
        }
        guard let environment = signedPushEnvironment() else {
            SlockIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_or_invalid_aps_environment")
            return
        }
        guard let topic = Bundle.main.bundleIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines), !topic.isEmpty else {
            SlockIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_topic")
            return
        }
        let appVersion =
            (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !appVersion.isEmpty else {
            SlockIosDiagnosticsRecord("warn", "SlockIosPush", "push_token_rejected reason=missing_app_version")
            return
        }
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        SlockIosPushRegistrationBridge.shared.updateRegistration(
            deviceToken: token,
            topic: topic,
            env: environment.rawValue,
            appVersion: appVersion
        )
        SlockIosDiagnosticsRecord(
            "info",
            "SlockIosPush",
            "push_token_received env=\(environment.rawValue) topicPresent=true tokenBytes=\(deviceToken.count)"
        )
    }

    static func didFailToRegister(error: Error) {
        SlockIosDiagnosticsRecord(
            "warn",
            "SlockIosPush",
            "push_token_registration_failed errorType=\(String(describing: type(of: error)))"
        )
    }

    static func acceptSystemNotificationOpen(
        userInfo: [AnyHashable: Any],
        categoryIdentifier: String? = nil,
        source: String
    ) {
        runOnMain {
            guard let payload = sanitizedSystemNotificationOpenPayload(
                userInfo: userInfo,
                categoryIdentifier: categoryIdentifier
            ) else {
                SlockIosDiagnosticsRecord(
                    "warn",
                    "SlockIosPush",
                    "system_notification_open_rejected reason=invalid_identity source=\(source)"
                )
                return
            }
            let key = systemNotificationOpenKey(payload)
            guard !pendingSystemNotificationOpenKeys.contains(key) else {
                SlockIosDiagnosticsRecord(
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
            SlockIosDiagnosticsRecord(
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
        return SlockVisibleConversationRegistryKt.slockSystemNotificationTargetsVisibleConversation(
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

    private static func sanitizedSystemNotificationOpenPayload(
        userInfo: [AnyHashable: Any],
        categoryIdentifier: String?
    ) -> [String: String]? {
        func stringValue(_ key: String) -> String? {
            guard let value = userInfo[key] as? String else {
                return nil
            }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }

        guard
            let serverId = stringValue("serverId"),
            let channelId = stringValue("channelId"),
            let messageId = stringValue("messageId"),
            let kind = stringValue("kind"),
            ["channel", "dm", "thread"].contains(kind)
        else {
            return nil
        }
        let threadId = stringValue("threadId") ?? channelId
        var payload = [
            "slockNotificationServerId": serverId,
            "slockNotificationThreadId": threadId,
            "slockNotificationChannelId": channelId,
            "slockNotificationMessageId": messageId,
            "slockNotificationKind": kind,
        ]
        if let parentChannelId = stringValue("parentChannelId") {
            payload["slockNotificationParentChannelId"] = parentChannelId
        }
        if let parentMessageId = stringValue("parentMessageId") {
            payload["slockNotificationParentMessageId"] = parentMessageId
        }
        let category =
            categoryIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines).takeIfNotEmpty()
                ?? ((userInfo["aps"] as? [AnyHashable: Any])?["category"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines).takeIfNotEmpty()
        if let category {
            payload["slockNotificationCategory"] = category
        }
        return payload
    }

    private static func systemNotificationOpenKey(_ payload: [String: String]) -> String {
        [
            payload["slockNotificationServerId"] ?? "",
            payload["slockNotificationThreadId"] ?? "",
            payload["slockNotificationMessageId"] ?? "",
        ].joined(separator: "|")
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
        SlockIosDiagnosticsRecord(
            "info",
            "SlockIosPush",
            "system_notification_open_dispatched source=\(source)"
        )
    }

    private static func signedPushEnvironment() -> SlockIosPushEnvironment? {
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

private enum SlockIosDesktopShortcutHost {
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

    static func acceptShortcutItem(_ shortcutItem: UIApplicationShortcutItem, source: String) {
        let userInfo = shortcutItem.userInfo ?? [:]
        guard let serverId = userInfo["slockShortcutServerId"] as? String, !serverId.isEmpty,
              let targetId = userInfo["slockShortcutTargetId"] as? String, !targetId.isEmpty else {
            return
        }
        let targetKind = userInfo["slockShortcutTargetKind"] as? String ?? "channel"
        let launchId = "\(serverId)_\(targetId)_\(Int64(Date().timeIntervalSince1970 * 1000))"
        let payload: [String: String] = [
            "slockShortcutServerId": serverId,
            "slockShortcutTargetId": targetId,
            "slockShortcutTargetKind": targetKind,
            "slockShortcutLaunchId": launchId,
            "slockShortcutTitle": shortcutItem.localizedTitle
        ]

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
        SlockIosDiagnosticsRecord(
            "info",
            "SlockIosShortcut",
            "desktop_shortcut_open_dispatched source=\(source)"
        )
    }
}

final class SlockIosAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private let networkRequestLifetimeAdapter = SlockIosNetworkRequestLifetimeAdapter(
        notificationCenter: .default,
        applicationState: { UIApplication.shared.applicationState },
        visibilitySink: { foreground in
            _ = SlockNetworkRequestLifetimeKt.updateSlockProcessNetworkVisibility(foreground: foreground)
        }
    )

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        networkRequestLifetimeAdapter.install()
        SlockIosNetworkPathMonitor.shared.start()
        UNUserNotificationCenter.current().delegate = self
        SlockIosDesktopShortcutHost.install()
        if let shortcutItem = launchOptions?[.shortcutItem] as? UIApplicationShortcutItem {
            SlockIosDesktopShortcutHost.acceptShortcutItem(shortcutItem, source: "cold_launch")
        }
        if let userInfo = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            SlockIosPushHost.acceptSystemNotificationOpen(
                userInfo: userInfo,
                source: "cold_launch"
            )
        }
        if let url = launchOptions?[.url] as? URL {
            DispatchQueue.main.async {
                _ = SlockIosMobileOAuthHost.shared.acceptCallbackURL(url, source: "cold_launch")
            }
        }
        return true
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        SlockIosMobileOAuthHost.shared.acceptCallbackURL(url, source: "app_delegate")
    }

    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        SlockIosDesktopShortcutHost.acceptShortcutItem(shortcutItem, source: "perform_action")
        completionHandler(true)
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        SlockIosPushHost.didRegister(deviceToken: deviceToken)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        SlockIosPushHost.didFailToRegister(error: error)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if SlockIosPushHost.targetsVisibleConversation(userInfo: notification.request.content.userInfo) {
            // The user is already looking at this conversation: no banner or sound, same as the in-app banner.
            SlockIosDiagnosticsRecord("info", "SlockIosPush", "system_notification_suppressed reason=visible_conversation")
            completionHandler([.badge])
            return
        }
        SlockIosDiagnosticsRecord("info", "SlockIosPush", "system_notification_will_present")
        completionHandler([.banner, .list, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        SlockIosPushHost.acceptSystemNotificationOpen(
            userInfo: content.userInfo,
            categoryIdentifier: content.categoryIdentifier,
            source: "notification_response"
        )
        completionHandler()
    }
}

private enum SlockIosSharedLogBridge {
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
            SlockIosDiagnosticsRecordWithContext(level, tag, message, timestampMillis, true, forceCritical)
        }
    }
}

private func slockCurlCaBundlePath() -> String {
    var bundles = [Bundle.main] + Bundle.allBundles + Bundle.allFrameworks
    let nested = Bundle.main.urls(forResourcesWithExtension: "bundle", subdirectory: nil) ?? []
    bundles.append(contentsOf: nested.compactMap { Bundle(url: $0) })
    for bundle in bundles {
        if let url = bundle.url(
            forResource: "cacert-2026-05-14",
            withExtension: "pem",
            subdirectory: "network"
        ) {
            return url.path
        }
        if let url = bundle.url(
            forResource: "cacert-2026-05-14",
            withExtension: "pem"
        ) {
            return url.path
        }
    }
    return ""
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(SlockIosAppDelegate.self) private var appDelegate

    init() {
        // Capture failures in the startup work below, including the first
        // Kotlin call, storage setup and network transport initialization.
        SlockIosInstallHandsReporting()
        // task #423: point the kmmresource loader at the "shared" pod bundle so
        // MR.* (reaction atlas + all MR.strings) resolves on iOS. Must run before
        // any page renders / touches MR.
        SlockIosResourceBundleKt.slockConfigureIosResourceBundle()
        // task #436: register the SVG coder so SDWebImage (used by the image
        // load path Kuikly delegates to) can decode SVG assets — e.g. the
        // Google/GitHub provider icons, which are shipped as SVG and otherwise
        // render blank on iOS (UIImage has no native SVG support).
        SDImageCodersManager.shared.addCoder(SDImageSVGCoder.shared)
        SlockIosAvatarCacheHost.shared.install()
        SlockIosAppScopeMMKV.install()
        SlockIosInstallThreadComposerTemporaryFileRetirement()
        let curlCaPath = slockCurlCaBundlePath()
        let curlBootstrap = SlockIosNetworkTransportBootstrapKt
            .installIosSlockNetworkTransportAtStartup(caBundlePath: curlCaPath)
        SlockIosDiagnosticsRecord("info", "SlockNetworkKmm", curlBootstrap)
        SlockIosPushHost.install()
        SlockIosSharedLogBridge.install()
        SlockIosDiagnosticsRecord("info", "SlockIosDiagnostics", "ios_diagnostics_initialized backend=jsonl")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
