package build.raft.app

import build.raft.app.data.PendingMentionActionExecuteResult
import build.raft.app.data.ThreadApiResult

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
    val resultSummaryTemplate: String,
    val missing: String,
    val deliveryUnavailable: String,
    val dropped: String,
    val notifyDropped: String,
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
    val generic: String,
    val requestConnection: String,
    val requestAuthentication: String,
    val requestPermission: String,
    val requestRateLimited: String,
    val requestServer: String,
    val requestUnexpectedResponse: String,
    val requestGeneric: String
)

internal enum class PendingMentionActionRequestFailureKind {
    Connection,
    Authentication,
    Permission,
    Missing,
    RateLimited,
    Server,
    UnexpectedResponse,
    Generic
}

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
    actions: List<RaftPendingMentionActionItem>,
    actionStates: Map<String, String>,
    removingIds: Set<String>,
    executingIds: Set<String>
): List<RaftPendingMentionActionItem> {
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
    val failure = localizedFailureNotice(strings) ?: return null
    return strings.resultTemplate
        .replace("__RAFT_ADDED__", deliveredCount.toString())
        .replace("__RAFT_TOTAL__", requestedCount.toString())
        .replace("__RAFT_ERROR__", failure)
}

internal fun PendingMentionActionBatchOutcome.localizedNoticeTitle(
    strings: PendingMentionActionBatchStrings
): String? {
    if (requestedCount == 0 || deliveredCount == requestedCount) return null
    return strings.resultSummaryTemplate
        .replace("__RAFT_ADDED__", deliveredCount.toString())
        .replace("__RAFT_TOTAL__", requestedCount.toString())
}

internal fun PendingMentionActionBatchOutcome.localizedFailureNotice(
    strings: PendingMentionActionBatchStrings
): String? {
    if (requestedCount == 0 || deliveredCount == requestedCount) return null
    return if (missingFirstFailure) {
        strings.missing
    } else {
        firstFailure.localizedPendingMentionActionFailure(action = "add", strings = strings)
    }
}

internal fun PendingMentionActionExecuteResult?.localizedPendingMentionActionFailure(
    action: String,
    strings: PendingMentionActionBatchStrings
): String =
    when (this?.status) {
        null, "not_found" -> strings.missing
        "dropped" ->
            when {
                reason == "delivery_unavailable" -> strings.deliveryUnavailable
                action == "notify" || action == "notify_only" -> strings.notifyDropped
                else -> strings.dropped
            }
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

internal fun pendingMentionActionRequestFailureKind(
    result: ThreadApiResult<*>?,
    requestThrew: Boolean
): PendingMentionActionRequestFailureKind? {
    if (requestThrew) return PendingMentionActionRequestFailureKind.Connection
    val failure = result as? ThreadApiResult.Failure ?: return null
    if (!failure.contractCode.isNullOrBlank()) {
        return PendingMentionActionRequestFailureKind.UnexpectedResponse
    }
    return when (failure.code) {
        in Int.MIN_VALUE..0, 408 -> PendingMentionActionRequestFailureKind.Connection
        401 -> PendingMentionActionRequestFailureKind.Authentication
        403 -> PendingMentionActionRequestFailureKind.Permission
        404, 410 -> PendingMentionActionRequestFailureKind.Missing
        429 -> PendingMentionActionRequestFailureKind.RateLimited
        in 500..599 -> PendingMentionActionRequestFailureKind.Server
        else -> PendingMentionActionRequestFailureKind.Generic
    }
}

internal fun localizedPendingMentionActionRequestFailure(
    result: ThreadApiResult<*>?,
    requestThrew: Boolean,
    strings: PendingMentionActionBatchStrings
): String? =
    when (pendingMentionActionRequestFailureKind(result, requestThrew)) {
        null -> null
        PendingMentionActionRequestFailureKind.Connection -> strings.requestConnection
        PendingMentionActionRequestFailureKind.Authentication -> strings.requestAuthentication
        PendingMentionActionRequestFailureKind.Permission -> strings.requestPermission
        PendingMentionActionRequestFailureKind.Missing -> strings.missing
        PendingMentionActionRequestFailureKind.RateLimited -> strings.requestRateLimited
        PendingMentionActionRequestFailureKind.Server -> strings.requestServer
        PendingMentionActionRequestFailureKind.UnexpectedResponse -> strings.requestUnexpectedResponse
        PendingMentionActionRequestFailureKind.Generic -> strings.requestGeneric
    }
