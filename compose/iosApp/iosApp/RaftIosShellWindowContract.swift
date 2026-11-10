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
// shell id "legacy-app-shell" — identical to RaftShellStoreScope.Legacy in
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

/// Shell-window diagnostics. Plain `RaftIosDiagnosticsRecord` writes the diagnostics file only
/// (emitConsole NO), which is why the verification of 7724fe83 showed none of these lines; these
/// also reach the host console, so a device run shows where a scene request stops.
internal func RaftIosShellWindowLog(_ level: String, _ message: String) {
    // NSLog directly: the diagnostics recorder's own console echo is skipped when its admission
    // lock is contended, and these lines must never be missing.
    NSLog("[RaftIosShellWindow][%@] %@", level, message)
    RaftIosDiagnosticsRecord(level, "RaftIosShellWindow", message)
}

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
    /// #4484 launch intent of a window opened by a server switch (shared `slockInitialTab`): the
    /// tab the switch kept. Intent only, never product state; a restored scene has none.
    var launchInitialTab: String?
    /// #4484 launch intent (shared `slockSuppressRestoreLanding`): the shell skips its restored
    /// landing, as the Android document task opened by the same switch does.
    var launchSuppressRestoreLanding: Bool

    init(
        shellInstanceId: String = RaftIosShellWindowContract.legacyShellInstanceId,
        serverId: String? = nil,
        routeOwner: RaftIosShellRouteOwner? = nil,
        launchInitialTab: String? = nil,
        launchSuppressRestoreLanding: Bool = false
    ) {
        self.shellInstanceId = shellInstanceId
        self.serverId = serverId
        self.routeOwner = routeOwner
        self.launchInitialTab = launchInitialTab
        self.launchSuppressRestoreLanding = launchSuppressRestoreLanding
    }

    func apply(_ other: RaftIosShellContext) {
        // Intents first: the @Published identity changes below re-render the shell host, which
        // reads them.
        launchInitialTab = other.launchInitialTab
        launchSuppressRestoreLanding = other.launchSuppressRestoreLanding
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
    /// Matches the shared-side `RaftShellStoreScope.Legacy` id exactly.
    static let legacyShellInstanceId = "legacy-app-shell"
    /// NSUserActivity type carrying document-shell identity between scenes.
    static let shellUserActivityType = "build.raft.app.shell.window"

    private static let documentShellKey = "slockDocumentShell"
    private static let shellInstanceIdKey = "slockShellInstanceId"
    private static let shellServerIdKey = "slockShellServerId"
    private static let routeEnvironmentIdKey = "slockRouteEnvironmentId"
    private static let routeAccountIdKey = "slockRouteAccountId"
    private static let routeServerIdKey = "slockRouteServerId"
    static let initialTabKey = "slockInitialTab"
    static let suppressRestoreLandingKey = "slockSuppressRestoreLanding"
    /// Seconds a scene request may stay unanswered (no error, no scene) before it counts as
    /// refused and the source window switches in place.
    static let sceneRequestTimeoutSeconds: TimeInterval = 4
    /// Seconds after such a timeout during which a scene that still connects for the owner is a
    /// duplicate of the source window (which switched in place) and is closed again.
    static let lateSceneDestructionWindowSeconds: TimeInterval = 30

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

    /// The activity a new shell scene is requested with (and, without launch intents, the one a
    /// document scene is restored from: `stateRestorationActivity`). Identity + intent only.
    static func shellUserActivity(
        owner: RaftIosShellRouteOwner,
        initialTab: String? = nil,
        suppressRestoreLanding: Bool = false
    ) -> NSUserActivity {
        let activity = NSUserActivity(activityType: shellUserActivityType)
        // No targetContentIdentifier: with one, UIKit may hand the request to an existing scene
        // whose activation conditions accept it (every scene accepts any identifier by default)
        // through scene(_:continue:) instead of connecting a new scene. The identity is userInfo.
        // A window identity of this device: never offered to Handoff, Spotlight or Siri.
        activity.isEligibleForHandoff = false
        activity.isEligibleForSearch = false
        activity.isEligibleForPublicIndexing = false
        var info: [String: Any] = identityInfo(owner: owner)
        if let tab = initialTab?.trimmingCharacters(in: .whitespacesAndNewlines), !tab.isEmpty {
            info[initialTabKey] = tab
        }
        if suppressRestoreLanding {
            info[suppressRestoreLandingKey] = true
        }
        activity.userInfo = info
        return activity
    }

    private static func identityInfo(owner: RaftIosShellRouteOwner) -> [String: Any] {
        [
            documentShellKey: true,
            shellInstanceIdKey: owner.shellCanonicalUri,
            shellServerIdKey: owner.serverId,
            routeEnvironmentIdKey: owner.environmentId,
            routeAccountIdKey: owner.accountId,
            routeServerIdKey: owner.serverId,
        ]
    }

    /// Persisted with the scene session (`UISceneSession.userInfo`, kept by the system across
    /// launches) so a session the system reconnects or restores later still knows its server, and
    /// so a later switch to the same owner finds the session even while it has no scene. Nil for
    /// the legacy shell.
    static func sessionUserInfo(for context: RaftIosShellContext) -> [String: Any]? {
        guard let owner = context.routeOwner else { return nil }
        var info = identityInfo(owner: owner)
        info[shellInstanceIdKey] = context.shellInstanceId
        return info
    }

    /// The identity a session persisted (`sessionUserInfo(for:)`); nil when it has none. Never
    /// carries launch intents: a reconnected window opens like a relaunch.
    static func context(fromSessionUserInfo userInfo: [String: Any]?) -> RaftIosShellContext? {
        guard let userInfo, (userInfo[documentShellKey] as? Bool) == true else { return nil }
        let activity = NSUserActivity(activityType: shellUserActivityType)
        activity.userInfo = userInfo
        if let uri = userInfo[shellInstanceIdKey] as? String {
            activity.targetContentIdentifier = uri
        }
        return context(from: activity, includeLaunchIntent: false)
    }

    /// Rebuilds a shell context from an inbound userActivity (scene connect or
    /// `scene(_:continue:)`). Returns nil when no valid route owner is present —
    /// caller keeps the current/legacy context instead of minting a half
    /// identity. Owner resolution order: explicit slockRoute* keys, then the
    /// targetContentIdentifier URI.
    static func context(
        from userActivity: NSUserActivity,
        includeLaunchIntent: Bool = true
    ) -> RaftIosShellContext? {
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
            RaftIosShellWindowLog(
                "warn",
                "shell_user_activity_rejected reason=missing_route_owner"
            )
            return nil
        }
        let requestedId = string(shellInstanceIdKey)
        let tab = string(initialTabKey).trimmingCharacters(in: .whitespacesAndNewlines)
        let context = RaftIosShellContext(
            shellInstanceId: requestedId.isEmpty ? routeOwner.shellCanonicalUri : requestedId,
            serverId: routeOwner.serverId,
            routeOwner: routeOwner,
            launchInitialTab: includeLaunchIntent && !tab.isEmpty ? tab : nil,
            launchSuppressRestoreLanding: includeLaunchIntent && ((info[suppressRestoreLandingKey] as? Bool) ?? false)
        )
        return context
    }

    // MARK: Scene requests (#4484)

    /// Outcome strings shared `appShellShellWindowOutcome` reads; anything else is a refusal.
    enum RequestStatus: String {
        case opened
        case reused
        case refused
    }

    typealias RequestCompletion = (_ status: RequestStatus, _ reason: String) -> Void

    /// Main thread only. Requests waiting for their owner's scene, by shell instance id (the
    /// owner's canonical URI), each under its own token so a timeout completes only its request.
    private static var pendingRequests: [String: [UUID: RequestCompletion]] = [:]
    /// Main thread only. The shell context (identity + launch intent) each pending request asked
    /// for, by shell instance id, in request order: a new scene that connects without the request's
    /// activity (none in its connection options, no restoration identity) adopts the oldest.
    private static var pendingContexts: [(shellInstanceId: String, context: RaftIosShellContext, requestedAt: Date)] = []
    /// Main thread only. Owners whose request timed out (the source switched in place), until when
    /// a late scene for them is closed again.
    private static var abandonedRequests: [String: Date] = [:]

    /// The open session hosting `owner`: the registry's connected one, else a session the system
    /// keeps without a scene (backgrounded and disconnected, or restored at launch) found by the
    /// identity it persisted.
    static func existingSession(for owner: RaftIosShellRouteOwner) -> UISceneSession? {
        if let session = RaftIosShellWindowRegistry.session(forOwner: owner) {
            return session
        }
        let uri = owner.shellCanonicalUri
        return UIApplication.shared.openSessions.first { session in
            (session.userInfo?[shellInstanceIdKey] as? String) == uri
                && session.role == .windowApplication
        }
    }

    /// Foregrounds the window already hosting `owner`, or asks UIKit for a new scene carrying the
    /// shell identity (+ launch intent). `completion` (main thread, exactly once) says whether a
    /// window shows the owner: `.opened` once the new scene connects, `.reused` once the existing
    /// one is active, `.refused` when UIKit reports an error, the device cannot show another scene,
    /// or nothing happened within `sceneRequestTimeoutSeconds`. The caller (shared) switches the
    /// source window in place on `.refused`.
    static func activateShell(
        for owner: RaftIosShellRouteOwner,
        initialTab: String? = nil,
        suppressRestoreLanding: Bool = false,
        source: String,
        requestingScene: UIScene? = nil,
        completion: RequestCompletion? = nil
    ) {
        dispatchPrecondition(condition: .onQueue(.main))
        RaftIosShellWindowLog(
            "info",
            "activate_shell_entry source=\(source) server=\(owner.serverId) " +
                "supportsMultipleScenes=\(UIApplication.shared.supportsMultipleScenes) " +
                "connectedScenes=\(UIApplication.shared.connectedScenes.count)"
        )
        let finish: RequestCompletion = { status, reason in
            RaftIosShellWindowLog(
                status == .refused ? "warn" : "info",
                "scene_request_result status=\(status.rawValue) reason=\(reason) source=\(source)"
            )
            completion?(status, reason)
        }
        guard let normalized = owner.normalizedOrNull() else {
            RaftIosShellWindowLog(
                "warn",
                "activate_shell_rejected reason=invalid_owner source=\(source)"
            )
            finish(.refused, "invalid_owner")
            return
        }
        guard UIApplication.shared.supportsMultipleScenes else {
            finish(.refused, "multiple_scenes_unsupported")
            return
        }
        let shellInstanceId = normalized.shellCanonicalUri
        abandonedRequests.removeValue(forKey: shellInstanceId)
        let options = UIScene.ActivationRequestOptions()
        options.requestingScene = requestingScene
        if let existing = existingSession(for: normalized) {
            if existing.scene?.activationState == .foregroundActive {
                // Already on screen (side by side, the other display): focusing it cannot fail
                // in a way that leaves the user without the server.
                UIApplication.shared.requestSceneSessionActivation(
                    existing,
                    userActivity: nil,
                    options: options
                ) { error in
                    RaftIosShellWindowLog(
                        "warn",
                        "scene_activation_failed errorType=\(String(describing: type(of: error))) " +
                            "source=\(source) state=foreground_active"
                    )
                }
                finish(.reused, "already_active")
                return
            }
            let token = awaitScene(shellInstanceId: shellInstanceId, source: source, completion: finish)
            RaftIosShellWindowLog(
                "info",
                "scene_request_call kind=existing shell=\(shellInstanceId) source=\(source) " +
                    "sceneState=\(existing.scene.map { String(describing: $0.activationState.rawValue) } ?? "none")"
            )
            UIApplication.shared.requestSceneSessionActivation(
                existing,
                userActivity: nil,
                options: options
            ) { error in
                RaftIosShellWindowLog(
                    "warn",
                    "scene_request_error_handler kind=existing error=\(String(describing: error)) source=\(source)"
                )
                DispatchQueue.main.async {
                    RaftIosShellWindowContract.complete(shellInstanceId: shellInstanceId, token: token, status: .refused,
                             reason: "activation_error:\(String(describing: type(of: error)))")
                }
            }
            RaftIosShellWindowLog(
                "info",
                "scene_reactivation_requested reason=owner_reuse source=\(source)"
            )
            return
        }
        let token = awaitScene(shellInstanceId: shellInstanceId, source: source, completion: finish)
        pendingContexts.append((
            shellInstanceId: shellInstanceId,
            context: RaftIosShellContext(
                shellInstanceId: shellInstanceId,
                serverId: normalized.serverId,
                routeOwner: normalized,
                launchInitialTab: initialTab,
                launchSuppressRestoreLanding: suppressRestoreLanding
            ),
            requestedAt: Date()
        ))
        RaftIosShellWindowLog(
            "info",
            "scene_request_call kind=new shell=\(shellInstanceId) source=\(source) " +
                "tab=\(initialTab ?? "") requestingScene=\(requestingScene != nil)"
        )
        UIApplication.shared.requestSceneSessionActivation(
            nil,
            userActivity: shellUserActivity(
                owner: normalized,
                initialTab: initialTab,
                suppressRestoreLanding: suppressRestoreLanding
            ),
            options: options
        ) { error in
            RaftIosShellWindowLog(
                "warn",
                "scene_request_error_handler kind=new error=\(String(describing: error)) source=\(source)"
            )
            DispatchQueue.main.async {
                RaftIosShellWindowContract.complete(shellInstanceId: shellInstanceId, token: token, status: .refused,
                         reason: "request_error:\(String(describing: type(of: error)))")
            }
        }
        RaftIosShellWindowLog(
            "info",
            "scene_requested source=\(source)"
        )
    }

    private static func awaitScene(
        shellInstanceId: String,
        source: String,
        completion: @escaping RequestCompletion
    ) -> UUID {
        let token = UUID()
        pendingRequests[shellInstanceId, default: [:]][token] = completion
        DispatchQueue.main.asyncAfter(deadline: .now() + sceneRequestTimeoutSeconds) {
            guard RaftIosShellWindowContract.pendingRequests[shellInstanceId]?[token] != nil else { return }
            RaftIosShellWindowLog(
                "warn",
                "scene_request_timeout shell=\(shellInstanceId) source=\(source) " +
                    "connectedScenes=\(UIApplication.shared.connectedScenes.count) " +
                    "openSessions=\(UIApplication.shared.openSessions.count)"
            )
            RaftIosShellWindowContract.abandonedRequests[shellInstanceId] =
                Date().addingTimeInterval(RaftIosShellWindowContract.lateSceneDestructionWindowSeconds)
            RaftIosShellWindowContract.complete(shellInstanceId: shellInstanceId, token: token, status: .refused, reason: "timeout")
        }
        return token
    }

    /// Completes one waiting request (no-op when it already completed).
    private static func complete(shellInstanceId: String, token: UUID, status: RequestStatus, reason: String) {
        guard let completion = pendingRequests[shellInstanceId]?.removeValue(forKey: token) else { return }
        if pendingRequests[shellInstanceId]?.isEmpty == true {
            pendingRequests.removeValue(forKey: shellInstanceId)
            pendingContexts.removeAll { $0.shellInstanceId == shellInstanceId }
        }
        completion(status, reason)
    }

    /// True while a request for `shellInstanceId` waits for its scene.
    static func hasPendingRequest(shellInstanceId: String) -> Bool {
        pendingRequests[shellInstanceId]?.isEmpty == false
    }

    /// The identity a newly connecting scene without any identity of its own adopts: the oldest
    /// pending new-scene request (UIKit connects the scene it was asked for; the activity carrying
    /// the identity is the part that can go missing). Nil when nothing is pending.
    static func pendingContextForIdentitylessScene() -> RaftIosShellContext? {
        dispatchPrecondition(condition: .onQueue(.main))
        pendingContexts.removeAll { pendingRequests[$0.shellInstanceId]?.isEmpty != false }
        guard let oldest = pendingContexts.first else { return nil }
        pendingContexts.removeFirst()
        let context = oldest.context
        return RaftIosShellContext(
            shellInstanceId: context.shellInstanceId,
            serverId: context.serverId,
            routeOwner: context.routeOwner,
            launchInitialTab: context.launchInitialTab,
            launchSuppressRestoreLanding: context.launchSuppressRestoreLanding
        )
    }

    /// The scene delegate reports that the scene of `session` now hosts `shellInstanceId`:
    /// `connected` from `scene(_:willConnectTo:options:)`, else from `sceneDidBecomeActive`.
    /// Completes the requests waiting for that shell. A late scene for an owner whose request
    /// already timed out (the source window switched in place) is closed again.
    static func noteShellScene(session: UISceneSession, shellInstanceId: String, connected: Bool) {
        dispatchPrecondition(condition: .onQueue(.main))
        RaftIosShellWindowLog(
            "info",
            "scene_noted connected=\(connected) shell=\(shellInstanceId) " +
                "pending=\(pendingRequests[shellInstanceId]?.count ?? 0) pendingTotal=\(pendingRequests.count)"
        )
        guard shellInstanceId != legacyShellInstanceId else { return }
        if let waiting = pendingRequests.removeValue(forKey: shellInstanceId) {
            let status: RequestStatus = connected ? .opened : .reused
            for completion in waiting.values {
                completion(status, connected ? "scene_connected" : "scene_active")
            }
            return
        }
        guard connected, let until = abandonedRequests.removeValue(forKey: shellInstanceId) else { return }
        guard until > Date() else { return }
        RaftIosShellWindowLog(
            "warn",
            "scene_destroyed reason=late_after_timeout shell=\(shellInstanceId)"
        )
        UIApplication.shared.requestSceneSessionDestruction(session, options: nil) { error in
            RaftIosShellWindowLog(
                "warn",
                "scene_destruction_failed errorType=\(String(describing: type(of: error)))"
            )
        }
    }
}

