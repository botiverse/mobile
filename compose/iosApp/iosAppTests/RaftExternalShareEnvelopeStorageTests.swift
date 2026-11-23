import XCTest

final class RaftExternalShareEnvelopeStorageTests: XCTestCase {
    func testHostLaunchURLUsesConfiguredDebugAndReleaseSchemes() throws {
        let token = "opaque-token_1234567890"
        XCTAssertEqual(
            try RaftExternalShareContract.hostLaunchURL(token: token, configuredScheme: "raft-debug").absoluteString,
            "raft-debug://external-share?token=opaque-token_1234567890"
        )
        XCTAssertEqual(
            try RaftExternalShareContract.hostLaunchURL(token: token, configuredScheme: "raft").absoluteString,
            "raft://external-share?token=opaque-token_1234567890"
        )
    }

    func testHostLaunchURLRejectsMissingOrUnknownScheme() {
        for scheme in [nil, "", "https", "raft-alpha", "raft-beta", "raft-dev"] as [String?] {
            XCTAssertThrowsError(
                try RaftExternalShareContract.hostLaunchURL(
                    token: "opaque-token_1234567890",
                    configuredScheme: scheme
                )
            ) { error in
                XCTAssertEqual(
                    error as? RaftExternalShareStorageError,
                    .rejected("external_share_launch_scheme_invalid")
                )
            }
        }
    }

    func testHostLaunchAcceptsOnlyMatchingBuildVariantScheme() throws {
        let token = "opaque-token_1234567890"
        let debugURL = try RaftExternalShareContract.hostLaunchURL(
            token: token,
            configuredScheme: "raft-debug"
        )
        let releaseURL = try RaftExternalShareContract.hostLaunchURL(
            token: token,
            configuredScheme: "raft"
        )

        XCTAssertEqual(
            RaftExternalShareContract.hostLaunchToken(from: debugURL, configuredScheme: "raft-debug"),
            token
        )
        XCTAssertNil(RaftExternalShareContract.hostLaunchToken(from: debugURL, configuredScheme: "raft"))
        XCTAssertEqual(
            RaftExternalShareContract.hostLaunchToken(from: releaseURL, configuredScheme: "raft"),
            token
        )
        XCTAssertNil(RaftExternalShareContract.hostLaunchToken(from: releaseURL, configuredScheme: "raft-debug"))
    }

    func testHostLaunchRejectsUnsupportedConfiguredSchemeAndMalformedRoute() throws {
        let validURL = try XCTUnwrap(URL(string: "raft-debug://external-share?token=opaque-token_1234567890"))
        for scheme in [nil, "", "raft-alpha", "raft-beta", "raft-dev", "https"] as [String?] {
            XCTAssertNil(RaftExternalShareContract.hostLaunchToken(from: validURL, configuredScheme: scheme))
        }
        XCTAssertNil(RaftExternalShareContract.hostLaunchToken(
            from: try XCTUnwrap(URL(string: "raft-debug://oauth/callback?token=opaque-token_1234567890")),
            configuredScheme: "raft-debug"
        ))
        XCTAssertNil(RaftExternalShareContract.hostLaunchToken(
            from: try XCTUnwrap(URL(string: "raft-debug://external-share?token=opaque-token_1234567890&extra=1")),
            configuredScheme: "raft-debug"
        ))
    }

