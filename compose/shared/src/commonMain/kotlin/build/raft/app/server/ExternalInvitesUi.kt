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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import build.raft.app.data.InviteConnectableServer
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftMultiChoiceList
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
internal const val ExternalInviteConnectMemberTag = "external-invite-connect-member"
internal const val ExternalInviteNothingToSendTag = "external-invite-nothing-to-send"
internal const val ExternalInviteSlotsTag = "external-invite-slots"
internal const val ExternalInviteRowTag = "external-invite-row"
internal const val ExternalInviteResendTag = "external-invite-resend"
internal const val ExternalInviteWithdrawTag = "external-invite-withdraw"
internal const val ExternalInviteWithdrawConfirmTag = "external-invite-withdraw-confirm"
internal const val ExternalInvitesLoadFailedTag = "external-invites-load-failed"
internal const val ExternalInviteServersTag = "external-invite-servers"
internal const val ExternalInviteReviewServerTag = "external-invite-review-server"

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

/** A failure's text: the server's own words for a conversion refusal (slock #15771), else [externalInviteErrorDesc]. */
internal fun externalInviteFailureDesc(failure: ExternalInviteFailure, joint: Boolean = false): StringDesc =
    failure.serverMessage?.let { RawStringDesc(it) } ?: externalInviteErrorDesc(failure.error, joint)

