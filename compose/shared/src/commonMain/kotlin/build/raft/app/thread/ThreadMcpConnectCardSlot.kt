package build.raft.app.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.RaftTraceIdNote
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecTree
import build.raft.app.data.DataResult
import build.raft.app.data.McpAccountKind
import build.raft.app.data.McpAppErrorCode
import build.raft.app.data.McpCardAccountChoice
import build.raft.app.data.McpConnectionAuthKind
import build.raft.app.data.McpConnectionAuthorizationView
import build.raft.app.data.McpConnectionStatus
import build.raft.app.data.McpConnectionViewerRole
import build.raft.app.data.McpOAuthAttemptResult
import build.raft.app.data.McpOAuthAttemptStatus
import build.raft.app.data.McpOAuthSignInIntent
import build.raft.app.data.McpOAuthStartRequest
import build.raft.app.data.McpOAuthStartResult
import build.raft.app.data.RaftManagedMcpConnectApiClient
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.initialMcpCardChoice
import build.raft.app.data.isActionCardExpiredRefusal
import build.raft.app.data.isShownMcpMarkExecutedRefusal
import build.raft.app.data.markedExpired
import build.raft.app.data.mcpAppErrorCode
import build.raft.app.data.mcpCardAccountChoices
import build.raft.app.data.mcpCardChoiceUsable
import build.raft.app.data.mcpOAuthProviderError
import build.raft.app.data.resolvedMcpServerId
import build.raft.app.get
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.traceId
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftMessageActionCardActions
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val ACTION_CARD_MCP_LOGIN_REQUIRED_NOTICE_KEY: String = "actionCard.mcp.loginRequired"

internal const val MCP_OAUTH_POLL_INTERVAL_MILLIS: Long = 2_000L
internal const val MCP_OAUTH_POLL_TIMEOUT_MILLIS: Long = 10L * 60L * 1_000L
private const val MCP_ALSO_USED_BY_MAX_NAMES: Int = 3

internal const val ThreadActionCardMcpConnectLoadingTag: String = "mcp-connect-card-loading"
internal const val ThreadActionCardMcpConnectLoadFailedTag: String = "mcp-connect-card-load-failed"
internal const val ThreadActionCardMcpConnectLockedTag: String = "mcp-connect-card-locked"
internal const val ThreadActionCardMcpConnectOpenWebTag: String = "mcp-connect-card-open-web"
internal const val ThreadActionCardMcpConnectAccountsTag: String = "mcp-connect-card-accounts"
internal const val ThreadActionCardMcpConnectAskAdminTag: String = "mcp-connect-card-ask-admin"
internal const val ThreadActionCardMcpConnectCurrentAccountTag: String = "mcp-connect-card-current-account"
internal const val ThreadActionCardMcpConnectErrorTag: String = "mcp-connect-card-error"
internal const val ThreadActionCardMcpConnectAllowTag: String = "mcp-connect-card-allow"
internal const val ThreadActionCardMcpConnectOAuthWaitingTag: String = "mcp-connect-card-oauth-waiting"
internal const val ThreadActionCardMcpConnectOAuthOpenTag: String = "mcp-connect-card-oauth-open"
internal const val ThreadActionCardMcpConnectOAuthCancelTag: String = "mcp-connect-card-oauth-cancel"
internal const val ThreadActionCardMcpConnectReconfirmTag: String = "mcp-connect-card-reconfirm"

internal fun threadActionCardMcpChoiceTag(key: String): String = "mcp-connect-card-choice-$key"

/**
 * Strips the legacy `actionCard.mcp.loginRequired` Notice from an `integration:connect_mcp`
 * card spec when the native account picker slot mounts underneath (mirror of web
 * `ActionCardSpecCard.tsx`).
 */
internal fun ActionCardSpecTree.withoutMcpConnectLoginNotice(): ActionCardSpecTree =
    withoutElements { _, node ->
        node is ActionCardSpecDisplayElement.Notice &&
            node.text.key == ACTION_CARD_MCP_LOGIN_REQUIRED_NOTICE_KEY
    }

/** Outcome of finalizing an `integration:connect_mcp` grant (`mark-executed`). */
internal sealed interface ThreadMcpConnectFinishOutcome {
    data object Completed : ThreadMcpConnectFinishOutcome

    /**
     * `POST /api/actions/:messageId/mark-executed` refused with `MCP_CONNECTION_NOT_AUTHORIZED` or
     * `MCP_CONNECTION_RESULT_MISMATCH` (web `markExecutedRefusal.ts`): show on the card.
     */
    data class Refused(val error: ScreenError) : ThreadMcpConnectFinishOutcome
}

