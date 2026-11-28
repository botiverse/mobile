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

/// Pure audio math & configuration helpers for iOS composer voice dictation (`#wg-mobile-sync:#334`, `dm:@artin:#177`).
///
/// Mirrors shared Kotlin `VoiceDictationV2AudioMath.kt` for un-AGC'd `.record` + `.measurement`
/// capture: raw PCM16 bytes forwarded to ASR are NEVER altered, while visual halo levels use an
/// un-AGC'd noise floor (`0.0045`, ~ -47 dBFS) and adaptive ceiling (`0.045...0.25`, starting at `0.055`).
enum RaftIosVoiceDictationPolicy {
    static let sampleRateHz: Int = 16_000
    static let chunkDurationMs: Int = 100
    static let chunkByteSize: Int = sampleRateHz * 2 * chunkDurationMs / 1_000
    static let rmsNoiseFloor: Float = 0.0045
    static let rmsCeilingMin: Float = 0.045
    static let rmsCeilingInitial: Float = 0.055
    static let rmsCeilingMax: Float = 0.25
    static let rmsCeiling: Float = rmsCeilingInitial
    static let rmsExponent: Double = 0.60
    static let attackMs: Double = 50.0
    static let releaseMs: Double = 220.0
    static let peakAttackMs: Double = 150.0
    static let peakDecayMs: Double = 3_000.0
    static let silenceSnapEpsilon: Float = 0.005

    /// Computes raw normalized RMS in `0...1` for signed 16-bit little-endian mono PCM bytes.
    static func computePcm16RawRms(data: Data) -> Float {
        let sampleCount = data.count / MemoryLayout<Int16>.size
        guard sampleCount > 0 else { return 0 }
        let sumSquares: Double = data.withUnsafeBytes { rawBuffer in
            guard rawBuffer.count >= sampleCount * 2 else { return 0 }
            var total: Double = 0
            for index in 0..<sampleCount {
                let offset = index * 2
                let lo = UInt16(rawBuffer[offset])
                let hi = UInt16(rawBuffer[offset + 1])
                let sample = Int16(bitPattern: (hi << 8) | lo)
                let normalized = Double(sample) / 32768.0
                total += normalized * normalized
            }
            return total
        }
        let rms = Float(sqrt(sumSquares / Double(sampleCount)))
        return min(1, max(0, rms))
    }

    /// Maps raw PCM RMS into perceptual visual level (`0...1`) against `[rmsNoiseFloor, ceiling]`.
    static func normalizeRms(_ rawRms: Float, ceiling: Float = rmsCeiling) -> Float {
        guard rawRms.isFinite, rawRms > rmsNoiseFloor else { return 0 }
        let safeCeiling = ceiling.isFinite ? max(rmsCeilingMin, ceiling) : rmsCeiling
        let span = safeCeiling - rmsNoiseFloor
        guard span > 0 else { return 0 }
        let linear = min(1, max(0, (rawRms - rmsNoiseFloor) / span))
        let curved = Float(pow(Double(linear), rmsExponent))
        return min(1, max(0, curved))
    }

    /// Computes calibrated visual audio level (`0...1`) directly from a PCM16 LE mono chunk.
    static func computePcm16AudioLevel(data: Data) -> Float {
        normalizeRms(computePcm16RawRms(data: data))
    }

    /// Asymmetric attack/release smoother (~50 ms attack, ~220 ms release at 100 ms frames).
    static func smoothAudioLevel(previous: Float, target: Float, dtMs: Double = Double(chunkDurationMs)) -> Float {
        let prev = previous.isFinite ? min(1, max(0, previous)) : 0
        let next = target.isFinite ? min(1, max(0, target)) : 0
        let dt = (dtMs.isFinite && dtMs > 0) ? dtMs : Double(chunkDurationMs)
        let tau = next >= prev ? attackMs : releaseMs
        let alpha = 1.0 - exp(-dt / tau)
        let smoothed = prev + (next - prev) * Float(alpha)
        if next == 0 && smoothed < silenceSnapEpsilon {
            return 0
        }
        return min(1, max(0, smoothed))
    }

