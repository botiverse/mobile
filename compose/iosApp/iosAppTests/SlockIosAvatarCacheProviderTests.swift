import Foundation
import shared
import UIKit
import XCTest

final class SlockIosAvatarCacheProviderTests: XCTestCase {
    private var rootURL: URL!

    override func setUpWithError() throws {
        rootURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SlockIosAvatarCacheProviderTests-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: rootURL)
    }

    func testCommitSurvivesRestartAndOwnerRetirementPhysicallyDeletesBytes() throws {
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(rootURL: rootURL)
        let result = try commit(imageData: makePng(), slot: slot, store: store)

        XCTAssertEqual(store.managedBytes, Int64(makePng().count))
        XCTAssertNotNil(store.image(forRenderModel: model(slot: slot, reservation: result)))

        let restarted = SlockIosAvatarDurableStore(rootURL: rootURL)
        let inspection = inspect(slot, store: restarted)
        XCTAssertTrue(inspection.supported)
        XCTAssertTrue(inspection.bytesAvailable)
        XCTAssertTrue(inspection.bytesDecodable)
        XCTAssertNotNil(restarted.image(forRenderModel: model(slot: slot, reservation: result)))

        let retirement = operation { completion in
            restarted.retireOwner(nativePartitionKey: slot.nativePartitionKey, completionHandler: completion)
        }
        XCTAssertTrue(retirement is SlockAvatarNativeOperationResultAccepted)
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
        let store = SlockIosAvatarDurableStore(rootURL: rootURL)
        let fence = makeFence(slot: slot)
        let opened = try XCTUnwrap(store.beginStage(fence: fence) as? SlockAvatarNativeStageOpenOpened)
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
        let store = SlockIosAvatarDurableStore(rootURL: rootURL)
        _ = try commit(imageData: makePng(), slot: slot, store: store)
        let entriesURL = rootURL.appendingPathComponent("entries")
        let file = try XCTUnwrap(
            FileManager.default.contentsOfDirectory(at: entriesURL, includingPropertiesForKeys: nil).first
        )
        try Data("corrupt".utf8).write(to: file)

        let restarted = SlockIosAvatarDurableStore(rootURL: rootURL)
        XCTAssertFalse(inspect(slot, store: restarted).bytesAvailable)
        XCTAssertEqual(restarted.managedBytes, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testNilExpectedIdentityCannotReplaceAnExistingCommit() throws {
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(rootURL: rootURL)
        let first = try commit(imageData: makePng(.systemBlue), slot: slot, store: store)
        let replacementFence = makeFence(
            slot: slot,
            requestNonceDigit: "5",
            expectedCommitIdentity: nil,
            expectedModelRevision: 0
        )
        let staged = try stage(imageData: makePng(.systemRed), fence: replacementFence, store: store)

        let reserveResult = reserve(
            SlockAvatarNativeReservationRequest(
                fence: replacementFence,
                candidateModelRevision: 1
            ),
            store: store
        )
        XCTAssertTrue(reserveResult is SlockAvatarNativeReserveResultExpectedIdentityMismatch)
        XCTAssertNotNil(store.image(forRenderModel: model(slot: slot, reservation: first)))
        store.abortStage(staged: staged) { result, error in
            XCTAssertNil(error)
            XCTAssertTrue(result is SlockAvatarNativeOperationResultAccepted)
        }
    }

    func testRollbackAfterFinalizePhysicallyRemovesUnpublishableCommit() throws {
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(rootURL: rootURL)
        let reservation = try commit(imageData: makePng(), slot: slot, store: store)

        let rollback = operation { completion in
            store.rollback(reservation: reservation, completionHandler: completion)
        }
        XCTAssertTrue(rollback is SlockAvatarNativeOperationResultAccepted)
        XCTAssertFalse(inspect(slot, store: store).bytesAvailable)
        XCTAssertNil(store.image(forRenderModel: model(slot: slot, reservation: reservation)))
        XCTAssertEqual(store.managedBytes, 0)
        XCTAssertTrue(operation { completion in
            store.rollback(reservation: reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
    }

    func testBudgetEvictsLeastRecentlyUsedEntryAndKeepsNewestCommit() throws {
        let firstBytes = makePng(.systemBlue)
        let secondBytes = makePng(.systemRed)
        let budget = Int64(firstBytes.count + secondBytes.count - 1)
        let store = SlockIosAvatarDurableStore(rootURL: rootURL, maxManagedBytes: budget)
        let firstSlot = makeSlot(subjectDigit: "2", bytesDigit: "3")
        let secondSlot = makeSlot(subjectDigit: "6", bytesDigit: "7")
        _ = try commit(
            imageData: firstBytes,
            slot: firstSlot,
            requestNonceDigit: "4",
            store: store
        )
        Thread.sleep(forTimeInterval: 0.01)
        let second = try commit(
            imageData: secondBytes,
            slot: secondSlot,
            requestNonceDigit: "5",
            store: store
        )

        XCTAssertFalse(inspect(firstSlot, store: store).bytesAvailable)
        XCTAssertTrue(inspect(secondSlot, store: store).bytesAvailable)
        XCTAssertNotNil(store.image(forRenderModel: model(slot: secondSlot, reservation: second)))
        XCTAssertLessThanOrEqual(store.managedBytes, budget)
    }

    func testRetirementScopesKeepCleanupDebtAndAdmissionClosedUntilPhysicalDeleteRetries() throws {
        struct RetirementCase {
            let name: String
            let blockedSlot: (SlockAvatarNativeSlot) -> SlockAvatarNativeSlot
            let retire: (SlockIosAvatarDurableStore, SlockAvatarNativeSlot, SlockAvatarNativeReservation) -> SlockAvatarNativeOperationResult?
        }
        let cases = [
            RetirementCase(name: "exact", blockedSlot: { $0 }) { store, slot, reservation in
                self.operation { completion in
                    store.retireExact(
                        slot: slot,
                        expectedCommitIdentity: reservation.commitIdentity,
                        completionHandler: completion
                    )
                }
            },
            RetirementCase(
                name: "subject",
                blockedSlot: { _ in self.makeSlot(subjectDigit: "2", bytesDigit: "8") }
            ) { store, slot, _ in
                self.operation { completion in store.retireSubject(slot: slot, completionHandler: completion) }
            },
            RetirementCase(
                name: "owner",
                blockedSlot: { _ in self.makeSlot(subjectDigit: "8", bytesDigit: "9") }
            ) { store, slot, _ in
                self.operation { completion in
                    store.retireOwner(nativePartitionKey: slot.nativePartitionKey, completionHandler: completion)
                }
            },
            RetirementCase(
                name: "clearAll",
                blockedSlot: { _ in
                    SlockAvatarNativeSlot(
                        nativePartitionKey: String(repeating: "7", count: 64),
                        nativeSubjectKey: String(repeating: "8", count: 64),
                        nativeBytesKey: String(repeating: "9", count: 64)
                    )
                }
            ) { store, _, _ in
                self.operation { completion in store.clearAll(completionHandler: completion) }
            },
        ]

        for retirementCase in cases {
            let caseRoot = rootURL.appendingPathComponent(retirementCase.name)
            let fileManager = DeleteFailingFileManager()
            let store = SlockIosAvatarDurableStore(rootURL: caseRoot, fileManager: fileManager)
            let slot = makeSlot()
            let blockedSlot = retirementCase.blockedSlot(slot)
            let reservation = try commit(imageData: makePng(), slot: slot, store: store)
            fileManager.failNextEntryRemovals = 3

            XCTAssertTrue(
                retirementCase.retire(store, slot, reservation) is SlockAvatarNativeOperationResultBusyCleanup,
                retirementCase.name
            )
            XCTAssertGreaterThan(store.managedBytes, 0, retirementCase.name)

            let restarted = SlockIosAvatarDurableStore(rootURL: caseRoot, fileManager: fileManager)
            XCTAssertTrue(
                restarted.beginStage(fence: makeFence(slot: blockedSlot, requestNonceDigit: "8"))
                    is SlockAvatarNativeStageOpenBusyCleanup,
                retirementCase.name
            )
            XCTAssertTrue(
                retirementCase.retire(restarted, slot, reservation) is SlockAvatarNativeOperationResultAccepted,
                retirementCase.name
            )
            XCTAssertEqual(restarted.managedBytes, 0, retirementCase.name)
            XCTAssertTrue(
                restarted.beginStage(fence: makeFence(slot: blockedSlot, requestNonceDigit: "9"))
                    is SlockAvatarNativeStageOpenOpened,
                retirementCase.name
            )
        }
    }

    func testAbortCleanupDebtSurvivesRestartAndBlocksAdmissionUntilRetry() throws {
        let fileManager = DeleteFailingFileManager()
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(rootURL: rootURL, fileManager: fileManager)
        let fence = makeFence(slot: slot)
        let staged = try stage(imageData: makePng(), fence: fence, store: store)
        fileManager.failNextStagingRemovals = 3

        XCTAssertTrue(operation { completion in
            store.abortStage(staged: staged, completionHandler: completion)
        } is SlockAvatarNativeOperationResultBusyCleanup)
        XCTAssertGreaterThan(store.managedBytes, 0)

        let restarted = SlockIosAvatarDurableStore(rootURL: rootURL, fileManager: fileManager)
        XCTAssertTrue(
            restarted.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "8"))
                is SlockAvatarNativeStageOpenBusyCleanup
        )
        XCTAssertTrue(operation { completion in
            restarted.abortStage(staged: staged, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertTrue(
            restarted.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "9"))
                is SlockAvatarNativeStageOpenOpened
        )
    }

    func testFinalizedRollbackKeepsCleanupDebtAndAdmissionClosedUntilRetry() throws {
        let fileManager = DeleteFailingFileManager()
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(rootURL: rootURL, fileManager: fileManager)
        let reservation = try commit(imageData: makePng(), slot: slot, store: store)
        fileManager.failNextEntryRemovals = 3

        XCTAssertTrue(operation { completion in
            store.rollback(reservation: reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultBusyCleanup)
        XCTAssertGreaterThan(store.managedBytes, 0)

        let restarted = SlockIosAvatarDurableStore(rootURL: rootURL, fileManager: fileManager)
        XCTAssertTrue(
            restarted.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "8"))
                is SlockAvatarNativeStageOpenBusyCleanup
        )
        XCTAssertTrue(operation { completion in
            restarted.rollback(reservation: reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertEqual(restarted.managedBytes, 0)
    }

    func testRetireExactRevokesActiveStageAndPhysicallyDeletesTemporaryBytes() throws {
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(rootURL: rootURL)
        let fence = makeFence(slot: slot)
        let opened = try XCTUnwrap(store.beginStage(fence: fence) as? SlockAvatarNativeStageOpenOpened)
        XCTAssertTrue(opened.sink.write(bytes: kotlinBytes(makePng())))

        XCTAssertTrue(operation { completion in
            store.retireExact(slot: slot, expectedCommitIdentity: nil, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertTrue(
            try FileManager.default.contentsOfDirectory(
                at: rootURL.appendingPathComponent("staging"),
                includingPropertiesForKeys: nil
            ).isEmpty
        )
        XCTAssertTrue(
            store.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "8"))
                is SlockAvatarNativeStageOpenOpened
        )
    }

    func testFinalizeIndexWriteFailureKeepsCandidateDebtAndAdmissionClosedUntilRollbackRetries() throws {
        let fileManager = DeleteFailingFileManager()
        let gate = IndexWriteGate()
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(
            rootURL: rootURL,
            fileManager: fileManager,
            indexWriteAllowed: { gate.allowWrite() }
        )
        let fence = makeFence(slot: slot)
        let staged = try stage(imageData: makePng(), fence: fence, store: store)
        let reserved = try XCTUnwrap(
            reserve(
                SlockAvatarNativeReservationRequest(fence: fence, candidateModelRevision: 1),
                store: store
            ) as? SlockAvatarNativeReserveResultReserved
        )
        XCTAssertTrue(operation { completion in
            store.bindStage(staged: staged, reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)

        gate.failNextWrites = 2
        fileManager.failNextEntryRemovals = 2
        XCTAssertTrue(operation { completion in
            store.finalize(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultBusyCleanup)
        XCTAssertTrue(operation { completion in
            store.finalize(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultBusyCleanup)
        XCTAssertTrue(
            store.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "8"))
                is SlockAvatarNativeStageOpenBusyCleanup
        )
        XCTAssertTrue(operation { completion in
            store.rollback(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultBusyCleanup)
        XCTAssertTrue(operation { completion in
            store.rollback(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertEqual(store.managedBytes, 0)
    }

    func testAbortIndexWriteFailureKeepsTerminalOperationAndAdmissionClosed() throws {
        let gate = IndexWriteGate()
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(
            rootURL: rootURL,
            indexWriteAllowed: { gate.allowWrite() }
        )
        let fence = makeFence(slot: slot)
        let staged = try stage(imageData: makePng(), fence: fence, store: store)
        gate.failNextWrites = 2

        XCTAssertTrue(operation { completion in
            store.abortStage(staged: staged, completionHandler: completion)
        } is SlockAvatarNativeOperationResultStorageFailure)
        XCTAssertTrue(
            store.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "8"))
                is SlockAvatarNativeStageOpenBusyCleanup
        )
        XCTAssertTrue(operation { completion in
            store.abortStage(staged: staged, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
    }

    func testStartupFailureMakesEveryTerminalApiFailClosed() {
        let gate = IndexWriteGate(allowWrites: false)
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(
            rootURL: rootURL,
            indexWriteAllowed: { gate.allowWrite() }
        )
        let reservation = SlockAvatarNativeReservation(
            requestNonce: String(repeating: "4", count: 64),
            commitIdentity: SlockAvatarCommitIdentity(
                commitGeneration: 1,
                nonce: String(repeating: "5", count: 64)
            ),
            candidateModelRevision: 1
        )
        let staged = SlockAvatarNativeStagedCandidate(
            requestNonce: reservation.requestNonce,
            sizeBytes: 1,
            contentType: "image/png"
        )

        let results = [
            operation { store.retireExact(slot: slot, expectedCommitIdentity: nil, completionHandler: $0) },
            operation { store.retireSubject(slot: slot, completionHandler: $0) },
            operation { store.retireOwner(nativePartitionKey: slot.nativePartitionKey, completionHandler: $0) },
            operation { store.clearAll(completionHandler: $0) },
            operation { store.abortStage(staged: staged, completionHandler: $0) },
            operation { store.rollback(reservation: reservation, completionHandler: $0) },
        ]
        XCTAssertTrue(results.allSatisfy { $0 is SlockAvatarNativeOperationResultStorageFailure })
    }

    func testFinalizeCountsPendingCleanupDebtAgainstBudgetWithoutEvictingCandidate() throws {
        let bytes = makePng()
        let fileManager = DeleteFailingFileManager()
        let store = SlockIosAvatarDurableStore(
            rootURL: rootURL,
            fileManager: fileManager,
            maxManagedBytes: Int64(bytes.count)
        )
        let oldSlot = makeSlot(subjectDigit: "2", bytesDigit: "3")
        let newSlot = makeSlot(subjectDigit: "8", bytesDigit: "9")
        let oldReservation = try commit(imageData: bytes, slot: oldSlot, store: store)
        fileManager.failNextEntryRemovals = 1
        XCTAssertTrue(operation { completion in
            store.retireExact(
                slot: oldSlot,
                expectedCommitIdentity: oldReservation.commitIdentity,
                completionHandler: completion
            )
        } is SlockAvatarNativeOperationResultBusyCleanup)

        let fence = makeFence(slot: newSlot, requestNonceDigit: "8")
        let staged = try stage(imageData: bytes, fence: fence, store: store)
        let reserved = try XCTUnwrap(
            reserve(
                SlockAvatarNativeReservationRequest(fence: fence, candidateModelRevision: 1),
                store: store
            ) as? SlockAvatarNativeReserveResultReserved
        )
        XCTAssertTrue(operation { completion in
            store.bindStage(staged: staged, reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertTrue(operation { completion in
            store.finalize(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultBusyCleanup)
        XCTAssertNotNil(store.image(forRenderModel: model(slot: newSlot, reservation: reserved.reservation)))

        XCTAssertTrue(operation { completion in
            store.retireExact(
                slot: oldSlot,
                expectedCommitIdentity: oldReservation.commitIdentity,
                completionHandler: completion
            )
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertTrue(operation { completion in
            store.finalize(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertEqual(store.managedBytes, Int64(bytes.count))
    }

    func testStartupRecoveryFailureBlocksInspectAndRenderOfExistingCommit() throws {
        let slot = makeSlot()
        let original = SlockIosAvatarDurableStore(rootURL: rootURL)
        let reservation = try commit(imageData: makePng(), slot: slot, store: original)
        let gate = IndexWriteGate()
        gate.failWrite(after: 2)

        let restarted = SlockIosAvatarDurableStore(
            rootURL: rootURL,
            indexWriteAllowed: { gate.allowWrite() }
        )

        XCTAssertFalse(inspect(slot, store: restarted).bytesAvailable)
        XCTAssertNil(restarted.image(forRenderModel: model(slot: slot, reservation: reservation)))
    }

    func testTerminalScopeBlocksInspectAndRenderWhenEntryMarkPersistenceFails() throws {
        let gate = IndexWriteGate()
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(
            rootURL: rootURL,
            indexWriteAllowed: { gate.allowWrite() }
        )
        let reservation = try commit(imageData: makePng(), slot: slot, store: store)
        gate.failWrite(after: 2)

        XCTAssertTrue(operation { completion in
            store.retireExact(
                slot: slot,
                expectedCommitIdentity: reservation.commitIdentity,
                completionHandler: completion
            )
        } is SlockAvatarNativeOperationResultStorageFailure)
        XCTAssertFalse(inspect(slot, store: store).bytesAvailable)
        XCTAssertNil(store.image(forRenderModel: model(slot: slot, reservation: reservation)))
        XCTAssertTrue(operation { completion in
            store.retireExact(
                slot: slot,
                expectedCommitIdentity: reservation.commitIdentity,
                completionHandler: completion
            )
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertEqual(store.managedBytes, 0)
    }

    func testPostMoveSyncFailurePersistsCandidateDebtAcrossRestartUntilRollback() throws {
        let fileManager = DeleteFailingFileManager()
        let slot = makeSlot()
        let store = SlockIosAvatarDurableStore(
            rootURL: rootURL,
            fileManager: fileManager,
            destinationSyncAllowed: { false }
        )
        let fence = makeFence(slot: slot)
        let staged = try stage(imageData: makePng(), fence: fence, store: store)
        let reserved = try XCTUnwrap(
            reserve(
                SlockAvatarNativeReservationRequest(fence: fence, candidateModelRevision: 1),
                store: store
            ) as? SlockAvatarNativeReserveResultReserved
        )
        XCTAssertTrue(operation { completion in
            store.bindStage(staged: staged, reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)

        XCTAssertTrue(operation { completion in
            store.finalize(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultBusyCleanup)
        fileManager.failNextEntryRemovals = 3
        XCTAssertTrue(
            store.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "8"))
                is SlockAvatarNativeStageOpenBusyCleanup
        )
        let restarted = SlockIosAvatarDurableStore(rootURL: rootURL, fileManager: fileManager)
        XCTAssertTrue(
            restarted.beginStage(fence: makeFence(slot: slot, requestNonceDigit: "8"))
                is SlockAvatarNativeStageOpenBusyCleanup
        )
        XCTAssertTrue(operation { completion in
            restarted.rollback(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertEqual(restarted.managedBytes, 0)
        XCTAssertTrue(
            try FileManager.default.contentsOfDirectory(
                at: rootURL.appendingPathComponent("entries"),
                includingPropertiesForKeys: nil
            ).isEmpty
        )
    }

    private func commit(
        imageData: Data,
        slot: SlockAvatarNativeSlot,
        requestNonceDigit: Character = "4",
        store: SlockIosAvatarDurableStore
    ) throws -> SlockAvatarNativeReservation {
        let fence = makeFence(slot: slot, requestNonceDigit: requestNonceDigit)
        let ready = try stage(imageData: imageData, fence: fence, store: store)
        let reserved = try XCTUnwrap(
            reserve(
                SlockAvatarNativeReservationRequest(fence: fence, candidateModelRevision: 1),
                store: store
            ) as? SlockAvatarNativeReserveResultReserved
        )
        XCTAssertTrue(operation { completion in
            store.bindStage(
                staged: ready,
                reservation: reserved.reservation,
                completionHandler: completion
            )
        } is SlockAvatarNativeOperationResultAccepted)
        XCTAssertTrue(operation { completion in
            store.finalize(reservation: reserved.reservation, completionHandler: completion)
        } is SlockAvatarNativeOperationResultAccepted)
        return reserved.reservation
    }

    private func stage(
        imageData: Data,
        fence: SlockAvatarNativeStageFence,
        store: SlockIosAvatarDurableStore
    ) throws -> SlockAvatarNativeStagedCandidate {
        let opened = try XCTUnwrap(store.beginStage(fence: fence) as? SlockAvatarNativeStageOpenOpened)
        XCTAssertTrue(opened.sink.write(bytes: kotlinBytes(imageData)))
        return try XCTUnwrap(opened.sink.finish() as? SlockAvatarNativeStageFinishReady).candidate
    }

    private func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let bytes = KotlinByteArray(size: Int32(data.count))
        for (index, value) in data.enumerated() {
            bytes.set(index: Int32(index), value: Int8(bitPattern: value))
        }
        return bytes
    }

    private func inspect(
        _ slot: SlockAvatarNativeSlot,
        store: SlockIosAvatarDurableStore
    ) -> SlockAvatarMountedNativeInspection {
        var value: SlockAvatarMountedNativeInspection?
        store.inspect(slot: slot) { result, error in
            XCTAssertNil(error)
            value = result
        }
        return value!
    }

    private func reserve(
        _ request: SlockAvatarNativeReservationRequest,
        store: SlockIosAvatarDurableStore
    ) -> SlockAvatarNativeReserveResult? {
        var value: SlockAvatarNativeReserveResult?
        store.reserve(request: request) { result, error in
            XCTAssertNil(error)
            value = result
        }
        return value
    }

    private func operation(
        _ action: (@escaping (SlockAvatarNativeOperationResult?, Error?) -> Void) -> Void
    ) -> SlockAvatarNativeOperationResult? {
        var value: SlockAvatarNativeOperationResult?
        action { result, error in
            XCTAssertNil(error)
            value = result
        }
        return value
    }

    private func makeSlot(subjectDigit: Character = "2", bytesDigit: Character = "3") -> SlockAvatarNativeSlot {
        SlockAvatarNativeSlot(
            nativePartitionKey: String(repeating: "1", count: 64),
            nativeSubjectKey: String(repeating: subjectDigit, count: 64),
            nativeBytesKey: String(repeating: bytesDigit, count: 64)
        )
    }

    private func makeFence(
        slot: SlockAvatarNativeSlot,
        requestNonceDigit: Character = "4",
        expectedCommitIdentity: SlockAvatarCommitIdentity? = nil,
        expectedModelRevision: Int64 = 0
    ) -> SlockAvatarNativeStageFence {
        SlockAvatarNativeStageFence(
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
        slot: SlockAvatarNativeSlot,
        reservation: SlockAvatarNativeReservation
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

private final class DeleteFailingFileManager: FileManager {
    var failNextEntryRemovals = 0
    var failNextStagingRemovals = 0

    override func removeItem(at URL: URL) throws {
        if URL.path.contains("/entries/"), failNextEntryRemovals > 0 {
            failNextEntryRemovals -= 1
            throw CocoaError(.fileWriteUnknown)
        }
        if URL.path.contains("/staging/"), failNextStagingRemovals > 0 {
            failNextStagingRemovals -= 1
            throw CocoaError(.fileWriteUnknown)
        }
        try super.removeItem(at: URL)
    }
}

private final class IndexWriteGate {
    var failNextWrites = 0
    var allowWrites: Bool
    private var writeCount = 0
    private var failedWriteNumbers: Set<Int> = []

    init(allowWrites: Bool = true) {
        self.allowWrites = allowWrites
    }

    func allowWrite() -> Bool {
        writeCount += 1
        guard allowWrites else { return false }
        if failedWriteNumbers.remove(writeCount) != nil {
            return false
        }
        if failNextWrites > 0 {
            failNextWrites -= 1
            return false
        }
        return true
    }

    func failWrite(after callsFromNow: Int) {
        failedWriteNumbers.insert(writeCount + callsFromNow)
    }
}
