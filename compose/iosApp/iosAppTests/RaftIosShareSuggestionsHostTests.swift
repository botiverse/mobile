import Intents
import UIKit
import XCTest
import shared

final class RaftIosShareSuggestionsHostTests: XCTestCase {
    private var defaultsSuiteName: String!
    private var defaults: UserDefaults!

    override func setUpWithError() throws {
        defaultsSuiteName = "build.raft.tests.share-suggestions.\(UUID().uuidString)"
        defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsSuiteName))
    }

    override func tearDownWithError() throws {
        defaults.removePersistentDomain(forName: defaultsSuiteName)
        defaults = nil
        defaultsSuiteName = nil
    }

    func testBuildInteractionConfiguresDmAndChannelIntentsForShareSheet() throws {
        let now = Date(timeIntervalSince1970: 1_763_900_000)
        let dmSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .directmessage,
            channelId: "dm-agent-1",
            title: "AirenaAgent",
            avatarWireValue: "pixel:cat"
        )
        let dmPng = try XCTUnwrap(renderIosShareSuggestionAvatarPngData(suggestion: dmSuggestion))
        let dmDonation = RaftIosShareSuggestionDonation(
            identifier: dmSuggestion.shortcutId,
            groupIdentifier: RaftIosShareSuggestionsEngine.interactionGroupIdentifier,
            serverId: "srv-1",
            serverName: "Acme Corp",
            channelId: "dm-agent-1",
            kind: .directmessage,
            title: "AirenaAgent",
            subtitle: "Acme Corp",
            avatarPngData: dmPng,
            date: now
        )
        let dmInteraction = dmDonation.buildInteraction()
        XCTAssertEqual(dmInteraction.identifier, "raft_share:v1:srv-1:dm:dm-agent-1")
        XCTAssertEqual(dmInteraction.groupIdentifier, RaftIosShareSuggestionsEngine.interactionGroupIdentifier)
        XCTAssertEqual(dmInteraction.direction, .outgoing)
        let dmIntent = try XCTUnwrap(dmInteraction.intent as? INSendMessageIntent)
        XCTAssertEqual(dmIntent.conversationIdentifier, "raft_share:v1:srv-1:dm:dm-agent-1")
        XCTAssertNil(dmIntent.speakableGroupName)
        XCTAssertEqual(dmIntent.serviceName, "Acme Corp")
        XCTAssertEqual(dmIntent.recipients?.count, 1)
        XCTAssertEqual(dmIntent.recipients?.first?.displayName, "AirenaAgent")
        XCTAssertEqual(dmIntent.recipients?.first?.customIdentifier, "raft_share:v1:srv-1:dm:dm-agent-1")
        XCTAssertNotNil(dmIntent.recipients?.first?.image)

        let chSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-general",
            title: "#general"
        )
        let chPng = try XCTUnwrap(renderIosShareSuggestionAvatarPngData(suggestion: chSuggestion))
        let chDonation = RaftIosShareSuggestionDonation(
            identifier: chSuggestion.shortcutId,
            groupIdentifier: RaftIosShareSuggestionsEngine.interactionGroupIdentifier,
            serverId: "srv-1",
            serverName: "Acme Corp",
            channelId: "ch-general",
            kind: .channel,
            title: "#general",
            subtitle: "Acme Corp",
            avatarPngData: chPng,
            date: now.addingTimeInterval(-1)
        )
        let chInteraction = chDonation.buildInteraction()
        let chIntent = try XCTUnwrap(chInteraction.intent as? INSendMessageIntent)
        XCTAssertEqual(chIntent.conversationIdentifier, "raft_share:v1:srv-1:channel:ch-general")
        XCTAssertEqual(chIntent.speakableGroupName?.spokenPhrase, "#general")
        XCTAssertEqual(chIntent.recipients?.count, 1)
        XCTAssertNotNil(chIntent.image(forParameterNamed: \.speakableGroupName))
    }

    func testPublishDeduplicatesRemovesStaleAndSkipsIdempotentRepublish() {
        let backend = FakeShareSuggestionsBackend()
        let engine = makeImmediateEngine(backend: backend)

        let dm = makeSuggestion(serverId: "srv-1", kind: .directmessage, channelId: "dm-1", title: "Alice")
        let ch1 = makeSuggestion(serverId: "srv-1", kind: .channel, channelId: "ch-1", title: "#general")
        let ch2 = makeSuggestion(serverId: "srv-1", kind: .joint, channelId: "ch-joint", title: "#partner")

        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm, ch1, ch2])
        XCTAssertEqual(backend.donateBatches.count, 1)
        XCTAssertEqual(
            backend.donateBatches[0].map(\.identifier),
            [
                "raft_share:v1:srv-1:dm:dm-1",
                "raft_share:v1:srv-1:channel:ch-1",
                "raft_share:v1:srv-1:joint:ch-joint",
            ]
        )
        XCTAssertTrue(backend.deletedIdBatches.isEmpty)

        // Identical publish is a no-op (no duplicate Siri donation IPC).
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm, ch1, ch2])
        XCTAssertEqual(backend.donateBatches.count, 1)

        // Shrinking to [dm] deletes stale channel + joint IDs before re-donating.
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm])
        XCTAssertEqual(backend.deletedIdBatches.count, 1)
        XCTAssertEqual(
            backend.deletedIdBatches[0],
            ["raft_share:v1:srv-1:channel:ch-1", "raft_share:v1:srv-1:joint:ch-joint"]
        )
        XCTAssertEqual(backend.donateBatches.count, 2)
        XCTAssertEqual(backend.donateBatches[1].map(\.identifier), ["raft_share:v1:srv-1:dm:dm-1"])
    }

    func testAccountSwitchAcrossRestartPurgesPriorAccountBeforeDonating() {
        defaults.set(["raft_share:v1:srv-old:dm:dm-old"], forKey: RaftIosShareSuggestionsEngine.donatedIdsDefaultsKey)
        defaults.set("acc-old", forKey: RaftIosShareSuggestionsEngine.donatedAccountIdDefaultsKey)

        let backend = FakeShareSuggestionsBackend()
        let engine = makeImmediateEngine(backend: backend)
        let newSuggestion = makeSuggestion(serverId: "srv-new", kind: .channel, channelId: "ch-new", title: "#new")

        engine.publish(accountId: "acc-new", serverId: "srv-new", suggestions: [newSuggestion])

        XCTAssertEqual(backend.events, ["deleteAll", "donate:raft_share:v1:srv-new:channel:ch-new"])
        XCTAssertEqual(
            defaults.stringArray(forKey: RaftIosShareSuggestionsEngine.donatedIdsDefaultsKey),
            ["raft_share:v1:srv-new:channel:ch-new"]
        )
        XCTAssertEqual(
            defaults.string(forKey: RaftIosShareSuggestionsEngine.donatedAccountIdDefaultsKey),
            "acc-new"
        )
    }

    func testQueuedLogoutClearAlwaysExecutesBeforeNewAccountPublishAndDropsStalePublish() {
        let backend = FakeShareSuggestionsBackend()
        var queue: [() -> Void] = []
        let engine = RaftIosShareSuggestionsEngine(
            backend: backend,
            defaults: defaults,
            dispatch: { block in queue.append(block) },
            clock: { Date(timeIntervalSince1970: 1_763_900_000) },
            avatarPngFactory: { _ in Data([0x89, 0x50, 0x4E, 0x47]) },
            log: { _, _ in }
        )

        func drainQueue() {
            while !queue.isEmpty {
                let next = queue.removeFirst()
                next()
            }
        }

        // Case 1: publish(acc-1) superseded by clear("logout") before worker drains -> stale publish dropped.
        engine.publish(
            accountId: "acc-1",
            serverId: "srv-1",
            suggestions: [makeSuggestion(serverId: "srv-1", kind: .directmessage, channelId: "dm-1", title: "Old")]
        )
        engine.clear(reason: "logout")
        drainQueue()
        XCTAssertEqual(backend.events, ["deleteAll"])
        XCTAssertNil(defaults.string(forKey: RaftIosShareSuggestionsEngine.donatedAccountIdDefaultsKey))

        // Case 2: clear("logout") immediately followed by publish(acc-2) before worker drains ->
        // deleteAll MUST run first before donating acc-2.
        backend.events.removeAll()
        engine.clear(reason: "logout")
        engine.publish(
            accountId: "acc-2",
            serverId: "srv-2",
            suggestions: [makeSuggestion(serverId: "srv-2", kind: .channel, channelId: "ch-2", title: "#fresh")]
        )
        drainQueue()
        XCTAssertEqual(backend.events, ["deleteAll", "donate:raft_share:v1:srv-2:channel:ch-2"])
    }

    func testClearIfIdleOnlyClearsBeforeFirstPublishOrClear() {
        let backend = FakeShareSuggestionsBackend()
        let idleEngine = makeImmediateEngine(backend: backend)
        idleEngine.clearIfIdle(reason: "cold_start_signed_out")
        XCTAssertEqual(backend.events, ["deleteAll"])

        let activeBackend = FakeShareSuggestionsBackend()
        let activeEngine = makeImmediateEngine(backend: activeBackend)
        activeEngine.publish(
            accountId: "acc-1",
            serverId: "srv-1",
            suggestions: [makeSuggestion(serverId: "srv-1", kind: .directmessage, channelId: "dm-1", title: "Alice")]
        )
        activeEngine.clearIfIdle(reason: "cold_start_signed_out")
        XCTAssertEqual(activeBackend.events, ["donate:raft_share:v1:srv-1:dm:dm-1"])
    }

    func testAvatarRendererProducesValidPngForPixelAvatarChannelAndDmFallback() throws {
        let pixelSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .directmessage,
            channelId: "dm-agent",
            title: "AirenaAgent",
            avatarWireValue: "pixel:cat"
        )
        let pixelPng = try XCTUnwrap(renderIosShareSuggestionAvatarPngData(suggestion: pixelSuggestion, sizePx: 120))
        let pixelImage = try XCTUnwrap(UIImage(data: pixelPng))
        XCTAssertEqual(Int(pixelImage.size.width * pixelImage.scale), 120)
        XCTAssertEqual(Int(pixelImage.size.height * pixelImage.scale), 120)

        let channelSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-1",
            title: "#general"
        )
        let channelPng = try XCTUnwrap(renderIosShareSuggestionAvatarPngData(suggestion: channelSuggestion, sizePx: 120))
        XCTAssertNotNil(UIImage(data: channelPng))

        let dmFallbackSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .directmessage,
            channelId: "dm-human",
            title: "Alice",
            avatarWireValue: "https://example.com/avatar.png"
        )
        let dmPng = try XCTUnwrap(renderIosShareSuggestionAvatarPngData(suggestion: dmFallbackSuggestion, sizePx: 120))
        XCTAssertNotNil(UIImage(data: dmPng))
    }

    func testStaleDeleteFailureRetainsStaleIdsAndRetriesDeletionOnNextPublish() {
        let backend = FakeShareSuggestionsBackend()
        let engine = makeImmediateEngine(backend: backend)
        let dm = makeSuggestion(serverId: "srv-1", kind: .directmessage, channelId: "dm-1", title: "Alice")
        let ch = makeSuggestion(serverId: "srv-1", kind: .channel, channelId: "ch-1", title: "#general")

        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm, ch])
        XCTAssertEqual(
            defaults.stringArray(forKey: RaftIosShareSuggestionsEngine.donatedIdsDefaultsKey),
            ["raft_share:v1:srv-1:channel:ch-1", "raft_share:v1:srv-1:dm:dm-1"]
        )

        // Simulate transient Siri error while deleting dropped channel ch-1.
        backend.deleteError = NSError(domain: "test", code: 1)
        backend.deletedIdBatches.removeAll()
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm])
        XCTAssertEqual(backend.deletedIdBatches, [["raft_share:v1:srv-1:channel:ch-1"]])
        // Stale ch-1 MUST remain tracked in UserDefaults so it is not orphaned forever.
        XCTAssertEqual(
            defaults.stringArray(forKey: RaftIosShareSuggestionsEngine.donatedIdsDefaultsKey),
            ["raft_share:v1:srv-1:channel:ch-1", "raft_share:v1:srv-1:dm:dm-1"]
        )

        // Next identical publish retries deleting ch-1 because lastAppliedSignature was NOT cached.
        backend.deleteError = nil
        backend.deletedIdBatches.removeAll()
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm])
        XCTAssertEqual(backend.deletedIdBatches, [["raft_share:v1:srv-1:channel:ch-1"]])
        XCTAssertEqual(
            defaults.stringArray(forKey: RaftIosShareSuggestionsEngine.donatedIdsDefaultsKey),
            ["raft_share:v1:srv-1:dm:dm-1"]
        )
    }

    func testPartialDonateFailureRetainsPotentiallyDonatedIdsForFuturePruning() {
        let backend = FakeShareSuggestionsBackend()
        let engine = makeImmediateEngine(backend: backend)
        let dm = makeSuggestion(serverId: "srv-1", kind: .directmessage, channelId: "dm-1", title: "Alice")
        let ch = makeSuggestion(serverId: "srv-1", kind: .channel, channelId: "ch-1", title: "#general")

        backend.donateError = NSError(domain: "test", code: 2)
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm, ch])
        XCTAssertEqual(
            defaults.stringArray(forKey: RaftIosShareSuggestionsEngine.donatedIdsDefaultsKey),
            ["raft_share:v1:srv-1:channel:ch-1", "raft_share:v1:srv-1:dm:dm-1"]
        )

        // Later publish with only [dm] still prunes ch-1 in case ch-1 had succeeded before donate failed.
        backend.donateError = nil
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [dm])
        XCTAssertEqual(backend.deletedIdBatches, [["raft_share:v1:srv-1:channel:ch-1"]])
        XCTAssertEqual(
            defaults.stringArray(forKey: RaftIosShareSuggestionsEngine.donatedIdsDefaultsKey),
            ["raft_share:v1:srv-1:dm:dm-1"]
        )
    }

    func testDeleteAllFailurePreservesPurgeDebtAndRetriesBeforeNextDonate() {
        let backend = FakeShareSuggestionsBackend()
        let engine = makeImmediateEngine(backend: backend)
        let oldDm = makeSuggestion(serverId: "srv-1", kind: .directmessage, channelId: "dm-old", title: "Old")
        let newCh = makeSuggestion(serverId: "srv-2", kind: .channel, channelId: "ch-new", title: "#new")

        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [oldDm])
        backend.events.removeAll()

        backend.deleteAllError = NSError(domain: "test", code: 3)
        engine.clear(reason: "logout")
        XCTAssertEqual(backend.events, ["deleteAll"])
        // Persisted state was NOT wiped because deleteAll failed.
        XCTAssertEqual(defaults.string(forKey: RaftIosShareSuggestionsEngine.donatedAccountIdDefaultsKey), "acc-1")

        // Next publish MUST retry deleteAll and succeed before donating acc-2.
        backend.deleteAllError = nil
        backend.events.removeAll()
        engine.publish(accountId: "acc-2", serverId: "srv-2", suggestions: [newCh])
        XCTAssertEqual(backend.events, ["deleteAll", "donate:raft_share:v1:srv-2:channel:ch-new"])
        XCTAssertEqual(defaults.string(forKey: RaftIosShareSuggestionsEngine.donatedAccountIdDefaultsKey), "acc-2")
    }

    private func makeImmediateEngine(backend: FakeShareSuggestionsBackend) -> RaftIosShareSuggestionsEngine {
        RaftIosShareSuggestionsEngine(
            backend: backend,
            defaults: defaults,
            dispatch: { block in block() },
            clock: { Date(timeIntervalSince1970: 1_763_900_000) },
            avatarPngFactory: { _ in Data([0x89, 0x50, 0x4E, 0x47]) },
            log: { _, _ in }
        )
    }

    private func makeSuggestion(
        serverId: String,
        kind: RaftShareSuggestionKind,
        channelId: String,
        title: String,
        avatarWireValue: String? = nil
    ) -> RaftShareSuggestion {
        let shortcutId = RaftShareSuggestionContract.shared.encodeTargetHint(
            serverId: serverId,
            kind: kind,
            channelId: channelId
        )!
        return RaftShareSuggestion(
            shortcutId: shortcutId,
            serverId: serverId,
            serverName: "Acme Corp",
            channelId: channelId,
            kind: kind,
            title: title,
            subtitle: "Acme Corp",
            peerType: kind == .directmessage ? "agent" : nil,
            avatarWireValue: avatarWireValue,
            avatarLabel: kind == .directmessage ? String(title.prefix(2)) : "#",
            lastActivityEpochMillis: 1_763_900_000_000
        )
    }

    private final class FakeShareSuggestionsBackend: RaftIosShareSuggestionsBackend {
        var events: [String] = []
        var donateBatches: [[RaftIosShareSuggestionDonation]] = []
        var deletedIdBatches: [[String]] = []
        var donateError: Error?
        var deleteError: Error?
        var deleteAllError: Error?

        func donate(donations: [RaftIosShareSuggestionDonation], completion: @escaping (Error?) -> Void) {
            donateBatches.append(donations)
            events.append("donate:" + donations.map(\.identifier).joined(separator: ","))
            completion(donateError)
        }

        func delete(identifiers: [String], completion: @escaping (Error?) -> Void) {
            deletedIdBatches.append(identifiers)
            events.append("delete:" + identifiers.joined(separator: ","))
            completion(deleteError)
        }

        func deleteAll(completion: @escaping (Error?) -> Void) {
            events.append("deleteAll")
            completion(deleteAllError)
        }
    }
}
