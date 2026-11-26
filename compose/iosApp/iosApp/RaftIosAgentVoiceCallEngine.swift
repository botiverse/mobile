import AVFAudio
import AVFoundation
import Foundation
#if canImport(LiveKitClient)
import LiveKitClient
#elseif canImport(LiveKit)
import LiveKit
#endif
import shared

/// Pure policy helpers for iOS Agent Voice calls (covered by `RaftIosAgentVoiceCallEngineTests`).
enum RaftIosAgentVoiceCallPolicy {
    static let captionTopic = "caption"
    static let micPermissionDeniedReason = "permission_denied"
    static let micUnavailableReason = "unavailable"

    /// Extracts UTF-8 caption JSON only when the LiveKit data packet topic is `"caption"`.
    static func extractCaptionJson(topic: String, data: Data) -> String? {
        guard topic == captionTopic, !data.isEmpty else { return nil }
        guard let decoded = String(data: data, encoding: .utf8), !decoded.isEmpty else {
            return nil
        }
        return decoded
    }

    /// Maps LiveKit Swift disconnect errors to the shared `RaftAgentVoiceDisconnectReason` tokens.
    static func mapDisconnectReason(
        errorType: LiveKitErrorType?,
        clientInitiated: Bool
    ) -> String {
        if clientInitiated || errorType == .cancelled {
            return RaftAgentVoiceDisconnectReason.shared.CLIENT_INITIATED
        }
        switch errorType {
        case .roomDeleted, .participantRemoved:
            return RaftAgentVoiceDisconnectReason.shared.SERVER_CLOSED
        default:
            return RaftAgentVoiceDisconnectReason.shared.FAILED
        }
    }

    /// Returns true when at least one speaking participant is remote (not the local caller).
    static func hasRemoteSpeakingParticipant(remoteFlags: [Bool]) -> Bool {
        remoteFlags.contains(true)
    }

    static func hasRemoteSpeakingParticipant(_ participants: [Participant]) -> Bool {
        hasRemoteSpeakingParticipant(remoteFlags: participants.map { $0 is RemoteParticipant })
    }
}

/// Native iOS LiveKit audio room engine for Agent Voice calls.
///
/// Registered at app startup (`iOSApp.init`) via `RaftIosAgentVoiceCallEngine.install()`.
final class RaftIosAgentVoiceCallEngine: NSObject, RaftAgentVoiceCallEngine, RoomDelegate, @unchecked Sendable {
    private final class ActiveSession {
        let generation: UInt64
        let callId: String
        let url: String
        let listener: RaftAgentVoiceEngineListener
        var latestToken: String
        var muted: Bool
        var speakerOn: Bool
        var connected: Bool = false
        var micBlocked: Bool = false
        var clientDisconnecting: Bool = false
        var terminalReported: Bool = false
        var room: Room?
        var connectTask: Task<Void, Never>?
        var micTask: Task<Void, Never>?

        init(
            generation: UInt64,
            callId: String,
            url: String,
            token: String,
            muted: Bool,
            speakerOn: Bool,
            listener: RaftAgentVoiceEngineListener
        ) {
            self.generation = generation
            self.callId = callId
            self.url = url
            self.latestToken = token
            self.muted = muted
            self.speakerOn = speakerOn
            self.listener = listener
        }
    }

    private static let sharedInstance = RaftIosAgentVoiceCallEngine()

    private let lock = NSLock()
    private var generationCounter: UInt64 = 0
    private var activeSession: ActiveSession?

    static func install() {
        RaftAgentVoiceCallEngineRegistry.shared.register(engine: sharedInstance)
    }

    var isSupported: Bool { true }

    var latestTokenForTests: String? {
        lock.lock()
        defer { lock.unlock() }
        return activeSession?.latestToken
    }

