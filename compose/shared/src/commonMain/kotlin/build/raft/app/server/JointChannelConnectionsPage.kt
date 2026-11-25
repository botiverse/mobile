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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.JOINT_CHANNEL_PLAN_LIMIT_REACHED
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JOINT_INVITE_ACCEPT_FORBIDDEN
import build.raft.app.data.JOINT_INVITE_CHANNEL_ARCHIVED
import build.raft.app.data.JOINT_INVITE_NOT_ACCEPTABLE
import build.raft.app.data.JOINT_INVITE_NOT_FOUND
import build.raft.app.data.JOINT_SERVER_LIMIT
import build.raft.app.data.JointChannelServer
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ResolvedServerRefusal
import build.raft.app.data.ScreenError
import build.raft.app.data.ServerRefusalInput
import build.raft.app.data.isAuthSessionErrorCode
import build.raft.app.data.isEdgeBlockedMarker
import build.raft.app.data.resolveServerRefusal
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import build.raft.app.base.BridgeModule
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ChannelConversionIntent
import build.raft.app.data.ChannelConversionKey
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.JointChannelConnections
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.launch
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
 * by you"; at the end the pending badge and the host's Disconnect (JointServerDisconnectAction)
 * with its confirm. Another server joins through the external channel flow only: there is no
 * server invite form or Resend (botiverse/slock #15939).
 *
 * [JointConnectedServersHolder] keeps the store on the details page, outside its lazy list, so
 * scrolling the section away loses nothing; the page draws the section with
 * [JointConnectedServersSection] and the confirm and the sheet with [JointConnectedServersOverlays].
 */
@Stable
internal class JointConnectedServersHolder(val store: JointChannelConnectionsStore)

/**
 * Web `useChannelConversionInProgress` (slock #16389): observes [SharedChannelConversionStore] for
 * `(serverId, channelId)` and triggers a background read once while [active] if not yet read.
 */
@Composable
internal fun rememberChannelConversionInProgress(
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    serverId: ServerId,
    channelId: ChannelId,
    active: Boolean,
): Boolean {
    val conversionStore = remember(sharedStores, storeScope) { sharedStores.channelConversionStore(storeScope) }
    val key = remember(serverId, channelId) { ChannelConversionKey(serverId.value, channelId.value) }
    val slice by remember(conversionStore, key) { conversionStore.slice(key) }.collectAsState(conversionStore.current(key))
    LaunchedEffect(conversionStore, key, active, slice.read) {
        if (active && !slice.read) {
            conversionStore.dispatch(ChannelConversionIntent.Load(key, "connect_entry", DataRequestPriority.Background))
        }
    }
    return slice.isConversionInProgress
}

/**
 * The section's store for the open channel, loaded while [active] (a joint channel, a private one
 * offering Connect your servers, or a Guest's Connect your servers entry) and the route's account
 * is the live one. Its effects become the web's toasts.
 */
@Composable
internal fun rememberJointConnectedServers(
    route: ChannelSettingsRoute,
    routeOwner: AuthenticatedRouteOwner,
    liveOwner: AuthenticatedRouteOwner?,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    bridge: BridgeModule,
    active: Boolean,
    seedConnections: JointChannelConnections? = null,
    onConnectionsChanged: () -> Unit = {},
    onServersChanged: () -> Unit = {},
    onMemberConnectFinished: () -> Unit = {},
): JointConnectedServersHolder {
    val scope = rememberCoroutineScope()
    val authorityGateway = remember(sharedStores, storeScope) { sharedStores.authenticatedRouteAuthorityGateway(storeScope) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val latestOnConnectionsChanged = androidx.compose.runtime.rememberUpdatedState(onConnectionsChanged)
    val latestOnServersChanged = androidx.compose.runtime.rememberUpdatedState(onServersChanged)
    val latestOnMemberConnectFinished = androidx.compose.runtime.rememberUpdatedState(onMemberConnectFinished)
    val holder =
        remember(route, routeOwner, authorityGateway, scope, sharedStores) {
            var storeRef: JointChannelConnectionsStore? = null
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
                    onConnectionsChanged = { latestOnConnectionsChanged.value() },
                    onServersChanged = {
                        scope.launch {
                            val authDataSource = sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope)
                            val refreshed = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()
                            val session =
                                (refreshed as? SharedAuthResult.Success<StoredAuthSession>)?.data
                                    ?: runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
                            storeRef?.dispatch(
                                JointChannelConnectionsIntent.SetMemberConnectAccount(session?.memberConnectAccount(route.serverId.value))
                            )
                            latestOnServersChanged.value()
                        }
                    },
                ).also { storeRef = it },
            )
        }
    val store = holder.store
    val state by store.state.collectAsState()
    // Connect your servers: the account's servers and its role on each, as `GET /api/servers` sent them.
    LaunchedEffect(store, authStore, liveOwner) {
        val session = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        store.dispatch(JointChannelConnectionsIntent.SetMemberConnectAccount(session?.memberConnectAccount(route.serverId.value)))
    }
    LaunchedEffect(store, seedConnections) {
        if (seedConnections != null) store.dispatch(JointChannelConnectionsIntent.SeedChannel(seedConnections))
    }
    LaunchedEffect(store, liveOwner, routeOwner, active) {
        if (active && liveOwner == routeOwner) store.dispatch(JointChannelConnectionsIntent.Refresh)
    }
    val memberConnectToasts = jointMemberConnectToasts(state.effect as? JointChannelConnectionsEffect.MemberConnectFinished)
    LaunchedEffect(state.effect) {
        when (val effect = state.effect) {
            is JointChannelConnectionsEffect.ServerDisconnected ->
                bridge.toast(Res.string.joint_server_disconnected_toast.format(effect.serverName, effect.channelName).resolve())
            is JointChannelConnectionsEffect.MemberConnectFinished -> {
                memberConnectToasts.forEach { bridge.toast(it) }
                latestOnMemberConnectFinished.value()
            }
            null -> Unit
        }
        if (state.effect != null) store.dispatch(JointChannelConnectionsIntent.ConsumeEffect)
    }
    return holder
}

