package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.reduce
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.app.data.MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES
import build.raft.app.data.MemberAttachedComputer
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactRepository
import build.raft.app.data.MemberRemovalImpactState
import build.raft.app.data.blockingComputers
import build.raft.app.data.handedOverComputers
import build.raft.app.data.holdsConfirm
import build.raft.app.data.toMemberRemovalImpactState
import build.raft.app.model.ServerId
import build.raft.app.reduce
import build.raft.designsystem.components.LocalRaftListRowInks
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * botiverse/slock #13547, #16386, #16435 (web MemberRemovalImpactNote / useMemberRemovalImpact):
 * the Remove member and Leave server confirmations show what ending a membership involves.
 *
 * - While the person is the Attached by of live Computers here (`blocking && attachedComputers`),
 *   leaving and being removed are refused (`409 member_has_computer`). The Leave dialog lists each
 *   Computer (its agents, how many are other people's, and "Delete computer" to open it) and keeps
 *   Leave disabled. The Remove member dialog lists them without the link, each saying it can't be
 *   reconnected afterwards, and offers one-step "Disconnect these computers and remove" sending
 *   `{ revokeComputers: true, computerIds }`.
 * - Otherwise, legacy machines the person registered go offline: the note names up to three (the
 *   rest read as "others"), how many agents belong to other people, and how they come back.
 * - Live Computers the person handed over (`handedOverComputers`) show a non-blocking Leave notice
 *   to sign out of Claude and Codex on those machines.
 * - While the read is in flight the confirm waits with "Checking…"; a failed read says so and
 *   leaves the confirm available (the server still enforces 409 and refreshes the list on refusal).
 */

internal const val MEMBER_REMOVAL_IMPACT_TAG = "member-removal-impact"
internal const val MEMBER_REMOVAL_IMPACT_LINE_TAG = "member-removal-impact-line"
internal const val MEMBER_REMOVAL_IMPACT_LOADING_TAG = "member-removal-impact-loading"
internal const val MEMBER_REMOVAL_IMPACT_FAILED_TAG = "member-removal-impact-failed"
internal const val MEMBER_REMOVAL_IMPACT_COMPUTERS_TAG = "member-removal-impact-computers"
internal const val MEMBER_REMOVAL_IMPACT_COMPUTER_TAG = "member-removal-impact-computer"
internal const val MEMBER_REMOVAL_IMPACT_DELETE_COMPUTER_TAG = "member-removal-impact-delete-computer"
internal const val MEMBER_REMOVAL_IMPACT_DISCONNECTS_TAG = "member-removal-impact-disconnects"
internal const val MEMBER_REMOVAL_IMPACT_HANDED_OVER_TAG = "member-removal-impact-handed-over"

/**
 * Web `useMemberRemovalImpact`: reads what ending [memberId]'s membership of [serverId] involves
 * when the confirmation opens. [memberId] null asks nothing ([MemberRemovalImpactState.None], a
 * Guest leaving); a blank id or no [repository] cannot ask ([MemberRemovalImpactState.Failed]).
 * A new [revision] reads it again (after a `409 member_has_computer` refusal).
 */
@Composable
internal fun rememberMemberRemovalImpact(
    repository: MemberRemovalImpactRepository?,
    serverId: String?,
    memberId: String?,
    revision: Int = 0,
): MemberRemovalImpactState {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
    val cleanMemberId = memberId?.trim()
    var state by remember(repository, cleanServerId, cleanMemberId, revision) {
        mutableStateOf(
            when {
                memberId == null -> MemberRemovalImpactState.None
                repository == null || cleanServerId == null || cleanMemberId.isNullOrBlank() -> MemberRemovalImpactState.Failed
                else -> MemberRemovalImpactState.Loading
            }
        )
    }
    LaunchedEffect(repository, cleanServerId, cleanMemberId, revision) {
        if (state != MemberRemovalImpactState.Loading || repository == null || cleanServerId == null || cleanMemberId.isNullOrBlank()) {
            return@LaunchedEffect
        }
        state = repository.removalImpact(ServerId(cleanServerId), cleanMemberId).toMemberRemovalImpactState()
    }
    return state
}

/** At most three names, then [others] when [total] exceeds the shown count (web `formatNames`). */
internal fun memberRemovalImpactNameItems(names: List<String>, total: Int, others: String): List<String> {
    val shown = names.take(MEMBER_REMOVAL_IMPACT_MAX_COMPUTER_NAMES)
    return if (shown.size < total) shown + others else shown
}

/**
 * The Computers the route names, then "others" for the rest (web: never a count, since the counts
 * may run slightly high).
 */
internal fun memberRemovalImpactNameItems(impact: MemberRemovalImpact, others: String): List<String> =
    memberRemovalImpactNameItems(impact.computerNames, impact.computers, others)

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

/** Localized conjunction of [items] (web `intl.formatList(items, { type: "conjunction" })`). */
@Composable
internal fun memberRemovalImpactJoinLocalized(items: List<String>): String {
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

/** At most three localized names, then "others" (web `formatNames`). */
@Composable
internal fun memberRemovalImpactNames(names: List<String>, total: Int): String =
    memberRemovalImpactJoinLocalized(
        memberRemovalImpactNameItems(names, total, stringResource(Res.string.member_removal_impact_more_computers))
    )

/** The localized names list of [impact] (web `formatImpactComputerNames`). */
@Composable
internal fun memberRemovalImpactNames(impact: MemberRemovalImpact): String =
    memberRemovalImpactNames(impact.computerNames, impact.computers)

/** The summary line above [BlockingComputerList] for [blocking] Computers (web `member.removalImpact.remove` / `.leave`). */
@Composable
internal fun memberRemovalImpactBlockingLine(blocking: List<MemberAttachedComputer>, memberName: String?): String {
    val computers = blocking.size
    val agents = blocking.sumOf { it.agentCount }
    val names = memberRemovalImpactNames(blocking.map { it.name }, computers)
    val agentsSummary =
        stringResource(
            if (agents == 1) Res.string.member_removal_impact_agents_total_one else Res.string.member_removal_impact_agents_total_other,
            agents,
        )
    return if (memberName != null) {
        stringResource(
            if (computers == 1) Res.string.member_removal_impact_remove_one else Res.string.member_removal_impact_remove_other,
            memberName,
            computers,
            names,
            agentsSummary,
        )
    } else {
        stringResource(
            if (computers == 1) Res.string.member_removal_impact_leave_one else Res.string.member_removal_impact_leave_other,
            computers,
            names,
            agentsSummary,
        )
    }
}

/** One blocking Computer's agent count subtitle (web `member.removalImpact.computerAgents`). */
@Composable
internal fun memberRemovalImpactComputerAgentsText(agentCount: Int, othersAgentCount: Int): String {
    val agentsText =
        when (agentCount) {
            0 -> stringResource(Res.string.member_removal_impact_computer_agents_zero)
            1 -> stringResource(Res.string.member_removal_impact_computer_agents_one, agentCount)
            else -> stringResource(Res.string.member_removal_impact_computer_agents_other, agentCount)
        }
    return if (othersAgentCount > 0) {
        stringResource(
            if (othersAgentCount == 1) {
                Res.string.member_removal_impact_computer_agents_with_others_one
            } else {
                Res.string.member_removal_impact_computer_agents_with_others_other
            },
            agentsText,
            othersAgentCount,
        )
    } else {
        agentsText
    }
}

/** The legacy offline impact's sentences: the Computers line (with other people's agents), then how they come back. */
@Composable
internal fun memberRemovalImpactText(impact: MemberRemovalImpact, memberName: String?): Pair<String, String> {
    val computers = impact.computers
    val names = memberRemovalImpactNames(impact)
    val computersLine =
        if (memberName != null) {
            if (computers == 1) {
                stringResource(Res.string.member_removal_impact_remove_offline_one, memberName, computers, names)
            } else {
                stringResource(Res.string.member_removal_impact_remove_offline_other, memberName, computers, names)
            }
        } else if (computers == 1) {
            stringResource(Res.string.member_removal_impact_leave_offline_one, computers, names)
        } else {
            stringResource(Res.string.member_removal_impact_leave_offline_other, computers, names)
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
 * The impact part of a confirmation, or nothing when there is none. With [memberName] it is Remove
 * member's and names the member; without, Leave server's, about the Computers you attached.
 * [banner]: a non-zero impact in its own warning banner (Leave server); otherwise paragraphs in the
 * caller's frame (Remove member's danger banner).
 * [disconnectsOnConfirm]: Remove member's one-step disconnect-and-remove flow (no Delete computer
 * link; each row says it can't be reconnected, plus a closing sentence on what confirming does).
 */
@Composable
internal fun MemberRemovalImpactNote(
    state: MemberRemovalImpactState,
    memberName: String? = null,
    banner: Boolean = false,
    disconnectsOnConfirm: Boolean = false,
    onDeleteComputer: ((machineId: String) -> Unit)? = null,
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
                    color = RaftTheme.colors.foreground,
                )
            }
        MemberRemovalImpactState.Failed ->
            RaftText(
                stringResource(Res.string.member_removal_impact_failed),
                modifier = modifier.testTag(MEMBER_REMOVAL_IMPACT_FAILED_TAG),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foreground,
            )
        is MemberRemovalImpactState.Ready -> {
            val blocking = state.blockingComputers()
            if (blocking.isNotEmpty()) {
                val line = memberRemovalImpactBlockingLine(blocking, memberName)
                if (banner) {
                    RaftBanner(
                        description = AnnotatedString(line),
                        status = RaftBannerStatus.Warning,
                        live = false,
                        modifier = modifier.testTag(MEMBER_REMOVAL_IMPACT_TAG),
                        content = {
                            BlockingComputerList(
                                computers = blocking,
                                disconnects = disconnectsOnConfirm,
                                onDeleteComputer = onDeleteComputer,
                            )
                        },
                    )
                } else {
                    Column(
                        modifier = modifier.fillMaxWidth().testTag(MEMBER_REMOVAL_IMPACT_TAG),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RaftText(
                            line,
                            modifier = Modifier.testTag(MEMBER_REMOVAL_IMPACT_LINE_TAG),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                        )
                        BlockingComputerList(
                            computers = blocking,
                            disconnects = disconnectsOnConfirm,
                            onDeleteComputer = onDeleteComputer,
                        )
                        if (disconnectsOnConfirm && memberName != null) {
                            RaftText(
                                stringResource(Res.string.member_removal_impact_remove_disconnects, memberName),
                                modifier = Modifier.testTag(MEMBER_REMOVAL_IMPACT_DISCONNECTS_TAG),
                                style = RaftTheme.typography.callout,
                                color = RaftTheme.colors.foreground,
                            )
                        }
                    }
                }
                return
            }
            if (state.impact.computers <= 0) return
            val (line, recover) = memberRemovalImpactText(state.impact, memberName)
            if (banner) {
                RaftBanner(
                    description = AnnotatedString("$line\n$recover"),
                    status = RaftBannerStatus.Warning,
                    live = false,
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

/**
 * Web `BlockingComputerList`: the Computers in the way, one display-only row each — its name, its
 * agents and how many are other people's, and "Delete computer" (opens the Computer) in the Leave
 * dialog; in Remove member (`disconnects`), no link and each says it can't be reconnected afterwards.
 */
@Composable
private fun BlockingComputerList(
    computers: List<MemberAttachedComputer>,
    disconnects: Boolean,
    onDeleteComputer: ((machineId: String) -> Unit)?,
) {
    val noReconnect = if (disconnects) stringResource(Res.string.member_removal_impact_computer_no_reconnect) else null
    val deleteLabel = if (!disconnects && onDeleteComputer != null) stringResource(Res.string.member_removal_impact_delete_computer) else null
    RaftListRowGroup(modifier = Modifier.fillMaxWidth().testTag(MEMBER_REMOVAL_IMPACT_COMPUTERS_TAG)) {
        computers.forEachIndexed { index, computer ->
            val agentsLabel = memberRemovalImpactComputerAgentsText(computer.agentCount, computer.othersAgentCount)
            val machineId = computer.machineId?.takeIf { it.isNotBlank() }
            RaftListRow(
                title = computer.name,
                variant = RaftListRowVariant.Plain,
                onClick = null,
                divider = index > 0,
                titleMaxLines = 2,
                subtitleContent = {
                    RaftText(
                        agentsLabel,
                        style = RaftTheme.typography.caption,
                        color = LocalRaftListRowInks.current?.ink3 ?: RaftTheme.colors.foregroundHint,
                    )
                },
                description = noReconnect,
                wrapDescription = disconnects,
                trailing =
                    if (deleteLabel != null && onDeleteComputer != null && machineId != null) {
                        {
                            RaftTextLink(
                                text = deleteLabel,
                                onClick = { onDeleteComputer(machineId) },
                                variant = RaftTextLinkVariant.Strong,
                                style = RaftTheme.typography.footnote,
                                modifier = Modifier.testTag(MEMBER_REMOVAL_IMPACT_DELETE_COMPUTER_TAG),
                            )
                        }
                    } else {
                        null
                    },
                modifier = Modifier.testTag(MEMBER_REMOVAL_IMPACT_COMPUTER_TAG),
            )
        }
    }
}

/**
 * Web `HandedOverComputersNote` (slock #16435): Computers here you handed over to someone else
 * (`handedOverComputers`). Your Claude and Codex sign-ins on those machines stay in use until you
 * sign out on each one. Does not hold Leave back.
 */
@Composable
internal fun HandedOverComputersNote(
    state: MemberRemovalImpactState,
    modifier: Modifier = Modifier,
) {
    val computers = state.handedOverComputers()
    if (computers.isEmpty()) return
    // Every one named (not "and others"): each is a machine to sign out on.
    val names = memberRemovalImpactJoinLocalized(computers.map { it.name })
    val text =
        stringResource(
            if (computers.size == 1) Res.string.member_removal_impact_handed_over_one else Res.string.member_removal_impact_handed_over_other,
            names,
        )
    RaftBanner(
        description = AnnotatedString(text),
        status = RaftBannerStatus.Warning,
        live = false,
        modifier = modifier.testTag(MEMBER_REMOVAL_IMPACT_HANDED_OVER_TAG),
    )
}

internal const val MEMBER_REMOVE_DIALOG_TAG = "member-remove-dialog"
internal const val MEMBER_REMOVE_CONFIRM_TAG = "human-remove-member-confirm"

/**
 * Web `RemoveMemberConfirmDialog` (HumanProfileActions, slock #13547, #16386): "Remove member",
 * one danger banner whose explanation also says what the removal involves. While the person is the
 * Attached by of live Computers here, lists them (each saying it can't be reconnected) and switches
 * the confirm to "Disconnect these computers and remove", sending exactly those Computer ids in
 * [onConfirm]. The confirm waits only while the read is in flight, and stays available when it fails.
 */
@Composable
internal fun MembersRemoveMemberConfirmDialog(
    personName: String,
    impact: MemberRemovalImpactState,
    removing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (revokeComputerIds: List<String>) -> Unit,
) {
    val message = stringResource(Res.string.member_remove_dialog_message, personName)
    val blocking = impact.blockingComputers()
    val readyLegacy =
        if (blocking.isEmpty()) {
            (impact as? MemberRemovalImpactState.Ready)?.impact?.takeIf { it.computers > 0 }
        } else {
            null
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
                text =
                    stringResource(
                        if (blocking.isNotEmpty()) {
                            Res.string.member_remove_dialog_confirm_with_computers
                        } else {
                            Res.string.member_remove_dialog_confirm
                        }
                    ),
                onClick = { if (!removing && !impact.holdsConfirm) onConfirm(blocking.map { it.id }) },
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
            when {
                blocking.isNotEmpty() ->
                    RaftBanner(
                        description = AnnotatedString(message),
                        status = RaftBannerStatus.Destructive,
                        content = {
                            MemberRemovalImpactNote(
                                state = impact,
                                memberName = personName,
                                disconnectsOnConfirm = true,
                            )
                        },
                    )
                readyLegacy != null -> {
                    val (line, recover) = memberRemovalImpactText(readyLegacy, personName)
                    RaftBanner(
                        description = "$message\n\n$line\n$recover",
                        status = RaftBannerStatus.Destructive,
                        modifier = Modifier.testTag(MEMBER_REMOVAL_IMPACT_TAG),
                    )
                }
                else -> {
                    RaftBanner(
                        description = message,
                        status = RaftBannerStatus.Destructive,
                    )
                    MemberRemovalImpactNote(
                        state = impact,
                        memberName = personName,
                        disconnectsOnConfirm = true,
                    )
                }
            }
            errorMessage?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
        }
    }
}
