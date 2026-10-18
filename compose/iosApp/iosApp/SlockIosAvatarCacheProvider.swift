import CryptoKit
import Foundation
import SDWebImage
import Security
import shared
import UIKit

private let slockIosAvatarMaxEntryBytes: Int64 = 5 * 1024 * 1024
private let slockIosAvatarMaxManagedBytes: Int64 = 64 * 1024 * 1024
private let slockIosAvatarModelPrefix = "slock-avatar-render-v1:"
private let slockIosAvatarOpaquePattern = try! NSRegularExpression(pattern: "^[0-9a-f]{64}$")

private func slockIosAvatarIsOpaque(_ value: String) -> Bool {
    let range = NSRange(value.startIndex..<value.endIndex, in: value)
    return slockIosAvatarOpaquePattern.firstMatch(in: value, range: range)?.range == range
}

private func slockIosAvatarHex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

private func slockIosAvatarNonce() -> String {
    var bytes = [UInt8](repeating: 0, count: 32)
    guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
        return slockIosAvatarHex(Data(UUID().uuidString.utf8))
    }
    return bytes.map { String(format: "%02x", $0) }.joined()
}

private struct SlockIosAvatarEntry: Codable, Equatable {
    let nativePartitionKey: String
    let nativeSubjectKey: String
    let nativeBytesKey: String
    let commitGeneration: Int64
    let commitNonce: String
    let modelRevision: Int64
    let fileName: String
    let byteLength: Int64
    let sha256: String
    let contentType: String
    var lastAccessMillis: Int64
    var cleanupPending: Bool

    private enum CodingKeys: String, CodingKey {
        case nativePartitionKey
        case nativeSubjectKey
        case nativeBytesKey
        case commitGeneration
        case commitNonce
        case modelRevision
        case fileName
        case byteLength
        case sha256
        case contentType
        case lastAccessMillis
        case cleanupPending
    }

    init(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String,
        commitGeneration: Int64,
        commitNonce: String,
        modelRevision: Int64,
        fileName: String,
        byteLength: Int64,
        sha256: String,
        contentType: String,
        lastAccessMillis: Int64,
        cleanupPending: Bool = false
    ) {
        self.nativePartitionKey = nativePartitionKey
        self.nativeSubjectKey = nativeSubjectKey
        self.nativeBytesKey = nativeBytesKey
        self.commitGeneration = commitGeneration
        self.commitNonce = commitNonce
        self.modelRevision = modelRevision
        self.fileName = fileName
        self.byteLength = byteLength
        self.sha256 = sha256
        self.contentType = contentType
        self.lastAccessMillis = lastAccessMillis
        self.cleanupPending = cleanupPending
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        nativePartitionKey = try values.decode(String.self, forKey: .nativePartitionKey)
        nativeSubjectKey = try values.decode(String.self, forKey: .nativeSubjectKey)
        nativeBytesKey = try values.decode(String.self, forKey: .nativeBytesKey)
        commitGeneration = try values.decode(Int64.self, forKey: .commitGeneration)
        commitNonce = try values.decode(String.self, forKey: .commitNonce)
        modelRevision = try values.decode(Int64.self, forKey: .modelRevision)
        fileName = try values.decode(String.self, forKey: .fileName)
        byteLength = try values.decode(Int64.self, forKey: .byteLength)
        sha256 = try values.decode(String.self, forKey: .sha256)
        contentType = try values.decode(String.self, forKey: .contentType)
        lastAccessMillis = try values.decode(Int64.self, forKey: .lastAccessMillis)
        cleanupPending = try values.decodeIfPresent(Bool.self, forKey: .cleanupPending) ?? false
    }

    var slotIdentity: String {
        "\(nativePartitionKey):\(nativeSubjectKey):\(nativeBytesKey)"
    }

    func matches(_ slot: SlockAvatarNativeSlot) -> Bool {
        nativePartitionKey == slot.nativePartitionKey &&
            nativeSubjectKey == slot.nativeSubjectKey &&
            nativeBytesKey == slot.nativeBytesKey
    }

    func matches(_ identity: SlockAvatarCommitIdentity?) -> Bool {
        guard let identity else { return true }
        return commitGeneration == identity.commitGeneration && commitNonce == identity.nonce
    }
}

private struct SlockIosAvatarCleanupDebt: Codable, Equatable {
    let requestNonce: String
    let nativePartitionKey: String?
    let nativeSubjectKey: String?
    let nativeBytesKey: String?
    let relativeDirectory: String
    let fileName: String
    let byteLength: Int64

    func matches(_ slot: SlockAvatarNativeSlot) -> Bool {
        guard let nativePartitionKey, let nativeSubjectKey, let nativeBytesKey else { return true }
        return nativePartitionKey == slot.nativePartitionKey &&
            nativeSubjectKey == slot.nativeSubjectKey &&
            nativeBytesKey == slot.nativeBytesKey
    }
}

private enum SlockIosAvatarTerminalScopeKind: String, Codable {
    case exact
    case subject
    case owner
    case global
}

private struct SlockIosAvatarTerminalScope: Codable, Equatable {
    let id: String
    let kind: SlockIosAvatarTerminalScopeKind
    let nativePartitionKey: String?
    let nativeSubjectKey: String?
    let nativeBytesKey: String?
    let retireCommittedEntries: Bool
    let requestNonce: String?

    func matches(_ slot: SlockAvatarNativeSlot) -> Bool {
        switch kind {
        case .exact:
            return nativePartitionKey == slot.nativePartitionKey &&
                nativeSubjectKey == slot.nativeSubjectKey &&
                nativeBytesKey == slot.nativeBytesKey
        case .subject:
            return nativePartitionKey == slot.nativePartitionKey &&
                nativeSubjectKey == slot.nativeSubjectKey
        case .owner:
            return nativePartitionKey == slot.nativePartitionKey
        case .global:
            return true
        }
    }
}

private struct SlockIosAvatarIndex: Codable {
    var entries: [SlockIosAvatarEntry]
    var cleanupDebts: [SlockIosAvatarCleanupDebt]
    var terminalScopes: [SlockIosAvatarTerminalScope]

    private enum CodingKeys: String, CodingKey {
        case entries
        case cleanupDebts
        case terminalScopes
    }

    init(
        entries: [SlockIosAvatarEntry],
        cleanupDebts: [SlockIosAvatarCleanupDebt],
        terminalScopes: [SlockIosAvatarTerminalScope]
    ) {
        self.entries = entries
        self.cleanupDebts = cleanupDebts
        self.terminalScopes = terminalScopes
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        entries = try values.decodeIfPresent([SlockIosAvatarEntry].self, forKey: .entries) ?? []
        cleanupDebts = try values.decodeIfPresent([SlockIosAvatarCleanupDebt].self, forKey: .cleanupDebts) ?? []
        terminalScopes = try values.decodeIfPresent(
            [SlockIosAvatarTerminalScope].self,
            forKey: .terminalScopes
        ) ?? []
    }
}

