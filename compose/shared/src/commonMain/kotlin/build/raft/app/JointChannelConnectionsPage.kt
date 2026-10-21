package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.JOINT_CHANNEL_FREE_LIMIT_REACHED
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JOINT_INVITEE_NOT_ADMIN
import build.raft.app.data.JOINT_INVITEE_NOT_FOUND
import build.raft.app.data.JOINT_INVITE_LIMIT_EXCEEDED
import build.raft.app.data.JOINT_INVITE_REQUIRED
import build.raft.app.data.JOINT_SERVER_LIMIT
import build.raft.app.data.JOINT_TARGET_SERVER_INVALID
import build.raft.app.data.JointChannelServer
import build.raft.app.data.JointChannelServerPlan
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

internal const val JOINT_CHANNEL_CONNECTIONS_PAGE = "compose_joint_channel_connections"

/** Identity only (server + channel); the page store loads the canonical connection state. */
internal fun jointChannelConnectionsPageData(route: ChannelSettingsRoute): JSONObject =
    JSONObject().apply {
        put("pageName", JOINT_CHANNEL_CONNECTIONS_PAGE)
        put("instanceId", "joint-connections-${route.channelId.value}")
        put("serverId", route.serverId.value)
        put("channelId", route.channelId.value)
    }

/**
 * Joint channel > Connected servers: web EditChannelDialog's joint section
 * (`packages/web/src/components/channel/EditChannelDialog.tsx`, slock 7d349b509d65): the
 * connected/pending server list with plan and status badges, Resend Invite, and the
 * "Channel connection invitation" form. Disconnect stays in Channel Settings' management card,
 * where web puts it in place of Delete.
 */
@Page(JOINT_CHANNEL_CONNECTIONS_PAGE, supportInLocal = true)
internal class RaftJointChannelConnectionsPage :
    AuthenticatedProductPager(AuthenticatedProductRoute.JointChannelConnections) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            RaftInputFocusDismissHost { focusDismissModifier ->
                Box(modifier = focusDismissModifier.fillMaxSize()) {
                    JointChannelConnectionsScreen()
                }
            }
        }
    }
}

@Composable
private fun JointChannelConnectionsScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val title = stringResource(MR.strings.joint_connections_title)
    val route = remember(params.toString()) { params.toChannelSettingsRouteOrNull() }
    val routeOwner = remember(params.toString()) { params.authenticatedRouteOwnerOrNull() }
    if (route == null || routeOwner == null || routeOwner.serverId != route.serverId.value) {
        SettingsDetailShell(title = title, subtitle = "", onBack = router::closePage) {
            RaftDesignSystemTheme {
                JointConnectionsStateBlock(
                    title = stringResource(MR.strings.channel_settings_unavailable),
                    detail = stringResource(MR.strings.channel_settings_invalid_route),
                )
            }
        }
        return
    }
    val storeScope = remember(params.toString(), pager.pagerId) {
        params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authorityGateway = remember(sharedStores, storeScope) {
        sharedStores.authenticatedRouteAuthorityGateway(storeScope)
    }
    val liveOwner by
        remember(sharedStores, storeScope) { sharedStores.authenticatedRouteOwnerFlow(storeScope) }
            .collectAsState(sharedStores.currentAuthenticatedRouteOwner(storeScope))
    val scope = rememberCoroutineScope()
    val store = remember(route, routeOwner, authorityGateway, scope, sharedStores) {
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
        )
    }
    val state by store.state.collectAsState()
    var slug by remember(route) { mutableStateOf("") }
    var people by remember(route) { mutableStateOf("") }
    // The last finished action's receipt (web: the role="status" line under the form).
    var statusMessage by remember(route) { mutableStateOf<JointChannelConnectionsEffect?>(null) }

    LaunchedEffect(store, liveOwner, routeOwner) {
        if (liveOwner == routeOwner) store.dispatch(JointChannelConnectionsIntent.Refresh)
    }
    LaunchedEffect(state.effect) {
        when (val effect = state.effect) {
            is JointChannelConnectionsEffect.InviteSent -> {
                slug = ""
                people = ""
                statusMessage = effect
            }
            is JointChannelConnectionsEffect.Resent -> statusMessage = effect
            null -> Unit
        }
        if (state.effect != null) store.dispatch(JointChannelConnectionsIntent.ConsumeEffect)
    }

    SettingsDetailShell(
        title = title,
        subtitle = state.connections?.channelName?.let { "#${it.removePrefix("#")}" }.orEmpty(),
        iconName = RaftLucideIconName.GitBranch,
        onBack = router::closePage,
    ) {
        RaftDesignSystemTheme {
            Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                when {
                    state.connections != null ->
                        JointConnectionsContent(
                            state = state,
                            slug = slug,
                            people = people,
                            statusMessage = statusMessage,
                            onSlugChange = {
                                slug = it
                                statusMessage = null
                                store.dispatch(JointChannelConnectionsIntent.ClearInviteFeedback)
                            },
                            onPeopleChange = {
                                people = it
                                statusMessage = null
                                store.dispatch(JointChannelConnectionsIntent.ClearInviteFeedback)
                            },
                            onSendInvite = {
                                statusMessage = null
                                store.dispatch(JointChannelConnectionsIntent.Invite(slug, people))
                            },
                            onResend = {
                                statusMessage = null
                                store.dispatch(JointChannelConnectionsIntent.Resend)
                            },
                        )
                    state.status == JointChannelConnectionsLoadStatus.Error ->
                        JointConnectionsStateBlock(
                            title = stringResource(MR.strings.joint_connections_load_failed),
                            detail = state.loadFailure?.let { jointChannelFailureMessage(it, fallback = "") },
                            actionLabel = stringResource(MR.strings.common_retry),
                            onAction = { store.dispatch(JointChannelConnectionsIntent.Refresh) },
                        )
                    else -> JointConnectionsStateBlock(title = stringResource(MR.strings.common_loading), loading = true)
                }
            }
        }
    }
}

