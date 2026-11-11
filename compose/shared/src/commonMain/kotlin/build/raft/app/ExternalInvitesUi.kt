package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ChannelExternalInvite
import build.raft.app.data.EXTERNAL_INVITE_EXPIRY_DAYS
import build.raft.app.data.EXTERNAL_INVITE_MAX_EMAILS
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable

internal const val ExternalInviteEntryTag = "add-member-external-entry"
internal const val ExternalInviteSheetTag = "external-invite-sheet"
internal const val ExternalInviteEmailsTag = "external-invite-emails"
internal const val ExternalInviteErrorTag = "external-invite-error"
internal const val ExternalInviteUpgradeTag = "external-invite-upgrade"
internal const val ExternalInvitePrivateWarningTag = "external-invite-private-warning"
internal const val ExternalInviteReviewRowTag = "external-invite-review-row"
internal const val ExternalInviteNothingToSendTag = "external-invite-nothing-to-send"
internal const val ExternalInviteSlotsTag = "external-invite-slots"
internal const val ExternalInviteRowTag = "external-invite-row"
internal const val ExternalInviteResendTag = "external-invite-resend"
internal const val ExternalInviteWithdrawTag = "external-invite-withdraw"
internal const val ExternalInviteWithdrawConfirmTag = "external-invite-withdraw-confirm"
internal const val ExternalInvitesLoadFailedTag = "external-invites-load-failed"

/** The text of a failure (web `ERROR_MESSAGE_IDS`); a full joint channel also offers disconnecting a server. */
internal fun externalInviteErrorDesc(error: ExternalInviteError, joint: Boolean = false): StringDesc =
    when (error) {
        ExternalInviteError.DailyCap -> Res.string.channel_external_invite_error_daily_cap.desc()
        ExternalInviteError.FreeLimit -> Res.string.channel_external_invite_error_free_limit.desc()
        ExternalInviteError.ChannelFull ->
            if (joint) {
                Res.string.channel_external_invite_error_channel_full_or_disconnect.desc()
            } else {
                Res.string.channel_external_invite_error_channel_full.desc()
            }
        ExternalInviteError.KeepPrivate -> Res.string.channel_external_invite_error_keep_private.desc()
        ExternalInviteError.MembershipRequired -> Res.string.channel_external_invite_error_membership_required.desc()
        ExternalInviteError.MakePrivateRequired -> Res.string.channel_external_invite_error_make_private_required.desc()
        ExternalInviteError.Archived -> Res.string.channel_external_invite_error_archived.desc()
        ExternalInviteError.NotInvitable -> Res.string.channel_external_invite_error_not_invitable.desc()
        ExternalInviteError.Forbidden -> Res.string.channel_external_invite_error_forbidden.desc()
        ExternalInviteError.Connecting -> Res.string.channel_external_invite_error_connecting.desc()
        ExternalInviteError.NotPending -> Res.string.channel_external_invite_error_not_pending.desc()
        ExternalInviteError.InvalidInput -> Res.string.channel_external_invite_error_invalid_input.format(EXTERNAL_INVITE_MAX_EMAILS)
        ExternalInviteError.NotFound -> Res.string.channel_external_invite_error_not_found.desc()
        ExternalInviteError.Generic -> Res.string.channel_external_invite_error_generic.desc()
    }

@Composable
internal fun externalInviteErrorText(error: ExternalInviteError, joint: Boolean = false): String =
    externalInviteErrorDesc(error, joint).localized()

@Composable
private fun externalInviteSlotsText(line: ExternalInviteSlotsLine, joint: Boolean): String =
    when (line) {
        ExternalInviteSlotsLine.Full -> externalInviteErrorText(ExternalInviteError.ChannelFull, joint)
        is ExternalInviteSlotsLine.Over ->
            stringResource(
                if (line.room == 1) Res.string.channel_external_invite_over_slots_one else Res.string.channel_external_invite_over_slots_other,
                line.room,
                line.count,
            )
        ExternalInviteSlotsLine.Fills -> stringResource(Res.string.channel_external_invite_fills_channel)
        is ExternalInviteSlotsLine.Left -> stringResource(Res.string.channel_external_invite_slots_left, line.count)
    }

