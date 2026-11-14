import Foundation
import SocketIO
import Starscream
import UIKit
import shared

@objcMembers
final class RaftIosRealtimeHost: NSObject {
    static let shared = RaftIosRealtimeHost()

    private enum State {
        case disconnected
        case connecting
        case connected
    }

    private struct ProcessIdentity: Equatable {
        let environmentId: String
        let socketUrl: String
        let principalId: String
        let authGeneration: Int64
        let connectionFamilyId: String
        let accessToken: String

        // task #333: identity = the env/account binding (environmentId, socketUrl,
        // principalId, connectionFamilyId). authGeneration and accessToken rotate on
        // every silent token refresh on the SAME binding — a credential refresh, not
        // an identity replacement. Only a real binding swap may disconnect-all with
        // process_identity_replaced.
        func sameBinding(as other: ProcessIdentity) -> Bool {
            environmentId == other.environmentId &&
                socketUrl == other.socketUrl &&
                principalId == other.principalId &&
                connectionFamilyId == other.connectionFamilyId
        }
    }

    private struct ConnectParams: Equatable {
        let serverId: String
        let accessToken: String
        let socketUrl: String
        let lastSeq: Int64
        let authorityScopeLeaseId: String
        let authorityPrincipalId: String
        let authorityAuthGeneration: Int64
        let authorityConnectionFamilyId: String
    }

    private final class Runtime {
        let serverId: String
        let roomEngine = RaftRealtimeAppScopeEngine()
        var manager: SocketManager?
        var socket: SocketIOClient?
        /// The common-Kotlin client (`:realtime-socket`) when `realtime_kmp_client_v1` was on as this socket
        /// was created (RaftRealtimeKmpClientFlag); nil on the Socket.IO-Client-Swift path.
        var kmpSocket: RaftKmpRealtimeSocket?
        var kmpBridge: RaftIosKmpSocketBridge?
        var connectParams: ConnectParams?
        var state: State = .disconnected
        var joinedChannels: Set<String> = []
        var authRefreshInFlight = false
        var generation: UInt64 = 0
        var resumeLastSeq: Int64 = 0
        /// Sync horizon (botiverse/slock #10982; shared `RealtimeSyncHorizon`): decimal xid8 text, never a
        /// number. The next `sync:resume` sends it as `sinceHorizon`; nil = resume exactly as before.
        ///
        /// Invariant: the horizon held must have been computed before every later read that raises
        /// `lastSeq`. The starting horizon guarantees this because its ack arrives before any history
        /// request is sent. Each later round uses the horizon from the `sync:resume` response, which is
        /// also computed before any later history load.
        ///
        /// [resumeLastSeq] is raised only from socket frames (shared's `cursorAdvance` after it applied a
        /// `message:new` / `sync:resume:response`), never by an HTTP history load; a connect that would
        /// move it up from elsewhere drops the horizon (connectRuntimeLocked).
        var horizon: String?
        var presenceTransportEpoch: Int64 = 0
        /// Last `presence:activity` `active` value sent on this connection; nil = none since connect.
        var presenceActiveSent: Bool?

        init(serverId: String) {
            self.serverId = serverId
        }
    }

    private let queue = DispatchQueue(label: "build.raft.app.ios.realtime.host")
    private var runtimes: [String: Runtime] = [:]
    private var processIdentity: ProcessIdentity?
    private var nextGeneration: UInt64 = 0
    private var nextPresenceTransportEpoch: Int64 = 0
    private var foregroundEpoch: Int64 = 0
    private var didInstallObservers = false
    private var lifecyclePolicy: RaftIosRealtimeLifecyclePolicy?
    /// App/shell visibility as last reported by the lifecycle policy; nil until known (see init).
    private var shellVisible: Bool?
    /// `presence:activity` heartbeat while visible and at least one socket is connected (contract §5).
    private var presenceTimer: DispatchSourceTimer?

    var statusLabel: String {
        queue.sync {
            if runtimes.values.contains(where: { $0.state == .connected }) { return "Connected" }
            if runtimes.values.contains(where: { $0.state == .connecting }) { return "Connecting" }
            return "Disconnected"
        }
    }

    /// Info.plist `RaftRealtimeKmpClientDefaultOn` (`$(RAFT_REALTIME_KMP_CLIENT_DEFAULT_ON)`); off when absent.
    static func realtimeKmpClientBuildDefaultOn(bundle: Bundle = .main) -> Bool {
        guard let raw = bundle.object(forInfoDictionaryKey: "RaftRealtimeKmpClientDefaultOn") as? String else { return false }
        return ["yes", "true", "1"].contains(raw.trimmingCharacters(in: .whitespaces).lowercased())
    }

    private override init() {
        super.init()
        // This host has the common-client adapter: Settings > Developer offers its switch.
        RaftRealtimeKmpClientFlagKt.raftRealtimeKmpClientMarkHostSupported()
        // Build default (RAFT_REALTIME_KMP_CLIENT_DEFAULT_ON: YES in Debug, the QA / simulator builds; NO in
        // Release): QA builds use the common client until the server explicitly turns it off.
        RaftRealtimeKmpClientFlagKt.raftRealtimeKmpClientSetBuildDefault(on: Self.realtimeKmpClientBuildDefaultOn())
        installObserversIfNeeded()
        // The host may be created after the first didBecomeActive, so seed visibility from the current
        // application state unless a lifecycle event already set it.
        DispatchQueue.main.async { [weak self] in
            let active = UIApplication.shared.applicationState == .active
            self?.queue.async {
                guard let self, self.shellVisible == nil else { return }
                self.shellVisible = active
                if active { self.sendPresenceActivityLocked(active: true) }
                self.updatePresenceTimerLocked()
            }
        }
    }

    func handleCommand(_ command: NSDictionary) {
        let action = stringValue(command["action"])
        switch action {
        case "reconcile":
            reconcile(
                serverIds: stringArray(command["serverIds"]),
                accessToken: stringValue(command["accessToken"]),
                environmentId: stringValue(command["environmentId"]),
                socketUrl: stringValue(command["socketUrl"]),
                forceReconnect: boolValue(command["forceReconnect"]),
                disconnectReason: stringValue(command["disconnectReason"], fallback: "auth_invalid"),
                authorityScopeLeaseId: stringValue(command["authorityScopeLeaseId"]),
                authorityPrincipalId: stringValue(command["authorityPrincipalId"]),
                authorityAuthGeneration: int64Value(command["authorityAuthGeneration"]),
                authorityConnectionFamilyId: stringValue(command["authorityConnectionFamilyId"])
            )
        case "connect":
            connectLegacy(
                serverId: stringValue(command["serverId"]),
                accessToken: stringValue(command["accessToken"]),
                socketUrl: stringValue(command["socketUrl"]),
                lastSeq: int64Value(command["lastSeq"]),
                forceReconnect: boolValue(command["forceReconnect"]),
                authorityScopeLeaseId: stringValue(command["authorityScopeLeaseId"]),
                authorityPrincipalId: stringValue(command["authorityPrincipalId"]),
                authorityAuthGeneration: int64Value(command["authorityAuthGeneration"]),
                authorityConnectionFamilyId: stringValue(command["authorityConnectionFamilyId"])
            )
        case "disconnect":
            disconnect(
                reason: stringValue(command["reason"], fallback: "shared_disconnect"),
                serverId: stringValue(command["serverId"])
            )
        case "authRefreshFinished":
            queue.async { [weak self] in
                guard let self else { return }
                self.runtimes.values.forEach { $0.authRefreshInFlight = false }
                self.log(
                    "auth_refresh_finished reason=\(self.stringValue(command["reason"])) " +
                        "success=\(self.boolValue(command["success"]))"
                )
            }
        case "cursorAdvance":
            advanceResumeCursor(
                serverId: stringValue(command["serverId"]),
                appliedSeq: int64Value(command["appliedSeq"]),
                authorityScopeLeaseId: stringValue(command["authorityScopeLeaseId"]),
                authorityPrincipalId: stringValue(command["authorityPrincipalId"]),
                authorityAuthGeneration: int64Value(command["authorityAuthGeneration"]),
                authorityConnectionFamilyId: stringValue(command["authorityConnectionFamilyId"])
            )
        default:
            log("unsupported_action action=\(action)")
        }
    }

