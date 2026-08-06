import Foundation

enum SlockIosExternalShareLaunch {
    private static let allowedSchemes = Set(["raft", "raft-dev"])

    static func accept(_ url: URL, source: String) -> Bool {
        guard
            let scheme = url.scheme?.lowercased(), allowedSchemes.contains(scheme),
            url.host?.lowercased() == "external-share",
            url.path.isEmpty || url.path == "/",
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.queryItems?.count == 1,
            components.queryItems?.first?.name == "token",
            let token = components.queryItems?.first?.value,
            SlockExternalShareEnvelopeStorage.isSafeToken(token)
        else { return false }
        SlockIosOAuthRouteStore.shared.openExternalShare(token: token)
        SlockIosDiagnosticsRecord("info", "SlockIosExternalShare", "external_share_launch_admitted source=\(source)")
        return true
    }

    static func resumePendingIfNeeded() {
        guard let token = SlockExternalShareEnvelopeStorage.shared.pendingToken() else { return }
        SlockIosOAuthRouteStore.shared.openExternalShare(token: token)
    }
}
