package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.ANTHROPIC_VERIFICATION_PORTAL_URL
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.COMPUTER_READINESS_ATTENTION_UPDATE_AVAILABLE
import build.raft.app.data.COMPUTER_READINESS_ATTENTION_UPDATE_REQUIRED
import build.raft.app.data.COMPUTER_READINESS_KNOWN_CODES
import build.raft.app.data.COMPUTER_READINESS_LIMIT_WAIT_CODES
import build.raft.app.data.ComputerReadinessAction
import build.raft.app.data.ComputerReadinessActor
import build.raft.app.data.ComputerReadinessAttention
import build.raft.app.data.ComputerReadinessAttentionItem
import build.raft.app.data.ComputerReadinessBehaviour
import build.raft.app.data.ComputerReadinessEntry
import build.raft.app.data.ComputerReadinessFact
import build.raft.app.data.ComputerReadinessResource
import build.raft.app.data.ComputerReadinessScope
import build.raft.app.data.ComputerReadinessState
import build.raft.app.data.RUNTIME_USAGE_RESET_FLOOR_MS
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.holds
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerHour
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.resources.*
import build.raft.app.thread.key
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import org.jetbrains.compose.resources.StringResource

/*
 * Computers page readiness & needs-attention presentation (`#wg-mobile-sync:#337`,
 * botiverse/slock #14554, #14587, #14590, #14602):
 *
 * - `packages/web/src/utils/readinessCopy.ts` (readinessLight, formatReadiness, readinessHeading,
 *   readinessBody, readinessWhoOf, readinessDoLabel, formatAttentionItem, attentionItemLines,
 *   formatWaitBackIn)
 * - `packages/web/src/features/computer/ComputersAttentionSummary.tsx`
 * - `packages/web/src/components/machine/RuntimeUsageSurface.tsx` (`RuntimeReadinessNotice`)
 */

internal const val COMPUTERS_ATTENTION_SUMMARY_TAG = "computers-attention-summary"
internal const val COMPUTERS_ATTENTION_LINE_TAG = "computers-attention-line"
internal const val COMPUTERS_ATTENTION_FILTER_TAG = "computers-attention-filter"
internal const val COMPUTERS_ATTENTION_ACTION_TAG = "computers-attention-action"
internal const val COMPUTERS_WAITING_LINE_TAG = "computers-waiting-line"
internal const val COMPUTERS_RACK_COUNTS_TAG = "computers-rack-counts"
internal const val RUNTIME_READINESS_NOTICE_TAG = "runtime-readiness-notice"
internal const val RUNTIME_READINESS_LINK_TAG = "runtime-readiness-link"

internal fun computerStatusDotTag(machineId: String): String = "computer-status-dot-$machineId"

/**
 * Web `readinessLight` (`readinessCopy.ts`):
 * - `computer_offline` -> muted / offline (stone, like any offline device)
 * - `blocked` or a limit wait (`quota_exhausted` / `rate_limited`) -> warning / error (orange)
 * - self-clearing in-progress waits (`computer_restarting` / `computer_updating`) -> busy (yellow)
 */
internal data class RaftReadinessLight(
    val badgeVariant: RaftBadgeVariant,
    val statusVariant: RaftStatusVariant,
    val presence: RaftPresence,
)

internal fun raftReadinessLight(readiness: ComputerReadinessEntry): RaftReadinessLight {
    val primary = readiness.primary
    val code = primary?.code
    if (code == "computer_offline") {
        return RaftReadinessLight(
            badgeVariant = RaftBadgeVariant.Muted,
            statusVariant = RaftStatusVariant.Muted,
            presence = RaftPresence.Offline,
        )
    }
    if (readiness.state == ComputerReadinessState.Blocked || (code != null && code in COMPUTER_READINESS_LIMIT_WAIT_CODES)) {
        return RaftReadinessLight(
            badgeVariant = RaftBadgeVariant.Warning,
            statusVariant = RaftStatusVariant.Warning,
            presence = RaftPresence.Error,
        )
    }
    return RaftReadinessLight(
        badgeVariant = RaftBadgeVariant.Busy,
        statusVariant = RaftStatusVariant.Primary,
        presence = RaftPresence.Busy,
    )
}

