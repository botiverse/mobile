import XCTest

final class SlockExternalShareEnvelopeStorageTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("external-share-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    func testAdmitPublishesEnvelopeThenPendingToken() throws {
        let source = root.appendingPathComponent("source.txt")
        try Data("hello".utf8).write(to: source)
        let storage = makeStorage(token: "opaque-token_1234567890")

        XCTAssertEqual(
            try storage.admit(
                text: "caption",
                subject: "",
                candidates: [
                    SlockExternalShareCandidate(
                        url: source,
                        displayName: "note.txt",
                        mimeType: "text/plain",
                        sizeBytes: 5
                    )
                ]
            ),
            "opaque-token_1234567890"
        )
        XCTAssertEqual(storage.pendingToken(), "opaque-token_1234567890")
        let inbox = root.appendingPathComponent(SlockExternalShareContract.rootDirectoryName)
        let envelope = inbox.appendingPathComponent("opaque-token_1234567890/envelope.json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: envelope.path))
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: inbox.path)
            .contains(where: { $0.hasPrefix(".staging-") }))
    }

    func testTokenReuseCannotReplacePublishedGeneration() throws {
        let storage = makeStorage(token: "opaque-token_1234567890")
        XCTAssertNoThrow(try storage.admit(text: "first", subject: "", candidates: []))
        XCTAssertThrowsError(try storage.admit(text: "second", subject: "", candidates: [])) { error in
            XCTAssertEqual(error as? SlockExternalShareStorageError, .rejected("external_share_token_conflict"))
        }
        let envelope = root
            .appendingPathComponent(SlockExternalShareContract.rootDirectoryName)
            .appendingPathComponent("opaque-token_1234567890/envelope.json")
        XCTAssertTrue(String(data: try Data(contentsOf: envelope), encoding: .utf8)?.contains("first") == true)
    }

    func testConsumedOrInvalidPendingTokenIsNotResumed() throws {
        let storage = makeStorage(token: "opaque-token_1234567890")
        _ = try storage.admit(text: "first", subject: "", candidates: [])
        let directory = root
            .appendingPathComponent(SlockExternalShareContract.rootDirectoryName)
            .appendingPathComponent("opaque-token_1234567890")
        try Data("external-share-consume:opaque-token_1234567890".utf8)
            .write(to: directory.appendingPathComponent(SlockExternalShareContract.consumedFileName))
        XCTAssertNil(storage.pendingToken())
    }

    func testCopyRejectsEmptyAndOversizeWithoutPublishingEnvelope() throws {
        let empty = root.appendingPathComponent("empty")
        FileManager.default.createFile(atPath: empty.path, contents: Data())
        let storage = makeStorage(token: "opaque-token_1234567890")
        XCTAssertThrowsError(
            try storage.admit(
                text: "",
                subject: "",
                candidates: [
                    SlockExternalShareCandidate(
                        url: empty,
                        displayName: "empty",
                        mimeType: "application/octet-stream",
                        sizeBytes: 0
                    )
                ]
            )
        )
        let inbox = root.appendingPathComponent(SlockExternalShareContract.rootDirectoryName)
        let children = (try? FileManager.default.contentsOfDirectory(atPath: inbox.path)) ?? []
        XCTAssertFalse(children.contains("opaque-token_1234567890"))
    }

    func testPublicationFenceRejectsDuplicateLateAndOldGenerationCallbacks() {
        let fence = SlockExternalSharePublicationFence()
        let first = fence.begin()
        XCTAssertTrue(fence.acceptProvider(index: 0, generation: first))
        XCTAssertFalse(fence.acceptProvider(index: 0, generation: first))
        XCTAssertTrue(fence.finish(generation: first))
        XCTAssertFalse(fence.acceptProvider(index: 1, generation: first))
        XCTAssertFalse(fence.finish(generation: first))

        let second = fence.begin()
        XCTAssertFalse(fence.acceptProvider(index: 1, generation: first))
        XCTAssertTrue(fence.acceptProvider(index: 1, generation: second))
    }

    private func makeStorage(token: String) -> SlockExternalShareEnvelopeStorage {
        SlockExternalShareEnvelopeStorage(
            appGroupRootOverride: root,
            clock: { Date(timeIntervalSince1970: 1_700_000_000) },
            tokenFactory: { token }
        )
    }
}