/**
 * Collaborator driving native `integration:connect_mcp` account choice (RFC 087 §7.1,
 * `#wg-mobile-sync:#228`, parity with web `McpConnectCardSlot` + `McpConnectCardAccountChoice`).
 */
internal interface ThreadMcpConnectCardHost {
    suspend fun loadAuthorization(
        appId: String,
        agentId: String,
        sourceServerId: String?,
    ): DataResult<McpConnectionAuthorizationView>

    suspend fun grantExistingAccount(
        messageId: String,
        card: ThreadActionCard,
        appId: String,
        agentId: String,
        choice: McpCardAccountChoice.Existing,
    ): DataResult<ThreadMcpConnectFinishOutcome>

    suspend fun startNewOAuth(
        messageId: String,
        card: ThreadActionCard,
        appId: String,
        agentId: String,
        kind: McpAccountKind,
        connectionName: String,
    ): DataResult<McpOAuthStartResult>

    suspend fun pollOAuthAttempt(
        attemptId: String,
        sourceServerId: String?,
    ): DataResult<McpOAuthAttemptResult>

    suspend fun finishOAuthSignIn(
        messageId: String,
        card: ThreadActionCard,
        appId: String,
        agentId: String,
        kind: McpAccountKind,
    ): DataResult<ThreadMcpConnectFinishOutcome>

    fun openAuthorizationUrl(url: String)

    /**
     * Whether this host shares a cookie jar between `POST /api/mcp/apps/:appId/connections/oauth/start`
     * and the browser completing `GET /api/mcp/oauth/callback`. `botiverse/slock` binds OAuth start
     * to the starting browser with an `HttpOnly` `raft_mcp_oauth_*` cookie (`managedMcpOAuthCallbackIsBound`),
     * so mobile Ktor -> external system browser cannot satisfy the callback check and hands new
     * OAuth choices cleanly to the card on Raft Web (`onOpenInWeb`) instead of failing with HTTP 400.
     */
    val supportsNativeOAuthStart: Boolean get() = true

    val pollIntervalMillis: Long get() = MCP_OAUTH_POLL_INTERVAL_MILLIS
    val pollTimeoutMillis: Long get() = MCP_OAUTH_POLL_TIMEOUT_MILLIS
}