@Composable
internal fun externalInviteFailureText(failure: ExternalInviteFailure, joint: Boolean = false): String =
    externalInviteFailureDesc(failure, joint).localized()

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
                            externalInviteFailureText(error, joint),
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
    /** Web `serversHistoryHint`: a channel not joint yet with messages; a server connected sees them. */
    serversHistoryHint: Boolean = false,
    onDismiss: () -> Unit,
    onTextChange: (String) -> Unit,
    onServersChange: (Set<String>) -> Unit = {},
    onConnectInviteChange: (email: String, checked: Boolean) -> Unit = { _, _ -> },
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
            ExternalInviteSheetBody(
                dialog,
                serverName,
                willBecomePrivate,
                joint,
                channelSlotsLeft,
                onTextChange,
                onUpgrade,
                serversHistoryHint = serversHistoryHint,
                onServersChange = onServersChange,
                onConnectInviteChange = onConnectInviteChange,
            )
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
    serversHistoryHint: Boolean = false,
    onServersChange: (Set<String>) -> Unit = {},
    onConnectInviteChange: (email: String, checked: Boolean) -> Unit = { _, _ -> },
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
        dialog.failure?.let { failure -> ExternalInviteFailureBanner(failure, joint, onUpgrade) }
        if (preview == null) {
            ExternalInviteEmailsField(
                text = dialog.text,
                onTextChange = onTextChange,
                overBy = dialog.overBy,
                enabled = !dialog.busy,
                historyHint = serversHistoryHint,
                slotsText = externalInviteTypedSlotsLine(channelSlotsLeft, dialog.entries.size, dialog.chosenServers.size)?.let { externalInviteSlotsText(it, joint) },
            )
            ExternalInviteServersField(
                servers = dialog.servers,
                values = dialog.serverIds,
                onValuesChange = onServersChange,
                enabled = !dialog.busy,
                historyHint = serversHistoryHint,
            )
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
                        if (row.connectInvite) {
                            // Web `ConnectInviteCheckbox` (slock #15279): checked by default; the
                            // member is added (or stays) either way, checked they are also invited
                            // to connect a server of theirs.
                            RaftCheckboxRow(
                                label = stringResource(Res.string.channel_external_invite_invite_to_connect),
                                checked = row.email !in dialog.noConnectInvite,
                                onCheckedChange = { checked -> onConnectInviteChange(row.email, checked) },
                                enabled = !dialog.busy,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag(ExternalInviteConnectMemberTag),
                            )
                        }
                    }
                }
            }
            val chosen = dialog.chosenServers
            if (chosen.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(colors.layerCard, RoundedCornerShape(RaftRadius.Container)),
                ) {
                    chosen.forEachIndexed { index, server ->
                        RaftListRow(
                            title = server.name,
                            modifier = Modifier.testTag(ExternalInviteReviewServerTag),
                            divider = index > 0,
                            description = stringResource(Res.string.channel_external_invite_status_will_connect, server.name),
                            wrapDescription = true,
                            leading = { RaftServerListRowAvatar(server.id, server.name, server.avatarUrl) },
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
            externalInviteSlotsLine(preview.slotsLeft, dialog.inviteCount + chosen.size)?.let { line ->
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

/** Web `errorBanner`: a refusal as a warning with its trace ID; the Free plan's limit offers Upgrade. */
@Composable
internal fun ExternalInviteFailureBanner(failure: ExternalInviteFailure, joint: Boolean = false, onUpgrade: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(ExternalInviteErrorTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = externalInviteFailureText(failure, joint),
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

/**
 * Web `ExternalInviteEmailsField`: Invite by email, its hint (the join-by-link line, then the history line and the
 * channel's slots as typed, when given) and the over-the-limit error.
 */
@Composable
internal fun ExternalInviteEmailsField(
    text: String,
    onTextChange: (String) -> Unit,
    overBy: Int,
    enabled: Boolean,
    historyHint: Boolean = false,
    slotsText: String? = null,
) {
    val hint =
        listOfNotNull(
            // Web #15466: everyone gets an email with a link (the separator/max line is gone;
            // the over-limit error still names the max).
            stringResource(Res.string.channel_external_invite_emails_join_by_link),
            if (historyHint) stringResource(Res.string.channel_external_invite_emails_see_history) else null,
            slotsText,
        ).joinToString("\n")
    RaftField(
        label = stringResource(Res.string.channel_external_invite_emails_label),
        description = hint,
        error = if (overBy > 0) stringResource(Res.string.channel_external_invite_too_many, EXTERNAL_INVITE_MAX_EMAILS, overBy) else null,
    ) {
        RaftTextarea(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth().testTag(ExternalInviteEmailsTag),
            placeholder = stringResource(Res.string.channel_external_invite_emails_placeholder),
            enabled = enabled,
            invalid = overBy > 0,
        )
    }
}

/**
 * Web `ExternalInviteServersField`: "Connect your servers", the viewer's other servers they own or
 * administer as a searchable checkbox list (DS ChoiceList, multiple). Each one checked connects
 * when the review is confirmed, with no email and no approval. One at the Free plan's external
 * channel limit is listed, not chosen. Not drawn without any.
 */
@Composable
internal fun ExternalInviteServersField(
    servers: List<InviteConnectableServer>,
    values: Set<String>,
    onValuesChange: (Set<String>) -> Unit,
    enabled: Boolean,
    historyHint: Boolean,
) {
    if (servers.isEmpty()) return
    var query by remember { mutableStateOf("") }
    val atLimit = stringResource(Res.string.external_invite_connect_at_limit)
    val items =
        servers.map { server ->
            RaftChoiceListItem(
                value = server.id,
                label = server.name,
                descriptions = if (server.atLimit) listOf(AnnotatedString(atLimit)) else emptyList(),
                enabled = !server.atLimit,
                searchText = "${server.name} ${server.slug.orEmpty()}",
                media = { RaftServerListRowAvatar(server.id, server.name, server.avatarUrl) },
            )
        }
    RaftField(
        label = stringResource(Res.string.channel_external_invite_servers_label),
        modifier = Modifier.fillMaxWidth().testTag(ExternalInviteServersTag),
        description = if (historyHint) stringResource(Res.string.channel_external_invite_servers_see_history) else null,
    ) {
        RaftMultiChoiceList(
            items = items,
            values = values,
            onValuesChange = onValuesChange,
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = stringResource(Res.string.channel_external_invite_servers_search),
            clearSearchLabel = stringResource(Res.string.channel_external_invite_servers_clear_search),
            noMatchLabel = stringResource(Res.string.channel_external_invite_servers_no_match),
            emptyLabel = stringResource(Res.string.channel_external_invite_servers_no_match),
            enabled = enabled,
        )
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
        ExternalInviteReviewStatus.MemberInChannel -> stringResource(Res.string.channel_external_invite_status_member_in_channel)
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
        is ExternalInvitesEffect.Failed -> externalInviteFailureDesc(effect.failure, joint)
        is ExternalInvitesEffect.ServersConnected ->
            (if (effect.count == 1) Res.string.channel_external_invite_servers_connected_one else Res.string.channel_external_invite_servers_connected_other)
                .format(effect.count)
        is ExternalInvitesEffect.ServersConnecting ->
            (if (effect.count == 1) Res.string.channel_external_invite_servers_connecting_one else Res.string.channel_external_invite_servers_connecting_other)
                .format(effect.count)
        is ExternalInvitesEffect.ServerRefused -> Res.string.channel_external_invite_server_refused.format(effect.serverName)
        is ExternalInvitesEffect.ServersNotConnected -> Res.string.channel_external_invite_servers_not_connected.desc()
        // Not a toast: the page opens the upgrade dialog.
        ExternalInvitesEffect.FreeLimitUpgrade -> Res.string.channel_external_invite_error_free_limit.desc()
    }

/** Whether the effect is an error toast (with the answer's trace ID), as the web's `type: "error"`. */
internal val ExternalInvitesEffect.isError: Boolean
    get() =
        this is ExternalInvitesEffect.Failed ||
            this is ExternalInvitesEffect.ServerRefused ||
            this is ExternalInvitesEffect.ServersNotConnected

/** The trace ID an error effect shows. */
internal val ExternalInvitesEffect.traceId: String?
    get() =
        when (this) {
            is ExternalInvitesEffect.Failed -> failure.traceId
            is ExternalInvitesEffect.ServerRefused -> traceId
            is ExternalInvitesEffect.ServersNotConnected -> traceId
            else -> null
        }

/**
 * A refused server's reason, placed in `RaftToast.description` (web `toastServerOutcomes`):
 * values {target} the server, {channel} with its #, {server} the host, {inviter} empty.
 */
internal fun externalInviteServerRefusalDesc(reason: ExternalInviteServerRefusal, target: String, channel: String, host: String): StringDesc =
    when (reason) {
        ExternalInviteServerRefusal.HostFreeLimit -> Res.string.channel_external_invite_error_free_limit.desc()
        ExternalInviteServerRefusal.PartyLimit -> Res.string.channel_external_invite_server_refused_party_limit.format(channel, JOINT_CHANNEL_MAX_SERVERS)
        is ExternalInviteServerRefusal.Invitee ->
            reason.serverWords?.let { RawStringDesc(it) }
                ?: inviteeMessageDesc(reason.message, target = target, channel = channel, server = host, inviter = "")
    }

/** One resolved toast to post via `BridgeModule.toast` or structured `BridgeModule.errorToast`. */
internal data class ExternalInviteResolvedToast(
    val title: String,
    val reason: String? = null,
    val isError: Boolean = false,
    val traceId: String? = null,
)

/**
 * Resolves a batch of [ExternalInvitesEffect] into display-ready toasts (`#proj-mobile:#319`):
 * - Separates heading ([ExternalInviteResolvedToast.title]) and refusal explanation
 *   ([ExternalInviteResolvedToast.reason]) so `BridgeModule.errorToast(title, reason, traceId)`
 *   puts the reason in `RaftToast.description` and the trace ID on its own footnote row.
 * - Combines multiple [ExternalInvitesEffect.ServerRefused] items in the same batch into one
 *   toast so later refusals never overwrite earlier servers' refusal reasons (when every refused
 *   server shares the same reason sentence — e.g. `channel_conversion_too_large` — the sentence
 *   is shown once; distinct reasons are listed one per server).
 * - Preserves whole-request [ExternalInvitesEffect.ServersNotConnected.failure] detail in [reason].
 */
internal fun buildExternalInviteToasts(
    effects: List<ExternalInvitesEffect>,
    joint: Boolean,
    channel: String,
    host: String,
    resolveDesc: (StringDesc) -> String,
): List<ExternalInviteResolvedToast> {
    val result = mutableListOf<ExternalInviteResolvedToast>()
    val refused = mutableListOf<ExternalInvitesEffect.ServerRefused>()
    effects.forEach { effect ->
        when (effect) {
            ExternalInvitesEffect.FreeLimitUpgrade -> Unit
            is ExternalInvitesEffect.ServerRefused -> refused += effect
            is ExternalInvitesEffect.ServersNotConnected -> {
                val title = resolveDesc(externalInviteEffectDesc(effect, joint))
                val reason = effect.failure?.let { resolveDesc(externalInviteFailureDesc(it, joint)) }
                result += ExternalInviteResolvedToast(title = title, reason = reason, isError = true, traceId = effect.traceId)
            }
            else -> {
                result +=
                    ExternalInviteResolvedToast(
                        title = resolveDesc(externalInviteEffectDesc(effect, joint)),
                        reason = null,
                        isError = effect.isError,
                        traceId = effect.traceId,
                    )
            }
        }
    }
    if (refused.isNotEmpty()) {
        val names = refused.map { it.serverName.trim() }.filter { it.isNotEmpty() }.distinct()
        val title =
            if (names.isEmpty()) {
                resolveDesc(Res.string.channel_external_invite_error_generic.desc())
            } else {
                resolveDesc(Res.string.channel_external_invite_server_refused.format(names.joinToString(", ")))
            }
        val pairs =
            refused.map { item ->
                item.serverName to
                    resolveDesc(
                        externalInviteServerRefusalDesc(
                            reason = item.reason,
                            target = item.serverName,
                            channel = channel,
                            host = host,
                        )
                    ).trim()
            }
        val distinctReasons = pairs.map { it.second }.filter { it.isNotEmpty() }.distinct()
        val combinedReason =
            when {
                distinctReasons.isEmpty() -> null
                distinctReasons.size == 1 -> distinctReasons.single()
                else ->
                    pairs
                        .filter { it.second.isNotEmpty() }
                        .joinToString("\n") { (serverName, reasonText) ->
                            if (serverName.isNotBlank()) "$serverName: $reasonText" else reasonText
                        }
            }
        val traceId = refused.firstNotNullOfOrNull { it.traceId }
        result += ExternalInviteResolvedToast(title = title, reason = combinedReason, isError = true, traceId = traceId)
    }
    return result
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
