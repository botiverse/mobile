package build.raft.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftStaleReadRecoverySignals
import build.raft.app.data.SecondFactorStepUpCoordinator
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpRepository
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.settings.rememberRaftPagerAppeared
import build.raft.app.settings.rememberSettingsWebViewAuthSnapshot
import build.raft.app.settings.settingsWebViewAuthEventSource
import build.raft.app.thread.RaftConnectedAppLogo
import build.raft.app.thread.icon
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftSpinnerVariant
import build.raft.designsystem.components.RaftToast
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.RaftToastSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource

/*
 * Native "Log in with Raft" sheet and in-app browser host wiring (#proj-workspace:#356 §5).
 * Kept in small composables outside InAppBrowserPage.kt to stay well below JVM method-size caps.
 */

internal data class RaftInAppBrowserSignInHost(
    val session: RaftBrowserSignInSession,
    val stepUp: SecondFactorStepUpCoordinator,
    val flagEnabled: Boolean,
    val appForeground: Boolean,
    val signedInAsName: String? = null,
)

@Composable
internal fun rememberInAppBrowserSignInHost(
    pager: build.raft.app.compat.pager.Pager,
    bridge: BridgeModule,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope?,
    entryContext: RaftAppEntryContext?,
    activeWebBaseUrl: String,
    activeApiBaseUrl: String,
    controller: RaftWebViewController,
    onOpenInBrowser: (String) -> Unit,
    sessionKey: Any = Unit,
    panelVisible: Boolean = true,
): RaftInAppBrowserSignInHost {
    val authStore =
        remember(sharedStores, storeScope) {
            if (storeScope != null) sharedStores.authStore(storeScope) else sharedStores.authStore
        }
    val authEvents =
        remember(authStore, sharedStores.authStore) {
            settingsWebViewAuthEventSource(authStore, sharedStores.authStore)
        }
    val initialSession =
        remember(sharedStores.authStore, storeScope) {
            val committed = runCatching { sharedStores.authStore.readCommittedSessionSnapshot() }.getOrNull()
            val expectedServerId = storeScope?.initialServerId?.trim()?.takeIf { it.isNotEmpty() }
            if (committed != null && (expectedServerId == null || committed.currentServerId == expectedServerId)) {
                committed
            } else {
                null
            }
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, authEvents, initialSession = initialSession)
    val authSession = authSnapshot.session
    val flagServerId =
        remember(entryContext?.preferredServerId, authSession?.currentServerId) {
            (entryContext?.preferredServerId ?: authSession?.currentServerId)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::ServerId)
        }
    // mobile_raft_sign_in_v0 graduated to 100% live in 1.29 (prod config v707); enabled whenever a server is bound.
    val flagEnabled = flagServerId != null
    val pageVisible = rememberRaftPagerAppeared(pager) && panelVisible
    val appForeground by RaftStaleReadRecoverySignals.foreground.collectAsState()
    val scope = rememberCoroutineScope()

    val networkClient =
        remember(sharedStores, storeScope) {
            sharedStores.networkClient(storeScope ?: RaftShellStoreScope.Legacy)
        }
    val signInApi = remember(networkClient) { NetworkRaftBrowserSignInApi(networkClient) }
    val stepUp =
        remember(networkClient) {
            val repository = SecondFactorStepUpRepository(networkClient)
            SecondFactorStepUpCoordinator(
                stepUp = repository::stepUp,
                passkeyOptions = repository::passkeyAssertionOptions,
                webStepUp = repository.webStepUp(),
            )
        }

    val currentWebUrl by rememberUpdatedState(activeWebBaseUrl)
    val currentApiUrl by rememberUpdatedState(activeApiBaseUrl)
    val currentFlagEnabled by rememberUpdatedState(flagEnabled)
    val currentSession by rememberUpdatedState(authSession)
    val currentPageVisible by rememberUpdatedState(pageVisible)
    val currentAppForeground by rememberUpdatedState(appForeground)
    val currentOpenInBrowser by rememberUpdatedState(onOpenInBrowser)
    val environmentKey = remember(activeWebBaseUrl, activeApiBaseUrl) { "$activeWebBaseUrl|$activeApiBaseUrl" }
    val currentEnvKey by rememberUpdatedState(environmentKey)

    val signInSession =
        remember(pager.pagerId, sessionKey, signInApi, stepUp, controller, entryContext?.preferredServerId) {
            RaftBrowserSignInSession(
                scope = scope,
                authorizeOriginProvider = {
                    raftAuthorizeOrigin(currentApiUrl, allowLoopback = RaftCmpRouter.isDebugBuild)
                },
                webUrlProvider = { currentWebUrl },
                setupOriginProvider = {
                    raftAuthorizeOrigin(currentWebUrl, allowLoopback = RaftCmpRouter.isDebugBuild)
                },
                raftHostsProvider = { raftBrowserRaftHosts(currentWebUrl, currentApiUrl) },
                featureEnabledProvider = { currentFlagEnabled },
                accountProvider = { currentSession?.toSignInAccountContext(currentEnvKey) },
                preferredServerId = entryContext?.preferredServerId,
                isPageVisible = { currentPageVisible },
                isAppForeground = { currentAppForeground },
                api = signInApi,
                stepUp = stepUp,
                loadUrl = controller::loadUrl,
                onSignedInWithoutAsking = { appName ->
                    val message = Res.string.in_app_browser_sign_in_signed_in_toast.format(appName).resolve()
                    bridge.toastWithIntent(message, RaftToastIntent.Success)
                },
                onOpenInBrowser = { url -> currentOpenInBrowser(url) },
                log = bridge::log,
            )
        }

    LaunchedEffect(authSnapshot.hydrated, authSession?.user?.id, environmentKey) {
        if (!authSnapshot.hydrated) return@LaunchedEffect
        signInSession.onAccountOrEnvironmentChanged(authSession?.user?.id, environmentKey)
    }
    DisposableEffect(signInSession) {
        onDispose { signInSession.onPageClosed() }
    }
    val signedInAsName =
        authSession?.user?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: authSession?.user?.email?.trim()?.takeIf { it.isNotEmpty() }
    return RaftInAppBrowserSignInHost(
        session = signInSession,
        stepUp = stepUp,
        flagEnabled = flagEnabled,
        appForeground = appForeground,
        signedInAsName = signedInAsName,
    )
}