@Composable
private fun ExternalInviteIcon(name: RaftLucideIconName, size: androidx.compose.ui.unit.Dp = 16.dp) = RaftComputersIcon(name, size)

/**
 * Web `addExternalMemberRow` (design v5.0 A1): under Create agent in Add Member, the same row
 * style with a dashed Mail tile, "Invite people outside {server}" and its line. Host owners and
 * admins only ([externalInviteAccess]).
 */
@Composable
internal fun ExternalInviteEntryRow(serverName: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val chipShape = RoundedCornerShape(RaftRadius.Chip)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ExternalInviteEntryTag)
            .raftClickable(onClick = onClick, enabled = enabled, shape = chipShape, tier = RaftHoverTier.Subtle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tileInk = colors.foregroundHint
        Box(
            Modifier.size(24.dp).drawBehind {
                val stroke = RaftStroke.Control.toPx()
                val inset = stroke / 2f
                drawRoundRect(
                    color = tileInk,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius((RaftRadius.Sticker.toPx() - inset).coerceAtLeast(0f)),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            RaftLucideIcon(RaftLucideIconName.Mail, modifier = Modifier.size(14.dp), color = raftArgbHex(tileInk))
        }
        Column(Modifier.weight(1f)) {
            RaftText(
                stringResource(Res.string.channel_external_invite_title, serverName),
                style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) colors.foreground else colors.foregroundDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftText(
                stringResource(Res.string.channel_external_invite_entry_description, serverName),
                style = typography.caption,
                color = colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One open invite in the Invited group (web `ExternalInviteRow`, design v5.0 A4): a Mail tile, the
 * email with where it stands (days left, waiting for approval, connecting a server) in hint ink,
 * then Resend (a plain pending invite, flag on) and Withdraw (off while its conversion can't be
 * interrupted; the long press says why). Shown at rest, as on touch on the web.
 */
@Composable
internal fun ExternalInviteRow(
    invite: ChannelExternalInvite,
    nowEpochMillis: Long,
    canResend: Boolean,
    busy: Boolean,
    divider: Boolean,
    onResend: () -> Unit,
    onWithdraw: () -> Unit,
) {
    val state =
        when {
            invite.connecting -> stringResource(Res.string.channel_external_invite_state_connecting)
            invite.awaitingApproval -> stringResource(Res.string.channel_external_invite_state_awaiting_approval)
            else -> {
                val days = externalInviteDaysLeft(invite.expiresAt, nowEpochMillis)
                stringResource(
                    if (days == 1) Res.string.channel_external_invite_days_left_one else Res.string.channel_external_invite_days_left_other,
                    days,
                )
            }
        }
    val resendShown = canResend && !invite.connecting && !invite.awaitingApproval
    val withdrawLabel = stringResource(Res.string.channel_external_invite_withdraw)
    RaftListRow(
        title = invite.email,
        modifier = Modifier.testTag(ExternalInviteRowTag),
        divider = divider,
        text = RaftListRowText.Member,
        titleCopyText = invite.email,
        titleTrailing = {
            RaftText(state, style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint, maxLines = 1)
        },
        leading = {
            RaftIconTile(size = RaftIconTileSize.Md) { ExternalInviteIcon(RaftLucideIconName.Mail) }
        },
        trailing = {
            if (resendShown) {
                RaftIconButton(
                    onClick = onResend,
                    tooltip = stringResource(Res.string.channel_external_invite_resend),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Md,
                    enabled = !busy,
                    loading = busy,
                    modifier = Modifier.testTag(ExternalInviteResendTag),
                ) {
                    ExternalInviteIcon(RaftLucideIconName.Mail, size = 18.dp)
                }
            }
            RaftIconButton(
                onClick = onWithdraw,
                tooltip =
                    if (invite.revocable) withdrawLabel else "$withdrawLabel\n${stringResource(Res.string.channel_external_invite_withdraw_connecting)}",
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
                enabled = !busy && invite.revocable,
                modifier = Modifier.testTag(ExternalInviteWithdrawTag),
            ) {
                ExternalInviteIcon(RaftLucideIconName.X, size = 18.dp)
            }
        },
    )
}

/** Web `externalInvitesFailedBanner`: the invites couldn't be read (none shown), with Retry and the trace ID. */
@Composable
internal fun ExternalInvitesLoadFailedBanner(traceId: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp).testTag(ExternalInvitesLoadFailedTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = stringResource(Res.string.channel_external_invite_load_failed),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm,
            modifier = Modifier.fillMaxWidth(),
            action = {
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    onClick = onRetry,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                )
            },
        )
        RaftTraceIdNote(traceId)
    }
}

/**
 * Web Withdraw's ConfirmDialog: "Withdraw invite?", the link sent to {email} stops working, Cancel
 * and a red Withdraw. A refusal stays in the dialog with its trace ID.
 */
@Composable
internal fun ExternalInviteWithdrawDialog(
    invite: ChannelExternalInvite,
    busy: Boolean,
    failure: ExternalInviteFailure?,
    joint: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = stringResource(Res.string.channel_external_invite_withdraw_confirm_title),
            description = stringResource(Res.string.channel_external_invite_withdraw_confirm_message, invite.email),
            size = RaftDialogSize.Sm,
            dismissOnClickOutside = false,
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !busy,
                )
                RaftButton(
                    text = stringResource(Res.string.channel_external_invite_withdraw_confirm_confirm),
                    onClick = onConfirm,
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Lg,
                    loading = busy,
                    loadingLabel = stringResource(Res.string.channel_external_invite_withdraw_confirm_loading),
                    modifier = Modifier.testTag(ExternalInviteWithdrawConfirmTag),
                )
            },
            content =
                failure?.let { error ->
                    {
                        RaftText(
                            externalInviteErrorText(error.error, joint),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.dangerStrong,
                        )
                        RaftTraceIdNote(error.traceId)
                    }
                },
        )
    }
}

