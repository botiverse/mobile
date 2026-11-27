package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpOutcome
import build.raft.app.markdown.raftAppLinkAsciiHost
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * Per-page "Log in with Raft" session state machine for InAppBrowserPage (#proj-workspace:#356 §5).
 * Mirrors slock desktop `apps/desktop/src/main/webPreviewRaftSignIn.ts` and web
 * `packages/web/src/features/desktopBrowserSignIn/DesktopBrowserSignInHost.tsx`:
 * - Pending lock (one in-flight server check per page; dropping a shown sheet triggers quiet period).
 * - 10 s quiet period after a shown request ends or a server/registration refusal.
 * - 10 min request lifetime matching the server authorization code lifetime.
 * - Main-frame document commit, page close, account switch, or environment change drops any pending
 *   or shown request immediately (nothing loaded).
 * - Consent-skip path posts `confirmSkipped = true` directly (never through step-up); any non-200
 *   (including 409 `consent_required` or 403 `second_factor_step_up_required`) falls back to the sheet.
 * - Logs carry ONLY request id, plain client id, and fixed reason — NEVER a URL, code, state, nonce,
 *   or code challenge.
 */

/** A pending request lives this long: the server's authorization code lifetime (slock `REQUEST_LIFETIME_MS`). */
internal const val RAFT_SIGN_IN_REQUEST_LIFETIME_MS: Long = 10 * 60_000L

/** After a shown request ends or the server refuses one, this page raises nothing for this long (slock `RAISE_AGAIN_AFTER_MS`). */
internal const val RAFT_SIGN_IN_RAISE_AGAIN_AFTER_MS: Long = 10_000L

/** Tapjacking guard on Continue: disabled for 1 s after first ready and again after app resume (slock `APPROVE_AFTER_MS`). */
internal const val RAFT_SIGN_IN_APPROVE_AFTER_MS: Long = 1_000L

private val RaftAgentInboundOAuthScopes: Set<String> = setOf("agent:event:write", "agent:notification:write")
private val RaftSignInWhitespaceRegex = Regex("\\s+")

/** Fixed drop/decline reasons for structured diagnostics — never free text or URLs. */
internal enum class RaftBrowserSignInDropReason(val wire: String) {
    HiddenPage("hidden_page"),
    Background("background"),
    Initiator("initiator"),
    OtherSite("other_site"),
    SignedOut("signed_out"),
    NoWebOrigin("no_web_origin"),
    Pending("pending"),
    RaisedRecently("raised_recently"),
    Superseded("superseded"),
    ServerRefused("server_refused"),
    InvalidSetup("invalid_setup"),
    UnregisteredReturn("unregistered_return"),
    PageClosed("page_closed"),
    Navigated("navigated"),
    Expired("expired"),
    AccountChanged("account_changed"),
    EnvironmentChanged("environment_changed"),
    ResultRefused("result_refused"),
    LoadFailed("load_failed"),
}

internal data class RaftBrowserSignInServerOption(
    val id: String,
    val name: String,
    val slug: String,
)

internal data class RaftBrowserSignInAccountContext(
    val accountId: String,
    val signedInAsLabel: String,
    val currentServerId: String?,
    val servers: List<RaftBrowserSignInServerOption>,
    val environmentKey: String,
)

internal sealed interface RaftBrowserSignInSheetMode {
    data class ServerReady(
        val readyServers: List<RaftBrowserSignInServerOption>,
        val selectedServerId: String?,
    ) : RaftBrowserSignInSheetMode

    data class AccountReady(
        val loginModeChanged: Boolean = false,
    ) : RaftBrowserSignInSheetMode

    data object NotAvailable : RaftBrowserSignInSheetMode

    data class InstallRequired(
        val canInstall: Boolean,
        val accountMode: Boolean,
    ) : RaftBrowserSignInSheetMode

    data class Suspended(
        val accountMode: Boolean,
    ) : RaftBrowserSignInSheetMode

    data class DisallowedScopes(
        val reason: String?,
        val disallowedScopes: List<String>,
    ) : RaftBrowserSignInSheetMode

    data object AgentInboundScopes : RaftBrowserSignInSheetMode

    data object RootIssuerRequired : RaftBrowserSignInSheetMode

    data object LookupFailed : RaftBrowserSignInSheetMode
}

internal data class RaftBrowserSignInSheetState(
    val requestId: String,
    val appName: String?,
    val logoUrl: String?,
    val description: String?,
    /** ASCII host (with port when non-default) where Raft sends the OAuth answer. */
    val returnHost: String,
    val signedInAsLabel: String,
    val scopes: List<String>,
    val mode: RaftBrowserSignInSheetMode,
    val submitting: Boolean = false,
    val inlineError: String? = null,
    val inlineErrorTraceId: String? = null,
) {
    val canApprove: Boolean
        get() =
            when (val current = mode) {
                is RaftBrowserSignInSheetMode.ServerReady ->
                    !submitting && !current.selectedServerId.isNullOrBlank()
                is RaftBrowserSignInSheetMode.AccountReady -> !submitting
                else -> false
            }
}

internal data class RaftBrowserSignInUiState(
    val sheet: RaftBrowserSignInSheetState? = null,
    /** True while an intercepted sign-in request is resolving before consent shows or finishes. */
    val resolving: Boolean = false,
    /** Set when a caught authorize navigation was declined; ↗ opens this original authorize URL in the system browser. */
    val declinedAuthorizeUrl: String? = null,
    /** Reason [declinedAuthorizeUrl] was declined so the UI can distinguish cooldown from unsupported flows. */
    val declinedReason: RaftBrowserSignInDropReason? = null,
)

private data class ParsedSetupQuery(
    val oidcFlow: Boolean,
    val nonce: String?,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
    val serverHint: String?,
    val state: String?,
    val scopes: List<String>,
    val asksToConfirmAgain: Boolean,
)

private data class LookupBatchResult(
    val lookups: List<Pair<RaftBrowserSignInServerOption, RaftBrowserSignInLookupResult>>,
    val resolvedServerIds: Set<String>,
    val complete: Boolean,
)

internal class RaftBrowserSignInSession(
    private val scope: CoroutineScope,
    private val authorizeOriginProvider: () -> String?,
    private val webUrlProvider: () -> String?,
    private val setupOriginProvider: () -> String? = {
        webUrlProvider()?.let { raftAuthorizeOrigin(it, allowLoopback = false) }
    },
    private val raftHostsProvider: () -> Collection<String>,
    private val featureEnabledProvider: () -> Boolean,
    private val accountProvider: () -> RaftBrowserSignInAccountContext?,
    private val preferredServerId: String?,
    private val isPageVisible: () -> Boolean,
    private val isAppForeground: () -> Boolean,
    private val fetchAuthorize: suspend (String) -> RaftAuthorizeResponse? = ::raftFetchAuthorizeUnfollowed,
    private val api: RaftBrowserSignInApi,
    private val stepUp: SecondFactorStepUpCoordinator? = null,
    private val loadUrl: (String) -> Boolean,
    private val onSignedInWithoutAsking: (appName: String) -> Unit,
    private val onOpenInBrowser: (String) -> Unit,
    private val nowMs: () -> Long = defaultMonotonicNowMs(),
    private val newRequestId: () -> String = ::defaultSignInRequestId,
    private val log: (String) -> Unit = {},
) {
    private class PendingRequest(
        val id: String,
        val generation: Long,
        val createdAtMs: Long,
        val accountId: String,
        val environmentKey: String,
        val signedInAsLabel: String,
        val servers: List<RaftBrowserSignInServerOption>,
        val currentServerId: String?,
        val logClientId: String?,
        val requestState: String?,
        val originalAuthorizeUrl: String,
        var returnUrl: String? = null,
        var setupRequest: RaftSignInSetupRequest? = null,
        var setupQuery: ParsedSetupQuery? = null,
        var shown: Boolean = false,
        var loginModeRetryCount: Int = 0,
        var timerJob: Job? = null,
        var workJob: Job? = null,
        var approveJob: Job? = null,
    )

    private val _uiState = MutableStateFlow(RaftBrowserSignInUiState())
    val uiState: StateFlow<RaftBrowserSignInUiState> = _uiState.asStateFlow()

    private var pageGeneration: Long = 0L
    private var quietUntilMs: Long = Long.MIN_VALUE
    private var pending: PendingRequest? = null
    private var closed: Boolean = false

    /**
     * Synchronous main-frame navigation catch called from WebView before routing, fencing, and
     * `Raft-Host` re-issue. Returns true iff this navigation was Raft's authorize endpoint or
     * direct setup page while the feature flag is on (cancelling the navigation so Raft never loads
     * inside the in-app browser).
     */
    fun onMainFrameNavigation(ask: RaftWebViewMainFrameCatchAsk): Boolean {
        if (closed || !featureEnabledProvider()) return false
        val raftHosts = raftHostsProvider()
        val request: RaftBrowserSignInEntry =
            raftAuthorizeRequest(ask.url, authorizeOriginProvider(), raftHosts)
                ?: raftSetupEntryRequest(ask.url, setupOriginProvider(), raftHosts)
                ?: return false

        val account = accountProvider()
        if (account == null) {
            decline(RaftBrowserSignInDropReason.SignedOut, ask.url, request.logClientId)
            return true
        }
        val webUrl = webUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }
        if (request is RaftAuthorizeRequest && webUrl == null) {
            decline(RaftBrowserSignInDropReason.NoWebOrigin, ask.url, request.logClientId)
            return true
        }
        val askRefusal =
            raftSignInAskRefusal(
                RaftSignInAsk(
                    redirectUri = request.redirectUri,
                    starter = ask.starter,
                    page = ask.page,
                    committed = ask.committed,
                    hops = ask.hops,
                    pageVisible = isPageVisible(),
                    appForeground = isAppForeground(),
                ),
            )
        if (askRefusal != null) {
            decline(askRefusal.toDropReason(), ask.url, request.logClientId)
            return true
        }
        val active = pending
        if (active != null && !active.shown) {
            // An earlier request is still with the server: do not let a page spam server fetches.
            decline(RaftBrowserSignInDropReason.Pending, ask.url, request.logClientId)
            return true
        }
        if (active != null) {
            // Superseding a shown sheet arms the 10 s quiet period, so a page cannot swap the sheet
            // under the user's finger.
            dropPending(RaftBrowserSignInDropReason.Superseded)
        }
        if (nowMs() < quietUntilMs) {
            decline(RaftBrowserSignInDropReason.RaisedRecently, ask.url, request.logClientId)
            return true
        }

        val currentPending =
            PendingRequest(
                id = newRequestId(),
                generation = pageGeneration,
                createdAtMs = nowMs(),
                accountId = account.accountId,
                environmentKey = account.environmentKey,
                signedInAsLabel = account.signedInAsLabel,
                servers = account.servers,
                currentServerId = account.currentServerId,
                logClientId = request.logClientId,
                requestState = request.state,
                originalAuthorizeUrl = ask.url,
            )
        pending = currentPending
        _uiState.value =
            RaftBrowserSignInUiState(
                sheet = null,
                resolving = true,
                declinedAuthorizeUrl = null,
                declinedReason = null,
            )
        logEvent("intercepted", currentPending)
        currentPending.timerJob =
            scope.launch {
                delay(RAFT_SIGN_IN_REQUEST_LIFETIME_MS)
                if (pending === currentPending) {
                    dropPending(RaftBrowserSignInDropReason.Expired)
                }
            }
        currentPending.workJob =
            scope.launch {
                runAuthorizePipeline(currentPending, request, webUrl)
            }
        return true
    }

    /** A main-frame document committed on this page: any pending/shown sign-in for the old document is dropped. */
    fun onPageCommitted() {
        pageGeneration += 1L
        if (pending != null) {
            dropPending(RaftBrowserSignInDropReason.Navigated)
        }
        if (_uiState.value.declinedAuthorizeUrl != null || _uiState.value.declinedReason != null) {
            _uiState.value = _uiState.value.copy(declinedAuthorizeUrl = null, declinedReason = null)
        }
    }

    /** Account sign-out/switch or Developer environment change drops any active request immediately. */
    fun onAccountOrEnvironmentChanged(accountId: String?, environmentKey: String) {
        val active = pending
        if (active != null && (active.accountId != accountId || active.environmentKey != environmentKey)) {
            val reason =
                if (active.accountId != accountId) {
                    RaftBrowserSignInDropReason.AccountChanged
                } else {
                    RaftBrowserSignInDropReason.EnvironmentChanged
                }
            dropPending(reason)
        }
        if ((_uiState.value.declinedAuthorizeUrl != null || _uiState.value.declinedReason != null) && active?.accountId != accountId) {
            _uiState.value = _uiState.value.copy(declinedAuthorizeUrl = null, declinedReason = null)
        }
    }

    /** Page closed / disposed: cancel any active request and step-up without loading anything. */
    fun onPageClosed() {
        if (closed) return
        closed = true
        if (pending != null) {
            dropPending(RaftBrowserSignInDropReason.PageClosed)
        }
        _uiState.value = RaftBrowserSignInUiState()
        cancelOpenStepUp()
    }

    fun dismissDeclinedNotice() {
        if (_uiState.value.declinedAuthorizeUrl != null || _uiState.value.declinedReason != null) {
            _uiState.value = _uiState.value.copy(declinedAuthorizeUrl = null, declinedReason = null)
        }
    }

    fun selectServer(serverId: String) {
        val sheet = _uiState.value.sheet ?: return
        val mode = sheet.mode as? RaftBrowserSignInSheetMode.ServerReady ?: return
        if (sheet.submitting || mode.readyServers.none { it.id == serverId }) return
        _uiState.value =
            _uiState.value.copy(
                sheet =
                    sheet.copy(
                        mode = mode.copy(selectedServerId = serverId),
                        inlineError = null,
                        inlineErrorTraceId = null,
                    ),
            )
    }

    /** User pressed Cancel, ×, Back, or scrim on a shown sheet: loads `error=access_denied` back to the App. */
    fun cancelSheet() {
        if (stepUp?.state?.value != null) return
        val active = pending ?: return
        if (!active.shown) {
            dropPending(RaftBrowserSignInDropReason.Superseded)
            return
        }
        val returnUrl = active.returnUrl
        val state = active.requestState
        forgetPending(active, armQuiet = true)
        _uiState.value = _uiState.value.copy(sheet = null, resolving = false)
        val deniedUrl =
            returnUrl?.let { raftSignInDeniedUrl(it, state, raftHostsProvider()) }
        val unloaded = loadAnswer(active, deniedUrl)
        logEvent("cancelled", active, unloaded)
    }

    /** Non-ready state's ↗ "Open in browser": opens the original authorize URL in the system browser without `access_denied`. */
    fun openOriginalAuthorizeInBrowser() {
        if (stepUp?.state?.value != null) return
        val active = pending ?: return
        val authorizeUrl = active.originalAuthorizeUrl
        forgetPending(active, armQuiet = active.shown)
        _uiState.value = _uiState.value.copy(sheet = null, resolving = false)
        logEvent("opened_in_browser", active)
        onOpenInBrowser(authorizeUrl)
    }

    /** User pressed Continue on a ready sheet. */
    fun approveCurrent() {
        val active = pending ?: return
        val setupRequest = active.setupRequest ?: return
        val setupQuery = active.setupQuery ?: return
        val sheet = _uiState.value.sheet ?: return
        if (!sheet.canApprove || active.approveJob?.isActive == true) return

        val target =
            when (val mode = sheet.mode) {
                is RaftBrowserSignInSheetMode.ServerReady -> {
                    val selectedId = mode.selectedServerId?.takeIf { it.isNotBlank() } ?: return
                    RaftBrowserSignInApproveTarget.Server(selectedId)
                }
                is RaftBrowserSignInSheetMode.AccountReady -> RaftBrowserSignInApproveTarget.Account
                else -> return
            }

        _uiState.value =
            _uiState.value.copy(
                sheet = sheet.copy(submitting = true, inlineError = null, inlineErrorTraceId = null),
            )
        active.approveJob =
            scope.launch {
                val request = buildApproveRequest(setupRequest, setupQuery, target, confirmSkipped = false)
                val outcome =
                    if (stepUp != null) {
                        stepUp.run { api.approve(request) }
                    } else {
                        SecondFactorStepUpOutcome.Completed(api.approve(request))
                    }
                if (!isPendingLive(active)) return@launch
                when (outcome) {
                    SecondFactorStepUpOutcome.Cancelled -> {
                        updateSheet { it.copy(submitting = false) }
                    }
                    is SecondFactorStepUpOutcome.Completed -> handleInteractiveApproveResult(active, setupRequest, setupQuery, outcome.result)
                }
            }
    }

    private suspend fun runAuthorizePipeline(
        currentPending: PendingRequest,
        request: RaftBrowserSignInEntry,
        webUrl: String?,
    ) {
        val answer: RaftSetupAnswer? =
            when (request) {
                is RaftAuthorizeRequest -> {
                    val response =
                        try {
                            fetchAuthorize(request.fetchUrl)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    if (!isPendingLive(currentPending)) return
                    webUrl?.let { raftSetupAnswer(response, it, request, raftHostsProvider()) }
                }
                is RaftSetupEntryRequest -> {
                    RaftSetupAnswer(setupSearch = request.setupSearch, returnUrl = request.redirectUri)
                }
            }
        if (answer == null) {
            refusePending(currentPending, RaftBrowserSignInDropReason.ServerRefused)
            return
        }
        if (!isPageVisible()) {
            dropPending(RaftBrowserSignInDropReason.HiddenPage)
            return
        }
        if (!isAppForeground()) {
            dropPending(RaftBrowserSignInDropReason.Background)
            return
        }
        val setupRequest = raftSignInSetupRequest(answer.setupSearch)
        if (setupRequest == null) {
            refusePending(currentPending, RaftBrowserSignInDropReason.InvalidSetup)
            return
        }
        val setupQuery = parseSetupQuery(setupRequest)
        currentPending.returnUrl = answer.returnUrl
        currentPending.setupRequest = setupRequest
        currentPending.setupQuery = setupQuery

        val effectivePreferredServerId = preferredServerId ?: currentPending.currentServerId
        val lookupBatch =
            lookupAcrossServers(
                setupRequest = setupRequest,
                setupQuery = setupQuery,
                servers = currentPending.servers,
                effectivePreferredServerId = effectivePreferredServerId,
                allowServerSkipEarlyExit = true,
            )
        if (!isPendingLive(currentPending)) return

        var lookups = lookupBatch.lookups
        val foundLookups = lookups.mapNotNull { (_, result) -> (result as? RaftBrowserSignInLookupResult.Found)?.client }
        if (!raftSignInIsRegisteredReturnAddress(setupRequest.returnUrl, foundLookups.map { it.toRuleLookup() })) {
            refusePending(currentPending, RaftBrowserSignInDropReason.UnregisteredReturn)
            return
        }

        val skipTarget =
            raftSignInSkipTarget(
                request = setupRequest,
                lookups = foundLookups.map { it.toRuleLookup() },
                servers = currentPending.servers.map { RaftSignInServer(it.id, it.slug) },
                preferredServerId = effectivePreferredServerId,
            )
        if (skipTarget != null) {
            val approveTarget =
                when (skipTarget) {
                    is RaftSignInSkipTarget.Account -> RaftBrowserSignInApproveTarget.Account
                    is RaftSignInSkipTarget.Server -> RaftBrowserSignInApproveTarget.Server(skipTarget.serverId)
                }
            val skipRequest = buildApproveRequest(setupRequest, setupQuery, approveTarget, confirmSkipped = true)
            val skipResult = api.approve(skipRequest)
            if (!isPendingLive(currentPending)) return
            val approved = (skipResult as? DataResult.Content)?.data as? RaftBrowserSignInApproveOutcome.Approved
            if (approved != null) {
                val successUrl = raftSignInSuccessUrl(approved.returnUrl, approved.code, setupQuery.state)
                val validatedUrl = successUrl?.let { raftSignInResultUrl(it, answer.returnUrl, raftHostsProvider()) }
                if (validatedUrl != null) {
                    forgetPending(currentPending, armQuiet = true)
                    _uiState.value = _uiState.value.copy(resolving = false)
                    val loadRefusal = loadAnswer(currentPending, validatedUrl)
                    logEvent(if (loadRefusal == null) "completed" else "dropped", currentPending, loadRefusal)
                    if (loadRefusal == null) {
                        onSignedInWithoutAsking(skipTarget.name)
                    }
                    return
                }
            }
            // Any non-200 (409 consent_required, 403 step_up_required, network error) falls back to the native sheet.
        }

        val isAccountReady =
            foundLookups.any {
                it.loginMode == RaftSignInLoginMode.Account &&
                    it.availability == RaftSignInAvailability.Ready &&
                    it.returnUrl == setupRequest.returnUrl
            }
        if (!lookupBatch.complete && !isAccountReady) {
            val candidateServers = candidateServersForLookup(currentPending.servers, setupQuery.serverHint)
            val remainingServers = candidateServers.filter { it.id !in lookupBatch.resolvedServerIds }
            if (remainingServers.isNotEmpty()) {
                val remainingLookups = lookupServersAll(setupRequest, remainingServers)
                if (!isPendingLive(currentPending)) return
                val mergedById =
                    buildMap(candidateServers.size) {
                        lookups.forEach { (server, result) -> put(server.id, result) }
                        remainingLookups.forEach { (server, result) -> put(server.id, result) }
                    }
                lookups = candidateServers.mapNotNull { server -> mergedById[server.id]?.let { server to it } }
            }
        }

        if (!isPendingLive(currentPending)) return
        if (!isPageVisible()) {
            dropPending(RaftBrowserSignInDropReason.HiddenPage)
            return
        }
        if (!isAppForeground()) {
            dropPending(RaftBrowserSignInDropReason.Background)
            return
        }

        currentPending.shown = true
        logEvent("shown", currentPending)
        val sheet =
            buildSheetState(
                pending = currentPending,
                setupRequest = setupRequest,
                setupQuery = setupQuery,
                lookups = lookups,
                previousSelectedServerId = null,
                loginModeChanged = false,
            )
        _uiState.value =
            RaftBrowserSignInUiState(
                sheet = sheet,
                resolving = false,
                declinedAuthorizeUrl = null,
                declinedReason = null,
            )
    }

    private suspend fun handleInteractiveApproveResult(
        active: PendingRequest,
        setupRequest: RaftSignInSetupRequest,
        setupQuery: ParsedSetupQuery,
        result: DataResult<RaftBrowserSignInApproveOutcome>,
    ) {
        when (result) {
            is DataResult.Error -> {
                updateSheet {
                    it.copy(
                        submitting = false,
                        inlineError = result.error.serverMessage,
                        inlineErrorTraceId = result.error.traceId,
                    )
                }
            }
            is DataResult.NoPermission -> {
                updateSheet {
                    it.copy(
                        submitting = false,
                        inlineError = result.blocker.serverMessage,
                        inlineErrorTraceId = result.blocker.traceId,
                    )
                }
            }
            is DataResult.Empty -> {
                updateSheet {
                    it.copy(
                        submitting = false,
                        inlineError = null,
                        inlineErrorTraceId = null,
                    )
                }
            }
            is DataResult.Content -> {
                when (val outcome = result.data) {
                    is RaftBrowserSignInApproveOutcome.Approved -> {
                        val expectedReturnUrl = active.returnUrl ?: setupRequest.returnUrl
                        val successUrl = raftSignInSuccessUrl(outcome.returnUrl, outcome.code, setupQuery.state)
                        val validatedUrl = successUrl?.let { raftSignInResultUrl(it, expectedReturnUrl, raftHostsProvider()) }
                        forgetPending(active, armQuiet = true)
                        _uiState.value = _uiState.value.copy(sheet = null)
                        val unloaded = loadAnswer(active, validatedUrl)
                        logEvent(if (unloaded == null) "completed" else "dropped", active, unloaded)
                    }
                    RaftBrowserSignInApproveOutcome.LoginModeChanged -> {
                        if (active.loginModeRetryCount >= 1) {
                            updateSheet {
                                it.copy(
                                    submitting = false,
                                    inlineError = null,
                                    inlineErrorTraceId = null,
                                )
                            }
                            return
                        }
                        active.loginModeRetryCount += 1
                        val previousSelection =
                            (_uiState.value.sheet?.mode as? RaftBrowserSignInSheetMode.ServerReady)?.selectedServerId
                        val freshLookups =
                            lookupAcrossServers(
                                setupRequest = setupRequest,
                                setupQuery = setupQuery,
                                servers = active.servers,
                                effectivePreferredServerId = preferredServerId ?: active.currentServerId,
                                allowServerSkipEarlyExit = false,
                            ).lookups
                        if (!isPendingLive(active)) return
                        val refreshedSheet =
                            buildSheetState(
                                pending = active,
                                setupRequest = setupRequest,
                                setupQuery = setupQuery,
                                lookups = freshLookups,
                                previousSelectedServerId = previousSelection,
                                loginModeChanged = true,
                            )
                        _uiState.value = _uiState.value.copy(sheet = refreshedSheet)
                    }
                    is RaftBrowserSignInApproveOutcome.ScopeNotApproved -> {
                        val expectedReturnUrl = active.returnUrl ?: setupRequest.returnUrl
                        val errorUrl =
                            raftSignInInvalidScopeUrl(
                                errorCode = "OAUTH_SCOPE_NOT_APPROVED",
                                returnUrl = outcome.returnUrl,
                                description = outcome.description,
                                state = setupQuery.state,
                            )
                        val validatedUrl = errorUrl?.let { raftSignInResultUrl(it, expectedReturnUrl, raftHostsProvider()) }
                        if (validatedUrl != null) {
                            forgetPending(active, armQuiet = true)
                            _uiState.value = _uiState.value.copy(sheet = null)
                            val unloaded = loadAnswer(active, validatedUrl)
                            logEvent(if (unloaded == null) "completed" else "dropped", active, unloaded)
                        } else {
                            updateSheet {
                                it.copy(
                                    submitting = false,
                                    inlineError = outcome.description,
                                    inlineErrorTraceId = outcome.traceId,
                                )
                            }
                        }
                    }
                    is RaftBrowserSignInApproveOutcome.AppNotAvailable -> {
                        updateSheet {
                            it.copy(
                                submitting = false,
                                mode = RaftBrowserSignInSheetMode.NotAvailable,
                                inlineError = null,
                                inlineErrorTraceId = outcome.traceId,
                            )
                        }
                    }
                    is RaftBrowserSignInApproveOutcome.InstallationSuspended -> {
                        val isAccount = _uiState.value.sheet?.mode is RaftBrowserSignInSheetMode.AccountReady
                        updateSheet {
                            it.copy(
                                submitting = false,
                                mode = RaftBrowserSignInSheetMode.Suspended(accountMode = isAccount),
                                inlineError = null,
                                inlineErrorTraceId = outcome.traceId,
                            )
                        }
                    }
                    is RaftBrowserSignInApproveOutcome.RootIssuerRequired -> {
                        updateSheet {
                            it.copy(
                                submitting = false,
                                mode = RaftBrowserSignInSheetMode.RootIssuerRequired,
                                inlineError = null,
                                inlineErrorTraceId = outcome.traceId,
                            )
                        }
                    }
                    is RaftBrowserSignInApproveOutcome.ConsentRequired -> {
                        updateSheet {
                            it.copy(
                                submitting = false,
                                inlineError = null,
                                inlineErrorTraceId = outcome.traceId,
                            )
                        }
                    }
                    is RaftBrowserSignInApproveOutcome.Failed -> {
                        updateSheet {
                            it.copy(
                                submitting = false,
                                inlineError = outcome.message,
                                inlineErrorTraceId = outcome.traceId,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun lookupAcrossServers(
        setupRequest: RaftSignInSetupRequest,
        setupQuery: ParsedSetupQuery,
        servers: List<RaftBrowserSignInServerOption>,
        effectivePreferredServerId: String?,
        allowServerSkipEarlyExit: Boolean,
    ): LookupBatchResult {
        val candidateServers = candidateServersForLookup(servers, setupQuery.serverHint)
        if (candidateServers.isEmpty()) {
            return LookupBatchResult(lookups = emptyList(), resolvedServerIds = emptySet(), complete = true)
        }
        val orderedServers =
            if (effectivePreferredServerId != null && candidateServers.size > 1) {
                val preferred = candidateServers.filter { it.id == effectivePreferredServerId }
                if (preferred.isNotEmpty()) {
                    preferred + candidateServers.filter { it.id != effectivePreferredServerId }
                } else {
                    candidateServers
                }
            } else {
                candidateServers
            }
        if (orderedServers.size == 1) {
            val single = lookupServersAll(setupRequest, orderedServers)
            return LookupBatchResult(
                lookups = single,
                resolvedServerIds = single.mapTo(HashSet(1)) { it.first.id },
                complete = true,
            )
        }

        // Prefer the batch lookup endpoint (botiverse/slock #17196 / #17197): one request per 50
        // servers with the preferred server ordered first. Older servers answer HTTP 400
        // (Unsupported) and fall back to the concurrent single-server fast path below.
        when (
            val batch =
                api.lookupClientsBatch(
                    clientId = setupRequest.clientId,
                    serverIds = orderedServers.map { it.id },
                    scope = setupRequest.scope,
                )
        ) {
            is RaftBrowserSignInBatchLookupResult.Completed -> {
                val inOriginalOrder =
                    candidateServers.map { server ->
                        val result = batch.results[server.id] ?: RaftBrowserSignInLookupResult.Failed()
                        server to result
                    }
                return LookupBatchResult(
                    lookups = inOriginalOrder,
                    resolvedServerIds = candidateServers.mapTo(HashSet(candidateServers.size)) { it.id },
                    complete = true,
                )
            }
            RaftBrowserSignInBatchLookupResult.Unsupported -> Unit
        }

        val hasAgentInbound = setupQuery.scopes.any { it in RaftAgentInboundOAuthScopes }
        val resultsByServerId = LinkedHashMap<String, RaftBrowserSignInLookupResult>(orderedServers.size)
        var decidedEarly = false

        coroutineScope {
            val channel = Channel<Pair<RaftBrowserSignInServerOption, RaftBrowserSignInLookupResult>>(Channel.UNLIMITED)
            val jobs =
                orderedServers.map { server ->
                    launch {
                        val result = api.lookupClient(setupRequest.clientId, server.id, setupRequest.scope)
                        channel.trySend(server to result)
                    }
                }
            try {
                var remaining = orderedServers.size
                while (remaining > 0) {
                    val (server, result) = channel.receive()
                    resultsByServerId[server.id] = result
                    remaining -= 1
                    if (
                        canDecideLookupEarly(
                            server = server,
                            result = result,
                            setupRequest = setupRequest,
                            effectivePreferredServerId = effectivePreferredServerId,
                            asksToConfirmAgain = setupQuery.asksToConfirmAgain,
                            hasAgentInbound = hasAgentInbound,
                            allowServerSkipEarlyExit = allowServerSkipEarlyExit,
                        )
                    ) {
                        decidedEarly = remaining > 0
                        break
                    }
                }
            } finally {
                jobs.forEach { it.cancel() }
                channel.close()
            }
        }

        val inOriginalOrder =
            candidateServers.mapNotNull { server ->
                resultsByServerId[server.id]?.let { server to it }
            }
        return LookupBatchResult(
            lookups = inOriginalOrder,
            resolvedServerIds = resultsByServerId.keys,
            complete = !decidedEarly,
        )
    }

    private suspend fun lookupServersAll(
        setupRequest: RaftSignInSetupRequest,
        servers: List<RaftBrowserSignInServerOption>,
    ): List<Pair<RaftBrowserSignInServerOption, RaftBrowserSignInLookupResult>> =
        coroutineScope {
            servers.map { server ->
                async {
                    server to api.lookupClient(setupRequest.clientId, server.id, setupRequest.scope)
                }
            }.awaitAll()
        }

    private fun candidateServersForLookup(
        servers: List<RaftBrowserSignInServerOption>,
        serverHint: String?,
    ): List<RaftBrowserSignInServerOption> {
        val hint = serverHint.orEmpty()
        return if (hint.isNotEmpty()) {
            servers.filter { it.id == hint || it.slug == hint }
        } else {
            servers
        }
    }

    private fun canDecideLookupEarly(
        server: RaftBrowserSignInServerOption,
        result: RaftBrowserSignInLookupResult,
        setupRequest: RaftSignInSetupRequest,
        effectivePreferredServerId: String?,
        asksToConfirmAgain: Boolean,
        hasAgentInbound: Boolean,
        allowServerSkipEarlyExit: Boolean,
    ): Boolean {
        val client = (result as? RaftBrowserSignInLookupResult.Found)?.client ?: return false
        if (client.returnUrl != setupRequest.returnUrl) return false
        if (client.availability != RaftSignInAvailability.Ready) return false
        return when (client.loginMode) {
            RaftSignInLoginMode.Account -> true
            RaftSignInLoginMode.Server -> {
                if (!allowServerSkipEarlyExit || asksToConfirmAgain || hasAgentInbound) return false
                if (server.id != effectivePreferredServerId) return false
                val scopeAllowed =
                    client.scopeValidation?.allowed != false &&
                        client.scopeValidation?.disallowedScopes.isNullOrEmpty()
                val consentCovered = client.consent?.let { it.granted && it.coversRequested } == true
                scopeAllowed && consentCovered
            }
        }
    }

    private fun buildSheetState(
        pending: PendingRequest,
        setupRequest: RaftSignInSetupRequest,
        setupQuery: ParsedSetupQuery,
        lookups: List<Pair<RaftBrowserSignInServerOption, RaftBrowserSignInLookupResult>>,
        previousSelectedServerId: String?,
        loginModeChanged: Boolean,
    ): RaftBrowserSignInSheetState {
        val hint = setupQuery.serverHint.orEmpty()
        val candidateLookups =
            if (hint.isNotEmpty()) {
                lookups.filter { (server, _) -> server.id == hint || server.slug == hint }
            } else {
                lookups
            }
        // Only Servers whose lookup matches the requested returnUrl are shown (HumanLoginSetupPage.tsx:203-207).
        val matchingEntries =
            candidateLookups.mapNotNull { (server, result) ->
                val client = (result as? RaftBrowserSignInLookupResult.Found)?.client ?: return@mapNotNull null
                if (client.returnUrl != setupRequest.returnUrl) return@mapNotNull null
                server to client
            }
        val someFailed = candidateLookups.any { (_, result) -> result is RaftBrowserSignInLookupResult.Failed }
        val readyEntries = matchingEntries.filter { (_, client) -> client.availability == RaftSignInAvailability.Ready }
        val installEntries = matchingEntries.filter { (_, client) -> client.availability == RaftSignInAvailability.InstallRequired }
        val suspendedEntries = matchingEntries.filter { (_, client) -> client.availability == RaftSignInAvailability.Suspended }
        val accountEntries = matchingEntries.filter { (_, client) -> client.loginMode == RaftSignInLoginMode.Account }
        val isAccountMode = accountEntries.isNotEmpty()

        val detailsClient =
            readyEntries.firstOrNull()?.second
                ?: installEntries.firstOrNull()?.second
                ?: suspendedEntries.firstOrNull()?.second
                ?: matchingEntries.firstOrNull()?.second

        val hasAgentInbound = setupQuery.scopes.any { it in RaftAgentInboundOAuthScopes }
        val scopeValidation = detailsClient?.scopeValidation
        val disallowedScopes = scopeValidation?.disallowedScopes.orEmpty()

        val mode: RaftBrowserSignInSheetMode =
            when {
                hasAgentInbound -> RaftBrowserSignInSheetMode.AgentInboundScopes
                disallowedScopes.isNotEmpty() ->
                    RaftBrowserSignInSheetMode.DisallowedScopes(
                        reason = scopeValidation?.reason,
                        disallowedScopes = disallowedScopes,
                    )
                isAccountMode && hint.isNotEmpty() -> RaftBrowserSignInSheetMode.RootIssuerRequired
                isAccountMode -> {
                    val accountReady = readyEntries.any { (_, client) -> client.loginMode == RaftSignInLoginMode.Account }
                    when {
                        accountReady -> RaftBrowserSignInSheetMode.AccountReady(loginModeChanged = loginModeChanged)
                        installEntries.isNotEmpty() ->
                            RaftBrowserSignInSheetMode.InstallRequired(
                                canInstall = installEntries.any { (_, client) -> client.canInstall },
                                accountMode = true,
                            )
                        suspendedEntries.isNotEmpty() -> RaftBrowserSignInSheetMode.Suspended(accountMode = true)
                        someFailed -> RaftBrowserSignInSheetMode.LookupFailed
                        else -> RaftBrowserSignInSheetMode.NotAvailable
                    }
                }
                readyEntries.isNotEmpty() -> {
                    val readyServers = readyEntries.map { (server, _) -> server }
                    val effectivePreferred = preferredServerId ?: pending.currentServerId
                    val selected =
                        when {
                            previousSelectedServerId != null && readyServers.any { it.id == previousSelectedServerId } ->
                                previousSelectedServerId
                            effectivePreferred != null && readyServers.any { it.id == effectivePreferred } ->
                                effectivePreferred
                            readyServers.size == 1 -> readyServers.first().id
                            else -> null
                        }
                    RaftBrowserSignInSheetMode.ServerReady(
                        readyServers = readyServers,
                        selectedServerId = selected,
                    )
                }
                installEntries.isNotEmpty() ->
                    RaftBrowserSignInSheetMode.InstallRequired(
                        canInstall = installEntries.any { (_, client) -> client.canInstall },
                        accountMode = false,
                    )
                suspendedEntries.isNotEmpty() -> RaftBrowserSignInSheetMode.Suspended(accountMode = false)
                someFailed -> RaftBrowserSignInSheetMode.LookupFailed
                else -> RaftBrowserSignInSheetMode.NotAvailable
            }

        return RaftBrowserSignInSheetState(
            requestId = pending.id,
            appName = detailsClient?.name?.takeIf { it.isNotBlank() },
            logoUrl = detailsClient?.logoUrl,
            description = detailsClient?.description,
            returnHost = formatAsciiReturnHost(setupRequest.returnHost),
            signedInAsLabel = pending.signedInAsLabel,
            scopes = setupQuery.scopes,
            mode = mode,
        )
    }

    private fun buildApproveRequest(
        setupRequest: RaftSignInSetupRequest,
        setupQuery: ParsedSetupQuery,
        target: RaftBrowserSignInApproveTarget,
        confirmSkipped: Boolean,
    ): RaftBrowserSignInApproveRequest =
        RaftBrowserSignInApproveRequest(
            clientId = setupRequest.clientId,
            target = target,
            returnUrl = setupRequest.returnUrl,
            scopes = setupQuery.scopes,
            oidcFlow = setupQuery.oidcFlow,
            nonce = setupQuery.nonce,
            codeChallenge = setupQuery.codeChallenge,
            codeChallengeMethod = setupQuery.codeChallengeMethod,
            serverHint = if (target is RaftBrowserSignInApproveTarget.Server) setupQuery.serverHint else null,
            confirmSkipped = confirmSkipped,
        )

    private fun isPendingLive(candidate: PendingRequest): Boolean {
        if (closed || pending !== candidate) return false
        if (pageGeneration != candidate.generation) return false
        if (nowMs() - candidate.createdAtMs >= RAFT_SIGN_IN_REQUEST_LIFETIME_MS) return false
        val current = accountProvider() ?: return false
        return current.accountId == candidate.accountId && current.environmentKey == candidate.environmentKey
    }

    private fun loadAnswer(
        candidate: PendingRequest,
        url: String?,
    ): RaftBrowserSignInDropReason? {
        if (closed) return RaftBrowserSignInDropReason.PageClosed
        if (nowMs() - candidate.createdAtMs >= RAFT_SIGN_IN_REQUEST_LIFETIME_MS) return RaftBrowserSignInDropReason.Expired
        if (pageGeneration != candidate.generation) return RaftBrowserSignInDropReason.Navigated
        val current = accountProvider()
        if (current == null || current.accountId != candidate.accountId) return RaftBrowserSignInDropReason.AccountChanged
        if (current.environmentKey != candidate.environmentKey) return RaftBrowserSignInDropReason.EnvironmentChanged
        if (url.isNullOrBlank()) return RaftBrowserSignInDropReason.ResultRefused
        return if (loadUrl(url)) null else RaftBrowserSignInDropReason.LoadFailed
    }

    private fun refusePending(
        candidate: PendingRequest,
        reason: RaftBrowserSignInDropReason,
    ) {
        if (pending !== candidate) return
        val authorizeUrl = candidate.originalAuthorizeUrl
        forgetPending(candidate, armQuiet = true)
        logEvent("dropped", candidate, reason)
        if (!closed && pageGeneration == candidate.generation) {
            _uiState.value =
                RaftBrowserSignInUiState(
                    sheet = null,
                    resolving = false,
                    declinedAuthorizeUrl = authorizeUrl,
                    declinedReason = reason,
                )
        } else if (_uiState.value.resolving || _uiState.value.sheet != null) {
            _uiState.value = _uiState.value.copy(sheet = null, resolving = false)
        }
    }

    private fun dropPending(reason: RaftBrowserSignInDropReason) {
        val active = pending ?: return
        val wasShown = active.shown
        forgetPending(active, armQuiet = wasShown)
        logEvent("dropped", active, reason)
        if (_uiState.value.sheet != null || _uiState.value.resolving) {
            _uiState.value = _uiState.value.copy(sheet = null, resolving = false)
        }
    }

    private fun forgetPending(
        candidate: PendingRequest,
        armQuiet: Boolean,
    ) {
        if (pending === candidate) {
            pending = null
        }
        candidate.timerJob?.cancel()
        candidate.workJob?.cancel()
        candidate.approveJob?.cancel()
        if (armQuiet) {
            quietUntilMs = maxOf(quietUntilMs, nowMs() + RAFT_SIGN_IN_RAISE_AGAIN_AFTER_MS)
        }
        cancelOpenStepUp()
    }

    private fun cancelOpenStepUp() {
        val coordinator = stepUp ?: return
        if (coordinator.state.value != null) {
            scope.launch { coordinator.accept(SecondFactorStepUpIntent.Cancel) }
        }
    }

    private fun decline(
        reason: RaftBrowserSignInDropReason,
        authorizeUrl: String,
        logClientId: String?,
    ) {
        log("raft_browser_sign_in event=dropped${logClientId?.let { " clientId=$it" }.orEmpty()} reason=${reason.wire}")
        if (pending == null) {
            _uiState.value =
                _uiState.value.copy(
                    resolving = false,
                    declinedAuthorizeUrl = authorizeUrl,
                    declinedReason = reason,
                )
        }
    }

    private fun updateSheet(transform: (RaftBrowserSignInSheetState) -> RaftBrowserSignInSheetState) {
        val current = _uiState.value.sheet ?: return
        _uiState.value = _uiState.value.copy(sheet = transform(current))
    }

    private fun logEvent(
        event: String,
        request: PendingRequest,
        reason: RaftBrowserSignInDropReason? = null,
    ) {
        val parts =
            buildList {
                add("raft_browser_sign_in")
                add("event=$event")
                add("requestId=${request.id}")
                request.logClientId?.let { add("clientId=$it") }
                reason?.let { add("reason=${it.wire}") }
            }
        log(parts.joinToString(" "))
    }
}

private fun RaftSignInAskRefusal.toDropReason(): RaftBrowserSignInDropReason =
    when (this) {
        RaftSignInAskRefusal.HiddenPage -> RaftBrowserSignInDropReason.HiddenPage
        RaftSignInAskRefusal.Background -> RaftBrowserSignInDropReason.Background
        RaftSignInAskRefusal.Initiator -> RaftBrowserSignInDropReason.Initiator
        RaftSignInAskRefusal.OtherSite -> RaftBrowserSignInDropReason.OtherSite
    }

private fun parseSetupQuery(setupRequest: RaftSignInSetupRequest): ParsedSetupQuery {
    val query = RaftBrowserQuery.parse(setupRequest.setupSearch)
    val scopes = setupRequest.scope.split(RaftSignInWhitespaceRegex).filter { it.isNotEmpty() }
    return ParsedSetupQuery(
        oidcFlow = query?.first("flow") == "oidc",
        nonce = query?.first("nonce")?.takeIf { it.isNotEmpty() },
        codeChallenge = query?.first("code_challenge")?.takeIf { it.isNotEmpty() },
        codeChallengeMethod = query?.first("code_challenge_method")?.takeIf { it.isNotEmpty() },
        serverHint = query?.first("server")?.takeIf { it.isNotEmpty() },
        state = query?.first("state")?.takeIf { it.isNotEmpty() },
        scopes = scopes,
        asksToConfirmAgain = query?.let(::raftSignInAsksToConfirmAgain) == true,
    )
}

/** Formats host (with optional `:port`) in ASCII via `raftAppLinkAsciiHost`. */
internal fun formatAsciiReturnHost(returnHost: String): String {
    if (returnHost.startsWith("[")) return returnHost
    val colon = returnHost.lastIndexOf(':')
    return if (colon > 0) {
        "${raftAppLinkAsciiHost(returnHost.substring(0, colon))}:${returnHost.substring(colon + 1)}"
    } else {
        raftAppLinkAsciiHost(returnHost)
    }
}

private fun defaultMonotonicNowMs(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

@OptIn(ExperimentalUuidApi::class)
private fun defaultSignInRequestId(): String = Uuid.random().toString()