private fun StoredAuthSession.toSignInAccountContext(environmentKey: String): RaftBrowserSignInAccountContext =
    RaftBrowserSignInAccountContext(
        accountId = user.id,
        signedInAsLabel = user.displayName.orEmpty().trim().ifBlank { user.email.orEmpty().trim() },
        currentServerId = currentServerId,
        servers =
            servers.map { server ->
                RaftBrowserSignInServerOption(
                    id = server.id,
                    name = server.name,
                    slug = server.slug.orEmpty(),
                )
            },
        environmentKey = environmentKey,
    )

internal const val RAFT_BROWSER_SIGN_IN_RESOLVING_OVERLAY_TAG = "raft_browser_sign_in_resolving_overlay"
internal const val RAFT_BROWSER_SIGN_IN_APPROVE_BUTTON_TAG = "raft_browser_sign_in_approve_button"

internal fun raftBrowserSignInDeclinedStringRes(reason: RaftBrowserSignInDropReason?): StringResource =
    when (reason) {
        RaftBrowserSignInDropReason.RaisedRecently -> Res.string.in_app_browser_sign_in_declined_recently
        else -> Res.string.in_app_browser_sign_in_declined
    }

@Composable
internal fun raftBrowserSignInDeclinedMessage(reason: RaftBrowserSignInDropReason?): String =
    stringResource(raftBrowserSignInDeclinedStringRes(reason))

/**
 * Lightweight resolving indicator shown over the web viewport while an intercepted Raft Sign-In
 * request checks the server or prepares consent, absorbing accidental duplicate page taps without
 * blocking the panel's bottom bar (Close / Back / Reload).
 */
@Composable
internal fun RaftBrowserSignInResolvingOverlay(
    modifier: Modifier = Modifier,
) {
    RaftDesignSystemTheme {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(RAFT_BROWSER_SIGN_IN_RESOLVING_OVERLAY_TAG)
                    // raw-click: transparent tap sink preventing duplicate page taps while Raft sign-in resolves
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            RaftToast(
                title = stringResource(Res.string.in_app_browser_sign_in_resolving),
                surface = RaftToastSurface.Hud,
                icon = {
                    RaftSpinner(
                        size = RaftSpinnerSize.Sm,
                        variant = RaftSpinnerVariant.Inverse,
                        contentDescription = "",
                    )
                },
            )
        }
    }
}

/**
 * Hosts the native "Log in with Raft" sheet and its optional `SecondFactorStepUpDialog` over it.
 */