/** Production [ThreadMcpConnectCardHost] wired to [RaftManagedMcpConnectApiClient] and `mark-executed`. */
internal class RealThreadMcpConnectCardHost(
    private val apiClient: RaftManagedMcpConnectApiClient,
    private val markExecuted: suspend (messageId: String, card: ThreadActionCard, mcpServerId: String, agentId: String) -> DataResult<ThreadActionCard>,
    private val applyCard: (messageId: String, card: ThreadActionCard) -> Unit,
    private val onNoPermission: (DataResult.NoPermission) -> Boolean = { false },
    private val openExternalUrl: (String) -> Unit,
    private val onApproved: () -> Unit = {},
    override val supportsNativeOAuthStart: Boolean = false,
    override val pollIntervalMillis: Long = MCP_OAUTH_POLL_INTERVAL_MILLIS,
    override val pollTimeoutMillis: Long = MCP_OAUTH_POLL_TIMEOUT_MILLIS,
) : ThreadMcpConnectCardHost {
    private fun serverIdOverride(sourceServerId: String?): ServerId? =
        sourceServerId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId)

    override suspend fun loadAuthorization(
        appId: String,
        agentId: String,
        sourceServerId: String?,
    ): DataResult<McpConnectionAuthorizationView> {
        val result = apiClient.getConnectionAuthorization(appId, agentId, serverIdOverride(sourceServerId))
        if (result is DataResult.NoPermission) onNoPermission(result)
        return result
    }

    private fun propagateUnitFailure(result: DataResult<Unit>): DataResult<ThreadMcpConnectFinishOutcome>? =
        when (result) {
            is DataResult.Content -> null
            is DataResult.Error -> result
            is DataResult.NoPermission -> {
                onNoPermission(result)
                result
            }
            is DataResult.Empty -> result
        }

    override suspend fun grantExistingAccount(
        messageId: String,
        card: ThreadActionCard,
        appId: String,
        agentId: String,
        choice: McpCardAccountChoice.Existing,
    ): DataResult<ThreadMcpConnectFinishOutcome> {
        val serverOverride = serverIdOverride(card.sourceServerId)
        val grantResult =
            if (choice.kind == McpAccountKind.Shared && choice.allAgents) {
                // Team default ("All agents"): follow the default (connectionId: null) so the
                // Agent moves with future default changes; never pin it. Also turns the App on.
                apiClient.followDefaultAccount(appId, agentId, serverOverride)
            } else {
                val addResult = apiClient.addConnectionAgent(choice.option.connectionId, agentId, serverOverride)
                propagateUnitFailure(addResult)?.let { return it }
                if (choice.kind == McpAccountKind.Shared) {
                    apiClient.ensureAppEnabled(appId, agentId, serverOverride)
                } else {
                    DataResult.Content(Unit)
                }
            }
        propagateUnitFailure(grantResult)?.let { return it }
        return completeMarkExecuted(messageId, card, appId, agentId)
    }

    override suspend fun startNewOAuth(
        messageId: String,
        card: ThreadActionCard,
        appId: String,
        agentId: String,
        kind: McpAccountKind,
        connectionName: String,
    ): DataResult<McpOAuthStartResult> {
        val request =
            McpOAuthStartRequest(
                kind = kind,
                name = connectionName,
                makeDefault = false,
                intent = McpOAuthSignInIntent(agentIds = listOf(agentId), actionCardMessageId = messageId),
            )
        val result = apiClient.startConnectionOAuth(appId, request, serverIdOverride(card.sourceServerId))
        if (result is DataResult.NoPermission) onNoPermission(result)
        return result
    }

    override suspend fun pollOAuthAttempt(
        attemptId: String,
        sourceServerId: String?,
    ): DataResult<McpOAuthAttemptResult> {
        val result = apiClient.pollOAuthAttempt(attemptId, serverIdOverride(sourceServerId))
        if (result is DataResult.NoPermission) onNoPermission(result)
        return result
    }

    override suspend fun finishOAuthSignIn(
        messageId: String,
        card: ThreadActionCard,
        appId: String,
        agentId: String,
        kind: McpAccountKind,
    ): DataResult<ThreadMcpConnectFinishOutcome> {
        if (kind == McpAccountKind.Shared) {
            val enableResult = apiClient.ensureAppEnabled(appId, agentId, serverIdOverride(card.sourceServerId))
            propagateUnitFailure(enableResult)?.let { return it }
        }
        return completeMarkExecuted(messageId, card, appId, agentId)
    }

    override fun openAuthorizationUrl(url: String) {
        openExternalUrl(url)
    }

    private suspend fun completeMarkExecuted(
        messageId: String,
        card: ThreadActionCard,
        appId: String,
        agentId: String,
    ): DataResult<ThreadMcpConnectFinishOutcome> =
        when (val markResult = markExecuted(messageId, card, appId, agentId)) {
            is DataResult.Content -> {
                applyCard(messageId, markResult.data)
                if (markResult.data.isExecuted) onApproved()
                DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
            }
            is DataResult.Error -> {
                when {
                    markResult.error.isShownMcpMarkExecutedRefusal() ->
                        DataResult.Content(ThreadMcpConnectFinishOutcome.Refused(markResult.error))
                    markResult.error.isActionCardExpiredRefusal() -> {
                        applyCard(messageId, card.markedExpired())
                        DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
                    }
                    else -> {
                        // Mirror of web markExecutedRefusal.ts: the connection authorization already
                        // succeeded; swallow non-authorization bookkeeping failures so the user is
                        // not falsely told their authorization failed.
                        DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
                    }
                }
            }
            is DataResult.NoPermission -> {
                onNoPermission(markResult)
                DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
            }
            is DataResult.Empty -> DataResult.Content(ThreadMcpConnectFinishOutcome.Completed)
        }
}

private data class McpOAuthWaitingSession(
    val attemptId: String,
    val authorizationUrl: String,
    val kind: McpAccountKind,
)

private sealed interface McpCardDisplayError {
    val traceId: String?

    data class FromError(val error: ScreenError) : McpCardDisplayError {
        override val traceId: String? get() = error.traceId
    }

    data class FromPermission(val serverMessage: String?, override val traceId: String?) : McpCardDisplayError

    data object SignInFailed : McpCardDisplayError {
        override val traceId: String? get() = null
    }

    data object SignInExpired : McpCardDisplayError {
        override val traceId: String? get() = null
    }
}

/**
 * Footer mounted inside [ThreadActionCardSpecView] for an actionable `integration:connect_mcp`
 * card while `mcp_personal_accounts` is enabled:
 * - `reconfirm_required`: "Confirm again" button (dispatches [ThreadActionCardSpecIntent.ReconfirmMcpConnect]).
 * - `frozen`: disabled button matching frozen frame rules.
 * - `prepared`: mounts [ThreadMcpConnectCardSlot].
 */