/// ObjC-facing entry point for `HRBridgeModule`: the shared server-switch flow
/// ships the route owner triple (`slockRouteEnvironmentId`, `slockRouteAccountId`,
/// `slockRouteServerId`) plus launch intent (`slockInitialTab`,
/// `slockSuppressRestoreLanding`). The switch targets a shell window keyed by that
/// owner instead of pushing a page in the current scene — the same contract Android
/// implements via document-task identity.
@objcMembers
final class RaftIosShellWindowBridge: NSObject {
    /// Retired Kuikly bridge path (`HRBridgeModule.raft_openPageWithName`): fire and forget.
    /// Returns true when the owner is valid and a scene activation request was dispatched (caller
    /// must not open the page in the current scene); false when the owner is missing/invalid or
    /// the device shows one scene, and the caller should open the page locally.
    static func activateShell(
        environmentId: String?,
        accountId: String?,
        serverId: String?,
        source: String
    ) -> Bool {
        guard let owner = routeOwner(environmentId: environmentId, accountId: accountId, serverId: serverId, source: source),
              UIApplication.shared.supportsMultipleScenes else {
            return false
        }
        RaftIosShellWindowContract.activateShell(for: owner, source: source)
        return true
    }

    /// The Compose host's `requestShellWindow` bridge method (#4484). `completion` runs once on
    /// the main thread with `opened` / `reused` / `refused` and a diagnostic reason.
    @objc(requestShellWindowWithEnvironmentId:accountId:serverId:initialTab:suppressRestoreLanding:source:requestingScene:completion:)
    static func requestShellWindow(
        environmentId: String?,
        accountId: String?,
        serverId: String?,
        initialTab: String?,
        suppressRestoreLanding: Bool,
        source: String,
        requestingScene: UIWindowScene?,
        completion: @escaping (String, String) -> Void
    ) {
        RaftIosShellWindowLog(
            "info",
            "bridge_request_entry source=\(source) server=\(serverId ?? "") tab=\(initialTab ?? "") " +
                "main=\(Thread.isMainThread)"
        )
        let run: () -> Void = {
            guard let owner = RaftIosShellWindowBridge.routeOwner(
                environmentId: environmentId,
                accountId: accountId,
                serverId: serverId,
                source: source
            ) else {
                completion(RaftIosShellWindowContract.RequestStatus.refused.rawValue, "invalid_owner")
                return
            }
            RaftIosShellWindowContract.activateShell(
                for: owner,
                initialTab: initialTab,
                suppressRestoreLanding: suppressRestoreLanding,
                source: source,
                requestingScene: requestingScene
            ) { status, reason in
                completion(status.rawValue, reason)
            }
        }
        if Thread.isMainThread {
            run()
        } else {
            DispatchQueue.main.async(execute: run)
        }
    }

    private static func routeOwner(
        environmentId: String?,
        accountId: String?,
        serverId: String?,
        source: String
    ) -> RaftIosShellRouteOwner? {
        guard let owner = RaftIosShellRouteOwner(
            environmentId: environmentId ?? "",
            accountId: accountId ?? "",
            serverId: serverId ?? ""
        ).normalizedOrNull() else {
            RaftIosShellWindowLog(
                "warn",
                "document_shell_rejected reason=invalid_owner source=\(source)"
            )
            return nil
        }
        return owner
    }
}