    /// Splits incoming PCM16 bytes into fixed `chunkByteSize` frames and meters visual levels
    /// without ever mutating the raw PCM bytes forwarded to ASR.
    static func drainPcm16Chunks(
        pendingBytes: inout Data,
        appending incoming: Data,
        meter: inout RaftIosVoiceDictationAudioLevelMeter
    ) -> [(Data, Float)] {
        pendingBytes.append(incoming)
        var emitted: [(Data, Float)] = []
        while pendingBytes.count >= chunkByteSize {
            let chunk = Data(pendingBytes.prefix(chunkByteSize))
            pendingBytes.removeFirst(chunkByteSize)
            let level = meter.processPcm16(data: chunk)
            emitted.append((chunk, level))
        }
        return emitted
    }
}

/// Pure, deterministic stateful audio-level meter for un-AGC'd iOS microphone capture (`dm:@artin:#177`).
/// Mirrors shared Kotlin `VoiceDictationAudioLevelMeter` in `VoiceDictationV2AudioMath.kt`.
struct RaftIosVoiceDictationAudioLevelMeter: Sendable {
    private let defaultPeakRms: Float
    private(set) var peakRms: Float
    private(set) var smoothedLevel: Float = 0

    init(initialPeakRms: Float = RaftIosVoiceDictationPolicy.rmsCeilingInitial) {
        let clamped = initialPeakRms.isFinite
            ? min(RaftIosVoiceDictationPolicy.rmsCeilingMax, max(RaftIosVoiceDictationPolicy.rmsCeilingMin, initialPeakRms))
            : RaftIosVoiceDictationPolicy.rmsCeilingInitial
        self.defaultPeakRms = clamped
        self.peakRms = clamped
    }

    mutating func reset() {
        peakRms = defaultPeakRms
        smoothedLevel = 0
    }

    mutating func processPcm16(
        data: Data,
        dtMs: Double = Double(RaftIosVoiceDictationPolicy.chunkDurationMs)
    ) -> Float {
        processRawRms(RaftIosVoiceDictationPolicy.computePcm16RawRms(data: data), dtMs: dtMs)
    }

    mutating func processRawRms(
        _ rawRms: Float,
        dtMs: Double = Double(RaftIosVoiceDictationPolicy.chunkDurationMs)
    ) -> Float {
        let safeRms: Float = (rawRms.isFinite && rawRms > 0) ? min(1, rawRms) : 0
        let dt = (dtMs.isFinite && dtMs > 0) ? dtMs : Double(RaftIosVoiceDictationPolicy.chunkDurationMs)
        let rawTarget: Float
        if safeRms > RaftIosVoiceDictationPolicy.rmsNoiseFloor {
            if safeRms > peakRms {
                let targetPeak = min(safeRms, RaftIosVoiceDictationPolicy.rmsCeilingMax)
                let alpha = 1.0 - exp(-dt / RaftIosVoiceDictationPolicy.peakAttackMs)
                peakRms = Float(Double(peakRms) + Double(targetPeak - peakRms) * alpha)
            } else {
                let alpha = 1.0 - exp(-dt / RaftIosVoiceDictationPolicy.peakDecayMs)
                peakRms = Float(Double(peakRms) + Double(RaftIosVoiceDictationPolicy.rmsCeilingInitial - peakRms) * alpha)
            }
            peakRms = min(RaftIosVoiceDictationPolicy.rmsCeilingMax, max(RaftIosVoiceDictationPolicy.rmsCeilingMin, peakRms))
            rawTarget = RaftIosVoiceDictationPolicy.normalizeRms(safeRms, ceiling: peakRms)
        } else {
            let alpha = 1.0 - exp(-dt / RaftIosVoiceDictationPolicy.peakDecayMs)
            peakRms = min(
                RaftIosVoiceDictationPolicy.rmsCeilingMax,
                max(
                    RaftIosVoiceDictationPolicy.rmsCeilingMin,
                    Float(Double(peakRms) + Double(RaftIosVoiceDictationPolicy.rmsCeilingInitial - peakRms) * alpha)
                )
            )
            rawTarget = 0
        }
        smoothedLevel = RaftIosVoiceDictationPolicy.smoothAudioLevel(
            previous: smoothedLevel,
            target: rawTarget,
            dtMs: dt
        )
        return smoothedLevel
    }
}

