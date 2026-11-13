package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.DataResult
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ServerJoinAgreement
import build.raft.app.data.ServerJoinAnswer
import build.raft.app.data.ServerJoinInviteInfo
import build.raft.app.data.ServerJoinLinkApi
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

internal const val SERVER_JOIN_PAGE = "compose_server_join"
private const val SERVER_JOIN_TOKEN = "serverJoinToken"

internal const val ServerJoinPageTag = "server-join-page"
internal const val ServerJoinButtonTag = "server-join-button"
internal const val ServerJoinErrorTag = "server-join-error"

/** Identity only (the Route / PageData rule): the link's token; the page reads the invite itself. */
internal fun serverJoinPageData(token: String): JSONObject =
    JSONObject().apply {
        put("pageName", SERVER_JOIN_PAGE)
        put("instanceId", "server-join")
        put(SERVER_JOIN_TOKEN, token)
    }

internal fun JSONObject.serverJoinTokenOrNull(): String? = optString(SERVER_JOIN_TOKEN).trim().takeIf(String::isNotEmpty)

/** Opens the join page for [token] from [this] page with [storeScope]; false when it can't be opened. */
internal fun Pager.openServerJoinPage(token: String, storeScope: RaftShellStoreScope?): Boolean =
    openRaftPage(pageName = SERVER_JOIN_PAGE, pageData = serverJoinPageData(token), storeScopeOverride = storeScope)

/**
 * The page a server join link (`/join/{token}`) opens (web `InviteAcceptPage`, signed in): the
 * server's name and who is inside, its agreement if it has one, and Join server. Joined, the app
 * opens on that server with the server list reloaded.
 */
@Page(SERVER_JOIN_PAGE, supportInLocal = true)
internal class RaftServerJoinPage : AuthenticatedProductPager(AuthenticatedProductRoute.ServerJoin) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { ServerJoinScreen() }
    }
}

/** What the join page shows. */
internal sealed interface ServerJoinView {
    data object Loading : ServerJoinView

    /** The invite can't be read or used: [message] (a server's own text, or null for [fallback]). */
    data class Failed(val fallback: ServerJoinFailure, val message: String? = null, val traceId: String? = null) : ServerJoinView

    data class Ready(val info: ServerJoinInviteInfo, val joining: Boolean = false) : ServerJoinView

    /** Joined, but the app couldn't open the server by itself (web's "You're in" page). */
    data class Joined(val serverName: String) : ServerJoinView
}

internal enum class ServerJoinFailure { InvalidOrExpired, TooManyAttempts, FailedToLoad, FailedToAccept, HumanSeatLimit }

private fun DataResult<*>.traceIdOrNull(): String? =
    when (this) {
        is DataResult.Error -> error.traceId
        is DataResult.NoPermission -> blocker.traceId
        else -> null
    }