@Composable
internal fun RaftBrowserSignInSheetHost(
    sheet: RaftBrowserSignInSheetState?,
    stepUpState: SecondFactorStepUpDialogState?,
    onStepUpIntent: (SecondFactorStepUpIntent) -> Unit,
    appForeground: Boolean,
    onSelectServer: (String) -> Unit,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    if (sheet == null && stepUpState == null) return
    RaftDesignSystemTheme {
        if (sheet != null) {
            val approveLocked =
                rememberRaftSignInApproveLock(
                    requestId = sheet.requestId,
                    ready = sheet.canApprove,
                    appForeground = appForeground,
                )
            val namedAppName = sheet.appName?.trim()?.takeIf { it.isNotEmpty() }
            val displayAppName = namedAppName ?: stringResource(Res.string.in_app_browser_sign_in_this_app)
            val isReadyMode =
                sheet.mode is RaftBrowserSignInSheetMode.ServerReady ||
                    sheet.mode is RaftBrowserSignInSheetMode.AccountReady
            RaftSheet(
                onDismissRequest = {
                    if (stepUpState == null && !sheet.submitting) {
                        onCancel()
                    }
                },
                title = displayAppName,
                showClose = true,
                actions = {
                    RaftBrowserSignInSheetActions(
                        namedAppName = namedAppName,
                        isReadyMode = isReadyMode,
                        canApprove = sheet.canApprove,
                        approveLocked = approveLocked,
                        submitting = sheet.submitting,
                        onApprove = onApprove,
                        onOpenInBrowser = onOpenInBrowser,
                        onCancel = onCancel,
                    )
                },
            ) {
                RaftBrowserSignInSheetBody(
                    sheet = sheet,
                    displayAppName = displayAppName,
                    onSelectServer = onSelectServer,
                )
            }
        }
        if (stepUpState != null) {
            SecondFactorStepUpDialog(
                state = stepUpState,
                onIntent = onStepUpIntent,
                description = stringResource(Res.string.in_app_browser_sign_in_step_up_description),
            )
        }
    }
}

/**
 * Tapjacking guard matching slock web `useApproveLock.ts` (`APPROVE_AFTER_MS = 1000`):
 * Continue is disabled for 1 s after it can first be pressed AND again for 1 s whenever the app
 * returns to the foreground.
 */
@Composable
internal fun rememberRaftSignInApproveLock(
    requestId: String,
    ready: Boolean,
    appForeground: Boolean,
    lockDurationMs: Long = RAFT_SIGN_IN_APPROVE_AFTER_MS,
): Boolean {
    var unlocked by remember(requestId) { mutableStateOf(false) }
    var resumeRound by remember(requestId) { mutableIntStateOf(0) }
    var wasForeground by remember(requestId) { mutableStateOf(appForeground) }

    LaunchedEffect(appForeground) {
        if (appForeground && !wasForeground) {
            unlocked = false
            resumeRound += 1
        }
        wasForeground = appForeground
    }

    LaunchedEffect(requestId, ready, resumeRound, appForeground, lockDurationMs) {
        if (!ready || !appForeground) {
            unlocked = false
            return@LaunchedEffect
        }
        unlocked = false
        delay(lockDurationMs)
        unlocked = true
    }

    return !(ready && appForeground && unlocked)
}

@Composable
private fun RaftBrowserSignInSheetActions(
    namedAppName: String?,
    isReadyMode: Boolean,
    canApprove: Boolean,
    approveLocked: Boolean,
    submitting: Boolean,
    onApprove: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    if (isReadyMode) {
        val approveLabel =
            if (namedAppName != null) {
                stringResource(Res.string.in_app_browser_sign_in_approve_app, namedAppName)
            } else {
                stringResource(Res.string.in_app_browser_sign_in_approve)
            }
        RaftButton(
            text = approveLabel,
            onClick = onApprove,
            modifier = Modifier.fillMaxWidth().testTag(RAFT_BROWSER_SIGN_IN_APPROVE_BUTTON_TAG),
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
            enabled = canApprove && !approveLocked,
            loading = submitting,
            loadingLabel = stringResource(Res.string.in_app_browser_sign_in_continuing),
        )
    } else {
        RaftButton(
            text = stringResource(Res.string.in_app_browser_sign_in_open_in_browser),
            onClick = onOpenInBrowser,
            trailingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) },
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
        )
    }
    RaftButton(
        text = stringResource(Res.string.common_cancel),
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
        variant = RaftButtonVariant.Ghost,
        size = RaftButtonSize.Lg,
        enabled = !submitting,
    )
}

