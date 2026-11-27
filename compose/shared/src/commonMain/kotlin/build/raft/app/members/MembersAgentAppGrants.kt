package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentAppGrantRevokeFailure
import build.raft.app.data.AgentAppGrantsRevoke
import build.raft.app.data.AgentAppGrantsRevokeIntent
import build.raft.app.data.AgentAppLoginItem
import build.raft.app.data.isActiveGrant
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.app.shell.rememberRaftLinkOpensInRaft
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * The agent's Apps tab, one row per App (web AgentAppGrantsRow + RevokeAppGrantsDialog +
 * AppGrantAbilities, agentAppGrants.ts; botiverse/slock 31abbdefb #13293, with #14769's sentence,
 * on staging 5828cdce3e). An App can hold several grants for an agent (Stamp: the agent's sign-in,
 * and notifications); it is one row, saying in one plain line what it can do across them. Revoke
 * access takes every grant of it after a confirmation listing each one; with more than one grant,
 * Details opens them under the row grouped by how access was granted, each with its own Revoke.
 * The Revokes are for someone who may manage the agent's access; everyone else reads the rows.
 */

internal const val MembersAgentAppAbilitiesTag = "agent-app-abilities"
internal const val MembersAgentAppSourceTag = "agent-app-source"
internal const val MembersAgentAppRevokeConfirmTag = "agent-app-revoke-confirm"
internal const val MembersAgentAppRevokePartialFailureTag = "agent-app-revoke-partial-failure"
internal const val MembersAgentAppRevokeReasonTag = "agent-app-revoke-reason"

internal fun membersAgentAppRowTag(clientId: String) = "agent-app-row-$clientId"

internal fun membersAgentAppRevokeTag(clientId: String) = "agent-app-revoke-$clientId"

internal fun membersAgentAppDetailsTag(clientId: String) = "agent-app-details-$clientId"

internal fun membersAgentAppGrantDetailsTag(clientId: String) = "agent-app-grant-details-$clientId"

internal fun membersAgentAppGrantTag(grantId: String) = "agent-app-grant-$grantId"

internal fun membersAgentAppRevokeGrantTag(grantId: String) = "agent-app-revoke-grant-$grantId"

internal fun membersAgentAppRevokeItemTag(grantId: String) = "agent-app-revoke-item-$grantId"

/** One App the agent has, with every live grant it holds (web `AgentAppGrantGroup`). */
internal data class MembersAgentAppGrantGroup(
    val clientId: String,
    val name: String,
    val description: String?,
    val homepageUrl: String?,
    /** The earliest grant's time: when the App first got access (null when no grant says). */
    val since: String?,
    val grants: List<AgentAppLoginItem>,
)

/** Web `groupGrantsByApp`: the live grants grouped by App (`clientId`), Apps and grants in the order the server lists them. */
internal fun membersAgentAppGrantGroups(items: List<AgentAppLoginItem>): List<MembersAgentAppGrantGroup> {
    val groups = LinkedHashMap<String, MembersAgentAppGrantGroup>()
    for (item in items) {
        if (!item.isActiveGrant) continue
        val group = groups[item.clientId]
        groups[item.clientId] =
            if (group == null) {
                MembersAgentAppGrantGroup(
                    clientId = item.clientId,
                    name = item.clientName,
                    description = item.clientDescription,
                    homepageUrl = item.clientHomepageUrl,
                    since = item.createdAt,
                    grants = listOf(item),
                )
            } else {
                group.copy(grants = group.grants + item, since = earliestGrantTime(group.since, item.createdAt))
            }
    }
    return groups.values.toList()
}

/** ISO timestamps compare as text, as the web's `item.createdAt < group.since` does. */
private fun earliestGrantTime(a: String?, b: String?): String? =
    when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

/** Web AgentAppGrantsRow `source`: the row says how access was granted only when every grant came the same way. */
internal fun MembersAgentAppGrantGroup.onlyGrantSource(): String? = grants.map { it.grantSource }.distinct().singleOrNull()

/** Web `grantsBySource`: the grants by how access was granted (null: not recorded), sources in first-seen order. */
internal fun membersAgentAppGrantsBySource(grants: List<AgentAppLoginItem>): List<Pair<String?, List<AgentAppLoginItem>>> =
    grants.groupBy { it.grantSource }.toList()

