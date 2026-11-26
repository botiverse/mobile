import CoreGraphics
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

    func testChannelAvatarRendererUsesDarkServerPaletteAndTopLeftSemanticBadges() throws {
        let publicSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-pub",
            title: "#general",
            serverName: "Acme Corp"
        )
        let privateSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-priv",
            title: "#exec",
            serverName: "Acme Corp",
            isPrivate: true
        )
        let jointSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .joint,
            channelId: "ch-joint",
            title: "#partner",
            serverName: "Acme Corp"
        )
        let privateJointSuggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .joint,
            channelId: "ch-jpriv",
            title: "#secret-partner",
            serverName: "Acme Corp",
            isPrivate: true
        )

        XCTAssertEqual(resolveIosShareChannelBadgeKind(suggestion: publicSuggestion), .publicHash)
        XCTAssertEqual(resolveIosShareChannelBadgeKind(suggestion: privateSuggestion), .privateLock)
        XCTAssertEqual(resolveIosShareChannelBadgeKind(suggestion: jointSuggestion), .jointUsers)
        XCTAssertEqual(resolveIosShareChannelBadgeKind(suggestion: privateJointSuggestion), .privateLock)

        let expectedServerArgb = resolveIosShareServerTileArgb(serverId: "srv-1")
        XCTAssertNotEqual(expectedServerArgb, 0xFFFFDF3F)

        let publicPng = try XCTUnwrap(
            renderIosShareSuggestionAvatarPngData(suggestion: publicSuggestion, sizePx: 120, cachedServerImageDataLoader: { _ in nil })
        )
        let privatePng = try XCTUnwrap(
            renderIosShareSuggestionAvatarPngData(suggestion: privateSuggestion, sizePx: 120, cachedServerImageDataLoader: { _ in nil })
        )
        let jointPng = try XCTUnwrap(
            renderIosShareSuggestionAvatarPngData(suggestion: jointSuggestion, sizePx: 120, cachedServerImageDataLoader: { _ in nil })
        )

        let publicRgba = try decodeTopDownRgbaBytes(from: publicPng)
        let privateRgba = try decodeTopDownRgbaBytes(from: privatePng)
        let jointRgba = try decodeTopDownRgbaBytes(from: jointPng)

        XCTAssertEqual(publicRgba.width, 120)
        XCTAssertEqual(publicRgba.height, 120)

        // Bottom-right corner must match the dark-slate Server tile palette (never old yellow #FFDF3F).
        let bottomRight = rgbaPixel(in: publicRgba, x: 112, y: 112)
        XCTAssertTrue(pixelMatchesArgb(bottomRight, argb: expectedServerArgb, tolerance: 2))

        // Visual top-left badge region around (32, 32) must have light pill (#F3F1EB) + dark ink (#262420).
        // Visual bottom-right mirror region must NOT have light badge pill pixels (proves upright orientation).
        for rgba in [publicRgba, privateRgba, jointRgba] {
            XCTAssertTrue(regionHasNearArgb(in: rgba, xs: 14...50, ys: 14...50, argb: 0xFFF3F1EB))
            XCTAssertTrue(regionHasNearArgb(in: rgba, xs: 14...50, ys: 14...50, argb: 0xFF262420))
            XCTAssertFalse(regionHasNearArgb(in: rgba, xs: 84...114, ys: 84...114, argb: 0xFFF3F1EB))
        }

        // Public (#), private (lock), and joint (users) top-left badge regions must render distinctly.
        let publicBadge = extractRegionBytes(in: publicRgba, xs: 14...50, ys: 14...50)
        let privateBadge = extractRegionBytes(in: privateRgba, xs: 14...50, ys: 14...50)
        let jointBadge = extractRegionBytes(in: jointRgba, xs: 14...50, ys: 14...50)
        XCTAssertNotEqual(publicBadge, privateBadge)
        XCTAssertNotEqual(publicBadge, jointBadge)
        XCTAssertNotEqual(privateBadge, jointBadge)
    }

    func testChannelAvatarRendererDrawsCachedServerImageAndFallsBackOnInvalidData() throws {
        let tealArgb: UInt32 = 0xFF147A52
        let cachedPng = try XCTUnwrap(makeSolidPngData(width: 64, height: 48, argb: tealArgb))
        let suggestion = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-1",
            title: "#general",
            serverName: "Acme Corp",
            serverAvatarUrl: "https://cdn.raft.build/avatars/server/s1.png"
        )

        let renderedCached = try XCTUnwrap(
            renderIosShareSuggestionAvatarPngData(
                suggestion: suggestion,
                sizePx: 120,
                cachedServerImageDataLoader: { url in
                    url == suggestion.serverAvatarUrl ? cachedPng : nil
                }
            )
        )
        let cachedRgba = try decodeTopDownRgbaBytes(from: renderedCached)
        let bottomRight = rgbaPixel(in: cachedRgba, x: 112, y: 112)
        XCTAssertTrue(pixelMatchesArgb(bottomRight, argb: tealArgb, tolerance: 6))
        XCTAssertTrue(regionHasNearArgb(in: cachedRgba, xs: 14...50, ys: 14...50, argb: 0xFFF3F1EB))
        XCTAssertTrue(regionHasNearArgb(in: cachedRgba, xs: 14...50, ys: 14...50, argb: 0xFF262420))

        // Corrupt bytes fall back cleanly to the deterministic dark-slate Server monogram tile.
        let renderedFallback = try XCTUnwrap(
            renderIosShareSuggestionAvatarPngData(
                suggestion: suggestion,
                sizePx: 120,
                cachedServerImageDataLoader: { _ in Data("not-a-valid-image".utf8) }
            )
        )
        let fallbackRgba = try decodeTopDownRgbaBytes(from: renderedFallback)
        let fallbackCorner = rgbaPixel(in: fallbackRgba, x: 112, y: 112)
        XCTAssertTrue(pixelMatchesArgb(fallbackCorner, argb: resolveIosShareServerTileArgb(serverId: "srv-1"), tolerance: 2))
        XCTAssertTrue(regionHasNearArgb(in: fallbackRgba, xs: 14...50, ys: 14...50, argb: 0xFFF3F1EB))
    }

    func testSignatureInvalidatesWhenPrivacyServerAvatarOrServerNameChanges() {
        let backend = FakeShareSuggestionsBackend()
        let engine = makeImmediateEngine(backend: backend)

        let base = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-1",
            title: "#general",
            serverName: "Acme Corp"
        )
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [base])
        XCTAssertEqual(backend.donateBatches.count, 1)

        // Identical publish is skipped.
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [base])
        XCTAssertEqual(backend.donateBatches.count, 1)

        // Privacy change re-donates.
        let nowPrivate = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-1",
            title: "#general",
            serverName: "Acme Corp",
            isPrivate: true
        )
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [nowPrivate])
        XCTAssertEqual(backend.donateBatches.count, 2)

        // Server avatar URL change re-donates.
        let withServerAvatar = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-1",
            title: "#general",
            serverName: "Acme Corp",
            serverAvatarUrl: "https://cdn.raft.build/avatars/server/s1.webp",
            isPrivate: true
        )
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [withServerAvatar])
        XCTAssertEqual(backend.donateBatches.count, 3)

        // Server name change re-donates.
        let renamedServer = makeSuggestion(
            serverId: "srv-1",
            kind: .channel,
            channelId: "ch-1",
            title: "#general",
            serverName: "Beta Labs",
            serverAvatarUrl: "https://cdn.raft.build/avatars/server/s1.webp",
            isPrivate: true
        )
        engine.publish(accountId: "acc-1", serverId: "srv-1", suggestions: [renamedServer])
        XCTAssertEqual(backend.donateBatches.count, 4)
    }

    private func makeSolidPngData(width: Int, height: Int, argb: UInt32) -> Data? {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        guard let context = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else { return nil }
        let a = CGFloat((argb >> 24) & 0xFF) / 255.0
        let r = CGFloat((argb >> 16) & 0xFF) / 255.0
        let g = CGFloat((argb >> 8) & 0xFF) / 255.0
        let b = CGFloat(argb & 0xFF) / 255.0
        context.setFillColor(red: r, green: g, blue: b, alpha: a)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        guard let output = context.makeImage() else { return nil }
        return UIImage(cgImage: output).pngData()
    }

    private func decodeTopDownRgbaBytes(from pngData: Data) throws -> (width: Int, height: Int, bytes: [UInt8]) {
        let image = try XCTUnwrap(UIImage(data: pngData))
        let cgImage = try XCTUnwrap(image.cgImage)
        let width = cgImage.width
        let height = cgImage.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        let created = bytes.withUnsafeMutableBytes { rawBuffer -> Bool in
            guard let baseAddress = rawBuffer.baseAddress,
                let context = CGContext(
                    data: baseAddress,
                    width: width,
                    height: height,
                    bitsPerComponent: 8,
                    bytesPerRow: width * 4,
                    space: colorSpace,
                    bitmapInfo: bitmapInfo
                )
            else { return false }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        XCTAssertTrue(created)
        return (width, height, bytes)
    }

    private func rgbaPixel(
        in rgba: (width: Int, height: Int, bytes: [UInt8]),
        x: Int,
        y: Int
    ) -> (r: UInt8, g: UInt8, b: UInt8, a: UInt8) {
        let offset = (y * rgba.width + x) * 4
        return (rgba.bytes[offset], rgba.bytes[offset + 1], rgba.bytes[offset + 2], rgba.bytes[offset + 3])
    }

    private func pixelMatchesArgb(
        _ pixel: (r: UInt8, g: UInt8, b: UInt8, a: UInt8),
        argb: UInt32,
        tolerance: Int = 24
    ) -> Bool {
        let tr = Int((argb >> 16) & 0xFF)
        let tg = Int((argb >> 8) & 0xFF)
        let tb = Int(argb & 0xFF)
        return abs(Int(pixel.r) - tr) <= tolerance
            && abs(Int(pixel.g) - tg) <= tolerance
            && abs(Int(pixel.b) - tb) <= tolerance
    }

    private func regionHasNearArgb(
        in rgba: (width: Int, height: Int, bytes: [UInt8]),
        xs: ClosedRange<Int>,
        ys: ClosedRange<Int>,
        argb: UInt32,
        tolerance: Int = 24
    ) -> Bool {
        for y in ys {
            for x in xs {
                if pixelMatchesArgb(rgbaPixel(in: rgba, x: x, y: y), argb: argb, tolerance: tolerance) {
                    return true
                }
            }
        }
        return false
    }

    private func extractRegionBytes(
        in rgba: (width: Int, height: Int, bytes: [UInt8]),
        xs: ClosedRange<Int>,
        ys: ClosedRange<Int>
    ) -> [UInt8] {
        var result: [UInt8] = []
        result.reserveCapacity(xs.count * ys.count * 4)
        for y in ys {
            for x in xs {
                let offset = (y * rgba.width + x) * 4
                result.append(rgba.bytes[offset])
                result.append(rgba.bytes[offset + 1])
                result.append(rgba.bytes[offset + 2])
                result.append(rgba.bytes[offset + 3])
            }
        }
        return result
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
        avatarWireValue: String? = nil,
        serverName: String = "Acme Corp",
        serverAvatarUrl: String? = nil,
        isPrivate: Bool = false
    ) -> RaftShareSuggestion {
        let shortcutId = RaftShareSuggestionContract.shared.encodeTargetHint(
            serverId: serverId,
            kind: kind,
            channelId: channelId
        )!
        return RaftShareSuggestion(
            shortcutId: shortcutId,
            serverId: serverId,
            serverName: serverName,
            channelId: channelId,
            kind: kind,
            title: title,
            subtitle: serverName,
            peerType: kind == .directmessage ? "agent" : nil,
            avatarWireValue: avatarWireValue,
            avatarLabel: kind == .directmessage ? String(title.prefix(2)) : "#",
            lastActivityEpochMillis: 1_763_900_000_000,
            serverAvatarUrl: serverAvatarUrl,
            isPrivate: isPrivate
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
