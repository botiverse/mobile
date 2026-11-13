package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.theme.RaftTheme

/*
 * Computer owner v1 (botiverse/slock #13344, web ComputerOwnerField / ComputerOwnerLabel /
 * TransferComputerOwnerDialog): who Raft records as a Computer's owner, or Public (no owner). The
 * owner carries no rights; Transfer owner is for the current owner and a server owner/admin, Make
 * it mine for whoever connected a Public Computer. `PUT servers/:id/machines/:machineId/owner`.
 */

internal const val RAFT_COMPUTER_OWNER_TAG = "computer-owner"
internal const val RAFT_COMPUTER_OWNER_PUBLIC_TAG = "computer-owner-public"
internal const val RAFT_COMPUTER_OWNER_TRANSFER_TAG = "computer-owner-transfer"
internal const val RAFT_COMPUTER_OWNER_MAKE_MINE_TAG = "computer-owner-make-mine"
internal const val RAFT_COMPUTER_OWNER_LABEL_TAG = "computer-owner-label"
internal const val RAFT_TRANSFER_OWNER_DIALOG_TAG = "transfer-computer-owner-dialog"
internal const val RAFT_TRANSFER_OWNER_CONFIRM_TAG = "transfer-owner-confirm"
internal const val RAFT_TRANSFER_OWNER_FAILED_TAG = "transfer-owner-failed"
internal const val RAFT_TRANSFER_OWNER_SEARCH_TAG = "transfer-owner-person"

/** The Computers list's owner line (web `ComputerOwnerLabel`): "Owner: <name>" or "Public (no owner)". */
@Composable
internal fun raftComputerOwnerLabel(owner: RaftComputerCreatorModel?): String =
    if (owner != null) {
        stringResource(Res.string.settings_computers_owner_rack_label, owner.displayName)
    } else {
        stringResource(Res.string.settings_computers_owner_public)
    }

