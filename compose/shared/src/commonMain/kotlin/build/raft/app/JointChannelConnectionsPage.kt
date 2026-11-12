package build.raft.app

import build.raft.app.resources.*
import build.raft.app.components.raftUpsellCopy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.JOINT_CHANNEL_FREE_LIMIT_REACHED
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JOINT_INVITEE_NOT_ADMIN
import build.raft.app.data.JOINT_INVITEE_NOT_FOUND
import build.raft.app.data.JOINT_INVITE_RATE_LIMITED
import build.raft.app.data.jointInviteRateLimitMinutes
import build.raft.app.data.JOINT_INVITE_LIMIT_EXCEEDED
import build.raft.app.data.JOINT_INVITE_REQUIRED
import build.raft.app.data.JOINT_SERVER_LIMIT
import build.raft.app.data.JOINT_TARGET_SERVER_INVALID
import build.raft.app.data.JointChannelServer
import build.raft.app.data.JointChannelServerPlan
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.jointSameAdminContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.designsystem.components.RaftTextFieldDefaults
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
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import build.raft.app.base.BridgeModule
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * Connected servers, a section of the channel details (web EditChannelDialog `serversSection`,
 * slock c8849c8b3cfd `packages/web/src/features/channel/EditChannelDialog.tsx:1285-1420`): after
 * the announcement, never a page of its own. The heading (server icon, "Connected servers", an
 * ⓘ whose tooltip is the 30-server rule, the ⋮ with Connect your servers), then one plain row per
 * server: its name with the Host badge beside it, the slug in mono, "This server" and "Connected
 * by you"; at the end the plan and pending badges and the host's Disconnect
 * (JointServerDisconnectAction) with its confirm. With `external_channels_v1` off: plan chips,
 * Active / pending badges, Resend Invite and the "Channel connection invitation" form, as the web.
 *
 * [JointConnectedServersHolder] keeps the store and the invite draft on the details page, outside
 * its lazy list, so scrolling the section away loses neither; the page draws the section with
 * [JointConnectedServersSection] and the confirm and the sheet with [JointConnectedServersOverlays].
 */
@Stable
internal class JointConnectedServersHolder(val store: JointChannelConnectionsStore) {
    var slug by mutableStateOf("")
    var people by mutableStateOf("")
    var sameAdminConfirmed by mutableStateOf(false)

    /** The last finished action's receipt (web: the role="status" line under the form). */
    var statusMessage by mutableStateOf<JointChannelConnectionsEffect?>(null)

    fun changeSlug(value: String) {
        slug = value
        sameAdminConfirmed = false
        statusMessage = null
        store.dispatch(JointChannelConnectionsIntent.ClearInviteFeedback)
    }

    fun changePeople(value: String) {
        people = value
        sameAdminConfirmed = false
        statusMessage = null
        store.dispatch(JointChannelConnectionsIntent.ClearInviteFeedback)
    }

    fun changeSameAdminConfirmed(value: Boolean) {
        sameAdminConfirmed = value
        store.dispatch(JointChannelConnectionsIntent.ClearInviteFeedback)
    }

    fun sendInvite() {
        statusMessage = null
        store.dispatch(JointChannelConnectionsIntent.Invite(slug, people, sameAdminConfirmed))
    }

    fun resend() {
        statusMessage = null
        store.dispatch(JointChannelConnectionsIntent.Resend)
    }
}

/**
 * The section's store for the open channel, loaded while [active] (a joint channel, or a private
 * one offering Connect your servers) and the route's account is the live one. Its effects become
 * the web's toasts and receipt.
 */