    var isMutedForTests: Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeSession?.muted == true
    }

    var isSpeakerOnForTests: Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeSession?.speakerOn == true
    }

    // MARK: - RaftAgentVoiceCallEngine

    func connect(
        callId: String,
        url: String,
        token: String,
        muted: Bool,
        speakerOn: Bool,
        listener: RaftAgentVoiceEngineListener
    ) {
        let previousRoom: Room?
        let session: ActiveSession

        lock.lock()
        generationCounter &+= 1
        session = ActiveSession(
            generation: generationCounter,
            callId: callId,
            url: url,
            token: token,
            muted: muted,
            speakerOn: speakerOn,
            listener: listener
        )
        let previous = activeSession
        activeSession = session
        previous?.clientDisconnecting = true
        previous?.terminalReported = true
        previous?.connectTask?.cancel()
        previous?.micTask?.cancel()
        previousRoom = previous?.room
        previous?.room = nil
        lock.unlock()

        if let previousRoom {
            Task {
                await previousRoom.disconnect()
            }
        }

        AudioManager.shared.isSpeakerOutputPreferred = speakerOn
        listener.onConnectionState(state: RaftAgentVoiceEngineState.shared.CONNECTING)

        let connectTask = Task { [weak self] in
            guard let self else { return }
            await self.runConnect(session: session)
        }

        lock.lock()
        if activeSession === session {
            session.connectTask = connectTask
        } else {
            connectTask.cancel()
        }
        lock.unlock()
    }

    func setMuted(muted: Bool) {
        let target: (ActiveSession, Room)?
        lock.lock()
        if let session = activeSession {
            session.muted = muted
            if session.connected, let room = session.room {
                session.micTask?.cancel()
                target = (session, room)
            } else {
                target = nil
            }
        } else {
            target = nil
        }
        lock.unlock()

        guard let (session, room) = target else { return }
        let micTask = Task { [weak self] in
            guard let self else { return }
            await self.applyMicrophoneState(session: session, room: room, enabled: !muted)
        }
        lock.lock()
        if activeSession === session {
            session.micTask = micTask
        } else {
            micTask.cancel()
        }
        lock.unlock()
    }

    func setSpeakerOn(speakerOn: Bool) {
        lock.lock()
        let hasActive = activeSession != nil
        activeSession?.speakerOn = speakerOn
        lock.unlock()

        if hasActive {
            AudioManager.shared.isSpeakerOutputPreferred = speakerOn
        }
    }

    func updateToken(token: String) {
        guard !token.isEmpty else { return }
        // LiveKit's SignalClient handles server-pushed token refreshes on live rooms; retain
        // the latest token for any pending connect/rejoin.
        lock.lock()
        activeSession?.latestToken = token
        lock.unlock()
    }

    func disconnect() {
        let roomToDisconnect: Room?
        lock.lock()
        let previous = activeSession
        activeSession = nil
        previous?.clientDisconnecting = true
        previous?.terminalReported = true
        previous?.connectTask?.cancel()
        previous?.micTask?.cancel()
        roomToDisconnect = previous?.room
        previous?.room = nil
        lock.unlock()

        AudioManager.shared.isSpeakerOutputPreferred = true
        if let roomToDisconnect {
            Task {
                await roomToDisconnect.disconnect()
            }
        }
    }

    // MARK: - Connection flow

    private func runConnect(session: ActiveSession) async {
        let permissionGranted = await Self.requestMicrophonePermission()
        guard isCurrent(session) else { return }

        if !permissionGranted {
            session.listener.onMicError(reason: RaftIosAgentVoiceCallPolicy.micPermissionDeniedReason)
            if isCurrent(session) {
                reportTerminalDisconnect(
                    session: session,
                    reason: RaftAgentVoiceDisconnectReason.shared.FAILED,
                    detail: RaftIosAgentVoiceCallPolicy.micPermissionDeniedReason
                )
            }
            return
        }

        let connectOptions = ConnectOptions(autoSubscribe: true, enableMicrophone: false)
        let room = Room(delegate: self, connectOptions: connectOptions)

        lock.lock()
        guard activeSession === session else {
            lock.unlock()
            return
        }
        session.room = room
        let currentToken = session.latestToken
        let currentSpeakerOn = session.speakerOn
        lock.unlock()

        AudioManager.shared.isSpeakerOutputPreferred = currentSpeakerOn

        do {
            try await room.connect(
                url: session.url,
                token: currentToken,
                connectOptions: connectOptions
            )
        } catch {
            let lkError = error as? LiveKitError
            let clientInitiated = isClientDisconnecting(session)
            let reason = RaftIosAgentVoiceCallPolicy.mapDisconnectReason(
                errorType: lkError?.type,
                clientInitiated: clientInitiated
            )
            reportTerminalDisconnect(
                session: session,
                reason: reason,
                detail: error.localizedDescription
            )
            await room.disconnect()
            return
        }

        let postConnectState: (muted: Bool, speakerOn: Bool)?
        lock.lock()
        if activeSession === session {
            session.connected = true
            postConnectState = (session.muted, session.speakerOn)
        } else {
            postConnectState = nil
        }
        lock.unlock()

        guard let postConnectState else {
            await room.disconnect()
            return
        }

        AudioManager.shared.isSpeakerOutputPreferred = postConnectState.speakerOn
        await applyMicrophoneState(session: session, room: room, enabled: !postConnectState.muted)
    }

    private func applyMicrophoneState(session: ActiveSession, room: Room, enabled: Bool) async {
        guard isCurrent(session) else { return }
        do {
            try await room.localParticipant.setMicrophone(enabled: enabled)
            if enabled {
                let shouldClear: Bool
                lock.lock()
                if activeSession === session && session.micBlocked {
                    session.micBlocked = false
                    shouldClear = true
                } else {
                    shouldClear = false
                }
                lock.unlock()
                if shouldClear {
                    session.listener.onMicError(reason: "")
                }
            }
        } catch {
            guard isCurrent(session) else { return }
            lock.lock()
            if activeSession === session {
                session.micBlocked = true
            }
            lock.unlock()
            session.listener.onMicError(reason: RaftIosAgentVoiceCallPolicy.micUnavailableReason)
        }
    }

    private static func requestMicrophonePermission() async -> Bool {
        if #available(iOS 17.0, *) {
            switch AVAudioApplication.shared.recordPermission {
            case .granted:
                return true
            case .denied:
                return false
            case .undetermined:
                return await AVAudioApplication.requestRecordPermission()
            @unknown default:
                return false
            }
        } else {
            let session = AVAudioSession.sharedInstance()
            switch session.recordPermission {
            case .granted:
                return true
            case .denied:
                return false
            case .undetermined:
                return await withCheckedContinuation { continuation in
                    session.requestRecordPermission { granted in
                        continuation.resume(returning: granted)
                    }
                }
            @unknown default:
                return false
            }
        }
    }

    // MARK: - RoomDelegate

    func roomDidConnect(_ room: Room) {
        guard let session = sessionFor(room) else { return }
        lock.lock()
        session.connected = true
        lock.unlock()
        session.listener.onConnectionState(state: RaftAgentVoiceEngineState.shared.CONNECTED)
    }

    func roomIsReconnecting(_ room: Room) {
        guard let session = sessionFor(room) else { return }
        session.listener.onConnectionState(state: RaftAgentVoiceEngineState.shared.RECONNECTING)
    }

    func room(_ room: Room, didStartReconnectWithMode reconnectMode: ReconnectMode) {
        guard let session = sessionFor(room) else { return }
        session.listener.onConnectionState(state: RaftAgentVoiceEngineState.shared.RECONNECTING)
    }

    func roomDidReconnect(_ room: Room) {
        guard let session = sessionFor(room) else { return }
        session.listener.onConnectionState(state: RaftAgentVoiceEngineState.shared.CONNECTED)
    }

    func room(_ room: Room, didCompleteReconnectWithMode reconnectMode: ReconnectMode) {
        guard let session = sessionFor(room) else { return }
        session.listener.onConnectionState(state: RaftAgentVoiceEngineState.shared.CONNECTED)
    }

    func room(_ room: Room, didUpdateSpeakingParticipants participants: [Participant]) {
        guard let session = sessionFor(room) else { return }
        let agentSpeaking = RaftIosAgentVoiceCallPolicy.hasRemoteSpeakingParticipant(participants)
        session.listener.onAgentSpeaking(speaking: agentSpeaking)
    }

    func room(
        _ room: Room,
        participant: RemoteParticipant?,
        didReceiveData data: Data,
        forTopic topic: String,
        encryptionType: EncryptionType
    ) {
        guard let session = sessionFor(room) else { return }
        if let jsonText = RaftIosAgentVoiceCallPolicy.extractCaptionJson(topic: topic, data: data) {
            session.listener.onCaptionData(jsonText: jsonText)
        }
    }

    func room(_ room: Room, didDisconnectWithError error: LiveKitError?) {
        guard let session = sessionFor(room) else { return }
        let clientInitiated = isClientDisconnecting(session)
        let reason = RaftIosAgentVoiceCallPolicy.mapDisconnectReason(
            errorType: error?.type,
            clientInitiated: clientInitiated
        )
        reportTerminalDisconnect(
            session: session,
            reason: reason,
            detail: error?.localizedDescription
        )
    }

    // MARK: - Session helpers

    private func isCurrent(_ session: ActiveSession) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeSession === session
    }

    private func isClientDisconnecting(_ session: ActiveSession) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return session.clientDisconnecting
    }

    private func sessionFor(_ room: Room) -> ActiveSession? {
        lock.lock()
        defer { lock.unlock() }
        guard let session = activeSession, session.room === room else { return nil }
        return session
    }

    private func reportTerminalDisconnect(
        session: ActiveSession,
        reason: String,
        detail: String?
    ) {
        let shouldReport: Bool
        lock.lock()
        if activeSession === session && !session.terminalReported {
            session.terminalReported = true
            session.connected = false
            session.connectTask?.cancel()
            session.micTask?.cancel()
            session.room = nil
            activeSession = nil
            shouldReport = true
        } else {
            shouldReport = false
        }
        lock.unlock()

        guard shouldReport else { return }
        AudioManager.shared.isSpeakerOutputPreferred = true
        session.listener.onDisconnected(reason: reason, detail: detail)
    }
}