    func testProviderRoutingHandlesWeChatZipFileURLTextFilesAndWebURLs() {
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["com.pkware.zip-archive", "public.file-url"]),
            .fileRepresentation(typeIdentifier: "com.pkware.zip-archive", fallbackToFileURL: true)
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.file-url"]),
            .fileURL
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.file-url", "public.plain-text"]),
            .fileURL
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.json", "public.file-url"]),
            .fileURL
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.data", "public.file-url"]),
            .fileURL
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.data"]),
            .fileRepresentation(typeIdentifier: "public.data", fallbackToFileURL: false)
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.jpeg"]),
            .fileRepresentation(typeIdentifier: "public.jpeg", fallbackToFileURL: false)
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.url", "public.plain-text"]),
            .url
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: ["public.utf8-plain-text"]),
            .text(typeIdentifier: "public.utf8-plain-text")
        )
        XCTAssertEqual(
            RaftExternalShareProviderRouting.classify(registeredTypeIdentifiers: []),
            .unsupported
        )
    }

    func testResolvedDisplayNameAndMimeTypeForZipAndNonAsciiNames() {
        let zipURL = URL(fileURLWithPath: "/tmp/ChatHistory.zip")
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "ChatHistory",
                sourceURL: zipURL,
                typeIdentifier: "public.file-url",
                index: 0
            ),
            "ChatHistory.zip"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "聊天记录",
                sourceURL: zipURL,
                typeIdentifier: "com.pkware.zip-archive",
                index: 0
            ),
            "聊天记录.zip"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "2026.11.23 聊天记录",
                sourceURL: URL(fileURLWithPath: "/tmp/2026.11.23 聊天记录.zip"),
                typeIdentifier: "public.file-url",
                index: 0
            ),
            "2026.11.23 聊天记录.zip"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "report.v2",
                sourceURL: URL(fileURLWithPath: "/tmp/report.v2.zip"),
                typeIdentifier: "com.pkware.zip-archive",
                index: 0
            ),
            "report.v2.zip"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "ChatHistory.ZIP",
                sourceURL: zipURL,
                typeIdentifier: "public.file-url",
                index: 0
            ),
            "ChatHistory.ZIP"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "photo.jpg",
                sourceURL: URL(fileURLWithPath: "/tmp/image-payload"),
                typeIdentifier: "public.jpeg",
                index: 0
            ),
            "photo.jpg"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "photo.jpeg",
                sourceURL: URL(fileURLWithPath: "/tmp/IMG_1234.JPG"),
                typeIdentifier: "public.jpeg",
                index: 0
            ),
            "photo.jpeg"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedDisplayName(
                suggestedName: "notes.txt",
                sourceURL: URL(fileURLWithPath: "/tmp/export.tmp"),
                typeIdentifier: "public.file-url",
                index: 0
            ),
            "notes.txt"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedMimeType(
                typeIdentifier: "public.file-url",
                displayName: "ChatHistory.zip"
            ),
            "application/zip"
        )
        XCTAssertEqual(
            RaftExternalShareEnvelopeStorage.resolvedMimeType(
                typeIdentifier: nil,
                rawMimeType: "application/octet-stream",
                displayName: "ChatHistory.zip"
            ),
            "application/zip"
        )
    }

    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("external-share-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testAdmitPublishesZipEnvelopeWithNonAsciiNameThenPendingToken() throws {
        let source = root.appendingPathComponent("ChatHistory.zip")
        try Data([0x50, 0x4B, 0x03, 0x04, 0x14, 0x00]).write(to: source)
        let storage = makeStorage(token: "opaque-token_1234567890")

        XCTAssertEqual(
            try storage.admit(
                text: "caption",
                subject: "",
                candidates: [
                    RaftExternalShareCandidate(
                        url: source,
                        displayName: "微信聊天记录.zip",
                        mimeType: "application/octet-stream",
                        sizeBytes: 6
                    )
                ]
            ),
            "opaque-token_1234567890"
        )
        XCTAssertEqual(storage.pendingToken(), "opaque-token_1234567890")
        let inbox = root.appendingPathComponent(RaftExternalShareContract.rootDirectoryName)
        let envelopeURL = inbox.appendingPathComponent("opaque-token_1234567890/envelope.json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: envelopeURL.path))
        let json = try String(contentsOf: envelopeURL, encoding: .utf8)
        XCTAssertTrue(json.contains("微信聊天记录.zip"))
        XCTAssertTrue(json.contains("application\\/zip") || json.contains("application/zip"))
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: inbox.path)
            .contains(where: { $0.hasPrefix(".staging-") }))
    }

    func testTokenReuseCannotReplacePublishedGeneration() throws {
        let storage = makeStorage(token: "opaque-token_1234567890")
        XCTAssertNoThrow(try storage.admit(text: "first", subject: "", candidates: []))
        XCTAssertThrowsError(try storage.admit(text: "second", subject: "", candidates: [])) { error in
            XCTAssertEqual(error as? RaftExternalShareStorageError, .rejected("external_share_token_conflict"))
        }
        let envelope = root
            .appendingPathComponent(RaftExternalShareContract.rootDirectoryName)
            .appendingPathComponent("opaque-token_1234567890/envelope.json")
        XCTAssertTrue(String(data: try Data(contentsOf: envelope), encoding: .utf8)?.contains("first") == true)
    }

    func testConsumedOrDeletedPendingTokenIsNotResumed() throws {
        let storage = makeStorage(token: "opaque-token_1234567890")
        _ = try storage.admit(text: "first", subject: "", candidates: [])
        let directory = root
            .appendingPathComponent(RaftExternalShareContract.rootDirectoryName)
            .appendingPathComponent("opaque-token_1234567890")
        try Data("external-share-consume:opaque-token_1234567890".utf8)
            .write(to: directory.appendingPathComponent(RaftExternalShareContract.consumedFileName))
        XCTAssertNil(storage.pendingToken())

        let secondStorage = makeStorage(token: "opaque-token_9876543210")
        _ = try secondStorage.admit(text: "second", subject: "", candidates: [])
        XCTAssertEqual(secondStorage.pendingToken(), "opaque-token_9876543210")
        secondStorage.delete(token: "opaque-token_9876543210")
        XCTAssertNil(secondStorage.pendingToken())
    }

    func testExpiredPendingTokenIsClearedAndNotResumed() throws {
        var now = Date(timeIntervalSince1970: 1_700_000_000)
        let storage = RaftExternalShareEnvelopeStorage(
            appGroupRootOverride: root,
            clock: { now },
            tokenFactory: { "opaque-token_expired12345" }
        )
        _ = try storage.admit(text: "hello", subject: "", candidates: [])
        XCTAssertEqual(storage.pendingToken(), "opaque-token_expired12345")

        now = Date(timeIntervalSince1970: 1_700_000_000 + RaftExternalShareContract.envelopeTtl + 1)
        XCTAssertNil(storage.pendingToken())
        let pendingFile = root
            .appendingPathComponent(RaftExternalShareContract.rootDirectoryName)
            .appendingPathComponent(RaftExternalShareContract.pendingFileName)
        XCTAssertFalse(FileManager.default.fileExists(atPath: pendingFile.path))
    }

    func testWhitespaceOnlyTextWithoutFilesIsRejected() {
        let storage = makeStorage(token: "opaque-token_whitespace12")
        XCTAssertThrowsError(try storage.admit(text: "   \n\t ", subject: "  ", candidates: [])) { error in
            XCTAssertEqual(error as? RaftExternalShareStorageError, .rejected("external_share_empty"))
        }
    }

    func testCopyRejectsEmptyAndDirectoryWithoutPublishingEnvelope() throws {
        let empty = root.appendingPathComponent("empty")
        FileManager.default.createFile(atPath: empty.path, contents: Data())
        let storage = makeStorage(token: "opaque-token_1234567890")
        XCTAssertThrowsError(
            try storage.admit(
                text: "",
                subject: "",
                candidates: [
                    RaftExternalShareCandidate(
                        url: empty,
                        displayName: "empty",
                        mimeType: "application/octet-stream",
                        sizeBytes: 0
                    )
                ]
            )
        )
        let subdir = root.appendingPathComponent("folder", isDirectory: true)
        try FileManager.default.createDirectory(at: subdir, withIntermediateDirectories: true)
        XCTAssertThrowsError(
            try RaftExternalShareEnvelopeStorage.copyBounded(
                source: subdir,
                destination: root.appendingPathComponent("out"),
                maximumBytes: 1024
            )
        ) { error in
            XCTAssertEqual(error as? RaftExternalShareStorageError, .rejected("external_share_item_unsupported"))
        }
        let inbox = root.appendingPathComponent(RaftExternalShareContract.rootDirectoryName)
        let children = (try? FileManager.default.contentsOfDirectory(atPath: inbox.path)) ?? []
        XCTAssertFalse(children.contains("opaque-token_1234567890"))
    }

    func testPublicationFenceRejectsDuplicateLateAndOldGenerationCallbacks() {
        let fence = RaftExternalSharePublicationFence()
        let first = fence.begin()
        XCTAssertTrue(fence.acceptProvider(index: 0, generation: first))
        XCTAssertFalse(fence.acceptProvider(index: 0, generation: first))
        XCTAssertTrue(fence.acceptProviderFallback(index: 0, generation: first))
        XCTAssertFalse(fence.acceptProviderFallback(index: 0, generation: first))
        XCTAssertTrue(fence.finish(generation: first))
        XCTAssertFalse(fence.acceptProvider(index: 1, generation: first))
        XCTAssertFalse(fence.finish(generation: first))

        let second = fence.begin()
        XCTAssertFalse(fence.acceptProvider(index: 1, generation: first))
        XCTAssertTrue(fence.acceptProvider(index: 1, generation: second))
    }

    func testCanonicalizeTargetHintValidatesAllKindsAndRejectsMalformedOrForeignIds() {
        XCTAssertEqual(
            RaftExternalShareContract.canonicalizeTargetHint("raft_share:v1:srv-1:dm:dm-agent_1"),
            "raft_share:v1:srv-1:dm:dm-agent_1"
        )
        XCTAssertEqual(
            RaftExternalShareContract.canonicalizeTargetHint("  raft_share:v1:srv-1:CHANNEL:ch_1.a~b  "),
            "raft_share:v1:srv-1:channel:ch_1.a~b"
        )
        XCTAssertEqual(
            RaftExternalShareContract.canonicalizeTargetHint("raft_share:v1:srv-2:joint:joint-9"),
            "raft_share:v1:srv-2:joint:joint-9"
        )

        let invalidHints: [String?] = [
            nil,
            "",
            "   ",
            "shortcut_srv-1_ch-1",
            "raft_share:v2:srv-1:channel:ch-1",
            "raft_share:v1:srv-1:thread:ch-1",
            "raft_share:v1:srv-1:channel:ch-1:extra",
            "raft_share:v1:srv-1:channel:../escape",
            "raft_share:v1:srv-1:channel:ch/1",
            "raft_share:v1:\(String(repeating: "a", count: 130)):channel:ch-1",
        ]
        for invalid in invalidHints {
            XCTAssertNil(RaftExternalShareContract.canonicalizeTargetHint(invalid))
        }
    }

    func testAdmitPersistsCanonicalPreselectedTargetHintAndDropsInvalidHint() throws {
        let validStorage = makeStorage(token: "opaque-token_hint_valid123")
        let hint = "raft_share:v1:srv-1:dm:dm-agent-1"
        _ = try validStorage.admit(
            text: "direct share hello",
            subject: "",
            candidates: [],
            preselectedTargetHint: "  raft_share:v1:srv-1:DM:dm-agent-1  "
        )
        let inbox = root.appendingPathComponent(RaftExternalShareContract.rootDirectoryName)
        let validJSON = try String(
            contentsOf: inbox.appendingPathComponent("opaque-token_hint_valid123/envelope.json"),
            encoding: .utf8
        )
        XCTAssertTrue(validJSON.contains("\"preselectedTargetHint\":\"\(hint)\""))

        let invalidStorage = makeStorage(token: "opaque-token_hint_bad12345")
        _ = try invalidStorage.admit(
            text: "fallback share hello",
            subject: "",
            candidates: [],
            preselectedTargetHint: "shortcut_srv-1_ch-1"
        )
        let invalidJSON = try String(
            contentsOf: inbox.appendingPathComponent("opaque-token_hint_bad12345/envelope.json"),
            encoding: .utf8
        )
        XCTAssertFalse(invalidJSON.contains("preselectedTargetHint"))
    }

    private func makeStorage(token: String) -> RaftExternalShareEnvelopeStorage {
        RaftExternalShareEnvelopeStorage(
            appGroupRootOverride: root,
            clock: { Date(timeIntervalSince1970: 1_700_000_000) },
            tokenFactory: { token }
        )
    }
}
