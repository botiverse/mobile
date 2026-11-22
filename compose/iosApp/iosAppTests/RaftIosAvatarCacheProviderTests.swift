import Foundation
import shared
import UIKit
import XCTest

final class RaftIosAvatarCacheProviderTests: XCTestCase {
    private var rootURL: URL!

    override func setUpWithError() throws {
        rootURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("RaftIosAvatarCacheProviderTests-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: rootURL)
    }

    func testCommitSurvivesRestartAndOwnerRetirementPhysicallyDeletesBytes() throws {
        let slot = makeSlot()
        let store = RaftIosAvatarDurableStore(rootURL: rootURL)
        let result = try commit(imageData: makePng(), slot: slot, store: store)

        XCTAssertEqual(store.managedBytes, Int64(makePng().count))
        XCTAssertNotNil(store.image(forRenderModel: model(slot: slot, reservation: result)))

        let restarted = RaftIosAvatarDurableStore(rootURL: rootURL)
        let inspection = inspect(slot, store: restarted)
        XCTAssertTrue(inspection.supported)
        XCTAssertTrue(inspection.bytesAvailable)
        XCTAssertTrue(inspection.bytesDecodable)
        XCTAssertNotNil(restarted.image(forRenderModel: model(slot: slot, reservation: result)))

        let retirement = operation { completion in
            restarted.retireOwner(nativePartitionKey: slot.nativePartitionKey, completionHandler: completion)
        }
        XCTAssertTrue(retirement is RaftAvatarNativeOperationResultAccepted)
        XCTAssertEqual(restarted.managedBytes, 0)
        XCTAssertFalse(inspect(slot, store: restarted).bytesAvailable)
        let files = try FileManager.default.contentsOfDirectory(
            at: rootURL.appendingPathComponent("entries"),
            includingPropertiesForKeys: nil
        )
        XCTAssertTrue(files.isEmpty)
    }

    func testOversizeWriteFailsClosedWithoutPublishingBytes() throws {
        let slot = makeSlot()
        let store = RaftIosAvatarDurableStore(rootURL: rootURL)
        let fence = makeFence(slot: slot)
        let opened = try XCTUnwrap(store.beginStage(fence: fence) as? RaftAvatarNativeStageOpenOpened)
        let chunk = KotlinByteArray(size: Int32(1024 * 1024))
        for index in 0..<chunk.size { chunk.set(index: index, value: 1) }
        for _ in 0..<5 { XCTAssertTrue(opened.sink.write(bytes: chunk)) }
        XCTAssertFalse(opened.sink.write(bytes: KotlinByteArray(size: 1)))
        opened.sink.abort()

        XCTAssertFalse(inspect(slot, store: store).bytesAvailable)
        XCTAssertEqual(store.managedBytes, 0)
    }

    func testCorruptCommittedBytesAreRetiredDuringRestartRecovery() throws {
        let slot = makeSlot()
        let store = RaftIosAvatarDurableStore(rootURL: rootURL)
        _ = try commit(imageData: makePng(), slot: slot, store: store)
        let entriesURL = rootURL.appendingPathComponent("entries")
        let file = try XCTUnwrap(
            FileManager.default.contentsOfDirectory(at: entriesURL, includingPropertiesForKeys: nil).first
        )
        try Data("corrupt".utf8).write(to: file)

        let restarted = RaftIosAvatarDurableStore(rootURL: rootURL)
        XCTAssertFalse(inspect(slot, store: restarted).bytesAvailable)
        XCTAssertEqual(restarted.managedBytes, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    private func commit(
        imageData: Data,
        slot: RaftAvatarNativeSlot,
        requestNonceDigit: Character = "4",
        store: RaftIosAvatarDurableStore
    ) throws -> RaftAvatarNativeReservation {
        let fence = makeFence(slot: slot, requestNonceDigit: requestNonceDigit)
        let ready = try stage(imageData: imageData, fence: fence, store: store)
        let reserved = try XCTUnwrap(
            reserve(
                RaftAvatarNativeReservationRequest(fence: fence, candidateModelRevision: 1),
                store: store
            ) as? RaftAvatarNativeReserveResultReserved
        )
        XCTAssertTrue(operation { completion in
            store.bindStage(
                staged: ready,
                reservation: reserved.reservation,
                completionHandler: completion
            )
        } is RaftAvatarNativeOperationResultAccepted)
        XCTAssertTrue(operation { completion in
            store.finalize(reservation: reserved.reservation, completionHandler: completion)
        } is RaftAvatarNativeOperationResultAccepted)
        return reserved.reservation
    }

    private func stage(
        imageData: Data,
        fence: RaftAvatarNativeStageFence,
        store: RaftIosAvatarDurableStore
    ) throws -> RaftAvatarNativeStagedCandidate {
        let opened = try XCTUnwrap(store.beginStage(fence: fence) as? RaftAvatarNativeStageOpenOpened)
        XCTAssertTrue(opened.sink.write(bytes: kotlinBytes(imageData)))
        return try XCTUnwrap(opened.sink.finish() as? RaftAvatarNativeStageFinishReady).candidate
    }

    private func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, value) in data.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: value))
        }
        return bytes
    }

    private func inspect(
        _ slot: RaftAvatarNativeSlot,
        store: RaftIosAvatarDurableStore
    ) -> RaftAvatarMountedNativeInspection {
        var value: RaftAvatarMountedNativeInspection?
        store.inspect(slot: slot) { result, error in
            XCTAssertNil(error)
            value = result
        }
        return value!
    }

    private func reserve(
        _ request: RaftAvatarNativeReservationRequest,
        store: RaftIosAvatarDurableStore
    ) -> RaftAvatarNativeReserveResult? {
        var value: RaftAvatarNativeReserveResult?
        store.reserve(request: request) { result, error in
            XCTAssertNil(error)
            value = result
        }
        return value
    }

    private func operation(
        _ action: (@escaping (RaftAvatarNativeOperationResult?, Error?) -> Void) -> Void
    ) -> RaftAvatarNativeOperationResult? {
        var value: RaftAvatarNativeOperationResult?
        action { result, error in
            XCTAssertNil(error)
            value = result
        }
        return value
    }

    private func makeSlot(subjectDigit: Character = "2", bytesDigit: Character = "3") -> RaftAvatarNativeSlot {
        RaftAvatarNativeSlot(
            nativePartitionKey: String(repeating: "1", count: 64),
            nativeSubjectKey: String(repeating: subjectDigit, count: 64),
            nativeBytesKey: String(repeating: bytesDigit, count: 64)
        )
    }

    private func makeFence(
        slot: RaftAvatarNativeSlot,
        requestNonceDigit: Character = "4",
        expectedCommitIdentity: RaftAvatarCommitIdentity? = nil,
        expectedModelRevision: Int64 = 0
    ) -> RaftAvatarNativeStageFence {
        RaftAvatarNativeStageFence(
            slot: slot,
            requestNonce: String(repeating: requestNonceDigit, count: 64),
            expectedCommitIdentity: expectedCommitIdentity,
            expectedModelRevision: expectedModelRevision
        )
    }

    private func makePng(_ color: UIColor = .systemBlue) -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).pngData { context in
            color.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }
    }

    private func model(
        slot: RaftAvatarNativeSlot,
        reservation: RaftAvatarNativeReservation
    ) -> String {
        let json: [String: String] = [
            "protocol": "slock_avatar_render_v1",
            "nativePartitionKey": slot.nativePartitionKey,
            "nativeSubjectKey": slot.nativeSubjectKey,
            "nativeBytesKey": slot.nativeBytesKey,
            "commitGeneration": String(reservation.commitIdentity.commitGeneration),
            "commitNonce": reservation.commitIdentity.nonce,
            "modelRevision": String(reservation.candidateModelRevision),
        ]
        let data = try! JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])
        return "slock-avatar-render-v1:\(data.base64EncodedString())"
    }
}
