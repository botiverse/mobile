package build.raft.app

import build.raft.app.data.ForwardMessageDestination
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.model.ServerId

/**
 * task #803 (P0, feedback #152): displayed target A occasionally forwarded to
 * B. The destination identity is FROZEN at row-tap time into this immutable
 * receipt — the note modal and the send path consume only the receipt and may
 * never re-resolve identity from a mutable list index, display name, or
 * "current selection". Any drift between what was displayed and what would be
 * sent invalidates the operation instead of forwarding elsewhere (桑兰's
 * frozen contract, #proj-chat:4e1f5d74).
 */
internal data class ThreadForwardDestinationReceipt(
    /** Unique per row-tap; a new tap always mints a new receipt. */
    val operationId: String,
    /** Source server frozen at tap; a server switch invalidates the send. */
    val sourceServerId: ServerId,
    /** Directory list generation at tap; a refresh invalidates the send. */
    val directoryGeneration: Int,
    /** The stable key of the row THE USER SAW, captured at render identity. */
    val displayedStableKey: String,
    /** Display-name snapshot for receipts/diagnostics (never re-resolved). */
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
        displayedStableKey = threadForwardSelectionKey(),
        displayedTitle = title,
        target = this
    )

/**
 * Pre-resolve gate: the world must still look like it did at tap time.
 * Returns a typed failure code, or null when the send may proceed to resolve.
 */
internal fun ThreadForwardDestinationReceipt.preResolveFailureCode(
    currentSourceServerId: ServerId?,
    currentDirectoryGeneration: Int
): String? =
    when {
        // Tamper/copy bugs: the frozen object must still be the displayed one.
        target.threadForwardSelectionKey() != displayedStableKey ->
            "forward_destination_identity_mismatch"
        currentSourceServerId == null || currentSourceServerId != sourceServerId ->
            "forward_source_server_changed"
        currentDirectoryGeneration != directoryGeneration ->
            "forward_directory_refreshed"
        else -> null
    }

/**
 * Post-resolve gate: the wire destination must bind to the SAME identity the
 * user saw. Mismatch = typed error and ZERO forward API traffic.
 */
internal fun ThreadForwardDestinationReceipt.postResolveFailureCode(
    destination: ForwardMessageDestination
): String? {
    val frozenChannelId = target.channelId
    if (frozenChannelId != null) {
        val sameChannel = destination.channelId == frozenChannelId
        val sameJointProjection =
            target.jointChannelId != null && destination.jointChannelId == target.jointChannelId
        if (!sameChannel && !sameJointProjection) {
            return "forward_destination_identity_mismatch"
        }
        return null
    }
    // Member DM candidate (channelId unknown until create/resolve): the
    // repository binds member→channel; here we enforce the invariants that do
    // not require trusting the mutable directory again.
    if (target.memberId == null) {
        return "forward_destination_identity_mismatch"
    }
    if (destination.serverId != sourceServerId) {
        return "forward_destination_identity_mismatch"
    }
    return null
}