@Composable
internal fun ThreadMcpConnectCardFooter(
    messageId: String,
    card: ThreadActionCard,
    busyElementKey: String?,
    host: ThreadMcpConnectCardHost?,
    onReconfirm: () -> Unit,
    onOpenInWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workingLabel = stringResource(Res.string.thread_action_card_busy_working)
    when {
        card.needsReconfirmation -> {
            val reconfirmBusy = busyElementKey == ACTION_CARD_MCP_CONNECT_RECONFIRM_ELEMENT_ID
            RaftMessageActionCardActions(modifier = modifier) {
                RaftButton(
                    text = stringResource(Res.string.thread_action_card_reconfirm),
                    onClick = onReconfirm,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    enabled = busyElementKey == null,
                    loading = reconfirmBusy,
                    loadingLabel = workingLabel,
                    modifier = Modifier.testTag(ThreadActionCardMcpConnectReconfirmTag),
                )
            }
        }
        card.isFrozen -> {
            RaftMessageActionCardActions(modifier = modifier) {
                RaftButton(
                    text = stringResource(Res.string.thread_action_card_mcp_accounts_allow),
                    onClick = {},
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    enabled = false,
                    modifier = Modifier.testTag(ThreadActionCardMcpConnectAllowTag),
                )
            }
        }
        host == null -> {
            RaftMessageActionCardActions(modifier = modifier) {
                RaftButton(
                    text = stringResource(Res.string.thread_action_card_open_on_web),
                    onClick = onOpenInWeb,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    modifier = Modifier.testTag(ThreadActionCardMcpConnectOpenWebTag),
                )
            }
        }
        else -> {
            val appId = card.resolvedMcpServerId
            val agentId = card.agentId?.trim()?.takeIf { it.isNotEmpty() }
            if (appId != null && agentId != null) {
                ThreadMcpConnectCardSlot(
                    messageId = messageId,
                    card = card,
                    appId = appId,
                    agentId = agentId,
                    host = host,
                    onOpenInWeb = onOpenInWeb,
                    modifier = modifier,
                )
            } else {
                RaftMessageActionCardActions(modifier = modifier) {
                    RaftButton(
                        text = stringResource(Res.string.thread_action_card_open_on_web),
                        onClick = onOpenInWeb,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag(ThreadActionCardMcpConnectOpenWebTag),
                    )
                }
            }
        }
    }
}