@Composable
internal fun rememberJointConnectedServers(
    route: ChannelSettingsRoute,
    routeOwner: AuthenticatedRouteOwner,
    liveOwner: AuthenticatedRouteOwner?,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    externalChannels: Boolean,
    active: Boolean,
): JointConnectedServersHolder {
    val scope = rememberCoroutineScope()
    val authorityGateway = remember(sharedStores, storeScope) { sharedStores.authenticatedRouteAuthorityGateway(storeScope) }
    val holder =
        remember(route, routeOwner, authorityGateway, scope, sharedStores) {
            JointConnectedServersHolder(
                JointChannelConnectionsStore(
                    dataSource =
                        RealJointChannelConnectionsDataSource(
                            route = route,
                            owner = routeOwner,
                            gateway = authorityGateway,
                            logger = { bridge.log("joint_connections_store $it") },
                        ),
                    scope = scope,
                    // Data-layer plan step 5: the first frame is the cached list for this account + channel.
                    cacheSlot = jointChannelConnectionsCacheSlot(sharedStores.jointConnectionsCache, routeOwner, route),
                    // Web "Connected by you": what this account connected here while the app runs.
                    connectedByYouSlot =
                        MemberConnectedByYouSlot(
                            RaftMemberConnectedByYou,
                            "${routeOwner.environmentId}|${routeOwner.accountId}|${route.serverId.value}|${route.channelId.value}",
                        ),
                ),
            )
        }
    val store = holder.store
    val state by store.state.collectAsState()
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    // Web `findSameAdminJointTarget` inputs: the account and its role on each of its servers.
    LaunchedEffect(store, authStore, liveOwner) {
        val session = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        store.dispatch(JointChannelConnectionsIntent.SetSameAdminContext(session?.jointSameAdminContext(route.serverId.value)))
        // Connect your servers: the account's servers and its role on each, as `GET /api/servers` sent them.
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(session?.memberConnectAccount(route.serverId.value)))
    }
    // Web reads external_channels_v1 for the current server: the one whose copy is open.
    LaunchedEffect(store, externalChannels) {
        store.dispatch(JointChannelConnectionsIntent.SetExternalChannelsEnabled(externalChannels))
    }
    LaunchedEffect(store, liveOwner, routeOwner, active) {
        if (active && liveOwner == routeOwner) store.dispatch(JointChannelConnectionsIntent.Refresh)
    }
    val memberConnectToasts = jointMemberConnectToasts(state.effect as? JointChannelConnectionsEffect.MemberConnectFinished)
    LaunchedEffect(state.effect) {
        when (val effect = state.effect) {
            is JointChannelConnectionsEffect.InviteSent -> {
                holder.slug = ""
                holder.people = ""
                holder.sameAdminConfirmed = false
                holder.statusMessage = effect
            }
            is JointChannelConnectionsEffect.Resent -> holder.statusMessage = effect
            is JointChannelConnectionsEffect.ServerDisconnected ->
                bridge.toast(Res.string.joint_server_disconnected_toast.format(effect.serverName, effect.channelName).resolve())
            is JointChannelConnectionsEffect.MemberConnectFinished -> memberConnectToasts.forEach { bridge.toast(it) }
            null -> Unit
        }
        if (state.effect != null) store.dispatch(JointChannelConnectionsIntent.ConsumeEffect)
    }
    return holder
}

/** The section's dialogs: the host's Disconnect confirm and the Connect your servers sheet. */
@Composable
internal fun JointConnectedServersOverlays(holder: JointConnectedServersHolder) {
    val store = holder.store
    val state by store.state.collectAsState()
    state.disconnectConfirmServer?.let { server ->
        JointServerDisconnectConfirmDialog(
            serverName = server.name.ifBlank { server.slug },
            channelName = state.connections?.channelName?.removePrefix("#").orEmpty(),
            busy = state.actionInFlight == JointChannelConnectionsAction.DisconnectServer,
            failure = state.disconnectFailure,
            onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissDisconnectServer) },
            onConfirm = { store.dispatch(JointChannelConnectionsIntent.DisconnectServer) },
        )
    }
    JointMemberConnectSheet(
        state = state,
        onDismiss = { store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect) },
        onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
        onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
    )
}

/** [JointConnectedServersSection] wired to [holder]'s store and draft. */
@Composable
internal fun JointConnectedServersSection(holder: JointConnectedServersHolder, modifier: Modifier = Modifier) {
    val store = holder.store
    val state by store.state.collectAsState()
    JointConnectedServersSection(
        state = state,
        slug = holder.slug,
        people = holder.people,
        sameAdminConfirmed = holder.sameAdminConfirmed,
        statusMessage = holder.statusMessage,
        onSlugChange = holder::changeSlug,
        onPeopleChange = holder::changePeople,
        onSameAdminConfirmedChange = holder::changeSameAdminConfirmed,
        onSendInvite = holder::sendInvite,
        onResend = holder::resend,
        onDisconnectServer = { serverId -> store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer(serverId)) },
        onConnectYourServers = { store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect) },
        onRetry = { store.dispatch(JointChannelConnectionsIntent.Refresh) },
        modifier = modifier,
    )
}

