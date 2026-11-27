package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.badgeVariant
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.defaultNowMillis
import build.raft.app.localized
import build.raft.app.members.localized
import build.raft.app.model.AGENT_READINESS_KNOWN_CODES
import build.raft.app.model.AGENT_READINESS_LIMIT_WAIT_CODES
import build.raft.app.model.ANTHROPIC_VERIFICATION_PORTAL_URL
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentReadinessLight
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.agentReadinessResetCountdown
import build.raft.app.model.holds
import build.raft.app.model.isModelUnavailableAdvisory
import build.raft.app.model.light
import build.raft.app.model.resolvedModel
import build.raft.app.model.resolvedRuntimeId
import build.raft.app.resources.*
import build.raft.app.thread.badgeVariant
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import org.jetbrains.compose.resources.StringResource

internal object AgentReadinessTestTags {
    const val ProfileChip = "agent-status-chip"
    const val ProfileDetail = "agent-readiness-detail"
    const val ProfileLink = "agent-readiness-link"
    const val ProfileAdvisory = "agent-readiness-advisory"
    const val DmHeaderStatus = "agent_dm_status_readiness"
    const val MemberRowStatus = "member_row_readiness"
    const val SidebarShort = "sidebar_agent_readiness"
}

/**
 * Resolves whether [readiness] should override ordinary activity on agent status surfaces
 * (web `AgentStatusBadge.tsx` `readinessHolds(readiness) && !displayState.blocked`).
 * External agents, paused personal assistants, App Bots, and `blockedReason` agents keep their
 * dedicated status paths.
 */
internal fun effectiveHoldingReadiness(
    readiness: AgentReadiness?,
    blocked: AgentBlockedState?,
    external: Boolean,
    paused: Boolean,
    appBot: Boolean = false,
): AgentReadiness? =
    readiness?.takeIf { !appBot && !external && !paused && blocked == null && it.holds() }

internal fun effectiveHoldingReadiness(
    state: RaftAgentStatusIndicatorState?,
    appBot: Boolean = false,
): AgentReadiness? =
    effectiveHoldingReadiness(
        readiness = state?.readiness,
        blocked = state?.blocked,
        external = state?.external == true,
        paused = state?.paused == true,
        appBot = appBot,
    )

internal fun AgentReadinessLight.dotColor(): Color =
    when (this) {
        AgentReadinessLight.Error -> RaftColor.AccentOrange
        AgentReadinessLight.Busy -> RaftColor.BrandYellow
        AgentReadinessLight.Offline -> RaftColor.PresenceOffline
    }

internal fun AgentReadiness.dotColor(): Color = light().dotColor()

internal fun AgentReadinessLight.badgeVariant(): RaftBadgeVariant =
    when (this) {
        AgentReadinessLight.Error -> RaftBadgeVariant.Warning
        AgentReadinessLight.Busy -> RaftBadgeVariant.Busy
        AgentReadinessLight.Offline -> RaftBadgeVariant.Muted
    }

/** Pure, resource-backed specification for [formatReadiness] (testable outside Compose). */
internal data class AgentReadinessTextSpec(
    val res: StringResource,
    val stringArg: String? = null,
    val stringArg2: String? = null,
    val intArg: Int? = null,
)

internal data class AgentReadinessCopySpec(
    val light: AgentReadinessLight,
    val title: AgentReadinessTextSpec,
    val scope: StringResource? = null,
    val detail: AgentReadinessTextSpec,
    val actor: AgentReadinessTextSpec? = null,
    val wrapActorInSentence: Boolean = false,
    val verificationPortalUrl: String? = null,
    val offersRetry: Boolean = false,
    val offersChangeModel: Boolean = false,
    val code: String? = null,
)

internal data class AgentReadinessDisplayCopy(
    val light: AgentReadinessLight,
    val title: String,
    val detail: String,
    val body: String,
    val fullDetail: String,
    val verificationPortalUrl: String?,
    val offersRetry: Boolean,
    val offersChangeModel: Boolean = false,
    val code: String?,
)

private fun normalizedReadinessAction(action: String?): String =
    when (action?.trim()?.lowercase()) {
        "sign_in", "verify_account", "update_computer", "change_model", "fix" -> action.trim().lowercase()
        else -> "fix"
    }