/** Web `grantSourceLabelId`: a grant older than the field has none recorded ("Granted earlier"); a source this build doesn't know shows as itself. */
@Composable
private fun membersAgentAppGrantSourceTitle(source: String?): String =
    when (source) {
        null -> stringResource(Res.string.member_app_grant_source_unknown)
        else -> membersAgentAppGrantSourceLabel(source)?.let { stringResource(it) } ?: source
    }

/**
 * One App on the Apps tab (web AgentAppGrantsRow): its name, since when, how it got access (one
 * way for all its grants), status, description, and one plain line of what it can do across its
 * grants. [canManage] adds Revoke access (every grant, [onRevoke] with `whole`); more than one
 * grant adds Details, which lists them grouped by source, each with its own Revoke for [canManage].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MembersAgentAppGrantsCard(
    group: MembersAgentAppGrantGroup,
    canManage: Boolean,
    timeContext: RaftTimeContext,
    onOpenUrl: (String) -> Unit,
    onRevoke: (whole: Boolean, grantIds: List<String>) -> Unit,
) {
    var detailsOpen by remember(group.clientId) { mutableStateOf(false) }
    val multiple = group.grants.size > 1
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    // raft-ui Card (default): no shadow, the hairline edge.
    Column(
        modifier = Modifier.fillMaxWidth().membersRaftCard().padding(RaftAppSpacing.Md).testTag(membersAgentAppRowTag(group.clientId)),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    text = group.name,
                    style = typography.cardTitle,
                    color = colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                group.since?.let {
                    RaftText(
                        text = stringResource(
                            Res.string.member_label_connected_since,
                            // A clock alone read as today for a grant from any day (artin 11-06).
                            it.membersReminderShortDateTime(timeContext)
                        ),
                        style = typography.caption,
                        color = colors.foregroundHint
                    )
                }
                // Several sources: Details says it per grant.
                group.onlyGrantSource()?.let(::membersAgentAppGrantSourceLabel)?.let { label ->
                    RaftText(
                        text = stringResource(label),
                        style = typography.caption,
                        color = colors.foregroundHint,
                        modifier = Modifier.testTag(MembersAgentAppSourceTag)
                    )
                }
            }
            RaftBadge(
                text = stringResource(Res.string.member_label_active),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Success
            )
        }
        group.description?.takeIf { it.isNotBlank() }?.let {
            RaftText(text = it, style = typography.callout, color = colors.foregroundMuted)
        }
        // Web (slock #14769): one sentence with the App as its subject ("Stamp can sign in as
        // this agent and send notifications"), not raw scope chips; the manifest isn't on the row.
        membersAgentAppCanSummary(group.name, group.grants.flatMap { it.scopes })?.let { summary ->
            RaftText(
                text = summary,
                style = typography.callout,
                color = colors.foreground,
                modifier = Modifier.testTag(MembersAgentAppAbilitiesTag)
            )
        }
        if (multiple && detailsOpen) {
            MembersAgentAppGrantDetails(group = group, canManage = canManage, timeContext = timeContext, onRevoke = onRevoke)
        }
        if (canManage || multiple) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
            ) {
                if (canManage) {
                    val revokeAccessFor = stringResource(Res.string.member_app_revoke_access_for, group.name)
                    RaftButton(
                        text = stringResource(Res.string.member_app_revoke_access),
                        onClick = { onRevoke(true, group.grants.map { it.id }) },
                        modifier = Modifier
                            .testTag(membersAgentAppRevokeTag(group.clientId))
                            .semantics { contentDescription = revokeAccessFor },
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md
                    )
                }
                if (multiple) {
                    val detailsFor = stringResource(Res.string.member_app_grant_details_for, group.name)
                    RaftButton(
                        text = stringResource(Res.string.member_app_grant_details),
                        onClick = { detailsOpen = !detailsOpen },
                        modifier = Modifier
                            .testTag(membersAgentAppDetailsTag(group.clientId))
                            .semantics {
                                contentDescription = detailsFor
                                if (detailsOpen) collapse { detailsOpen = false; true } else expand { detailsOpen = true; true }
                            },
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Md,
                        trailingIcon = {
                            RaftContentLucideIcon(
                                if (detailsOpen) RaftLucideIconName.ChevronUp else RaftLucideIconName.ChevronDown,
                                size = 16.dp
                            )
                        }
                    )
                }
            }
        }
        group.homepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
            val leavesRaft = !rememberRaftLinkOpensInRaft(homepageUrl)
            RaftButton(
                text = stringResource(Res.string.member_action_view_service),
                onClick = { onOpenUrl(homepageUrl) },
                modifier = Modifier.fillMaxWidth(),
                size = RaftButtonSize.Lg,
                // raft-ui 0.26 (#758) & slock #16676: show ↗ only when the click leaves Raft.
                trailingIcon =
                    if (leavesRaft) {
                        { RaftContentLucideIcon(RaftLucideIconName.ArrowUpRight, size = 18.dp) }
                    } else {
                        null
                    },
            )
        }
    }
}

/** Web AgentAppGrantsRow's details: the App's grants by source, each its abilities, when it was granted and its own Revoke. */
@Composable
private fun MembersAgentAppGrantDetails(
    group: MembersAgentAppGrantGroup,
    canManage: Boolean,
    timeContext: RaftTimeContext,
    onRevoke: (whole: Boolean, grantIds: List<String>) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(membersAgentAppGrantDetailsTag(group.clientId)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        membersAgentAppGrantsBySource(group.grants).forEach { (source, grants) ->
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftText(
                    text = membersAgentAppGrantSourceTitle(source),
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = RaftTheme.colors.foregroundMuted
                )
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    grants.forEachIndexed { index, grant ->
                        // ListRow plain: the hairline from the edge (16dp).
                        if (index > 0) RaftSeparator(Modifier.padding(start = 16.dp))
                        MembersAgentAppGrantDetailRow(
                            grant = grant,
                            appName = group.name,
                            canManage = canManage,
                            timeContext = timeContext,
                            onRevoke = { onRevoke(false, listOf(grant.id)) }
                        )
                    }
                }
            }
        }
    }
}

