package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.JOINT_CHANNEL_MAX_SERVERS
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.ConversationKind
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftMultiChoiceList
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.launch

internal const val EXTERNAL_INVITE_PAGE = "compose_external_invite"
private const val EXTERNAL_INVITE_TOKEN = "externalInviteToken"

internal const val ExternalInvitePageTag = "external-invite-page"
internal const val ExternalInviteWrongAccountTag = "external-invite-wrong-account"
internal const val ExternalInviteSwitchAccountTag = "external-invite-switch-account"
internal const val ExternalInviteSignOutTag = "external-invite-sign-out"
internal const val ExternalInviteOptionGuestTag = "external-invite-option-guest"
internal const val ExternalInviteOptionConnectTag = "external-invite-option-connect"
internal const val ExternalInviteOptionNewServerTag = "external-invite-option-new-server"
internal const val ExternalInviteCommitTag = "external-invite-commit"
internal const val ExternalInviteAcceptErrorTag = "external-invite-accept-error"
internal const val ExternalInviteNoticeTag = "external-invite-notice"
internal const val ExternalInviteCancelConfirmTag = "external-invite-cancel-confirm"

/** Identity only (the Route / PageData rule): the invite's token; the page reads the invite itself. */
internal fun externalInvitePageData(token: String): JSONObject =
    JSONObject().apply {
        put("pageName", EXTERNAL_INVITE_PAGE)
        put("instanceId", "external-invite")
        put(EXTERNAL_INVITE_TOKEN, token)
    }

internal fun JSONObject.externalInviteTokenOrNull(): String? = optString(EXTERNAL_INVITE_TOKEN).trim().takeIf(String::isNotEmpty)

/** Opens the invite page for [token] from [this] page with [storeScope]; false when it can't be opened. */
internal fun Pager.openExternalInvitePage(token: String, storeScope: RaftShellStoreScope?): Boolean =
    openRaftPage(pageName = EXTERNAL_INVITE_PAGE, pageData = externalInvitePageData(token), storeScopeOverride = storeScope)

/**
 * The page an external channel invite's email links to (web ExternalInvitePage): the ways in the
 * server offers (join as a guest, connect servers, create a server), each step, where a connection
 * stands, and "isn't for this account" with Switch account (#13942).
 */
@Page(EXTERNAL_INVITE_PAGE, supportInLocal = true)
internal class RaftExternalInvitePage : AuthenticatedProductPager(AuthenticatedProductRoute.ExternalInvite) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            RaftInputFocusDismissHost { focusDismissModifier ->
                Box(modifier = focusDismissModifier.fillMaxSize()) { ExternalInviteScreen() }
            }
        }
    }
}

private fun StoredAuthSession.inviteMyServers(): List<InviteMyServer> =
    servers.map { InviteMyServer(id = it.id, name = it.name, slug = it.slug, plan = it.plan) }