@Composable
private fun ServerJoinScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    val token = remember(params.toString()) { params.serverJoinTokenOrNull() }
    val storeScope = remember(params.toString(), pager.pagerId) { params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId) }
    val documentShell = remember(params.toString()) { params.optBoolean(RAFT_DOCUMENT_SHELL, false) }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val api = remember(sharedStores, storeScope) { ServerJoinLinkApi(sharedStores.networkClient(storeScope)) { bridge.log("server_join $it") } }
    val authDataSource = remember(sharedStores, storeScope) { sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope) }
    val scope = rememberCoroutineScope()
    var view by remember(token) { mutableStateOf<ServerJoinView>(ServerJoinView.Loading) }
    var toldOwners by remember(token) { mutableStateOf(false) }

    LaunchedEffect(token) {
        if (token == null) {
            view = ServerJoinView.Failed(ServerJoinFailure.InvalidOrExpired)
            return@LaunchedEffect
        }
        val result = api.info(token)
        view =
            when (val answer = (result as? DataResult.Content)?.data) {
                is ServerJoinAnswer.Ok -> ServerJoinView.Ready(answer.value)
                is ServerJoinAnswer.Refused ->
                    ServerJoinView.Failed(
                        when (answer.refusal.status) {
                            404 -> ServerJoinFailure.InvalidOrExpired
                            429 -> ServerJoinFailure.TooManyAttempts
                            else -> ServerJoinFailure.FailedToLoad
                        },
                        traceId = answer.refusal.traceId,
                    )
                null -> ServerJoinView.Failed(ServerJoinFailure.FailedToLoad, traceId = result.traceIdOrNull())
            }
        bridge.log("server_join_info result=${view::class.simpleName}")
    }
    // The page says a full link's owners were told: tell them, once (web useTellOwnersSeatsFull).
    val readyInfo = (view as? ServerJoinView.Ready)?.info
    LaunchedEffect(readyInfo?.humanSeatLimitReached, readyInfo?.seatNoticeEnabled) {
        val info = readyInfo ?: return@LaunchedEffect
        if (token == null || toldOwners || !info.humanSeatLimitReached || !info.seatNoticeEnabled) return@LaunchedEffect
        toldOwners = true
        runCatchingPreservingCancellation { api.tellOwnersSeatsFull(token) }
    }

    fun join(info: ServerJoinInviteInfo) {
        val joinToken = token ?: return
        view = ServerJoinView.Ready(info, joining = true)
        scope.launch {
            val result = api.accept(joinToken, info.agreement?.id)
            when (val answer = (result as? DataResult.Content)?.data) {
                is ServerJoinAnswer.Ok -> {
                    val joined = answer.value
                    // Into the server joined, with the server list reloaded (web loadServers + /s/{slug}).
                    runCatchingPreservingCancellation { authDataSource.loadServers() }
                    val selected = runCatchingPreservingCancellation { authDataSource.selectServer(joined.serverId) }.getOrNull()
                    bridge.log("server_join_accept result=joined select=${selected is SharedAuthResult.Success}")
                    if (selected is SharedAuthResult.Success) {
                        pager.openAppShellForSelectedServer(
                            readySession = selected.data,
                            sourceStoreScope = storeScope,
                            sourceDocumentShell = documentShell,
                            environmentId = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                            bridge = bridge,
                        )
                    } else {
                        view = ServerJoinView.Joined(joined.serverName.ifBlank { info.serverName })
                    }
                }
                is ServerJoinAnswer.Refused -> {
                    val refusal = answer.refusal
                    bridge.log("server_join_accept result=refused status=${refusal.status} agreement=${refusal.agreement != null}")
                    view =
                        when {
                            // A new or changed agreement: show it, then Agree & join (web).
                            refusal.agreement != null -> ServerJoinView.Ready(info.copy(agreement = refusal.agreement))
                            refusal.code == "human_seat_limit_reached" ->
                                ServerJoinView.Failed(ServerJoinFailure.HumanSeatLimit, traceId = refusal.traceId)
                            // The server's own reason (revoked, expired, used up), as the web shows it.
                            refusal.status == 400 && refusal.error != null ->
                                ServerJoinView.Failed(ServerJoinFailure.FailedToAccept, message = refusal.error, traceId = refusal.traceId)
                            else -> ServerJoinView.Failed(ServerJoinFailure.FailedToAccept, traceId = refusal.traceId)
                        }
                }
                null -> {
                    bridge.log("server_join_accept result=error")
                    view = ServerJoinView.Failed(ServerJoinFailure.FailedToAccept, traceId = result.traceIdOrNull())
                }
            }
        }
    }

    RaftDesignSystemTheme {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).testTag(ServerJoinPageTag)) {
            RaftTopPageHeader(title = "", onBack = router::closePage, backgroundColor = RaftColor.SurfaceWhite)
            ServerJoinContent(view = view, onJoin = ::join, onClose = router::closePage)
        }
    }
}