    private func reconcile(
        serverIds: [String],
        accessToken: String,
        environmentId: String,
        socketUrl: String,
        forceReconnect: Bool,
        disconnectReason: String,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Int64,
        authorityConnectionFamilyId: String
    ) {
        let desiredServerIds = Set(serverIds.map(clean).filter { !$0.isEmpty })
        let cleanToken = clean(accessToken)
        let cleanSocketUrl = clean(socketUrl).isEmpty ? "https://api.raft.build" : clean(socketUrl)
        queue.async { [weak self] in
            guard let self else { return }
            guard !desiredServerIds.isEmpty else {
                self.disconnectAllLocked(reason: disconnectReason)
                self.processIdentity = nil
                self.record("info", "process_reconcile result=cleared reason=\(disconnectReason)")
                return
            }
            let principalId = self.clean(authorityPrincipalId)
            let familyId = self.clean(authorityConnectionFamilyId)
            guard
                !cleanToken.isEmpty,
                !self.clean(environmentId).isEmpty,
                !authorityScopeLeaseId.isEmpty,
                !principalId.isEmpty,
                authorityAuthGeneration > 0,
                !familyId.isEmpty
            else {
                self.record("warn", "process_reconcile_skip reason=invalid_authority serverCount=\(desiredServerIds.count)")
                self.disconnectAllLocked(reason: "invalid_process_authority")
                self.processIdentity = nil
                return
            }
            let nextIdentity = ProcessIdentity(
                environmentId: self.clean(environmentId),
                socketUrl: cleanSocketUrl,
                principalId: principalId,
                authGeneration: authorityAuthGeneration,
                connectionFamilyId: familyId,
                accessToken: cleanToken
            )
            let identityChanged = self.processIdentity != nil && self.processIdentity != nextIdentity
            // task #333: only a real binding swap tears the session down
            // (process_identity_replaced); a same-binding token rotation is a
            // credential refresh and must not disconnect-all or kick the UI.
            let bindingReplaced = self.processIdentity != nil && !(self.processIdentity!.sameBinding(as: nextIdentity))
            if bindingReplaced {
                self.disconnectAllLocked(reason: "process_identity_replaced")
            }
            self.processIdentity = nextIdentity
            for serverId in Array(self.runtimes.keys) where !desiredServerIds.contains(serverId) {
                self.removeRuntimeLocked(serverId: serverId, reason: "server_removed")
            }
            for serverId in desiredServerIds.sorted() {
                let params = ConnectParams(
                    serverId: serverId,
                    accessToken: cleanToken,
                    socketUrl: cleanSocketUrl,
                    lastSeq: self.runtimes[serverId]?.resumeLastSeq ?? 0,
                    authorityScopeLeaseId: authorityScopeLeaseId,
                    authorityPrincipalId: principalId,
                    authorityAuthGeneration: authorityAuthGeneration,
                    authorityConnectionFamilyId: familyId
                )
                self.connectRuntimeLocked(params: params, forceReconnect: forceReconnect || identityChanged)
            }
            self.log(
                "process_reconcile result=applied serverCount=\(desiredServerIds.count) " +
                    "identityChanged=\(identityChanged) forceReconnect=\(forceReconnect)"
            )
        }
    }

    private func connectLegacy(
        serverId: String,
        accessToken: String,
        socketUrl: String,
        lastSeq: Int64,
        forceReconnect: Bool,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Int64,
        authorityConnectionFamilyId: String
    ) {
        let cleanServerId = clean(serverId)
        let cleanToken = clean(accessToken)
        guard !cleanServerId.isEmpty, !cleanToken.isEmpty else {
            log("connect_skip reason=missing_server_or_token")
            return
        }
        let params = ConnectParams(
            serverId: cleanServerId,
            accessToken: cleanToken,
            socketUrl: clean(socketUrl).isEmpty ? "https://api.raft.build" : clean(socketUrl),
            lastSeq: lastSeq,
            authorityScopeLeaseId: authorityScopeLeaseId,
            authorityPrincipalId: authorityPrincipalId,
            authorityAuthGeneration: authorityAuthGeneration,
            authorityConnectionFamilyId: authorityConnectionFamilyId
        )
        queue.async { [weak self] in
            self?.connectRuntimeLocked(params: params, forceReconnect: forceReconnect)
        }
    }

    private func connectRuntimeLocked(params: ConnectParams, forceReconnect: Bool) {
        let runtime = runtimes[params.serverId] ?? Runtime(serverId: params.serverId)
        runtimes[params.serverId] = runtime
        let sameSession = runtime.connectParams.map { sameSessionIdentity($0, params) } ?? false
        if sameSession && !forceReconnect && runtime.state != .disconnected {
            log("connect_skip reason=same_session state=\(stateLabel(runtime.state)) serverId=\(params.serverId)")
            return
        }
        // The common client asks for a token before every attempt: a rotated token on the same binding
        // is handed to the live socket instead of replacing it.
        if let kmpSocket = runtime.kmpSocket, !forceReconnect,
           let current = runtime.connectParams, sameSessionExceptToken(current, params), current.accessToken != params.accessToken {
            runtime.connectParams = params
            runtime.resumeLastSeq = max(runtime.resumeLastSeq, params.lastSeq)
            runtime.authRefreshInFlight = false
            kmpSocket.updateToken(token: params.accessToken)
            record("info", "kmp_socket_token_updated serverId=\(params.serverId) state=\(stateLabel(runtime.state))")
            if runtime.state == .disconnected { kmpSocket.kick(reason: "token_updated") }
            return
        }
        let previousResumeLastSeq = runtime.resumeLastSeq
        if !sameSession {
            runtime.joinedChannels.removeAll()
            runtime.roomEngine.resetRoomStateForHost()
            runtime.resumeLastSeq = max(0, params.lastSeq)
        } else {
            runtime.resumeLastSeq = max(runtime.resumeLastSeq, params.lastSeq)
        }
        // The horizon stays paired with the watermark it was taken with: a lower one only asks for more
        // rows, a higher one (or none) is not covered by it.
        if runtime.resumeLastSeq <= 0 || runtime.resumeLastSeq > previousResumeLastSeq {
            runtime.horizon = nil
        }
        runtime.connectParams = params
        runtime.authRefreshInFlight = false
        openSocketLocked(runtime: runtime, params: params)
    }