/**
 * Web `formatWaitBackIn` / `rackReset` over an epoch-ms [untilMs] at [nowMillis]:
 * minutes under 1h (floor, min 1), hours under 48h, then days; null when absent, implausible, or past.
 */
internal fun raftWaitBackInReset(untilMs: Long?, nowMillis: Long): RaftRackReset? {
    if (untilMs == null || untilMs <= RUNTIME_USAGE_RESET_FLOOR_MS) return null
    val left = untilMs - nowMillis
    if (left <= 0L) return null
    return when {
        left < MillisPerHour -> RaftRackReset(RaftRelativeTimeUnit.Minute, (left / MillisPerMinute).toInt().coerceAtLeast(1))
        left < 48L * MillisPerHour -> RaftRackReset(RaftRelativeTimeUnit.Hour, (left / MillisPerHour).toInt())
        else -> RaftRackReset(RaftRelativeTimeUnit.Day, (left / MillisPerDay).toInt())
    }
}

/** Headline of the Computers page top needs-attention summary (`ComputersAttentionSummary.tsx`). */
internal sealed interface RaftComputersAttentionHeadline {
    /** Exactly one flagged Computer with exactly one attention item. */
    data class SingleItem(
        val machineId: String,
        val machineName: String,
        val item: ComputerReadinessAttentionItem,
    ) : RaftComputersAttentionHeadline

    /** Multiple flagged Computers, or one flagged Computer with multiple items (or count > 0 and items != 1). */
    data class MultipleComputers(val count: Int) : RaftComputersAttentionHeadline
}

/** Pure projection of `ComputersAttentionSummary.tsx`; null when hidden (older server or nothing to say). */
internal data class RaftComputersAttentionSummaryModel(
    val headline: RaftComputersAttentionHeadline?,
    val waitingAgents: Int,
    val waitingReset: RaftRackReset?,
)

internal fun raftComputersAttentionSummary(
    computers: List<SettingsComputerSnapshot>,
    nowMillis: Long,
): RaftComputersAttentionSummaryModel? {
    val summarized =
        computers.mapNotNull { computer ->
            val attention = computer.attention ?: return@mapNotNull null
            computer to attention
        }
    if (summarized.isEmpty()) return null
    val flagged = summarized.filter { (_, attention) -> attention.count > 0 }
    val waitingAgents = summarized.sumOf { (_, attention) -> attention.waitingAgents }
    if (flagged.isEmpty() && waitingAgents <= 0) return null
    val waitingUntilMs = summarized.mapNotNull { (_, attention) -> attention.waitingUntilMs }.minOrNull()
    val headline =
        when {
            flagged.size == 1 && flagged[0].second.items.size == 1 -> {
                val (machine, attention) = flagged[0]
                RaftComputersAttentionHeadline.SingleItem(
                    machineId = machine.id,
                    machineName = machine.name.trim().ifBlank { machine.id },
                    item = attention.items[0],
                )
            }
            flagged.isNotEmpty() -> RaftComputersAttentionHeadline.MultipleComputers(flagged.size)
            else -> null
        }
    return RaftComputersAttentionSummaryModel(
        headline = headline,
        waitingAgents = waitingAgents,
        waitingReset = if (waitingAgents > 0) raftWaitBackInReset(waitingUntilMs, nowMillis) else null,
    )
}

internal data class RaftReadinessLink(val href: String, val text: String)

internal data class RaftReadinessCopy(
    val title: String,
    val scope: String?,
    val detail: String,
    val actor: String?,
    val link: RaftReadinessLink?,
)

internal data class RaftAttentionItemCopy(
    val title: String,
    val who: String?,
)

internal fun raftReadinessRuntimeDisplayName(
    resource: ComputerReadinessResource?,
    fallbackRuntimeId: String?,
    runtimeCatalog: ServerRuntimeCatalog,
): String {
    val runtimeId =
        when (resource) {
            is ComputerReadinessResource.Runtime -> resource.runtime
            is ComputerReadinessResource.Login ->
                if (resource.key.startsWith("shared:")) resource.key.removePrefix("shared:") else null
            is ComputerReadinessResource.LoginWindow ->
                if (resource.key.startsWith("shared:")) resource.key.removePrefix("shared:") else null
            is ComputerReadinessResource.Model ->
                if (resource.key.startsWith("shared:")) resource.key.removePrefix("shared:") else null
            else -> null
        } ?: fallbackRuntimeId
    if (runtimeId.isNullOrBlank()) return ""
    return runtimeCatalog.entry(runtimeId)?.displayName ?: raftAgentRuntimeDisplayName(runtimeId)
}

