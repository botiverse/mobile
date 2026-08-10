import Foundation

enum SlockIosExternalShareLaunch {
    static func accept(_ url: URL, source: String) -> Bool {
        let configuredScheme = Bundle.main.object(forInfoDictionaryKey: "SlockOAuthCallbackScheme") as? String
        guard let token = SlockExternalShareContract.hostLaunchToken(
            from: url,
            configuredScheme: configuredScheme
        ) else { return false }
        SlockIosOAuthRouteStore.shared.openExternalShare(token: token)
        SlockIosDiagnosticsRecord("info", "SlockIosExternalShare", "external_share_launch_admitted source=\(source)")
        return true
    }

    static func resumePendingIfNeeded() {
        guard let token = SlockExternalShareEnvelopeStorage.shared.pendingToken() else { return }
        SlockIosOAuthRouteStore.shared.openExternalShare(token: token)
    }
}