private enum SlockIosAvatarOperationPhase {
    case open
    case finished
    case reserved
    case bound
    case finalized
    case terminal
}

private final class SlockIosAvatarOperation {
    let fence: SlockAvatarNativeStageFence
    let stagingURL: URL
    var handle: FileHandle?
    var phase: SlockIosAvatarOperationPhase = .open
    var sizeBytes: Int64 = 0
    var contentType: String?
    var reservation: SlockAvatarNativeReservation?
    var boundEntry: SlockIosAvatarEntry?

    init(fence: SlockAvatarNativeStageFence, stagingURL: URL, handle: FileHandle) {
        self.fence = fence
        self.stagingURL = stagingURL
        self.handle = handle
    }
}

final class SlockIosAvatarStageSink: NSObject, SlockAvatarNativeStageSink {
    private weak var store: SlockIosAvatarDurableStore?
    private let requestNonce: String

    fileprivate init(store: SlockIosAvatarDurableStore, requestNonce: String) {
        self.store = store
        self.requestNonce = requestNonce
    }

    func write(bytes: KotlinByteArray) -> Bool {
        var buffer = [UInt8]()
        buffer.reserveCapacity(Int(bytes.size))
        for index in 0..<bytes.size {
            buffer.append(UInt8(bitPattern: bytes.get(index: index)))
        }
        return store?.write(requestNonce: requestNonce, data: Data(buffer)) ?? false
    }

    func finish() -> SlockAvatarNativeStageFinish {
        store?.finish(requestNonce: requestNonce) ?? SlockAvatarNativeStageFinishStorageFailure.shared
    }

    func abort() {
        store?.abort(requestNonce: requestNonce)
    }
}

final class SlockIosAvatarDurableStore: NSObject, SlockAvatarMountedNativeProvider {
    private let lock = NSRecursiveLock()
    private let fileManager: FileManager
    private let rootURL: URL
    private let entriesURL: URL
    private let stagingURL: URL
    private let indexURL: URL
    private let maxManagedBytes: Int64
    private let indexWriteAllowed: () -> Bool
    private let destinationSyncAllowed: () -> Bool
    private var entries: [SlockIosAvatarEntry] = []
    private var cleanupDebts: [SlockIosAvatarCleanupDebt] = []
    private var terminalScopes: [SlockIosAvatarTerminalScope] = []
    private var operations: [String: SlockIosAvatarOperation] = [:]
    private var startupReady = false

    init(
        rootURL: URL,
        fileManager: FileManager = .default,
        maxManagedBytes: Int64 = slockIosAvatarMaxManagedBytes,
        indexWriteAllowed: @escaping () -> Bool = { true },
        destinationSyncAllowed: @escaping () -> Bool = { true }
    ) {
        self.rootURL = rootURL
        self.fileManager = fileManager
        self.maxManagedBytes = maxManagedBytes
        self.indexWriteAllowed = indexWriteAllowed
        self.destinationSyncAllowed = destinationSyncAllowed
        entriesURL = rootURL.appendingPathComponent("entries", isDirectory: true)
        stagingURL = rootURL.appendingPathComponent("staging", isDirectory: true)
        indexURL = rootURL.appendingPathComponent("index-v1.json", isDirectory: false)
        super.init()
        startupReady = recoverStartup()
    }

    func inspect(
        slot: SlockAvatarNativeSlot,
        completionHandler: @escaping (SlockAvatarMountedNativeInspection?, Error?) -> Void
    ) {
        let result = lock.withLock { () -> SlockAvatarMountedNativeInspection in
            guard !readBlocked(slot: slot), let index = entries.firstIndex(where: { $0.matches(slot) }) else {
                return SlockAvatarMountedNativeInspection(
                    supported: true,
                    bytesAvailable: false,
                    bytesDecodable: false
                )
            }
            guard !entries[index].cleanupPending else {
                _ = completeEntryCleanup(at: index)
                return SlockAvatarMountedNativeInspection(
                    supported: true,
                    bytesAvailable: false,
                    bytesDecodable: false
                )
            }
            var entry = entries[index]
            guard validatedData(for: entry) != nil else {
                _ = retireEntry(at: index)
                return SlockAvatarMountedNativeInspection(
                    supported: true,
                    bytesAvailable: false,
                    bytesDecodable: false
                )
            }
            entry.lastAccessMillis = nowMillis()
            entries[index] = entry
            _ = persistIndex()
            return SlockAvatarMountedNativeInspection(
                supported: true,
                bytesAvailable: true,
                bytesDecodable: true
            )
        }
        completionHandler(result, nil)
    }

    func beginStage(fence: SlockAvatarNativeStageFence) -> SlockAvatarNativeStageOpen {
        lock.withLock {
            guard startupReady, valid(slot: fence.slot), slockIosAvatarIsOpaque(fence.requestNonce) else {
                return SlockAvatarNativeStageOpenStorageFailure.shared
            }
            let matchingScopeIds = terminalScopes.filter { $0.matches(fence.slot) }.map(\.id)
            for scopeId in matchingScopeIds {
                _ = drainTerminalScope(id: scopeId)
            }
            guard !terminalScopes.contains(where: { $0.matches(fence.slot) }) else {
                return SlockAvatarNativeStageOpenBusyCleanup.shared
            }
            _ = completeCleanupDebts { $0.nativePartitionKey == nil }
            guard managedBytes <= maxManagedBytes else {
                return SlockAvatarNativeStageOpenBusyCleanup.shared
            }
            _ = completeCleanupDebts(matching: fence.slot)
            if let pendingIndex = entries.firstIndex(where: { $0.matches(fence.slot) && $0.cleanupPending }) {
                _ = completeEntryCleanup(at: pendingIndex)
            }
            guard !hasCleanupDebt(matching: fence.slot) else {
                return SlockAvatarNativeStageOpenBusyCleanup.shared
            }
            operations = operations.filter { _, operation in
                !sameSlot(operation.fence.slot, fence.slot) ||
                    (operation.phase != .finalized && operation.phase != .terminal)
            }
            if let existing = operations[fence.requestNonce] {
                guard sameFence(existing.fence, fence), existing.phase == .open else {
                    return SlockAvatarNativeStageOpenAdmissionClosed.shared
                }
                return SlockAvatarNativeStageOpenOpened(
                    sink: SlockIosAvatarStageSink(store: self, requestNonce: fence.requestNonce)
                )
            }
            guard !operations.values.contains(where: {
                (isActive($0.phase) || $0.phase == .terminal) && sameSlot($0.fence.slot, fence.slot)
            }) else {
                return SlockAvatarNativeStageOpenBusyCleanup.shared
            }
            let fileURL = stagingURL.appendingPathComponent("\(fence.requestNonce).tmp")
            do {
                if fileManager.fileExists(atPath: fileURL.path) {
                    try fileManager.removeItem(at: fileURL)
                }
                guard fileManager.createFile(atPath: fileURL.path, contents: nil) else {
                    return SlockAvatarNativeStageOpenStorageFailure.shared
                }
                let handle = try FileHandle(forWritingTo: fileURL)
                operations[fence.requestNonce] = SlockIosAvatarOperation(
                    fence: fence,
                    stagingURL: fileURL,
                    handle: handle
                )
                return SlockAvatarNativeStageOpenOpened(
                    sink: SlockIosAvatarStageSink(store: self, requestNonce: fence.requestNonce)
                )
            } catch {
                try? fileManager.removeItem(at: fileURL)
                return SlockAvatarNativeStageOpenStorageFailure.shared
            }
        }
    }

