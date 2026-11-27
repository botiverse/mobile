import CoreGraphics
import CoreText
import Foundation
import ImageIO
import Intents
import UIKit
import shared

/// Materialized Share Sheet suggestion ready for `INInteraction` donation.
struct RaftIosShareSuggestionDonation: Equatable {
    let identifier: String
    let groupIdentifier: String
    let serverId: String
    let serverName: String
    let channelId: String
    let kind: RaftShareSuggestionKind
    let title: String
    let subtitle: String?
    let avatarPngData: Data?
    let date: Date

    func buildInteraction() -> INInteraction {
        let handle = INPersonHandle(value: identifier, type: .unknown)
        let image = avatarPngData.map { INImage(imageData: $0) }
        let person = INPerson(
            personHandle: handle,
            nameComponents: nil,
            displayName: title,
            image: image,
            contactIdentifier: nil,
            customIdentifier: identifier
        )
        let isChannel = kind != .directmessage
        let speakableGroupName = isChannel ? INSpeakableString(spokenPhrase: title) : nil
        let cleanServerName = serverName.trimmingCharacters(in: .whitespacesAndNewlines)
        let serviceName = cleanServerName.isEmpty ? "Raft" : cleanServerName
        let intent = INSendMessageIntent(
            recipients: [person],
            outgoingMessageType: .outgoingMessageText,
            content: nil,
            speakableGroupName: speakableGroupName,
            conversationIdentifier: identifier,
            serviceName: serviceName,
            sender: nil,
            attachments: nil
        )
        if isChannel, let image {
            intent.setImage(image, forParameterNamed: \.speakableGroupName)
        }
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.identifier = identifier
        interaction.groupIdentifier = groupIdentifier
        interaction.direction = .outgoing
        interaction.dateInterval = DateInterval(start: date, duration: 0)
        return interaction
    }
}

/// Backend seam over `INInteraction` so unit tests verify ordering, deduplication, stale removal,
/// account switches, and logout purge races without writing to the simulator's Siri database.
protocol RaftIosShareSuggestionsBackend: AnyObject {
    func donate(donations: [RaftIosShareSuggestionDonation], completion: @escaping (Error?) -> Void)
    func delete(identifiers: [String], completion: @escaping (Error?) -> Void)
    func deleteAll(completion: @escaping (Error?) -> Void)
}

private final class SystemRaftIosShareSuggestionsBackend: RaftIosShareSuggestionsBackend {
    func donate(donations: [RaftIosShareSuggestionDonation], completion: @escaping (Error?) -> Void) {
        donateNext(donations: donations, index: 0, firstError: nil, completion: completion)
    }

    private func donateNext(
        donations: [RaftIosShareSuggestionDonation],
        index: Int,
        firstError: Error?,
        completion: @escaping (Error?) -> Void
    ) {
        guard index < donations.count else {
            completion(firstError)
            return
        }
        let interaction = donations[index].buildInteraction()
        interaction.donate { error in
            self.donateNext(
                donations: donations,
                index: index + 1,
                firstError: firstError ?? error,
                completion: completion
            )
        }
    }

    func delete(identifiers: [String], completion: @escaping (Error?) -> Void) {
        guard !identifiers.isEmpty else {
            completion(nil)
            return
        }
        INInteraction.delete(with: identifiers, completion: completion)
    }

    func deleteAll(completion: @escaping (Error?) -> Void) {
        INInteraction.deleteAll(completion: completion)
    }
}

/// Owns iOS Share Sheet `INSendMessageIntent` donations for recent Raft conversations.
///
/// Uses a fast in-memory `stateLock` for callers (safe from main thread) plus an asynchronous
/// serial drain loop so heavy CoreGraphics icon rendering and Siri `INInteraction` IPC never block
/// the UI thread, stale batches are dropped if superseded by a newer update or `clear("logout")`,
/// and monotonic `requestedPurgeGeneration` / `appliedPurgeGeneration` guarantees logout purges
/// always run before any subsequent donation even if coalesced before the worker wakes.
final class RaftIosShareSuggestionsEngine: NSObject, RaftShareSuggestionsHost {
    static let interactionGroupIdentifier = "build.raft.app.share-suggestions.v1"
    static let donatedIdsDefaultsKey = "raftIosShareSuggestionsDonatedIds.v1"
    static let donatedAccountIdDefaultsKey = "raftIosShareSuggestionsAccountId.v1"
    static let donatedIconStyleVersionDefaultsKey = "raftIosShareSuggestionsIconStyleVersion.v1"

    private struct NormalizedSuggestion {
        let canonicalShortcutId: String
        let suggestion: RaftShareSuggestion
    }

    private struct Snapshot {
        let generation: UInt64
        let accountId: String?
        let serverId: String?
        let suggestions: [NormalizedSuggestion]
        let requestedPurgeGeneration: UInt64
        let requestedPurgeReason: String?
        let clearAll: Bool
        let clearReason: String?
    }

