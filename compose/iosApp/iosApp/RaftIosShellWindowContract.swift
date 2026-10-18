import Combine
import Foundation
import shared
import UIKit

// task #471/#472: iOS shell-window identity + scene plumbing.
//
// Mirrors AndroidShellWindowContract.kt: a "document shell" is keyed by the
// authenticated route-owner triple (environmentId, accountId, serverId) and its
// shell instance id is the canonical URI `slock://shell/<enc>/<enc>/<enc>`,
// stable across process death. The single-window app path keeps the legacy
// shell id "legacy-app-shell" — identical to SlockShellStoreScope.Legacy in
// shared — so a scene with no explicit identity resolves to the same store
// scope as before (shared also maps a blank id to Legacy, so always-injecting
// it is byte-equivalent).
//
// task #483: notification opens, desktop shortcuts, OAuth callbacks and host
// lifecycle visibility are routed per-shell via `slockShellInstanceId`.
// Scene-attributed events (scene_connect/scene_open_url/performActionFor) carry
// the owning scene's shell id; app-level deliveries resolve the target shell by
// payload serverId when unique, else broadcast — a payload without
// `slockShellInstanceId` keeps the historical broadcast path. Host lifecycle
// visibility fans out across every registered shell id (legacy fallback).

/// Canonical document-shell identity: the authenticated route owner the shell
/// hosts. Byte-compatible port of `AndroidShellRouteOwner`.
internal struct RaftIosShellRouteOwner: Equatable {
    let environmentId: String
    let accountId: String
    let serverId: String

    /// env: trim + trimEnd('/'), acct/server: trim; any blank -> nil.
    func normalizedOrNull() -> RaftIosShellRouteOwner? {
        var environment = environmentId.trimmingCharacters(in: .whitespaces)
        while environment.hasSuffix("/") {
            environment.removeLast()
        }
        let account = accountId.trimmingCharacters(in: .whitespaces)
        let server = serverId.trimmingCharacters(in: .whitespaces)
        guard !environment.isEmpty, !account.isEmpty, !server.isEmpty else {
            return nil
        }
        return RaftIosShellRouteOwner(
            environmentId: environment,
            accountId: account,
            serverId: server
        )
    }

    /// `slock://shell/<enc env>/<enc acct>/<enc server>` — stable across process death.
    var shellCanonicalUri: String {
        "slock://shell/\(RaftIosShellWindowContract.shellUriSegment(environmentId))" +
            "/\(RaftIosShellWindowContract.shellUriSegment(accountId))" +
            "/\(RaftIosShellWindowContract.shellUriSegment(serverId))"
    }
}

/// Per-scene shell identity resolved by `RaftIosSceneDelegate` and consumed by
/// `ContentView` when building pageData. Published so a `scene(_:continue:)`
/// update can retarget an existing window.
internal final class RaftIosShellContext: ObservableObject {
    @Published var shellInstanceId: String
    @Published var serverId: String?
    @Published var routeOwner: RaftIosShellRouteOwner?

    init(
        shellInstanceId: String = RaftIosShellWindowContract.legacyShellInstanceId,
        serverId: String? = nil,
        routeOwner: RaftIosShellRouteOwner? = nil
    ) {
        self.shellInstanceId = shellInstanceId
        self.serverId = serverId
        self.routeOwner = routeOwner
    }

    func apply(_ other: RaftIosShellContext) {
        shellInstanceId = other.shellInstanceId
        serverId = other.serverId
        routeOwner = other.routeOwner
    }
}

/// Process-local lifecycle index: UISceneSession.persistentIdentifier -> shell
/// context, plus a shellInstanceId -> session map for owner reuse. Unlike the
/// Android registry (taskId), the retained UISceneSession lets us ask UIKit to
/// foreground the existing window instead of opening a duplicate.
internal enum RaftIosShellWindowRegistry {
    private static let lock = NSLock()
    private static var contextsBySessionId: [String: RaftIosShellContext] = [:]
    private static var sessionsByShellInstanceId: [String: UISceneSession] = [:]

    static func register(session: UISceneSession, context: RaftIosShellContext) {
        lock.lock()
        defer { lock.unlock() }
        contextsBySessionId[session.persistentIdentifier] = context
        sessionsByShellInstanceId[context.shellInstanceId] = session
    }

