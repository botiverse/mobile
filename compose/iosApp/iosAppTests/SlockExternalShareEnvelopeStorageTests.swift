import XCTest

final class SlockExternalShareEnvelopeStorageTests: XCTestCase {
    func testAdmitCopiesPrivateEnvelopeAndPublishesOnlyPendingToken() throws {
        let root = temporaryDirectory("roundtrip")
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source.png")
        try Data([1, 2, 3]).write(to: source)
        let storage = SlockExternalShareEnvelopeStorage(
            appGroupRootOverride: root,
            clock: { Date(timeIntervalSince1970: 1) },
            tokenFactory: { "ios-share-token-123456" }
        )

        let token = try storage.admit(
            text: "hello",
            subject: "subject",
            candidates: [
                SlockExternalShareCandidate(
                    url: source,
                    displayName: "photo.png",
                    mimeType: "image/png",
                    sizeBytes: 3
                )
            ]
        )

        XCTAssertEqual(token, "ios-share-token-123456")
        XCTAssertEqual(storage.pendingToken(), token)
        let inbox = root.appendingPathComponent(SlockExternalShareContract.rootDirectoryName)
        XCTAssertEqual(
            try String(contentsOf: inbox.appendingPathComponent(SlockExternalShareContract.pendingFileName)),
            token
        )
        let envelopeData = try Data(contentsOf: inbox
            .appendingPathComponent(token)
            .appendingPathComponent(SlockExternalShareContract.envelopeFileName))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: envelopeData) as? [String: Any])
        XCTAssertEqual(json["token"] as? String, token)
        XCTAssertEqual(json["sourcePlatform"] as? String, "ios")
        XCTAssertNil(json["serverId"])
        XCTAssertNil(json["auth"])
        let items = try XCTUnwrap(json["items"] as? [[String: Any]])
        let localRef = try XCTUnwrap(items.single?["localRef"] as? String)
        XCTAssertTrue(localRef.hasPrefix("file:"))
        XCTAssertFalse(localRef.contains(source.lastPathComponent))
    }

    func testStreamOverflowFailsClosedWithoutPendingToken() throws {
        let root = temporaryDirectory("overflow")
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("large.bin")
        try Data(repeating: 7, count: 64 * 1024).write(to: source)
        let destination = root.appendingPathComponent("copy.bin")

        XCTAssertThrowsError(
            try SlockExternalShareEnvelopeStorage.copyBounded(
                source: source,
                destination: destination,
                maximumBytes: 1024
            )
        ) { error in
            XCTAssertEqual((error as? SlockExternalShareStorageError)?.code, "external_share_item_too_large")
        }
    }

    func testUnsafeTokenFailsBeforeEnvelopePublication() throws {
        let root = temporaryDirectory("token")
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = SlockExternalShareEnvelopeStorage(
            appGroupRootOverride: root,
            tokenFactory: { "../../escape" }
        )

        XCTAssertThrowsError(try storage.admit(text: "hello", subject: "", candidates: [])) { error in
            XCTAssertEqual((error as? SlockExternalShareStorageError)?.code, "external_share_token_invalid")
        }
        XCTAssertNil(storage.pendingToken())
    }

    private func temporaryDirectory(_ name: String) -> URL {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("SlockExternalShareTests-\(name)-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