/// Native iOS `AVAudioEngine` + `AVAudioConverter` 16 kHz PCM16 mono microphone recorder for
/// thin-client v2 composer voice dictation (`#wg-mobile-sync:#334`).
///
/// Installed at launch (`iOSApp.init`) via `RaftIosVoiceDictationAudioRecorder.install()`.
final class RaftIosVoiceDictationAudioRecorder: NSObject, RaftIosVoiceDictationNativeRecorder, @unchecked Sendable {
    private final class ActiveCapture: @unchecked Sendable {
        let generation: UInt64
        let listener: RaftIosVoiceDictationRecorderListener
        var startTask: Task<Void, Never>?
        var engine: AVAudioEngine?
        var sessionActivated: Bool = false
        var pendingBytes = Data()
        var audioLevelMeter = RaftIosVoiceDictationAudioLevelMeter()

        init(generation: UInt64, listener: RaftIosVoiceDictationRecorderListener) {
            self.generation = generation
            self.listener = listener
        }
    }

    private static let sharedInstance = RaftIosVoiceDictationAudioRecorder()

    private let lock = NSLock()
    private var generationCounter: UInt64 = 0
    private var activeCapture: ActiveCapture?

    static func install() {
        RaftIosVoiceDictationRecorderHost.shared.install(recorder: sharedInstance)
    }

    var sampleRateHz: Int32 {
        Int32(RaftIosVoiceDictationPolicy.sampleRateHz)
    }

    func startCapture(listener: RaftIosVoiceDictationRecorderListener) {
        let previous = detachActiveCapture()
        teardown(previous)

        let capture: ActiveCapture
        lock.lock()
        generationCounter &+= 1
        capture = ActiveCapture(generation: generationCounter, listener: listener)
        activeCapture = capture
        lock.unlock()

        let task = Task { [weak self] in
            guard let self else { return }
            await self.runStart(capture: capture)
        }

        lock.lock()
        if activeCapture === capture {
            capture.startTask = task
        } else {
            task.cancel()
        }
        lock.unlock()
    }

    func stopCapture() {
        let previous = detachActiveCapture()
        teardown(previous)
    }

