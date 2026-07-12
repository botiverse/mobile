import Foundation
import SocketIO
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

    private struct ConnectParams {
        let serverId: String
        let accessToken: String
        let socketUrl: String
        let lastSeq: Int64
    }

    private let queue = DispatchQueue(label: "build.raft.app.ios.realtime.host")
    private var manager: SocketManager?
    private var socket: SocketIOClient?
    private var connectParams: ConnectParams?
    private var state: State = .disconnected
    private var joinedChannels: Set<String> = []
    // Room-command semantics (join dedupe, leave bookkeeping, force rejoin)
    // live in the shared Kotlin engine — same decision path as Android/OHOS.
    // joinedChannels stays only as the host mirror used to resend joins on
    // reconnect, updated exclusively from decision.joinedChannelAction.
    private let roomEngine = SlockRealtimeAppScopeEngine()
    private var authRefreshInFlight = false
    private var didInstallObservers = false

    var statusLabel: String {
        queue.sync {
            switch state {
            case .connected:
                return "Connected"
            case .connecting:
                return "Connecting"
            case .disconnected:
                return "Disconnected"
            }
        }
    }

    private override init() {
        super.init()
        installObserversIfNeeded()
    }

    func handleCommand(_ command: NSDictionary) {
        let action = stringValue(command["action"])
        switch action {
        case "connect":
            connect(
                serverId: stringValue(command["serverId"]),
                accessToken: stringValue(command["accessToken"]),
                socketUrl: stringValue(command["socketUrl"]),
                lastSeq: int64Value(command["lastSeq"]),
                forceReconnect: boolValue(command["forceReconnect"])
            )
        case "disconnect":
            disconnect(reason: stringValue(command["reason"], fallback: "shared_disconnect"))
        case "authRefreshFinished":
            queue.async { [weak self] in
                guard let self else { return }
                self.authRefreshInFlight = false
                let reason = self.stringValue(command["reason"])
                let success = self.boolValue(command["success"])
                self.log("auth_refresh_finished reason=\(reason) success=\(success)")
            }
        default:
            log("unsupported_action action=\(action)")
        }
    }

    func connect(
        serverId: String,
        accessToken: String,
        socketUrl: String,
        lastSeq: Int64,
        forceReconnect: Bool
    ) {
        let cleanServerId = serverId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanAccessToken = accessToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSocketUrl = socketUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanServerId.isEmpty, !cleanAccessToken.isEmpty else {
            log("connect_skip reason=missing_server_or_token")
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            let next = ConnectParams(
                serverId: cleanServerId,
                accessToken: cleanAccessToken,
                socketUrl: cleanSocketUrl.isEmpty ? "https://api.raft.build" : cleanSocketUrl,
                lastSeq: lastSeq
            )
            let sameSession =
                self.connectParams?.serverId == next.serverId &&
                self.connectParams?.accessToken == next.accessToken
            if sameSession && !forceReconnect && self.state != .disconnected {
                self.log("connect_skip reason=same_session state=\(self.stateLabel)")
                return
            }
            if !sameSession {
                self.joinedChannels.removeAll()
                self.roomEngine.resetRoomStateForHost()
            }
            self.connectParams = next
            self.authRefreshInFlight = false
            self.openSocket(params: next)
        }
    }

    func disconnect(reason: String) {
        queue.async { [weak self] in
            self?.disconnectLocked(reason: reason, keepSession: false)
        }
    }

    private func openSocket(params: ConnectParams) {
        guard let url = URL(string: params.socketUrl) else {
            log("connect_skip reason=invalid_url")
            return
        }
        disconnectLocked(reason: "reconnect", keepSession: true)
        state = .connecting
        let manager = SocketManager(
            socketURL: url,
            config: [
                .log(false),
                .compress,
                .forceWebsockets(true),
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
        self.manager = manager
        self.socket = socket
        installSocketHandlers(socket)
        log("connect_start transport=swift_socketio serverId=\(params.serverId)")
        socket.connect(withPayload: [
            "token": params.accessToken,
            "serverId": params.serverId,
            "clientKind": "mobile",
        ])
    }

    private func installSocketHandlers(_ socket: SocketIOClient) {
        socket.on(clientEvent: .connect) { [weak self] _, _ in
            self?.queue.async {
                self?.state = .connected
                self?.log("socket_io_connected")
                self?.rejoinChannels(reason: "connect")
            }
        }
        socket.on(clientEvent: .disconnect) { [weak self] data, _ in
            self?.queue.async {
                self?.state = .disconnected
                self?.log("socket_io_disconnected reason=\(data.first.map { "\($0)" } ?? "")")
            }
        }
        socket.on(clientEvent: .error) { [weak self] data, _ in
            self?.queue.async {
                self?.state = .disconnected
                self?.log("socket_io_error data=\(data)")
                self?.requestAuthRefresh(reason: "connect_error")
            }
        }
        for eventName in Self.serverWireEvents {
            socket.on(eventName) { [weak self] data, _ in
                self?.queue.async {
                    self?.handleSocketEvent(eventName: eventName, data: data)
                }
            }
        }
    }

    private func handleSocketEvent(eventName: String, data: [Any]) {
        if eventName == GeneratedRealtimeContract.SocketEvent.roomsJoined {
            log("rooms_joined joinedRooms=\(joinedChannels.count) lastSeq=\(connectParams?.lastSeq ?? 0)")
            if let lastSeq = connectParams?.lastSeq, lastSeq > 0 {
                emit(eventName: GeneratedRealtimeContract.SocketEvent.syncResume, payload: ["lastSeq": lastSeq])
            }
            return
        }
        let rawPayload = jsonString(data.first ?? [:])
        guard let serverId = connectParams?.serverId, !serverId.isEmpty else {
            log("raw_event_skip reason=missing_server eventName=\(eventName)")
            return
        }
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.serverId: serverId,
                GeneratedRealtimeContract.PayloadKey.eventName: eventName,
                GeneratedRealtimeContract.PayloadKey.rawPayload: rawPayload,
            ]
        )
    }

    @objc private func handleRoomNotification(_ notification: Notification) {
        let userInfo = notification.userInfo ?? [:]
        let action = stringValue(userInfo[GeneratedRealtimeContract.PayloadKey.action])
        let channelId = stringValue(userInfo[GeneratedRealtimeContract.PayloadKey.channelId])
        guard !channelId.isEmpty else {
            log("room_command_skip reason=missing_channel_id action=\(action)")
            return
        }
        queue.async { [weak self] in
            guard let self else { return }
            let decision = self.roomEngine.roomCommandDecisionForHost(
                action: action,
                channelIdValue: channelId,
                reason: "notify"
            )
            if !decision.logMessage.isEmpty {
                self.log(decision.logMessage)
            }
            if let decidedChannelId = decision.channelId?.value, !decidedChannelId.isEmpty {
                if decision.joinedChannelAction == "join" {
                    self.joinedChannels.insert(decidedChannelId)
                } else if decision.joinedChannelAction == "leave" {
                    self.joinedChannels.remove(decidedChannelId)
                }
            }
            guard decision.shouldEmit, let eventName = decision.eventName, let payload = decision.payload else {
                return
            }
            self.emitRoomCommand(eventName: eventName, channelId: payload)
        }
    }

    private func emitRoomCommand(eventName: String, channelId: String) {
        emit(eventName: eventName, payload: ["channelId": channelId])
    }

    private func emit(eventName: String, payload: [String: Any]) {
        guard state == .connected, let socket else {
            log("emit_deferred eventName=\(eventName)")
            return
        }
        socket.emit(eventName, payload)
    }

    private func rejoinChannels(reason: String) {
        for channelId in joinedChannels {
            log("rejoin_channel reason=\(reason) channelId=\(channelId)")
            emitRoomCommand(eventName: GeneratedRealtimeContract.SocketEvent.joinChannel, channelId: channelId)
        }
    }

    private func disconnectLocked(reason: String, keepSession: Bool) {
        socket?.removeAllHandlers()
        socket?.disconnect()
        socket = nil
        manager = nil
        state = .disconnected
        authRefreshInFlight = false
        if !keepSession {
            connectParams = nil
            joinedChannels.removeAll()
            roomEngine.resetRoomStateForHost()
        }
        log("disconnect reason=\(reason) keepSession=\(keepSession)")
    }

    private func requestAuthRefresh(reason: String) {
        guard !authRefreshInFlight, let serverId = connectParams?.serverId, !serverId.isEmpty else {
            return
        }
        authRefreshInFlight = true
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.action: "authRefreshRequired",
                "reason": reason,
                GeneratedRealtimeContract.PayloadKey.serverId: serverId,
            ]
        )
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
            if self.state == .disconnected, let params = self.connectParams {
                self.openSocket(params: params)
            }
            SlockHostVisibilityKt.updateSlockHostVisibility(
                shellInstanceId: "legacy-app-shell",
                visible: true
            )
            self.sendAppLifecycle(event: "show")
        }
    }

    @objc private func appWillResignActive() {
        queue.async { [weak self] in
            SlockHostVisibilityKt.updateSlockHostVisibility(
                shellInstanceId: "legacy-app-shell",
                visible: false
            )
            self?.sendAppLifecycle(event: "hide")
            self?.disconnectLocked(reason: "background", keepSession: true)
        }
    }

    private func sendAppLifecycle(event: String) {
        NotificationCenter.default.post(
            name: Notification.Name(GeneratedRealtimeContract.Notify.eventName),
            object: nil,
            userInfo: [
                GeneratedRealtimeContract.PayloadKey.action: "appLifecycle",
                "event": event,
                "pageName": "compose_slock_app_shell",
            ]
        )
    }

    private var stateLabel: String {
        switch state {
        case .connected:
            return "Connected"
        case .connecting:
            return "Connecting"
        case .disconnected:
            return "Disconnected"
        }
    }

    private func log(_ message: String) {
        NSLog("[SlockIosRealtime] %@", message)
    }

    private func stringValue(_ value: Any?, fallback: String = "") -> String {
        if let value = value as? String {
            return value.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let value = value as? NSNumber {
            return value.stringValue
        }
        return fallback
    }

    private func int64Value(_ value: Any?) -> Int64 {
        if let value = value as? NSNumber {
            return value.int64Value
        }
        if let value = value as? String {
            return Int64(value) ?? 0
        }
        return 0
    }

    private func boolValue(_ value: Any?) -> Bool {
        if let value = value as? NSNumber {
            return value.boolValue
        }
        if let value = value as? String {
            let clean = value.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
            return clean == "true" || clean == "1" || clean == "yes"
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
            if text.hasPrefix("{") || text.hasPrefix("[") {
                return text
            }
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
    ]
}