/**
 * Invite people outside {server} (web AddExternalMemberDialog + ExternalInviteSteps, a phone dialog
 * sheet): the emails (up to 20, the over-the-limit error under the field, the channel's slots as
 * typed), then the review of what the server makes of them (who is invited, who is added as a
 * member of the server, who isn't and why), then Invite. A public channel becomes private with
 * the first invite, said above both steps. A refusal is a warning banner with its trace ID; the
 * Free plan's one external channel offers Upgrade.
 */
@Composable
internal fun ExternalInviteSheet(
    dialog: ExternalInviteDialogState,
    serverName: String,
    willBecomePrivate: Boolean,
    joint: Boolean,
    channelSlotsLeft: Int?,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onUpgrade: () -> Unit,
) {
    RaftDesignSystemTheme {
        val preview = dialog.preview
        RaftDialogSheet(
            onDismissRequest = { if (!dialog.busy) onDismiss() },
            title = stringResource(Res.string.channel_external_invite_title, serverName),
            commitLabel =
                when {
                    preview == null && dialog.busy -> stringResource(Res.string.channel_external_invite_checking)
                    preview == null -> stringResource(Res.string.channel_external_invite_next)
                    dialog.busy -> stringResource(Res.string.channel_external_invite_sending)
                    else -> stringResource(Res.string.channel_external_invite_commit)
                },
            onCommit = if (preview == null) onNext else onSend,
            commitEnabled = if (preview == null) dialog.canNext else dialog.canSend,
            commitLoading = dialog.busy,
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(Res.string.common_cancel),
            closeEnabled = !dialog.busy,
            onBack = if (preview != null && !dialog.busy) onBack else null,
            backLabel = stringResource(Res.string.channel_external_invite_back),
            modifier = Modifier.testTag(ExternalInviteSheetTag),
        ) {
            ExternalInviteSheetBody(dialog, serverName, willBecomePrivate, joint, channelSlotsLeft, onTextChange, onUpgrade)
        }
    }
}

