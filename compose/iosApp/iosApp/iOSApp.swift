import QuiverReport
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
        // App-owned Quiver reporting config (client key is DSN-class: it
        // identifies the app; rotate from the Quiver console if leaked).
        QuiverReport.start(
            with: QuiverReportConfig(
                baseUrl: "https://quiver.oranix.io",
                appSlug: "raft-ios",
                channel: "main",
                clientKey: "qk_2d6ac57a6532bc7ff1d3183b43bda99e2e732a99"
            )
        )
        SlockIosAppScopeMMKV.install()
        SlockIosSharedLogBridge.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
