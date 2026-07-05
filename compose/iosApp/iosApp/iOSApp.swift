import SwiftUI

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
            SlockIosDiagnosticsRecord(level, tag, message)
        }
    }
}

@main
struct iOSApp: App {
    init() {
        SlockQuiverCrashReporter.install()
        SlockIosAppScopeMMKV.install()
        SlockQuiverCrashReporter.uploadPending(afterDelay: 3.0)
        SlockIosSharedLogBridge.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