/** The sheet's content, for [ExternalInviteSheet] and the screenshot tests' window-free surface. */
@Composable
internal fun ExternalInviteSheetBody(
    dialog: ExternalInviteDialogState,
    serverName: String,
    willBecomePrivate: Boolean,
    joint: Boolean,
    channelSlotsLeft: Int?,
    onTextChange: (String) -> Unit,
    onUpgrade: () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val preview = dialog.preview
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (willBecomePrivate) {
            RaftBanner(
                description = stringResource(Res.string.channel_external_invite_becomes_private),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.fillMaxWidth().testTag(ExternalInvitePrivateWarningTag),
            )
        }
        dialog.failure?.let { failure ->
            Column(Modifier.fillMaxWidth().testTag(ExternalInviteErrorTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftBanner(
                    description = externalInviteErrorText(failure.error, joint),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.fillMaxWidth(),
                    action =
                        if (failure.upgrade) {
                            {
                                RaftButton(
                                    text = stringResource(Res.string.channel_external_invite_upgrade),
                                    onClick = onUpgrade,
                                    variant = RaftButtonVariant.Default,
                                    size = RaftButtonSize.Md,
                                    modifier = Modifier.testTag(ExternalInviteUpgradeTag),
                                )
                            }
                        } else {
                            null
                        },
                )
                RaftTraceIdNote(failure.traceId)
            }
        }
        if (preview == null) {
            val overBy = dialog.overBy
            val slots = externalInviteTypedSlotsLine(channelSlotsLeft, dialog.entries.size)
            val hint = stringResource(Res.string.channel_external_invite_emails_hint, EXTERNAL_INVITE_MAX_EMAILS)
            RaftField(
                label = stringResource(Res.string.channel_external_invite_emails_label),
                description = slots?.let { "$hint\n${externalInviteSlotsText(it, joint)}" } ?: hint,
                error =
                    if (overBy > 0) stringResource(Res.string.channel_external_invite_too_many, EXTERNAL_INVITE_MAX_EMAILS, overBy) else null,
            ) {
                RaftTextarea(
                    value = dialog.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().testTag(ExternalInviteEmailsTag),
                    placeholder = stringResource(Res.string.channel_external_invite_emails_placeholder),
                    enabled = !dialog.busy,
                    invalid = overBy > 0,
                )
            }
        } else {
            val rows = externalInviteReviewRows(preview)
            if (rows.isNotEmpty()) {
                RaftText(
                    stringResource(Res.string.channel_external_invite_review_intro, EXTERNAL_INVITE_EXPIRY_DAYS),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                )
                Column(
                    Modifier.fillMaxWidth().background(colors.layerCard, RoundedCornerShape(RaftRadius.Container)),
                ) {
                    rows.forEachIndexed { index, row ->
                        RaftListRow(
                            title = row.email,
                            modifier = Modifier.testTag(ExternalInviteReviewRowTag),
                            divider = index > 0,
                            titleMaxLines = 3,
                            description = externalInviteReviewStatusText(row, serverName),
                            wrapDescription = true,
                        )
                    }
                }
            }
            if (dialog.actionCount == 0) {
                RaftText(
                    stringResource(Res.string.channel_external_invite_nothing_to_send),
                    modifier = Modifier.testTag(ExternalInviteNothingToSendTag),
                    style = typography.callout,
                    color = colors.foregroundMuted,
                )
            }
            externalInviteSlotsLine(preview.slotsLeft, dialog.inviteCount)?.let { line ->
                val text = externalInviteSlotsText(line, joint)
                if (line is ExternalInviteSlotsLine.Full || line is ExternalInviteSlotsLine.Over) {
                    RaftBanner(
                        description = text,
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        modifier = Modifier.fillMaxWidth().testTag(ExternalInviteSlotsTag),
                    )
                } else {
                    RaftText(text, modifier = Modifier.testTag(ExternalInviteSlotsTag), style = typography.callout, color = colors.foregroundMuted)
                }
            }
        }
    }
}

