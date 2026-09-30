import Foundation

/// Identity-only parsing for the approved external HTTPS origins. This intentionally
/// does not inherit the developer web URL or the in-message parser's HTTP allowance.
struct SlockIosPermalinkIdentity: Equatable {
    let serverSlug: String
    let channelId: String
    let routeKind: String
    let parentMessageId: String?
    let messageId: String?
    let originHost: String

    static func parse(_ url: URL) -> Self? {
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "https",
              parts.user == nil, parts.password == nil,
              parts.port == nil || parts.port == 443,
              let host = parts.host?.lowercased(),
              ["app.raft.build", "app.slock.ai", "staging.slock.ai"].contains(host)
        else { return nil }
        let path = parts.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false)
        guard path.count == 5, path[0].isEmpty, path[1] == "s",
              let slug = path[2].removingPercentEncoding,
              let channel = path[4].removingPercentEncoding,
              validComponent(slug), validComponent(channel),
              ["channel", "dm"].contains(String(path[3])) else { return nil }
        let items = parts.queryItems ?? []
        let messages = items.filter { $0.name == "msg" }
        let threads = items.filter { $0.name == "thread" }
        guard messages.count <= 1, threads.count <= 1 else { return nil }
        let message = messages.first?.value
        if !messages.isEmpty && (message == nil || !validComponent(message!)) { return nil }
        var parent: String?
        if let thread = threads.first {
            guard let value = thread.value else { return nil }
            let ids = value.split(separator: ":", omittingEmptySubsequences: false)
            guard ids.count == 2, String(ids[0]) == channel,
                  validComponent(String(ids[1])) else { return nil }
            parent = String(ids[1])
        }
        return Self(serverSlug: slug, channelId: channel, routeKind: String(path[3]),
                    parentMessageId: parent, messageId: message, originHost: host)
    }

    private static func validComponent(_ value: String) -> Bool {
        !value.isEmpty && value.rangeOfCharacter(from:
            .whitespacesAndNewlines.union(.controlCharacters).union(CharacterSet(charactersIn: "/\\?#:"))) == nil
    }
}

/// Scene-owned transport queue, independent of notification navigation. Calls are
/// serialized by the host on the main thread. Shared owns canonical route/auth state
/// after acknowledgement; an acknowledgement is not a navigation success.
final class SlockIosPermalinkIntake {
    struct Pending: Equatable {
        let activationId: String
        let identity: SlockIosPermalinkIdentity
        let receivedAtMillis: Int64
        var authGeneration: String?
    }
    enum Acceptance: String {
        case firstTime = "accepted_first_time"
        case duplicate = "accepted_duplicate"
        case rejected = "rejected"
    }
    private(set) var shellScopeId: String
    private(set) var pending: [Pending] = []
    private(set) var ready = false
    private(set) var authGeneration: String?
    private let capacity: Int
    private let lifetimeMillis: Int64
    private let clock: () -> Int64
    private let dispatch: ([String: Any]) -> Void
    private let result: (String, String) -> Void

    init(shellScopeId: String, capacity: Int = 8, lifetimeMillis: Int64 = 300_000,
         clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
         dispatch: @escaping ([String: Any]) -> Void,
         result: @escaping (String, String) -> Void) {
        self.shellScopeId = shellScopeId
        self.capacity = capacity
        self.lifetimeMillis = lifetimeMillis
        self.clock = clock
        self.dispatch = dispatch
        self.result = result
    }

    @discardableResult
    func receive(_ url: URL, activationId: String = UUID().uuidString) -> Bool {
        guard let identity = SlockIosPermalinkIdentity.parse(url) else { return false }
        expire()
        if pending.contains(where: { $0.activationId == activationId }) {
            retry()
            return true
        }
        guard pending.count < capacity else {
            result(activationId, "queue_full")
            return false
        }
        pending.append(Pending(activationId: activationId, identity: identity,
                               receivedAtMillis: clock(), authGeneration: authGeneration))
        retry()
        return true
    }

    /// A root replacement gets a new receiver scope. Unacknowledged identities
    /// survive but can only dispatch after that receiver publishes its authority.
    func replaceReceiver(shellScopeId: String) {
        guard self.shellScopeId != shellScopeId else { return }
        self.shellScopeId = shellScopeId
        ready = false
    }

    func setReady(scope: String, authGeneration: String?, isReady: Bool) {
        guard scope == shellScopeId else { return }
        let generation = authGeneration.flatMap { $0.isEmpty ? nil : $0 }
        if isReady {
            // Never reinterpret a bound activation as an unauthenticated one.
            for item in pending where item.authGeneration != nil && item.authGeneration != generation {
                result(item.activationId, "auth_generation_stale")
            }
            pending.removeAll { $0.authGeneration != nil && $0.authGeneration != generation }
            self.authGeneration = generation
        } else if self.authGeneration != generation {
            // A late teardown from an older auth generation cannot revoke a new receiver.
            return
        }
        ready = isReady
        retry()
    }

    func acknowledge(activationId: String, scope: String, authGeneration: String?, status: Acceptance) {
        guard scope == shellScopeId, ready,
              let index = pending.firstIndex(where: { $0.activationId == activationId }),
              pending[index].authGeneration == authGeneration else { return }
        pending.remove(at: index)
        result(activationId, status.rawValue)
    }

    /// Explicit auth cancellation/scene destruction: no pending survives ownership.
    func cancel(reason: String) {
        for item in pending { result(item.activationId, reason) }
        pending.removeAll()
        authGeneration = nil
        ready = false
    }

    func retry() {
        expire()
        // Keep pre-login activations native until the first authenticated receiver;
        // that first bind is not an account switch. Readiness alone isn't auth.
        guard ready, let generation = authGeneration else { return }
        for index in pending.indices where pending[index].authGeneration == nil {
            pending[index].authGeneration = generation
        }
        // Dispatch can synchronously acknowledge; iterate a value snapshot.
        for item in pending {
            var data: [String: Any] = [
                "activationId": item.activationId, "shellScopeId": shellScopeId,
                "authGeneration": generation, "serverSlug": item.identity.serverSlug,
                "channelId": item.identity.channelId, "routeKind": item.identity.routeKind,
                "receivedAtMillis": String(item.receivedAtMillis), "originHost": item.identity.originHost,
            ]
            data["parentMessageId"] = item.identity.parentMessageId
            data["messageId"] = item.identity.messageId
            // Match the existing iOS platform event bus JSON boundary.
            if let bytes = try? JSONSerialization.data(withJSONObject: data),
               let json = String(data: bytes, encoding: .utf8) { data["stringify"] = json }
            dispatch(data)
        }
    }

    private func expire() {
        let now = clock()
        for item in pending where now - item.receivedAtMillis >= lifetimeMillis {
            result(item.activationId, "expired")
        }
        pending.removeAll { now - $0.receivedAtMillis >= lifetimeMillis }
    }
}