/** The page's content for [view] (the page and UI tests compose it the same way). */
@Composable
internal fun ServerJoinContent(view: ServerJoinView, onJoin: (ServerJoinInviteInfo) -> Unit, onClose: () -> Unit) {
    when (view) {
        ServerJoinView.Loading ->
            ServerJoinFrame(stringResource(Res.string.server_join_loading_title), null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RaftSpinner(size = RaftSpinnerSize.Md) }
            }
        is ServerJoinView.Failed ->
            ServerJoinFrame(stringResource(Res.string.server_join_error_title), null) {
                Column(Modifier.fillMaxWidth().testTag(ServerJoinErrorTag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftBanner(
                        description = view.message ?: serverJoinFailureText(view.fallback),
                        status = RaftBannerStatus.Warning,
                        size = RaftBannerSize.Sm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RaftTraceIdNote(view.traceId)
                }
                ServerJoinWideButton(stringResource(Res.string.server_join_go_to_raft), variant = RaftButtonVariant.Default, onClick = onClose)
            }
        is ServerJoinView.Joined ->
            ServerJoinFrame(
                stringResource(Res.string.server_join_accepted_title),
                serverJoinBold(Res.string.server_join_accepted_description, view.serverName),
            ) {
                ServerJoinWideButton(stringResource(Res.string.server_join_continue_to_raft), onClick = onClose)
            }
        is ServerJoinView.Ready -> ServerJoinReady(view, onJoin, onClose)
    }
}

@Composable
private fun ServerJoinReady(view: ServerJoinView.Ready, onJoin: (ServerJoinInviteInfo) -> Unit, onClose: () -> Unit) {
    val info = view.info
    val joinLink = info.kind == "join_link"
    val description =
        if (joinLink || info.inviterName.isNullOrBlank()) {
            serverJoinBold(Res.string.server_join_join_link_description, info.serverName)
        } else {
            serverJoinBold(Res.string.server_join_inviter_description, info.inviterName.orEmpty(), info.serverName)
        }
    // Signed in (the app's page always is): a full server says so, and Join goes.
    val seatsFull = info.humanSeatLimitReached
    ServerJoinFrame(
        stringResource(if (joinLink) Res.string.server_join_join_this_server else Res.string.server_join_youre_invited),
        description,
        insidePreview = serverJoinInsidePreview(info),
    ) {
        info.agreement?.let { ServerJoinAgreementCard(it) }
        if (seatsFull) {
            if (info.seatNoticeEnabled) {
                RaftBanner(
                    title = stringResource(Res.string.server_join_seats_full_title, info.serverName),
                    description = stringResource(Res.string.server_join_seats_full_body),
                    status = RaftBannerStatus.Info,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                RaftBanner(
                    description = stringResource(Res.string.server_join_human_seat_limit),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ServerJoinWideButton(stringResource(Res.string.server_join_go_to_raft), variant = RaftButtonVariant.Default, onClick = onClose)
        } else {
            ServerJoinWideButton(
                text =
                    when {
                        view.joining -> stringResource(Res.string.server_join_joining)
                        info.agreement != null -> stringResource(Res.string.server_join_agree_and_join)
                        else -> stringResource(Res.string.server_join_join)
                    },
                modifier = Modifier.testTag(ServerJoinButtonTag),
                loading = view.joining,
                enabled = !view.joining,
            ) { onJoin(info) }
        }
    }
}

@Composable
private fun ServerJoinAgreementCard(agreement: ServerJoinAgreement) {
    val uriHandler = LocalUriHandler.current
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                RaftText(agreement.title, style = RaftTheme.typography.headline, color = RaftTheme.colors.foreground)
                RaftText(
                    stringResource(Res.string.server_join_agreement_version, agreement.version),
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundHint,
                )
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 224.dp).verticalScroll(rememberScrollState())) {
                RaftMarkdownBody(body = agreement.bodyMarkdown, onOpenUrl = { uriHandler.openUri(it) })
            }
        }
    }
}

@Composable
private fun serverJoinFailureText(failure: ServerJoinFailure): String =
    when (failure) {
        ServerJoinFailure.InvalidOrExpired -> stringResource(Res.string.server_join_invalid_or_expired)
        ServerJoinFailure.TooManyAttempts -> stringResource(Res.string.server_join_too_many_attempts)
        ServerJoinFailure.FailedToLoad -> stringResource(Res.string.server_join_failed_to_load)
        ServerJoinFailure.FailedToAccept -> stringResource(Res.string.server_join_failed_to_accept)
        ServerJoinFailure.HumanSeatLimit -> stringResource(Res.string.server_join_human_seat_limit)
    }

/**
 * Who is inside (web `renderInsidePreview`): everyone when the server hides its counts, else the
 * humans and/or agents, each count bold; null when there is no one to mention.
 */
@Composable
private fun serverJoinInsidePreview(info: ServerJoinInviteInfo): AnnotatedString? {
    if (info.insideCountsHidden) {
        return serverJoinBold(Res.string.server_join_inside_everyone, stringResource(Res.string.server_join_inside_everyone_word))
    }
    val humans = info.memberCount
    val agents = info.agentCount
    val humansText =
        stringResource(if (humans == 1) Res.string.server_join_humans_one else Res.string.server_join_humans_other, humans)
    val agentsText =
        stringResource(if (agents == 1) Res.string.server_join_agents_one else Res.string.server_join_agents_other, agents)
    return when {
        humans > 0 && agents > 0 -> serverJoinBold(Res.string.server_join_inside_both, humansText, agentsText)
        humans > 0 -> serverJoinBold(Res.string.server_join_inside_humans, humansText)
        agents > 0 -> serverJoinBold(Res.string.server_join_inside_agents, agentsText)
        else -> null
    }
}

/** [resource] with its `%n$s` arguments [values] in bold (the web's `<b>` / `<server>` chunks). */
@Composable
private fun serverJoinBold(resource: StringResource, vararg values: String): AnnotatedString {
    val template = stringResource(resource, *values.map { ServerJoinArgMark + it + ServerJoinArgMark }.toTypedArray())
    return buildAnnotatedString {
        template.split(ServerJoinArgMark).forEachIndexed { index, part ->
            if (index % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
        }
    }
}

private const val ServerJoinArgMark = "\uE000"

@Composable
private fun ServerJoinFrame(
    title: String,
    description: AnnotatedString?,
    insidePreview: AnnotatedString? = null,
    body: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftText(title, style = RaftTheme.typography.title, color = RaftTheme.colors.foreground)
                description?.let { RaftText(it, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted) }
                insidePreview?.let { RaftText(it, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted) }
            }
            body()
        }
    }
}

@Composable
private fun ServerJoinWideButton(
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
