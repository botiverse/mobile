import Foundation
import UniformTypeIdentifiers

enum RaftExternalShareContract {
    private static let supportedHostLaunchSchemes = Set(["raft", "raft-debug"])

    static let appGroupIdentifier = "group.build.raft.app.external-share"
    static let rootDirectoryName = "ExternalShareInbox"
    static let envelopeFileName = "envelope.json"
    static let consumedFileName = "consumed.txt"
    static let pendingFileName = "pending.txt"
    static let failureReceiptFileName = "last-error.json"
    static let maximumItems = 9
    static let maximumItemBytes: Int64 = 50 * 1024 * 1024
    static let maximumTotalBytes: Int64 = 100 * 1024 * 1024
    static let maximumTextCharacters = 256 * 1024
    static let envelopeTtl: TimeInterval = 24 * 60 * 60

    static func hostLaunchURL(token: String, configuredScheme: String?) throws -> URL {
        guard RaftExternalShareEnvelopeStorage.isSafeToken(token) else {
            throw RaftExternalShareStorageError.rejected("external_share_token_invalid")
        }
        guard let scheme = normalizedHostLaunchScheme(configuredScheme) else {
            throw RaftExternalShareStorageError.rejected("external_share_launch_scheme_invalid")
        }
        var components = URLComponents()
        components.scheme = scheme
        components.host = "external-share"
        components.queryItems = [URLQueryItem(name: "token", value: token)]
        guard let url = components.url else {
            throw RaftExternalShareStorageError.rejected("external_share_launch_url_invalid")
        }
        return url
    }

    static func hostLaunchToken(from url: URL, configuredScheme: String?) -> String? {
        guard
            let expectedScheme = normalizedHostLaunchScheme(configuredScheme),
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.scheme?.lowercased() == expectedScheme,
            components.host?.lowercased() == "external-share",
            components.path.isEmpty || components.path == "/",
            components.queryItems?.count == 1,
            components.queryItems?.first?.name == "token",
            let token = components.queryItems?.first?.value,
            RaftExternalShareEnvelopeStorage.isSafeToken(token)
        else { return nil }
        return token
    }

    private static func normalizedHostLaunchScheme(_ configuredScheme: String?) -> String? {
        let scheme = configuredScheme?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard let scheme, supportedHostLaunchSchemes.contains(scheme) else { return nil }
        return scheme
    }
}

enum RaftExternalShareProviderAction: Equatable {
    case fileRepresentation(typeIdentifier: String, fallbackToFileURL: Bool)
    case fileURL
    case url
    case text(typeIdentifier: String)
    case unsupported
}

/// Pure routing rules for classifying an `NSItemProvider`'s registered UTIs.
///
/// Priority order prevents third-party apps (such as WeChat sharing a `.zip` via `public.file-url`
/// or Files sharing `.txt`/`.md`/`.json` documents) from having file attachments misclassified as
/// literal `file://` URL strings or rejected because text documents conform to `public.text`.
enum RaftExternalShareProviderRouting {
    static func classify(registeredTypeIdentifiers: [String]) -> RaftExternalShareProviderAction {
        let hasFileURL = registeredTypeIdentifiers.contains { identifier in
            guard let type = UTType(identifier) else { return false }
            return type.conforms(to: .fileURL)
        }

        // 1. Concrete non-text, non-URL binary/media/archive types (e.g. zip, jpeg, pdf, mp4).
        if let concreteFileType = registeredTypeIdentifiers.first(where: { identifier in
            guard let type = UTType(identifier) else { return false }
            return type.conforms(to: .data)
                && !type.conforms(to: .text)
                && !type.conforms(to: .url)
                && type != .data
                && type != .item
        }) {
            return .fileRepresentation(typeIdentifier: concreteFileType, fallbackToFileURL: hasFileURL)
        }

        // 2. File URLs (covers WeChat `.zip` shared as `public.file-url`, text documents like
        //    `.txt`/`.md`/`.json`/`.csv`, and `["public.data", "public.file-url"]` with real names).
        if hasFileURL {
            return .fileURL
        }

        // 3. Generic binary data without a file URL.
        if let genericDataType = registeredTypeIdentifiers.first(where: { identifier in
            guard let type = UTType(identifier) else { return false }
            return type.conforms(to: .data)
                && !type.conforms(to: .text)
                && !type.conforms(to: .url)
        }) {
            return .fileRepresentation(typeIdentifier: genericDataType, fallbackToFileURL: false)
        }

        // 4. Web or app URLs (ShareViewController still promotes `url.isFileURL` to a file).
        if registeredTypeIdentifiers.contains(where: { UTType($0)?.conforms(to: .url) == true }) {
            return .url
        }

        // 5. Plain text (preferred) or other text payloads.
        if let plainTextType = registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .plainText) == true
        }) {
            return .text(typeIdentifier: plainTextType)
        }
        if let anyTextType = registeredTypeIdentifiers.first(where: {
            UTType($0)?.conforms(to: .text) == true
        }) {
            return .text(typeIdentifier: anyTextType)
        }

        return .unsupported
    }
}