private fun readinessWhoSpec(readiness: AgentReadiness): AgentReadinessTextSpec? {
    val action = normalizedReadinessAction(readiness.action)
    val actorName = readiness.actorName?.trim()?.takeIf { it.isNotEmpty() }
    if (actorName != null) {
        val res =
            when (action) {
                "sign_in" -> Res.string.agent_readiness_can_sign_in
                "verify_account" -> Res.string.agent_readiness_can_verify_account
                "update_computer" -> Res.string.agent_readiness_can_update_computer
                "change_model" -> Res.string.agent_readiness_can_change_model
                else -> Res.string.agent_readiness_can_fix
            }
        return AgentReadinessTextSpec(res = res, stringArg = actorName)
    }
    val actor = readiness.actor?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val res =
        when (action) {
            "sign_in" ->
                when (actor) {
                    "computer_owner" -> Res.string.agent_readiness_owner_can_sign_in_computer_owner
                    "agent_owner" -> Res.string.agent_readiness_owner_can_sign_in_agent_owner
                    else -> Res.string.agent_readiness_owner_can_sign_in_server_admin
                }
            "verify_account" ->
                when (actor) {
                    "computer_owner" -> Res.string.agent_readiness_owner_can_verify_account_computer_owner
                    "agent_owner" -> Res.string.agent_readiness_owner_can_verify_account_agent_owner
                    else -> Res.string.agent_readiness_owner_can_verify_account_server_admin
                }
            "update_computer" ->
                when (actor) {
                    "computer_owner" -> Res.string.agent_readiness_owner_can_update_computer_computer_owner
                    "agent_owner" -> Res.string.agent_readiness_owner_can_update_computer_agent_owner
                    else -> Res.string.agent_readiness_owner_can_update_computer_server_admin
                }
            "change_model" ->
                when (actor) {
                    "computer_owner" -> Res.string.agent_readiness_owner_can_change_model_computer_owner
                    "agent_owner" -> Res.string.agent_readiness_owner_can_change_model_agent_owner
                    else -> Res.string.agent_readiness_owner_can_change_model_server_admin
                }
            else ->
                when (actor) {
                    "computer_owner" -> Res.string.agent_readiness_owner_can_fix_computer_owner
                    "agent_owner" -> Res.string.agent_readiness_owner_can_fix_agent_owner
                    else -> Res.string.agent_readiness_owner_can_fix_server_admin
                }
        }
    return AgentReadinessTextSpec(res = res)
}

/**
 * Pure Kotlin parity with `botiverse/slock` `packages/web/src/utils/readinessCopy.ts`
 * `formatReadiness(..., onAgent = true)`.
 *
 * For provider limit waits (`quota_exhausted`, `rate_limited`), shows the reset countdown when
 * [AgentReadinessFact.until] is in the future ("Back in 3h") or the generic title otherwise, paired
 * with the generic detail ("This agent can't reply right now. Its messages are kept and will be
 * delivered.") so runtime-wide holds never falsely blame this agent's model quota.
 */