@Composable
private fun ExternalInviteScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val token = remember(params.toString()) { params.externalInviteTokenOrNull() }
    val storeScope = remember(params.toString(), pager.pagerId) { params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId) }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val scope = rememberCoroutineScope()
    val store =
        remember(token, storeScope) {
            ExternalInviteStore(
                scope = scope,
                dataSource =
                    RealExternalInviteDataSource(token.orEmpty(), sharedStores.networkClient(storeScope)) { bridge.log(it) },
            )
        }
    DisposableEffect(store) { onDispose { store.clear() } }
    val state by store.state.collectAsState()
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val authDataSource = remember(sharedStores, storeScope) { sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope) }
    var email by remember { mutableStateOf("") }
    LaunchedEffect(store, token) {
        val session = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        email = session?.user?.email.orEmpty()
        session?.let { store.dispatch(ExternalInviteIntent.SetMyServers(it.inviteMyServers())) }
        if (token == null) store.dispatch(ExternalInviteIntent.GoToRaft) else store.dispatch(ExternalInviteIntent.Load)
    }
    suspend fun reloadServers(): StoredAuthSession? {
        val result = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()
        val session = (result as? SharedAuthResult.Success<StoredAuthSession>)?.data
            ?: runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        session?.let { store.dispatch(ExternalInviteIntent.SetMyServers(it.inviteMyServers())) }
        return session
    }
    LaunchedEffect(state.effects) {
        state.effects.forEach { effect ->
            store.dispatch(ExternalInviteIntent.ConsumeEffect(effect))
            when (effect) {
                ExternalInviteEffect.ServersChanged -> scope.launch { reloadServers() }
                is ExternalInviteEffect.OpenChannel ->
                    scope.launch {
                        // Into the channel on the server it landed on: the shell resolves it like a
                        // message link (identity only), switching server when it has to.
                        val slug = reloadServers()?.servers?.firstOrNull { it.id == effect.serverId }?.slug
                        bridge.log("external_invite_open_channel slug=${!slug.isNullOrBlank()}")
                        if (!slug.isNullOrBlank()) {
                            RaftMessageLinkInbox.Process.submit(
                                RaftMessageLink(serverSlug = slug, routeKind = ConversationKind.Channel, channelId = effect.channelId),
                                source = "external_invite",
                            )
                        }
                        router.closePage()
                    }
                // The app has no server switch from a page: back to the app, where the server list
                // now has it (web opens /s/{slug}).
                is ExternalInviteEffect.OpenServer -> router.closePage()
                ExternalInviteEffect.Close -> router.closePage()
                ExternalInviteEffect.SignOut ->
                    scope.launch {
                        // Switch account: the invite opens again once signed in with the right one.
                        token?.let { RaftExternalInviteLinkInbox.Process.submit(it, "external_invite_switch_account") }
                        pager.performStandaloneSettingsLogout(authDataSource)
                    }
            }
        }
    }
    RaftDesignSystemTheme {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).testTag(ExternalInvitePageTag)) {
            RaftTopPageHeader(title = "", onBack = router::closePage, backgroundColor = RaftColor.SurfaceWhite)
            ExternalInviteContent(state = state, email = email, onIntent = store::dispatch)
        }
        state.cancelConfirm?.let { confirm ->
            ExternalInviteCancelDialog(
                approverName = confirm.approverName,
                busy = state.busy,
                onDismiss = { store.dispatch(ExternalInviteIntent.DismissCancel) },
                onConfirm = { store.dispatch(ExternalInviteIntent.ConfirmCancel) },
            )
        }
    }
}

/** The values the web formats the page's texts with. */
private data class InviteValues(val channel: String, val server: String, val inviter: String)

@Composable
private fun inviteeMessageText(message: InviteeMessage, values: InviteValues, target: String): String =
    when (message) {
        InviteeMessage.GuestUnavailable -> stringResource(Res.string.external_invite_error_guest_unavailable, values.channel)
        InviteeMessage.AlreadyMember -> stringResource(Res.string.external_invite_error_already_member, values.server, values.channel)
        InviteeMessage.GuestElsewhere -> stringResource(Res.string.external_invite_error_guest_elsewhere, values.channel)
        InviteeMessage.ChannelFull -> stringResource(Res.string.external_invite_error_channel_full, values.channel, values.inviter)
        InviteeMessage.InvalidServer -> stringResource(Res.string.external_invite_error_invalid_server, target, values.channel)
        InviteeMessage.ServerAdminRequired -> stringResource(Res.string.external_invite_error_server_admin_required, target)
        InviteeMessage.ServerMembershipRequired -> stringResource(Res.string.external_invite_error_server_membership_required, target)
        InviteeMessage.InvalidApprover -> stringResource(Res.string.external_invite_error_invalid_approver, target)
        InviteeMessage.GuestsPresent -> stringResource(Res.string.external_invite_error_guests_present, values.channel)
        InviteeMessage.JointPartyLimit ->
            stringResource(Res.string.external_invite_error_joint_party_limit, values.channel, JOINT_CHANNEL_MAX_SERVERS, values.inviter)
        InviteeMessage.AlreadyConnected -> stringResource(Res.string.external_invite_error_already_connected, target, values.channel)
        InviteeMessage.JointServerLimit -> stringResource(Res.string.external_invite_error_joint_server_limit, values.channel)
        InviteeMessage.HostLimit -> stringResource(Res.string.external_invite_error_host_limit, values.server, values.inviter)
        InviteeMessage.TargetFreeLimit -> stringResource(Res.string.external_invite_error_target_free_limit, target)
        InviteeMessage.JointFreeServerLimit -> stringResource(Res.string.external_invite_error_joint_free_server_limit, values.channel, target)
        InviteeMessage.ConnectInProgress -> stringResource(Res.string.external_invite_error_connect_in_progress, values.channel)
        InviteeMessage.Archived -> stringResource(Res.string.external_invite_error_archived, values.channel)
        InviteeMessage.Busy -> stringResource(Res.string.external_invite_error_busy, values.channel)
        InviteeMessage.TooManyAttempts -> stringResource(Res.string.external_invite_error_too_many_attempts)
        InviteeMessage.Generic -> stringResource(Res.string.external_invite_error_generic)
    }