/** The section's dialogs: the host's Disconnect confirm and the Connect your servers sheet. */
@Composable
internal fun JointConnectedServersOverlays(
    holder: JointConnectedServersHolder,
    onBack: (() -> Unit)? = null,
    onUpgradeServer: (serverId: String) -> Unit = {},
) {
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
        onBack = onBack,
        onSelectionChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectSelection(it)) },
        onSubmit = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnect) },
        onUpgradeServer = { serverId ->
            store.dispatch(JointChannelConnectionsIntent.DismissMemberConnect)
            onUpgradeServer(serverId)
        },
        onOpenCreateServer = { store.dispatch(JointChannelConnectionsIntent.OpenMemberConnectNewServer) },
        onCloseCreateServer = { store.dispatch(JointChannelConnectionsIntent.CloseMemberConnectNewServer) },
        onNewServerNameChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectNewServerName(it)) },
        onNewServerSlugChange = { store.dispatch(JointChannelConnectionsIntent.SetMemberConnectNewServerSlug(it)) },
        onSubmitNewServer = { store.dispatch(JointChannelConnectionsIntent.SubmitMemberConnectNewServer) },
    )
}

/** [JointConnectedServersSection] wired to [holder]'s store. */
@Composable
internal fun JointConnectedServersSection(holder: JointConnectedServersHolder, modifier: Modifier = Modifier) {
    val store = holder.store
    val state by store.state.collectAsState()
    JointConnectedServersSection(
        state = state,
        onDisconnectServer = { serverId -> store.dispatch(JointChannelConnectionsIntent.ConfirmDisconnectServer(serverId)) },
        onConnectYourServers = { store.dispatch(JointChannelConnectionsIntent.OpenMemberConnect) },
        onRetry = { store.dispatch(JointChannelConnectionsIntent.Refresh) },
        modifier = modifier,
    )
}

/** Web `serversSection`: `stack-y-3`, the heading, then the rows (or why there are none). */
@Composable
internal fun JointConnectedServersSection(
    state: JointChannelConnectionsState,
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
    }
}

/**
 * Web `SectionHeader` of Connected servers: the server icon and the name, no count; at the end the
 * ⓘ (its tooltip the external channel size rule, "a fact the name doesn't say") and, where the viewer has a
 * server to connect, the ⋮ (ConnectedServersMenu). A phone has no hover: a tap opens the rule.
 */