    private func openSocketLocked(runtime: Runtime, params: ConnectParams) {
        guard let url = URL(string: params.socketUrl) else {
            log("connect_skip reason=invalid_url serverId=\(params.serverId)")
            return
        }
        disconnectRuntimeLocked(runtime, reason: "reconnect", keepSession: true)
        runtime.state = .connecting
        nextGeneration += 1
        runtime.generation = nextGeneration
        let generation = runtime.generation
        // Read at socket creation only (never mid-connection): turning the flag off rolls the next socket
        // back to Socket.IO-Client-Swift.
        if RaftRealtimeKmpClientFlagKt.raftRealtimeKmpClientEnabled() {
            openKmpSocketLocked(runtime: runtime, params: params, generation: generation)
            return
        }
        let manager = SocketManager(
            socketURL: url,
            config: [
                .log(false),
                .compress,
                .forceWebsockets(true),
                // Without a pinner Starscream's TCPTransport (Socket.IO's default custom engine)
                // completes the TLS verify block with `true` for any certificate. FoundationSecurity
                // evaluates the chain with SecPolicyCreateSSL(true, host) + SecTrustEvaluateWithError.
                .security(FoundationSecurity()),
                .reconnects(true),
                .reconnectAttempts(-1),
                .reconnectWait(1),
                .reconnectWaitMax(30),
                .path("/socket.io"),
                .extraHeaders([
                    "Authorization": "Bearer \(params.accessToken)",
                    "X-Server-Id": params.serverId,
                ]),
            ]
        )
        let socket = manager.defaultSocket
        runtime.manager = manager
        runtime.socket = socket
        installSocketHandlers(socket, serverId: params.serverId, generation: generation)
        record("info", "connect_start transport=swift_socketio serverId=\(params.serverId) generation=\(generation)")
        socket.connect(withPayload: [
            "token": params.accessToken,
            "serverId": params.serverId,
            "clientKind": "mobile",
            // botiverse/slock 968d102d: detailed agent:activity only for subscribed agents, plus
            // agent:activity_state for all (shared AgentActivitySubscriptions drives the subscriptions).
            "capabilities": ["agent_activity_subscriptions_v1"],
        ])
    }

    private func markConnectedLocked(runtime: Runtime, transport: String, generation: UInt64) {
        runtime.state = .connected
        nextPresenceTransportEpoch += 1
        runtime.presenceTransportEpoch = nextPresenceTransportEpoch
        record(
            "info",
            "\(transport)_connected serverId=\(runtime.serverId) generation=\(generation) " +
                "presenceEpoch=\(runtime.presenceTransportEpoch)"
        )
        postPresenceTransportConnected(runtime: runtime)
        requestStartingHorizon(runtime: runtime, generation: generation)
        rejoinChannels(runtime: runtime, reason: "connect")
        runtime.presenceActiveSent = nil
        if shellVisible == true {
            emitPresenceActivity(runtime: runtime, active: true)
        }
        updatePresenceTimerLocked()
    }

    // MARK: presence:activity (contract §5)

    private func emitPresenceActivity(runtime: Runtime, active: Bool) {
        guard runtime.state == .connected, runtime.socket != nil else { return }
        // A heartbeat repeats `true`; `false` is sent once per connection (hide, then background).
        if !active && runtime.presenceActiveSent == false { return }
        emit(runtime: runtime, eventName: RaftIosPresenceActivity.eventName, payload: RaftIosPresenceActivity.payload(active: active))
        runtime.presenceActiveSent = active
    }

    private func sendPresenceActivityLocked(active: Bool) {
        for runtime in runtimes.values where runtime.state == .connected {
            emitPresenceActivity(runtime: runtime, active: active)
        }
    }

