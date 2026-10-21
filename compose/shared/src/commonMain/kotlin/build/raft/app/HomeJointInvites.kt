package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.PendingJointInvite
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedJointInviteEffect
import build.raft.app.data.SharedJointInviteIntent
import build.raft.app.data.SharedJointInviteState
import build.raft.app.data.SharedJointInviteStore
import build.raft.app.data.jointInviteHomeNotificationId
import build.raft.app.model.ConversationKind
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.flow.MutableStateFlow

internal class HomeJointInvitesUi(
    /** Notification-center rows, listed before the other notifications (web "0a"). */
    val items: List<RaftHomeNotificationItem>,
    val refresh: () -> Unit,
)

/** Web `usePendingJointInvites`: owners/admins of the current server can be invited and accept. */
internal fun homeJointInviteAudience(content: SharedHomeContentState): Pair<String?, Boolean> {
    val server = (content as? SharedHomeContentState.Content)?.snapshot?.server
    return server?.id?.value to (server?.permissions?.canManageSettings == true)
}

/** The rows for the current server only; a list still owned by another server shows nothing. */
internal fun SharedJointInviteState.homeJointInviteRows(currentServerId: String?): List<PendingJointInvite> =
    if (currentServerId != null && serverId == currentServerId) visibleInvites else emptyList()

/**
 * Pending joint-channel invites in the Home notification center (web `useSystemNotifications`
 * "Pending joint channel invites"): title "Invitation to join #channel", body "<server> invited
 * this server to a joint channel.", action Accept. Accepting refreshes Home and opens the joined
 * channel; a failure toasts the web's accept error copy.
 */
@Composable
internal fun rememberHomeJointInvites(
    bridge: build.raft.app.base.BridgeModule,
    store: SharedJointInviteStore?,
    content: SharedHomeContentState,
    active: Boolean,
    onIntent: (SharedHomeIntent) -> Unit,
    onOpenConversation: (ThreadLaunchConversationIdentity) -> Unit,
): HomeJointInvitesUi {
    val fallbackFlow = remember { MutableStateFlow(SharedJointInviteState()) }
    val state by (store?.state ?: fallbackFlow).collectAsState()
    val (serverId, canBeInvited) = homeJointInviteAudience(content)
    val latestIntent by rememberUpdatedState(onIntent)
    val latestOpenConversation by rememberUpdatedState(onOpenConversation)
    LaunchedEffect(store, active, serverId, canBeInvited) {
        if (active) store?.dispatch(SharedJointInviteIntent.Load(serverId, canBeInvited))
    }
    val failureCopy =
        JointFailureCopy(
            freeServerLimit = stringResource(MR.strings.joint_limit_free_servers),
            serverLimit = stringResource(MR.strings.joint_limit_servers, JOINT_CHANNEL_MAX_SERVERS),
            hostFreeLimit = stringResource(MR.strings.joint_limit_host_free),
            fallback = stringResource(MR.strings.joint_invite_accept_failed),
        )
    val errorStrings = localizedErrorPresentationStrings()
    LaunchedEffect(store, state.effect) {
        when (val effect = state.effect) {
            is SharedJointInviteEffect.Accepted -> {
                latestIntent(SharedHomeIntent.Refresh)
                latestOpenConversation(
                    buildThreadLaunchConversationIdentity(
                        kind = ConversationKind.Joint,
                        channelId = effect.channel.channelId,
                        channelType = "joint",
                    )
                )
            }
            is SharedJointInviteEffect.AcceptFailed -> {
                val failure = effect.result.toJointChannelFailure()
                bridge.toast(
                    if (failure == null) {
                        failureCopy.fallback
                    } else {
                        jointChannelFailureText(failure, failureCopy) { value ->
                            when (value) {
                                is JointChannelFailure.Error -> value.error.userFacingMessage(errorStrings)
                                is JointChannelFailure.Blocked -> value.blocker.userFacingMessage(errorStrings)
                            }
                        }
                    }
                )
            }
            null -> Unit
        }
        if (state.effect != null) store?.dispatch(SharedJointInviteIntent.ConsumeEffect)
    }
    val accept = stringResource(MR.strings.joint_invite_notification_accept)
    val accepting = stringResource(MR.strings.joint_invite_notification_accepting)
    val titleRes = MR.strings.joint_invite_notification_title
    val bodyRes = MR.strings.joint_invite_notification_body
    val rows = state.homeJointInviteRows(serverId)
    val items =
        rows.map { invite ->
            RaftHomeNotificationItem(
                id = jointInviteHomeNotificationId(invite.id),
                title = stringResource(titleRes, invite.channelName.removePrefix("#")),
                body = stringResource(bodyRes, invite.fromServerName),
                actionLabel = if (state.acceptingInviteId == invite.id) accepting else accept,
                kind = RaftHomeNotificationKind.Info,
                icon = RaftHomeNotificationIcon.Info,
            )
        }
    return HomeJointInvitesUi(
        items = items,
        refresh = { store?.dispatch(SharedJointInviteIntent.Load(serverId, canBeInvited)) },
    )
}
