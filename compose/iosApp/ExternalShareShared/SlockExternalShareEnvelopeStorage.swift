import Foundation

enum SlockExternalShareContract {
    static let appGroupIdentifier = "group.build.raft.app.external-share"
    static let rootDirectoryName = "ExternalShareInbox"
    static let envelopeFileName = "envelope.json"
    static let consumedFileName = "consumed.txt"
    static let pendingFileName = "pending.txt"
    static let failureReceiptFileName = "last-error.json"
    static let maximumItems = 9
    static let maximumItemBytes: Int64 = 25 * 1024 * 1024
    static let maximumTotalBytes: Int64 = 100 * 1024 * 1024
    static let maximumTextCharacters = 256 * 1024
    static let envelopeTtl: TimeInterval = 24 * 60 * 60
}

struct SlockExternalShareCandidate {
    let url: URL
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
}

private struct SlockExternalShareEnvelopeItem: Codable {
    let localRef: String
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
}

private struct SlockExternalShareEnvelope: Codable {
    let token: String
    let createdAtEpochMillis: Int64
    let sourcePlatform: String
    let text: String
    let subject: String
    let items: [SlockExternalShareEnvelopeItem]
}

private struct SlockExternalShareFailureReceipt: Codable {
    let code: String
    let createdAtEpochMillis: Int64
}

enum SlockExternalShareStorageError: Error, Equatable {
    case rejected(String)

    var code: String {
        switch self {
        case .rejected(let code): return code
        }
    }
}

/// Fences every asynchronous provider callback to one collection generation. A provider may invoke
/// its completion more than once or after cancellation; neither can publish another envelope.
final class SlockExternalSharePublicationFence {
    private let lock = NSLock()
    private(set) var generation = UUID()
    private var acceptedProviderIndexes = Set<Int>()
    private var terminal = false

    func begin() -> UUID {
        lock.lock()
        defer { lock.unlock() }
        generation = UUID()
        acceptedProviderIndexes.removeAll(keepingCapacity: true)
        terminal = false
        return generation
    }

    func acceptProvider(index: Int, generation expected: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !terminal, generation == expected else { return false }
        return acceptedProviderIndexes.insert(index).inserted
    }

    func finish(generation expected: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !terminal, generation == expected else { return false }
        terminal = true
        return true
    }

    func invalidate() {
        lock.lock()
        generation = UUID()
        terminal = true
        lock.unlock()
    }
}

/// Owns the extension-to-host private byte handoff. Publication is staging -> immutable token
/// directory -> pending token, so a crash cannot expose a partial envelope and a reused token
/// cannot replace an earlier generation.
final class SlockExternalShareEnvelopeStorage {
    static let shared = SlockExternalShareEnvelopeStorage()

    private let fileManager: FileManager
    private let appGroupRootOverride: URL?
    private let clock: () -> Date
    private let tokenFactory: () -> String
    private let lock = NSLock()

    init(
        fileManager: FileManager = .default,
        appGroupRootOverride: URL? = nil,
        clock: @escaping () -> Date = Date.init,
        tokenFactory: @escaping () -> String = { UUID().uuidString.lowercased() }
    ) {
        self.fileManager = fileManager
        self.appGroupRootOverride = appGroupRootOverride
        self.clock = clock
        self.tokenFactory = tokenFactory
    }