    private func runStart(capture: ActiveCapture) async {
        let granted = await Self.requestMicrophonePermission()
        guard isCurrent(capture) else { return }

        if !granted {
            _ = clearIfCurrent(capture)
            capture.listener.onStarted(
                status: RaftIosVoiceDictationStartStatus.shared.PERMISSION_DENIED,
                detail: nil
            )
            return
        }

        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.record, mode: .measurement, options: [])
            try audioSession.setActive(true, options: [])
        } catch {
            _ = clearIfCurrent(capture)
            capture.listener.onStarted(
                status: RaftIosVoiceDictationStartStatus.shared.DEVICE_UNAVAILABLE,
                detail: error.localizedDescription
            )
            return
        }

        lock.lock()
        let stillCurrentAfterSession = activeCapture === capture
        if stillCurrentAfterSession {
            capture.sessionActivated = true
        }
        lock.unlock()

        if !stillCurrentAfterSession {
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            return
        }

        let engine = AVAudioEngine()
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0,
              inputFormat.channelCount > 0,
              let targetFormat = AVAudioFormat(
                  commonFormat: .pcmFormatInt16,
                  sampleRate: Double(RaftIosVoiceDictationPolicy.sampleRateHz),
                  channels: 1,
                  interleaved: true
              ),
              let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            let detached = clearIfCurrent(capture)
            teardown(detached ?? capture)
            capture.listener.onStarted(
                status: RaftIosVoiceDictationStartStatus.shared.DEVICE_UNAVAILABLE,
                detail: "Audio input format unavailable"
            )
            return
        }

        let tapBufferFrames = AVAudioFrameCount(max(512, Int(inputFormat.sampleRate * 0.1)))
        inputNode.installTap(onBus: 0, bufferSize: tapBufferFrames, format: inputFormat) { [weak self] buffer, _ in
            guard let self else { return }
            self.handleTapBuffer(buffer, capture: capture, converter: converter, targetFormat: targetFormat)
        }

        lock.lock()
        if activeCapture === capture {
            capture.engine = engine
        } else {
            lock.unlock()
            inputNode.removeTap(onBus: 0)
            engine.stop()
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            return
        }
        lock.unlock()

        do {
            engine.prepare()
            try engine.start()
        } catch {
            let detached = clearIfCurrent(capture)
            teardown(detached ?? capture)
            capture.listener.onStarted(
                status: RaftIosVoiceDictationStartStatus.shared.FAILED,
                detail: error.localizedDescription
            )
            return
        }

        guard isCurrent(capture) else {
            teardown(capture)
            return
        }

        capture.listener.onStarted(
            status: RaftIosVoiceDictationStartStatus.shared.STARTED,
            detail: nil
        )
    }

    private func handleTapBuffer(
        _ buffer: AVAudioPCMBuffer,
        capture: ActiveCapture,
        converter: AVAudioConverter,
        targetFormat: AVAudioFormat
    ) {
        guard isCurrent(capture), buffer.frameLength > 0 else { return }
        let ratio = targetFormat.sampleRate / max(1.0, buffer.format.sampleRate)
        let estimatedFrames = AVAudioFrameCount(ceil(Double(buffer.frameLength) * ratio)) + 64
        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: max(64, estimatedFrames)) else {
            return
        }

        var convertError: NSError?
        var providedInput = false
        let status = converter.convert(to: outputBuffer, error: &convertError) { _, outStatus in
            if providedInput {
                outStatus.pointee = .noDataNow
                return nil
            }
            providedInput = true
            outStatus.pointee = .haveData
            return buffer
        }

        guard status != .error,
              convertError == nil,
              outputBuffer.frameLength > 0,
              let channelData = outputBuffer.int16ChannelData else {
            return
        }

        let byteCount = Int(outputBuffer.frameLength) * MemoryLayout<Int16>.size
        guard byteCount > 0 else { return }
        let convertedData = Data(bytes: channelData[0], count: byteCount)

        let chunksToEmit: [(Data, Float)]

        lock.lock()
        guard activeCapture === capture else {
            lock.unlock()
            return
        }
        chunksToEmit = RaftIosVoiceDictationPolicy.drainPcm16Chunks(
            pendingBytes: &capture.pendingBytes,
            appending: convertedData,
            meter: &capture.audioLevelMeter
        )
        lock.unlock()

        for (chunk, level) in chunksToEmit {
            guard isCurrent(capture) else { return }
            capture.listener.onPcmChunk(data: chunk, audioLevel: level)
        }
    }

    private func detachActiveCapture() -> ActiveCapture? {
        lock.lock()
        let previous = activeCapture
        activeCapture = nil
        lock.unlock()
        return previous
    }

    private func clearIfCurrent(_ capture: ActiveCapture) -> ActiveCapture? {
        lock.lock()
        defer { lock.unlock() }
        guard activeCapture === capture else { return nil }
        activeCapture = nil
        return capture
    }

    private func isCurrent(_ capture: ActiveCapture) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeCapture === capture
    }

    private func teardown(_ capture: ActiveCapture?) {
        guard let capture else { return }
        capture.startTask?.cancel()
        if let engine = capture.engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            capture.engine = nil
        }
        if capture.sessionActivated {
            capture.sessionActivated = false
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
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
}