@Composable
private fun newServerFieldErrorText(error: NewServerFieldError): String =
    when (error) {
        NewServerFieldError.NameRequired -> stringResource(Res.string.external_invite_new_server_name_required)
        NewServerFieldError.SlugRequired -> stringResource(Res.string.external_invite_new_server_slug_required)
        NewServerFieldError.SlugTooShort -> stringResource(Res.string.external_invite_new_server_slug_too_short, NEW_SERVER_SLUG_MIN_LENGTH)
        NewServerFieldError.SlugPattern -> stringResource(Res.string.external_invite_new_server_slug_pattern)
        NewServerFieldError.SlugTaken -> stringResource(Res.string.external_invite_new_server_slug_taken)
    }

/** The page's content for [state] (the page and the UI tests compose it the same way). */
@Composable
internal fun ExternalInviteContent(state: ExternalInviteState, email: String, onIntent: (ExternalInviteIntent) -> Unit) {
    val invite = state.invite
    val values =
        InviteValues(
            channel = "#${invite?.channelName.orEmpty()}",
            server = invite?.serverName.orEmpty(),
            inviter = invite?.inviterName.orEmpty(),
        )
    val signedInPrefix = stringResource(Res.string.external_invite_signed_in_as)
    // "Signed in as a@b"; a full-width colon (zh, ja) takes no space after it.
    val signedIn = email.takeIf(String::isNotBlank)?.let { if (signedInPrefix.endsWith("：")) signedInPrefix + it else "$signedInPrefix $it" }
    val errorBanner: @Composable () -> Unit = {
        state.error?.let { error ->
            val target = error.target ?: state.serverName(error.serverId) ?: state.pickedServers.firstOrNull()?.name.orEmpty()
            Column(Modifier.fillMaxWidth().testTag(ExternalInviteAcceptErrorTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val kept = state.keptServer?.takeIf { it.id == error.serverId }
                RaftBanner(
                    description =
                        inviteeMessageText(error.message, values, target) +
                            (kept?.let { " " + stringResource(Res.string.external_invite_new_server_kept, state.serverName(it.id) ?: it.name) } ?: ""),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.fillMaxWidth(),
                )
                RaftTraceIdNote(error.traceId)
            }
        }
    }
    val signOut: @Composable () -> Unit = {
        RaftButton(
            text = stringResource(Res.string.external_invite_sign_out),
            onClick = { onIntent(ExternalInviteIntent.SignOut) },
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Md,
            modifier = Modifier.fillMaxWidth().testTag(ExternalInviteSignOutTag),
        )
    }
    val step = state.step
    when (step) {
        InviteStep.Loading ->
            InviteFrame(title = stringResource(Res.string.external_invite_loading_title), description = null, signedIn = null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RaftSpinner(size = RaftSpinnerSize.Md) }
            }
        InviteStep.Invalid ->
            InviteFrame(
                stringResource(Res.string.external_invite_invalid_title),
                stringResource(Res.string.external_invite_invalid_description),
                signedIn,
            ) {
                InviteWideButton(stringResource(Res.string.external_invite_go_to_raft), variant = RaftButtonVariant.Default) {
                    onIntent(ExternalInviteIntent.GoToRaft)
                }
                signOut()
            }
        is InviteStep.LoadFailed ->
            InviteFrame(
                stringResource(Res.string.external_invite_load_failed_title),
                stringResource(Res.string.external_invite_load_failed_description),
                signedIn,
            ) {
                RaftTraceIdNote(step.traceId)
                InviteWideButton(stringResource(Res.string.external_invite_retry), variant = RaftButtonVariant.Default) {
                    onIntent(ExternalInviteIntent.Load)
                }
                signOut()
            }
        is InviteStep.WrongAccount ->
            InviteFrame(
                stringResource(Res.string.external_invite_wrong_account_title),
                step.maskedEmail?.let { stringResource(Res.string.external_invite_wrong_account_sent_to, it) }
                    ?: stringResource(Res.string.external_invite_wrong_account_description),
                signedIn,
                modifier = Modifier.testTag(ExternalInviteWrongAccountTag),
            ) {
                InviteWideButton(
                    stringResource(Res.string.external_invite_switch_account),
                    modifier = Modifier.testTag(ExternalInviteSwitchAccountTag),
                ) { onIntent(ExternalInviteIntent.SignOut) }
            }
        is InviteStep.Waiting ->
            InviteFrame(
                stringResource(Res.string.external_invite_waiting_title),
                step.request?.let { stringResource(Res.string.external_invite_waiting_sent, it.approverName, it.serverName) }
                    ?: stringResource(Res.string.external_invite_waiting_description, values.channel),
                signedIn,
            ) {
                step.request?.let { request ->
                    InviteServerRow(name = request.serverName, meta = stringResource(Res.string.external_invite_waiting_request_sent))
                }
                errorBanner()
                if (step.request != null) {
                    InviteWideButton(
                        if (state.busy) stringResource(Res.string.external_invite_waiting_canceling) else stringResource(Res.string.external_invite_waiting_cancel),
                        variant = RaftButtonVariant.Default,
                        loading = state.busy,
                    ) { onIntent(ExternalInviteIntent.AskCancel(null, step.request.approverName)) }
                }
                signOut()
            }
        is InviteStep.Connecting, is InviteStep.Connected -> {
            val connected = step is InviteStep.Connected
            val landing = (step as? InviteStep.Connected)?.landing
            val serverId = (step as? InviteStep.Connected)?.serverId ?: (step as? InviteStep.Connecting)?.serverId
            val target = state.serverName(serverId) ?: if (landing?.outcome == "guest") values.server.takeIf(String::isNotBlank) else null
            val channel = landing?.let { "#${it.channelName}" } ?: values.channel
            InviteFrame(
                title =
                    when {
                        connected -> stringResource(Res.string.external_invite_connected_title, channel)
                        target != null -> stringResource(Res.string.external_invite_connecting_title, target)
                        else -> stringResource(Res.string.external_invite_connecting_title_unnamed)
                    },
                description =
                    when {
                        !connected -> stringResource(Res.string.external_invite_connecting_description, values.channel)
                        target != null -> stringResource(Res.string.external_invite_connected_description, target)
                        else -> stringResource(Res.string.external_invite_connected_description_unnamed)
                    },
                signedIn = signedIn,
            ) {
                val openTarget = if (landing != null) channel else state.myServer(serverId)?.name ?: target
                InviteWideButton(
                    openTarget?.let { stringResource(Res.string.external_invite_open_server, it) } ?: stringResource(Res.string.external_invite_go_to_raft),
                ) { onIntent(ExternalInviteIntent.OpenServer) }
                signOut()
            }
        }
        is InviteStep.Progress ->
            InviteFrame(
                stringResource(if (invite?.status == "pending") Res.string.external_invite_title else Res.string.external_invite_progress_title, values.channel),
                stringResource(Res.string.external_invite_progress_description, values.channel),
                signedIn,
            ) {
                InviteProgressList(state, values, onIntent)
                errorBanner()
                if (invite?.status == "pending") InviteWaysInRows(state, values, onIntent)
                signOut()
            }
        InviteStep.Guest ->
            InviteFrame(
                stringResource(Res.string.external_invite_guest_title),
                stringResource(Res.string.external_invite_guest_description, values.channel, values.server),
                signedIn,
            ) {
                errorBanner()
                InviteStepActions(
                    label = if (state.busy) stringResource(Res.string.external_invite_guest_joining) else stringResource(Res.string.external_invite_guest_commit),
                    busy = state.busy,
                    onCommit = { onIntent(ExternalInviteIntent.JoinAsGuest) },
                    onBack = { onIntent(ExternalInviteIntent.ShowOptions) },
                )
            }
        InviteStep.Connect -> InviteConnectStep(state, values, signedIn, errorBanner, onIntent)
        is InviteStep.Approval -> InviteApprovalStep(state, step, signedIn, errorBanner, onIntent)
        InviteStep.NewServer -> InviteNewServerStep(state, values, signedIn, errorBanner, onIntent)
        InviteStep.Options ->
            InviteFrame(
                stringResource(Res.string.external_invite_title, values.channel),
                stringResource(Res.string.external_invite_invited_by, values.inviter, values.server),
                signedIn,
            ) {
                val notice =
                    when {
                        state.connectFailedNotice ->
                            state.serverName(state.connectFailedServerId)?.let { stringResource(Res.string.external_invite_notice_connect_failed, it) }
                                ?: stringResource(Res.string.external_invite_notice_connect_failed_unnamed)
                        invite?.lastRequest?.status == "rejected" ->
                            stringResource(Res.string.external_invite_notice_declined, invite?.lastRequest?.serverName.orEmpty())
                        else -> null
                    }
                notice?.let {
                    RaftBanner(
                        description = it,
                        status = RaftBannerStatus.Info,
                        size = RaftBannerSize.Sm,
                        modifier = Modifier.fillMaxWidth().testTag(ExternalInviteNoticeTag),
                    )
                }
                InviteWaysInRows(state, values, onIntent)
                signOut()
            }
    }
}