    @discardableResult
    static func unregister(session: UISceneSession) -> RaftIosShellContext? {
        lock.lock()
        defer { lock.unlock() }
        let context = contextsBySessionId.removeValue(forKey: session.persistentIdentifier)
        if let shellInstanceId = context?.shellInstanceId,
           sessionsByShellInstanceId[shellInstanceId]?.persistentIdentifier == session.persistentIdentifier {
            sessionsByShellInstanceId.removeValue(forKey: shellInstanceId)
        }
        return context
    }

    static func context(forSessionId sessionId: String) -> RaftIosShellContext? {
        lock.lock()
        defer { lock.unlock() }
        return contextsBySessionId[sessionId]
    }

    static func session(forShellInstanceId shellInstanceId: String) -> UISceneSession? {
        lock.lock()
        defer { lock.unlock() }
        return sessionsByShellInstanceId[shellInstanceId]
    }

    /// Distinct shell ids of all registered contexts, insertion order not
    /// guaranteed. Empty before the first scene connects — callers fall back
    /// to the legacy shell.
    static func allShellInstanceIds() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        var seen = Set<String>()
        return contextsBySessionId.values.compactMap { context in
            seen.insert(context.shellInstanceId).inserted ? context.shellInstanceId : nil
        }
    }

    /// task #483: contexts whose selected server matches `serverId`, used to
    /// resolve the target shell of an app-level notification open. Deduped by
    /// shellInstanceId; empty when no window shows that server.
    static func contexts(forServerId serverId: String) -> [RaftIosShellContext] {
        let normalized = serverId.trimmingCharacters(in: .whitespaces)
        guard !normalized.isEmpty else { return [] }
        lock.lock()
        defer { lock.unlock() }
        var seen = Set<String>()
        return contextsBySessionId.values.compactMap { context in
            guard context.serverId == normalized,
                  seen.insert(context.shellInstanceId).inserted else { return nil }
            return context
        }
    }

    static func session(forOwner owner: RaftIosShellRouteOwner) -> UISceneSession? {
        lock.lock()
        defer { lock.unlock() }
        for (sessionId, context) in contextsBySessionId where context.routeOwner == owner {
            if let session = sessionsByShellInstanceId[context.shellInstanceId],
               session.persistentIdentifier == sessionId {
                return session
            }
        }
        return nil
    }
}

/// Entry points shared by the scene delegate (inbound userActivity) and host
/// code that wants to open/retarget a shell window (outbound activation).
internal enum RaftIosShellWindowContract {
    /// Matches the shared-side `SlockShellStoreScope.Legacy` id exactly.
    static let legacyShellInstanceId = "legacy-app-shell"
    /// NSUserActivity type carrying document-shell identity between scenes.
    static let shellUserActivityType = "build.raft.app.shell.window"

    private static let documentShellKey = "slockDocumentShell"
    private static let shellInstanceIdKey = "slockShellInstanceId"
    private static let shellServerIdKey = "slockShellServerId"
    private static let routeEnvironmentIdKey = "slockRouteEnvironmentId"
    private static let routeAccountIdKey = "slockRouteAccountId"
    private static let routeServerIdKey = "slockRouteServerId"

