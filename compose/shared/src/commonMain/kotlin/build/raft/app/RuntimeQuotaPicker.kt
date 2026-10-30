package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeQuotaLineCopy
import build.raft.app.data.RuntimeQuotaSummary
import build.raft.app.data.canViewMachineRuntimeAccountUsage
import build.raft.app.data.RuntimeQuotaLinePart
import build.raft.app.data.runtimeQuotaLineParts
import build.raft.app.data.summarizeRuntimeQuota
import build.raft.designsystem.components.RaftChoicePicker
import build.raft.designsystem.components.RaftComboboxListAction
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftSelectTrigger
import kotlinx.coroutines.launch

/**
 * One option of a machine/runtime picker that may carry a compact quota line: the usage of
 * [runtimeId] on [machineId]. [attachedByCurrentUser] and [creatorId] are the machine's facts
 * for [canViewMachineRuntimeAccountUsage]; when [machineFactsKnown] is false (a picker that
 * only knows the machine id) the server alone decides (a 403 shows no line).
 */
internal data class RuntimeQuotaRequest(
    val optionId: String,
    val machineId: String?,
    val runtimeId: String?,
    val machineFactsKnown: Boolean = false,
    val attachedByCurrentUser: Boolean = false,
    val creatorId: String? = null
)

/** Whether [access]'s viewer may see [request]'s machine usage (unknown facts: ask the server). */
internal fun MembersRuntimeAccountUsageAccess.mayRead(request: RuntimeQuotaRequest): Boolean {
    val who = viewer ?: return true
    if (!request.machineFactsKnown) return true
    return canViewMachineRuntimeAccountUsage(
        attachedByCurrentUser = request.attachedByCurrentUser,
        creatorId = request.creatorId,
        currentUserId = who.currentUserId,
        canEditMachines = who.canEditMachines
    )
}

/** The usage key of each request [access] may read, by option id. */
internal fun runtimeQuotaKeys(
    access: MembersRuntimeAccountUsageAccess?,
    requests: List<RuntimeQuotaRequest>
): Map<String, RuntimeAccountUsageKey> {
    if (access == null) return emptyMap()
    return requests
        .filter { access.mayRead(it) }
        .mapNotNull { request -> membersRuntimeAccountUsageKey(access, request.runtimeId, request.machineId)?.let { request.optionId to it } }
        .toMap()
}

/** The compact line facts of a usage [load], or null when it has nothing to show. */
internal fun RuntimeAccountUsageLoad?.quotaSummary(key: RuntimeAccountUsageKey): RuntimeQuotaSummary? =
    (this as? RuntimeAccountUsageLoad.Loaded)?.let { summarizeRuntimeQuota(key.provider, it.read) }

@Composable
internal fun rememberRuntimeQuotaLineCopy(): RuntimeQuotaLineCopy {
    val fiveHour = stringResource(Res.string.runtime_quota_window_five_hour)
    val week = stringResource(Res.string.runtime_quota_window_week)
    val used = stringResource(Res.string.runtime_quota_used, "\u0001", "\u0002")
    val usedUp = stringResource(Res.string.runtime_quota_used_up, "\u0001", "\u0002")
    val usedUpNoReset = stringResource(Res.string.runtime_quota_used_up_no_reset, "\u0001")
    val attention = stringResource(Res.string.runtime_quota_attention, "\u0001")
    return remember(fiveHour, week, used, usedUp, usedUpNoReset, attention) {
        RuntimeQuotaLineCopy(
            fiveHour = fiveHour,
            week = week,
            used = { window, percent -> used.replace("\u0001", window).replace("\u0002", percent) },
            usedUp = { window, reset -> usedUp.replace("\u0001", window).replace("\u0002", reset) },
            usedUpNoReset = { window -> usedUpNoReset.replace("\u0001", window) },
            attention = { plan -> attention.replace("\u0001", plan) }
        )
    }
}

/**
 * The usage read of each of [requests] [access] may read, by option id: absent while it loads.
 * Reads go through [access]'s store (60 s cache, one request per key in flight); nothing is read
 * without [access] (no usage on this route) or for a machine the viewer may not see.
 */
@Composable
internal fun rememberRuntimeQuotaLoads(
    access: MembersRuntimeAccountUsageAccess?,
    requests: List<RuntimeQuotaRequest>
): RuntimeQuotaLoads {
    val keys = remember(access, requests) { runtimeQuotaKeys(access, requests) }
    val loads = remember(access) { mutableStateMapOf<RuntimeAccountUsageKey, RuntimeAccountUsageLoad>() }
    LaunchedEffect(access, keys.values.toSet()) {
        val store = access?.store ?: return@LaunchedEffect
        keys.values.toSet().filter { it !in loads }.forEach { key ->
            launch {
                val result = store.read(key)
                access.log("runtime_quota_read provider=${key.provider.wire} result=${result.diagnosticsLabel()}")
                result.shownRuntimeAccountUsage()?.let { loads[key] = it }
                if (result is RuntimeAccountUsageLoad.Superseded) access.onIdentityChanged()
            }
        }
    }
    return RuntimeQuotaLoads(keys, keys.mapNotNull { (optionId, key) -> loads[key]?.let { optionId to it } }.toMap())
}

/** What [rememberRuntimeQuotaLoads] read: the [keys] it may read and the [loads] that came back. */
internal data class RuntimeQuotaLoads(
    val keys: Map<String, RuntimeAccountUsageKey>,
    val loads: Map<String, RuntimeAccountUsageLoad>
)

/**
 * The compact quota line of each option of [quota] that has one, by option id, in pieces
 * ([runtimeQuotaLineParts]); reset times read relative to now.
 */
