package build.raft.app

import build.raft.app.data.PendingMentionActionExecuteResult

internal data class PendingMentionActionBatchOutcome(
    val requestedIds: List<String>,
    val deliveredIds: Set<String>,
    val firstFailure: PendingMentionActionExecuteResult?
) {
    val requestedCount: Int = requestedIds.size
    val deliveredCount: Int = deliveredIds.size
    val missingFirstFailure: Boolean =
        firstFailure == null && requestedIds.any { it !in deliveredIds }
}

internal data class PendingMentionActionBatchStrings(
    val resultTemplate: String,
    val missing: String,
    val deliveryUnavailable: String,
    val dropped: String,
    val senderLacksChannelAccess: String,
    val targetLacksReadAccess: String,
    val addNotAllowedForSurface: String,
    val addRequiresHumanAuthority: String,
    val addRequiresMemberManagementAuthority: String,
    val noPermission: String,
    val targetAlreadyMember: String,
    val targetUnavailable: String,
    val stale: String,
    val expired: String,
    val ambiguous: String,
    val generic: String
)

internal class PendingMentionActionBatchRequestGate {
    private var inFlight = false

    fun tryBegin(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    fun finish() {
        inFlight = false
    }
}

internal fun eligiblePendingMentionActionBatch(
    actions: List<KuiklyPendingMentionActionItem>,
    actionStates: Map<String, String>,
    removingIds: Set<String>,
    executingIds: Set<String>
): List<KuiklyPendingMentionActionItem> {
    val seen = mutableSetOf<String>()
    return actions.filter { action ->
        val resolutionId = action.resolutionId.trim()
        resolutionId.isNotEmpty() &&
            seen.add(resolutionId) &&
            actionStates[resolutionId] == null &&
            resolutionId !in removingIds &&
            resolutionId !in executingIds &&
            action.supportsMentionAction("invite")
    }
}

internal fun pendingMentionActionBatchOutcome(
    requestedIds: List<String>,
    results: List<PendingMentionActionExecuteResult>
): PendingMentionActionBatchOutcome {
    val stableRequestedIds = requestedIds.map(String::trim).filter(String::isNotEmpty).distinct()
    val firstResultById = linkedMapOf<String, PendingMentionActionExecuteResult>()
    results.forEach { result ->
        val resolutionId = result.resolutionId.trim()
        if (resolutionId.isNotEmpty() && resolutionId !in firstResultById) {
            firstResultById[resolutionId] = result
        }
    }
    val deliveredIds = stableRequestedIds.filterTo(linkedSetOf()) { id -> firstResultById[id]?.status == "delivered" }
    val firstFailureId = stableRequestedIds.firstOrNull { it !in deliveredIds }
    return PendingMentionActionBatchOutcome(
        requestedIds = stableRequestedIds,
        deliveredIds = deliveredIds,
        firstFailure = firstFailureId?.let(firstResultById::get)
    )
}

internal fun PendingMentionActionBatchOutcome.localizedNotice(strings: PendingMentionActionBatchStrings): String? {
    if (requestedCount == 0 || deliveredCount == requestedCount) return null
    val failure =
        if (missingFirstFailure) {
            strings.missing
        } else {
            firstFailure.localizedPendingMentionBatchFailure(strings)
        }
    return strings.resultTemplate
        .replace("__RAFT_ADDED__", deliveredCount.toString())
        .replace("__RAFT_TOTAL__", requestedCount.toString())
        .replace("__RAFT_ERROR__", failure)
}

private fun PendingMentionActionExecuteResult?.localizedPendingMentionBatchFailure(
    strings: PendingMentionActionBatchStrings
): String =
    when (this?.status) {
        null, "not_found" -> strings.missing
        "dropped" -> if (reason == "delivery_unavailable") strings.deliveryUnavailable else strings.dropped
        "no_permission" ->
            when (reason) {
                "sender_lacks_channel_access" -> strings.senderLacksChannelAccess
                "target_lacks_read_access" -> strings.targetLacksReadAccess
                "add_not_allowed_for_surface" -> strings.addNotAllowedForSurface
                "add_requires_human_member_authority" -> strings.addRequiresHumanAuthority
                "add_requires_member_management_authority" -> strings.addRequiresMemberManagementAuthority
                else -> strings.noPermission
            }
        "stale" ->
            when (reason) {
                "target_already_member" -> strings.targetAlreadyMember
                "target_unavailable" -> strings.targetUnavailable
                else -> strings.stale
            }
        "expired" -> strings.expired
        "ambiguous" -> strings.ambiguous
        else -> strings.generic
    }