/** A review row's status line; a guest of another channel also says why (web's tooltip, inline here). */
@Composable
private fun externalInviteReviewStatusText(row: ExternalInviteReviewRow, serverName: String): String =
    when (row.status) {
        ExternalInviteReviewStatus.WillInvite -> stringResource(Res.string.channel_external_invite_status_will_invite)
        ExternalInviteReviewStatus.WillAdd -> stringResource(Res.string.channel_external_invite_status_will_add, serverName)
        ExternalInviteReviewStatus.Duplicate -> stringResource(Res.string.channel_external_invite_status_duplicate)
        ExternalInviteReviewStatus.AlreadyInvited -> stringResource(Res.string.channel_external_invite_status_already_invited)
        ExternalInviteReviewStatus.AlreadyInChannel -> stringResource(Res.string.channel_external_invite_status_already_in_channel)
        ExternalInviteReviewStatus.GuestElsewhere ->
            stringResource(Res.string.channel_external_invite_status_guest_elsewhere, row.channel.orEmpty()) + "\n" +
                stringResource(Res.string.channel_external_invite_status_guest_elsewhere_hint, serverName, row.channel.orEmpty())
        ExternalInviteReviewStatus.GuestElsewhereUnnamed ->
            stringResource(Res.string.channel_external_invite_status_guest_elsewhere_unnamed) + "\n" +
                stringResource(Res.string.channel_external_invite_status_guest_elsewhere_unnamed_hint, serverName)
        ExternalInviteReviewStatus.Invalid -> stringResource(Res.string.channel_external_invite_status_invalid)
    }

/** The toast text of an effect (web showToast titles); [ExternalInvitesEffect.Failed] is an error toast. */
internal fun externalInviteEffectDesc(effect: ExternalInvitesEffect, joint: Boolean = false): StringDesc =
    when (effect) {
        is ExternalInvitesEffect.Sent ->
            (if (effect.count == 1) Res.string.channel_external_invite_sent_one else Res.string.channel_external_invite_sent_other).format(effect.count)
        is ExternalInvitesEffect.Added ->
            (if (effect.count == 1) Res.string.channel_external_invite_added_one else Res.string.channel_external_invite_added_other).format(effect.count)
        is ExternalInvitesEffect.Resent -> Res.string.channel_external_invite_resent.format(effect.email)
        is ExternalInvitesEffect.Withdrawn -> Res.string.channel_external_invite_withdrawn.format(effect.email)
        is ExternalInvitesEffect.Failed -> externalInviteErrorDesc(effect.failure.error, joint)
    }

/**
 * What the members page shows of the channel's external invites: the Invited group and its load
 * failure ([management]), and Add Member's Invite people outside {server} entry ([access]).
 */
internal data class ExternalInvitesSection(
    val state: ExternalInvitesState,
    /** [externalInviteAccess]: invite and resend. */
    val access: Boolean,
    /** [externalInviteManagement]: see and withdraw. */
    val management: Boolean,
    val serverName: String,
    val nowEpochMillis: Long,
    val onRetry: () -> Unit = {},
    val onResend: (String) -> Unit = {},
    val onWithdraw: (String) -> Unit = {},
    val onOpenInvite: () -> Unit = {},
) {
    /** The invites the roster search matches (web `visibleExternalInvites`: by email). */
    fun visibleInvites(query: String): List<ChannelExternalInvite> {
        if (!management) return emptyList()
        val needle = query.trim().lowercase()
        return if (needle.isEmpty()) state.invites else state.invites.filter { it.email.lowercase().contains(needle) }
    }

    /** Web: the banner only when the read failed and nothing is listed. */
    val loadFailedShown: Boolean
        get() = management && state.loadFailure != null && state.invites.isEmpty()
}
