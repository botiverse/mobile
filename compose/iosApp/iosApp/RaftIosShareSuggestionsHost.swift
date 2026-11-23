import CoreGraphics
import CoreText
import Foundation
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
        let accountSwitched = !snapshot.clearAll
            && persistedAccountId != nil
            && persistedAccountId != snapshot.accountId
        let needsPurge = appliedPurgeGeneration < snapshot.requestedPurgeGeneration
            || snapshot.clearAll
            || accountSwitched

        if needsPurge {
            let reason = snapshot.requestedPurgeReason
                ?? snapshot.clearReason
                ?? (accountSwitched ? "account_switched" : "clear")
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
                        }
                        self.lastAppliedSignature = nil
                        self.log("warn", "share_suggestions_donate_failed count=\(donations.count) error=\(error.localizedDescription)")
                    } else {
                        let trackedIds = staleDeleteFailed ? desiredIds.union(staleIds) : desiredIds
                        self.defaults.set(Array(trackedIds).sorted(), forKey: Self.donatedIdsDefaultsKey)
                        if let accountId = snapshot.accountId {
                            self.defaults.set(accountId, forKey: Self.donatedAccountIdDefaultsKey)
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

    private static func buildSignature(snapshot: Snapshot) -> String {
        var parts: [String] = [
            snapshot.accountId ?? "",
            snapshot.serverId ?? "",
        ]
        for entry in snapshot.suggestions {
            let item = entry.suggestion
            parts.append(
                "\(entry.canonicalShortcutId)|\(item.title)|\(item.subtitle ?? "")|\(item.avatarWireValue ?? "")"
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

private let raftIosShareChannelTileBackgroundArgb: UInt32 = 0xFFFFDF3F
private let raftIosShareChannelTileForegroundArgb: UInt32 = 0xFF18181B
private let raftIosShareDmTilePaletteArgb: [UInt32] = [
    0xFFE84C88,
    0xFF3B82F6,
    0xFF10B981,
    0xFF8B5CF6,
    0xFFF59E0B,
    0xFF06B6D4,
]

func renderIosShareSuggestionAvatarPngData(
    suggestion: RaftShareSuggestion,
    sizePx: Int = 120
) -> Data? {
    guard sizePx > 0 else { return nil }
    if suggestion.kind == .directmessage,
        let rawWire = suggestion.avatarWireValue?.trimmingCharacters(in: .whitespacesAndNewlines),
        !rawWire.isEmpty,
        let pixelAvatar = RaftIosShareSuggestionsSupportKt.raftIosRenderPixelAvatarRgba(wireValue: rawWire),
        let png = renderIosPixelAvatarPngData(raster: pixelAvatar, sizePx: sizePx) {
        return png
    }

    let isChannel = suggestion.kind != .directmessage
    let glyph: String
    if isChannel {
        glyph = "#"
    } else {
        let trimmed = suggestion.avatarLabel.trimmingCharacters(in: .whitespacesAndNewlines)
        glyph = trimmed.isEmpty ? "?" : String(trimmed.prefix(2))
    }
    let backgroundArgb: UInt32
    let textArgb: UInt32
    if isChannel {
        backgroundArgb = raftIosShareChannelTileBackgroundArgb
        textArgb = raftIosShareChannelTileForegroundArgb
    } else {
        let index = Int(deterministicFnv1aHash(suggestion.shortcutId) % UInt64(raftIosShareDmTilePaletteArgb.count))
        backgroundArgb = raftIosShareDmTilePaletteArgb[index]
        textArgb = 0xFFFFFFFF
    }
    return renderIosGlyphTilePngData(
        glyph: glyph,
        backgroundArgb: backgroundArgb,
        textArgb: textArgb,
        sizePx: sizePx
    )
}

private func renderIosPixelAvatarPngData(raster: RaftIosPixelAvatarRgba, sizePx: Int) -> Data? {
    let rawSize = Int(raster.size)
    let rgbaData = raster.rgbaData
    guard rawSize > 0, rgbaData.count == rawSize * rawSize * 4 else { return nil }

    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
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
        ),
        let context = CGContext(
            data: nil,
            width: sizePx,
            height: sizePx,
            bitsPerComponent: 8,
            bytesPerRow: sizePx * 4,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        )
    else { return nil }

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

    context.setFillColor(cgColor(fromArgb: backgroundArgb, colorSpace: colorSpace))
    context.fill(CGRect(x: 0, y: 0, width: sizePx, height: sizePx))

    let fontSize = CGFloat(sizePx) * (glyph.count <= 1 ? 0.44 : 0.34)
    let font = UIFont.systemFont(ofSize: fontSize, weight: .bold)
    let attributes: [NSAttributedString.Key: Any] = [
        .font: font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): cgColor(fromArgb: textArgb, colorSpace: colorSpace),
    ]
    let attributed = NSAttributedString(string: glyph, attributes: attributes)
    let line = CTLineCreateWithAttributedString(attributed)
    let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
    let drawX = (CGFloat(sizePx) - bounds.width) * 0.5 - bounds.origin.x
    let drawY = (CGFloat(sizePx) - bounds.height) * 0.5 - bounds.origin.y

    context.textMatrix = .identity
    context.textPosition = CGPoint(x: drawX, y: drawY)
    CTLineDraw(line, context)

    guard let output = context.makeImage() else { return nil }
    return UIImage(cgImage: output).pngData()
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