    func admit(text: String, subject: String, candidates: [SlockExternalShareCandidate]) throws -> String {
        lock.lock()
        defer { lock.unlock() }
        let normalizedText = try boundedText(text)
        let normalizedSubject = try boundedText(subject)
        guard !normalizedText.isEmpty || !normalizedSubject.isEmpty || !candidates.isEmpty else {
            throw SlockExternalShareStorageError.rejected("external_share_empty")
        }
        guard candidates.count <= SlockExternalShareContract.maximumItems else {
            throw SlockExternalShareStorageError.rejected("external_share_too_many_items")
        }

        let root = try rootURL()
        try prune(root: root)
        let token = tokenFactory()
        guard Self.isSafeToken(token) else {
            throw SlockExternalShareStorageError.rejected("external_share_token_invalid")
        }
        let generation = UUID().uuidString.lowercased()
        let staging = root.appendingPathComponent(".staging-\(token)-\(generation)", isDirectory: true)
        let finalDirectory = root.appendingPathComponent(token, isDirectory: true)
        guard !fileManager.fileExists(atPath: finalDirectory.path) else {
            throw SlockExternalShareStorageError.rejected("external_share_token_conflict")
        }
        try fileManager.createDirectory(
            at: staging,
            withIntermediateDirectories: false,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )

        var totalBytes: Int64 = 0
        do {
            let items = try candidates.enumerated().map { index, candidate -> SlockExternalShareEnvelopeItem in
                let displayName = Self.sanitizedDisplayName(candidate.displayName, index: index)
                let destination = staging.appendingPathComponent("item-\(index + 1)-\(Self.storageName(displayName))")
                let size = try Self.copyBounded(
                    source: candidate.url,
                    destination: destination,
                    maximumBytes: SlockExternalShareContract.maximumItemBytes,
                    bytesBefore: totalBytes
                )
                guard size == candidate.sizeBytes else {
                    throw SlockExternalShareStorageError.rejected("external_share_item_changed")
                }
                totalBytes += size
                let finalURL = finalDirectory.appendingPathComponent(destination.lastPathComponent)
                return SlockExternalShareEnvelopeItem(
                    localRef: finalURL.absoluteString,
                    displayName: displayName,
                    mimeType: Self.sanitizedMimeType(candidate.mimeType),
                    sizeBytes: size
                )
            }
            let envelope = SlockExternalShareEnvelope(
                token: token,
                createdAtEpochMillis: Int64(clock().timeIntervalSince1970 * 1000),
                sourcePlatform: "ios",
                text: normalizedText,
                subject: normalizedSubject,
                items: items
            )
            let data = try JSONEncoder().encode(envelope)
            try data.write(
                to: staging.appendingPathComponent(SlockExternalShareContract.envelopeFileName),
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
            guard !fileManager.fileExists(atPath: finalDirectory.path) else {
                throw SlockExternalShareStorageError.rejected("external_share_token_conflict")
            }
            try fileManager.moveItem(at: staging, to: finalDirectory)
            try Data(token.utf8).write(
                to: root.appendingPathComponent(SlockExternalShareContract.pendingFileName),
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
            return token
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }
    }

    func pendingToken() -> String? {
        lock.lock()
        defer { lock.unlock() }
        guard let root = try? rootURL() else { return nil }
        let pending = root.appendingPathComponent(SlockExternalShareContract.pendingFileName)
        guard
            let token = try? String(contentsOf: pending, encoding: .utf8)
                .trimmingCharacters(in: .whitespacesAndNewlines),
            Self.isSafeToken(token)
        else {
            try? fileManager.removeItem(at: pending)
            return nil
        }
        let directory = root.appendingPathComponent(token, isDirectory: true)
        let envelope = directory.appendingPathComponent(SlockExternalShareContract.envelopeFileName)
        let consumed = directory.appendingPathComponent(SlockExternalShareContract.consumedFileName)
        guard fileManager.fileExists(atPath: envelope.path), !fileManager.fileExists(atPath: consumed.path) else {
            try? fileManager.removeItem(at: pending)
            return nil
        }
        return token
    }

    func recordFailure(code: String) {
        lock.lock()
        defer { lock.unlock() }
        guard let root = try? rootURL() else { return }
        let receipt = SlockExternalShareFailureReceipt(
            code: code,
            createdAtEpochMillis: Int64(clock().timeIntervalSince1970 * 1000)
        )
        guard let data = try? JSONEncoder().encode(receipt) else { return }
        try? data.write(
            to: root.appendingPathComponent(SlockExternalShareContract.failureReceiptFileName),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    private func rootURL() throws -> URL {
        let group = appGroupRootOverride ?? fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: SlockExternalShareContract.appGroupIdentifier
        )
        guard let group else {
            throw SlockExternalShareStorageError.rejected("external_share_app_group_unavailable")
        }
        let root = group.appendingPathComponent(SlockExternalShareContract.rootDirectoryName, isDirectory: true)
        try fileManager.createDirectory(
            at: root,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return root
    }

    private func prune(root: URL) throws {
        let now = clock()
        for candidate in try fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.contentModificationDateKey, .isDirectoryKey],
            options: []
        ) {
            if [SlockExternalShareContract.pendingFileName, SlockExternalShareContract.failureReceiptFileName]
                .contains(candidate.lastPathComponent) {
                continue
            }
            let values = try? candidate.resourceValues(forKeys: [.contentModificationDateKey, .isDirectoryKey])
            let age = values?.contentModificationDate.map { now.timeIntervalSince($0) } ?? .infinity
            if values?.isDirectory != true || age > SlockExternalShareContract.envelopeTtl {
                try? fileManager.removeItem(at: candidate)
            }
        }
    }

    private func boundedText(_ value: String) throws -> String {
        guard value.count <= SlockExternalShareContract.maximumTextCharacters else {
            throw SlockExternalShareStorageError.rejected("external_share_text_too_large")
        }
        return value
    }

    static func copyBounded(
        source: URL,
        destination: URL,
        maximumBytes: Int64,
        bytesBefore: Int64 = 0
    ) throws -> Int64 {
        guard source.isFileURL else {
            throw SlockExternalShareStorageError.rejected("external_share_item_unreadable")
        }
        guard FileManager.default.createFile(
            atPath: destination.path,
            contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        ) else {
            throw SlockExternalShareStorageError.rejected("external_share_copy_failed")
        }
        let input = try FileHandle(forReadingFrom: source)
        let output = try FileHandle(forWritingTo: destination)
        defer {
            try? input.close()
            try? output.close()
        }
        var copied: Int64 = 0
        while true {
            let data = try input.read(upToCount: 64 * 1024) ?? Data()
            if data.isEmpty { break }
            copied += Int64(data.count)
            guard copied <= maximumBytes else {
                throw SlockExternalShareStorageError.rejected("external_share_item_too_large")
            }
            guard bytesBefore + copied <= SlockExternalShareContract.maximumTotalBytes else {
                throw SlockExternalShareStorageError.rejected("external_share_total_too_large")
            }
            try output.write(contentsOf: data)
        }
        guard copied > 0 else {
            throw SlockExternalShareStorageError.rejected("external_share_item_empty")
        }
        try output.synchronize()
        return copied
    }

    static func isSafeToken(_ value: String) -> Bool {
        value.range(of: #"^[A-Za-z0-9._~-]{16,200}$"#, options: .regularExpression) != nil
    }

    private static func sanitizedDisplayName(_ value: String, index: Int) -> String {
        let candidate = URL(fileURLWithPath: value).lastPathComponent
            .unicodeScalars
            .map { CharacterSet.controlCharacters.contains($0) ? "_" : String($0) }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty, candidate != ".", candidate != ".." else {
            return "shared-item-\(index + 1)"
        }
        return String(candidate.prefix(120))
    }

    private static func storageName(_ value: String) -> String {
        value.map { character in
            character.isASCII && (character.isLetter || character.isNumber || "._-".contains(character))
                ? character : "_"
        }.map(String.init).joined()
    }

    private static func sanitizedMimeType(_ value: String) -> String {
        let candidate = value.split(separator: ";", maxSplits: 1).first.map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return candidate.isEmpty || candidate.count > 255 ? "application/octet-stream" : candidate
    }
}