@Composable
private fun JointConnectedServersHeader(state: JointChannelConnectionsState, onConnectYourServers: () -> Unit) {
    val rule = stringResource(Res.string.joint_connections_rule, JOINT_CHANNEL_MAX_SERVERS)
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

internal const val JointConnectionsSectionTag = "joint-connections-section"
internal const val JointConnectionsServerListTag = "joint-connections-servers"
internal const val JointConnectionsRuleTag = "joint-connections-rule"
internal const val JointConnectionsHostBadgeTag = "joint-connections-host-badge"
internal const val JointConnectionsStatusBadgeTag = "joint-connections-status-badge"
internal const val JointConnectionsDisconnectTag = "joint-connections-disconnect"
internal const val JointConnectionsDisconnectConfirmTag = "joint-connections-disconnect-confirm"

/**
 * Web's server row (`ListRow variant="plain"`, not a target): `ListRowTop` the name with the Host
 * badge right after it; `ListRowDescription` the slug in mono (an id), then " · This server" and
 * " · Connected by you" in words. At the end the pending badge and the host's Disconnect, always
 * visible on touch (no hover there).
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

/**
 * Localized copy for joint-invite accept refusal codes (`botiverse/slock #16117`,
 * `jointChannelLimit.ts` `jointInviteAcceptRefusalMessageId`).
 */
internal data class JointInviteAcceptRefusalCopy(
    val notFound: String,
    val forbidden: String,
    val notAcceptable: String,
    val archived: String,
)

/** Localized copy for each joint failure, with the web's precedence. */
internal data class JointFailureCopy(
    val freeServerLimit: String,
    val serverLimit: String,
    /** The host is at its plan's hosted-joint limit (web `channel.joint.inviteHostFreeLimit`). */
    val hostLimit: String,
    val fallback: String,
    /** Non-null on the invite-accept surface so `#16117` accept refusal codes get specific copy. */
    val acceptRefusals: JointInviteAcceptRefusalCopy? = null,
)

private enum class KnownJointFailureCode {
    FreeServerLimit,
    ServerLimit,
    HostLimit,
    InviteNotFound,
    InviteForbidden,
    InviteNotAcceptable,
    InviteArchived,
    NonAcceptArchived,
}

private fun JointChannelFailure.toServerRefusalInput(): ServerRefusalInput =
    when (this) {
        is JointChannelFailure.Error -> {
            val status =
                when {
                    error.code == "server_unavailable" -> 500
                    error.code.startsWith("joint_http_") -> error.code.removePrefix("joint_http_").toIntOrNull()
                    else -> null
                }
            ServerRefusalInput(
                status = status,
                code = if (status == 0) null else error.code,
                serverMessage = error.serverMessage,
                traceId = error.traceId,
            )
        }
        is JointChannelFailure.Blocked ->
            ServerRefusalInput(
                status = 403,
                code = blocker.code,
                serverMessage = blocker.serverMessage,
                traceId = blocker.traceId,
            )
    }

private fun JointChannelFailure.withoutServerMessage(): JointChannelFailure =
    when (this) {
        is JointChannelFailure.Error -> JointChannelFailure.Error(error.copy(serverMessage = null))
        is JointChannelFailure.Blocked -> JointChannelFailure.Blocked(blocker.copy(serverMessage = null))
    }

private fun JointChannelFailure.scrubbedForInfrastructure(resolved: ResolvedServerRefusal.Infrastructure): JointChannelFailure {
    val rawServerMessage =
        when (this) {
            is JointChannelFailure.Error -> error.serverMessage
            is JointChannelFailure.Blocked -> blocker.serverMessage
        }
    return when {
        resolved.code == "edge_blocked" || rawServerMessage.isEdgeBlockedMarker() ->
            JointChannelFailure.Error(ScreenError(code = "edge_blocked", serverMessage = "edge_blocked", traceId = resolved.traceId))
        resolved.status == 401 || resolved.code.isAuthSessionErrorCode() ->
            JointChannelFailure.Blocked(
                PermissionBlocker(
                    code = "auth_required",
                    requiredCapability = "auth_session",
                    serverMessage = null,
                    traceId = resolved.traceId,
                )
            )
        else -> withoutServerMessage()
    }
}

/**
 * Web `jointInviteAcceptErrorMessage` routed through [resolveServerRefusal]:
 * - Tier 1 (infrastructure/auth/edge) scrubs raw body and falls back to [pipeline] / [JointFailureCopy.fallback].
 * - Tier 2 maps known limit and (when [JointFailureCopy.acceptRefusals] is supplied) joint-invite
 *   accept refusal codes (`#16117`) to localized web copy.
 * - Tier 3 preserves usable server-worded refusal explanations for unknown non-5xx codes.
 * - Tier 4 strips rejected/5xx raw server text and falls back to [pipeline] / [JointFailureCopy.fallback].
 */
internal fun jointChannelFailureText(
    failure: JointChannelFailure,
    copy: JointFailureCopy,
    pipeline: (JointChannelFailure) -> String,
): String {
    val acceptCopy = copy.acceptRefusals
    val resolved =
        resolveServerRefusal(failure.toServerRefusalInput()) { normalizedCode, _ ->
            when (normalizedCode) {
                JOINT_FREE_SERVER_LIMIT -> KnownJointFailureCode.FreeServerLimit
                JOINT_SERVER_LIMIT -> KnownJointFailureCode.ServerLimit
                JOINT_CHANNEL_PLAN_LIMIT_REACHED -> KnownJointFailureCode.HostLimit
                JOINT_INVITE_NOT_FOUND -> KnownJointFailureCode.InviteNotFound.takeIf { acceptCopy != null }
                JOINT_INVITE_ACCEPT_FORBIDDEN -> KnownJointFailureCode.InviteForbidden.takeIf { acceptCopy != null }
                JOINT_INVITE_NOT_ACCEPTABLE -> KnownJointFailureCode.InviteNotAcceptable.takeIf { acceptCopy != null }
                JOINT_INVITE_CHANNEL_ARCHIVED ->
                    if (acceptCopy != null) KnownJointFailureCode.InviteArchived else KnownJointFailureCode.NonAcceptArchived
                else -> null
            }
        }
    return when (resolved) {
        is ResolvedServerRefusal.KnownCode ->
            when (resolved.known) {
                KnownJointFailureCode.FreeServerLimit -> copy.freeServerLimit
                KnownJointFailureCode.ServerLimit -> copy.serverLimit
                KnownJointFailureCode.HostLimit -> copy.hostLimit
                KnownJointFailureCode.InviteNotFound -> acceptCopy!!.notFound
                KnownJointFailureCode.InviteForbidden -> acceptCopy!!.forbidden
                KnownJointFailureCode.InviteNotAcceptable -> acceptCopy!!.notAcceptable
                KnownJointFailureCode.InviteArchived -> acceptCopy!!.archived
                KnownJointFailureCode.NonAcceptArchived ->
                    pipeline(failure.withoutServerMessage()).takeIf(String::isNotBlank) ?: copy.fallback
            }
        is ResolvedServerRefusal.ServerWords -> resolved.message
        is ResolvedServerRefusal.Infrastructure ->
            pipeline(failure.scrubbedForInfrastructure(resolved)).takeIf(String::isNotBlank) ?: copy.fallback
        is ResolvedServerRefusal.Generic ->
            pipeline(failure.withoutServerMessage()).takeIf(String::isNotBlank) ?: copy.fallback
    }
}

@Composable
internal fun jointChannelFailureMessage(failure: JointChannelFailure, fallback: String): String {
    val strings = localizedErrorPresentationStrings()
    val copy =
        JointFailureCopy(
            freeServerLimit = stringResource(raftUpsellCopy(Res.string.joint_limit_free_servers)),
            serverLimit = stringResource(Res.string.joint_limit_servers, JOINT_CHANNEL_MAX_SERVERS),
            hostLimit = stringResource(Res.string.joint_limit_host_free),
            fallback = fallback,
        )
    return jointChannelFailureText(failure, copy) { value ->
        when (value) {
            is JointChannelFailure.Error -> value.error.userFacingMessage(strings)
            is JointChannelFailure.Blocked -> value.blocker.userFacingMessage(strings)
        }
    }
}
