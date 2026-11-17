package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactRepository
import build.raft.app.data.MemberRemovalImpactState
import build.raft.app.data.holdsConfirm
import build.raft.app.data.toMemberRemovalImpactState
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * botiverse/slock #13547 (web MemberRemovalImpactNote / useMemberRemovalImpact): the Remove member
 * and Leave server confirmations say, when anything is attached, how many Computers go offline, up
 * to three of their names (the rest read as "others"), how many agents on them belong to other
 * people, and how they come back. While the read is in flight the confirm waits with a "Checking…"
 * line; a failed read says so and leaves the confirm available. Nothing attached adds nothing.
 */

internal const val MEMBER_REMOVAL_IMPACT_TAG = "member-removal-impact"
internal const val MEMBER_REMOVAL_IMPACT_LOADING_TAG = "member-removal-impact-loading"
internal const val MEMBER_REMOVAL_IMPACT_FAILED_TAG = "member-removal-impact-failed"

/**
 * Web `useMemberRemovalImpact`: reads what ending [memberId]'s membership of [serverId] takes
 * offline when the confirmation opens. [memberId] null asks nothing ([MemberRemovalImpactState.None],
 * a Guest leaving); a blank id or no [repository] cannot ask ([MemberRemovalImpactState.Failed]).
 */
@Composable
internal fun rememberMemberRemovalImpact(
    repository: MemberRemovalImpactRepository?,
    serverId: String?,
    memberId: String?,
): MemberRemovalImpactState {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
    val cleanMemberId = memberId?.trim()
    var state by remember(repository, cleanServerId, cleanMemberId) {
        mutableStateOf(
            when {
                memberId == null -> MemberRemovalImpactState.None
                repository == null || cleanServerId == null || cleanMemberId.isNullOrBlank() -> MemberRemovalImpactState.Failed
                else -> MemberRemovalImpactState.Loading
            }
        )
    }
    LaunchedEffect(repository, cleanServerId, cleanMemberId) {
        if (state != MemberRemovalImpactState.Loading || repository == null || cleanServerId == null || cleanMemberId.isNullOrBlank()) {
            return@LaunchedEffect
        }
        state = repository.removalImpact(ServerId(cleanServerId), cleanMemberId).toMemberRemovalImpactState()
    }
    return state
}

/**
 * The Computers the route names, then "others" for the rest (web: never a count, since the counts
 * may run slightly high).
 */
internal fun memberRemovalImpactNameItems(impact: MemberRemovalImpact, others: String): List<String> =
    if (impact.computerNames.size < impact.computers) impact.computerNames + others else impact.computerNames

/**
 * `Intl.ListFormat` conjunction of [items]: [two] for a pair, else [more] between the leading items
 * and [end] before the last ("A, B, and C"). Each takes the two halves.
 */
internal fun memberRemovalImpactJoin(
    items: List<String>,
    two: (String, String) -> String,
    more: (String, String) -> String,
    end: (String, String) -> String,
): String =
    when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> two(items[0], items[1])
        else -> end(items.dropLast(1).reduce { head, next -> more(head, next) }, items.last())
    }

/** The localized names list of [impact] (web `formatImpactComputerNames`). */
@Composable
internal fun memberRemovalImpactNames(impact: MemberRemovalImpact): String {
    val items = memberRemovalImpactNameItems(impact, stringResource(Res.string.member_removal_impact_more_computers))
    val twoTemplate = stringResource(Res.string.member_removal_impact_list_two, "\u0001", "\u0002")
    val moreTemplate = stringResource(Res.string.member_removal_impact_list_more, "\u0001", "\u0002")
    val endTemplate = stringResource(Res.string.member_removal_impact_list_end, "\u0001", "\u0002")
    fun fill(template: String, a: String, b: String) = template.replace("\u0001", a).replace("\u0002", b)
    return memberRemovalImpactJoin(
        items,
        two = { a, b -> fill(twoTemplate, a, b) },
        more = { a, b -> fill(moreTemplate, a, b) },
        end = { a, b -> fill(endTemplate, a, b) },
    )
}