@Composable
private fun RaftBrowserSignInSheetBody(
    sheet: RaftBrowserSignInSheetState,
    displayAppName: String,
    onSelectServer: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftBanner(
            description =
                boldSubstringAnnotated(
                    full = stringResource(Res.string.in_app_browser_sign_in_notice, sheet.returnHost),
                    highlight = sheet.returnHost,
                ),
            status = RaftBannerStatus.Info,
            size = RaftBannerSize.Sm,
        )
        RaftBrowserSignInAppHeader(
            appName = displayAppName,
            logoUrl = sheet.logoUrl,
            description = sheet.description,
            signedInAsLabel = sheet.signedInAsLabel,
        )
        when (val mode = sheet.mode) {
            is RaftBrowserSignInSheetMode.ServerReady -> {
                RaftBrowserSignInServerSection(
                    mode = mode,
                    enabled = !sheet.submitting,
                    onSelectServer = onSelectServer,
                )
                RaftBrowserSignInScopeSection(sheet.scopes)
            }
            is RaftBrowserSignInSheetMode.AccountReady -> {
                if (mode.loginModeChanged) {
                    RaftBanner(
                        description =
                            stringResource(
                                Res.string.in_app_browser_sign_in_account_login_mode_changed,
                                displayAppName,
                            ),
                        status = RaftBannerStatus.Info,
                        size = RaftBannerSize.Sm,
                    )
                }
                RaftText(
                    text =
                        boldSubstringAnnotated(
                            full = stringResource(Res.string.in_app_browser_sign_in_account_body, displayAppName),
                            highlight = displayAppName,
                        ),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
                RaftBrowserSignInScopeSection(sheet.scopes)
            }
            else -> {
                RaftBrowserSignInNonReadySection(
                    mode = mode,
                    displayAppName = displayAppName,
                    traceId = sheet.inlineErrorTraceId,
                )
            }
        }
        if (
            (sheet.mode is RaftBrowserSignInSheetMode.ServerReady || sheet.mode is RaftBrowserSignInSheetMode.AccountReady) &&
            (sheet.inlineError != null || sheet.inlineErrorTraceId != null)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftBanner(
                    description = sheet.inlineError ?: stringResource(Res.string.in_app_browser_sign_in_continue_failed),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
                RaftTraceIdNote(sheet.inlineErrorTraceId)
            }
        }
    }
}

@Composable
private fun RaftBrowserSignInAppHeader(
    appName: String,
    logoUrl: String?,
    description: String?,
    signedInAsLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftConnectedAppLogo(name = appName, logoUrl = logoUrl)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RaftText(
                text = appName,
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foreground,
            )
            if (!description.isNullOrBlank()) {
                RaftText(
                    text = description,
                    style = RaftTheme.typography.footnote,
                    color = RaftTheme.colors.foregroundMuted,
                )
            }
            if (signedInAsLabel.isNotBlank()) {
                val prefix = stringResource(Res.string.in_app_browser_sign_in_signed_in_as)
                val joined =
                    if (prefix.endsWith(":") || prefix.endsWith("：")) {
                        "$prefix$signedInAsLabel"
                    } else {
                        "$prefix $signedInAsLabel"
                    }
                RaftText(
                    text = joined,
                    style = RaftTheme.typography.caption,
                    color = RaftTheme.colors.foregroundHint,
                )
            }
        }
    }
}

@Composable
private fun RaftBrowserSignInServerSection(
    mode: RaftBrowserSignInSheetMode.ServerReady,
    enabled: Boolean,
    onSelectServer: (String) -> Unit,
) {
    val items =
        remember(mode.readyServers) {
            mode.readyServers.map { server ->
                RaftChoiceListItem(
                    value = server.id,
                    label = "${server.name} (${server.slug})",
                    searchText = "${server.name} ${server.slug}",
                )
            }
        }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RaftText(
            text = stringResource(Res.string.in_app_browser_sign_in_server_label),
            style = RaftTheme.typography.caption,
            color = RaftTheme.colors.foregroundMuted,
        )
        RaftChoiceList(
            items = items,
            value = mode.selectedServerId,
            onValueChange = onSelectServer,
            searchPlaceholder = stringResource(Res.string.in_app_browser_sign_in_search_servers),
            clearSearchLabel = stringResource(Res.string.list_toolbar_clear_search),
            noMatchLabel = stringResource(Res.string.in_app_browser_sign_in_no_server_search_results),
            emptyLabel = stringResource(Res.string.in_app_browser_sign_in_no_available_servers),
            enabled = enabled,
        )
        if (mode.selectedServerId == null) {
            RaftText(
                text = stringResource(Res.string.in_app_browser_sign_in_choose_server),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foreground,
            )
        }
    }
}

