import Foundation
import SocketIO
import Starscream
import UIKit
import shared

@objcMembers
final class SlockIosRealtimeHost: NSObject {
    static let shared = SlockIosRealtimeHost()

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
        let roomEngine = SlockRealtimeAppScopeEngine()
        var manager: SocketManager?
        var socket: SocketIOClient?
        var connectParams: ConnectParams?
        var state: State = .disconnected
        var joinedChannels: Set<String> = []
        var authRefreshInFlight = false
        var generation: UInt64 = 0
        var resumeLastSeq: Int64 = 0
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
        if !sameSession {
            runtime.joinedChannels.removeAll()
            runtime.roomEngine.resetRoomStateForHost()
            runtime.resumeLastSeq = max(0, params.lastSeq)
        } else {
            runtime.resumeLastSeq = max(runtime.resumeLastSeq, params.lastSeq)
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

    private func installSocketHandlers(_ socket: SocketIOClient, serverId: String, generation: UInt64) {
        socket.on(clientEvent: .connect) { [weak self] _, _ in
            self?.queue.async {
                guard let self, let runtime = self.currentRuntime(serverId: serverId, generation: generation) else { return }
                runtime.state = .connected
                self.nextPresenceTransportEpoch += 1
                runtime.presenceTransportEpoch = self.nextPresenceTransportEpoch
                self.record(
                    "info",
                    "socket_io_connected serverId=\(serverId) generation=\(generation) " +
                        "presenceEpoch=\(runtime.presenceTransportEpoch)"
                )
                self.postPresenceTransportConnected(runtime: runtime)
                self.rejoinChannels(runtime: runtime, reason: "connect")
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
                    self.handleSocketEvent(runtime: runtime, eventName: eventName, data: data)
                }
            }
        }
    }

    private func handleSocketEvent(runtime: Runtime, eventName: String, data: [Any]) {
        if eventName == GeneratedRealtimeContract.SocketEvent.roomsJoined {
            log(
                "rooms_joined serverId=\(runtime.serverId) joinedRooms=\(runtime.joinedChannels.count) " +
                    "lastSeq=\(runtime.resumeLastSeq)"
            )
            if runtime.resumeLastSeq > 0 {
                emit(
                    runtime: runtime,
                    eventName: GeneratedRealtimeContract.SocketEvent.syncResume,
                    payload: ["lastSeq": runtime.resumeLastSeq]
                )
            }
            return
        }
        guard let params = runtime.connectParams else { return }
        let rawPayload = jsonString(data.first ?? [:])
        let gate = payloadServerGate(connectionServerId: params.serverId, rawPayload: rawPayload)
        guard gate.accept else {
            log(
                "raw_event_skip reason=payload_server_\(gate.reason) connectionServerId=\(params.serverId) " +
                    "payloadServerId=\(gate.payloadServerId) eventName=\(eventName)"
            )
            return
        }
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

    private func postPresenceTransportConnected(runtime: Runtime) {
        guard let params = runtime.connectParams, runtime.presenceTransportEpoch > 0 else { return }
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.action: "presenceTransportConnected",
                GeneratedRealtimeContract.PayloadKey.serverId: params.serverId,
                "authorityScopeLeaseId": params.authorityScopeLeaseId,
                "authorityPrincipalId": params.authorityPrincipalId,
                "authorityAuthGeneration": params.authorityAuthGeneration,
                "authorityConnectionFamilyId": params.authorityConnectionFamilyId,
                "presenceTransportEpoch": runtime.presenceTransportEpoch,
            ]
        )
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
        emit(runtime: runtime, eventName: eventName, payload: ["channelId": channelId])
    }

    private func emit(runtime: Runtime, eventName: String, payload: [String: Any]) {
        guard runtime.state == .connected, let socket = runtime.socket else {
            log("emit_deferred serverId=\(runtime.serverId) eventName=\(eventName)")
            return
        }
        socket.emit(eventName, payload)
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
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(appWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
    }

    @objc private func appDidBecomeActive() {
        queue.async { [weak self] in
            guard let self else { return }
            for runtime in self.runtimes.values where runtime.state == .disconnected {
                if let params = runtime.connectParams { self.openSocketLocked(runtime: runtime, params: params) }
            }
            self.foregroundEpoch += 1
            SlockHostVisibilityKt.updateSlockHostVisibility(shellInstanceId: "legacy-app-shell", visible: true)
            NSLog("thread_lifecycle_host_show epoch=\\(self.foregroundEpoch) runtimes=\\(self.runtimes.count)")
            self.sendAppLifecycle(event: "show")
        }
    }

    @objc private func appWillResignActive() {
        queue.async { [weak self] in
            guard let self else { return }
            SlockHostVisibilityKt.updateSlockHostVisibility(shellInstanceId: "legacy-app-shell", visible: false)
            self.sendAppLifecycle(event: "hide")
            for runtime in self.runtimes.values {
                self.disconnectRuntimeLocked(runtime, reason: "background", keepSession: true)
            }
        }
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
    private func record(_ level: String, _ message: String) {
        log(message)
        SlockIosDiagnosticsRecordWithContext(level, "SlockIosRealtime", message, 0, false, true)
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

    private static let serverWireEvents: [String] = [
        GeneratedRealtimeContract.SocketEvent.roomsJoined,
        GeneratedRealtimeContract.SocketEvent.syncResumeResponse,
        GeneratedRealtimeContract.SocketEvent.heartbeat,
        GeneratedRealtimeContract.SocketEvent.messageNew,
        GeneratedRealtimeContract.SocketEvent.messageUpdated,
        GeneratedRealtimeContract.SocketEvent.directMessageNew,
        GeneratedRealtimeContract.SocketEvent.threadUpdated,
        GeneratedRealtimeContract.SocketEvent.channelUpdated,
        GeneratedRealtimeContract.SocketEvent.channelMembersUpdated,
        GeneratedRealtimeContract.SocketEvent.taskCreated,
        GeneratedRealtimeContract.SocketEvent.taskUpdated,
        GeneratedRealtimeContract.SocketEvent.taskDeleted,
        GeneratedRealtimeContract.SocketEvent.agentActivity,
        GeneratedRealtimeContract.SocketEvent.agentSession,
        GeneratedRealtimeContract.SocketEvent.agentCreated,
        GeneratedRealtimeContract.SocketEvent.agentDeleted,
        GeneratedRealtimeContract.SocketEvent.machineStatus,
        GeneratedRealtimeContract.SocketEvent.machineCapabilities,
        GeneratedRealtimeContract.SocketEvent.machineUpdated,
        GeneratedRealtimeContract.SocketEvent.computerUpgradeProgress,
        GeneratedRealtimeContract.SocketEvent.computerUpgradeDone,
        GeneratedRealtimeContract.SocketEvent.daemonStatus,
        GeneratedRealtimeContract.SocketEvent.serverMemberAdded,
        GeneratedRealtimeContract.SocketEvent.serverMemberUpdated,
        GeneratedRealtimeContract.SocketEvent.serverMemberLeft,
        GeneratedRealtimeContract.SocketEvent.serverMemberRemoved,
        GeneratedRealtimeContract.SocketEvent.serverMembershipRemoved,
        GeneratedRealtimeContract.SocketEvent.serverDeleted,
        GeneratedRealtimeContract.SocketEvent.serverPlanUpdated,
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