/**
 * Native Connect card account choice slot (mirror of web `McpConnectCardSlot` +
 * `McpConnectCardBody` + `McpConnectCardAccountChoice`, RFC 087 §7.1, `#16087` + `#16241`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThreadMcpConnectCardSlot(
    messageId: String,
    card: ThreadActionCard,
    appId: String,
    agentId: String,
    host: ThreadMcpConnectCardHost,
    onOpenInWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = LocalThreadActionCardNames.current
    val scope = rememberCoroutineScope()
    val currentCard by rememberUpdatedState(card)
    val currentHost by rememberUpdatedState(host)
    val agentName =
        names.name(ThreadActionCardEntityKind.Agent, agentId)?.takeIf { it.isNotBlank() }
            ?: card.agentName?.takeIf { it.isNotBlank() }
            ?: agentId.take(8)

    var reloadToken by remember(messageId, appId, agentId) { mutableIntStateOf(0) }
    var loading by remember(messageId, appId, agentId) { mutableStateOf(true) }
    var view by remember(messageId, appId, agentId) { mutableStateOf<McpConnectionAuthorizationView?>(null) }
    var loadError by remember(messageId, appId, agentId) { mutableStateOf<McpCardDisplayError?>(null) }
    var pickedKey by remember(messageId, appId, agentId) { mutableStateOf<String?>(null) }
    var submitting by remember(messageId, appId, agentId) { mutableStateOf(false) }
    var actionError by remember(messageId, appId, agentId) { mutableStateOf<McpCardDisplayError?>(null) }
    var waitingSession by remember(messageId, appId, agentId) { mutableStateOf<McpOAuthWaitingSession?>(null) }

    LaunchedEffect(messageId, appId, agentId, card.sourceServerId, reloadToken) {
        if (view == null) loading = true
        loadError = null
        when (val result = currentHost.loadAuthorization(appId, agentId, currentCard.sourceServerId)) {
            is DataResult.Content -> {
                view = result.data
                loading = false
            }
            is DataResult.Error -> {
                if (view == null) loadError = McpCardDisplayError.FromError(result.error)
                loading = false
            }
            is DataResult.NoPermission -> {
                if (view == null) {
                    loadError = McpCardDisplayError.FromPermission(result.blocker.serverMessage, result.blocker.traceId)
                }
                loading = false
            }
            is DataResult.Empty -> {
                if (view == null) loadError = McpCardDisplayError.FromError(ScreenError(code = "mcp_load_empty"))
                loading = false
            }
        }
    }

    // Poll OAuth sign-in attempt while waiting; automatically cancelled if the card scrolls away or closes.
    LaunchedEffect(waitingSession) {
        val session = waitingSession ?: return@LaunchedEffect
        var elapsed = 0L
        while (elapsed <= currentHost.pollTimeoutMillis) {
            when (val polled = currentHost.pollOAuthAttempt(session.attemptId, currentCard.sourceServerId)) {
                is DataResult.Content -> {
                    when (polled.data.status) {
                        McpOAuthAttemptStatus.Connected -> {
                            waitingSession = null
                            actionError = null
                            submitting = true
                            when (val finish = currentHost.finishOAuthSignIn(messageId, currentCard, appId, agentId, session.kind)) {
                                is DataResult.Content -> {
                                    val finishedOutcome = finish.data
                                    if (finishedOutcome is ThreadMcpConnectFinishOutcome.Refused) {
                                        actionError = McpCardDisplayError.FromError(finishedOutcome.error)
                                    }
                                }
                                is DataResult.Error -> actionError = McpCardDisplayError.FromError(finish.error)
                                is DataResult.NoPermission ->
                                    actionError = McpCardDisplayError.FromPermission(finish.blocker.serverMessage, finish.blocker.traceId)
                                is DataResult.Empty -> Unit
                            }
                            submitting = false
                            reloadToken++
                            return@LaunchedEffect
                        }
                        McpOAuthAttemptStatus.Expired -> {
                            waitingSession = null
                            actionError = McpCardDisplayError.SignInExpired
                            return@LaunchedEffect
                        }
                        McpOAuthAttemptStatus.Failed -> {
                            waitingSession = null
                            actionError = McpCardDisplayError.SignInFailed
                            return@LaunchedEffect
                        }
                        McpOAuthAttemptStatus.Pending -> Unit
                    }
                }
                is DataResult.Error -> {
                    if (!polled.error.retryable) {
                        waitingSession = null
                        actionError = McpCardDisplayError.SignInFailed
                        return@LaunchedEffect
                    }
                }
                is DataResult.NoPermission -> {
                    waitingSession = null
                    actionError = McpCardDisplayError.FromPermission(polled.blocker.serverMessage, polled.blocker.traceId)
                    return@LaunchedEffect
                }
                is DataResult.Empty -> Unit
            }
            val step = currentHost.pollIntervalMillis.coerceAtLeast(1L)
            delay(step)
            elapsed += step
        }
        waitingSession = null
        actionError = McpCardDisplayError.SignInExpired
    }

    val loadedView = view
    when {
        loading && loadedView == null -> {
            RaftText(
                text = stringResource(Res.string.common_loading),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
                modifier = modifier.fillMaxWidth().padding(top = 4.dp).testTag(ThreadActionCardMcpConnectLoadingTag),
            )
        }
        loadedView == null -> {
            Column(
                modifier = modifier.fillMaxWidth().padding(top = 4.dp).testTag(ThreadActionCardMcpConnectLoadFailedTag),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RaftBanner(
                    description = stringResource(Res.string.thread_action_card_mcp_load_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    action = {
                        RaftButton(
                            text = stringResource(Res.string.common_retry),
                            onClick = { reloadToken++ },
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                        )
                    },
                )
                RaftTraceIdNote(loadError?.traceId)
            }
        }
        loadedView.options == null -> {
            // Server flag `mcp_personal_accounts` is OFF: observer sees locked copy; manager opens on web.
            if (loadedView.viewerRole == McpConnectionViewerRole.Observer) {
                RaftBanner(
                    description = stringResource(Res.string.thread_action_card_mcp_locked),
                    status = RaftBannerStatus.Info,
                    size = RaftBannerSize.Sm,
                    modifier = modifier.padding(top = 4.dp).testTag(ThreadActionCardMcpConnectLockedTag),
                )
            } else {
                RaftMessageActionCardActions(modifier = modifier) {
                    RaftButton(
                        text = stringResource(Res.string.thread_action_card_open_on_web),
                        onClick = onOpenInWeb,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        modifier = Modifier.testTag(ThreadActionCardMcpConnectOpenWebTag),
                    )
                }
            }
        }
        else -> {
            val choices = remember(loadedView) { mcpCardAccountChoices(loadedView) }
            if (choices.isEmpty()) {
                val headersElsewhere =
                    loadedView.viewerRole == McpConnectionViewerRole.ServerManager &&
                        loadedView.app?.authKind == McpConnectionAuthKind.Headers
                RaftText(
                    text =
                        if (headersElsewhere) {
                            stringResource(Res.string.thread_action_card_mcp_headers_elsewhere)
                        } else {
                            stringResource(Res.string.thread_action_card_mcp_accounts_ask_admin, agentName)
                        },
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundMuted,
                    modifier = modifier.fillMaxWidth().padding(top = 4.dp).testTag(ThreadActionCardMcpConnectAskAdminTag),
                )
                return
            }
            val selectedKey =
                pickedKey?.takeIf { key -> choices.any { it.key == key } }
                    ?: initialMcpCardChoice(choices, loadedView, agentId)
            val selected = choices.firstOrNull { it.key == selectedKey }
            val appName = loadedView.app?.name.orEmpty()
            val unnamed = stringResource(Res.string.thread_action_card_mcp_connection_unnamed)
            val loadedOptions = loadedView.options
            val hasPersonalConnections = loadedOptions?.personal?.connections?.isNotEmpty() == true
            val currentAccountLine = mcpCurrentAccountLine(loadedView, choices, agentName)
            val controlsLocked = submitting || waitingSession != null

            Column(
                modifier = modifier.fillMaxWidth().padding(top = 4.dp).testTag(ThreadActionCardMcpConnectAccountsTag),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actionError?.let { err ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftBanner(
                            description = mcpDisplayErrorMessage(err),
                            status = RaftBannerStatus.Warning,
                            size = RaftBannerSize.Sm,
                            modifier = Modifier.testTag(ThreadActionCardMcpConnectErrorTag),
                        )
                        RaftTraceIdNote(err.traceId)
                    }
                }
                RaftText(
                    text = stringResource(Res.string.thread_action_card_mcp_accounts_heading, agentName),
                    style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.SemiBold),
                    color = RaftTheme.colors.foreground,
                )
                if (currentAccountLine != null) {
                    RaftText(
                        text = currentAccountLine,
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted,
                        modifier = Modifier.testTag(ThreadActionCardMcpConnectCurrentAccountTag),
                    )
                }
                RaftRadioGroup(Modifier.fillMaxWidth()) {
                    for (choice in choices) {
                        val (title, detail) =
                            mcpChoiceText(
                                choice = choice,
                                appName = appName,
                                unnamed = unnamed,
                                agentId = agentId,
                                agentName = agentName,
                                currentAccountConnectionId = loadedView.account?.connectionId,
                                hasPersonalConnections = hasPersonalConnections,
                                names = names,
                            )
                        val usable = mcpCardChoiceUsable(choice)
                        RaftRadioRow(
                            label = title,
                            description = detail,
                            selected = choice.key == selectedKey,
                            enabled = !controlsLocked && usable,
                            onClick = {
                                pickedKey = choice.key
                                actionError = null
                            },
                            modifier = Modifier.testTag(threadActionCardMcpChoiceTag(choice.key)),
                        )
                    }
                }
                val activeWaiting = waitingSession
                if (activeWaiting != null) {
                    RaftBanner(
                        description = stringResource(Res.string.thread_action_card_mcp_oauth_waiting),
                        status = RaftBannerStatus.Info,
                        size = RaftBannerSize.Sm,
                        pending = true,
                        modifier = Modifier.testTag(ThreadActionCardMcpConnectOAuthWaitingTag),
                        action = {
                            RaftButton(
                                text = stringResource(Res.string.thread_action_card_mcp_oauth_cancel_sign_in),
                                onClick = {
                                    waitingSession = null
                                    actionError = null
                                },
                                variant = RaftButtonVariant.Ghost,
                                size = RaftButtonSize.Md,
                                modifier = Modifier.testTag(ThreadActionCardMcpConnectOAuthCancelTag),
                            )
                            RaftButton(
                                text = stringResource(Res.string.thread_action_card_mcp_oauth_open_sign_in_page),
                                onClick = { currentHost.openAuthorizationUrl(activeWaiting.authorizationUrl) },
                                variant = RaftButtonVariant.Default,
                                size = RaftButtonSize.Md,
                                modifier = Modifier.testTag(ThreadActionCardMcpConnectOAuthOpenTag),
                            )
                        },
                    )
                }
                val allowLabel =
                    when {
                        selected is McpCardAccountChoice.New && !currentHost.supportsNativeOAuthStart ->
                            stringResource(Res.string.thread_action_card_open_on_web)
                        selected is McpCardAccountChoice.New ->
                            stringResource(Res.string.thread_action_card_mcp_accounts_sign_in, appName)
                        submitting -> stringResource(Res.string.thread_action_card_mcp_accounts_allowing)
                        else -> stringResource(Res.string.thread_action_card_mcp_accounts_allow)
                    }
                val allowingLabel = stringResource(Res.string.thread_action_card_mcp_accounts_allowing)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftButton(
                        text = allowLabel,
                        onClick = {
                            val active = selected ?: return@RaftButton
                            if (!mcpCardChoiceUsable(active) || controlsLocked) return@RaftButton
                            actionError = null
                            when (active) {
                                is McpCardAccountChoice.New -> {
                                    if (!currentHost.supportsNativeOAuthStart) {
                                        onOpenInWeb()
                                        return@RaftButton
                                    }
                                    submitting = true
                                    scope.launch {
                                        val started =
                                            currentHost.startNewOAuth(
                                                messageId = messageId,
                                                card = currentCard,
                                                appId = appId,
                                                agentId = agentId,
                                                kind = active.kind,
                                                connectionName = appName.ifBlank { unnamed },
                                            )
                                        submitting = false
                                        when (started) {
                                            is DataResult.Content -> {
                                                waitingSession =
                                                    McpOAuthWaitingSession(
                                                        attemptId = started.data.attemptId,
                                                        authorizationUrl = started.data.authorizationUrl,
                                                        kind = active.kind,
                                                    )
                                                currentHost.openAuthorizationUrl(started.data.authorizationUrl)
                                            }
                                            is DataResult.Error -> actionError = McpCardDisplayError.FromError(started.error)
                                            is DataResult.NoPermission ->
                                                actionError = McpCardDisplayError.FromPermission(started.blocker.serverMessage, started.blocker.traceId)
                                            is DataResult.Empty ->
                                                actionError = McpCardDisplayError.FromError(ScreenError(code = "mcp_oauth_start_empty"))
                                        }
                                    }
                                }
                                is McpCardAccountChoice.Existing -> {
                                    submitting = true
                                    scope.launch {
                                        val outcome =
                                            currentHost.grantExistingAccount(
                                                messageId = messageId,
                                                card = currentCard,
                                                appId = appId,
                                                agentId = agentId,
                                                choice = active,
                                            )
                                        submitting = false
                                        when (outcome) {
                                            is DataResult.Content -> {
                                                val finishOutcome = outcome.data
                                                if (finishOutcome is ThreadMcpConnectFinishOutcome.Refused) {
                                                    actionError = McpCardDisplayError.FromError(finishOutcome.error)
                                                }
                                            }
                                            is DataResult.Error -> actionError = McpCardDisplayError.FromError(outcome.error)
                                            is DataResult.NoPermission ->
                                                actionError = McpCardDisplayError.FromPermission(outcome.blocker.serverMessage, outcome.blocker.traceId)
                                            is DataResult.Empty ->
                                                actionError = McpCardDisplayError.FromError(ScreenError(code = "mcp_grant_empty"))
                                        }
                                    }
                                }
                            }
                        },
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md,
                        enabled = selected != null && mcpCardChoiceUsable(selected) && !controlsLocked,
                        loading = submitting,
                        loadingLabel = allowingLabel,
                        modifier = Modifier.testTag(ThreadActionCardMcpConnectAllowTag),
                    )
                }
            }
        }
    }
}

@Composable
private fun mcpCurrentAccountLine(
    view: McpConnectionAuthorizationView,
    choices: List<McpCardAccountChoice>,
    agentName: String,
): String? {
    val account = view.account ?: return null
    val listed = account.connectionId != null && choices.any { it.key == account.connectionId }
    if (listed) return null
    val accountName =
        account.label?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                account.kind == McpAccountKind.Personal && !account.ownerDisplayName.isNullOrBlank() ->
                    stringResource(Res.string.thread_action_card_mcp_accounts_current_personal, account.ownerDisplayName!!)
                account.kind == McpAccountKind.Personal ->
                    stringResource(Res.string.thread_action_card_mcp_accounts_current_other_member)
                else ->
                    stringResource(Res.string.thread_action_card_mcp_accounts_current_shared)
            }
    return stringResource(Res.string.thread_action_card_mcp_accounts_current, agentName, accountName)
}

@Composable
private fun mcpChoiceText(
    choice: McpCardAccountChoice,
    appName: String,
    unnamed: String,
    agentId: String,
    agentName: String,
    currentAccountConnectionId: String?,
    hasPersonalConnections: Boolean,
    names: ThreadActionCardNameLookup,
): Pair<String, String> {
    if (choice is McpCardAccountChoice.New) {
        val title =
            when (choice.kind) {
                McpAccountKind.Personal ->
                    if (hasPersonalConnections) {
                        stringResource(Res.string.thread_action_card_mcp_accounts_new_personal_another, appName)
                    } else {
                        stringResource(Res.string.thread_action_card_mcp_accounts_new_personal, appName)
                    }
                McpAccountKind.Shared -> stringResource(Res.string.thread_action_card_mcp_accounts_new_shared)
            }
        val detail = stringResource(Res.string.thread_action_card_mcp_accounts_opens_sign_in, appName)
        return title to detail
    }
    val existing = choice as McpCardAccountChoice.Existing
    val option = existing.option
    val parts = mutableListOf<String>()
    parts +=
        when {
            existing.kind == McpAccountKind.Personal ->
                stringResource(Res.string.thread_action_card_mcp_accounts_your_account)
            existing.allAgents ->
                stringResource(Res.string.thread_action_card_mcp_accounts_shared_all_agents)
            else ->
                stringResource(Res.string.thread_action_card_mcp_accounts_shared_account)
        }
    if (option.status != McpConnectionStatus.Ready) {
        parts += mcpConnectionStatusLabel(option.status)
    }
    if (agentId in option.agentIds || currentAccountConnectionId == option.connectionId) {
        parts += stringResource(Res.string.thread_action_card_mcp_accounts_agent_uses_it, agentName)
    }
    val otherNames =
        option.agentIds
            .filter { it != agentId }
            .mapNotNull { id -> names.name(ThreadActionCardEntityKind.Agent, id)?.takeIf { it.isNotBlank() } }
    if (otherNames.size > MCP_ALSO_USED_BY_MAX_NAMES) {
        val head = otherNames.take(MCP_ALSO_USED_BY_MAX_NAMES).joinToString(", ")
        parts += stringResource(Res.string.thread_action_card_mcp_accounts_also_used_by_more, head, otherNames.size - MCP_ALSO_USED_BY_MAX_NAMES)
    } else if (otherNames.isNotEmpty()) {
        parts += stringResource(Res.string.thread_action_card_mcp_accounts_also_used_by, otherNames.joinToString(", "))
    }
    val title =
        option.label?.trim()?.takeIf { it.isNotEmpty() }
            ?: option.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: unnamed
    return title to parts.joinToString(" · ")
}

@Composable
private fun mcpConnectionStatusLabel(status: McpConnectionStatus): String =
    when (status) {
        McpConnectionStatus.Ready -> stringResource(Res.string.thread_action_card_mcp_status_ready)
        McpConnectionStatus.Pending -> stringResource(Res.string.thread_action_card_mcp_status_pending)
        McpConnectionStatus.NeedsAuth -> stringResource(Res.string.thread_action_card_mcp_status_needs_auth)
        McpConnectionStatus.Expired -> stringResource(Res.string.thread_action_card_mcp_status_expired)
        McpConnectionStatus.Error -> stringResource(Res.string.thread_action_card_mcp_status_error)
    }

@Composable
private fun mcpDisplayErrorMessage(error: McpCardDisplayError): String =
    when (error) {
        McpCardDisplayError.SignInFailed -> stringResource(Res.string.thread_action_card_mcp_oauth_sign_in_failed)
        McpCardDisplayError.SignInExpired -> stringResource(Res.string.thread_action_card_mcp_oauth_sign_in_expired)
        is McpCardDisplayError.FromPermission ->
            error.serverMessage?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_mcp_error_forbidden)
        is McpCardDisplayError.FromError -> mcpScreenErrorMessage(error.error)
    }

@Composable
private fun mcpScreenErrorMessage(error: ScreenError): String =
    when (error.mcpAppErrorCode()) {
        McpAppErrorCode.RateLimited -> stringResource(Res.string.thread_action_card_mcp_error_rate_limited)
        McpAppErrorCode.Unreachable -> stringResource(Res.string.thread_action_card_mcp_error_unreachable)
        McpAppErrorCode.CredentialsRejected -> stringResource(Res.string.thread_action_card_mcp_error_credentials_rejected)
        McpAppErrorCode.OAuthBusy -> stringResource(Res.string.thread_action_card_mcp_error_oauth_busy)
        McpAppErrorCode.OAuthRegistrationRejected -> {
            val providerCode = error.mcpOAuthProviderError()
            if (providerCode != null) {
                stringResource(Res.string.thread_action_card_mcp_error_oauth_registration_rejected, providerCode)
            } else {
                stringResource(Res.string.thread_action_card_mcp_error_oauth_registration_rejected_no_code)
            }
        }
        McpAppErrorCode.OAuthRegistrationUnsupported -> stringResource(Res.string.thread_action_card_mcp_error_oauth_registration_unsupported)
        McpAppErrorCode.OAuthDiscoveryFailed -> stringResource(Res.string.thread_action_card_mcp_error_oauth_discovery_failed)
        McpAppErrorCode.OAuthSetupFailed -> stringResource(Res.string.thread_action_card_mcp_error_oauth_setup_failed)
        McpAppErrorCode.ConnectionRequestInvalid -> stringResource(Res.string.thread_action_card_mcp_error_connection_request_invalid)
        McpAppErrorCode.HeaderNamesUnset -> stringResource(Res.string.thread_action_card_mcp_error_header_names_unset)
        McpAppErrorCode.OAuthUnsafeUrl -> stringResource(Res.string.thread_action_card_mcp_error_oauth_unsafe_url)
        McpAppErrorCode.Forbidden -> stringResource(Res.string.thread_action_card_mcp_error_forbidden)
        McpAppErrorCode.Generic ->
            error.serverMessage?.takeIf { it.isNotBlank() }
                ?: stringResource(Res.string.thread_action_card_mcp_error_generic)
    }
