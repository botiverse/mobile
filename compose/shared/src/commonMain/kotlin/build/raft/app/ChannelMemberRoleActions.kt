package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.data.ChannelMemberRole
import build.raft.app.model.MemberProfile
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem

/*
 * The channel members page's role action (web `MemberRoleActions`, botiverse/slock
 * packages/web/src/components/agent/MemberRoleActions.tsx): "Make admin" / "Demote" on a member's
 * row. The web shows it as an outline button at rest on touch; here it sits in the row's ⋯ menu.
 */

/** What a member row's role menu offers (web `MemberRoleActions` `nextRole` / `canExplainProtectedDemote`). */
internal enum class ChannelMemberRoleAction {
    /** `PATCH .../role {role:"admin"}`. */
    MakeAdmin,

    /** `PATCH .../role {role:"member"}`. */
    RemoveAdmin,

    /**
     * A server owner or admin who is a channel admin everywhere: "Demote" explains why it can't
     * be done (web `onProtectedDemote`, the cannot-demote dialog) and sends nothing.
     */
    ExplainProtectedDemote,
    ;

    /** The role this action asks the server for; null for the explanation. */
    val nextRole: ChannelMemberRole?
        get() =
            when (this) {
                MakeAdmin -> ChannelMemberRole.Admin
                RemoveAdmin -> ChannelMemberRole.Member
                ExplainProtectedDemote -> null
            }
}

/**
 * Web `MemberRoleActions`, one rule:
 * - the server role is `serverRole ?? role` (here [MemberProfile.serverRole], already both);
 * - the effective role is `effectiveChannelRole`, else admin for a server owner / admin or an
 *   explicit channel admin, else member;
 * - Make admin / Demote when the server says the viewer may change THIS member's role
 *   (`canChangeChannelRole`: never oneself, never a server owner / admin / guest, only this server's
 *   members of a joint channel);
 * - the cannot-demote explanation for someone else who is a server owner / admin and holds an admin role;
 * - both only on a channel whose roles can change (not archived, not #all) where the viewer has
 *   `channelCapabilities.changeChannelMemberRoles` ([canChangeMemberRoles]).
 */
internal fun channelMemberRoleAction(
    member: MemberProfile,
    canChangeMemberRoles: Boolean,
    currentUserId: String?,
): ChannelMemberRoleAction? {
    if (!canChangeMemberRoles) return null
    val serverRole = member.serverRole?.trim()?.lowercase() ?: "member"
    val effectiveRole =
        member.effectiveChannelRole?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: if (serverRole == "owner" || serverRole == "admin" || member.channelRole?.trim()?.lowercase() == "admin") {
                "admin"
            } else {
                "member"
            }
    val protectedServerAdmin = serverRole == "owner" || serverRole == "admin"
    val self = !currentUserId.isNullOrBlank() && member.id.value.trim() == currentUserId.trim()
    val canExplainProtectedDemote = !self && protectedServerAdmin && effectiveRole != "member"
    val canChangeRole = member.canChangeChannelRole == true
    return when {
        canExplainProtectedDemote -> ChannelMemberRoleAction.ExplainProtectedDemote
        !canChangeRole -> null
        effectiveRole == "member" -> ChannelMemberRoleAction.MakeAdmin
        else -> ChannelMemberRoleAction.RemoveAdmin
    }
}

/** The page's role-change state (web `roleChangingKey` / `roleError`). */
internal data class ChannelMemberRoleChangeState(
    /** `kind:id` of the row whose change is in flight ([memberKey]); its ⋯ shows the spinner. */
    val changingKey: String? = null,
    /** The last change failed: the page's warning banner (web `channel-member-role-error`). */
    val failed: Boolean = false,
    /** The failed request's trace id, under the banner. */
    val failureTraceId: String? = null,
    /** The name the cannot-demote dialog names (web `protectedDemoteTarget`); null while closed. */
    val protectedDemoteName: String? = null,
)

internal const val ChannelMemberRoleMenuTestTag = "channel-member-role-menu"
internal const val ChannelMemberRoleActionTestTag = "channel-member-role-action"

/**
 * The row's ⋯ with its menu: one item, the web button's label ("Make admin" / "Demote"), which
 * reads its full sentence ("Make ada a channel admin") to a screen reader.
 */
@Composable
internal fun ChannelMemberRoleMenuButton(
    action: ChannelMemberRoleAction,
    memberName: String,
    busy: Boolean,
    onAction: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RaftIconButton(
            onClick = { open = true },
            tooltip = stringResource(Res.string.common_more_actions),
            modifier = Modifier.testTag(ChannelMemberRoleMenuTestTag),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Md,
            enabled = !busy,
            loading = busy,
        ) {
            RaftContentLucideIcon(RaftLucideIconName.Ellipsis, size = 18.dp)
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, minWidth = 192.dp, alignEnd = true) {
            val makeAdmin = action == ChannelMemberRoleAction.MakeAdmin
            val fullLabel =
                stringResource(
                    if (makeAdmin) Res.string.channel_members_make_admin else Res.string.channel_members_remove_admin,
                    memberName,
                )
            RaftMenuItem(
                text =
                    stringResource(
                        if (makeAdmin) Res.string.channel_members_make_admin_short else Res.string.channel_members_remove_admin_short
                    ),
                modifier = Modifier.testTag(ChannelMemberRoleActionTestTag).semantics { contentDescription = fullLabel },
                leadingIcon = {
                    MembersMenuIcon(if (makeAdmin) RaftLucideIconName.ShieldCheck else RaftLucideIconName.Shield)
                },
                onClick = {
                    open = false
                    onAction()
                },
            )
        }
    }
}

/**
 * Web cannot-demote `ConfirmDialog` (`hideCancel`, one outline OK): a server owner or admin stays
 * a channel admin everywhere until their server role changes.
 */
@Composable
internal fun ChannelMemberProtectedDemoteDialog(name: String, onDismiss: () -> Unit) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.channel_members_cannot_demote_server_admin_title),
        description = stringResource(Res.string.channel_members_cannot_demote_server_admin_message, name),
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_ok),
                onClick = onDismiss,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg,
            )
        },
    )
}

/** Web `channel-member-role-error`: the warning above the roster, with the request's trace id. */
@Composable
internal fun ChannelMemberRoleErrorBanner(traceId: String?) {
    ChannelMembersWarning(stringResource(Res.string.channel_members_update_role_failed))
    RaftTraceIdNote(traceId, Modifier.padding(horizontal = 12.dp).testTag("channel-member-role-error-trace"))
}