    private func updatePresenceTimerLocked() {
        let shouldRun = shellVisible == true && runtimes.values.contains { $0.state == .connected }
        if !shouldRun {
            presenceTimer?.cancel()
            presenceTimer = nil
            return
        }
        guard presenceTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        let interval = RaftIosPresenceActivity.heartbeatInterval
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            guard self.shellVisible == true, self.runtimes.values.contains(where: { $0.state == .connected }) else {
                self.updatePresenceTimerLocked()
                return
            }
            self.sendPresenceActivityLocked(active: true)
        }
        presenceTimer = timer
        timer.resume()
    }

    /// Foreground-only socket copy of the APNs retract: closes the group's delivered notifications.
    /// The badge is not touched (the socket event carries none).
    private func applyNotificationRetractLocked(rawPayload: String) {
        guard let retract = RaftIosNotificationRetraction.parseSocketRetract(rawPayload: rawPayload) else {
            log("notification_retract_rejected reason=invalid_payload")
            return
        }
        RaftIosNotificationRetraction.apply(retract, completion: { [weak self] removed in
            self?.log("notification_retract_applied removed=\(removed) wholeGroup=\(retract.throughSeq == nil)")
        })
    }

    /// Asks this socket for a starting horizon while none is held. Never waited for: the ack is used only
    /// if it arrives before any baseline history request of this server was sent (shared
    /// `RealtimeBaselineHistoryRequests`); a late, missing (older server) or error ack leaves none, so the
    /// first resume runs as before and its response provides the next one.
    private func requestStartingHorizon(runtime: Runtime, generation: UInt64) {
        guard runtime.horizon == nil else { return }
        if let kmpSocket = runtime.kmpSocket {
            let serverId = runtime.serverId
            let callback = RaftIosKmpAckCallback { [weak self] payload, status in
                self?.queue.async {
                    guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
                    let ack = payload.flatMap { $0.data(using: .utf8) }.flatMap { try? JSONSerialization.jsonObject(with: $0) }
                    self.log("sync_horizon_ack_status serverId=\(serverId) status=\(status)")
                    self.acceptStartingHorizonLocked(runtime: runtime, ack: ack)
                }
            }
            kmpSocket.emitWithAck(
                eventName: GeneratedRealtimeContract.SocketEvent.syncHorizon,
                timeoutMillis: 10_000,
                callback: callback
            )
            record("info", "sync_horizon_request serverId=\(serverId) result=sent transport=kmp")
            return
        }
        guard let socket = runtime.socket else {
            log("sync_horizon_request serverId=\(runtime.serverId) result=unsupported_transport")
            return
        }
        let serverId = runtime.serverId
        socket.emitWithAck(GeneratedRealtimeContract.SocketEvent.syncHorizon).timingOut(after: 10) { [weak self] data in
            self?.queue.async {
                guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
                self.acceptStartingHorizonLocked(runtime: runtime, ack: data.first)
            }
        }
        record("info", "sync_horizon_request serverId=\(serverId) result=sent")
    }

    private func acceptStartingHorizonLocked(runtime: Runtime, ack: Any?) {
        let horizon = ((ack as? [String: Any])?[Self.syncHorizonKey] as? String)
            .flatMap { RealtimeSyncHorizon.shared.parse(value: $0) }
        let reason: String
        if horizon == nil {
            reason = "no_horizon"
        } else if runtime.horizon != nil {
            reason = "horizon_held"
        } else if RealtimeBaselineHistoryRequests.shared.anySent(serverId: runtime.serverId) {
            reason = "history_request_sent_first"
        } else {
            runtime.horizon = horizon
            reason = "accepted"
        }
        record("info", "sync_horizon_ack serverId=\(runtime.serverId) accepted=\(reason == "accepted") reason=\(reason)")
    }

    // MARK: common-Kotlin client (realtime_kmp_client_v1)

    /// The same socket as openSocketLocked's Socket.IO-Client-Swift one, on `:realtime-socket`: same handshake
    /// auth, same events (`serverWireEvents`; anything else is logged by the client, never silently dropped),
    /// same callbacks into this host. The client reconnects itself with backoff and asks for the current
    /// token before every attempt.
    private func openKmpSocketLocked(runtime: Runtime, params: ConnectParams, generation: UInt64) {
        let bridge = RaftIosKmpSocketBridge(host: self, serverId: params.serverId, generation: generation)
        let kmpSocket = RaftKmpRealtimeSocket(
            socketUrl: params.socketUrl,
            serverId: params.serverId,
            token: params.accessToken,
            clientKind: "mobile",
            capabilities: ["agent_activity_subscriptions_v1"],
            allowedEvents: Self.serverWireEvents,
            listener: bridge
        )
        runtime.kmpSocket = kmpSocket
        runtime.kmpBridge = bridge
        record("info", "connect_start transport=kmp_socketio serverId=\(params.serverId) generation=\(generation)")
        kmpSocket.connect()
    }

    fileprivate func kmpSocketConnected(serverId: String, generation: UInt64, socketId: String, reconnect: Bool) {
        queue.async { [weak self] in
            guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
            self.record("info", "kmp_socket_connected serverId=\(serverId) reconnect=\(reconnect)")
            self.markConnectedLocked(runtime: runtime, transport: "kmp_socket", generation: generation)
        }
    }

    fileprivate func kmpSocketDisconnected(serverId: String, generation: UInt64, reason: String) {
        queue.async { [weak self] in
            guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
            runtime.state = .disconnected
            self.record("warn", "kmp_socket_disconnected serverId=\(serverId) generation=\(generation) reason=\(reason)")
            self.updatePresenceTimerLocked()
        }
    }

    fileprivate func kmpSocketConnectError(
        serverId: String, generation: UInt64, kind: String, terminal: Bool, requiresTokenRefresh: Bool
    ) {
        queue.async { [weak self] in
            guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
            runtime.state = .disconnected
            self.record(
                "warn",
                "kmp_socket_connect_error serverId=\(serverId) kind=\(kind) terminal=\(terminal) refresh=\(requiresTokenRefresh)"
            )
            // Typed, not keyword-matched: SSO_REQUIRED and "Not a member" never refresh (refreshing would loop);
            // the next API call surfaces them.
            guard requiresTokenRefresh else { return }
            self.requestAuthRefresh(runtime: runtime, reason: "connect_error")
        }
    }

    fileprivate func kmpSocketEvent(serverId: String, generation: UInt64, eventName: String, rawPayload: String, ackId: Int64) {
        queue.async { [weak self] in
            guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
            if ackId >= 0 {
                // Only desktop sockets get ack requests (notification:desktop); a mobile socket logs and moves on.
                self.log("kmp_socket_ack_request_unanswered serverId=\(serverId) eventName=\(eventName) ackId=\(ackId)")
            }
            self.handleSocketEvent(runtime: runtime, eventName: eventName, rawPayload: rawPayload)
        }
    }

    fileprivate func kmpSocketLog(_ message: String) {
        record("info", message)
    }

    private func installSocketHandlers(_ socket: SocketIOClient, serverId: String, generation: UInt64) {
        socket.on(clientEvent: .connect) { [weak self] _, _ in
            self?.queue.async {
                guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
                self.markConnectedLocked(runtime: runtime, transport: "socket_io", generation: generation)
            }
        }
        socket.on(clientEvent: .disconnect) { [weak self] data, _ in
            self?.queue.async {
                guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
                runtime.state = .disconnected
                self.record("warn", "socket_io_disconnected serverId=\(serverId) generation=\(generation) reason=\(data.first.map { "\($0)" } ?? "")")
            }
        }
        // task #191: observation only — Socket.IO's own reconnect policy is unchanged.
        socket.on(clientEvent: .reconnect) { [weak self] data, _ in
            self?.queue.async {
                guard let self, self.currentRuntime(serverId: serverId, generation: generation) != nil else { return }
                self.record("info", "socket_io_reconnect serverId=\(serverId) generation=\(generation) reason=\(data.first.map { "\($0)" } ?? "")")
            }
        }
        socket.on(clientEvent: .reconnectAttempt) { [weak self] data, _ in
            self?.queue.async {
                guard let self, self.currentRuntime(serverId: serverId, generation: generation) != nil else { return }
                self.record("info", "socket_io_reconnect_attempt serverId=\(serverId) generation=\(generation) remaining=\(data.first.map { "\($0)" } ?? "")")
            }
        }
        socket.on(clientEvent: .error) { [weak self] data, _ in
            self?.queue.async {
                guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
                runtime.state = .disconnected
                self.record("warn", "socket_io_error serverId=\(serverId) generation=\(generation) data=\(data)")
                // SSO required (rfcs/083 in botiverse/slock): the token is valid, so refreshing it and
                // reconnecting would loop. The next API call answers 403 SSO_REQUIRED and the app asks
                // the person to sign in again (RaftSsoRequiredSignal).
                if "\(data)".contains("SSO_REQUIRED") { return }
                self.requestAuthRefresh(runtime: runtime, reason: "connect_error")
            }
        }
        for eventName in Self.serverWireEvents {
            socket.on(eventName) { [weak self] data, _ in
                self?.queue.async {
                    guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
                    self.handleSocketEvent(runtime: runtime, eventName: eventName, rawPayload: self.jsonString(data.first ?? [:]))
                }
            }
        }
    }

    private func handleSocketEvent(runtime: Runtime, eventName: String, rawPayload: String) {
        if eventName == GeneratedRealtimeContract.SocketEvent.roomsJoined {
            log(
                "rooms_joined serverId=\(runtime.serverId) joinedRooms=\(runtime.joinedChannels.count) " +
                    "lastSeq=\(runtime.resumeLastSeq)"
            )
            // Task #168: shared learns this socket is subscribed to all of its rooms, with the socket's epoch.
            postTransportSignal(runtime: runtime, action: "realtimeRoomsJoined")
            if runtime.resumeLastSeq > 0 {
                var payload: [String: Any] = ["lastSeq": runtime.resumeLastSeq]
                // See Runtime.horizon for the invariant; the response's horizon starts the next round.
                if let horizon = runtime.horizon {
                    payload[Self.syncSinceHorizonKey] = horizon
                }
                record("info", "sync_resume_emit serverId=\(runtime.serverId) lastSeq=\(runtime.resumeLastSeq) sinceHorizon=\(runtime.horizon != nil)")
                emit(
                    runtime: runtime,
                    eventName: GeneratedRealtimeContract.SocketEvent.syncResume,
                    payload: payload
                )
            }
            return
        }
        if eventName == GeneratedRealtimeContract.SocketEvent.channelJoined ||
            eventName == GeneratedRealtimeContract.SocketEvent.channelJoinDenied {
            // Task #14c: the server's answer to this socket's `join:channel` ({ channelId }), forwarded to shared
            // with the socket's epoch like rooms:joined. Never a domain event.
            let joined = eventName == GeneratedRealtimeContract.SocketEvent.channelJoined
            let channelId = payloadStringField(rawPayload: rawPayload, key: GeneratedRealtimeContract.PayloadKey.channelId)
            log("channel_join_ack serverId=\(runtime.serverId) channelId=\(channelId) joined=\(joined)")
            guard !channelId.isEmpty else { return }
            postTransportSignal(
                runtime: runtime,
                action: joined ? "realtimeChannelJoined" : "realtimeChannelJoinDenied",
                extra: [GeneratedRealtimeContract.PayloadKey.channelId: channelId]
            )
            return
        }
        guard let params = runtime.connectParams else { return }
        let gate = payloadServerGate(connectionServerId: params.serverId, rawPayload: rawPayload)
        // A user-scoped event (sent to the member's own room, e.g. appearance:updated) may name
        // another server than this connection's: the shared layer applies it to that server
        // (RealtimeSocketEvents.UserScopedServerEvents; artin 2026-10-28, a theme changed on web).
        let userScopedElsewhere = gate.reason == "mismatch" && Self.userScopedServerEvents.contains(eventName)
        guard gate.accept || userScopedElsewhere else {
            log(
                "raw_event_skip reason=payload_server_\(gate.reason) connectionServerId=\(params.serverId) " +
                    "payloadServerId=\(gate.payloadServerId) eventName=\(eventName)"
            )
            return
        }
        postRawEvent(runtime: runtime, params: params, eventName: eventName, rawPayload: rawPayload)
        if eventName == RaftIosNotificationRetraction.socketEventName {
            applyNotificationRetractLocked(rawPayload: rawPayload)
        }
        if eventName == GeneratedRealtimeContract.SocketEvent.syncResumeResponse {
            applySyncResumeHorizonLocked(runtime: runtime, params: params, rawPayload: rawPayload)
        }
    }

    private func postRawEvent(runtime: Runtime, params: ConnectParams, eventName: String, rawPayload: String) {
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.serverId: params.serverId,
                GeneratedRealtimeContract.PayloadKey.eventName: eventName,
                GeneratedRealtimeContract.PayloadKey.rawPayload: rawPayload,
                "authorityScopeLeaseId": params.authorityScopeLeaseId,
                "authorityPrincipalId": params.authorityPrincipalId,
                "authorityAuthGeneration": params.authorityAuthGeneration,
                "authorityConnectionFamilyId": params.authorityConnectionFamilyId,
                "presenceTransportEpoch": runtime.presenceTransportEpoch,
            ]
        )
    }

    /// Every resume is a round's first (and only) page: its `horizon` starts the next round; an older
    /// server sends none and nothing changes. `horizonReset: true` (too many late messages to send) asks
    /// shared for the Home/Activity re-fetch, after the response's own rows were posted above; shared
    /// maps the mobile-local frame to `RealtimeRefreshRequired(Home)` and the open thread re-reads its
    /// latest page.
    private func applySyncResumeHorizonLocked(runtime: Runtime, params: ConnectParams, rawPayload: String) {
        guard
            let data = rawPayload.data(using: .utf8),
            let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return }
        if let horizon = (payload[Self.syncHorizonKey] as? String)
            .flatMap({ RealtimeSyncHorizon.shared.parse(value: $0) }) {
            runtime.horizon = horizon
        }
        let reset = (payload["horizonReset"] as? Bool) ?? false
        record("info", "sync_resume_response serverId=\(runtime.serverId) horizon=\(runtime.horizon != nil) horizonReset=\(reset)")
        guard reset else { return }
        postRawEvent(
            runtime: runtime,
            params: params,
            eventName: Self.catchUpRequiredEvent,
            rawPayload: jsonString([
                GeneratedRealtimeContract.PayloadKey.serverId: params.serverId,
                "reason": Self.syncHorizonResetReason,
            ])
        )
    }

    private func postPresenceTransportConnected(runtime: Runtime) {
        postTransportSignal(runtime: runtime, action: "presenceTransportConnected")
    }

    /// A connection-level signal for shared, carrying this socket's epoch; skipped when the socket has none.
    /// [extra]: signal-specific keys (task #14c: the acknowledged channel id).
    private func postTransportSignal(runtime: Runtime, action: String, extra: [String: Any] = [:]) {
        guard let params = runtime.connectParams, runtime.presenceTransportEpoch > 0 else { return }
        var userInfo: [String: Any] = [
            GeneratedRealtimeContract.PayloadKey.action: action,
            GeneratedRealtimeContract.PayloadKey.serverId: params.serverId,
            "authorityScopeLeaseId": params.authorityScopeLeaseId,
            "authorityPrincipalId": params.authorityPrincipalId,
            "authorityAuthGeneration": params.authorityAuthGeneration,
            "authorityConnectionFamilyId": params.authorityConnectionFamilyId,
            "presenceTransportEpoch": runtime.presenceTransportEpoch,
        ]
        for (key, value) in extra { userInfo[key] = value }
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: userInfo
        )
    }

    /// Task #14c: a top-level non-blank string field of a socket payload (exact key, no aliases); "" otherwise.
    private func payloadStringField(rawPayload: String, key: String) -> String {
        guard
            let data = rawPayload.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data),
            let payload = object as? [String: Any],
            let value = payload[key] as? String
        else {
            return ""
        }
        let trimmed = clean(value)
        return trimmed == value ? trimmed : ""
    }

    @objc private func handleRoomNotification(_ notification: Notification) {
        let userInfo = notification.userInfo ?? [:]
        let action = stringValue(userInfo[GeneratedRealtimeContract.PayloadKey.action])
        let channelId = stringValue(userInfo[GeneratedRealtimeContract.PayloadKey.channelId])
        let targetServerId = stringValue(userInfo[GeneratedRealtimeContract.PayloadKey.serverId])
        if let subscriptionEvent = agentActivitySubscriptionEventName(action: action) {
            handleAgentActivitySubscription(
                eventName: subscriptionEvent,
                agentIds: agentActivitySubscriptionIds(userInfo: userInfo),
                targetServerId: targetServerId
            )
            return
        }
        guard !channelId.isEmpty else {
            log("room_command_skip reason=missing_channel_id action=\(action)")
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            let runtime: Runtime?
            if !targetServerId.isEmpty {
                runtime = self.runtimes[targetServerId]
            } else if self.runtimes.count == 1 {
                runtime = self.runtimes.values.first
            } else {
                runtime = nil
            }
            guard let runtime else {
                self.log(
                    "room_command_skip reason=ambiguous_or_missing_server targetServerId=\(targetServerId) " +
                        "runtimeCount=\(self.runtimes.count)"
                )
                return
            }
            let decision = runtime.roomEngine.roomCommandDecisionForHost(
                action: action,
                channelIdValue: channelId,
                reason: "notify"
            )
            if !decision.logMessage.isEmpty { self.log("serverId=\(runtime.serverId) \(decision.logMessage)") }
            if let decidedChannelId = decision.channelId?.value, !decidedChannelId.isEmpty {
                if decision.joinedChannelAction == "join" || decision.joinedChannelAction == "rejoin" {
                    runtime.joinedChannels.insert(decidedChannelId)
                } else if decision.joinedChannelAction == "leave" {
                    runtime.joinedChannels.remove(decidedChannelId)
                }
            }
            guard decision.shouldEmit, let eventName = decision.eventName, let payload = decision.payload else { return }
            self.emitRoomCommand(runtime: runtime, eventName: eventName, channelId: payload)
        }
    }

    /// Shared `RaftRealtimeEvents.postAgentActivitySubscription`: one subscribe / unsubscribe emit on the
    /// named server's socket. Dropped while disconnected: shared re-sends its whole set after every connect.
    private func handleAgentActivitySubscription(eventName: String, agentIds: [String], targetServerId: String) {
        guard !agentIds.isEmpty, !targetServerId.isEmpty else {
            log("agent_activity_subscription_skip reason=missing_ids_or_server serverId=\(targetServerId)")
            return
        }
        queue.async { [weak self] in
            guard let self, let runtime = self.runtimes[targetServerId] else { return }
            self.log("agent_activity_subscription serverId=\(targetServerId) eventName=\(eventName) count=\(agentIds.count)")
            self.emit(runtime: runtime, eventName: eventName, payload: ["agentIds": agentIds])
        }
    }

    private func agentActivitySubscriptionEventName(action: String) -> String? {
        switch action {
        case GeneratedRealtimeContract.RoomAction.agentActivitySubscribe:
            return GeneratedRealtimeContract.SocketEvent.agentActivitySubscribe
        case GeneratedRealtimeContract.RoomAction.agentActivityUnsubscribe:
            return GeneratedRealtimeContract.SocketEvent.agentActivityUnsubscribe
        default:
            return nil
        }
    }

    /// The `agentIds` array of the shared event (only in its `stringify` JSON; userInfo carries scalar keys).
    private func agentActivitySubscriptionIds(userInfo: [AnyHashable: Any]) -> [String] {
        guard
            let json = userInfo["stringify"] as? String,
            let data = json.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return []
        }
        return stringArray(object["agentIds"]).map { clean($0) }.filter { !$0.isEmpty }
    }

    private func emitRoomCommand(runtime: Runtime, eventName: String, channelId: String) {
        // The server's join:channel / leave:channel handlers take the channel id itself, as Android
        // sends it. The previous {"channelId": …} object made both a silent no-op on iOS. Which
        // rooms get a leave at all is decided in shared (member channels never do).
        guard runtime.state == .connected else {
            log("emit_deferred serverId=\(runtime.serverId) eventName=\(eventName)")
            return
        }
        if let kmpSocket = runtime.kmpSocket {
            kmpSocket.emitString(eventName: eventName, value: channelId)
        } else if let socket = runtime.socket {
            socket.emit(eventName, channelId)
        } else {
            log("emit_deferred serverId=\(runtime.serverId) eventName=\(eventName)")
        }
    }

    private func emit(runtime: Runtime, eventName: String, payload: [String: Any]) {
        guard runtime.state == .connected else {
            log("emit_deferred serverId=\(runtime.serverId) eventName=\(eventName)")
            return
        }
        if let kmpSocket = runtime.kmpSocket {
            kmpSocket.emitJson(eventName: eventName, payloadJson: jsonString(payload))
        } else if let socket = runtime.socket {
            socket.emit(eventName, payload)
        } else {
            log("emit_deferred serverId=\(runtime.serverId) eventName=\(eventName)")
        }
    }

    private func rejoinChannels(runtime: Runtime, reason: String) {
        for channelId in runtime.joinedChannels {
            log("rejoin_channel serverId=\(runtime.serverId) reason=\(reason) channelId=\(channelId)")
            emitRoomCommand(runtime: runtime, eventName: GeneratedRealtimeContract.SocketEvent.joinChannel, channelId: channelId)
        }
    }

    private func disconnect(reason: String, serverId: String) {
        queue.async { [weak self] in
            guard let self else { return }
            if serverId.isEmpty || Self.processWideDisconnectReasons.contains(reason) {
                self.disconnectAllLocked(reason: reason)
                self.processIdentity = nil
            } else {
                self.removeRuntimeLocked(serverId: serverId, reason: reason)
            }
        }
    }

    private func disconnectAllLocked(reason: String) {
        for runtime in runtimes.values {
            disconnectRuntimeLocked(runtime, reason: reason, keepSession: false)
        }
        runtimes.removeAll()
    }

    private func removeRuntimeLocked(serverId: String, reason: String) {
        guard let runtime = runtimes.removeValue(forKey: serverId) else { return }
        disconnectRuntimeLocked(runtime, reason: reason, keepSession: false)
    }

    private func disconnectRuntimeLocked(_ runtime: Runtime, reason: String, keepSession: Bool) {
        nextGeneration += 1
        runtime.generation = nextGeneration
        runtime.socket?.removeAllHandlers()
        runtime.socket?.disconnect()
        runtime.socket = nil
        runtime.manager = nil
        runtime.kmpBridge?.detach()
        runtime.kmpSocket?.close()
        runtime.kmpSocket = nil
        runtime.kmpBridge = nil
        runtime.state = .disconnected
        runtime.authRefreshInFlight = false
        if !keepSession {
            runtime.connectParams = nil
            runtime.joinedChannels.removeAll()
            runtime.roomEngine.resetRoomStateForHost()
        }
        record("info", "disconnect serverId=\(runtime.serverId) reason=\(reason) keepSession=\(keepSession)")
        updatePresenceTimerLocked()
    }

    private func requestAuthRefresh(runtime: Runtime, reason: String) {
        guard !runtime.authRefreshInFlight, let params = runtime.connectParams else { return }
        runtime.authRefreshInFlight = true
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.action: "authRefreshRequired",
                "reason": reason,
                GeneratedRealtimeContract.PayloadKey.serverId: params.serverId,
            ]
        )
    }

    private func advanceResumeCursor(
        serverId: String,
        appliedSeq: Int64,
        authorityScopeLeaseId: String,
        authorityPrincipalId: String,
        authorityAuthGeneration: Int64,
        authorityConnectionFamilyId: String
    ) {
        let cleanServerId = clean(serverId)
        guard !cleanServerId.isEmpty, appliedSeq > 0 else { return }
        queue.async { [weak self] in
            guard
                let self,
                let runtime = self.runtimes[cleanServerId],
                let params = runtime.connectParams,
                params.authorityScopeLeaseId == authorityScopeLeaseId,
                params.authorityPrincipalId == authorityPrincipalId,
                params.authorityAuthGeneration == authorityAuthGeneration,
                params.authorityConnectionFamilyId == authorityConnectionFamilyId
            else {
                self?.log("cursor_advance_skip reason=stale_witness serverId=\(cleanServerId) appliedSeq=\(appliedSeq)")
                return
            }
            runtime.resumeLastSeq = max(runtime.resumeLastSeq, appliedSeq)
            self.log(
                "cursor_advance result=applied serverId=\(cleanServerId) " +
                    "requestedSeq=\(appliedSeq) resumeSeq=\(runtime.resumeLastSeq)"
            )
        }
    }

    private func currentRuntime(serverId: String, generation: UInt64) -> Runtime? {
        guard let runtime = runtimes[serverId], runtime.generation == generation else { return nil }
        return runtime
    }

    /// Same server, URL and authority binding; only the access token may differ (a silent refresh).
    private func sameSessionExceptToken(_ left: ConnectParams, _ right: ConnectParams) -> Bool {
        left.serverId == right.serverId &&
            left.socketUrl == right.socketUrl &&
            left.authorityScopeLeaseId == right.authorityScopeLeaseId &&
            left.authorityPrincipalId == right.authorityPrincipalId &&
            left.authorityConnectionFamilyId == right.authorityConnectionFamilyId
    }

    private func sameSessionIdentity(_ left: ConnectParams, _ right: ConnectParams) -> Bool {
        left.serverId == right.serverId &&
            left.accessToken == right.accessToken &&
            left.socketUrl == right.socketUrl &&
            left.authorityScopeLeaseId == right.authorityScopeLeaseId &&
            left.authorityPrincipalId == right.authorityPrincipalId &&
            left.authorityAuthGeneration == right.authorityAuthGeneration &&
            left.authorityConnectionFamilyId == right.authorityConnectionFamilyId
    }

    private func installObserversIfNeeded() {
        guard !didInstallObservers else { return }
        didInstallObservers = true
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRoomNotification(_:)),
            name: Notification.Name(GeneratedRealtimeContract.Notify.roomsEventName),
            object: nil
        )
        // willResignActive hides the shell only; the socket closes on didEnterBackground
        // (see RaftIosRealtimeLifecyclePolicy).
        let policy = RaftIosRealtimeLifecyclePolicy(notificationCenter: .default, target: self)
        policy.install()
        lifecyclePolicy = policy
    }

    private func setShellVisibleLocked(_ visible: Bool) {
        if visible {
            foregroundEpoch += 1
        }
        shellVisible = visible
        sendPresenceActivityLocked(active: visible)
        updatePresenceTimerLocked()
        // task #483: every live shell is foregrounded / backgrounded together.
        let shellIds = RaftIosShellWindowRegistry.allShellInstanceIds()
        for shellId in shellIds.isEmpty
            ? [RaftIosShellWindowContract.legacyShellInstanceId] : shellIds {
            RaftHostVisibilityKt.updateRaftHostVisibility(shellInstanceId: shellId, visible: visible)
        }
        if visible {
            NSLog("thread_lifecycle_host_show epoch=\\(self.foregroundEpoch) runtimes=\\(self.runtimes.count)")
        }
        sendAppLifecycle(event: visible ? "show" : "hide")
    }

    private func sendAppLifecycle(event: String) {
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.action: "appLifecycle",
                "event": event,
                "foregroundEpoch": foregroundEpoch,
                "pageName": "compose_slock_app_shell",
            ]
        )
    }

    /** Kotlin RealtimeSocketEvents.UserScopedServerEvents: events that may name any of the member's servers.
     *  `notification:push` / `notification:retract` go to the user's room for any of their servers; other
     *  workspaces' notifications must still be shown / closed. `notification_prefs:updated` (contract §2.3)
     *  carries its own `serverId`: another workspace's level change must still reach the level store, or that
     *  workspace's levels and badge stay stale until the next sign-in load. */
    private static let userScopedServerEvents: Set<String> = [
        GeneratedRealtimeContract.SocketEvent.appearanceUpdated,
        GeneratedRealtimeContract.SocketEvent.notificationPush,
        GeneratedRealtimeContract.SocketEvent.notificationRetract,
        GeneratedRealtimeContract.SocketEvent.notificationPrefsUpdated,
    ]

    private func payloadServerGate(connectionServerId: String, rawPayload: String) -> (accept: Bool, reason: String, payloadServerId: String) {
        guard
            let data = rawPayload.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data),
            let payload = object as? [String: Any]
        else {
            return (false, "invalid_payload", "")
        }
        guard payload.keys.contains(GeneratedRealtimeContract.PayloadKey.serverId) else {
            return (true, "absent", "")
        }
        let value = payload[GeneratedRealtimeContract.PayloadKey.serverId]
        if value is NSNull { return (true, "absent", "") }
        guard let payloadServerId = value as? String, !clean(payloadServerId).isEmpty else {
            return (false, "invalid_server_id", "")
        }
        let cleanPayloadServerId = clean(payloadServerId)
        return cleanPayloadServerId == clean(connectionServerId)
            ? (true, "matched", cleanPayloadServerId)
            : (false, "mismatch", cleanPayloadServerId)
    }

    private func stateLabel(_ state: State) -> String {
        switch state {
        case .connected: return "Connected"
        case .connecting: return "Connecting"
        case .disconnected: return "Disconnected"
        }
    }

    private func log(_ message: String) {
        NSLog("[SlockIosRealtime] %@", message)
    }

    // task #191: connection lifecycle also goes to raft-diagnostics.jsonl (NSLog alone never
    // reaches a Hands feedback bundle). Low volume, so it is marked critical to survive pressure.
    // The catch-up handshake (sync_horizon_*, sync_resume_*: a few lines per connect) is recorded too,
    // so a bundle shows when a reconnect resumed and how long it took (Hands 1b6dd022).
    private func record(_ level: String, _ message: String) {
        log(message)
        RaftIosDiagnosticsRecordWithContext(level, "SlockIosRealtime", message, 0, false, true)
    }

    private func clean(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func stringValue(_ value: Any?, fallback: String = "") -> String {
        if let value = value as? String { return clean(value) }
        if let value = value as? NSNumber { return value.stringValue }
        return fallback
    }

    private func stringArray(_ value: Any?) -> [String] {
        if let values = value as? [String] { return values }
        if let values = value as? NSArray { return values.compactMap { $0 as? String } }
        return []
    }

    private func int64Value(_ value: Any?) -> Int64 {
        if let value = value as? NSNumber { return value.int64Value }
        if let value = value as? String { return Int64(value) ?? 0 }
        return 0
    }

    private func boolValue(_ value: Any?) -> Bool {
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? String {
            let cleanValue = clean(value).lowercased()
            return cleanValue == "true" || cleanValue == "1" || cleanValue == "yes"
        }
        return false
    }

    private func jsonString(_ value: Any) -> String {
        let jsonObject: Any
        if JSONSerialization.isValidJSONObject(value) {
            jsonObject = value
        } else if let array = value as? [Any], JSONSerialization.isValidJSONObject(array) {
            jsonObject = array
        } else {
            let text = "\(value)"
            if text.hasPrefix("{") || text.hasPrefix("[") { return text }
            jsonObject = [:]
        }
        guard
            let data = try? JSONSerialization.data(withJSONObject: jsonObject, options: []),
            let text = String(data: data, encoding: .utf8)
        else {
            return "{}"
        }
        return text
    }

    private static let processWideDisconnectReasons: Set<String> = [
        "auth_invalid",
        "logout",
        "process_teardown",
        "api_environment_changed",
    ]

    // Shared `RealtimeSyncHorizon.HorizonKey` / `SinceHorizonKey` / `ResetReason` and
    // `RealtimeLocalEvents.CatchUpRequired` (mobile-local frame mapped to `RealtimeRefreshRequired(Home)`).
    private static let syncHorizonKey = "horizon"
    private static let syncSinceHorizonKey = "sinceHorizon"
    private static let syncHorizonResetReason = "sync_resume_horizon_reset"
    private static let catchUpRequiredEvent = "raft:realtime:catch_up_required"

    /// Connection signals this host handles itself (`handleSocketEvent`); not domain events, so they
    /// are not in the contract's `serverWireEvents` (Kotlin's Android client listens to them apart too).
    private static let connectionSignalEvents: [String] = [
        GeneratedRealtimeContract.SocketEvent.roomsJoined,
        GeneratedRealtimeContract.SocketEvent.channelJoined,
        GeneratedRealtimeContract.SocketEvent.channelJoinDenied,
    ]

    /// Contract events this app deliberately does not take. The web's feedback unread total
    /// (`product_feedback:unread`) is never this app's; it takes `product_feedback:app_unread`.
    static let excludedServerWireEvents: Set<String> = [
        GeneratedRealtimeContract.SocketEvent.productFeedbackUnread,
    ]

    /// Generated from compose/realtime-contract/realtime_contract.json, the same list Android uses
    /// (Kotlin `RealtimeSocketEvents.ServerWireEvents`). A new server event is added to the JSON and
    /// regenerated, and reaches iOS without touching this file (it used to be a hand-kept copy that
    /// missed `agent:seen` and `joint-invites:changed`).
    static let serverWireEvents: [String] =
        connectionSignalEvents +
        GeneratedRealtimeContract.SocketEvent.serverWireEvents.filter { !excludedServerWireEvents.contains($0) }
}