/** The impact's sentences: the Computers line (with the other people's agents), then how they come back. */
@Composable
internal fun memberRemovalImpactText(impact: MemberRemovalImpact, memberName: String?): Pair<String, String> {
    val computers = impact.computers
    val names = memberRemovalImpactNames(impact)
    val computersLine =
        if (memberName != null) {
            if (computers == 1) {
                stringResource(Res.string.member_removal_impact_remove_one, memberName, computers, names)
            } else {
                stringResource(Res.string.member_removal_impact_remove_other, memberName, computers, names)
            }
        } else if (computers == 1) {
            stringResource(Res.string.member_removal_impact_leave_one, computers, names)
        } else {
            stringResource(Res.string.member_removal_impact_leave_other, computers, names)
        }
    val line =
        if (impact.othersAgents > 0) {
            val othersLine =
                if (impact.othersAgents == 1) {
                    stringResource(Res.string.member_removal_impact_others_agents_one, impact.othersAgents)
                } else {
                    stringResource(Res.string.member_removal_impact_others_agents_other, impact.othersAgents)
                }
            stringResource(Res.string.member_removal_impact_with_others_agents, computersLine, othersLine)
        } else {
            computersLine
        }
    val recover =
        stringResource(
            if (computers == 1) Res.string.member_removal_impact_recover_one else Res.string.member_removal_impact_recover_other
        )
    return line to recover
}

/**
 * The impact part of a confirmation, or nothing when nothing goes offline. With [memberName] it is
 * Remove member's and names the member; without, Leave server's, about the Computers you attached.
 * [banner]: a non-zero impact in its own warning banner (Leave server); otherwise paragraphs in the
 * caller's frame (Remove member's danger banner).
 */
@Composable
internal fun MemberRemovalImpactNote(
    state: MemberRemovalImpactState,
    memberName: String? = null,
    banner: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (state) {
        MemberRemovalImpactState.None -> Unit
        MemberRemovalImpactState.Loading ->
            Row(
                modifier.testTag(MEMBER_REMOVAL_IMPACT_LOADING_TAG),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftSpinner(size = RaftSpinnerSize.Sm)
                RaftText(
                    stringResource(Res.string.member_removal_impact_loading),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
            }
        MemberRemovalImpactState.Failed ->
            RaftText(
                stringResource(Res.string.member_removal_impact_failed),
                modifier = modifier.testTag(MEMBER_REMOVAL_IMPACT_FAILED_TAG),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundMuted,
            )
        is MemberRemovalImpactState.Ready -> {
            if (state.impact.computers <= 0) return
            val (line, recover) = memberRemovalImpactText(state.impact, memberName)
            if (banner) {
                RaftBanner(
                    description = "$line\n$recover",
                    status = RaftBannerStatus.Warning,
                    modifier = modifier.testTag(MEMBER_REMOVAL_IMPACT_TAG),
                )
            } else {
                Column(modifier.testTag(MEMBER_REMOVAL_IMPACT_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftText(line, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
                    RaftText(recover, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
                }
            }
        }
    }
}

internal const val MEMBER_REMOVE_DIALOG_TAG = "member-remove-dialog"
internal const val MEMBER_REMOVE_CONFIRM_TAG = "human-remove-member-confirm"

/**
 * Web `RemoveMemberConfirmDialog` (HumanProfileActions, slock #13547): "Remove member", one danger
 * banner whose explanation also says which of the person's Computers go offline. The confirm waits
 * for that read, which gives up after 10 s, and stays available when it fails.
 */
@Composable
internal fun MembersRemoveMemberConfirmDialog(
    personName: String,
    impact: MemberRemovalImpactState,
    removing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val message = stringResource(Res.string.member_remove_dialog_message, personName)
    val ready = (impact as? MemberRemovalImpactState.Ready)?.impact?.takeIf { it.computers > 0 }
    val bannerText =
        if (ready != null) {
            val (line, recover) = memberRemovalImpactText(ready, personName)
            "$message\n\n$line\n$recover"
        } else {
            message
        }
    RaftDialog(
        onDismissRequest = { if (!removing) onDismiss() },
        title = stringResource(Res.string.member_remove_dialog_title),
        modifier = Modifier.testTag(MEMBER_REMOVE_DIALOG_TAG),
        dismissOnClickOutside = !removing,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !removing,
            )
            RaftButton(
                text = stringResource(Res.string.member_remove_dialog_confirm),
                onClick = { if (!removing && !impact.holdsConfirm) onConfirm() },
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                enabled = !impact.holdsConfirm,
                loading = removing,
                loadingLabel = stringResource(Res.string.member_remove_dialog_removing),
                modifier = Modifier.testTag(MEMBER_REMOVE_CONFIRM_TAG),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftBanner(
                description = bannerText,
                status = RaftBannerStatus.Destructive,
                modifier = if (ready != null) Modifier.testTag(MEMBER_REMOVAL_IMPACT_TAG) else Modifier,
            )
            if (ready == null) MemberRemovalImpactNote(state = impact, memberName = personName)
            errorMessage?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
        }
    }
}