internal fun resolveAgentReadinessCopySpec(
    readiness: AgentReadiness,
    fallbackRuntimeId: String?,
    nowEpochMillis: Long,
): AgentReadinessCopySpec {
    val primary = readiness.primary
    val light = readiness.light()
    if (primary != null && primary.code in AGENT_READINESS_LIMIT_WAIT_CODES) {
        val reset = agentReadinessResetCountdown(primary.until, nowEpochMillis)
        val title =
            if (reset != null) {
                val res =
                    when (reset.unit) {
                        RaftRelativeTimeUnit.Minute -> Res.string.settings_computers_rack_resets_in_minutes
                        RaftRelativeTimeUnit.Hour -> Res.string.settings_computers_rack_resets_in_hours
                        RaftRelativeTimeUnit.Day -> Res.string.settings_computers_rack_resets_in_days
                    }
                AgentReadinessTextSpec(res = res, intArg = reset.count)
            } else {
                AgentReadinessTextSpec(res = Res.string.agent_readiness_generic_title)
            }
        return AgentReadinessCopySpec(
            light = light,
            title = title,
            scope = null,
            detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_generic_detail),
            actor = null,
            code = primary.code,
        )
    }
    if (primary == null || primary.code !in AGENT_READINESS_KNOWN_CODES) {
        val who = if (readiness.state == "blocked") readinessWhoSpec(readiness) else null
        return AgentReadinessCopySpec(
            light = light,
            title = AgentReadinessTextSpec(res = Res.string.agent_readiness_generic_title),
            scope = null,
            detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_generic_detail),
            actor = who,
            wrapActorInSentence = who != null,
            code = primary?.code,
        )
    }
    val runtimeName = readiness.resolvedRuntimeId(fallbackRuntimeId)?.let(::raftAgentRuntimeDisplayName).orEmpty()
    val scope =
        when (readiness.scope) {
            "computer" -> Res.string.agent_readiness_scope_computer
            "agent" -> Res.string.agent_readiness_scope_agent
            "server" -> Res.string.agent_readiness_scope_server
            else -> null
        }
    val actorRes =
        when (readiness.actor) {
            "computer_owner" -> Res.string.agent_readiness_actor_computer_owner
            "agent_owner" -> Res.string.agent_readiness_actor_agent_owner
            "server_admin" -> Res.string.agent_readiness_actor_server_admin
            else -> null
        }
    val actor = actorRes?.let { AgentReadinessTextSpec(res = it) }
    return when (primary.code) {
        "verification_required" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_verification_required_title),
                scope = scope,
                detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_verification_required_detail, stringArg = runtimeName),
                actor = actor,
                verificationPortalUrl = ANTHROPIC_VERIFICATION_PORTAL_URL,
                offersRetry = true,
                code = primary.code,
            )
        "computer_offline" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_offline_title),
                scope = scope,
                detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_offline_detail),
                actor = actor,
                code = primary.code,
            )
        "computer_reconnecting" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_reconnecting_title),
                scope = scope,
                detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_reconnecting_detail),
                actor = null,
                code = primary.code,
            )
        "computer_restarting" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_restarting_agent_title),
                scope = scope,
                detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_restarting_detail),
                actor = null,
                code = primary.code,
            )
        "computer_updating" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_updating_agent_title),
                scope = scope,
                detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_updating_detail),
                actor = null,
                code = primary.code,
            )
        "computer_too_old" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_computer_too_old_title),
                scope = scope,
                detail =
                    AgentReadinessTextSpec(
                        res =
                            if (primary.managedByDesktop == true) {
                                Res.string.agent_readiness_computer_too_old_desktop_detail
                            } else {
                                Res.string.agent_readiness_computer_too_old_detail
                            },
                    ),
                actor = actor,
                code = primary.code,
            )
        "runtime_missing" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_runtime_missing_title, stringArg = runtimeName),
                scope = scope,
                detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_runtime_missing_detail, stringArg = runtimeName),
                actor = actor,
                code = primary.code,
            )
        "model_unavailable" ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_model_unavailable_title),
                scope = scope,
                detail =
                    AgentReadinessTextSpec(
                        res = Res.string.agent_readiness_model_unavailable_detail,
                        stringArg = runtimeName,
                        stringArg2 = primary.resolvedModel().orEmpty(),
                    ),
                actor = actor,
                offersChangeModel = true,
                code = primary.code,
            )
        else ->
            AgentReadinessCopySpec(
                light = light,
                title = AgentReadinessTextSpec(res = Res.string.agent_readiness_generic_title),
                scope = null,
                detail = AgentReadinessTextSpec(res = Res.string.agent_readiness_generic_detail),
                actor = null,
                code = primary.code,
            )
    }
}

@Composable
private fun AgentReadinessTextSpec.localized(): String =
    when {
        intArg != null -> stringResource(res, intArg)
        stringArg != null && stringArg2 != null -> stringResource(res, stringArg, stringArg2)
        stringArg != null -> stringResource(res, stringArg)
        else -> stringResource(res)
    }