    private let backend: RaftIosShareSuggestionsBackend
    private let defaults: UserDefaults
    private let dispatch: (@escaping () -> Void) -> Void
    private let clock: () -> Date
    private let avatarPngFactory: (RaftShareSuggestion) -> Data?
    private let log: (String, String) -> Void

    private let stateLock = NSLock()
    private var pendingGeneration: UInt64 = 0
    private var requestedPurgeGeneration: UInt64 = 0
    private var requestedPurgeReason: String?
    private var latestSnapshot: Snapshot?
    private var isDraining = false

    // Worker-only state (serialized by `isDraining` + `dispatch`).
    private var appliedPurgeGeneration: UInt64 = 0
    private var lastAppliedSignature: String?

    init(
        backend: RaftIosShareSuggestionsBackend,
        defaults: UserDefaults = .standard,
        dispatch: @escaping (@escaping () -> Void) -> Void,
        clock: @escaping () -> Date = Date.init,
        avatarPngFactory: @escaping (RaftShareSuggestion) -> Data? = { suggestion in
            renderIosShareSuggestionAvatarPngData(suggestion: suggestion)
        },
        log: @escaping (String, String) -> Void = { _, _ in }
    ) {
        self.backend = backend
        self.defaults = defaults
        self.dispatch = dispatch
        self.clock = clock
        self.avatarPngFactory = avatarPngFactory
        self.log = log
        super.init()
    }

    func publish(
        accountId: String,
        serverId: String,
        suggestions: [RaftShareSuggestion]
    ) {
        let cleanAccountId = accountId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanServerId = serverId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanAccountId.isEmpty, !cleanServerId.isEmpty else { return }

        let maxCount = Int(RaftShareSuggestionContract.shared.MaxSuggestionCount)
        var seen = Set<String>()
        var normalized: [NormalizedSuggestion] = []
        for item in suggestions {
            guard normalized.count < maxCount else { break }
            guard let canonicalId = Self.canonicalShortcutIdIfConsistent(item) else { continue }
            guard seen.insert(canonicalId).inserted else { continue }
            normalized.append(NormalizedSuggestion(canonicalShortcutId: canonicalId, suggestion: item))
        }

        enqueue {
            pendingGeneration &+= 1
            return Snapshot(
                generation: pendingGeneration,
                accountId: cleanAccountId,
                serverId: cleanServerId,
                suggestions: normalized,
                requestedPurgeGeneration: requestedPurgeGeneration,
                requestedPurgeReason: requestedPurgeReason,
                clearAll: false,
                clearReason: nil
            )
        }
    }

    func clear(reason: String) {
        enqueue {
            requestedPurgeGeneration &+= 1
            requestedPurgeReason = reason
            pendingGeneration &+= 1
            return Snapshot(
                generation: pendingGeneration,
                accountId: nil,
                serverId: nil,
                suggestions: [],
                requestedPurgeGeneration: requestedPurgeGeneration,
                requestedPurgeReason: requestedPurgeReason,
                clearAll: true,
                clearReason: reason
            )
        }
    }

    /// Cold-start signed-out cleanup: clears persisted Siri donations ONLY if neither `publish()`
    /// nor `clear()` has occurred yet in this process, so an async startup check can never race
    /// against an immediate user sign-in.
    func clearIfIdle(reason: String) {
        var shouldDrain = false
        stateLock.lock()
        if pendingGeneration == 0 {
            requestedPurgeGeneration &+= 1
            requestedPurgeReason = reason
            pendingGeneration &+= 1
            latestSnapshot = Snapshot(
                generation: pendingGeneration,
                accountId: nil,
                serverId: nil,
                suggestions: [],
                requestedPurgeGeneration: requestedPurgeGeneration,
                requestedPurgeReason: requestedPurgeReason,
                clearAll: true,
                clearReason: reason
            )
            if !isDraining {
                isDraining = true
                shouldDrain = true
            }
        }
        stateLock.unlock()
        if shouldDrain {
            dispatch { [weak self] in
                self?.drainNext()
            }
        }
    }

    private func enqueue(_ makeSnapshot: () -> Snapshot) {
        var shouldDrain = false
        stateLock.lock()
        latestSnapshot = makeSnapshot()
        if !isDraining {
            isDraining = true
            shouldDrain = true
        }
        stateLock.unlock()
        if shouldDrain {
            dispatch { [weak self] in
                self?.drainNext()
            }
        }
    }

    private func isCurrentGeneration(_ generation: UInt64) -> Bool {
        stateLock.lock()
        let current = pendingGeneration
        stateLock.unlock()
        return generation == current
    }

    private func drainNext() {
        stateLock.lock()
        guard let snapshot = latestSnapshot else {
            isDraining = false
            stateLock.unlock()
            return
        }
        latestSnapshot = nil
        stateLock.unlock()

        apply(snapshot: snapshot) { [weak self] in
            self?.dispatch {
                self?.drainNext()
            }
        }
    }