    func reserve(
        request: SlockAvatarNativeReservationRequest,
        completionHandler: @escaping (SlockAvatarNativeReserveResult?, Error?) -> Void
    ) {
        let result: SlockAvatarNativeReserveResult = lock.withLock {
            guard startupReady, let operation = operations[request.fence.requestNonce] else {
                return SlockAvatarNativeReserveResultStale.shared
            }
            guard sameFence(operation.fence, request.fence) else {
                return SlockAvatarNativeReserveResultExpectedIdentityMismatch.shared
            }
            if let reservation = operation.reservation {
                return reservation.candidateModelRevision == request.candidateModelRevision
                    ? SlockAvatarNativeReserveResultReserved(reservation: reservation)
                    : SlockAvatarNativeReserveResultExpectedIdentityMismatch.shared
            }
            guard operation.phase == .finished else {
                return operation.phase == .terminal
                    ? SlockAvatarNativeReserveResultStale.shared
                    : SlockAvatarNativeReserveResultBusyCleanup.shared
            }
            guard !hasCleanupDebt(matching: request.fence.slot) else {
                return SlockAvatarNativeReserveResultBusyCleanup.shared
            }
            let current = entries.first(where: { $0.matches(request.fence.slot) && !$0.cleanupPending })
            let expectedMatches = request.fence.expectedCommitIdentity.map { current?.matches($0) == true }
                ?? (current == nil)
            guard expectedMatches else {
                return SlockAvatarNativeReserveResultExpectedIdentityMismatch.shared
            }
            let floor = max(current?.commitGeneration ?? 0, request.fence.expectedCommitIdentity?.commitGeneration ?? 0)
            guard floor < Int64.max else {
                return SlockAvatarNativeReserveResultStorageFailure.shared
            }
            let reservation = SlockAvatarNativeReservation(
                requestNonce: request.fence.requestNonce,
                commitIdentity: SlockAvatarCommitIdentity(
                    commitGeneration: floor + 1,
                    nonce: slockIosAvatarNonce()
                ),
                candidateModelRevision: request.candidateModelRevision
            )
            operation.reservation = reservation
            operation.phase = .reserved
            return SlockAvatarNativeReserveResultReserved(reservation: reservation)
        }
        completionHandler(result, nil)
    }

    func bindStage(
        staged: SlockAvatarNativeStagedCandidate,
        reservation: SlockAvatarNativeReservation,
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        let result: SlockAvatarNativeOperationResult = lock.withLock {
            guard startupReady else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            guard let operation = operations[reservation.requestNonce] else {
                return SlockAvatarNativeOperationResultStale.shared
            }
            guard operation.phase == .reserved,
                  sameReservation(operation.reservation, reservation),
                  staged.requestNonce == reservation.requestNonce,
                  staged.sizeBytes == operation.sizeBytes,
                  operation.contentType == staged.contentType else {
                return SlockAvatarNativeOperationResultExpectedIdentityMismatch.shared
            }
            guard let data = try? Data(contentsOf: operation.stagingURL),
                  Int64(data.count) == staged.sizeBytes,
                  decodeImage(data) != nil else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            let identity = reservation.commitIdentity
            operation.boundEntry = SlockIosAvatarEntry(
                nativePartitionKey: operation.fence.slot.nativePartitionKey,
                nativeSubjectKey: operation.fence.slot.nativeSubjectKey,
                nativeBytesKey: operation.fence.slot.nativeBytesKey,
                commitGeneration: identity.commitGeneration,
                commitNonce: identity.nonce,
                modelRevision: reservation.candidateModelRevision,
                fileName: "\(operation.fence.slot.nativeBytesKey)-\(identity.commitGeneration)-\(identity.nonce).bin",
                byteLength: staged.sizeBytes,
                sha256: slockIosAvatarHex(data),
                contentType: staged.contentType ?? "image/unknown",
                lastAccessMillis: nowMillis()
            )
            operation.phase = .bound
            return SlockAvatarNativeOperationResultAccepted.shared
        }
        completionHandler(result, nil)
    }

    func abortStage(
        staged: SlockAvatarNativeStagedCandidate,
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        completionHandler(lock.withLock {
            guard startupReady else { return SlockAvatarNativeOperationResultStorageFailure.shared }
            return abortLocked(requestNonce: staged.requestNonce)
        }, nil)
    }

