package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize

/**
 * Which Leave confirmation to show (web `LeaveChannelConfirmDialog`, botiverse/slock #11292).
 * How you come back differs: a public channel you rejoin yourself, a private one needs a
 * member's invite, a joint one an admin's. The server deletes a private channel, with its
 * messages, once its last member (human or agent) leaves: [LastMember].
 */
internal enum class LeaveChannelConfirmVariant {
    Public,
    Private,
    Joint,
    LastMember,
}

/**
 * Web `leavingDeletesChannel(channelType, memberCount)`: private and `memberCount <= 1`. A roster
 * still loading, or failed to load, counts as empty ([memberCount] null) — warning once too often
 * beats deleting without a word. Joint channels are never deleted by a leave.
 */
internal fun leaveChannelConfirmVariant(
    isJoint: Boolean,
    isPrivate: Boolean,
    memberCount: Int?,
): LeaveChannelConfirmVariant =
    when {
        isJoint -> LeaveChannelConfirmVariant.Joint
        isPrivate && (memberCount == null || memberCount <= 1) -> LeaveChannelConfirmVariant.LastMember
        isPrivate -> LeaveChannelConfirmVariant.Private
        else -> LeaveChannelConfirmVariant.Public
    }

internal fun leaveChannelConfirmVariant(
    channel: HomeChannelRow,
    memberSlice: SharedServerChannelMembersSlice?,
): LeaveChannelConfirmVariant =
    leaveChannelConfirmVariant(
        isJoint = channelSettingsIsJoint(channel),
        isPrivate = channelSettingsIsPrivate(channel),
        memberCount = memberSlice?.loadedMemberCountFor(channel),
    )

/** Humans + agents of a loaded roster for [channel]; null while loading, failed or for another channel. */
private fun SharedServerChannelMembersSlice.loadedMemberCountFor(channel: HomeChannelRow): Int? =
    members.size.takeIf {
        status == SharedServerMembersLoadStatus.Loaded &&
            channelId.trim() == channel.channelId.value.trim()
    }

/**
 * The sidebar row menu's "Leave Channel" (web Sidebar `canLeaveChannel`, botiverse/slock #11289):
 * the same rule as Leave in the channel's settings — joined, not a DM, not #all, not archived.
 */
internal fun homeConversationCanLeave(
    joined: Boolean,
    archived: Boolean,
    isDirectMessage: Boolean,
    name: String,
): Boolean =
    joined && !archived && !isDirectMessage && !name.removePrefix("#").equals("all", ignoreCase = true)

/**
 * Leave's confirmation (raft-ui ConfirmDialog), shared by the channel settings and the Home row
 * menu as web shares `LeaveChannelConfirmDialog`: orange (Warning) Leave, or the red (Danger)
 * "Leave and Delete" when the last member leaves a private channel.
 */
@Composable
internal fun LeaveChannelConfirmDialog(
    variant: LeaveChannelConfirmVariant,
    channelName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deletes = variant == LeaveChannelConfirmVariant.LastMember
    val title =
        stringResource(if (deletes) Res.string.channel_settings_leave_delete_title else Res.string.channel_settings_leave)
    val description =
        when (variant) {
            LeaveChannelConfirmVariant.Public -> stringResource(Res.string.channel_settings_leave_confirm, channelName)
            LeaveChannelConfirmVariant.Private ->
                stringResource(Res.string.channel_settings_leave_confirm_private, channelName)
            LeaveChannelConfirmVariant.Joint -> stringResource(Res.string.channel_settings_leave_confirm_joint, channelName)
            LeaveChannelConfirmVariant.LastMember -> stringResource(Res.string.channel_settings_leave_delete_message)
        }
    val confirmLabel =
        stringResource(
            if (deletes) Res.string.channel_settings_leave_delete_action else Res.string.channel_settings_leave_action
        )
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = title,
            description = description,
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
                    text = confirmLabel,
                    onClick = onConfirm,
                    variant = if (deletes) RaftButtonVariant.Danger else RaftButtonVariant.Warning,
                    size = RaftButtonSize.Lg,
                    loading = busy,
                    loadingLabel = stringResource(Res.string.channel_settings_leaving),
                )
            },
        )
    }
}