    private func apply(snapshot: Snapshot, done: @escaping () -> Void) {
        let persistedAccountId = defaults.string(forKey: Self.donatedAccountIdDefaultsKey)
        let persistedIds = defaults.stringArray(forKey: Self.donatedIdsDefaultsKey) ?? []
        let persistedIconStyleVersion = defaults.string(forKey: Self.donatedIconStyleVersionDefaultsKey)
        let accountSwitched = !snapshot.clearAll
            && persistedAccountId != nil
            && persistedAccountId != snapshot.accountId
        let iconStyleUpgraded = !snapshot.clearAll
            && persistedIconStyleVersion != Self.iconStyleVersion
            && (persistedAccountId != nil || !persistedIds.isEmpty)
        let needsPurge = appliedPurgeGeneration < snapshot.requestedPurgeGeneration
            || snapshot.clearAll
            || accountSwitched
            || iconStyleUpgraded

        if needsPurge {
            let reason = snapshot.requestedPurgeReason
                ?? snapshot.clearReason
                ?? (accountSwitched ? "account_switched" : (iconStyleUpgraded ? "icon_style_upgraded" : "clear"))
            backend.deleteAll { [weak self] error in
                guard let self else {
                    done()
                    return
                }
                self.dispatch {
                    if let error {
                        self.log("warn", "share_suggestions_clear_failed reason=\(reason) error=\(error.localizedDescription)")
                        done()
                        return
                    }
                    self.appliedPurgeGeneration = max(self.appliedPurgeGeneration, snapshot.requestedPurgeGeneration)
                    self.lastAppliedSignature = nil
                    self.clearPersistedState()
                    self.log("info", "share_suggestions_cleared reason=\(reason)")
                    if snapshot.clearAll || !self.isCurrentGeneration(snapshot.generation) {
                        done()
                        return
                    }
                    self.applyPublishAfterPurge(snapshot: snapshot, done: done)
                }
            }
            return
        }

        applyPublishAfterPurge(snapshot: snapshot, done: done)
    }