struct RaftExternalShareCandidate {
    let url: URL
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
}

private struct RaftExternalShareEnvelopeItem: Codable {
    let localRef: String
    let displayName: String
    let mimeType: String
    let sizeBytes: Int64
}

private struct RaftExternalShareEnvelope: Codable {
    let token: String
    let createdAtEpochMillis: Int64
    let sourcePlatform: String
    let text: String
    let subject: String
    let items: [RaftExternalShareEnvelopeItem]
}

private struct RaftExternalShareFailureReceipt: Codable {
    let code: String
    let createdAtEpochMillis: Int64
}

enum RaftExternalShareStorageError: Error, Equatable {
    case rejected(String)

    var code: String {
        switch self {
        case .rejected(let code): return code
        }
    }
}

/// Fences every asynchronous provider callback to one collection generation. A provider may invoke
/// its completion more than once or after cancellation; neither can publish another envelope.
final class RaftExternalSharePublicationFence {
    private let lock = NSLock()
    private(set) var generation = UUID()
    private var acceptedProviderIndexes = Set<Int>()
    private var acceptedFallbackIndexes = Set<Int>()
    private var terminal = false

    func begin() -> UUID {
        lock.lock()
        defer { lock.unlock() }
        generation = UUID()
        acceptedProviderIndexes.removeAll(keepingCapacity: true)
        acceptedFallbackIndexes.removeAll(keepingCapacity: true)
        terminal = false
        return generation
    }

    func acceptProvider(index: Int, generation expected: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !terminal, generation == expected else { return false }
        return acceptedProviderIndexes.insert(index).inserted
    }