/** Web GrantDetailRow: what one grant lets the App do, when, and its Revoke (named for the grant and the App). */
@Composable
private fun MembersAgentAppGrantDetailRow(
    grant: AgentAppLoginItem,
    appName: String,
    canManage: Boolean,
    timeContext: RaftTimeContext,
    onRevoke: () -> Unit,
) {
    // ListRow plain, display only (`px-4 py-3`, top-aligned): its Revoke is the target.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(membersAgentAppGrantTag(grant.id)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MembersAgentAppGrantAbilities(grant.scopes)
            grant.createdAt?.let {
                RaftText(
                    text = it.membersReminderShortDateTime(timeContext),
                    style = RaftTheme.typography.monoMeta,
                    color = RaftTheme.colors.foregroundHint
                )
            }
        }
        if (canManage) {
            val grantWords = membersAgentAppGrantWords(grant.scopes)
            val revokeGrantFor = stringResource(Res.string.member_app_revoke_grant_for, grantWords, appName)
            RaftButton(
                text = stringResource(Res.string.member_app_revoke),
                onClick = onRevoke,
                modifier = Modifier
                    .testTag(membersAgentAppRevokeGrantTag(grant.id))
                    .semantics { contentDescription = revokeGrantFor },
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md
            )
        }
    }
}

/** A grant's abilities as one phrase ("Sign in as this agent and Send it notifications"), for its Revoke's name. */
@Composable
private fun membersAgentAppGrantWords(scopes: List<String>): String =
    jointMemberConnectNames(
        membersAgentAppAbilities(scopes).map { item -> item.ability?.let { stringResource(it.long) } ?: item.rawScope.orEmpty() }
    )

/**
 * Web AppGrantAbilities: what one grant lets the App do, each ability in plain words with the raw
 * scopes behind it in small mono text under it; a scope without words shows as itself.
 */
@Composable
internal fun MembersAgentAppGrantAbilities(scopes: List<String>, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        membersAgentAppAbilities(scopes).forEach { item ->
            val ability = item.ability
            if (ability != null) {
                Column {
                    RaftText(text = stringResource(ability.long), style = typography.subhead, color = colors.foreground)
                    RaftText(
                        text = ability.scopes.filter { it in scopes }.joinToString(" / "),
                        style = typography.monoMeta,
                        color = colors.foregroundHint
                    )
                }
            } else {
                RaftText(text = item.rawScope.orEmpty(), style = typography.monoMeta, color = colors.foreground)
            }
        }
    }
}

/**
 * Web RevokeAppGrantsDialog: confirming a Revoke lists every grant it takes, in plain words with the
 * raw scopes in small text, under the red consequence. After a try where some didn't revoke, the
 * list holds only those ([grants], as the list now has them), each marked with why and, for an
 * answer, its trace ID; Try again retries what a retry can help, and when every one was refused
 * the only button is Close. A ConfirmDialog at size sm: the red confirm, Cancel first.
 */