internal fun raftReadinessDoLabelRes(action: ComputerReadinessAction?): StringResource =
    when (action) {
        ComputerReadinessAction.SignIn -> Res.string.settings_computers_readiness_do_sign_in
        ComputerReadinessAction.VerifyAccount -> Res.string.settings_computers_readiness_do_verify_account
        ComputerReadinessAction.UpdateComputer -> Res.string.settings_computers_readiness_do_update_computer
        ComputerReadinessAction.ChangeModel -> Res.string.settings_computers_readiness_do_change_model
        ComputerReadinessAction.Fix,
        null -> Res.string.settings_computers_readiness_do_fix
    }

@Composable
internal fun raftReadinessDoLabel(action: ComputerReadinessAction?): String =
    stringResource(raftReadinessDoLabelRes(action))

/**
 * Web `readinessWhoOf` (`readinessCopy.ts`):
 * - When the server sent `actorName` (members/owners/admins): `"{name} can ..."`.
 * - When `actorName` is absent (guest privacy, slock #14602): role-based copy from `actor`
 *   (`"The computer's owner can ..."`, `"The agent's owner can ..."`, `"A server admin can ..."`).
 * - Null when neither `actorName` nor `actor` is present.
 */
@Composable
internal fun raftReadinessWhoOf(
    actor: ComputerReadinessActor?,
    actorName: String?,
    action: ComputerReadinessAction?,
): String? {
    val cleanName = actorName?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanName != null) {
        val res =
            when (action) {
                ComputerReadinessAction.SignIn -> Res.string.settings_computers_readiness_can_sign_in
                ComputerReadinessAction.VerifyAccount -> Res.string.settings_computers_readiness_can_verify_account
                ComputerReadinessAction.UpdateComputer -> Res.string.settings_computers_readiness_can_update_computer
                ComputerReadinessAction.ChangeModel -> Res.string.settings_computers_readiness_can_change_model
                ComputerReadinessAction.Fix,
                null -> Res.string.settings_computers_readiness_can_fix
            }
        return stringResource(res, cleanName)
    }
    if (actor == null) return null
    val roleSubject =
        stringResource(
            when (actor) {
                ComputerReadinessActor.ComputerOwner -> Res.string.settings_computers_readiness_actor_role_computer_owner
                ComputerReadinessActor.AgentOwner -> Res.string.settings_computers_readiness_actor_role_agent_owner
                ComputerReadinessActor.ServerAdmin -> Res.string.settings_computers_readiness_actor_role_server_admin
            },
        )
    val template =
        when (action) {
            ComputerReadinessAction.SignIn -> Res.string.settings_computers_readiness_owner_can_sign_in
            ComputerReadinessAction.VerifyAccount -> Res.string.settings_computers_readiness_owner_can_verify_account
            ComputerReadinessAction.UpdateComputer -> Res.string.settings_computers_readiness_owner_can_update_computer
            ComputerReadinessAction.ChangeModel -> Res.string.settings_computers_readiness_owner_can_change_model
            ComputerReadinessAction.Fix,
            null -> Res.string.settings_computers_readiness_owner_can_fix
        }
    return stringResource(template, roleSubject)
}

@Composable
internal fun raftFormatWaitBackIn(reset: RaftRackReset?): String? {
    if (reset == null) return null
    return when (reset.unit) {
        RaftRelativeTimeUnit.Minute -> stringResource(Res.string.settings_computers_readiness_attention_back_in_minutes, reset.count)
        RaftRelativeTimeUnit.Hour -> stringResource(Res.string.settings_computers_readiness_attention_back_in_hours, reset.count)
        RaftRelativeTimeUnit.Day -> stringResource(Res.string.settings_computers_readiness_attention_back_in_days, reset.count)
    }
}

