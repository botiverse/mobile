package build.raft.app.network.sync

import build.raft.app.canonical.message.CanonicalMessageCarry
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import kotlinx.serialization.json.JsonObject

/**
 * Shadow-compare for the flag-gated sync-core message rollout (`sync_core_messages_v1`).
 *
 * While the flag is in SHADOW mode the existing realtime path (RealtimeEventMapper → Message → store → UI)
 * stays authoritative. In parallel, the SAME raw socket JSON is run through the new canonical pipeline
 * (ingress [decodeCanonicalMessageWire] → [CanonicalMessageRow.toAppMessage]) and the two projected
 * [Message]s are compared on their CANONICAL FACTS only. Nothing here is applied to the store — the sole
 * output is a divergence diagnostic. Zero divergence across production traffic is the gate for SOLE-APPLY.
 *
 * Only canonical facts are compared. Domain-outside / lossy fields the canonical row deliberately does not
 * carry are normalized away by [toShadowParityView] before the equality check, so they never produce false
 * divergence:
 *  - `updatedAt`-derived fields (`timestampLabel`, `updatedAtLabel`) — `updatedAt` is an excluded
 *    emittedUnconsumed field; the row carries only `createdAt`.
 *  - `attachment.commentCount` — viewer-scoped, excluded from the shared canonical row.
 *  - `task` / delivery / thread-follow / reply-count / saved — task-domain or client-local projections
 *    joined after this boundary.
 *  - `MemberProfile.roleDescription` and all directory enrichment — roleDescription is not canonical,
 *    and the former legacy read was removed after the G6 proof below.
 */

// The G6 reverse-closure gate proves every message:* post-projection key is contained in canonical(19)
// plus explicit exclusions; roleDescription is in neither. The dead legacy mapper read is now removed.

/** Maps a realtime socket event name to the manifest production face, or null if not a message-fold event. */
fun shadowFaceForEvent(eventName: String): ProductionFace? = when (eventName) {
    RealtimeSocketEvents.MessageNew -> ProductionFace.MessageNew
    // message:updated carries the enriched row; task-status-only updates arrive on task:* events, not here.
    RealtimeSocketEvents.MessageUpdated -> ProductionFace.EnrichedUpdated
    else -> null
}

/** Outcome of one shadow comparison. */
sealed interface MessageShadowOutcome {
    /** Canonical pipeline reproduced the authoritative projection exactly (on canonical facts). */
    data object Parity : MessageShadowOutcome

    /** Ingress fail-closed on the raw payload while the legacy path produced a message. */
    data class DecodeFailed(val face: ProductionFace, val missingOrInvalidRequiredFields: Set<String>) :
        MessageShadowOutcome

    /** Both paths produced a message but their canonical facts differ. [fields] names the divergent facts. */
    data class Divergent(val face: ProductionFace, val fields: List<String>) : MessageShadowOutcome

    /** Canonical facts decoded, but the app projection rejected an unknown sender/message enum. */
    data class ProjectionRejected(val face: ProductionFace) : MessageShadowOutcome

    /**
     * The decoded fact did not survive the ENVELOPE codec round-trip: [encodeCanonicalFact] produced a BLOB
     * that [decodeCanonicalFact] could not read back (fail-closed). This is the codec/envelope layer only —
     * it does NOT prove the store persist→restore path on real traffic (that is a deterministic property
     * covered off-line by the #709 store gates; see the fingerprint-coverage bridge in the KDoc).
     */
    data class EnvelopeDecodeFailed(val face: ProductionFace) : MessageShadowOutcome

    /**
     * The ENVELOPE codec round-trip restored a DIFFERENT canonical fact than was decoded (the envelope is
     * lossy on this real production fact). [fields] names the drifted canonical-row fields. Codec/envelope
     * layer — isolates an envelope-losslessness divergence from a decode/projection [Divergent]. Named
     * `Envelope*` (not `Persist*`) deliberately: online (a) proves envelope losslessness, never real-traffic
     * store persistence — conflating them is a fake-assurance trap (赵 contract-face).
     */
    data class EnvelopeRoundTripDivergent(val face: ProductionFace, val fields: List<String>) : MessageShadowOutcome
}