@Composable
private fun JointConnectionsContent(
    state: JointChannelConnectionsState,
    slug: String,
    people: String,
    statusMessage: JointChannelConnectionsEffect?,
    onSlugChange: (String) -> Unit,
    onPeopleChange: (String) -> Unit,
    onSendInvite: () -> Unit,
    onResend: () -> Unit,
) {
    val connections = state.connections ?: return
    val bottomSafeArea = raftBottomSafeAreaPadding()
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "servers") {
            RaftSection(
                title = stringResource(MR.strings.joint_connections_title),
                count = connections.servers.size.toString(),
                separator = false,
            ) {
                RaftText(
                    stringResource(MR.strings.joint_connections_rule),
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundHint,
                )
                if (connections.servers.isEmpty()) {
                    RaftText(
                        stringResource(MR.strings.joint_connections_meta_unavailable),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundHint,
                    )
                } else {
                    RaftListRowGroup(Modifier.fillMaxWidth()) {
                        connections.servers.forEachIndexed { index, server ->
                            JointConnectedServerRow(server = server, divider = index > 0)
                        }
                    }
                }
                if (state.canResend) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        RaftButton(
                            text = stringResource(MR.strings.joint_connections_resend),
                            onClick = onResend,
                            enabled = state.actionInFlight == null,
                            loading = state.actionInFlight == JointChannelConnectionsAction.Resend,
                            loadingLabel = stringResource(MR.strings.joint_connections_resending),
                            leadingIcon = { RaftComputersIcon(RaftLucideIconName.Mail) },
                        )
                    }
                    state.resendFailure?.let { failure ->
                        RaftText(
                            jointChannelFailureMessage(failure, stringResource(MR.strings.joint_connections_resend_failed)),
                            modifier = Modifier.padding(top = 8.dp),
                            style = RaftTheme.typography.caption,
                            color = RaftTheme.colors.dangerStrong,
                        )
                    }
                }
            }
        }
        if (state.canManageConnections) {
            item(key = "invite") {
                JointInviteSection(
                    state = state,
                    slug = slug,
                    people = people,
                    onSlugChange = onSlugChange,
                    onPeopleChange = onPeopleChange,
                    onSendInvite = onSendInvite,
                )
            }
        }
        statusMessage?.let { receipt ->
            item(key = "status") {
                RaftBanner(
                    description = jointConnectionsReceiptText(receipt),
                    status = RaftBannerStatus.Success,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(bottomSafeArea + RaftAppSpacing.Lg)) }
    }
}

@Composable
private fun jointConnectionsReceiptText(receipt: JointChannelConnectionsEffect): String =
    when (receipt) {
        is JointChannelConnectionsEffect.InviteSent ->
            if (receipt.emailedCount == 1) {
                stringResource(MR.strings.joint_connections_invite_sent_one)
            } else {
                stringResource(MR.strings.joint_connections_invite_sent_many, receipt.emailedCount)
            }
        is JointChannelConnectionsEffect.Resent ->
            if (receipt.resentCount == 1) {
                stringResource(MR.strings.joint_connections_resent_one)
            } else {
                stringResource(MR.strings.joint_connections_resent_many, receipt.resentCount)
            }
    }