/** Web `formatReadiness` (`readinessCopy.ts`). */
@Composable
internal fun raftFormatReadiness(
    readiness: ComputerReadinessEntry,
    runtimeId: String? = null,
    runtimeCatalog: ServerRuntimeCatalog = BundledRuntimeCatalog,
    onAgent: Boolean = false,
    nowMillis: Long = 0L,
): RaftReadinessCopy {
    val primary = readiness.primary
    val readinessActor = readiness.actor
    val readinessActorName = readiness.actorName
    val readinessAction = readiness.action
    val who =
        if (readiness.state == ComputerReadinessState.Blocked) {
            raftReadinessWhoOf(readinessActor, readinessActorName, readinessAction)
        } else {
            null
        }
    val named = who?.let { stringResource(Res.string.settings_computers_readiness_sentence, it) }
    if (primary != null && primary.code in COMPUTER_READINESS_LIMIT_WAIT_CODES) {
        val reset = raftWaitBackInReset(primary.untilMs, nowMillis)
        val title =
            if (reset != null) {
                when (reset.unit) {
                    RaftRelativeTimeUnit.Minute -> stringResource(Res.string.settings_computers_rack_resets_in_minutes, reset.count)
                    RaftRelativeTimeUnit.Hour -> stringResource(Res.string.settings_computers_rack_resets_in_hours, reset.count)
                    RaftRelativeTimeUnit.Day -> stringResource(Res.string.settings_computers_rack_resets_in_days, reset.count)
                }
            } else {
                stringResource(Res.string.settings_computers_readiness_generic_title)
            }
        return RaftReadinessCopy(
            title = title,
            scope = null,
            detail = stringResource(Res.string.settings_computers_readiness_generic_detail),
            actor = null,
            link = null,
        )
    }
    if (primary == null || primary.code !in COMPUTER_READINESS_KNOWN_CODES) {
        return RaftReadinessCopy(
            title = stringResource(Res.string.settings_computers_readiness_generic_title),
            scope = null,
            detail = stringResource(Res.string.settings_computers_readiness_generic_detail),
            actor = named,
            link = null,
        )
    }
    val runtime = raftReadinessRuntimeDisplayName(primary.resource, runtimeId, runtimeCatalog)
    val scope =
        when (readiness.scope) {
            ComputerReadinessScope.Computer -> stringResource(Res.string.settings_computers_readiness_scope_computer)
            ComputerReadinessScope.Agent -> stringResource(Res.string.settings_computers_readiness_scope_agent)
            ComputerReadinessScope.Server -> stringResource(Res.string.settings_computers_readiness_scope_server)
            null -> null
        }
    val actor =
        when (readinessActor) {
            ComputerReadinessActor.ComputerOwner -> stringResource(Res.string.settings_computers_readiness_actor_computer_owner)
            ComputerReadinessActor.AgentOwner -> stringResource(Res.string.settings_computers_readiness_actor_agent_owner)
            ComputerReadinessActor.ServerAdmin -> stringResource(Res.string.settings_computers_readiness_actor_server_admin)
            null -> null
        }
    return when (primary.code) {
        "verification_required" ->
            RaftReadinessCopy(
                title = stringResource(Res.string.settings_computers_readiness_verification_required_title),
                scope = scope,
                detail = stringResource(Res.string.settings_computers_readiness_verification_required_detail, runtime),
                actor = actor,
                link =
                    RaftReadinessLink(
                        href = ANTHROPIC_VERIFICATION_PORTAL_URL,
                        text = stringResource(Res.string.settings_computers_readiness_verification_required_link),
                    ),
            )
        "computer_offline" ->
            RaftReadinessCopy(
                title = stringResource(Res.string.settings_computers_readiness_computer_offline_title),
                scope = scope,
                detail = stringResource(Res.string.settings_computers_readiness_computer_offline_detail),
                actor = actor,
                link = null,
            )
        "computer_reconnecting" ->
            RaftReadinessCopy(
                title = stringResource(Res.string.settings_computers_readiness_computer_reconnecting_title),
                scope = scope,
                detail = stringResource(Res.string.settings_computers_readiness_computer_reconnecting_detail),
                actor = null,
                link = null,
            )
        "computer_restarting" ->
            RaftReadinessCopy(
                title =
                    stringResource(
                        if (onAgent) {
                            Res.string.settings_computers_readiness_computer_restarting_agent_title
                        } else {
                            Res.string.settings_computers_readiness_computer_restarting_title
                        },
                    ),
                scope = scope,
                detail = stringResource(Res.string.settings_computers_readiness_computer_restarting_detail),
                actor = null,
                link = null,
            )
        "computer_updating" ->
            RaftReadinessCopy(
                title =
                    stringResource(
                        if (onAgent) {
                            Res.string.settings_computers_readiness_computer_updating_agent_title
                        } else {
                            Res.string.settings_computers_readiness_computer_updating_title
                        },
                    ),
                scope = scope,
                detail = stringResource(Res.string.settings_computers_readiness_computer_updating_detail),
                actor = null,
                link = null,
            )
        "computer_too_old" -> {
            val desktop = primary.managedByDesktop == true
            RaftReadinessCopy(
                title = stringResource(Res.string.settings_computers_readiness_computer_too_old_title),
                scope = scope,
                detail =
                    stringResource(
                        if (desktop) {
                            Res.string.settings_computers_readiness_computer_too_old_desktop_detail
                        } else {
                            Res.string.settings_computers_readiness_computer_too_old_detail
                        },
                    ),
                actor = actor,
                link = null,
            )
        }
        "runtime_missing" ->
            RaftReadinessCopy(
                title = stringResource(Res.string.settings_computers_readiness_runtime_missing_title, runtime),
                scope = scope,
                detail = stringResource(Res.string.settings_computers_readiness_runtime_missing_detail, runtime),
                actor = actor,
                link = null,
            )
        "model_unavailable" -> {
            val model =
                primary.model?.trim()?.takeIf { it.isNotEmpty() }
                    ?: (primary.resource as? ComputerReadinessResource.Model)?.model?.trim()?.takeIf { it.isNotEmpty() }
                    ?: ""
            RaftReadinessCopy(
                title = stringResource(Res.string.settings_computers_readiness_model_unavailable_title),
                scope = scope,
                detail = stringResource(Res.string.settings_computers_readiness_model_unavailable_detail, runtime, model),
                actor = actor,
                link = null,
            )
        }
        else ->
            RaftReadinessCopy(
                title = stringResource(Res.string.settings_computers_readiness_generic_title),
                scope = null,
                detail = stringResource(Res.string.settings_computers_readiness_generic_detail),
                actor = named,
                link = null,
            )
    }
}