    func finalize(
        reservation: SlockAvatarNativeReservation,
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        let result: SlockAvatarNativeOperationResult = lock.withLock {
            guard startupReady else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            guard let operation = operations[reservation.requestNonce] else {
                return SlockAvatarNativeOperationResultStale.shared
            }
            if operation.phase == .finalized, sameReservation(operation.reservation, reservation) {
                let debtsClean = completeCleanupDebts(matching: operation.fence.slot)
                let budgetClean = trimToBudget(protectedEntry: operation.boundEntry)
                return debtsClean && budgetClean
                    ? SlockAvatarNativeOperationResultAccepted.shared
                    : SlockAvatarNativeOperationResultBusyCleanup.shared
            }
            if operation.phase == .terminal, sameReservation(operation.reservation, reservation) {
                return SlockAvatarNativeOperationResultBusyCleanup.shared
            }
            guard operation.phase == .bound,
                  sameReservation(operation.reservation, reservation),
                  let candidate = operation.boundEntry else {
                return SlockAvatarNativeOperationResultExpectedIdentityMismatch.shared
            }
            let destination = entriesURL.appendingPathComponent(candidate.fileName)
            var movedCandidate = false
            do {
                try operation.handle?.synchronize()
                try operation.handle?.close()
                operation.handle = nil
                if fileManager.fileExists(atPath: destination.path) {
                    try fileManager.removeItem(at: destination)
                }
                try fileManager.moveItem(at: operation.stagingURL, to: destination)
                movedCandidate = true
                guard destinationSyncAllowed() else { throw CocoaError(.fileWriteUnknown) }
                try synchronize(url: destination)
                let oldEntries = entries
                let oldCleanupDebts = cleanupDebts
                let replacedEntries = entries.filter { $0.slotIdentity == candidate.slotIdentity }
                entries.removeAll { $0.slotIdentity == candidate.slotIdentity }
                entries.append(candidate)
                cleanupDebts.append(contentsOf: replacedEntries.map { cleanupDebt(for: $0) })
                guard persistIndex() else {
                    entries = oldEntries
                    cleanupDebts = oldCleanupDebts
                    _ = recordMovedCandidateCleanup(
                        candidate,
                        operation: operation,
                        requestNonce: reservation.requestNonce
                    )
                    return SlockAvatarNativeOperationResultBusyCleanup.shared
                }
                operation.phase = .finalized
                let debtsClean = completeCleanupDebts(matching: operation.fence.slot)
                let budgetClean = trimToBudget(protectedEntry: candidate)
                return debtsClean && budgetClean
                    ? SlockAvatarNativeOperationResultAccepted.shared
                    : SlockAvatarNativeOperationResultBusyCleanup.shared
            } catch {
                if movedCandidate {
                    return recordMovedCandidateCleanup(
                        candidate,
                        operation: operation,
                        requestNonce: reservation.requestNonce
                    )
                        ? SlockAvatarNativeOperationResultBusyCleanup.shared
                        : SlockAvatarNativeOperationResultStorageFailure.shared
                }
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
        }
        completionHandler(result, nil)
    }

    func rollback(
        reservation: SlockAvatarNativeReservation,
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        let result: SlockAvatarNativeOperationResult = lock.withLock {
            guard startupReady else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            guard let operation = operations[reservation.requestNonce] else {
                if let index = entries.firstIndex(where: { $0.matches(reservation.commitIdentity) }) {
                    return installAndDrainTerminalScope(
                        terminalScope(
                            kind: .exact,
                            slot: slot(for: entries[index]),
                            retireCommittedEntries: true
                        )
                    )
                }
                if let debt = cleanupDebts.first(where: { $0.requestNonce == reservation.requestNonce }),
                   let debtSlot = slot(for: debt) {
                    return installAndDrainTerminalScope(
                        terminalScope(
                            kind: .exact,
                            slot: debtSlot,
                            retireCommittedEntries: false,
                            requestNonce: reservation.requestNonce
                        )
                    )
                }
                if let scope = terminalScopes.first(where: { $0.requestNonce == reservation.requestNonce }) {
                    return drainTerminalScope(id: scope.id)
                }
                return SlockAvatarNativeOperationResultAccepted.shared
            }
            guard sameReservation(operation.reservation, reservation) else {
                return SlockAvatarNativeOperationResultExpectedIdentityMismatch.shared
            }
            if operation.phase == .finalized || operation.phase == .terminal {
                return installAndDrainTerminalScope(
                    terminalScope(
                        kind: .exact,
                        slot: operation.fence.slot,
                        retireCommittedEntries: operation.phase == .finalized ||
                            entries.contains { $0.matches(reservation.commitIdentity) },
                        requestNonce: operation.phase == .terminal ? reservation.requestNonce : nil
                    )
                )
            }
            return abortLocked(requestNonce: reservation.requestNonce)
        }
        completionHandler(result, nil)
    }

    func retireExact(
        slot: SlockAvatarNativeSlot,
        expectedCommitIdentity: SlockAvatarCommitIdentity?,
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        completionHandler(lock.withLock {
            guard startupReady else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            let scope = terminalScope(kind: .exact, slot: slot, retireCommittedEntries: true)
            if terminalScopes.contains(where: { $0.id == scope.id }) {
                return drainTerminalScope(id: scope.id)
            }
            if let entry = entries.first(where: { $0.matches(slot) }), !entry.matches(expectedCommitIdentity) {
                return SlockAvatarNativeOperationResultExpectedIdentityMismatch.shared
            }
            return installAndDrainTerminalScope(scope)
        }, nil)
    }

    func retireSubject(
        slot: SlockAvatarNativeSlot,
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        completionHandler(lock.withLock {
            guard startupReady else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            return installAndDrainTerminalScope(
                terminalScope(kind: .subject, slot: slot, retireCommittedEntries: true)
            )
        }, nil)
    }

    func retireOwner(
        nativePartitionKey: String,
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        completionHandler(lock.withLock {
            guard startupReady, slockIosAvatarIsOpaque(nativePartitionKey) else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            return installAndDrainTerminalScope(
                SlockIosAvatarTerminalScope(
                    id: "owner:\(nativePartitionKey)",
                    kind: .owner,
                    nativePartitionKey: nativePartitionKey,
                    nativeSubjectKey: nil,
                    nativeBytesKey: nil,
                    retireCommittedEntries: true,
                    requestNonce: nil
                )
            )
        }, nil)
    }

    func clearAll(
        completionHandler: @escaping (SlockAvatarNativeOperationResult?, Error?) -> Void
    ) {
        completionHandler(lock.withLock {
            guard startupReady else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
            return installAndDrainTerminalScope(
                SlockIosAvatarTerminalScope(
                    id: "global",
                    kind: .global,
                    nativePartitionKey: nil,
                    nativeSubjectKey: nil,
                    nativeBytesKey: nil,
                    retireCommittedEntries: true,
                    requestNonce: nil
                )
            )
        }, nil)
    }

    fileprivate func write(requestNonce: String, data: Data) -> Bool {
        lock.withLock {
            guard let operation = operations[requestNonce], operation.phase == .open,
                  !data.isEmpty,
                  operation.sizeBytes <= slockIosAvatarMaxEntryBytes - Int64(data.count) else {
                return false
            }
            do {
                try operation.handle?.write(contentsOf: data)
                operation.sizeBytes += Int64(data.count)
                return true
            } catch {
                return false
            }
        }
    }

    fileprivate func finish(requestNonce: String) -> SlockAvatarNativeStageFinish {
        lock.withLock {
            guard let operation = operations[requestNonce], operation.phase == .open,
                  operation.sizeBytes > 0 else {
                return SlockAvatarNativeStageFinishStorageFailure.shared
            }
            do {
                try operation.handle?.synchronize()
                try operation.handle?.close()
                operation.handle = nil
                let data = try Data(contentsOf: operation.stagingURL)
                guard let decoded = decodeImage(data) else {
                    operation.phase = .terminal
                    _ = abortLocked(requestNonce: requestNonce)
                    return SlockAvatarNativeStageFinishInvalidContent.shared
                }
                operation.contentType = decoded.contentType
                operation.phase = .finished
                return SlockAvatarNativeStageFinishReady(
                    candidate: SlockAvatarNativeStagedCandidate(
                        requestNonce: requestNonce,
                        sizeBytes: operation.sizeBytes,
                        contentType: decoded.contentType
                    )
                )
            } catch {
                return SlockAvatarNativeStageFinishStorageFailure.shared
            }
        }
    }

    fileprivate func abort(requestNonce: String) {
        lock.withLock { _ = abortLocked(requestNonce: requestNonce) }
    }

    func image(forRenderModel model: String) -> UIImage? {
        guard let descriptor = parseRenderModel(model) else { return nil }
        return lock.withLock {
            let descriptorSlot = SlockAvatarNativeSlot(
                nativePartitionKey: descriptor.nativePartitionKey,
                nativeSubjectKey: descriptor.nativeSubjectKey,
                nativeBytesKey: descriptor.nativeBytesKey
            )
            guard !readBlocked(slot: descriptorSlot) else { return nil }
            guard let index = entries.firstIndex(where: {
                !$0.cleanupPending &&
                $0.nativePartitionKey == descriptor.nativePartitionKey &&
                    $0.nativeSubjectKey == descriptor.nativeSubjectKey &&
                    $0.nativeBytesKey == descriptor.nativeBytesKey &&
                    $0.commitGeneration == descriptor.commitGeneration &&
                    $0.commitNonce == descriptor.commitNonce &&
                    $0.modelRevision == descriptor.modelRevision
            }), let data = validatedData(for: entries[index]), let image = decodeImage(data)?.image else {
                return nil
            }
            entries[index].lastAccessMillis = nowMillis()
            _ = persistIndex()
            return image
        }
    }

    var managedBytes: Int64 {
        lock.withLock {
            entries.reduce(0) { $0 + $1.byteLength } +
                cleanupDebts.reduce(0) { $0 + $1.byteLength }
        }
    }

    private func recoverStartup() -> Bool {
        lock.withLock {
            do {
                try fileManager.createDirectory(at: rootURL, withIntermediateDirectories: true)
                try fileManager.createDirectory(at: entriesURL, withIntermediateDirectories: true)
                try fileManager.createDirectory(at: stagingURL, withIntermediateDirectories: true)
                if let data = try? Data(contentsOf: indexURL) {
                    let index = try? JSONDecoder().decode(SlockIosAvatarIndex.self, from: data)
                    entries = index?.entries ?? []
                    cleanupDebts = index?.cleanupDebts ?? []
                    terminalScopes = index?.terminalScopes ?? []
                }
                entries = entries.compactMap { entry in
                    let structurallyValid = slockIosAvatarIsOpaque(entry.nativePartitionKey) &&
                        slockIosAvatarIsOpaque(entry.nativeSubjectKey) &&
                        slockIosAvatarIsOpaque(entry.nativeBytesKey) &&
                        slockIosAvatarIsOpaque(entry.commitNonce) &&
                        slockIosAvatarIsOpaque(entry.sha256) &&
                        entry.commitGeneration > 0 && entry.modelRevision > 0 &&
                        entry.byteLength > 0 && entry.byteLength <= slockIosAvatarMaxEntryBytes
                    guard structurallyValid else {
                        cleanupDebts.append(cleanupDebt(for: entry))
                        return nil
                    }
                    if entry.cleanupPending || validatedData(for: entry) == nil {
                        var pending = entry
                        pending.cleanupPending = true
                        return pending
                    }
                    return entry
                }

                let referencedEntryNames = Set(entries.map(\.fileName))
                    .union(cleanupDebts.filter { $0.relativeDirectory == "entries" }.map(\.fileName))
                for file in try fileManager.contentsOfDirectory(
                    at: entriesURL,
                    includingPropertiesForKeys: [.fileSizeKey]
                ) where !referencedEntryNames.contains(file.lastPathComponent) {
                    cleanupDebts.append(orphanCleanupDebt(file: file, relativeDirectory: "entries"))
                }

                let referencedStagingNames = Set(
                    cleanupDebts.filter { $0.relativeDirectory == "staging" }.map(\.fileName)
                )
                for file in try fileManager.contentsOfDirectory(
                    at: stagingURL,
                    includingPropertiesForKeys: [.fileSizeKey]
                ) where !referencedStagingNames.contains(file.lastPathComponent) {
                    cleanupDebts.append(orphanCleanupDebt(file: file, relativeDirectory: "staging"))
                }

                if cleanupDebts.contains(where: { $0.nativePartitionKey == nil }),
                   !terminalScopes.contains(where: { $0.kind == .global }) {
                    terminalScopes.append(
                        SlockIosAvatarTerminalScope(
                            id: "global-orphan-recovery",
                            kind: .global,
                            nativePartitionKey: nil,
                            nativeSubjectKey: nil,
                            nativeBytesKey: nil,
                            retireCommittedEntries: false,
                            requestNonce: nil
                        )
                    )
                }

                guard persistIndex() else { return false }
                var index = entries.count - 1
                while index >= 0 && !entries.isEmpty {
                    if entries[index].cleanupPending,
                       !terminalScopes.contains(where: { $0.matches(slot(for: entries[index])) }) {
                        _ = completeEntryCleanup(at: index)
                    }
                    if index == 0 { break }
                    index -= 1
                }
                let scopeIds = terminalScopes.map(\.id)
                for scopeId in scopeIds {
                    _ = drainTerminalScope(id: scopeId)
                }
                _ = completeCleanupDebts { debt in
                    guard let debtSlot = slot(for: debt) else {
                        return !terminalScopes.contains(where: { $0.kind == .global })
                    }
                    return !terminalScopes.contains(where: { $0.matches(debtSlot) })
                }
                _ = trimToBudget()
                return persistIndex()
            } catch {
                return false
            }
        }
    }

    private func terminalScope(
        kind: SlockIosAvatarTerminalScopeKind,
        slot: SlockAvatarNativeSlot,
        retireCommittedEntries: Bool,
        requestNonce: String? = nil
    ) -> SlockIosAvatarTerminalScope {
        let id: String
        switch kind {
        case .exact:
            id = "exact:\(slot.nativePartitionKey):\(slot.nativeSubjectKey):\(slot.nativeBytesKey):\(retireCommittedEntries):\(requestNonce ?? "")"
        case .subject:
            id = "subject:\(slot.nativePartitionKey):\(slot.nativeSubjectKey)"
        case .owner:
            id = "owner:\(slot.nativePartitionKey)"
        case .global:
            id = "global"
        }
        return SlockIosAvatarTerminalScope(
            id: id,
            kind: kind,
            nativePartitionKey: slot.nativePartitionKey,
            nativeSubjectKey: slot.nativeSubjectKey,
            nativeBytesKey: slot.nativeBytesKey,
            retireCommittedEntries: retireCommittedEntries,
            requestNonce: requestNonce
        )
    }

    private func installAndDrainTerminalScope(
        _ scope: SlockIosAvatarTerminalScope
    ) -> SlockAvatarNativeOperationResult {
        if !terminalScopes.contains(where: { $0.id == scope.id }) {
            terminalScopes.append(scope)
            guard persistIndex() else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
        }
        return drainTerminalScope(id: scope.id)
    }

    private func drainTerminalScope(id: String) -> SlockAvatarNativeOperationResult {
        guard let scope = terminalScopes.first(where: { $0.id == id }) else {
            return SlockAvatarNativeOperationResultAccepted.shared
        }

        if scope.retireCommittedEntries {
            let originalEntries = entries
            var markedAny = false
            for index in entries.indices where scope.matches(slot(for: entries[index])) && !entries[index].cleanupPending {
                entries[index].cleanupPending = true
                markedAny = true
            }
            if markedAny, !persistIndex() {
                entries = originalEntries
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
        }

        var allClean = true
        let requestNonces = operations.values
            .filter { scope.matches($0.fence.slot) }
            .map { $0.fence.requestNonce }
        for requestNonce in requestNonces {
            if !recordOperationCleanup(requestNonce: requestNonce) {
                allClean = false
            }
        }

        if scope.retireCommittedEntries {
            var index = entries.count - 1
            while index >= 0 && !entries.isEmpty {
                if scope.matches(slot(for: entries[index])), !completeEntryCleanup(at: index) {
                    allClean = false
                }
                if index == 0 { break }
                index -= 1
            }
        }

        if !completeCleanupDebts(where: { debt in
            if debt.relativeDirectory == "staging", requestNonces.contains(debt.requestNonce) { return false }
            guard let debtSlot = slot(for: debt) else { return scope.kind == .global }
            return scope.matches(debtSlot)
        }) {
            allClean = false
        }

        let entriesRemain = scope.retireCommittedEntries && entries.contains { scope.matches(slot(for: $0)) }
        let debtsRemain = cleanupDebts.contains { debt in
            guard let debtSlot = slot(for: debt) else { return scope.kind == .global }
            return scope.matches(debtSlot)
        }
        guard allClean, !entriesRemain, !debtsRemain else {
            return SlockAvatarNativeOperationResultBusyCleanup.shared
        }

        guard let scopeIndex = terminalScopes.firstIndex(where: { $0.id == id }) else {
            return SlockAvatarNativeOperationResultAccepted.shared
        }
        let retainedOperations = operations.filter { scope.matches($0.value.fence.slot) }
        for requestNonce in retainedOperations.keys {
            operations.removeValue(forKey: requestNonce)
        }
        let removed = terminalScopes.remove(at: scopeIndex)
        guard persistIndex() else {
            for (requestNonce, operation) in retainedOperations {
                operations[requestNonce] = operation
            }
            terminalScopes.insert(removed, at: min(scopeIndex, terminalScopes.count))
            return SlockAvatarNativeOperationResultStorageFailure.shared
        }
        return SlockAvatarNativeOperationResultAccepted.shared
    }

    private func slot(for entry: SlockIosAvatarEntry) -> SlockAvatarNativeSlot {
        SlockAvatarNativeSlot(
            nativePartitionKey: entry.nativePartitionKey,
            nativeSubjectKey: entry.nativeSubjectKey,
            nativeBytesKey: entry.nativeBytesKey
        )
    }

    private func slot(for debt: SlockIosAvatarCleanupDebt) -> SlockAvatarNativeSlot? {
        guard let nativePartitionKey = debt.nativePartitionKey,
              let nativeSubjectKey = debt.nativeSubjectKey,
              let nativeBytesKey = debt.nativeBytesKey else { return nil }
        return SlockAvatarNativeSlot(
            nativePartitionKey: nativePartitionKey,
            nativeSubjectKey: nativeSubjectKey,
            nativeBytesKey: nativeBytesKey
        )
    }

    private func retireEntry(at index: Int) -> Bool {
        let original = entries[index]
        if !entries[index].cleanupPending {
            entries[index].cleanupPending = true
        }
        guard original.cleanupPending || persistIndex() else {
            entries[index] = original
            return false
        }
        return completeEntryCleanup(at: index)
    }

    private func trimToBudget(protectedEntry: SlockIosAvatarEntry? = nil) -> Bool {
        while managedBytes > maxManagedBytes {
            guard let entry = entries
                .filter({ !$0.cleanupPending && $0 != protectedEntry })
                .min(by: { $0.lastAccessMillis < $1.lastAccessMillis }),
                let index = entries.firstIndex(of: entry),
                retireEntry(at: index) else { return false }
        }
        return managedBytes <= maxManagedBytes
    }

    private func persistIndex() -> Bool {
        do {
            let data = try JSONEncoder().encode(
                SlockIosAvatarIndex(
                    entries: entries,
                    cleanupDebts: cleanupDebts,
                    terminalScopes: terminalScopes
                )
            )
            guard indexWriteAllowed() else { throw CocoaError(.fileWriteUnknown) }
            try atomicWrite(data, to: indexURL)
            return true
        } catch {
            return false
        }
    }

    private func atomicWrite(_ data: Data, to destination: URL) throws {
        let temporary = rootURL.appendingPathComponent(".\(UUID().uuidString).tmp")
        guard fileManager.createFile(atPath: temporary.path, contents: nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let handle = try FileHandle(forWritingTo: temporary)
        try handle.write(contentsOf: data)
        try handle.synchronize()
        try handle.close()
        if fileManager.fileExists(atPath: destination.path) {
            _ = try fileManager.replaceItemAt(destination, withItemAt: temporary)
        } else {
            try fileManager.moveItem(at: temporary, to: destination)
        }
        try synchronize(url: destination)
    }

    private func synchronize(url: URL) throws {
        let handle = try FileHandle(forReadingFrom: url)
        try handle.synchronize()
        try handle.close()
    }

    private func validatedData(for entry: SlockIosAvatarEntry) -> Data? {
        let url = entriesURL.appendingPathComponent(entry.fileName)
        guard let data = try? Data(contentsOf: url), Int64(data.count) == entry.byteLength,
              slockIosAvatarHex(data) == entry.sha256, decodeImage(data) != nil else { return nil }
        return data
    }

    private func cleanupDebt(
        for entry: SlockIosAvatarEntry,
        requestNonce: String = ""
    ) -> SlockIosAvatarCleanupDebt {
        SlockIosAvatarCleanupDebt(
            requestNonce: requestNonce,
            nativePartitionKey: entry.nativePartitionKey,
            nativeSubjectKey: entry.nativeSubjectKey,
            nativeBytesKey: entry.nativeBytesKey,
            relativeDirectory: "entries",
            fileName: entry.fileName,
            byteLength: entry.byteLength
        )
    }

    private func cleanupDebt(for operation: SlockIosAvatarOperation) -> SlockIosAvatarCleanupDebt {
        SlockIosAvatarCleanupDebt(
            requestNonce: operation.fence.requestNonce,
            nativePartitionKey: operation.fence.slot.nativePartitionKey,
            nativeSubjectKey: operation.fence.slot.nativeSubjectKey,
            nativeBytesKey: operation.fence.slot.nativeBytesKey,
            relativeDirectory: "staging",
            fileName: operation.stagingURL.lastPathComponent,
            byteLength: operation.sizeBytes
        )
    }

    private func orphanCleanupDebt(file: URL, relativeDirectory: String) -> SlockIosAvatarCleanupDebt {
        let size = Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        return SlockIosAvatarCleanupDebt(
            requestNonce: "",
            nativePartitionKey: nil,
            nativeSubjectKey: nil,
            nativeBytesKey: nil,
            relativeDirectory: relativeDirectory,
            fileName: file.lastPathComponent,
            byteLength: size
        )
    }

    private func debtURL(_ debt: SlockIosAvatarCleanupDebt) -> URL {
        (debt.relativeDirectory == "entries" ? entriesURL : stagingURL)
            .appendingPathComponent(debt.fileName)
    }

    private func hasCleanupDebt(matching slot: SlockAvatarNativeSlot) -> Bool {
        entries.contains { $0.cleanupPending && $0.matches(slot) } ||
            cleanupDebts.contains { $0.matches(slot) }
    }

    private func readBlocked(slot: SlockAvatarNativeSlot) -> Bool {
        !startupReady || terminalScopes.contains {
            $0.retireCommittedEntries && $0.matches(slot)
        }
    }

    private func recordMovedCandidateCleanup(
        _ candidate: SlockIosAvatarEntry,
        operation: SlockIosAvatarOperation,
        requestNonce: String
    ) -> Bool {
        operation.phase = .terminal
        let candidateDebt = cleanupDebt(for: candidate, requestNonce: requestNonce)
        if !cleanupDebts.contains(candidateDebt) {
            cleanupDebts.append(candidateDebt)
        }
        let hold = terminalScope(
            kind: .exact,
            slot: operation.fence.slot,
            retireCommittedEntries: false,
            requestNonce: requestNonce
        )
        if !terminalScopes.contains(where: { $0.id == hold.id }) {
            terminalScopes.append(hold)
        }
        return persistIndex()
    }

    private func completeEntryCleanup(at index: Int) -> Bool {
        guard entries.indices.contains(index), entries[index].cleanupPending else { return true }
        let entry = entries[index]
        let file = entriesURL.appendingPathComponent(entry.fileName)
        if fileManager.fileExists(atPath: file.path) {
            try? fileManager.removeItem(at: file)
        }
        guard !fileManager.fileExists(atPath: file.path) else { return false }
        entries.remove(at: index)
        guard persistIndex() else {
            entries.insert(entry, at: min(index, entries.count))
            return false
        }
        return true
    }

    private func completeCleanupDebts(matching slot: SlockAvatarNativeSlot) -> Bool {
        completeCleanupDebts { $0.matches(slot) }
    }

    private func completeCleanupDebts(
        where predicate: (SlockIosAvatarCleanupDebt) -> Bool
    ) -> Bool {
        var allClean = true
        var index = cleanupDebts.count - 1
        while index >= 0 && !cleanupDebts.isEmpty {
            if predicate(cleanupDebts[index]) {
                let debt = cleanupDebts[index]
                let file = debtURL(debt)
                if fileManager.fileExists(atPath: file.path) {
                    try? fileManager.removeItem(at: file)
                }
                if fileManager.fileExists(atPath: file.path) {
                    allClean = false
                } else {
                    cleanupDebts.remove(at: index)
                    if !persistIndex() {
                        cleanupDebts.insert(debt, at: min(index, cleanupDebts.count))
                        allClean = false
                    }
                }
            }
            if index == 0 { break }
            index -= 1
        }
        return allClean && !cleanupDebts.contains(where: predicate)
    }

    private func abortLocked(requestNonce: String) -> SlockAvatarNativeOperationResult {
        guard let operation = operations[requestNonce] else {
            let clean = completeCleanupDebts { $0.requestNonce == requestNonce }
            guard clean else { return SlockAvatarNativeOperationResultBusyCleanup.shared }
            let cleanupScopeIds = terminalScopes.filter { $0.requestNonce == requestNonce }.map(\.id)
            for scopeId in cleanupScopeIds {
                _ = drainTerminalScope(id: scopeId)
            }
            return terminalScopes.contains(where: { $0.requestNonce == requestNonce })
                ? SlockAvatarNativeOperationResultBusyCleanup.shared
                : SlockAvatarNativeOperationResultAccepted.shared
        }
        operation.phase = .terminal
        try? operation.handle?.close()
        operation.handle = nil
        let scope = terminalScope(
            kind: .exact,
            slot: operation.fence.slot,
            retireCommittedEntries: false,
            requestNonce: requestNonce
        )
        if !terminalScopes.contains(where: { $0.id == scope.id }) {
            terminalScopes.append(scope)
            guard persistIndex() else {
                return SlockAvatarNativeOperationResultStorageFailure.shared
            }
        }
        return drainTerminalScope(id: scope.id)
    }

    private func recordOperationCleanup(requestNonce: String) -> Bool {
        guard let operation = operations[requestNonce] else { return true }
        operation.phase = .terminal
        try? operation.handle?.close()
        operation.handle = nil
        let debt = cleanupDebt(for: operation)
        if fileManager.fileExists(atPath: operation.stagingURL.path), !cleanupDebts.contains(debt) {
            cleanupDebts.append(debt)
            guard persistIndex() else { return false }
        }
        if cleanupDebts.contains(debt), !completeCleanupDebts(where: { $0 == debt }) {
            return false
        }
        return true
    }

    private func valid(slot: SlockAvatarNativeSlot) -> Bool {
        slockIosAvatarIsOpaque(slot.nativePartitionKey) &&
            slockIosAvatarIsOpaque(slot.nativeSubjectKey) &&
            slockIosAvatarIsOpaque(slot.nativeBytesKey)
    }

    private func sameSlot(_ lhs: SlockAvatarNativeSlot, _ rhs: SlockAvatarNativeSlot) -> Bool {
        lhs.nativePartitionKey == rhs.nativePartitionKey &&
            lhs.nativeSubjectKey == rhs.nativeSubjectKey &&
            lhs.nativeBytesKey == rhs.nativeBytesKey
    }

    private func sameFence(_ lhs: SlockAvatarNativeStageFence, _ rhs: SlockAvatarNativeStageFence) -> Bool {
        sameSlot(lhs.slot, rhs.slot) && lhs.requestNonce == rhs.requestNonce &&
            lhs.expectedModelRevision == rhs.expectedModelRevision &&
            lhs.expectedCommitIdentity?.commitGeneration == rhs.expectedCommitIdentity?.commitGeneration &&
            lhs.expectedCommitIdentity?.nonce == rhs.expectedCommitIdentity?.nonce
    }

    private func sameReservation(
        _ lhs: SlockAvatarNativeReservation?,
        _ rhs: SlockAvatarNativeReservation
    ) -> Bool {
        lhs?.requestNonce == rhs.requestNonce &&
            lhs?.candidateModelRevision == rhs.candidateModelRevision &&
            lhs?.commitIdentity.commitGeneration == rhs.commitIdentity.commitGeneration &&
            lhs?.commitIdentity.nonce == rhs.commitIdentity.nonce
    }

    private func isActive(_ phase: SlockIosAvatarOperationPhase) -> Bool {
        switch phase {
        case .open, .finished, .reserved, .bound:
            true
        case .finalized, .terminal:
            false
        }
    }

    private func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private struct DecodedImage {
        let image: UIImage
        let contentType: String
    }

    private func decodeImage(_ data: Data) -> DecodedImage? {
        let options: [SDImageCoderOption: Any] = [
            .decodeFirstFrameOnly: true,
            .decodeThumbnailPixelSize: NSValue(cgSize: CGSize(width: 1024, height: 1024)),
            .decodePreserveAspectRatio: true,
        ]
        guard let image = SDImageCodersManager.shared.decodedImage(with: data, options: options),
              image.size.width > 0, image.size.height > 0 else { return nil }
        let type: String
        if data.starts(with: [0x89, 0x50, 0x4e, 0x47]) { type = "image/png" }
        else if data.starts(with: [0xff, 0xd8, 0xff]) { type = "image/jpeg" }
        else if data.starts(with: [0x47, 0x49, 0x46, 0x38]) { type = "image/gif" }
        else if data.count >= 12 && String(data: data[8..<12], encoding: .ascii) == "WEBP" { type = "image/webp" }
        else if String(data: data.prefix(1024), encoding: .utf8)?.lowercased().contains("<svg") == true {
            type = "image/svg+xml"
        } else { type = "image/unknown" }
        return DecodedImage(image: image, contentType: type)
    }

    private struct RenderDescriptor {
        let nativePartitionKey: String
        let nativeSubjectKey: String
        let nativeBytesKey: String
        let commitGeneration: Int64
        let commitNonce: String
        let modelRevision: Int64
    }

    private func parseRenderModel(_ model: String) -> RenderDescriptor? {
        guard model.hasPrefix(slockIosAvatarModelPrefix),
              let data = Data(base64Encoded: String(model.dropFirst(slockIosAvatarModelPrefix.count))),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["protocol"] as? String == "slock_avatar_render_v1",
              let partition = json["nativePartitionKey"] as? String,
              let subject = json["nativeSubjectKey"] as? String,
              let bytes = json["nativeBytesKey"] as? String,
              let generation = Int64(json["commitGeneration"] as? String ?? ""), generation > 0,
              let nonce = json["commitNonce"] as? String,
              let revision = Int64(json["modelRevision"] as? String ?? ""), revision > 0,
              [partition, subject, bytes, nonce].allSatisfy(slockIosAvatarIsOpaque) else { return nil }
        return RenderDescriptor(
            nativePartitionKey: partition,
            nativeSubjectKey: subject,
            nativeBytesKey: bytes,
            commitGeneration: generation,
            commitNonce: nonce,
            modelRevision: revision
        )
    }
}

@objcMembers
final class SlockIosAvatarCacheHost: NSObject {
    static let shared = SlockIosAvatarCacheHost()
    let store: SlockIosAvatarDurableStore
    /// Durable avatar store root. Settings → Storage measures it in the image-cache row; the
    /// bytes are cleared only through the shared avatar runtime (`store.clearAll`), never by path.
    let rootPath: String

    override private init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let root = support.appendingPathComponent("Raft/AvatarDurableV1", isDirectory: true)
        rootPath = root.path
        store = SlockIosAvatarDurableStore(rootURL: root)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableRoot = root
        try? mutableRoot.setResourceValues(values)
        super.init()
    }

    func install() {
        _ = SlockAvatarMountedNativeProviderRegistry.shared.install(next: store)
    }

    func isManagedRenderModel(_ model: String?) -> Bool {
        model?.hasPrefix(slockIosAvatarModelPrefix) == true
    }

    func imageForRenderModel(_ model: String) -> UIImage? {
        store.image(forRenderModel: model)
    }

    func handleLegacyCommand(_ command: [AnyHashable: Any], completion: @escaping ([String: Any]) -> Void) {
        guard command["protocol"] as? String == "slock_avatar_cache_v1",
              let action = command["action"] as? String,
              let partition = command["nativePartitionKey"] as? String,
              slockIosAvatarIsOpaque(partition) else {
            completion(["status": "unsupported"])
            return
        }
        let respond: (SlockAvatarNativeOperationResult?, Error?) -> Void = { [weak self] result, _ in
            guard let self else { return completion(["status": "error", "code": "avatar_cache_host_released"]) }
            let accepted = result is SlockAvatarNativeOperationResultAccepted
            completion([
                "status": accepted ? "ok" : "error",
                "code": accepted ? "" : "avatar_cache_native_cleanup_failed",
                "managedSet": "typed_avatar_only",
                "managedIndex": "opaque_partition_subject_bytes_v1",
                "budgetScope": "avatar_only",
                "indexedRetirement": true,
                "managedBytes": String(self.store.managedBytes),
            ])
        }
        switch action {
        case "retireOwner":
            store.retireOwner(nativePartitionKey: partition, completionHandler: respond)
        case "retireSubject":
            guard let subject = command["nativeSubjectKey"] as? String,
                  slockIosAvatarIsOpaque(subject) else {
                completion(["status": "error", "code": "avatar_cache_key_invalid"])
                return
            }
            let placeholder = String(repeating: "0", count: 64)
            store.retireSubject(
                slot: SlockAvatarNativeSlot(
                    nativePartitionKey: partition,
                    nativeSubjectKey: subject,
                    nativeBytesKey: placeholder
                ),
                completionHandler: respond
            )
        default:
            completion(["status": "unsupported"])
        }
    }
}

private extension NSLocking {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
