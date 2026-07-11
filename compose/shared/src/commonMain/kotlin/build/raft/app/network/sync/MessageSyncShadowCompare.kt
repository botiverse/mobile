package build.raft.app.network.sync

import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import kotlinx.serialization.json.JsonObject

/**
 * Shadow-compare for the flag-gated sync-core message rollout (`sync_core_messages_v0`).
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
 *  - `MemberProfile.roleDescription` and all directory enrichment — the real mapper reads a non-canonical
 *    `roleDescription` wire field; it is intentionally dropped here (see MEMBER_ROLE_DESCRIPTION_NOTE).
 */

// MEMBER_ROLE_DESCRIPTION_NOTE: RealtimeEventMapper.MessageWireDto.toDomain() populates
// author.roleDescription from a `roleDescription` wire field that is NOT one of the 19 canonical manifest
// fields. The G6 reverse-closure gate (slock #4593/#4589, green) MECHANICALLY PROVES the message:* producer
// never emits it: every message:new/updated post-projection key ⊆ 19 canonical ∪ exclusions, and
// roleDescription is in neither. So server does not send it on message:*, the legacy mapper's read is dead
// code on this face, and dropping it in shadow parity is provably safe (not heuristic). Follow-up is to
// REMOVE the legacy dead read — no manifest expansion needed. (LiBai, G6.)

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
}

/**
 * Run the canonical pipeline over [rawJson] for [face] and compare its projection against the
 * authoritative [authoritative] message on canonical facts only. Pure — no store writes, no flag reads.
 *
 * [memberHydration] must match what the authoritative path applies; the realtime mapper builds the author
 * purely from wire (no directory enrichment), so the default identity hydration is the correct parity input.
 */
fun compareCanonicalShadow(
    rawJson: JsonObject,
    face: ProductionFace,
    authoritative: Message,
    memberHydration: CanonicalMemberHydration = { it },
): MessageShadowOutcome {
    var violation: MessageWireCanonicalViolation? = null
    val decoded = decodeCanonicalMessageWire(rawJson, face) { violation = it }
        ?: return MessageShadowOutcome.DecodeFailed(
            face,
            violation?.missingOrInvalidRequiredFields ?: emptySet(),
        )
    val shadow = decoded.row.toAppMessage(memberHydration)
    val fields = shadowParityDiff(authoritative.toShadowParityView(), shadow.toShadowParityView())
    return if (fields.isEmpty()) MessageShadowOutcome.Parity else MessageShadowOutcome.Divergent(face, fields)
}

/**
 * NORMALIZE step. Canonicalize the compared canonical-fact fields whose two projections differ only in a
 * lossy/degenerate way, so structural equality does not false-positive. Fields NOT in [shadowParityDiff]'s
 * compared set are EXCLUDED by omission (not touched here) — see that function for the exclusion ledger.
 *
 * Each normalization below is a compared fact, listed with the exact reason both paths may differ:
 *  - author: identity facts only; drop roleDescription (non-canonical wire field) + directory enrichment,
 *    and align displayName/handle to `ifBlank { id }` (legacy uses `senderName ?: id`, projection uses
 *    `ifBlank`; differ only when senderName is an explicit "", a producer violation — known fail-soft delta).
 *  - attachments.commentCount: viewer-scoped, excluded from the canonical row → zero on both.
 *  - attachments.sizeLabel: derived DISPLAY format of the canonical `sizeBytes` fact (the app Message does
 *    not retain bytes). Legacy emits raw "<bytes> B"; projection humanizes via formatAttachmentSizeLabel.
 *    Not a canonical-identity divergence → blanked on both. The label-format change at sole-apply is a
 *    layering fix (Attachment should carry sizeBytes; UI formats) tracked with the team.
 *  - reactions: collapse empty/null "no reactions" to null on both (legacy may emit emptyList()).
 */
fun Message.toShadowParityView(): Message = copy(
    author = author.toShadowParityView(),
    attachments = attachments.map { it.copy(commentCount = 0, sizeLabel = "") },
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
