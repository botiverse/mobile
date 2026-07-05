import SwiftUI

@main
struct iOSApp: App {
    init() {
        SlockQuiverCrashReporter.install()
        SlockIosAppScopeMMKV.install()
        SlockQuiverCrashReporter.uploadPending(afterDelay: 3.0)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
