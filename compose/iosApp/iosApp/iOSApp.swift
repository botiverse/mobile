import SwiftUI
import shared
import SDWebImage
import SDWebImageSVGCoder
import UIKit

private enum SlockIosPushEnvironment: String {
    case sandbox
    case production
}

private enum SlockIosPushHost {
    private static let remoteNotificationsStateName = Notification.Name("slockIosRemoteNotificationsState")
    private static var installationId: String?
    private static var remoteNotificationsObserver: NSObjectProtocol?

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
        let apply = {
            if enabled {
                UIApplication.shared.registerForRemoteNotifications()
            } else {
                UIApplication.shared.unregisterForRemoteNotifications()
            }
            SlockIosDiagnosticsRecord(
                "info",
                "SlockIosPush",
                "remote_notifications_state_applied enabled=\(enabled)"
            )
        }
        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.sync(execute: apply)
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

final class SlockIosAppDelegate: NSObject, UIApplicationDelegate {
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

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(SlockIosAppDelegate.self) private var appDelegate

    init() {
        // task #423: point the kmmresource loader at the "shared" pod bundle so
        // MR.* (reaction atlas + all MR.strings) resolves on iOS. Must run before
        // any page renders / touches MR.
        SlockIosResourceBundleKt.slockConfigureIosResourceBundle()
        // task #436: register the SVG coder so SDWebImage (used by the image
        // load path Kuikly delegates to) can decode SVG assets — e.g. the
        // Google/GitHub provider icons, which are shipped as SVG and otherwise
        // render blank on iOS (UIImage has no native SVG support).
        SDImageCodersManager.shared.addCoder(SDImageSVGCoder.shared)
        SlockIosAppScopeMMKV.install()
        SlockIosPushHost.install()
        SlockIosSharedLogBridge.install()
        SlockIosInstallHandsReporting()
        SlockIosDiagnosticsRecord("info", "SlockIosDiagnostics", "ios_diagnostics_initialized backend=jsonl")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