extension RaftIosRealtimeHost: RaftIosRealtimeLifecycleTarget {
    func recordRealtimeLifecycle(_ event: String) {
        queue.async { [weak self] in
            guard let self else { return }
            let connected = self.runtimes.values.filter { $0.state == .connected }.count
            self.record("info", "\(event) runtimes=\(self.runtimes.count) connected=\(connected)")
        }
    }

    func setRealtimeShellVisible(_ visible: Bool) {
        queue.async { [weak self] in
            self?.setShellVisibleLocked(visible)
        }
    }

    func disconnectRealtimeForBackground() {
        queue.async { [weak self] in
            guard let self else { return }
            // presence:activity active:false goes out before the sockets close (no-op when the
            // preceding hide already sent it).
            self.shellVisible = false
            self.sendPresenceActivityLocked(active: false)
            for runtime in self.runtimes.values {
                self.disconnectRuntimeLocked(runtime, reason: "background", keepSession: true)
            }
        }
    }

    func reconnectDisconnectedRealtime() {
        queue.async { [weak self] in
            guard let self else { return }
            for runtime in self.runtimes.values where runtime.state == .disconnected {
                if let params = runtime.connectParams { self.openSocketLocked(runtime: runtime, params: params) }
            }
        }
    }
}

/// The Kotlin listener of one `RaftKmpRealtimeSocket` (its callbacks run on the client's own thread); hops
/// into RaftIosRealtimeHost, which re-checks the runtime generation on its queue like the Swift path does.
final class RaftIosKmpSocketBridge: NSObject, RaftKmpRealtimeSocketListener {
    private weak var host: RaftIosRealtimeHost?
    private let serverId: String
    private let generation: UInt64
    private let lock = NSLock()
    private var detached = false