@Composable
internal fun raftReadinessHeading(copy: RaftReadinessCopy): String {
    val scope = copy.scope
    return if (scope != null) {
        stringResource(Res.string.settings_computers_readiness_scoped, scope, copy.title)
    } else {
        copy.title
    }
}

@Composable
internal fun raftReadinessBody(copy: RaftReadinessCopy): String {
    val actor = copy.actor
    return if (actor != null) {
        stringResource(Res.string.settings_computers_readiness_detail_with_actor, copy.detail, actor)
    } else {
        copy.detail
    }
}

/** Web `formatAttentionItem` (`readinessCopy.ts`). */
@Composable
internal fun raftFormatAttentionItem(
    item: ComputerReadinessAttentionItem,
    runtimeCatalog: ServerRuntimeCatalog = BundledRuntimeCatalog,
    nowMillis: Long = 0L,
): RaftAttentionItemCopy {
    val who = raftReadinessWhoOf(item.actor, item.actorName, item.action)
    val code = item.code
    val resource = item.resource
    if (code == COMPUTER_READINESS_ATTENTION_UPDATE_AVAILABLE) {
        return RaftAttentionItemCopy(
            title = stringResource(Res.string.settings_computers_readiness_attention_update_available),
            who = who,
        )
    }
    if (code == COMPUTER_READINESS_ATTENTION_UPDATE_REQUIRED) {
        return RaftAttentionItemCopy(
            title = stringResource(Res.string.settings_computers_readiness_attention_update_required),
            who = who,
        )
    }
    if (code == null || resource == null || code !in COMPUTER_READINESS_KNOWN_CODES) {
        return RaftAttentionItemCopy(
            title = stringResource(Res.string.settings_computers_readiness_attention_item),
            who = who,
        )
    }
    val synthetic =
        ComputerReadinessEntry(
            state = ComputerReadinessState.Blocked,
            primary =
                ComputerReadinessFact(
                    resource = resource,
                    code = code,
                    behaviour = ComputerReadinessBehaviour.Blocked,
                    sinceMs = 1L,
                ),
            scope = null,
            actor = item.actor,
        )
    val baseTitle = raftFormatReadiness(synthetic, runtimeId = null, runtimeCatalog = runtimeCatalog, onAgent = false, nowMillis = nowMillis).title
    val onSharedLogin =
        (resource is ComputerReadinessResource.Login && resource.key.startsWith("shared:")) ||
            (resource is ComputerReadinessResource.LoginWindow && resource.key.startsWith("shared:")) ||
            (resource is ComputerReadinessResource.Model && resource.key.startsWith("shared:"))
    val title =
        if (onSharedLogin) {
            val runtimeName = raftReadinessRuntimeDisplayName(resource, fallbackRuntimeId = null, runtimeCatalog = runtimeCatalog)
            stringResource(Res.string.settings_computers_readiness_attention_on_runtime, runtimeName, baseTitle)
        } else {
            baseTitle
        }
    return RaftAttentionItemCopy(title = title, who = who)
}