/** Web `serversSection`: `stack-y-3`, the heading, the rows (or why there are none), then Resend and the invite form. */
@Composable
internal fun JointConnectedServersSection(
    state: JointChannelConnectionsState,
    slug: String,
    people: String,
    sameAdminConfirmed: Boolean,
    statusMessage: JointChannelConnectionsEffect?,
    onSlugChange: (String) -> Unit,
    onPeopleChange: (String) -> Unit,
    onSameAdminConfirmedChange: (Boolean) -> Unit,
    onSendInvite: () -> Unit,
    onResend: () -> Unit,
    onDisconnectServer: (serverId: String) -> Unit = {},
    onConnectYourServers: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(JointConnectionsSectionTag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JointConnectedServersHeader(state = state, onConnectYourServers = onConnectYourServers)
        val connections = state.connections
        when {
            connections == null && state.status == JointChannelConnectionsLoadStatus.Error ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftText(
                        state.loadFailure?.let { jointChannelFailureMessage(it, stringResource(Res.string.joint_connections_load_failed)) }
                            ?: stringResource(Res.string.joint_connections_load_failed),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundHint,
                    )
                    RaftTraceIdNote(state.loadFailure?.traceId)
                    RaftButton(text = stringResource(Res.string.common_retry), onClick = onRetry, size = RaftButtonSize.Md)
                }
            connections == null -> RaftSkeleton(Modifier.fillMaxWidth().height(64.dp))
            state.displayServers.isEmpty() ->
                RaftText(
                    stringResource(Res.string.joint_connections_meta_unavailable),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundHint,
                )
            else ->
                RaftListRowGroup(Modifier.fillMaxWidth().testTag(JointConnectionsServerListTag)) {
                    state.displayServers.forEachIndexed { index, server ->
                        JointConnectedServerRow(
                            server = server,
                            state = state,
                            divider = index > 0,
                            onDisconnect = { onDisconnectServer(server.serverId) },
                        )
                    }
                }
        }
        if (connections == null) return@Column
        if (state.canResend) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().testTag(JointConnectionsResendTag)) {
                    RaftButton(
                        text = stringResource(Res.string.joint_connections_resend),
                        onClick = onResend,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        enabled = state.actionInFlight == null,
                        loading = state.actionInFlight == JointChannelConnectionsAction.Resend,
                        loadingLabel = stringResource(Res.string.joint_connections_resending),
                        leadingIcon = { RaftComputersIcon(RaftLucideIconName.Mail) },
                    )
                }
                state.resendFailure?.let { failure ->
                    RaftText(
                        jointChannelFailureMessage(failure, stringResource(Res.string.joint_connections_resend_failed)),
                        style = RaftTheme.typography.caption,
                        color = RaftTheme.colors.dangerStrong,
                    )
                }
            }
        }
        if (state.canManageConnections) {
            JointInviteSection(
                state = state,
                slug = slug,
                people = people,
                sameAdminConfirmed = sameAdminConfirmed,
                onSlugChange = onSlugChange,
                onPeopleChange = onPeopleChange,
                onSameAdminConfirmedChange = onSameAdminConfirmedChange,
                onSendInvite = onSendInvite,
            )
        }
        statusMessage?.let { receipt ->
            RaftBanner(
                description = jointConnectionsReceiptText(receipt),
                status = RaftBannerStatus.Success,
                size = RaftBannerSize.Sm,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Web `SectionHeader` of Connected servers: the server icon and the name, no count; at the end the
 * ⓘ (its tooltip the 30-server rule, "a fact the name doesn't say") and, where the viewer has a
 * server to connect, the ⋮ (ConnectedServersMenu). A phone has no hover: a tap opens the rule.
 */
@Composable
private fun JointConnectedServersHeader(state: JointChannelConnectionsState, onConnectYourServers: () -> Unit) {
    val rule = stringResource(Res.string.joint_connections_rule)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftSectionEyebrow(
            text = stringResource(Res.string.joint_connections_title),
            modifier = Modifier.weight(1f),
            icon = { RaftComputersIcon(RaftLucideIconName.Server, size = 16.dp) },
        )
        RaftTooltip(text = rule, openOnTap = true) {
            RaftIconButton(
                onClick = {},
                tooltip = null,
                contentDescription = rule,
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
                modifier = Modifier.testTag(JointConnectionsRuleTag),
            ) {
                RaftComputersIcon(RaftLucideIconName.Info, size = 18.dp)
            }
        }
        // Web ConnectedServersMenu: drawn only where the viewer has a server to connect.
        if (state.memberConnectAvailable) {
            JointMemberConnectMenu(enabled = state.actionInFlight == null, onConnectYourServers = onConnectYourServers)
        }
    }
}

/** Web ConnectYourServersDialog's toasts once every chosen server was answered well. */
@Composable
internal fun jointMemberConnectToasts(effect: JointChannelConnectionsEffect.MemberConnectFinished?): List<String> {
    effect ?: return emptyList()
    val connected = jointMemberConnectNames(effect.connected)
    val connecting = jointMemberConnectNames(effect.connecting)
    val already = jointMemberConnectNames(effect.alreadyConnected)
    return listOfNotNull(
        stringResource(Res.string.joint_member_connect_toast_connected, connected).takeIf { effect.connected.isNotEmpty() },
        when {
            effect.connecting.isEmpty() -> null
            effect.connecting.size == 1 -> stringResource(Res.string.joint_member_connect_toast_connecting_one, connecting)
            else -> stringResource(Res.string.joint_member_connect_toast_connecting_many, connecting)
        },
        when {
            effect.alreadyConnected.isEmpty() -> null
            effect.alreadyConnected.size == 1 -> stringResource(Res.string.joint_member_connect_toast_already_connected_one, already)
            else -> stringResource(Res.string.joint_member_connect_toast_already_connected_many, already)
        },
    )
}

@Composable
private fun jointConnectionsReceiptText(receipt: JointChannelConnectionsEffect): String =
    when (receipt) {
        is JointChannelConnectionsEffect.InviteSent -> {
            // Web: "Connected X directly." first, then the emails sent to anyone else on the list.
            val connected = receipt.connectedServerName?.let { stringResource(Res.string.joint_same_admin_connected, it) }
            val emailed =
                when {
                    receipt.emailedCount <= 0 && connected != null -> null
                    receipt.emailedCount == 1 -> stringResource(Res.string.joint_connections_invite_sent_one)
                    else -> stringResource(Res.string.joint_connections_invite_sent_many, receipt.emailedCount)
                }
            listOfNotNull(connected, emailed).joinToString(" ")
        }
        is JointChannelConnectionsEffect.Resent ->
            if (receipt.resentCount == 1) {
                stringResource(Res.string.joint_connections_resent_one)
            } else {
                stringResource(Res.string.joint_connections_resent_many, receipt.resentCount)
            }
        // A toast, not the status line (never kept as [statusMessage]).
        is JointChannelConnectionsEffect.ServerDisconnected ->
            stringResource(Res.string.joint_server_disconnected_toast, receipt.serverName, receipt.channelName)
        // Toasts too (never kept as [statusMessage]).
        is JointChannelConnectionsEffect.MemberConnectFinished -> jointMemberConnectToasts(receipt).joinToString(" ")
    }

internal const val JointConnectionsSectionTag = "joint-connections-section"
internal const val JointConnectionsServerListTag = "joint-connections-servers"
internal const val JointConnectionsRuleTag = "joint-connections-rule"
internal const val JointConnectionsHostBadgeTag = "joint-connections-host-badge"
internal const val JointConnectionsStatusBadgeTag = "joint-connections-status-badge"
internal const val JointConnectionsPlanBadgeTag = "joint-connections-plan-badge"
internal const val JointConnectionsDisconnectTag = "joint-connections-disconnect"
internal const val JointConnectionsInviteTag = "joint-connections-invite"
internal const val JointConnectionsResendTag = "joint-connections-resend"
internal const val JointConnectionsDisconnectConfirmTag = "joint-connections-disconnect-confirm"

/**
 * Web's server row (`ListRow variant="plain"`, not a target): `ListRowTop` the name with the Host
 * badge right after it; `ListRowDescription` the slug in mono (an id), then " · This server" and
 * " · Connected by you" in words. At the end the plan chip (flag off), the pending badge and the
 * host's Disconnect, always visible on touch (no hover there).
 */
@Composable
private fun JointConnectedServerRow(
    server: JointChannelServer,
    state: JointChannelConnectionsState,
    divider: Boolean,
    onDisconnect: () -> Unit,
) {
    val thisServer = stringResource(Res.string.joint_connections_this_server)
    val connectedByYou = stringResource(Res.string.joint_member_connect_row_connected_by_you)
    val serverName = server.name.ifBlank { server.slug }
    val mono = RaftTheme.typography.monoMeta.toSpanStyle()
    val description =
        buildAnnotatedString {
            if (server.slug.isNotBlank()) withStyle(mono) { append(server.slug) }
            listOfNotNull(
                thisServer.takeIf { server.isCurrentServer },
                connectedByYou.takeIf { server.serverId in state.connectedByYou },
            ).forEach { words ->
                if (length > 0) append(" · ")
                append(words)
            }
        }
    RaftListRow(
        title = serverName,
        variant = RaftListRowVariant.Plain,
        divider = divider,
        richDescription = description.takeIf { it.isNotEmpty() },
        titleTrailing =
            if (state.showsHostBadge(server)) {
                {
                    RaftBadge(
                        text = stringResource(Res.string.joint_connections_role_host),
                        appearance = RaftBadgeAppearance.Outline,
                        modifier = Modifier.testTag(JointConnectionsHostBadgeTag),
                    )
                }
            } else {
                null
            },
        trailing = {
            server.plan?.takeIf { state.showsPlanBadge(server) }?.let { plan ->
                RaftBadge(
                    text =
                        stringResource(
                            if (plan == JointChannelServerPlan.Paid) {
                                Res.string.joint_connections_plan_paid
                            } else {
                                Res.string.joint_connections_plan_free
                            }
                        ),
                    appearance = RaftBadgeAppearance.Outline,
                    modifier = Modifier.testTag(JointConnectionsPlanBadgeTag),
                )
            }
            if (state.showsStatusBadge(server)) {
                RaftBadge(
                    text = stringResource(Res.string.joint_connections_status_pending),
                    appearance = RaftBadgeAppearance.Soft,
                    variant = RaftBadgeVariant.Warning,
                    modifier = Modifier.testTag(JointConnectionsStatusBadgeTag),
                )
            }
            // Web JointServerDisconnectAction: "Disconnect server" in the tooltip, "Disconnect {server}" to a screen reader.
            if (state.canDisconnectServer(server)) {
                RaftIconButton(
                    onClick = onDisconnect,
                    tooltip = stringResource(Res.string.joint_server_disconnect_tooltip),
                    contentDescription = stringResource(Res.string.joint_server_disconnect_action, serverName),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Md,
                    enabled = state.actionInFlight == null,
                    modifier = Modifier.testTag(JointConnectionsDisconnectTag),
                ) {
                    RaftComputersIcon(RaftLucideIconName.Unplug, size = 20.dp)
                }
            }
        },
    )
}

/**
 * Web JointServerDisconnectAction's ConfirmDialog: "Disconnect {server}?", what happens to that
 * server's copy, Cancel and a red "Disconnect {server}". A refusal stays in the dialog as one line
 * (web `channel.edit.disconnectServerFailed`) with the request's trace ID.
 */
@Composable
internal fun JointServerDisconnectConfirmDialog(
    serverName: String,
    channelName: String,
    busy: Boolean,
    failure: JointChannelFailure?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = stringResource(Res.string.joint_server_disconnect_title, serverName),
            description = stringResource(Res.string.joint_server_disconnect_confirm, serverName, channelName),
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
                    text = stringResource(Res.string.joint_server_disconnect_action, serverName),
                    onClick = onConfirm,
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Lg,
                    loading = busy,
                    loadingLabel = stringResource(Res.string.joint_disconnecting),
                    modifier = Modifier.testTag(JointConnectionsDisconnectConfirmTag),
                )
            },
            content =
                failure?.let { error ->
                    {
                        RaftText(
                            stringResource(Res.string.joint_server_disconnect_failed, serverName),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.dangerStrong,
                        )
                        RaftTraceIdNote(error.traceId)
                    }
                },
        )
    }
}

