package build.raft.app

import build.raft.app.messageRes
import build.raft.app.resources.*
import build.raft.app.thread.messageRes
import org.jetbrains.compose.resources.StringResource

/**
 * An action the daemon held until the agent reads newer messages (detailKind `freshness_hold`;
 * shared `projectApmHeldFreshnessActivity`). That is the normal flow, so the app says what the
 * agent is doing instead of the daemon's English title (web `utils/activity.ts`
 * `FRESHNESS_HOLD_LABELS`, botiverse/slock staging).
 */
internal enum class AgentFreshnessHold(val daemonTitle: String, val labelRes: StringResource) {
    Send("Send held by freshness check", Res.string.member_activity_log_freshness_hold_send),
    TaskClaim("Task claim held by freshness check", Res.string.member_activity_log_freshness_hold_task_claim),
    TaskAmend("Task amendment held by freshness check", Res.string.member_activity_log_freshness_hold_task_amend),
    TaskUpdate("Task update held by freshness check", Res.string.member_activity_log_freshness_hold_task_update)
}

internal const val AgentActivityDetailKindFreshnessHold = "freshness_hold"

/** task #1116: deliveries the daemon wrote that the runtime never consumed (web `activity.status.deliveryUnconsumed`). */
internal const val AgentActivityDetailKindDeliveryUnconsumed = "delivery_unconsumed"

/** Web `freshnessHoldLabelId`: the hold a daemon title names, or null for any other text. */
internal fun agentFreshnessHold(title: String?): AgentFreshnessHold? {
    val trimmed = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return AgentFreshnessHold.entries.firstOrNull { it.daemonTitle == trimmed }
}

/** How many newer messages the hold waits on, and where. */
internal data class AgentFreshnessHoldDetail(val count: Int, val target: String)

private val FreshnessHoldTarget = Regex("^target: (.+)$", RegexOption.MULTILINE)
private val FreshnessHoldNewMessages = Regex("^new messages: (\\d+) newer", RegexOption.MULTILINE)
private val FreshnessHoldSyncedContext = Regex("^unreviewed synced context for this target: (\\d+)", RegexOption.MULTILINE)

/**
 * Web `parseFreshnessHoldText`: the hold's body as the daemon writes it ("target: #x",
 * "new messages: 3 newer messages", ...) read back as the count and the target. Null when it does
 * not read as one.
 */
internal fun parseAgentFreshnessHoldText(text: String?): AgentFreshnessHoldDetail? {
    if (text.isNullOrBlank()) return null
    val target = FreshnessHoldTarget.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val count =
        (FreshnessHoldNewMessages.find(text) ?: FreshnessHoldSyncedContext.find(text))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: return null
    return AgentFreshnessHoldDetail(count = count, target = target)
}

/** The `_one` / `_other` catalog string for a hold's "N new messages in #x". */
internal fun AgentFreshnessHoldDetail.messageRes(): StringResource =
    if (count == 1) {
        Res.string.member_activity_log_freshness_hold_detail_one
    } else {
        Res.string.member_activity_log_freshness_hold_detail_other
    }