    init(host: RaftIosRealtimeHost, serverId: String, generation: UInt64) {
        self.host = host
        self.serverId = serverId
        self.generation = generation
    }

    func detach() {
        lock.lock()
        detached = true
        lock.unlock()
    }

    private var target: RaftIosRealtimeHost? {
        lock.lock()
        defer { lock.unlock() }
        return detached ? nil : host
    }

    func onConnected(socketId: String, reconnect: Bool) {
        target?.kmpSocketConnected(serverId: serverId, generation: generation, socketId: socketId, reconnect: reconnect)
    }

    func onDisconnected(reason: String) {
        target?.kmpSocketDisconnected(serverId: serverId, generation: generation, reason: reason)
    }

    func onEvent(eventName: String, rawPayload: String, ackId: Int64) {
        target?.kmpSocketEvent(serverId: serverId, generation: generation, eventName: eventName, rawPayload: rawPayload, ackId: ackId)
    }

    func onConnectError(kind: String, message: String, terminal: Bool, requiresTokenRefresh: Bool) {
        target?.kmpSocketConnectError(
            serverId: serverId, generation: generation, kind: kind, terminal: terminal, requiresTokenRefresh: requiresTokenRefresh
        )
    }

    func onReconnectScheduled(attempt: Int32, delayMillis: Int64, reason: String) {
        target?.kmpSocketLog("kmp_socket_reconnect_scheduled serverId=\(serverId) attempt=\(attempt) delayMs=\(delayMillis) reason=\(reason)")
    }

    func onLog(message: String) {
        target?.kmpSocketLog("serverId=\(serverId) \(message)")
    }
}

/// One `emitWithAck` result from the common client (10 s timeout, like the Swift path's `timingOut(after: 10)`).
final class RaftIosKmpAckCallback: NSObject, RaftKmpRealtimeAckCallback {
    private let handler: (String?, String) -> Void

    init(_ handler: @escaping (String?, String) -> Void) {
        self.handler = handler
    }

    func onAckResult(payload: String?, status: String) {
        handler(payload, status)
    }
}