@Composable
private fun JointConnectedServerRow(server: JointChannelServer, divider: Boolean) {
    val thisServer = stringResource(MR.strings.joint_connections_this_server)
    RaftListRow(
        title = server.name.ifBlank { server.slug },
        divider = divider,
        subtitle =
            listOfNotNull(server.slug.takeIf(String::isNotBlank), thisServer.takeIf { server.isCurrentServer })
                .joinToString(" · ")
                .takeIf(String::isNotBlank),
        trailing = {
            server.plan?.let { plan ->
                RaftBadge(
                    text =
                        stringResource(
                            if (plan == JointChannelServerPlan.Paid) {
                                MR.strings.joint_connections_plan_paid
                            } else {
                                MR.strings.joint_connections_plan_free
                            }
                        ),
                    appearance = RaftBadgeAppearance.Outline,
                )
            }
            RaftBadge(
                text =
                    stringResource(
                        if (server.active) MR.strings.joint_connections_status_active else MR.strings.joint_connections_status_pending
                    ),
                appearance = RaftBadgeAppearance.Soft,
                variant = if (server.active) RaftBadgeVariant.Success else RaftBadgeVariant.Warning,
            )
        },
    )
}

@Composable
private fun JointInviteSection(
    state: JointChannelConnectionsState,
    slug: String,
    people: String,
    onSlugChange: (String) -> Unit,
    onPeopleChange: (String) -> Unit,
    onSendInvite: () -> Unit,
) {
    val connections = state.connections ?: return
    val inviting = state.actionInFlight == JointChannelConnectionsAction.Invite
    val failure = state.inviteFailure
    val failureField = failure?.let { jointInviteFailureField(it.code) }
    val failureText = failure?.let { jointChannelFailureMessage(it, stringResource(MR.strings.joint_connections_invite_failed)) }
    val slugError =
        when {
            JointInviteValidation.SlugRequired in state.inviteValidation -> stringResource(MR.strings.joint_connections_slug_required)
            JointInviteValidation.SlugPattern in state.inviteValidation -> stringResource(MR.strings.joint_connections_slug_pattern)
            failureField == JointInviteField.Slug -> failureText
            else -> null
        }
    val peopleError =
        when {
            JointInviteValidation.PeopleRequired in state.inviteValidation -> stringResource(MR.strings.joint_connections_people_required)
            failureField == JointInviteField.People -> failureText
            else -> null
        }
    val formError =
        when {
            JointInviteValidation.ServerLimitReached in state.inviteValidation ->
                stringResource(MR.strings.joint_connections_max_servers, JOINT_CHANNEL_MAX_SERVERS)
            failureField == JointInviteField.Form -> failureText
            else -> null
        }
    RaftSection(title = stringResource(MR.strings.joint_connections_invite_section)) {
        if (connections.serverLimitReached) {
            RaftText(
                stringResource(MR.strings.joint_connections_max_servers, JOINT_CHANNEL_MAX_SERVERS),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundHint,
            )
            return@RaftSection
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftField(
                label = stringResource(MR.strings.joint_connections_slug_label),
                required = true,
                error = slugError,
                enabled = !inviting,
            ) {
                RaftTextField(
                    value = slug,
                    onValueChange = onSlugChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(MR.strings.joint_connections_slug_placeholder),
                )
            }
            RaftField(
                label = stringResource(MR.strings.joint_connections_people_label),
                required = true,
                description = stringResource(MR.strings.joint_connections_people_hint),
                error = peopleError,
                enabled = !inviting,
            ) {
                RaftTextarea(
                    value = people,
                    onValueChange = onPeopleChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(MR.strings.joint_connections_people_placeholder),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RaftButton(
                    text = stringResource(MR.strings.joint_connections_send_invite),
                    onClick = onSendInvite,
                    enabled = state.actionInFlight == null && slug.isNotBlank() && people.isNotBlank(),
                    loading = inviting,
                    loadingLabel = stringResource(MR.strings.joint_connections_inviting),
                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.Mail) },
                )
            }
            formError?.let { message ->
                RaftBanner(description = message, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

@Composable
private fun JointConnectionsStateBlock(
    title: String,
    detail: String? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) RaftSpinner(size = RaftSpinnerSize.Md, contentDescription = title)
        RaftText(title, style = RaftTheme.typography.headline, color = RaftTheme.colors.foreground)
        detail?.takeIf(String::isNotBlank)?.let {
            RaftText(it, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
        }
        if (actionLabel != null) {
            RaftButton(text = actionLabel, onClick = onAction, variant = RaftButtonVariant.Default)
        }
    }
}

/** Localized copy for each joint failure, with the web's precedence. */
internal data class JointFailureCopy(
    val freeServerLimit: String,
    val serverLimit: String,
    val hostFreeLimit: String,
    val fallback: String,
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
    val copy =
        JointFailureCopy(
            freeServerLimit = stringResource(MR.strings.joint_limit_free_servers),
            serverLimit = stringResource(MR.strings.joint_limit_servers, JOINT_CHANNEL_MAX_SERVERS),
            hostFreeLimit = stringResource(MR.strings.joint_limit_host_free),
            fallback = fallback,
        )
    return jointChannelFailureText(failure, copy) { value ->
        when (value) {
            is JointChannelFailure.Error -> value.error.userFacingMessage(strings)
            is JointChannelFailure.Blocked -> value.blocker.userFacingMessage(strings)
        }
    }
}