    private func applyPublishAfterPurge(snapshot: Snapshot, done: @escaping () -> Void) {
        let signature = Self.buildSignature(snapshot: snapshot)
        if signature == lastAppliedSignature {
            done()
            return
        }

        let persistedIds = Set(defaults.stringArray(forKey: Self.donatedIdsDefaultsKey) ?? [])
        if snapshot.suggestions.isEmpty {
            let idsToRemove = Array(persistedIds).sorted()
            guard !idsToRemove.isEmpty else {
                lastAppliedSignature = signature
                if let accountId = snapshot.accountId {
                    defaults.set(accountId, forKey: Self.donatedAccountIdDefaultsKey)
                    defaults.set(Self.iconStyleVersion, forKey: Self.donatedIconStyleVersionDefaultsKey)
                }
                done()
                return
            }
            backend.delete(identifiers: idsToRemove) { [weak self] error in
                guard let self else {
                    done()
                    return
                }
                self.dispatch {
                    if let error {
                        self.log("warn", "share_suggestions_prune_failed count=\(idsToRemove.count) error=\(error.localizedDescription)")
                    } else {
                        self.defaults.set([String](), forKey: Self.donatedIdsDefaultsKey)
                        if let accountId = snapshot.accountId {
                            self.defaults.set(accountId, forKey: Self.donatedAccountIdDefaultsKey)
                            self.defaults.set(Self.iconStyleVersion, forKey: Self.donatedIconStyleVersionDefaultsKey)
                        }
                        self.lastAppliedSignature = signature
                        self.log("info", "share_suggestions_emptied removed=\(idsToRemove.count)")
                    }
                    done()
                }
            }
            return
        }

        let baseDate = clock()
        var donations: [RaftIosShareSuggestionDonation] = []
        donations.reserveCapacity(snapshot.suggestions.count)
        for (index, entry) in snapshot.suggestions.enumerated() {
            guard isCurrentGeneration(snapshot.generation) else {
                done()
                return
            }
            let suggestion = entry.suggestion
            let title = suggestion.title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { continue }
            let subtitle = suggestion.subtitle?.trimmingCharacters(in: .whitespacesAndNewlines)
            let pngData = avatarPngFactory(suggestion)
            donations.append(
                RaftIosShareSuggestionDonation(
                    identifier: entry.canonicalShortcutId,
                    groupIdentifier: Self.interactionGroupIdentifier,
                    serverId: suggestion.serverId.trimmingCharacters(in: .whitespacesAndNewlines),
                    serverName: suggestion.serverName.trimmingCharacters(in: .whitespacesAndNewlines),
                    channelId: suggestion.channelId.trimmingCharacters(in: .whitespacesAndNewlines),
                    kind: suggestion.kind,
                    title: title,
                    subtitle: subtitle?.isEmpty == true ? nil : subtitle,
                    avatarPngData: pngData,
                    date: baseDate.addingTimeInterval(-Double(index))
                )
            )
        }

        guard isCurrentGeneration(snapshot.generation) else {
            done()
            return
        }

        let desiredIds = Set(donations.map(\.identifier))
        let staleIds = Array(persistedIds.subtracting(desiredIds)).sorted()

        let performDonate: (Bool) -> Void = { [weak self] staleDeleteFailed in
            guard let self else {
                done()
                return
            }
            guard self.isCurrentGeneration(snapshot.generation) else {
                done()
                return
            }
            self.backend.donate(donations: donations) { [weak self] error in
                guard let self else {
                    done()
                    return
                }
                self.dispatch {
                    if let error {
                        // A sequential donate may partially succeed before failing on a later item;
                        // keep potentially donated IDs tracked so future publishes can still prune them.
                        let retainedPrevious = staleDeleteFailed ? persistedIds : persistedIds.subtracting(staleIds)
                        let trackedIds = retainedPrevious.union(desiredIds)
                        self.defaults.set(Array(trackedIds).sorted(), forKey: Self.donatedIdsDefaultsKey)
                        if let accountId = snapshot.accountId {
                            self.defaults.set(accountId, forKey: Self.donatedAccountIdDefaultsKey)
                            self.defaults.set(Self.iconStyleVersion, forKey: Self.donatedIconStyleVersionDefaultsKey)
                        }
                        self.lastAppliedSignature = nil
                        self.log("warn", "share_suggestions_donate_failed count=\(donations.count) error=\(error.localizedDescription)")
                    } else {
                        let trackedIds = staleDeleteFailed ? desiredIds.union(staleIds) : desiredIds
                        self.defaults.set(Array(trackedIds).sorted(), forKey: Self.donatedIdsDefaultsKey)
                        if let accountId = snapshot.accountId {
                            self.defaults.set(accountId, forKey: Self.donatedAccountIdDefaultsKey)
                            self.defaults.set(Self.iconStyleVersion, forKey: Self.donatedIconStyleVersionDefaultsKey)
                        }
                        self.lastAppliedSignature = staleDeleteFailed ? nil : signature
                        self.log("info", "share_suggestions_donated count=\(donations.count) staleRemoved=\(staleDeleteFailed ? 0 : staleIds.count)")
                    }
                    done()
                }
            }
        }

        if staleIds.isEmpty {
            performDonate(false)
        } else {
            backend.delete(identifiers: staleIds) { [weak self] error in
                guard let self else {
                    done()
                    return
                }
                self.dispatch {
                    if let error {
                        self.log("warn", "share_suggestions_stale_delete_failed count=\(staleIds.count) error=\(error.localizedDescription)")
                        performDonate(true)
                    } else {
                        self.defaults.set(Array(persistedIds.subtracting(staleIds)).sorted(), forKey: Self.donatedIdsDefaultsKey)
                        performDonate(false)
                    }
                }
            }
        }
    }

    private func clearPersistedState() {
        defaults.removeObject(forKey: Self.donatedIdsDefaultsKey)
        defaults.removeObject(forKey: Self.donatedAccountIdDefaultsKey)
        defaults.removeObject(forKey: Self.donatedIconStyleVersionDefaultsKey)
    }

    private static func canonicalShortcutIdIfConsistent(_ suggestion: RaftShareSuggestion) -> String? {
        guard let decoded = RaftShareSuggestionContract.shared.decodeTargetHint(raw: suggestion.shortcutId),
            decoded.serverId == suggestion.serverId.trimmingCharacters(in: .whitespacesAndNewlines),
            decoded.channelId == suggestion.channelId.trimmingCharacters(in: .whitespacesAndNewlines),
            decoded.kind == suggestion.kind,
            !suggestion.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            return nil
        }
        return RaftShareSuggestionContract.shared.encodeTargetHint(
            serverId: decoded.serverId,
            kind: decoded.kind,
            channelId: decoded.channelId
        )
    }

    private static let iconStyleVersion = "iconV2"

    private static func buildSignature(snapshot: Snapshot) -> String {
        var parts: [String] = [
            Self.iconStyleVersion,
            snapshot.accountId ?? "",
            snapshot.serverId ?? "",
        ]
        for entry in snapshot.suggestions {
            let item = entry.suggestion
            parts.append(
                "\(entry.canonicalShortcutId)|\(item.serverName)|\(item.title)|\(item.subtitle ?? "")|\(item.avatarWireValue ?? "")|\(item.serverAvatarUrl ?? "")|\(item.isPrivate ? "1" : "0")"
            )
        }
        return parts.joined(separator: ";")
    }
}

enum RaftIosShareSuggestionsHost {
    private static let lock = NSLock()
    private static var engine: RaftIosShareSuggestionsEngine?

