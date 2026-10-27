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

    var statusLabel: String {
        queue.sync {
            if runtimes.values.contains(where: { $0.state == .connected }) { return "Connected" }
            if runtimes.values.contains(where: { $0.state == .connecting }) { return "Connecting" }
            return "Disconnected"
        }
    }

    private override init() {
        super.init()
        installObserversIfNeeded()
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
    }

    /// Asks this socket for a starting horizon while none is held. Never waited for: the ack is used only
    /// if it arrives before any baseline history request of this server was sent (shared
    /// `RealtimeBaselineHistoryRequests`); a late, missing (older server) or error ack leaves none, so the
    /// first resume runs as before and its response provides the next one.
    private func requestStartingHorizon(runtime: Runtime, generation: UInt64) {
        guard runtime.horizon == nil else { return }
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
        guard gate.accept else {
            log(
                "raw_event_skip reason=payload_server_\(gate.reason) connectionServerId=\(params.serverId) " +
                    "payloadServerId=\(gate.payloadServerId) eventName=\(eventName)"
            )
            return
        }
        postRawEvent(runtime: runtime, params: params, eventName: eventName, rawPayload: rawPayload)
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

    private func emitRoomCommand(runtime: Runtime, eventName: String, channelId: String) {
        // The server's join:channel / leave:channel handlers take the channel id itself, as Android
        // sends it. The previous {"channelId": …} object made both a silent no-op on iOS. Which
        // rooms get a leave at all is decided in shared (member channels never do).
        guard runtime.state == .connected else {
            log("emit_deferred serverId=\(runtime.serverId) eventName=\(eventName)")
            return
        }
        if let socket = runtime.socket {
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
        if let socket = runtime.socket {
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
        runtime.state = .disconnected
        runtime.authRefreshInFlight = false
        if !keepSession {
            runtime.connectParams = nil
            runtime.joinedChannels.removeAll()
            runtime.roomEngine.resetRoomStateForHost()
        }
        record("info", "disconnect serverId=\(runtime.serverId) reason=\(reason) keepSession=\(keepSession)")
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

    private static let serverWireEvents: [String] = [
        GeneratedRealtimeContract.SocketEvent.roomsJoined,
        GeneratedRealtimeContract.SocketEvent.channelJoined,
        GeneratedRealtimeContract.SocketEvent.channelJoinDenied,
        GeneratedRealtimeContract.SocketEvent.syncResumeResponse,
        GeneratedRealtimeContract.SocketEvent.heartbeat,
        GeneratedRealtimeContract.SocketEvent.messageNew,
        GeneratedRealtimeContract.SocketEvent.messageUpdated,
        GeneratedRealtimeContract.SocketEvent.directMessageNew,
        GeneratedRealtimeContract.SocketEvent.threadUpdated,
        GeneratedRealtimeContract.SocketEvent.channelUpdated,
        GeneratedRealtimeContract.SocketEvent.channelMembersUpdated,
        GeneratedRealtimeContract.SocketEvent.scopeReadUpdated,
        GeneratedRealtimeContract.SocketEvent.taskCreated,
        GeneratedRealtimeContract.SocketEvent.taskUpdated,
        GeneratedRealtimeContract.SocketEvent.taskDeleted,
        GeneratedRealtimeContract.SocketEvent.agentActivity,
        GeneratedRealtimeContract.SocketEvent.agentTyping,
        GeneratedRealtimeContract.SocketEvent.agentSession,
        GeneratedRealtimeContract.SocketEvent.agentCreated,
        GeneratedRealtimeContract.SocketEvent.agentDeleted,
        GeneratedRealtimeContract.SocketEvent.machineStatus,
        GeneratedRealtimeContract.SocketEvent.machineCapabilities,
        GeneratedRealtimeContract.SocketEvent.machineUpdated,
        GeneratedRealtimeContract.SocketEvent.computerUpgradeProgress,
        GeneratedRealtimeContract.SocketEvent.computerUpgradeDone,
        GeneratedRealtimeContract.SocketEvent.machineUpgradeRequest,
        GeneratedRealtimeContract.SocketEvent.computerRestartDone,
        GeneratedRealtimeContract.SocketEvent.daemonStatus,
        GeneratedRealtimeContract.SocketEvent.serverMemberAdded,
        GeneratedRealtimeContract.SocketEvent.serverMemberUpdated,
        GeneratedRealtimeContract.SocketEvent.serverMemberLeft,
        GeneratedRealtimeContract.SocketEvent.serverMemberRemoved,
        GeneratedRealtimeContract.SocketEvent.serverMembershipRemoved,
        GeneratedRealtimeContract.SocketEvent.serverDeleted,
        GeneratedRealtimeContract.SocketEvent.serverPlanUpdated,
        GeneratedRealtimeContract.SocketEvent.serverFeaturesChanged,
        GeneratedRealtimeContract.SocketEvent.announcementNew,
        GeneratedRealtimeContract.SocketEvent.reminderFired,
        GeneratedRealtimeContract.SocketEvent.reminderScheduled,
        GeneratedRealtimeContract.SocketEvent.reminderCanceled,
        "thread:followers-updated",
        "read_state:updated",
        "read_state:updated_bulk",
        "notification_prefs:updated",
        "pinned:updated",
        "notification:push",
    ]
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
