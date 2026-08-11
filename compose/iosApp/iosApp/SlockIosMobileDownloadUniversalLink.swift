import Foundation

enum SlockIosMobileDownloadUniversalLink {
    private static let hosts: Set<String> = [
        "app.raft.build",
        "raft-app-staging.botiverse.dev",
    ]

    static func accepts(_ url: URL?) -> Bool {
        guard let components = url.flatMap({ URLComponents(url: $0, resolvingAgainstBaseURL: false) }) else {
            return false
        }
        return components.scheme?.lowercased() == "https"
            && hosts.contains(components.host?.lowercased() ?? "")
            && components.path == "/download"
    }
}
