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
}