@Composable
internal fun formatAgentReadinessDisplayCopy(
    readiness: AgentReadiness,
    fallbackRuntimeId: String?,
    timeContext: RaftTimeContext? = null,
): AgentReadinessDisplayCopy {
    build.raft.app.data.RaftRuntimeCatalogNames.observeCatalogs()
    val nowMillis =
        currentRaftTimeContextProvider?.current?.nowEpochMillis
            ?: timeContext?.nowEpochMillis
            ?: defaultNowMillis()
    val spec = resolveAgentReadinessCopySpec(readiness, fallbackRuntimeId, nowMillis)
    val title = spec.title.localized()
    val detail = spec.detail.localized()
    val rawActor = spec.actor?.localized()
    val actor =
        if (rawActor != null && spec.wrapActorInSentence) {
            stringResource(Res.string.agent_readiness_sentence, rawActor)
        } else {
            rawActor
        }
    val body =
        if (actor != null) {
            stringResource(Res.string.agent_readiness_detail_with_actor, detail, actor)
        } else {
            detail
        }
    val scope = spec.scope?.let { stringResource(it) }
    val fullDetail =
        if (scope != null) {
            stringResource(Res.string.agent_readiness_scoped, scope, body)
        } else {
            body
        }
    return AgentReadinessDisplayCopy(
        light = spec.light,
        title = title,
        detail = detail,
        body = body,
        fullDetail = fullDetail,
        verificationPortalUrl = spec.verificationPortalUrl,
        offersRetry = spec.offersRetry,
        offersChangeModel = spec.offersChangeModel,
        code = spec.code,
    )
}

@Composable
internal fun rememberAgentReadinessDisplayCopy(
    readiness: AgentReadiness?,
    fallbackRuntimeId: String?,
    timeContext: RaftTimeContext? = null,
): AgentReadinessDisplayCopy? {
    if (readiness == null || !readiness.holds()) return null
    return formatAgentReadinessDisplayCopy(
        readiness = readiness,
        fallbackRuntimeId = fallbackRuntimeId,
        timeContext = timeContext,
    )
}

@Composable
internal fun rememberAgentModelUnavailableAdvisoryCopy(
    readiness: AgentReadiness?,
    fallbackRuntimeId: String?,
    timeContext: RaftTimeContext? = null,
): AgentReadinessDisplayCopy? {
    if (!readiness.isModelUnavailableAdvisory()) return null
    return formatAgentReadinessDisplayCopy(
        readiness = readiness!!,
        fallbackRuntimeId = fallbackRuntimeId,
        timeContext = timeContext,
    )
}

@Composable
internal fun rememberAgentReadinessDisplayCopy(
    state: RaftAgentStatusIndicatorState?,
    fallbackRuntimeId: String? = null,
    appBot: Boolean = false,
    timeContext: RaftTimeContext? = null,
): AgentReadinessDisplayCopy? {
    val readiness = effectiveHoldingReadiness(state, appBot = appBot) ?: return null
    return rememberAgentReadinessDisplayCopy(
        readiness = readiness,
        fallbackRuntimeId = state?.runtime ?: fallbackRuntimeId,
        timeContext = timeContext,
    )
}

@Composable
internal fun AgentReadinessVerificationLink(
    onOpenUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftTextLink(
        text = stringResource(Res.string.agent_readiness_verification_required_link),
        onClick = onOpenUrl,
        variant = RaftTextLinkVariant.Strong,
        style = RaftTheme.typography.footnote,
        leavesRaft = true,
        modifier = modifier.testTag(AgentReadinessTestTags.ProfileLink),
    )
}

@Composable
internal fun AgentReadinessStatusNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftText(
            text = text,
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundMuted,
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .testTag(AgentReadinessTestTags.ProfileDetail)
                    .semantics { contentDescription = text },
        )
    }
}

/**
 * Web `AgentStatusBadge` `agent-readiness-advisory` (botiverse/slock #17127):
 * while a warm session is still live on a removed model, the chip stays green/working with this
 * owner-only note and Change model link below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentReadinessAdvisoryNotice(
    text: String,
    onChangeModel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().testTag(AgentReadinessTestTags.ProfileAdvisory),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            text = text,
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundMuted,
            modifier = Modifier.semantics { contentDescription = text },
        )
        if (onChangeModel != null) {
            RaftTextLink(
                text = stringResource(Res.string.agent_status_change_model),
                onClick = onChangeModel,
                variant = RaftTextLinkVariant.Strong,
                style = RaftTheme.typography.footnote,
                modifier = Modifier.testTag(AgentSpawnFailureStatusTestTags.ChangeModel),
            )
        }
    }
}
