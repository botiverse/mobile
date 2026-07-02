import SwiftUI

@main
struct iOSApp: App {
    init() {
        SlockIosAppScopeMMKV.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