    static func install(log: @escaping (String, String) -> Void = { _, _ in }) {
        let resolved: RaftIosShareSuggestionsEngine
        lock.lock()
        if let existing = engine {
            resolved = existing
        } else {
            let queue = DispatchQueue(label: "build.raft.app.share-suggestions", qos: .utility)
            let created = RaftIosShareSuggestionsEngine(
                backend: SystemRaftIosShareSuggestionsBackend(),
                defaults: .standard,
                dispatch: { block in queue.async(execute: block) },
                log: log
            )
            engine = created
            resolved = created
        }
        lock.unlock()
        RaftShareSuggestionsHostProvider.shared.installAppScopeHost(host: resolved)
        RaftIosShareSuggestionsSupportKt.checkRaftIosColdStartSignedOut {
            resolved.clearIfIdle(reason: "cold_start_signed_out")
        }
    }
}

// MARK: - Thread-safe CoreGraphics / CoreText avatar PNG renderer

enum RaftIosShareChannelBadgeKind: Equatable {
    case publicHash
    case privateLock
    case jointUsers
}

private let raftIosShareServerTilePaletteArgb: [UInt32] = [
    0xFF3A3A3E, // charcoal
    0xFF3D4C63, // deep slate-blue
    0xFF364D45, // deep forest
    0xFF4C3F59, // deep plum
    0xFF52453A, // deep bronze
]
private let raftIosShareChannelBadgeHaloArgb: UInt32 = 0xCC18181B
private let raftIosShareChannelBadgeFillArgb: UInt32 = 0xFFF3F1EB
private let raftIosShareChannelBadgeInkArgb: UInt32 = 0xFF262420
private let raftIosShareDmTilePaletteArgb: [UInt32] = [
    0xFFE84C88,
    0xFF3B82F6,
    0xFF10B981,
    0xFF8B5CF6,
    0xFFF59E0B,
    0xFF06B6D4,
]

func resolveIosShareChannelBadgeKind(suggestion: RaftShareSuggestion) -> RaftIosShareChannelBadgeKind {
    if suggestion.isPrivate {
        return .privateLock
    }
    if suggestion.kind == .joint {
        return .jointUsers
    }
    return .publicHash
}

func resolveIosShareServerTileArgb(serverId: String) -> UInt32 {
    let trimmed = serverId.trimmingCharacters(in: .whitespacesAndNewlines)
    let index = Int(deterministicFnv1aHash(trimmed) % UInt64(raftIosShareServerTilePaletteArgb.count))
    return raftIosShareServerTilePaletteArgb[index]
}

func renderIosShareSuggestionAvatarPngData(
    suggestion: RaftShareSuggestion,
    sizePx: Int = 120,
    cachedServerImageDataLoader: (String) -> Data? = { sourceUrl in
        RaftIosShareSuggestionsSupportKt.raftIosReadCachedAvatarImageData(sourceUrl: sourceUrl)
    }
) -> Data? {
    guard sizePx > 0 else { return nil }
    if suggestion.kind == .directmessage {
        if let rawWire = suggestion.avatarWireValue?.trimmingCharacters(in: .whitespacesAndNewlines),
            !rawWire.isEmpty,
            let pixelAvatar = RaftIosShareSuggestionsSupportKt.raftIosRenderPixelAvatarRgba(wireValue: rawWire),
            let png = renderIosPixelAvatarPngData(raster: pixelAvatar, sizePx: sizePx) {
            return png
        }
        let trimmed = suggestion.avatarLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        let glyph = trimmed.isEmpty ? "?" : String(trimmed.prefix(2))
        let index = Int(deterministicFnv1aHash(suggestion.shortcutId) % UInt64(raftIosShareDmTilePaletteArgb.count))
        return renderIosGlyphTilePngData(
            glyph: glyph,
            backgroundArgb: raftIosShareDmTilePaletteArgb[index],
            textArgb: 0xFFFFFFFF,
            sizePx: sizePx
        )
    }

    return renderIosChannelServerBadgeTilePngData(
        suggestion: suggestion,
        sizePx: sizePx,
        cachedServerImageDataLoader: cachedServerImageDataLoader
    )
}