/** Web `attentionItemLines` (`readinessCopy.ts`): each item as `"{title} · {who}"` (or `"{title}"`). */
@Composable
internal fun raftAttentionItemLines(
    attention: ComputerReadinessAttention?,
    runtimeCatalog: ServerRuntimeCatalog = BundledRuntimeCatalog,
    nowMillis: Long = 0L,
): List<String> {
    val items = attention?.items ?: return emptyList()
    return items.map { item ->
        val formatted = raftFormatAttentionItem(item, runtimeCatalog, nowMillis)
        val who = formatted.who
        if (who != null) {
            stringResource(Res.string.settings_computers_readiness_attention_item_with_who, formatted.title, who)
        } else {
            formatted.title
        }
    }
}

/**
 * Web `ComputersAttentionSummary` (`features/computer/ComputersAttentionSummary.tsx`):
 * top needs-attention summary strip above the Computers list/cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RaftComputersAttentionSummary(
    summary: RaftComputersAttentionSummaryModel,
    runtimeCatalog: ServerRuntimeCatalog,
    nowMillis: Long,
    onFilterAttention: () -> Unit,
    onOpenComputer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val hairline = colors.lineHairline
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.layerCanvas)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(hairline, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
            }
            .raftSafeAreaHorizontalPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(COMPUTERS_ATTENTION_SUMMARY_TAG),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val headline = summary.headline
        if (headline != null) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag(COMPUTERS_ATTENTION_LINE_TAG),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                when (headline) {
                    is RaftComputersAttentionHeadline.SingleItem -> {
                        val item = headline.item
                        val formatted = raftFormatAttentionItem(item, runtimeCatalog, nowMillis)
                        val itemCode = item.code
                        RaftTextLink(
                            text =
                                stringResource(
                                    Res.string.settings_computers_readiness_attention_on_computer,
                                    formatted.title,
                                    headline.machineName,
                                ),
                            onClick = onFilterAttention,
                            modifier = Modifier.testTag(COMPUTERS_ATTENTION_FILTER_TAG),
                            variant = RaftTextLinkVariant.Muted,
                            style = typography.callout,
                        )
                        if (item.agentCount > 0) {
                            RaftAttentionSummaryDot()
                            val agentsText =
                                if (itemCode != null) {
                                    if (item.agentCount == 1) {
                                        stringResource(Res.string.settings_computers_rack_agent_count_one, item.agentCount)
                                    } else {
                                        stringResource(Res.string.settings_computers_rack_agent_count_other, item.agentCount)
                                    }
                                } else {
                                    if (item.agentCount == 1) {
                                        stringResource(Res.string.settings_computers_readiness_attention_agents_cant_run_one, item.agentCount)
                                    } else {
                                        stringResource(Res.string.settings_computers_readiness_attention_agents_cant_run_other, item.agentCount)
                                    }
                                }
                            RaftText(agentsText, style = typography.callout, color = colors.foregroundMuted)
                        }
                        if (itemCode != null) {
                            RaftAttentionSummaryDot()
                            RaftTextLink(
                                text = raftReadinessDoLabel(item.action),
                                onClick = { onOpenComputer(headline.machineId) },
                                modifier = Modifier.testTag(COMPUTERS_ATTENTION_ACTION_TAG),
                                variant = RaftTextLinkVariant.Muted,
                                style = typography.callout,
                            )
                        } else {
                            val who = formatted.who
                            if (who != null) {
                                RaftAttentionSummaryDot()
                                RaftText(who, style = typography.callout, color = colors.foregroundMuted)
                            }
                        }
                    }
                    is RaftComputersAttentionHeadline.MultipleComputers -> {
                        val countText =
                            if (headline.count == 1) {
                                stringResource(Res.string.settings_computers_readiness_attention_computers_one, headline.count)
                            } else {
                                stringResource(Res.string.settings_computers_readiness_attention_computers_other, headline.count)
                            }
                        RaftTextLink(
                            text = countText,
                            onClick = onFilterAttention,
                            modifier = Modifier.testTag(COMPUTERS_ATTENTION_FILTER_TAG),
                            variant = RaftTextLinkVariant.Muted,
                            style = typography.callout,
                        )
                    }
                }
            }
        }
        if (summary.waitingAgents > 0) {
            val waitingText =
                if (summary.waitingAgents == 1) {
                    stringResource(Res.string.settings_computers_readiness_attention_waiting_one, summary.waitingAgents)
                } else {
                    stringResource(Res.string.settings_computers_readiness_attention_waiting_other, summary.waitingAgents)
                }
            val backIn = raftFormatWaitBackIn(summary.waitingReset)
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag(COMPUTERS_WAITING_LINE_TAG),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(waitingText, style = typography.callout, color = colors.foregroundMuted)
                if (backIn != null) {
                    RaftAttentionSummaryDot()
                    RaftText(backIn, style = typography.callout, color = colors.foregroundMuted)
                }
            }
        }
    }
}

@Composable
private fun RaftAttentionSummaryDot() {
    RaftText(
        "·",
        modifier = Modifier.clearAndSetSemantics {},
        style = RaftTheme.typography.callout,
        color = RaftTheme.colors.foregroundMuted,
    )
}

/**
 * Web `RuntimeReadinessNotice` (`RuntimeUsageSurface.tsx:112-133`): leads the runtime usage card
 * or sheet when `readiness.runtimes[runtimeId]` holds (`blocked` / `waiting`), including the fixed
 * Anthropic Verification Portal link for `verification_required`.
 */
