package build.raft.app.network.sync.activity

import build.raft.app.network.sync.activity.contract.v1.ACTIVITY_SYNC_CONTRACT
import build.raft.app.network.sync.activity.contract.v1.ACTIVITY_SYNC_CONTRACT_REVISION

/** Response header naming the Activity sync contract revision the server speaks. */
const val ACTIVITY_CONTRACT_RESPONSE_HEADER = "X-Raft-Contract"

/** Request header carrying the revision these bindings were generated at (recorded server-side only). */
const val ACTIVITY_CONTRACT_CLIENT_REVISION_HEADER = "X-Raft-Contract-Revision"

/**
 * Whether the Activity sync engine may run against this server.
 *
 * The rule (slock revisions.json, task #3): the engine runs only when the
 * server reports exactly the revision the client's bindings were generated at.
 * Anything else, including a missing or unparseable header from an older
 * server, keeps the client on the existing `/channels/inbox` path.
 */
data class ActivityContractDecision(
    val engineEnabled: Boolean,
    val clientRevision: Int,
    val serverRevision: Int?,
    val reason: Reason,
) {
    enum class Reason { Match, Mismatch, HeaderMissing, HeaderUnparseable, ContractMismatch }

    /** One diagnostic line with both revisions, so a mismatch is visible in traces. */
    val diagnostic: String
        get() = "activity_contract decision=${if (engineEnabled) "engine" else "inbox_fallback"} " +
            "reason=${reason.name} client_revision=$clientRevision server_revision=${serverRevision ?: "none"}"
}

private val CONTRACT_HEADER_RE = Regex("""^\s*([a-z0-9.-]+)\s*;\s*revision=([1-9][0-9]{0,8})\s*$""")

fun decideActivitySyncEngine(
    serverContractHeader: String?,
    clientRevision: Int = ACTIVITY_SYNC_CONTRACT_REVISION,
    contract: String = ACTIVITY_SYNC_CONTRACT,
): ActivityContractDecision {
    if (serverContractHeader.isNullOrBlank()) {
        return ActivityContractDecision(false, clientRevision, null, ActivityContractDecision.Reason.HeaderMissing)
    }
    val match = CONTRACT_HEADER_RE.matchEntire(serverContractHeader)
        ?: return ActivityContractDecision(false, clientRevision, null, ActivityContractDecision.Reason.HeaderUnparseable)
    val serverRevision = match.groupValues[2].toInt()
    if (match.groupValues[1] != contract) {
        return ActivityContractDecision(false, clientRevision, serverRevision, ActivityContractDecision.Reason.ContractMismatch)
    }
    return if (serverRevision == clientRevision) {
        ActivityContractDecision(true, clientRevision, serverRevision, ActivityContractDecision.Reason.Match)
    } else {
        ActivityContractDecision(false, clientRevision, serverRevision, ActivityContractDecision.Reason.Mismatch)
    }
}