private func renderIosChannelServerBadgeTilePngData(
    suggestion: RaftShareSuggestion,
    sizePx: Int,
    cachedServerImageDataLoader: (String) -> Data?
) -> Data? {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
    guard let context = CGContext(
        data: nil,
        width: sizePx,
        height: sizePx,
        bitsPerComponent: 8,
        bytesPerRow: sizePx * 4,
        space: colorSpace,
        bitmapInfo: bitmapInfo
    ) else { return nil }

    let size = CGFloat(sizePx)
    let tileRect = CGRect(x: 0, y: 0, width: size, height: size)
    let serverBackgroundArgb = resolveIosShareServerTileArgb(serverId: suggestion.serverId)
    context.setFillColor(cgColor(fromArgb: serverBackgroundArgb, colorSpace: colorSpace))
    context.fill(tileRect)

    var drewServerVisual = false
    if let rawServerAvatar = suggestion.serverAvatarUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
        !rawServerAvatar.isEmpty {
        if let pixelRaster = RaftIosShareSuggestionsSupportKt.raftIosRenderPixelAvatarRgba(wireValue: rawServerAvatar) {
            drewServerVisual = drawIosPixelAvatar(
                raster: pixelRaster,
                in: context,
                sizePx: sizePx,
                colorSpace: colorSpace,
                bitmapInfo: bitmapInfo
            )
        } else if let cachedData = cachedServerImageDataLoader(rawServerAvatar),
            let serverCgImage = decodeIosServerAvatarCgImage(data: cachedData, maxPixelSize: sizePx * 2) {
            drawIosCenterCroppedImage(serverCgImage, in: context, destRect: tileRect)
            drewServerVisual = true
        }
    }

    if !drewServerVisual {
        let monogram = suggestion.serverAvatarMonogram()
        let fontSize = size * (monogram.count <= 1 ? 0.40 : 0.31)
        // Gently shift down-right of center to balance the top-left semantic channel badge while
        // staying well inside the circular Share Sheet mask and clear of the bottom-right app badge.
        drawIosCenteredGlyph(
            monogram,
            in: context,
            center: CGPoint(x: size * 0.52, y: size * 0.48),
            fontSize: fontSize,
            textArgb: 0xFFFFFFFF,
            colorSpace: colorSpace
        )
    }

    // CoreGraphics bitmap context origin is bottom-left (+Y up), so visual top-left is
    // (0.27 * size, 0.73 * size). Outermost halo distance from center:
    // sqrt(0.23^2 + 0.23^2) + 0.168 = 0.493 * size < 0.50 * size (never clipped by iOS circle mask).
    drawIosChannelSemanticBadge(
        kind: resolveIosShareChannelBadgeKind(suggestion: suggestion),
        in: context,
        center: CGPoint(x: size * 0.27, y: size * (1.0 - 0.27)),
        badgeRadius: size * 0.15,
        haloRadius: size * 0.168,
        colorSpace: colorSpace
    )

    guard let output = context.makeImage() else { return nil }
    return UIImage(cgImage: output).pngData()
}

private func decodeIosServerAvatarCgImage(data: Data, maxPixelSize: Int) -> CGImage? {
    guard !data.isEmpty else { return nil }
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceShouldCacheImmediately: true,
        kCGImageSourceThumbnailMaxPixelSize: max(maxPixelSize, 64),
    ]
    return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
}

private func drawIosCenterCroppedImage(_ image: CGImage, in context: CGContext, destRect: CGRect) {
    let width = image.width
    let height = image.height
    guard width > 0, height > 0 else { return }
    let cropSize = min(width, height)
    let cropRect = CGRect(
        x: (width - cropSize) / 2,
        y: (height - cropSize) / 2,
        width: cropSize,
        height: cropSize
    )
    let cropped = image.cropping(to: cropRect) ?? image
    context.saveGState()
    context.interpolationQuality = .high
    context.draw(cropped, in: destRect)
    context.restoreGState()
}