private val JointChannelFailure.traceId: String?
    get() =
        when (this) {
            is JointChannelFailure.Error -> error.traceId
            is JointChannelFailure.Blocked -> blocker.traceId
        }

@Composable
private fun JointInviteSection(
    state: JointChannelConnectionsState,
    slug: String,
    people: String,
    sameAdminConfirmed: Boolean,
    onSlugChange: (String) -> Unit,
    onPeopleChange: (String) -> Unit,
    onSameAdminConfirmedChange: (Boolean) -> Unit,
    onSendInvite: () -> Unit,
) {
    val connections = state.connections ?: return
    val sameAdminTarget = state.sameAdminTarget(slug, people)
    val inviting = state.actionInFlight == JointChannelConnectionsAction.Invite
    val failure = state.inviteFailure
    val failureField = failure?.let { jointInviteFailureField(it.code) }
    val failureText = failure?.let { jointChannelFailureMessage(it, stringResource(Res.string.joint_connections_invite_failed)) }
    val slugError =
        when {
            JointInviteValidation.SlugRequired in state.inviteValidation -> stringResource(Res.string.joint_connections_slug_required)
            JointInviteValidation.SlugPattern in state.inviteValidation -> stringResource(Res.string.joint_connections_slug_pattern)
            failureField == JointInviteField.Slug -> failureText
            else -> null
        }
    val peopleError =
        when {
            JointInviteValidation.PeopleRequired in state.inviteValidation -> stringResource(Res.string.joint_connections_people_required)
            failureField == JointInviteField.People -> failureText
            else -> null
        }
    val formError =
        when {
            JointInviteValidation.ServerLimitReached in state.inviteValidation ->
                stringResource(Res.string.joint_connections_max_servers, JOINT_CHANNEL_MAX_SERVERS)
            JointInviteValidation.SameAdminConfirmRequired in state.inviteValidation ->
                stringResource(Res.string.joint_same_admin_confirm_required)
            failureField == JointInviteField.Form -> failureText
            else -> null
        }
    // Web `channel-settings-joint-invite`: its eyebrow, then 12 under it the form (or the limit).
    Column(Modifier.fillMaxWidth().testTag(JointConnectionsInviteTag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftSectionEyebrow(text = stringResource(Res.string.joint_connections_invite_section))
        if (connections.serverLimitReached) {
            RaftText(
                stringResource(Res.string.joint_connections_max_servers, JOINT_CHANNEL_MAX_SERVERS),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundHint,
            )
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftField(
                label = stringResource(Res.string.joint_connections_slug_label),
                required = true,
                error = slugError,
                enabled = !inviting,
            ) {
                RaftTextField(
                    value = slug,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = onSlugChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(Res.string.joint_connections_slug_placeholder),
                )
            }
            RaftField(
                label = stringResource(Res.string.joint_connections_people_label),
                required = true,
                description = stringResource(Res.string.joint_connections_people_hint),
                error = peopleError,
                enabled = !inviting,
            ) {
                RaftTextarea(
                    value = people,
                    onValueChange = onPeopleChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(Res.string.joint_connections_people_placeholder),
                )
            }
            sameAdminTarget?.let { target ->
                JointSameAdminNotice(
                    sourceServerName = state.sameAdminContext?.currentServerName.orEmpty(),
                    targetServerName = target.name,
                    confirmed = sameAdminConfirmed,
                    onConfirmedChange = onSameAdminConfirmedChange,
                    enabled = !inviting,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RaftButton(
                    text = stringResource(Res.string.joint_connections_send_invite),
                    onClick = onSendInvite,
                    enabled = state.actionInFlight == null && slug.isNotBlank() && people.isNotBlank(),
                    loading = inviting,
                    loadingLabel = stringResource(Res.string.joint_connections_inviting),
                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.Mail) },
                )
            }
            formError?.let { message ->
                RaftBanner(description = message, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

/**
 * Web `channel.jointInvite.error.rateLimited`: "Too many invites in a short time. Try again in X
 * minutes." with X from [jointInviteRateLimitMinutes]; "later" when the response named no wait.
 */
@Composable
internal fun jointInviteRateLimitedMessage(retryAfterSeconds: Long?): String =
    when (val minutes = jointInviteRateLimitMinutes(retryAfterSeconds)) {
        null -> stringResource(Res.string.joint_invite_rate_limited_later)
        1 -> stringResource(Res.string.joint_invite_rate_limited_one)
        else -> stringResource(Res.string.joint_invite_rate_limited_other, minutes)
    }

/** Localized copy for each joint failure, with the web's precedence. */
internal data class JointFailureCopy(
    val freeServerLimit: String,
    val serverLimit: String,
    val hostFreeLimit: String,
    val fallback: String,
    /** `joint_invite_rate_limited`: the wait in minutes, or null for "try again later". */
    val inviteRateLimited: (minutes: Int?) -> String,
)

private val JOINT_VALIDATION_CODES =
    setOf(
        JOINT_TARGET_SERVER_INVALID,
        JOINT_INVITEE_NOT_FOUND,
        JOINT_INVITEE_NOT_ADMIN,
        JOINT_INVITE_REQUIRED,
        JOINT_INVITE_LIMIT_EXCEEDED,
    )

/**
 * Web `jointInviteAcceptErrorMessage` / EditChannelDialog invite errors: limit codes get their
 * localized copy (the Pro upgrade guidance lives in the free-server copy), invite validation codes
 * show the server's own sentence, anything else goes through the app's error pipeline.
 */
internal fun jointChannelFailureText(
    failure: JointChannelFailure,
    copy: JointFailureCopy,
    pipeline: (JointChannelFailure) -> String,
): String =
    when (failure.code) {
        JOINT_FREE_SERVER_LIMIT -> copy.freeServerLimit
        JOINT_SERVER_LIMIT -> copy.serverLimit
        JOINT_CHANNEL_FREE_LIMIT_REACHED -> copy.hostFreeLimit
        JOINT_INVITE_RATE_LIMITED ->
            copy.inviteRateLimited(jointInviteRateLimitMinutes((failure as? JointChannelFailure.Error)?.error?.retryAfterSeconds))
        in JOINT_VALIDATION_CODES -> failure.serverMessage()?.takeIf(String::isNotBlank) ?: copy.fallback
        else -> pipeline(failure).takeIf(String::isNotBlank) ?: copy.fallback
    }

private fun JointChannelFailure.serverMessage(): String? =
    when (this) {
        is JointChannelFailure.Error -> error.serverMessage
        is JointChannelFailure.Blocked -> blocker.serverMessage
    }

@Composable
internal fun jointChannelFailureMessage(failure: JointChannelFailure, fallback: String): String {
    val strings = localizedErrorPresentationStrings()
    val rateLimited = jointInviteRateLimitedMessage((failure as? JointChannelFailure.Error)?.error?.retryAfterSeconds)
    val copy =
        JointFailureCopy(
            freeServerLimit = stringResource(raftUpsellCopy(Res.string.joint_limit_free_servers)),
            serverLimit = stringResource(Res.string.joint_limit_servers, JOINT_CHANNEL_MAX_SERVERS),
            hostFreeLimit = stringResource(Res.string.joint_limit_host_free),
            fallback = fallback,
            inviteRateLimited = { rateLimited },
        )
    return jointChannelFailureText(failure, copy) { value ->
        when (value) {
            is JointChannelFailure.Error -> value.error.userFacingMessage(strings)
            is JointChannelFailure.Blocked -> value.blocker.userFacingMessage(strings)
        }
    }
}