@Composable
internal fun rememberRuntimeQuotaLineParts(quota: RuntimeQuotaLoads): Map<String, List<RuntimeQuotaLinePart>> {
    val copy = rememberRuntimeQuotaLineCopy()
    val summaries = quota.keys.mapNotNull { (optionId, key) -> quota.loads[optionId].quotaSummary(key)?.let { optionId to it } }
    val timeContext = LocalRaftTimeContextProvider.current?.current
    // Relative reset words ("3 小时后") for every used-up window, resolved in composition.
    val resetLabels =
        summaries
            .flatMap { (_, summary) -> summary.segments.mapNotNull { if (it.usedUp) it.resetsAt else null } }
            .distinct()
            .associateWith { raftRelativeTimestampLabel(it, timeContext) }
    return summaries.associate { (optionId, summary) -> optionId to runtimeQuotaLineParts(summary, copy) { resetLabels[it] } }
}

/** The compact quota line of each of [requests] that has one, by option id ([rememberRuntimeQuotaLoads]). */
@Composable
internal fun rememberRuntimeQuotaLines(
    access: MembersRuntimeAccountUsageAccess?,
    requests: List<RuntimeQuotaRequest>
): Map<String, String> =
    rememberRuntimeQuotaLineParts(rememberRuntimeQuotaLoads(access, requests))
        .mapValues { (_, parts) -> parts.joinToString(" · ") { it.text } }

/** The machine a runtime picker reads quota lines on, and its facts when they are known. */
internal data class RuntimeQuotaMachine(
    val machineId: String,
    val factsKnown: Boolean = false,
    val attachedByCurrentUser: Boolean = false,
    val creatorId: String? = null,
    /**
     * The usage access read outside a dialog (dialog content is hosted at the page root, where
     * the route's [LocalMembersRuntimeAccountUsageAccess] does not reach); null: the local.
     */
    val access: MembersRuntimeAccountUsageAccess? = null
) {
    fun request(optionId: String, runtimeId: String?): RuntimeQuotaRequest =
        RuntimeQuotaRequest(
            optionId = optionId,
            machineId = machineId,
            runtimeId = runtimeId,
            machineFactsKnown = factsKnown,
            attachedByCurrentUser = attachedByCurrentUser,
            creatorId = creatorId
        )
}

internal fun SharedCreateAgentMachineOption.quotaMachine(): RuntimeQuotaMachine =
    RuntimeQuotaMachine(id, factsKnown = usageFactsKnown, attachedByCurrentUser = attachedByCurrentUser, creatorId = creatorId)

/** This computer's quota line request for [runtimeId] (the create dialog's computer picker). */
internal fun SharedCreateAgentMachineOption.quotaRequest(runtimeId: String?): RuntimeQuotaRequest = quotaMachine().request(id, runtimeId)

/**
 * A machine or runtime picker by Jasper's rule ([RaftChoicePicker]: radios up to 5 options, a
 * sheet list up to 10, a searchable sheet beyond), with the shared search and no-match words.
 * With no options at all it is a disabled Select showing [emptyLabel] (or [placeholder]).
 */
@Composable
internal fun RuntimePickerChoice(
    value: String?,
    options: List<RaftComboboxOption<String>>,
    onValueChange: (String) -> Unit,
    title: String,
    placeholder: String,
    enabled: Boolean,
    emptyLabel: String? = null,
    listAction: RaftComboboxListAction? = null
) {
    if (options.isEmpty() && listAction == null) {
        RaftSelectTrigger(value = emptyLabel, onClick = {}, placeholder = placeholder, enabled = false)
        return
    }
    val noMatches = stringResource(Res.string.runtime_picker_no_matches, "\u0001")
    RaftChoicePicker(
        value = value,
        options = options,
        onValueChange = onValueChange,
        title = title,
        emptyText = { query -> noMatches.replace("\u0001", query) },
        placeholder = placeholder,
        searchPlaceholder = stringResource(Res.string.runtime_picker_search),
        enabled = enabled,
        label = title,
        listAction = listAction
    )
}

/**
 * The runtime picker of the create and edit dialogs (web parity, botiverse/slock #11783 /
 * #11794 / #11822): [rows] grouped by what this computer can run, each runtime with, on
 * [quotaMachine], its compact quota line as its description, and Show All Supported / Show
 * Installed Only as the list's last row when [onShowAllChange] has something to toggle.
 */
@Composable
internal fun RuntimeSelectChoice(
    runtime: String,
    rows: RuntimePickerRows,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    quotaMachine: RuntimeQuotaMachine?,
    enabled: Boolean,
    emptyLabel: String?,
    onSelect: (String) -> Unit
) {
    val quotaLines =
        rememberRuntimeQuotaLines(
            quotaMachine?.access ?: LocalMembersRuntimeAccountUsageAccess.current,
            remember(rows, quotaMachine) {
                if (quotaMachine == null) emptyList() else rows.rows.map { quotaMachine.request(it.id, it.id) }
            }
        )
    val copy = rememberRuntimePickerCopy()
    val options = runtimePickerOptions(rows.rows, copy, quotaLines)
    val showAllLabel = stringResource(Res.string.runtime_picker_show_all_supported)
    val installedOnlyLabel = stringResource(Res.string.runtime_picker_show_installed_only)
    RuntimePickerChoice(
        value = runtime.takeIf { id -> options.any { it.value == id } },
        options = options,
        onValueChange = onSelect,
        title = stringResource(Res.string.agent_create_label_runtime),
        placeholder = stringResource(Res.string.agent_create_placeholder_select_runtime),
        enabled = enabled,
        emptyLabel = emptyLabel,
        listAction =
            if (rows.canToggle) {
                RaftComboboxListAction(if (showAll) installedOnlyLabel else showAllLabel) { onShowAllChange(!showAll) }
            } else {
                null
            }
    )
}