private func drawIosChannelSemanticBadge(
    kind: RaftIosShareChannelBadgeKind,
    in context: CGContext,
    center: CGPoint,
    badgeRadius: CGFloat,
    haloRadius: CGFloat,
    colorSpace: CGColorSpace
) {
    let haloColor = cgColor(fromArgb: raftIosShareChannelBadgeHaloArgb, colorSpace: colorSpace)
    let pillColor = cgColor(fromArgb: raftIosShareChannelBadgeFillArgb, colorSpace: colorSpace)
    let inkColor = cgColor(fromArgb: raftIosShareChannelBadgeInkArgb, colorSpace: colorSpace)

    context.saveGState()
    context.setFillColor(haloColor)
    context.fillEllipse(
        in: CGRect(
            x: center.x - haloRadius,
            y: center.y - haloRadius,
            width: haloRadius * 2,
            height: haloRadius * 2
        )
    )
    context.setFillColor(pillColor)
    context.fillEllipse(
        in: CGRect(
            x: center.x - badgeRadius,
            y: center.y - badgeRadius,
            width: badgeRadius * 2,
            height: badgeRadius * 2
        )
    )

    // Switch locally to top-down badge-centered coordinates (-Y up, +Y down) so vector paths match
    // Android's Canvas geometry identically and render right-side-up in the output PNG.
    context.translateBy(x: center.x, y: center.y)
    context.scaleBy(x: 1.0, y: -1.0)
    let r = badgeRadius

    switch kind {
    case .publicHash:
        let slant = 0.08 * r
        context.setStrokeColor(inkColor)
        context.setLineWidth(max(1.2, 0.135 * r))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.move(to: CGPoint(x: -0.18 * r + slant, y: -0.44 * r))
        context.addLine(to: CGPoint(x: -0.18 * r - slant, y: 0.44 * r))
        context.move(to: CGPoint(x: 0.18 * r + slant, y: -0.44 * r))
        context.addLine(to: CGPoint(x: 0.18 * r - slant, y: 0.44 * r))
        context.move(to: CGPoint(x: -0.42 * r, y: -0.17 * r))
        context.addLine(to: CGPoint(x: 0.46 * r, y: -0.17 * r))
        context.move(to: CGPoint(x: -0.46 * r, y: 0.17 * r))
        context.addLine(to: CGPoint(x: 0.42 * r, y: 0.17 * r))
        context.strokePath()

    case .privateLock:
        // Padlock shackle arch (Bezier semicircle through visual top y = -0.47 * r).
        let shackleR = 0.23 * r
        let shackleArchY = -0.24 * r
        let kappa = 0.55228475 * shackleR
        context.setStrokeColor(inkColor)
        context.setLineWidth(max(1.2, 0.15 * r))
        context.setLineCap(.round)
        context.setLineJoin(.round)
        context.move(to: CGPoint(x: -shackleR, y: -0.02 * r))
        context.addLine(to: CGPoint(x: -shackleR, y: shackleArchY))
        context.addCurve(
            to: CGPoint(x: 0, y: shackleArchY - shackleR),
            control1: CGPoint(x: -shackleR, y: shackleArchY - kappa),
            control2: CGPoint(x: -kappa, y: shackleArchY - shackleR)
        )
        context.addCurve(
            to: CGPoint(x: shackleR, y: shackleArchY),
            control1: CGPoint(x: kappa, y: shackleArchY - shackleR),
            control2: CGPoint(x: shackleR, y: shackleArchY - kappa)
        )
        context.addLine(to: CGPoint(x: shackleR, y: -0.02 * r))
        context.strokePath()

        // Padlock body (filled rounded rect) with subtle pill-colored keyhole dot.
        let bodyRect = CGRect(x: -0.37 * r, y: -0.06 * r, width: 0.74 * r, height: 0.52 * r)
        let corner = min(0.12 * r, min(bodyRect.width, bodyRect.height) * 0.5)
        context.setFillColor(inkColor)
        context.addPath(CGPath(roundedRect: bodyRect, cornerWidth: corner, cornerHeight: corner, transform: nil))
        context.fillPath()

        let keyholeR = 0.075 * r
        context.setFillColor(pillColor)
        context.fillEllipse(
            in: CGRect(x: -keyholeR, y: 0.20 * r - keyholeR, width: keyholeR * 2, height: keyholeR * 2)
        )

    case .jointUsers:
        // Secondary right-back person silhouette.
        let secHeadR = 0.135 * r
        context.setFillColor(inkColor)
        context.fillEllipse(
            in: CGRect(
                x: 0.25 * r - secHeadR,
                y: -0.18 * r - secHeadR,
                width: secHeadR * 2,
                height: secHeadR * 2
            )
        )
        addIosTorsoArchPath(in: context, left: 0.06 * r, right: 0.50 * r, top: 0.06 * r, bottom: 0.36 * r)
        context.fillPath()

        // Subtle pill-colored separation stroke around primary person so both silhouettes read crisply.
        let priHeadR = 0.175 * r
        let gapR = priHeadR + 0.055 * r
        context.setFillColor(pillColor)
        context.fillEllipse(
            in: CGRect(
                x: -0.15 * r - gapR,
                y: -0.22 * r - gapR,
                width: gapR * 2,
                height: gapR * 2
            )
        )
        addIosTorsoArchPath(in: context, left: -0.49 * r, right: 0.19 * r, top: 0.04 * r, bottom: 0.40 * r)
        context.setStrokeColor(pillColor)
        context.setLineWidth(max(1.0, 0.10 * r))
        context.setLineJoin(.round)
        context.strokePath()

        // Primary left-front person silhouette.
        context.setFillColor(inkColor)
        context.fillEllipse(
            in: CGRect(
                x: -0.15 * r - priHeadR,
                y: -0.22 * r - priHeadR,
                width: priHeadR * 2,
                height: priHeadR * 2
            )
        )
        addIosTorsoArchPath(in: context, left: -0.49 * r, right: 0.19 * r, top: 0.04 * r, bottom: 0.40 * r)
        context.fillPath()
    }

    context.restoreGState()
}

private func addIosTorsoArchPath(
    in context: CGContext,
    left: CGFloat,
    right: CGFloat,
    top: CGFloat,
    bottom: CGFloat
) {
    let midX = (left + right) * 0.5
    let halfW = (right - left) * 0.5
    context.move(to: CGPoint(x: left, y: bottom))
    context.addCurve(
        to: CGPoint(x: midX, y: top),
        control1: CGPoint(x: left, y: top),
        control2: CGPoint(x: midX - halfW * 0.45, y: top)
    )
    context.addCurve(
        to: CGPoint(x: right, y: bottom),
        control1: CGPoint(x: midX + halfW * 0.45, y: top),
        control2: CGPoint(x: right, y: top)
    )
    context.closePath()
}