/** Web `frame`: the title, the description, "Signed in as", then the step's body. */
@Composable
private fun InviteFrame(
    title: String,
    description: String?,
    signedIn: String?,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftText(title, style = RaftTheme.typography.title, color = RaftTheme.colors.foreground)
                description?.let { RaftText(it, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted) }
                signedIn?.let { RaftText(it, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted) }
            }
            body()
        }
    }
}

@Composable
private fun InviteWideButton(
    text: String,
    modifier: Modifier = Modifier,
    variant: RaftButtonVariant = RaftButtonVariant.Primary,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    RaftButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        variant = variant,
        size = RaftButtonSize.Lg,
        loading = loading,
        enabled = enabled,
    )
}

/** Web `InviteStepActions`: the one primary action, full width, and Back under it. */
@Composable
private fun InviteStepActions(label: String, busy: Boolean, onCommit: () -> Unit, onBack: () -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InviteWideButton(label, modifier = Modifier.testTag(ExternalInviteCommitTag), loading = busy, enabled = enabled, onClick = onCommit)
        RaftButton(
            text = stringResource(Res.string.external_invite_back),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Md,
            enabled = !busy,
        )
    }
}

/** Web `InviteWaysIn`: as many rows as the server offers, of equal weight; else why there's none. */
@Composable
private fun InviteWaysInRows(state: ExternalInviteState, values: InviteValues, onIntent: (ExternalInviteIntent) -> Unit) {
    val options = state.invite?.options
    val guest = options?.guest == true
    val connect = state.servers.isNotEmpty()
    val newServer = options?.newServer == true
    if (!guest && !connect && !newServer) {
        RaftBanner(
            description =
                if (state.invite?.channelFull == true) {
                    stringResource(Res.string.external_invite_error_channel_full, values.channel, values.inviter)
                } else {
                    stringResource(Res.string.external_invite_no_options, values.channel, values.inviter)
                },
            status = RaftBannerStatus.Info,
            size = RaftBannerSize.Sm,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    data class Way(val tag: String, val icon: RaftLucideIconName, val title: String, val description: String, val intent: ExternalInviteIntent)
    val ways =
        listOfNotNull(
            Way(
                ExternalInviteOptionGuestTag,
                RaftLucideIconName.UserPlus,
                stringResource(Res.string.external_invite_option_guest),
                stringResource(Res.string.external_invite_option_guest_description),
                ExternalInviteIntent.ShowGuest,
            ).takeIf { guest },
            Way(
                ExternalInviteOptionConnectTag,
                RaftLucideIconName.Server,
                stringResource(Res.string.external_invite_option_connect),
                stringResource(Res.string.external_invite_option_connect_description),
                ExternalInviteIntent.ShowConnect,
            ).takeIf { connect },
            Way(
                ExternalInviteOptionNewServerTag,
                RaftLucideIconName.Plus,
                stringResource(Res.string.external_invite_option_new_server),
                stringResource(Res.string.external_invite_option_new_server_description, values.channel),
                ExternalInviteIntent.ShowNewServer,
            ).takeIf { newServer },
        )
    Column(Modifier.fillMaxWidth().background(RaftTheme.colors.layerCard, RoundedCornerShape(RaftRadius.Container))) {
        ways.forEachIndexed { index, way ->
            RaftListRow(
                title = way.title,
                modifier = Modifier.testTag(way.tag),
                onClick = { onIntent(way.intent) },
                divider = index > 0,
                description = way.description,
                wrapDescription = true,
                leading = { RaftIconTile(size = RaftIconTileSize.Md) { RaftComputersIcon(way.icon) } },
                trailing = { RaftComputersIcon(RaftLucideIconName.ChevronRight) },
            )
        }
    }
}

/** Web connect step: the caller's servers as checkboxes (multi-select), the slots, then Connect. */
@Composable
private fun InviteConnectStep(
    state: ExternalInviteState,
    values: InviteValues,
    signedIn: String?,
    errorBanner: @Composable () -> Unit,
    onIntent: (ExternalInviteIntent) -> Unit,
) {
    val picked = state.pickedServers
    val slotsLeft = state.connectSlotsLeft
    val needsApproval = stringResource(Res.string.external_invite_connect_needs_approval)
    val atLimit = stringResource(Res.string.external_invite_connect_at_limit)
    val noSlot = stringResource(Res.string.external_invite_connect_no_slot)
    var query by remember { mutableStateOf("") }
    val items =
        state.servers.map { server ->
            val slug = (server.slug ?: state.myServer(server.id)?.slug)?.let { "/$it" }
            val blockedBySlots = slotsLeft == 0 && server.id !in state.pickedServerIds && !server.atLimit
            val detail =
                when {
                    server.atLimit -> atLimit
                    blockedBySlots -> noSlot
                    else -> listOfNotNull(slug, needsApproval.takeIf { server.needsApproval }).joinToString(" · ")
                }
            RaftChoiceListItem(
                value = server.id,
                label = server.name,
                descriptions = listOf(AnnotatedString(detail)).filter { it.isNotEmpty() },
                enabled = !server.atLimit && !blockedBySlots,
                searchText = "${server.name} ${server.slug.orEmpty()}",
            )
        }
    InviteFrame(
        stringResource(Res.string.external_invite_connect_title),
        stringResource(Res.string.external_invite_connect_description, values.channel),
        signedIn,
    ) {
        RaftField(
            label = stringResource(Res.string.external_invite_connect_picker_label),
            description =
                slotsLeft?.let {
                    if (it == 0) {
                        stringResource(Res.string.external_invite_connect_slots_full, values.channel)
                    } else {
                        stringResource(Res.string.external_invite_connect_slots_left, it)
                    }
                },
        ) {
            RaftMultiChoiceList(
                items = items,
                values = state.pickedServerIds.toSet(),
                onValuesChange = { chosen ->
                    val ordered = state.pickedServerIds.filter { it in chosen } + chosen.filter { it !in state.pickedServerIds }
                    onIntent(ExternalInviteIntent.SetPicked(ordered))
                },
                query = query,
                onQueryChange = { query = it },
                searchPlaceholder = stringResource(Res.string.external_invite_connect_search),
                clearSearchLabel = stringResource(Res.string.external_invite_connect_search),
                noMatchLabel = stringResource(Res.string.common_no_matches),
                emptyLabel = stringResource(Res.string.common_no_matches),
                enabled = !state.busy,
            )
        }
        errorBanner()
        InviteStepActions(
            label =
                when {
                    state.busy -> stringResource(Res.string.external_invite_connect_connecting)
                    picked.isEmpty() -> stringResource(Res.string.external_invite_connect_commit_unpicked)
                    picked.any { it.needsApproval } -> stringResource(Res.string.external_invite_connect_continue)
                    picked.size == 1 -> stringResource(Res.string.external_invite_connect_commit, picked.single().name)
                    else -> stringResource(Res.string.external_invite_connect_commit_many_other, picked.size)
                },
            busy = state.busy,
            enabled = picked.isNotEmpty(),
            onCommit = { onIntent(ExternalInviteIntent.Connect) },
            onBack = { onIntent(ExternalInviteIntent.ShowOptions) },
        )
    }
}

/** Web approval step: one list of owners and admins per server that needs approval. */
@Composable
private fun InviteApprovalStep(
    state: ExternalInviteState,
    step: InviteStep.Approval,
    signedIn: String?,
    errorBanner: @Composable () -> Unit,
    onIntent: (ExternalInviteIntent) -> Unit,
) {
    val approval = step.serverIds.mapNotNull { id -> state.servers.firstOrNull { it.id == id } }
    val direct = state.pickedServers.filter { it.canConnectDirectly }
    val many = approval.size > 1
    val asked = jointInviteNames(approval.map { it.name })
    val directNames = jointInviteNames(direct.map { it.name })
    InviteFrame(
        title =
            if (approval.size == 1) {
                stringResource(Res.string.external_invite_approval_title, approval.single().name)
            } else {
                stringResource(Res.string.external_invite_approval_title_many, approval.size)
            },
        description =
            when {
                direct.isNotEmpty() && many ->
                    stringResource(
                        if (direct.size == 1) Res.string.external_invite_approval_description_mixed_many_one else Res.string.external_invite_approval_description_mixed_many_other,
                        directNames,
                    )
                direct.isNotEmpty() ->
                    stringResource(
                        if (direct.size == 1) Res.string.external_invite_approval_description_mixed_one else Res.string.external_invite_approval_description_mixed_other,
                        directNames,
                        asked,
                    )
                many -> stringResource(Res.string.external_invite_approval_description_many)
                else -> stringResource(Res.string.external_invite_approval_description, asked)
            },
        signedIn = signedIn,
    ) {
        approval.forEach { server ->
            RaftField(label = stringResource(Res.string.external_invite_approval_picker_label, server.name)) {
                RaftChoiceList(
                    items = server.approvers.map { RaftChoiceListItem(value = it.id, label = it.name) },
                    value = state.approverFor(server),
                    onValueChange = { onIntent(ExternalInviteIntent.PickApprover(server.id, it)) },
                    searchPlaceholder = stringResource(Res.string.external_invite_approval_search),
                    clearSearchLabel = stringResource(Res.string.external_invite_approval_search),
                    noMatchLabel = stringResource(Res.string.common_no_matches),
                    emptyLabel = stringResource(Res.string.common_no_matches),
                    enabled = !state.busy,
                )
            }
        }
        errorBanner()
        InviteStepActions(
            label =
                when {
                    state.busy -> stringResource(Res.string.external_invite_approval_sending)
                    direct.isNotEmpty() ->
                        stringResource(if (many) Res.string.external_invite_approval_commit_mixed_other else Res.string.external_invite_approval_commit_mixed_one)
                    else -> stringResource(if (many) Res.string.external_invite_approval_commit_other else Res.string.external_invite_approval_commit_one)
                },
            busy = state.busy,
            enabled = approval.all { state.approverFor(it) != null },
            onCommit = { onIntent(ExternalInviteIntent.RequestApproval) },
            onBack = { onIntent(ExternalInviteIntent.ShowConnect) },
        )
    }
}

/** `Intl.ListFormat` conjunction of [names], as the web's `formatList`. */
@Composable
private fun jointInviteNames(names: List<String>): String {
    val two = stringResource(Res.string.member_removal_impact_list_two, "\u0001", "\u0002")
    val more = stringResource(Res.string.member_removal_impact_list_more, "\u0001", "\u0002")
    val end = stringResource(Res.string.member_removal_impact_list_end, "\u0001", "\u0002")
    fun fill(template: String, a: String, b: String) = template.replace("\u0001", a).replace("\u0002", b)
    return memberRemovalImpactJoin(names, { a, b -> fill(two, a, b) }, { a, b -> fill(more, a, b) }, { a, b -> fill(end, a, b) })
}

/** Web new-server step: the name and slug as Create Server takes them, then Create. */
@Composable
private fun InviteNewServerStep(
    state: ExternalInviteState,
    values: InviteValues,
    signedIn: String?,
    errorBanner: @Composable () -> Unit,
    onIntent: (ExternalInviteIntent) -> Unit,
) {
    val kept = state.keptServer
    InviteFrame(
        stringResource(Res.string.external_invite_new_server_title),
        stringResource(Res.string.external_invite_new_server_description, values.channel, values.server),
        signedIn,
    ) {
        RaftField(
            label = stringResource(Res.string.external_invite_new_server_name_label),
            error = state.fieldErrors.name?.let { newServerFieldErrorText(it) },
        ) {
            RaftTextField(
                value = state.newServerName,
                onValueChange = { onIntent(ExternalInviteIntent.SetNewServerName(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(Res.string.external_invite_new_server_name_placeholder),
                enabled = !state.busy && kept == null,
                invalid = state.fieldErrors.name != null,
            )
        }
        RaftField(
            label = stringResource(Res.string.external_invite_new_server_slug_label),
            description = stringResource(Res.string.external_invite_new_server_slug_help),
            error = state.fieldErrors.slug?.let { newServerFieldErrorText(it) },
        ) {
            RaftTextField(
                value = state.newServerSlug,
                onValueChange = { onIntent(ExternalInviteIntent.SetNewServerSlug(it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy && kept == null,
                invalid = state.fieldErrors.slug != null,
            )
        }
        errorBanner()
        if (kept != null) {
            InviteWideButton(stringResource(Res.string.external_invite_open_server, state.serverName(kept.id) ?: kept.name)) {
                onIntent(ExternalInviteIntent.OpenKeptServer)
            }
        } else {
            InviteStepActions(
                label = if (state.busy) stringResource(Res.string.external_invite_new_server_creating) else stringResource(Res.string.external_invite_new_server_commit),
                busy = state.busy,
                onCommit = { onIntent(ExternalInviteIntent.CreateServer) },
                onBack = { onIntent(ExternalInviteIntent.ShowOptions) },
            )
        }
    }
}

/** Web `ServerRow`: a server tile, its name and a line under it. */
@Composable
private fun InviteServerRow(name: String, meta: String) {
    Box(Modifier.fillMaxWidth().background(RaftTheme.colors.layerCard, RoundedCornerShape(RaftRadius.Container))) {
        RaftListRow(
            title = name,
            description = meta,
            leading = { RaftIconTile(size = RaftIconTileSize.Md) { RaftComputersIcon(RaftLucideIconName.Server) } },
        )
    }
}

/** Web `InviteProgress`: each chosen server and where it stands, Cancel on a waiting one, then Open. */
@Composable
private fun InviteProgressList(state: ExternalInviteState, values: InviteValues, onIntent: (ExternalInviteIntent) -> Unit) {
    val invite = state.invite ?: return
    val step = state.step as? InviteStep.Progress ?: return
    val rows = inviteProgressRows(step.chosen, invite)
    Column(Modifier.fillMaxWidth().background(RaftTheme.colors.layerCard, RoundedCornerShape(RaftRadius.Container))) {
        rows.forEachIndexed { index, row ->
            val (badge, detail) =
                when (row.state) {
                    "connected" -> stringResource(Res.string.external_invite_progress_connected) to null
                    "connecting" -> stringResource(Res.string.external_invite_connect_connecting) to null
                    "awaiting_approval" ->
                        stringResource(Res.string.external_invite_progress_waiting_badge) to
                            row.approverName?.let { stringResource(Res.string.external_invite_progress_waiting, it) }
                    else ->
                        stringResource(Res.string.external_invite_progress_not_connected) to
                            row.refusal?.let {
                                inviteeMessageText(
                                    inviteeErrorMessage(it.asRefusal(), InviteeAction.Connect, state.myServer(row.serverId), row.serverId),
                                    values,
                                    row.name,
                                )
                            }
                }
            RaftListRow(
                title = row.name,
                divider = index > 0,
                description = listOfNotNull(badge, detail).joinToString(" · "),
                wrapDescription = true,
                leading = { RaftIconTile(size = RaftIconTileSize.Md) { RaftComputersIcon(RaftLucideIconName.Server) } },
                trailing =
                    row.requestId?.takeIf { row.state == "awaiting_approval" }?.let { requestId ->
                        {
                            RaftButton(
                                text = stringResource(Res.string.external_invite_waiting_cancel),
                                onClick = { onIntent(ExternalInviteIntent.AskCancel(requestId, row.approverName.orEmpty())) },
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                enabled = !state.busy,
                            )
                        }
                    },
            )
        }
    }
    if (invite.status != "pending") {
        rows.firstOrNull { (it.state == "connected" || it.state == "connecting") && state.myServer(it.serverId) != null }?.let { opening ->
            InviteWideButton(stringResource(Res.string.external_invite_open_server, state.serverName(opening.serverId) ?: opening.name)) {
                onIntent(ExternalInviteIntent.OpenProgress(opening.serverId, opening.channelId))
            }
        }
    }
}

/** Web `CancelRequestConfirm`: cancelling makes the approver's emailed link stop working. */
@Composable
private fun ExternalInviteCancelDialog(approverName: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    RaftDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(Res.string.external_invite_cancel_confirm_title),
        description = stringResource(Res.string.external_invite_cancel_confirm_message, approverName),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.external_invite_cancel_confirm_keep),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
            RaftButton(
                text = stringResource(Res.string.external_invite_waiting_cancel),
                onClick = onConfirm,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.external_invite_waiting_canceling),
                modifier = Modifier.testTag(ExternalInviteCancelConfirmTag),
            )
        },
    )
}