    func acceptProviderFallback(index: Int, generation expected: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !terminal, generation == expected else { return false }
        return acceptedFallbackIndexes.insert(index).inserted
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
final class RaftExternalShareEnvelopeStorage {
    static let shared = RaftExternalShareEnvelopeStorage()

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

    func admit(text: String, subject: String, candidates: [RaftExternalShareCandidate]) throws -> String {
        lock.lock()
        defer { lock.unlock() }
        let normalizedText = try boundedText(text)
        let normalizedSubject = try boundedText(subject)
        let hasTextContent = !normalizedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasSubjectContent = !normalizedSubject.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        guard hasTextContent || hasSubjectContent || !candidates.isEmpty else {
            throw RaftExternalShareStorageError.rejected("external_share_empty")
        }
        guard candidates.count <= RaftExternalShareContract.maximumItems else {
            throw RaftExternalShareStorageError.rejected("external_share_too_many_items")
        }

        let root = try rootURL()
        try prune(root: root)
        let token = tokenFactory()
        guard Self.isSafeToken(token) else {
            throw RaftExternalShareStorageError.rejected("external_share_token_invalid")
        }
        let generation = UUID().uuidString.lowercased()
        let staging = root.appendingPathComponent(".staging-\(token)-\(generation)", isDirectory: true)
        let finalDirectory = root.appendingPathComponent(token, isDirectory: true)
        guard !fileManager.fileExists(atPath: finalDirectory.path) else {
            throw RaftExternalShareStorageError.rejected("external_share_token_conflict")
        }
        try fileManager.createDirectory(
            at: staging,
            withIntermediateDirectories: false,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )

        var totalBytes: Int64 = 0
        do {
            let items = try candidates.enumerated().map { index, candidate -> RaftExternalShareEnvelopeItem in
                let displayName = Self.sanitizedDisplayName(candidate.displayName, index: index)
                let destination = staging.appendingPathComponent("item-\(index + 1)-\(Self.storageName(displayName))")
                let size = try Self.copyBounded(
                    source: candidate.url,
                    destination: destination,
                    maximumBytes: RaftExternalShareContract.maximumItemBytes,
                    bytesBefore: totalBytes
                )
                guard size == candidate.sizeBytes else {
                    throw RaftExternalShareStorageError.rejected("external_share_item_changed")
                }
                totalBytes += size
                let finalURL = finalDirectory.appendingPathComponent(destination.lastPathComponent)
                return RaftExternalShareEnvelopeItem(
                    localRef: finalURL.absoluteString,
                    displayName: displayName,
                    mimeType: Self.resolvedMimeType(
                        typeIdentifier: nil,
                        rawMimeType: candidate.mimeType,
                        displayName: displayName
                    ),
                    sizeBytes: size
                )
            }
            let envelope = RaftExternalShareEnvelope(
                token: token,
                createdAtEpochMillis: Int64(clock().timeIntervalSince1970 * 1000),
                sourcePlatform: "ios",
                text: normalizedText,
                subject: normalizedSubject,
                items: items
            )
            let data = try JSONEncoder().encode(envelope)
            try data.write(
                to: staging.appendingPathComponent(RaftExternalShareContract.envelopeFileName),
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
            guard !fileManager.fileExists(atPath: finalDirectory.path) else {
                throw RaftExternalShareStorageError.rejected("external_share_token_conflict")
            }
            try fileManager.moveItem(at: staging, to: finalDirectory)
            try Data(token.utf8).write(
                to: root.appendingPathComponent(RaftExternalShareContract.pendingFileName),
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
        let pending = root.appendingPathComponent(RaftExternalShareContract.pendingFileName)
        guard
            let token = try? String(contentsOf: pending, encoding: .utf8)
                .trimmingCharacters(in: .whitespacesAndNewlines),
            Self.isSafeToken(token)
        else {
            try? fileManager.removeItem(at: pending)
            return nil
        }
        let directory = root.appendingPathComponent(token, isDirectory: true)
        let envelope = directory.appendingPathComponent(RaftExternalShareContract.envelopeFileName)
        let consumed = directory.appendingPathComponent(RaftExternalShareContract.consumedFileName)
        guard fileManager.fileExists(atPath: envelope.path), !fileManager.fileExists(atPath: consumed.path) else {
            try? fileManager.removeItem(at: pending)
            return nil
        }
        let nowMillis = Int64(clock().timeIntervalSince1970 * 1000)
        let ttlMillis = Int64(RaftExternalShareContract.envelopeTtl * 1000)
        guard
            let data = try? Data(contentsOf: envelope),
            let decoded = try? JSONDecoder().decode(RaftExternalShareEnvelope.self, from: data),
            decoded.token == token,
            nowMillis - decoded.createdAtEpochMillis <= ttlMillis,
            decoded.createdAtEpochMillis - nowMillis <= 5_000
        else {
            try? fileManager.removeItem(at: pending)
            return nil
        }
        return token
    }

    func delete(token: String) {
        lock.lock()
        defer { lock.unlock() }
        guard Self.isSafeToken(token), let root = try? rootURL() else { return }
        let directory = root.appendingPathComponent(token, isDirectory: true)
        try? fileManager.removeItem(at: directory)
        let pending = root.appendingPathComponent(RaftExternalShareContract.pendingFileName)
        if let current = try? String(contentsOf: pending, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines),
            current == token {
            try? fileManager.removeItem(at: pending)
        }
    }

    func recordFailure(code: String) {
        lock.lock()
        defer { lock.unlock() }
        guard let root = try? rootURL() else { return }
        let receipt = RaftExternalShareFailureReceipt(
            code: code,
            createdAtEpochMillis: Int64(clock().timeIntervalSince1970 * 1000)
        )
        guard let data = try? JSONEncoder().encode(receipt) else { return }
        try? data.write(
            to: root.appendingPathComponent(RaftExternalShareContract.failureReceiptFileName),
            options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    private func rootURL() throws -> URL {
        let group = appGroupRootOverride ?? fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: RaftExternalShareContract.appGroupIdentifier
        )
        guard let group else {
            throw RaftExternalShareStorageError.rejected("external_share_app_group_unavailable")
        }
        let root = group.appendingPathComponent(RaftExternalShareContract.rootDirectoryName, isDirectory: true)
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
            if [RaftExternalShareContract.pendingFileName, RaftExternalShareContract.failureReceiptFileName]
                .contains(candidate.lastPathComponent) {
                continue
            }
            let values = try? candidate.resourceValues(forKeys: [.contentModificationDateKey, .isDirectoryKey])
            let age = values?.contentModificationDate.map { now.timeIntervalSince($0) } ?? .infinity
            if values?.isDirectory != true || age > RaftExternalShareContract.envelopeTtl {
                try? fileManager.removeItem(at: candidate)
            }
        }
    }

    private func boundedText(_ value: String) throws -> String {
        guard value.count <= RaftExternalShareContract.maximumTextCharacters else {
            throw RaftExternalShareStorageError.rejected("external_share_text_too_large")
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
            throw RaftExternalShareStorageError.rejected("external_share_item_unreadable")
        }
        let resourceValues = try? source.resourceValues(forKeys: [.isDirectoryKey])
        if resourceValues?.isDirectory == true {
            throw RaftExternalShareStorageError.rejected("external_share_item_unsupported")
        }
        guard FileManager.default.createFile(
            atPath: destination.path,
            contents: nil,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        ) else {
            throw RaftExternalShareStorageError.rejected("external_share_copy_failed")
        }
        let input: FileHandle
        let output: FileHandle
        do {
            input = try FileHandle(forReadingFrom: source)
            output = try FileHandle(forWritingTo: destination)
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw RaftExternalShareStorageError.rejected("external_share_item_unreadable")
        }
        defer {
            try? input.close()
            try? output.close()
        }
        var copied: Int64 = 0
        do {
            while true {
                let done: Bool = try autoreleasepool {
                    let data = try input.read(upToCount: 64 * 1024) ?? Data()
                    if data.isEmpty { return true }
                    copied += Int64(data.count)
                    guard copied <= maximumBytes else {
                        throw RaftExternalShareStorageError.rejected("external_share_item_too_large")
                    }
                    guard bytesBefore + copied <= RaftExternalShareContract.maximumTotalBytes else {
                        throw RaftExternalShareStorageError.rejected("external_share_total_too_large")
                    }
                    try output.write(contentsOf: data)
                    return false
                }
                if done { break }
            }
            guard copied > 0 else {
                throw RaftExternalShareStorageError.rejected("external_share_item_empty")
            }
            try output.synchronize()
            return copied
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
    }

    static func isSafeToken(_ value: String) -> Bool {
        value.range(of: #"^[A-Za-z0-9._~-]{16,200}$"#, options: .regularExpression) != nil
    }

    static func resolvedDisplayName(
        suggestedName: String?,
        sourceURL: URL,
        typeIdentifier: String?,
        index: Int
    ) -> String {
        let trimmedSuggested = suggestedName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let rawBase = trimmedSuggested.isEmpty ? sourceURL.lastPathComponent : trimmedSuggested
        let sanitized = sanitizedDisplayName(rawBase, index: index)
        let inferredExtension = sanitizedExtension(sourceURL.pathExtension)
            ?? preferredFilenameExtension(for: typeIdentifier)
        guard let ext = inferredExtension, !ext.isEmpty else {
            return sanitized
        }
        let suffix = ".\(ext)"
        if sanitized.lowercased().hasSuffix(suffix) {
            return sanitized
        }
        if let existingExt = sanitizedExtension((sanitized as NSString).pathExtension),
            let existingType = UTType(filenameExtension: existingExt),
            existingType.isDeclared {
            if let inferredType = UTType(filenameExtension: ext), inferredType.isDeclared {
                if existingType == inferredType
                    || existingType.conforms(to: inferredType)
                    || inferredType.conforms(to: existingType) {
                    return sanitized
                }
            } else {
                // Keep a declared extension like `.txt` rather than appending an undeclared temp suffix like `.tmp`.
                return sanitized
            }
        }
        let maxStemLength = max(1, 120 - suffix.count)
        let stem = String(sanitized.prefix(maxStemLength))
        return stem + suffix
    }

    static func resolvedMimeType(
        typeIdentifier: String?,
        rawMimeType: String? = nil,
        displayName: String
    ) -> String {
        if let typeIdentifier,
            let type = UTType(typeIdentifier),
            type != .data,
            type != .item,
            type != .fileURL,
            type != .url,
            let mime = type.preferredMIMEType {
            let normalized = sanitizedMimeType(mime)
            if normalized != "application/octet-stream" {
                return normalized
            }
        }
        if let rawMimeType {
            let normalized = sanitizedMimeType(rawMimeType)
            if normalized != "application/octet-stream" {
                return normalized
            }
        }
        let ext = (displayName as NSString).pathExtension
        if !ext.isEmpty, let byExtension = UTType(filenameExtension: ext)?.preferredMIMEType {
            return sanitizedMimeType(byExtension)
        }
        return "application/octet-stream"
    }

    private static func preferredFilenameExtension(for typeIdentifier: String?) -> String? {
        guard let typeIdentifier,
            let type = UTType(typeIdentifier),
            type != .data,
            type != .item,
            type != .fileURL,
            type != .url,
            let ext = type.preferredFilenameExtension
        else { return nil }
        return sanitizedExtension(ext)
    }

    private static func sanitizedExtension(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 16 else { return nil }
        guard trimmed.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
        return trimmed.lowercased()
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
