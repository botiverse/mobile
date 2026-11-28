import AVFoundation
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

    private func makePcm16LeChunk(sample: Int16, sampleCount: Int) -> Data {
        var bytes = Data()
        bytes.reserveCapacity(sampleCount * 2)
        let littleEndian = sample.littleEndian
        for _ in 0..<sampleCount {
            withUnsafeBytes(of: littleEndian) { bytes.append(contentsOf: $0) }
        }
        return bytes
    }

    func testVoiceDictationPolicyPcm16RmsAndLevelNormalization() {
        let silence = Data(repeating: 0, count: RaftIosVoiceDictationPolicy.chunkByteSize)
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16RawRms(data: silence), 0)
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: silence), 0)

        // Quiet room noise (~0.0031 amplitude <= 0.0045 floor) clamps to 0.
        let quietBytes = makePcm16LeChunk(sample: 100, sampleCount: 160)
        XCTAssertLessThan(
            RaftIosVoiceDictationPolicy.computePcm16RawRms(data: quietBytes),
            RaftIosVoiceDictationPolicy.rmsNoiseFloor
        )
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: quietBytes), 0)

        // Un-AGC'd handheld phone speech (~0.0214 RMS) produces a strong mid-range visual level.
        let speechBytes = makePcm16LeChunk(sample: 700, sampleCount: 160)
        let speechLevel = RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: speechBytes)
        XCTAssertGreaterThan(speechLevel, 0.40)
        XCTAssertLessThan(speechLevel, 0.70)

        // Peak signal saturates at 1.0.
        let loudBytes = makePcm16LeChunk(sample: 16_384, sampleCount: 160)
        XCTAssertEqual(RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: loudBytes), 1.0, accuracy: 0.0001)
    }

    func testVoiceDictationAudioLevelMeterCalibratesHandheldSpeechAdaptsLoudPeaksAndPreservesRawPcm() {
        var meter = RaftIosVoiceDictationAudioLevelMeter()
        XCTAssertEqual(meter.peakRms, RaftIosVoiceDictationPolicy.rmsCeilingInitial, accuracy: 0.0001)
        XCTAssertEqual(meter.smoothedLevel, 0)

        // 1. Quiet room noise stays cleanly at 0.
        let roomNoise = makePcm16LeChunk(sample: 100, sampleCount: 1_600)
        for _ in 0..<5 {
            XCTAssertEqual(meter.processPcm16(data: roomNoise), 0)
        }

        // 2. Normal un-AGC'd handheld speech (~0.015-0.0305 RMS) produces lively halo levels.
        let handheld500 = meter.processPcm16(data: makePcm16LeChunk(sample: 500, sampleCount: 1_600))
        let handheld700 = meter.processPcm16(data: makePcm16LeChunk(sample: 700, sampleCount: 1_600))
        let handheld1000 = meter.processPcm16(data: makePcm16LeChunk(sample: 1_000, sampleCount: 1_600))
        XCTAssertGreaterThan(handheld500, 0.30)
        XCTAssertLessThan(handheld500, 0.50)
        XCTAssertGreaterThan(handheld700, 0.40)
        XCTAssertLessThan(handheld700, 0.65)
        XCTAssertGreaterThan(handheld1000, 0.55)
        XCTAssertLessThan(handheld1000, 0.85)

        // 3. Loud close-up speech adapts peakRms upward so quieter syllables still modulate visibly.
        var loudPeakLevel: Float = 0
        for _ in 0..<6 {
            loudPeakLevel = meter.processRawRms(0.18, dtMs: 100)
        }
        XCTAssertGreaterThan(meter.peakRms, 0.15)
        XCTAssertLessThanOrEqual(meter.peakRms, RaftIosVoiceDictationPolicy.rmsCeilingMax)
        XCTAssertGreaterThan(loudPeakLevel, 0.95)

        let quieterSyllable1 = meter.processRawRms(0.08, dtMs: 100)
        let quieterSyllable2 = meter.processRawRms(0.08, dtMs: 100)
        let nextLoudSyllable = meter.processRawRms(0.18, dtMs: 100)
        XCTAssertLessThan(quieterSyllable1, loudPeakLevel - 0.08)
        XCTAssertLessThan(quieterSyllable2, quieterSyllable1)
        XCTAssertGreaterThan(nextLoudSyllable, quieterSyllable2 + 0.15)

        // 4. Silence decays smoothedLevel to 0 and peakRms back toward initial ceiling.
        let peakBeforeSilence = meter.peakRms
        for _ in 0..<30 {
            _ = meter.processRawRms(0, dtMs: 100)
        }
        XCTAssertEqual(meter.smoothedLevel, 0)
        XCTAssertLessThan(meter.peakRms, peakBeforeSilence - 0.05)
        XCTAssertGreaterThanOrEqual(meter.peakRms, RaftIosVoiceDictationPolicy.rmsCeilingInitial)

        // 5. Chunking & metering in the iOS recorder path never alters raw PCM bytes.
        var pending = Data()
        var chunkMeter = RaftIosVoiceDictationAudioLevelMeter()
        let handheldChunk = makePcm16LeChunk(sample: 700, sampleCount: 1_600)
        let firstHalf = RaftIosVoiceDictationPolicy.drainPcm16Chunks(
            pendingBytes: &pending,
            appending: roomNoise.prefix(1_600),
            meter: &chunkMeter
        )
        XCTAssertTrue(firstHalf.isEmpty)
        let emitted = RaftIosVoiceDictationPolicy.drainPcm16Chunks(
            pendingBytes: &pending,
            appending: roomNoise.suffix(1_600) + handheldChunk,
            meter: &chunkMeter
        )
        XCTAssertEqual(emitted.count, 2)
        XCTAssertEqual(emitted[0].0, roomNoise)
        XCTAssertEqual(emitted[0].1, 0)
        XCTAssertEqual(emitted[1].0, handheldChunk)
        XCTAssertGreaterThan(emitted[1].1, 0.35)
        XCTAssertLessThan(emitted[1].1, 0.70)
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

    func testVoiceDictationPolicyDrainPendingChunksPreservesRemainderUntilIncludeTailAndAlignsEvenBytes() {
        let speechSample = Int16(3_600).littleEndian
        var pending = Data()
        // 1 full 3,200-byte chunk + 601 trailing bytes (odd byte verifies 2-byte PCM16 alignment).
        let totalSamples = (3_200 + 600) / MemoryLayout<Int16>.size
        for _ in 0..<totalSamples {
            withUnsafeBytes(of: speechSample) { pending.append(contentsOf: $0) }
        }
        pending.append(0x7F)
        XCTAssertEqual(pending.count, 3_801)

        var meter = RaftIosVoiceDictationAudioLevelMeter()
        let liveChunks = RaftIosVoiceDictationPolicy.drainPcm16Chunks(
            pendingBytes: &pending,
            meter: &meter,
            includeTail: false
        )
        XCTAssertEqual(liveChunks.count, 1)
        XCTAssertEqual(liveChunks[0].0.count, 3_200)
        XCTAssertGreaterThan(liveChunks[0].1, 0.2)
        XCTAssertEqual(pending.count, 601)

        let tailChunks = RaftIosVoiceDictationPolicy.drainPcm16Chunks(
            pendingBytes: &pending,
            meter: &meter,
            includeTail: true
        )
        XCTAssertEqual(tailChunks.count, 1)
        XCTAssertEqual(tailChunks[0].0.count, 600)
        XCTAssertGreaterThan(tailChunks[0].1, 0.2)
        XCTAssertTrue(pending.isEmpty)

        // Single stray byte (< 1 Int16 sample) is discarded cleanly on tail drain.
        var singleByte = Data([0x42])
        let emptyTail = RaftIosVoiceDictationPolicy.drainPcm16Chunks(
            pendingBytes: &singleByte,
            meter: &meter,
            includeTail: true
        )
        XCTAssertTrue(emptyTail.isEmpty)
        XCTAssertTrue(singleByte.isEmpty)
    }

    func testVoiceDictationPolicyDrainConverterEndOfStreamFlushesResamplerTailCleanly() throws {
        let inputFormat = try XCTUnwrap(
            AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false)
        )
        let targetFormat = try XCTUnwrap(
            AVAudioFormat(
                commonFormat: .pcmFormatInt16,
                sampleRate: Double(RaftIosVoiceDictationPolicy.sampleRateHz),
                channels: 1,
                interleaved: true
            )
        )
        let converter = try XCTUnwrap(AVAudioConverter(from: inputFormat, to: targetFormat))

        // Feed 4,800 frames (100 ms at 48 kHz -> ~1,600 frames / 3,200 bytes at 16 kHz).
        let inputFrames: AVAudioFrameCount = 4_800
        let inputBuffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: inputFrames))
        inputBuffer.frameLength = inputFrames
        let floatChannel = try XCTUnwrap(inputBuffer.floatChannelData)[0]
        for i in 0..<Int(inputFrames) {
            floatChannel[i] = Float(sin(2.0 * Double.pi * 440.0 * Double(i) / 48_000.0)) * 0.25
        }

        let outputBuffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: 2_048))
        var provided = false
        var convertError: NSError?
        let status = converter.convert(to: outputBuffer, error: &convertError) { _, outStatus in
            if provided {
                outStatus.pointee = .noDataNow
                return nil
            }
            provided = true
            outStatus.pointee = .haveData
            return inputBuffer
        }
        XCTAssertNotEqual(status, .error)
        XCTAssertNil(convertError)

        var totalBytes = Data()
        if outputBuffer.frameLength > 0, let channelData = outputBuffer.int16ChannelData {
            totalBytes.append(Data(bytes: channelData[0], count: Int(outputBuffer.frameLength) * MemoryLayout<Int16>.size))
        }

        let eosBytes = RaftIosVoiceDictationPolicy.drainConverterEndOfStreamPcm16(
            converter: converter,
            targetFormat: targetFormat
        )
        XCTAssertEqual(eosBytes.count % MemoryLayout<Int16>.size, 0)
        totalBytes.append(eosBytes)

        // 100 ms of 48 kHz input downsampled to 16 kHz Int16 mono should yield ~3,200 bytes total.
        XCTAssertGreaterThanOrEqual(totalBytes.count, 3_000)
        XCTAssertEqual(totalBytes.count % MemoryLayout<Int16>.size, 0)
        let level = RaftIosVoiceDictationPolicy.computePcm16AudioLevel(data: totalBytes)
        XCTAssertGreaterThan(level, 0.1)
        XCTAssertLessThanOrEqual(level, 1.0)
    }
}
