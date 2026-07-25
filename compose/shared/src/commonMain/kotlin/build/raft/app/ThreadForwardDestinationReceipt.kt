package build.raft.app

import build.raft.app.data.ForwardMessageDestination
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRequiredAction
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId

/**
 * task #803 (P0, feedback #152): displayed target A occasionally forwarded to
 * B. The destination identity is FROZEN at row-tap time into this immutable
 * receipt — the note modal and the send path consume only the receipt and may
 * never re-resolve identity from a mutable list index, display name, or
 * "current selection". Any drift between what was displayed and what would be
 * sent invalidates the operation instead of forwarding elsewhere (桑兰's
 * frozen contract, #proj-chat:4e1f5d74; hardened per LiBai's review round:
 * live-server authority split, full typed routing identity, and resolver
 * provenance).
 */

/**
 * The COMPLETE typed routing identity of a forward target. Every field that
 * participates in choosing the wire destination is covered — a receipt whose
 * target agrees on the display key but differs in ANY routing field is
 * treated as tampered and fails closed before resolve.
 */
internal data class ThreadForwardRoutingIdentity(
    val serverId: ServerId,
    val kind: ForwardMessageTargetKind,
    val id: String,
    val channelId: ChannelId?,
    val memberId: MemberId?,
    val jointChannelId: String?,
    val channelKind: ConversationKind,
    val requiredAction: ForwardMessageTargetRequiredAction
)

internal fun ForwardMessageTarget.threadForwardRoutingIdentity(): ThreadForwardRoutingIdentity =
    ThreadForwardRoutingIdentity(
        serverId = serverId,
        kind = kind,
        id = id,
        channelId = channelId,
        memberId = memberId,
        jointChannelId = jointChannelId?.trim()?.takeIf { it.isNotEmpty() },
        channelKind = channelKind,
        requiredAction = requiredAction
    )

internal data class ThreadForwardDestinationReceipt(
    /** Unique per row-tap; a new tap always mints a new receipt. */
    val operationId: String,
    /** Source server frozen at tap; compared against LIVE authority at send. */
    val sourceServerId: ServerId,
    /** Directory list generation at tap; a refresh invalidates the send. */
    val directoryGeneration: Int,
    /** Full typed routing identity frozen at tap (tamper guard authority). */
    val frozenRoutingIdentity: ThreadForwardRoutingIdentity,
    /** Display-name snapshot for receipts/toasts (never re-resolved). */
    val displayedTitle: String,
    /** The full identity object captured from the tapped row. */
    val target: ForwardMessageTarget
)

internal fun ForwardMessageTarget.toThreadForwardDestinationReceipt(
    operationId: String,
    sourceServerId: ServerId,
    directoryGeneration: Int
): ThreadForwardDestinationReceipt =
    ThreadForwardDestinationReceipt(
        operationId = operationId,
        sourceServerId = sourceServerId,
        directoryGeneration = directoryGeneration,
        frozenRoutingIdentity = threadForwardRoutingIdentity(),
        displayedTitle = title,
        target = this
    )

/**
 * Pre-resolve gate: the world must still look like it did at tap time.
 * [liveSourceServerId] MUST be the CURRENT server authority read at confirm
 * time — never the frozen picker-open value, or a server switch while the
 * modal is open sails through a tautological comparison.
 * Returns a typed failure code, or null when the send may proceed to resolve.
 */
internal fun ThreadForwardDestinationReceipt.preResolveFailureCode(
    liveSourceServerId: ServerId?,
    currentDirectoryGeneration: Int
): String? =
    when {
        // Tamper/copy bugs: EVERY routing field must still be the frozen one.
        target.threadForwardRoutingIdentity() != frozenRoutingIdentity ->
            "forward_destination_identity_mismatch"
        liveSourceServerId == null || liveSourceServerId != sourceServerId ->
            "forward_source_server_changed"
        currentDirectoryGeneration != directoryGeneration ->
            "forward_directory_refreshed"
        else -> null
    }

/**
 * Post-resolve gate: the wire destination must PROVABLY belong to the frozen
 * identity — exact server + channel for plain channels, exact source-side
 * joint projection for joint channels, and resolver-attested member
 * provenance for DM candidates. Mismatch = typed error, ZERO forward traffic.
 */
internal fun ThreadForwardDestinationReceipt.postResolveFailureCode(
    destination: ForwardMessageDestination
): String? {
    val frozen = frozenRoutingIdentity
    val frozenChannelId = frozen.channelId
    if (frozenChannelId != null) {
        val samePlainChannel =
            destination.channelId == frozenChannelId && destination.serverId == frozen.serverId
        val sameJointProjection =
            frozen.jointChannelId != null &&
                destination.jointChannelId == frozen.jointChannelId &&
                destination.channelKind == ConversationKind.Joint &&
                destination.serverId == sourceServerId
        if (!samePlainChannel && !sameJointProjection) {
            return "forward_destination_identity_mismatch"
        }
        return null
    }
    // Member DM candidate: the resolver must attest WHICH member it resolved
    // against; a same-server wrong-member DM must never pass.
    val frozenMemberId = frozen.memberId ?: return "forward_destination_identity_mismatch"
    if (destination.resolvedMemberId != frozenMemberId) {
        return "forward_destination_identity_mismatch"
    }
    if (destination.serverId != sourceServerId) {
        return "forward_destination_identity_mismatch"
    }
    return null
}