@Composable
internal fun RaftRuntimeReadinessNotice(
    readiness: ComputerReadinessEntry?,
    runtimeId: String,
    runtimeCatalog: ServerRuntimeCatalog,
    nowMillis: Long,
    inset: Dp,
    onOpenUrl: (String) -> Unit,
) {
    if (readiness == null || !readiness.holds()) return
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val copy = raftFormatReadiness(readiness, runtimeId = runtimeId, runtimeCatalog = runtimeCatalog, onAgent = false, nowMillis = nowMillis)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = inset, vertical = 12.dp)
                    .testTag(RUNTIME_READINESS_NOTICE_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 2.dp)) {
                CompositionLocalProvider(LocalRaftContentColor provides colors.warningStrong) {
                    RaftComputersIcon(RaftLucideIconName.TriangleAlert, size = 16.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    raftReadinessHeading(copy),
                    style = typography.callout.copy(fontWeight = FontWeight.Medium),
                    color = colors.foreground,
                )
                RaftText(
                    raftReadinessBody(copy),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                )
                val link = copy.link
                if (link != null) {
                    RaftTextLink(
                        text = link.text,
                        onClick = { onOpenUrl(link.href) },
                        modifier = Modifier.testTag(RUNTIME_READINESS_LINK_TAG),
                        variant = RaftTextLinkVariant.Strong,
                        style = typography.callout,
                        leavesRaft = true,
                    )
                }
            }
        }
        RaftSeparator()
    }
}