    /// URLEncoder.encode(v, "UTF-8").replace("+", "%20") byte-faithful port:
    /// unreserved set is [A-Za-z0-9.-*_], everything else is %XX (uppercase) of
    /// the UTF-8 bytes; space encodes as %20, never '+'.
    static func shellUriSegment(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: ".-*_")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    /// Inverse of `shellCanonicalUri`; returns nil for anything that is not a
    /// well-formed `slock://shell/<enc>/<enc>/<enc>` URI.
    static func parseShellUri(_ uri: String) -> RaftIosShellRouteOwner? {
        let prefix = "slock://shell/"
        guard uri.hasPrefix(prefix) else { return nil }
        let segments = uri.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false)
        guard segments.count == 3 else { return nil }
        func decoded(_ segment: Substring) -> String? {
            String(segment).removingPercentEncoding
        }
        guard let environment = decoded(segments[0]),
              let account = decoded(segments[1]),
              let server = decoded(segments[2]) else {
            return nil
        }
        return RaftIosShellRouteOwner(
            environmentId: environment,
            accountId: account,
            serverId: server
        ).normalizedOrNull()
    }

    static func shellUserActivity(owner: RaftIosShellRouteOwner) -> NSUserActivity {
        let activity = NSUserActivity(activityType: shellUserActivityType)
        activity.targetContentIdentifier = owner.shellCanonicalUri
        activity.userInfo = [
            documentShellKey: true,
            shellInstanceIdKey: owner.shellCanonicalUri,
            shellServerIdKey: owner.serverId,
            routeEnvironmentIdKey: owner.environmentId,
            routeAccountIdKey: owner.accountId,
            routeServerIdKey: owner.serverId,
        ]
        return activity
    }

    /// Rebuilds a shell context from an inbound userActivity (scene connect or
    /// `scene(_:continue:)`). Returns nil when no valid route owner is present —
    /// caller keeps the current/legacy context instead of minting a half
    /// identity. Owner resolution order: explicit slockRoute* keys, then the
    /// targetContentIdentifier URI.
    static func context(from userActivity: NSUserActivity) -> RaftIosShellContext? {
        guard userActivity.activityType == shellUserActivityType else { return nil }
        let info = userActivity.userInfo ?? [:]
        func string(_ key: String) -> String { (info[key] as? String) ?? "" }
        var owner = RaftIosShellRouteOwner(
            environmentId: string(routeEnvironmentIdKey),
            accountId: string(routeAccountIdKey),
            serverId: string(routeServerIdKey)
        ).normalizedOrNull()
        if owner == nil, let identifier = userActivity.targetContentIdentifier {
            owner = parseShellUri(identifier)
        }
        guard let routeOwner = owner else {
            SlockIosDiagnosticsRecord(
                "warn",
                "RaftIosShellWindow",
                "shell_user_activity_rejected reason=missing_route_owner"
            )
            return nil
        }
        let requestedId = string(shellInstanceIdKey)
        let context = RaftIosShellContext(
            shellInstanceId: requestedId.isEmpty ? routeOwner.shellCanonicalUri : requestedId,
            serverId: routeOwner.serverId,
            routeOwner: routeOwner
        )
        return context
    }

    /// Foregrounds the window already hosting `owner`, or asks UIKit to create
    /// a new scene carrying the shell identity. Task C calls this from the
    /// server-switch seam; keeping it here preserves the single entry point.
    static func activateShell(for owner: RaftIosShellRouteOwner, source: String) {
        guard let normalized = owner.normalizedOrNull() else {
            SlockIosDiagnosticsRecord(
                "warn",
                "RaftIosShellWindow",
                "activate_shell_rejected reason=invalid_owner source=\(source)"
            )
            return
        }
        if let existing = RaftIosShellWindowRegistry.session(forOwner: normalized) {
            UIApplication.shared.requestSceneSessionActivation(
                existing,
                userActivity: nil,
                options: nil
            ) { error in
                SlockIosDiagnosticsRecord(
                    "warn",
                    "RaftIosShellWindow",
                    "scene_activation_failed errorType=\(String(describing: type(of: error))) source=\(source)"
                )
            }
            SlockIosDiagnosticsRecord(
                "info",
                "RaftIosShellWindow",
                "scene_reactivated reason=owner_reuse source=\(source)"
            )
            return
        }
        UIApplication.shared.requestSceneSessionActivation(
            nil,
            userActivity: shellUserActivity(owner: normalized),
            options: nil
        ) { error in
            SlockIosDiagnosticsRecord(
                "warn",
                "RaftIosShellWindow",
                "scene_request_failed errorType=\(String(describing: type(of: error))) source=\(source)"
            )
        }
        SlockIosDiagnosticsRecord(
            "info",
            "RaftIosShellWindow",
            "scene_requested source=\(source)"
        )
    }
}

/// ObjC-facing entry point for `HRBridgeModule`: the shared server-switch flow
/// ships the document-shell flag + route owner triple inside `pageData`
/// (`slockDocumentShell`, `slockRouteEnvironmentId`, `slockRouteAccountId`,
/// `slockRouteServerId`). When the flag is set, the switch targets a shell
/// window keyed by that owner instead of pushing a page in the current scene —
/// the same contract Android implements via document-task identity.
@objcMembers
final class RaftIosShellWindowBridge: NSObject {
    /// Returns true when the owner is valid and a scene activation request was
    /// dispatched (caller must not open the page in the current scene); false
    /// when the owner is missing/invalid and the caller should fall back to
    /// opening the page locally.
    static func activateShell(
        environmentId: String?,
        accountId: String?,
        serverId: String?,
        source: String
    ) -> Bool {
        guard let owner = RaftIosShellRouteOwner(
            environmentId: environmentId ?? "",
            accountId: accountId ?? "",
            serverId: serverId ?? ""
        ).normalizedOrNull() else {
            SlockIosDiagnosticsRecord(
                "warn",
                "RaftIosShellWindow",
                "document_shell_rejected reason=invalid_owner source=\(source)"
            )
            return false
        }
        RaftIosShellWindowContract.activateShell(for: owner, source: source)
        return true
    }
}
