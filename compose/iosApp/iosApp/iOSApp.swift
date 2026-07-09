import SwiftUI
import shared
import SDWebImage
import SDWebImageSVGCoder

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
        SlockIosSharedLogBridge.install()
        SlockIosInstallQuiverReporting()
        SlockIosDiagnosticsRecord("info", "SlockIosDiagnostics", "ios_diagnostics_initialized backend=jsonl")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
