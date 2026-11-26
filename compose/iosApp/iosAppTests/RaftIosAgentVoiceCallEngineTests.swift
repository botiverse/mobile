import Foundation
#if canImport(LiveKitClient)
import LiveKitClient
#elseif canImport(LiveKit)
import LiveKit
#endif
import XCTest
import shared

final class RaftIosAgentVoiceCallEngineTests: XCTestCase {
    private var previousEngine: RaftAgentVoiceCallEngine?

    override func setUp() {
        super.setUp()
        previousEngine = RaftAgentVoiceCallEngineRegistry.shared.currentOrNull()
    }

    override func tearDown() {
        if let previousEngine {
            RaftAgentVoiceCallEngineRegistry.shared.register(engine: previousEngine)
        } else {
            RaftAgentVoiceCallEngineRegistry.shared.unregister()
        }
        super.tearDown()
    }

    func testCaptionTopicFilteringAndUtf8Decoding() {
        let payload = "{\"type\":\"caption\",\"role\":\"agent\",\"text\":\"你好 Raft\",\"final\":true,\"id\":\"c1\"}"
        let data = Data(payload.utf8)

        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.extractCaptionJson(topic: "caption", data: data),
            payload
        )
        XCTAssertNil(RaftIosAgentVoiceCallPolicy.extractCaptionJson(topic: "other", data: data))
        XCTAssertNil(RaftIosAgentVoiceCallPolicy.extractCaptionJson(topic: "", data: data))
        XCTAssertNil(RaftIosAgentVoiceCallPolicy.extractCaptionJson(topic: "caption", data: Data()))
    }

    func testDisconnectReasonMappingMatchesSharedContract() {
        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.mapDisconnectReason(errorType: .cancelled, clientInitiated: false),
            RaftAgentVoiceDisconnectReason.shared.CLIENT_INITIATED
        )
        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.mapDisconnectReason(errorType: nil, clientInitiated: true),
            RaftAgentVoiceDisconnectReason.shared.CLIENT_INITIATED
        )
        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.mapDisconnectReason(errorType: .roomDeleted, clientInitiated: false),
            RaftAgentVoiceDisconnectReason.shared.SERVER_CLOSED
        )
        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.mapDisconnectReason(errorType: .participantRemoved, clientInitiated: false),
            RaftAgentVoiceDisconnectReason.shared.SERVER_CLOSED
        )
        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.mapDisconnectReason(errorType: .network, clientInitiated: false),
            RaftAgentVoiceDisconnectReason.shared.FAILED
        )
        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.mapDisconnectReason(errorType: .timedOut, clientInitiated: false),
            RaftAgentVoiceDisconnectReason.shared.FAILED
        )
        XCTAssertEqual(
            RaftIosAgentVoiceCallPolicy.mapDisconnectReason(errorType: nil, clientInitiated: false),
            RaftAgentVoiceDisconnectReason.shared.FAILED
        )
    }

    func testRemoteSpeakingDetectionIgnoresLocalOnly() {
        XCTAssertFalse(RaftIosAgentVoiceCallPolicy.hasRemoteSpeakingParticipant(remoteFlags: []))
        XCTAssertFalse(RaftIosAgentVoiceCallPolicy.hasRemoteSpeakingParticipant(remoteFlags: [false]))
        XCTAssertTrue(RaftIosAgentVoiceCallPolicy.hasRemoteSpeakingParticipant(remoteFlags: [true]))
        XCTAssertTrue(RaftIosAgentVoiceCallPolicy.hasRemoteSpeakingParticipant(remoteFlags: [false, true]))
    }

    func testInstallRegistersSupportedEngineInSharedRegistry() {
        RaftIosAgentVoiceCallEngine.install()
        XCTAssertTrue(RaftAgentVoiceCallEngineRegistry.shared.isSupported())
        let registered = RaftAgentVoiceCallEngineRegistry.shared.currentOrNull()
        XCTAssertNotNil(registered)
        XCTAssertEqual(registered?.isSupported, true)
    }

    func testVoiceDictationPolicyPcm16RmsAndLevelNormalization() {
        let silence = Data(repeating: 0, count: RaftIosVoiceDictationPolicy.chunkByteSize)
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16RawRms(data: silence), 0)
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: silence), 0)

        // Sub-noise-floor signal (~0.01 amplitude) clamps to 0.
        var quietBytes = Data()
        let quietSample = Int16(300).littleEndian
        for _ in 0..<160 {
            withUnsafeBytes(of: quietSample) { quietBytes.append(contentsOf: $0) }
        }
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: quietBytes), 0)

        // Conversational speech (~0.10 amplitude) produces a mid-range visual level in (0, 1).
        var speechBytes = Data()
        let speechSample = Int16(3277).littleEndian
        for _ in 0..<160 {
            withUnsafeBytes(of: speechSample) { speechBytes.append(contentsOf: $0) }
        }
        let speechLevel = RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: speechBytes)
        XCTAssertGreaterThan(speechLevel, 0.3)
        XCTAssertLessThan(speechLevel, 1.0)

        // Peak signal saturates at 1.0.
        var loudBytes = Data()
        let loudSample = Int16(16_384).littleEndian
        for _ in 0..<160 {
            withUnsafeBytes(of: loudSample) { loudBytes.append(contentsOf: $0) }
        }
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: loudBytes), 1.0, accuracy: 0.0001)
    }

    func testVoiceDictationInstallRegistersSupportedRecorder() {
        let wasInstalled = RaftIosVoiceDictationRecorderHost.shared.isInstalled()
        defer {
            if wasInstalled {
                RaftIosVoiceDictationAudioRecorder.install()
            } else {
                RaftIosVoiceDictationRecorderHost.shared.unregister()
            }
        }
        RaftIosVoiceDictationRecorderHost.shared.unregister()
        XCTAssertFalse(RaftIosVoiceDictationRecorderHost.shared.isInstalled())
        RaftIosVoiceDictationAudioRecorder.install()
        XCTAssertTrue(RaftIosVoiceDictationRecorderHost.shared.isInstalled())
        XCTAssertEqual(
            RaftIosVoiceDictationRecorderHost.shared.currentSampleRateHz(),
            Int32(RaftIosVoiceDictationPolicy.sampleRateHz)
        )
    }
}