@Composable
internal fun MembersAgentAppRevokeDialog(
    revoke: AgentAppGrantsRevoke,
    grants: List<AgentAppLoginItem>,
    inFlight: Boolean,
    onIntent: (AgentAppGrantsRevokeIntent) -> Unit,
) {
    val failures = revoke.failures
    val onlyRefused = revoke.onlyRefused
    val dismiss = { onIntent(AgentAppGrantsRevokeIntent.Close) }
    RaftDialog(
        onDismissRequest = dismiss,
        title = stringResource(if (revoke.whole) Res.string.member_app_revoke_confirm_title else Res.string.member_app_revoke_grant_confirm_title),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            if (!onlyRefused) {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = dismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !inFlight
                )
            }
            RaftButton(
                text =
                    stringResource(
                        when {
                            onlyRefused -> Res.string.common_close
                            failures != null -> Res.string.member_app_revoke_retry
                            else -> Res.string.member_app_revoke_confirm_label
                        }
                    ),
                onClick = { onIntent(AgentAppGrantsRevokeIntent.Confirm) },
                modifier = Modifier.testTag(MembersAgentAppRevokeConfirmTag),
                variant = if (onlyRefused) RaftButtonVariant.Default else RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                loading = inFlight,
                loadingLabel = stringResource(Res.string.member_app_revoking)
            )
        }
    ) {
        RaftBanner(
            description =
                stringResource(
                    if (revoke.whole) Res.string.member_app_revoke_confirm_message else Res.string.member_app_revoke_grant_confirm_message,
                    revoke.appName
                ),
            status = RaftBannerStatus.Destructive,
            size = RaftBannerSize.Sm
        )
        if (failures != null) {
            RaftBanner(
                description =
                    stringResource(
                        if (grants.size == 1) Res.string.member_app_revoke_partial_failed_one else Res.string.member_app_revoke_partial_failed_other,
                        grants.size
                    ),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag(MembersAgentAppRevokePartialFailureTag)
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
            grants.forEach { grant ->
                val failure = failures?.get(grant.id)
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(membersAgentAppRevokeItemTag(grant.id)),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        MembersAgentAppGrantAbilities(grant.scopes, modifier = Modifier.weight(1f))
                        if (failure != null) {
                            RaftBadge(
                                text =
                                    stringResource(
                                        if (failure is AgentAppGrantRevokeFailure.Refused) {
                                            Res.string.member_app_revoke_not_allowed
                                        } else {
                                            Res.string.member_app_revoke_not_revoked
                                        }
                                    ),
                                appearance = RaftBadgeAppearance.Soft,
                                variant = RaftBadgeVariant.Warning
                            )
                        }
                    }
                    if (failure != null) MembersAgentAppRevokeFailureReason(failure)
                }
            }
        }
    }
}

/**
 * Web FailureReason: why one grant didn't revoke. A refusal says retrying won't help, a server
 * failure says try again, each with what the server said and its trace ID; no answer says it may
 * not have been revoked (and has no trace ID: nothing answered).
 */
@Composable
private fun MembersAgentAppRevokeFailureReason(failure: AgentAppGrantRevokeFailure) {
    val lines =
        when (failure) {
            AgentAppGrantRevokeFailure.NoAnswer -> listOf(stringResource(Res.string.member_app_revoke_no_answer))
            is AgentAppGrantRevokeFailure.Refused ->
                listOfNotNull(
                    stringResource(Res.string.member_app_revoke_refused_line),
                    failure.reason?.let { stringResource(Res.string.member_app_revoke_server_said, it) }
                )
            is AgentAppGrantRevokeFailure.Failed ->
                listOfNotNull(
                    stringResource(Res.string.member_app_revoke_server_failed),
                    failure.reason?.let { stringResource(Res.string.member_app_revoke_server_said, it) }
                )
        }
    val traceId =
        when (failure) {
            AgentAppGrantRevokeFailure.NoAnswer -> null
            is AgentAppGrantRevokeFailure.Refused -> failure.traceId
            is AgentAppGrantRevokeFailure.Failed -> failure.traceId
        }
    Column(modifier = Modifier.fillMaxWidth().testTag(MembersAgentAppRevokeReasonTag)) {
        lines.forEach { line ->
            RaftText(text = line, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
        }
        RaftTraceIdNote(traceId)
    }
}