private func drawIosPixelAvatar(
    raster: RaftIosPixelAvatarRgba,
    in context: CGContext,
    sizePx: Int,
    colorSpace: CGColorSpace,
    bitmapInfo: UInt32
) -> Bool {
    let rawSize = Int(raster.size)
    let rgbaData = raster.rgbaData
    guard rawSize > 0, rgbaData.count == rawSize * rawSize * 4 else { return false }
    guard let provider = CGDataProvider(data: rgbaData as CFData),
        let rawImage = CGImage(
            width: rawSize,
            height: rawSize,
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            bytesPerRow: rawSize * 4,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: bitmapInfo),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        )
    else { return false }

    context.saveGState()
    let bgArgb = UInt32(bitPattern: raster.backgroundArgb)
    context.setFillColor(cgColor(fromArgb: bgArgb, colorSpace: colorSpace))
    context.fill(CGRect(x: 0, y: 0, width: sizePx, height: sizePx))
    context.interpolationQuality = .none
    // Keep square pixel art comfortably inside the circular Share Sheet avatar mask.
    let inset = floor(CGFloat(sizePx) * 0.10)
    let dest = CGRect(
        x: inset,
        y: inset,
        width: CGFloat(sizePx) - inset * 2,
        height: CGFloat(sizePx) - inset * 2
    )
    context.draw(rawImage, in: dest)
    context.restoreGState()
    return true
}

private func renderIosPixelAvatarPngData(raster: RaftIosPixelAvatarRgba, sizePx: Int) -> Data? {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
    guard let context = CGContext(
        data: nil,
        width: sizePx,
        height: sizePx,
        bitsPerComponent: 8,
        bytesPerRow: sizePx * 4,
        space: colorSpace,
        bitmapInfo: bitmapInfo
    ) else { return nil }
    guard drawIosPixelAvatar(
        raster: raster,
        in: context,
        sizePx: sizePx,
        colorSpace: colorSpace,
        bitmapInfo: bitmapInfo
    ) else { return nil }
    guard let output = context.makeImage() else { return nil }
    return UIImage(cgImage: output).pngData()
}

private func renderIosGlyphTilePngData(
    glyph: String,
    backgroundArgb: UInt32,
    textArgb: UInt32,
    sizePx: Int
) -> Data? {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
    guard let context = CGContext(
        data: nil,
        width: sizePx,
        height: sizePx,
        bitsPerComponent: 8,
        bytesPerRow: sizePx * 4,
        space: colorSpace,
        bitmapInfo: bitmapInfo
    ) else { return nil }

    let size = CGFloat(sizePx)
    context.setFillColor(cgColor(fromArgb: backgroundArgb, colorSpace: colorSpace))
    context.fill(CGRect(x: 0, y: 0, width: size, height: size))

    let fontSize = size * (glyph.count <= 1 ? 0.44 : 0.34)
    drawIosCenteredGlyph(
        glyph,
        in: context,
        center: CGPoint(x: size * 0.5, y: size * 0.5),
        fontSize: fontSize,
        textArgb: textArgb,
        colorSpace: colorSpace
    )

    guard let output = context.makeImage() else { return nil }
    return UIImage(cgImage: output).pngData()
}

private func drawIosCenteredGlyph(
    _ glyph: String,
    in context: CGContext,
    center: CGPoint,
    fontSize: CGFloat,
    textArgb: UInt32,
    colorSpace: CGColorSpace
) {
    let font = UIFont.systemFont(ofSize: fontSize, weight: .bold)
    let attributes: [NSAttributedString.Key: Any] = [
        .font: font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): cgColor(fromArgb: textArgb, colorSpace: colorSpace),
    ]
    let attributed = NSAttributedString(string: glyph, attributes: attributes)
    let line = CTLineCreateWithAttributedString(attributed)
    let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
    let drawX = center.x - bounds.width * 0.5 - bounds.origin.x
    let drawY = center.y - bounds.height * 0.5 - bounds.origin.y

    context.textMatrix = .identity
    context.textPosition = CGPoint(x: drawX, y: drawY)
    CTLineDraw(line, context)
}

private func cgColor(fromArgb argb: UInt32, colorSpace: CGColorSpace) -> CGColor {
    let a = CGFloat((argb >> 24) & 0xFF) / 255.0
    let r = CGFloat((argb >> 16) & 0xFF) / 255.0
    let g = CGFloat((argb >> 8) & 0xFF) / 255.0
    let b = CGFloat(argb & 0xFF) / 255.0
    return CGColor(colorSpace: colorSpace, components: [r, g, b, a])
        ?? UIColor(red: r, green: g, blue: b, alpha: a).cgColor
}

private func deterministicFnv1aHash(_ value: String) -> UInt64 {
    var hash: UInt64 = 0xcbf29ce484222325
    for byte in value.utf8 {
        hash ^= UInt64(byte)
        hash &*= 0x100000001b3
    }
    return hash
}