/**
 * Run the canonical pipeline over [rawJson] for [face] and compare its projection against the
 * authoritative [authoritative] message on canonical facts only. Pure — no store writes, no flag reads.
 *
 * The compared projection is the fact AFTER an ENVELOPE codec round-trip ([encodeCanonicalFact] →
 * [decodeCanonicalFact]), so Parity proves: decode → envelope round-trip → project reproduces the
 * authoritative message on real traffic. This is the envelope layer ONLY (non-invasive, no real DB); a fact
 * that will not restore, or restores drifted, is surfaced as [MessageShadowOutcome.EnvelopeDecodeFailed] /
 * [MessageShadowOutcome.EnvelopeRoundTripDivergent].
 *
 * IMPORTANT — this does NOT prove the store persist→restore path on real traffic. Store scope/scalar/poison
 * are deterministic properties covered off-line by the #709 store gates (fullFieldRoundTrip / continuation /
 * scalar-tamper poison) over SYNTHETIC fixtures. To stop "online envelope-clean" being misread as
 * "real-traffic store-persist-clean", the t1 acceptance adds a fingerprint-coverage column: each event's
 * envelope schema-fingerprint must be within the shape-set the offline store gates exercise; a real-traffic
 * shape outside that set does not pass silently — it triggers adding that shape to the offline fixture. That
 * fingerprint/counter face lands with t1 (per-face counters), aligned with the contract face (赵).
 *
 * [memberHydration] must match what the authoritative path applies; the realtime mapper builds the author
 * purely from wire (no directory enrichment), so the default identity hydration is the correct parity input.
 */
fun compareCanonicalShadow(
    rawJson: JsonObject,
    face: ProductionFace,
    authoritative: Message,
    memberHydration: CanonicalMemberHydration = { it },
    // The persist codec seam — defaults to the real T-wrapper codec. Injectable so the persist-parity
    // outcomes ([PersistDivergent]/[PersistDecodeFailed]) can be proven to bite on a lossy/failing codec.
    encodeFact: (CanonicalMessageRow) -> String = ::encodeCanonicalFact,
    decodeFact: (String) -> CanonicalMessageRow? = ::decodeCanonicalFact,
): MessageShadowOutcome {
    var violation: MessageWireCanonicalViolation? = null
    val decoded = decodeCanonicalMessageWire(rawJson, face) { violation = it }
        ?: return MessageShadowOutcome.DecodeFailed(
            face,
            violation?.missingOrInvalidRequiredFields ?: emptySet(),
        )
    return compareCanonicalShadow(decoded, face, authoritative, memberHydration, encodeFact, decodeFact)
}

/**
 * Pre-decoded variant (#607 decode-once): compare an already-decoded [CanonicalMessageCarry] against the
 * authoritative [authoritative] message. Used by the projection gateway so the SAME single decode feeds
 * both this shadow-compare and the fold+persist carry — never [MessageShadowOutcome.DecodeFailed] (a
 * decode failure fails closed before this call). Same envelope round-trip + parity semantics as above.
 */
fun compareCanonicalShadow(
    decoded: CanonicalMessageCarry,
    face: ProductionFace,
    authoritative: Message,
    memberHydration: CanonicalMemberHydration = { it },
    encodeFact: (CanonicalMessageRow) -> String = ::encodeCanonicalFact,
    decodeFact: (String) -> CanonicalMessageRow? = ::decodeCanonicalFact,
): MessageShadowOutcome {
    // Envelope parity: the decoded fact must round-trip losslessly through the envelope codec. Restore-purity
    // (project the RESTORED row) folds the envelope layer into the same parity signal as decode.
    val restored = decodeFact(encodeFact(decoded.row))
        ?: return MessageShadowOutcome.EnvelopeDecodeFailed(face)
    val envelopeFields = canonicalRowDiff(decoded.row, restored)
    if (envelopeFields.isNotEmpty()) return MessageShadowOutcome.EnvelopeRoundTripDivergent(face, envelopeFields)

    val shadow = restored.toAppMessage(memberHydration = memberHydration)
        ?: return MessageShadowOutcome.ProjectionRejected(face)
    val fields = shadowParityDiff(authoritative.toShadowParityView(), shadow.toShadowParityView())
    return if (fields.isEmpty()) MessageShadowOutcome.Parity else MessageShadowOutcome.Divergent(face, fields)
}

/**
 * Names the canonical-row fields that differ between the decoded fact and its envelope round-trip restore.
 * Empty = the envelope codec is lossless on this fact. Covers every [CanonicalMessageRow] field (a dropped
 * field = a lossy envelope), so an OptionalAggregate the envelope silently flattened surfaces by name.
 */