@Composable
private fun RaftBrowserSignInScopeSection(scopes: List<String>) {
    if (scopes.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RaftText(
            text = stringResource(Res.string.in_app_browser_sign_in_requested_access),
            style = RaftTheme.typography.caption,
            color = RaftTheme.colors.foreground,
        )
        for (scope in scopes) {
            val copy = raftBrowserSignInScopeCopy(scope) ?: continue
            RaftText(
                text = copy,
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foreground,
            )
        }
    }
}

@Composable
private fun RaftBrowserSignInNonReadySection(
    mode: RaftBrowserSignInSheetMode,
    displayAppName: String,
    traceId: String?,
) {
    val message =
        when (mode) {
            RaftBrowserSignInSheetMode.NotAvailable ->
                stringResource(Res.string.in_app_browser_sign_in_app_not_available)
            is RaftBrowserSignInSheetMode.InstallRequired ->
                when {
                    mode.accountMode ->
                        stringResource(Res.string.in_app_browser_sign_in_account_install_help, displayAppName)
                    mode.canInstall ->
                        stringResource(Res.string.in_app_browser_sign_in_install_required_can_install)
                    else ->
                        stringResource(Res.string.in_app_browser_sign_in_install_required_ask_admin)
                }
            is RaftBrowserSignInSheetMode.Suspended ->
                if (mode.accountMode) {
                    stringResource(Res.string.in_app_browser_sign_in_account_suspended)
                } else {
                    stringResource(Res.string.in_app_browser_sign_in_installation_suspended)
                }
            is RaftBrowserSignInSheetMode.DisallowedScopes -> {
                val scopeList = mode.disallowedScopes.joinToString(", ")
                when (mode.reason) {
                    "not_allowed" ->
                        stringResource(Res.string.in_app_browser_sign_in_scope_not_allowed, displayAppName, scopeList)
                    "unsupported" ->
                        stringResource(Res.string.in_app_browser_sign_in_scope_unsupported, displayAppName, scopeList)
                    else ->
                        stringResource(Res.string.in_app_browser_sign_in_scope_invalid, displayAppName, scopeList)
                }
            }
            RaftBrowserSignInSheetMode.AgentInboundScopes ->
                stringResource(Res.string.in_app_browser_sign_in_agent_inbound_notice)
            RaftBrowserSignInSheetMode.RootIssuerRequired ->
                stringResource(Res.string.in_app_browser_sign_in_account_root_issuer_required)
            RaftBrowserSignInSheetMode.LookupFailed ->
                stringResource(Res.string.in_app_browser_sign_in_server_status_unavailable)
            is RaftBrowserSignInSheetMode.ServerReady,
            is RaftBrowserSignInSheetMode.AccountReady -> ""
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = message,
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm,
        )
        RaftTraceIdNote(traceId)
    }
}

@Composable
private fun raftBrowserSignInScopeCopy(scope: String): String? =
    when (scope) {
        "openid" -> stringResource(Res.string.in_app_browser_scope_openid)
        "profile" -> stringResource(Res.string.in_app_browser_scope_profile)
        "email" -> stringResource(Res.string.in_app_browser_scope_email)
        "identity" -> stringResource(Res.string.in_app_browser_scope_identity)
        "agent:read" -> stringResource(Res.string.in_app_browser_scope_agent_read)
        "app:read" -> stringResource(Res.string.in_app_browser_scope_app_read)
        "agent:event:write" -> stringResource(Res.string.in_app_browser_scope_agent_event_write)
        "agent:notification:write" -> stringResource(Res.string.in_app_browser_scope_agent_notification_write)
        "agent:voice" -> stringResource(Res.string.in_app_browser_scope_agent_voice)
        else -> null
    }

private fun boldSubstringAnnotated(
    full: String,
    highlight: String,
): AnnotatedString {
    val index = if (highlight.isNotEmpty()) full.indexOf(highlight) else -1
    if (index < 0) return AnnotatedString(full)
    return buildAnnotatedString {
        append(full)
        addStyle(
            style = SpanStyle(fontWeight = FontWeight.Bold),
            start = index,
            end = index + highlight.length,
        )
    }
}