/** The owner line on a card or list row, in the caption hint style, one line. */
@Composable
internal fun RaftComputerOwnerLine(owner: RaftComputerCreatorModel?, modifier: Modifier = Modifier) {
    RaftText(
        raftComputerOwnerLabel(owner),
        modifier = modifier.testTag(RAFT_COMPUTER_OWNER_LABEL_TAG),
        style = RaftTheme.typography.caption,
        color = RaftTheme.colors.foregroundHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The Computer page's Owner row value (web `ComputerOwnerField`): the owner (opening their profile)
 * or "Public (no owner)", then Transfer owner and / or Make it mine as the viewer may. Call it
 * inside a flow row (the Owner fact's value).
 */
@Composable
internal fun RaftComputerOwnerField(
    owner: RaftComputerCreatorModel?,
    canTransfer: Boolean,
    canClaim: Boolean,
    claiming: Boolean,
    onOpenOwner: (RaftComputerCreatorModel) -> Unit,
    onTransfer: () -> Unit,
    onClaim: () -> Unit,
) {
    // Web: one wrapping line (`flex flex-wrap items-center gap-2`): the owner, then the buttons.
    // Emitted straight into the caller's flow (the description list's value row).
    if (owner != null) {
        Row(Modifier.testTag(RAFT_COMPUTER_OWNER_TAG)) {
            RaftComputerCreatorLink(owner, onClick = { onOpenOwner(owner) })
        }
    } else {
        RaftText(
            stringResource(Res.string.settings_computers_owner_public),
            modifier = Modifier.testTag(RAFT_COMPUTER_OWNER_PUBLIC_TAG),
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundHint,
        )
    }
    if (canTransfer) {
        RaftButton(
            text = stringResource(Res.string.settings_computers_owner_transfer),
            onClick = onTransfer,
            modifier = Modifier.testTag(RAFT_COMPUTER_OWNER_TRANSFER_TAG),
        )
    }
    if (canClaim) {
        RaftButton(
            text = stringResource(Res.string.settings_computers_owner_make_mine),
            onClick = onClaim,
            loading = claiming,
            modifier = Modifier.testTag(RAFT_COMPUTER_OWNER_MAKE_MINE_TAG),
        )
    }
}

private enum class RaftTransferOwnerChoice { Person, Public }

/**
 * Web `TransferComputerOwnerDialog`: hand the Computer to another person in the server, or make it
 * Public (only when it has an owner). The people are [candidates] ([raftComputerOwnerCandidates]),
 * searched by name. The banner says what does not move with it: the sign-ins and git credentials on
 * the machine. [onTransfer] gets the new owner's id, or null for Public; a failed save keeps the
 * dialog open with [failed].
 */
@Composable
internal fun RaftTransferComputerOwnerDialog(
    computerName: String,
    ownerUserId: String?,
    candidates: List<RaftComputerOwnerCandidate>,
    saving: Boolean,
    failed: Boolean,
    onTransfer: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf(RaftTransferOwnerChoice.Person) }
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val hasOwner = !ownerUserId.isNullOrBlank()
    val ready = if (choice == RaftTransferOwnerChoice.Public) hasOwner else personId != null
    val matching = remember(candidates, query) { raftComputerOwnerCandidatesMatching(candidates, query) }
    RaftDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = stringResource(Res.string.settings_computers_owner_dialog_title),
        modifier = Modifier.testTag(RAFT_TRANSFER_OWNER_DIALOG_TAG),
        description = stringResource(Res.string.settings_computers_owner_dialog_description, computerName),
        dismissOnClickOutside = !saving,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !saving,
            )
            RaftButton(
                text =
                    stringResource(
                        if (choice == RaftTransferOwnerChoice.Public) {
                            Res.string.settings_computers_owner_dialog_public_confirm
                        } else {
                            Res.string.settings_computers_owner_dialog_transfer_confirm
                        }
                    ),
                onClick = {
                    if (ready && !saving) onTransfer(if (choice == RaftTransferOwnerChoice.Public) null else personId)
                },
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = ready,
                loading = saving,
                loadingLabel = stringResource(Res.string.settings_computers_owner_dialog_saving),
                modifier = Modifier.testTag(RAFT_TRANSFER_OWNER_CONFIRM_TAG),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftRadioGroup {
                RaftRadioRow(
                    label = stringResource(Res.string.settings_computers_owner_dialog_choice_person),
                    selected = choice == RaftTransferOwnerChoice.Person,
                    onClick = { choice = RaftTransferOwnerChoice.Person },
                    enabled = !saving,
                )
                RaftRadioRow(
                    label = stringResource(Res.string.settings_computers_owner_dialog_choice_public),
                    selected = choice == RaftTransferOwnerChoice.Public,
                    onClick = { choice = RaftTransferOwnerChoice.Public },
                    enabled = hasOwner && !saving,
                )
            }
            if (choice == RaftTransferOwnerChoice.Person) {
                RaftText(
                    stringResource(Res.string.settings_computers_owner_dialog_person_label),
                    style = RaftTheme.typography.subhead,
                    color = RaftTheme.colors.foreground,
                )
                RaftTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag(RAFT_TRANSFER_OWNER_SEARCH_TAG),
                    placeholder = stringResource(Res.string.settings_computers_owner_dialog_person_placeholder),
                    enabled = !saving,
                )
                if (matching.isEmpty()) {
                    RaftText(
                        stringResource(Res.string.settings_computers_owner_dialog_no_match),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundHint,
                    )
                } else {
                    RaftRadioGroup(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        matching.forEach { candidate ->
                            RaftRadioRow(
                                label =
                                    if (candidate.isYou) {
                                        stringResource(Res.string.settings_computers_owner_dialog_you, candidate.name)
                                    } else {
                                        candidate.name
                                    },
                                selected = personId == candidate.id,
                                onClick = { personId = candidate.id },
                                enabled = !saving,
                            )
                        }
                    }
                }
            }
            RaftBanner(
                description = stringResource(Res.string.settings_computers_owner_dialog_credentials),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
            )
            if (failed) {
                RaftBanner(
                    description = stringResource(Res.string.settings_computers_owner_dialog_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    showIcon = false,
                    modifier = Modifier.testTag(RAFT_TRANSFER_OWNER_FAILED_TAG),
                )
            }
        }
    }
}