private fun canonicalRowDiff(a: CanonicalMessageRow, b: CanonicalMessageRow): List<String> {
    val diffs = mutableListOf<String>()
    fun check(name: String, x: Any?, y: Any?) {
        if (x != y) diffs.add(name)
    }
    check("id", a.id, b.id)
    check("channelId", a.channelId, b.channelId)
    check("seq", a.seq, b.seq)
    check("senderType", a.senderType, b.senderType)
    check("senderId", a.senderId, b.senderId)
    check("content", a.content, b.content)
    check("messageType", a.messageType, b.messageType)
    check("createdAt", a.createdAt, b.createdAt)
    check("randomId", a.randomId, b.randomId)
    check("threadId", a.threadId, b.threadId)
    check("senderName", a.senderName, b.senderName)
    check("senderDescription", a.senderDescription, b.senderDescription)
    check("senderMembershipStatus", a.senderMembershipStatus, b.senderMembershipStatus)
    check("mentions", a.mentions, b.mentions)
    check("attachments", a.attachments, b.attachments)
    check("reactions", a.reactions, b.reactions)
    check("commentRef", a.commentRef, b.commentRef)
    check("actionMetadata", a.actionMetadata, b.actionMetadata)
    check("conversationContext", a.conversationContext, b.conversationContext)
    return diffs
}

/**
 * NORMALIZE step. Canonicalize the compared canonical-fact fields whose two projections differ only in a
 * lossy/degenerate way, so structural equality does not false-positive. Fields NOT in [shadowParityDiff]'s
 * compared set are EXCLUDED by omission (not touched here) — see that function for the exclusion ledger.
 *
 * Each normalization below is a compared fact, listed with the exact reason both paths may differ:
 *  - author: identity facts only; drop directory enrichment,
 *    and align displayName/handle to `ifBlank { id }` (legacy uses `senderName ?: id`, projection uses
 *    `ifBlank`; differ only when senderName is an explicit "", a producer violation — known fail-soft delta).
 *  - attachments.commentCount: viewer-scoped, excluded from the canonical row → zero on both.
 *  - reactions: collapse empty/null "no reactions" to null on both (legacy may emit emptyList()).
 */
fun Message.toShadowParityView(): Message = copy(
    author = author.toShadowParityView(),
    attachments = attachments.map { it.copy(commentCount = 0) },
    reactions = reactions?.takeIf { it.isNotEmpty() },
)

/** Author identity facts only — displayName/handle/kind/description from wire; enrichment dropped. */
private fun MemberProfile.toShadowParityView(): MemberProfile = MemberProfile(
    id = id,
    // displayName/handle: legacy uses `senderName ?: id`, projection `ifBlank { id }`; differ only on an
    // explicit "" senderName — a REGISTERED known-delta (senderId fallback, no product gate). Normalized.
    displayName = displayName.ifBlank { id.value },
    handle = displayName.ifBlank { id.value },
    kind = kind,
    // description: compared as-is. Empty-string vs null is NOT a registered delta — projection must
    // reproduce legacy (keep the empty string); surfacing any divergence here is intentional (AD2 #2).
    description = description,
    avatarLabel = displayName.ifBlank { id.value }.take(1).uppercase(),
)

/**
 * Names the canonical facts that differ between two already-normalized parity views. Empty = parity.
 *
 * This list IS the compared canonical-fact set. Every other [Message] field is EXCLUDED by omission — it is
 * a field the canonical shared row does not carry, so comparing it would be a guaranteed false divergence:
 *  - timestampLabel / updatedAtLabel — `updatedAt`-derived; excluded emittedUnconsumed (row has createdAt only)
 *  - task — task-domain projection, joined after this boundary
 *  - deliveryState — client-local send state (row is always server-sent)
 *  - systemEventType / replyCount / unreadReplyCount / firstUnreadReplyMessageId / isThreadFollowed /
 *    isSaved / parentMessageId — thread/reply/saved projections delivered by other events, not message:*
 * If a future field becomes a canonical fact, add it here explicitly — never fold it in silently.
 */
private fun shadowParityDiff(a: Message, b: Message): List<String> {
    val diffs = mutableListOf<String>()
    fun check(name: String, x: Any?, y: Any?) {
        if (x != y) diffs.add(name)
    }
    check("id", a.id, b.id)
    check("channelId", a.channelId, b.channelId)
    check("threadId", a.threadId, b.threadId)
    check("threadChannelId", a.threadChannelId, b.threadChannelId)
    check("seq", a.seq, b.seq)
    check("body", a.body, b.body)
    check("messageType", a.messageType, b.messageType)
    check("senderMembershipStatus", a.senderMembershipStatus, b.senderMembershipStatus)
    check("randomId", a.randomId, b.randomId)
    check("author", a.author, b.author)
    check("attachments", a.attachments, b.attachments)
    check("reactions", a.reactions, b.reactions)
    check("commentRef", a.commentRef, b.commentRef)
    check("conversationContext", a.conversationContext, b.conversationContext)
    check("metadata", a.metadata, b.metadata)
    check("forwardBundle", a.forwardBundle, b.forwardBundle)
    return diffs
}
