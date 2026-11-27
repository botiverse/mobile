package build.raft.app.settings

import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.settings.localizedMessage
import build.raft.app.traceId
import build.raft.app.userFacingErrorCodeMessage
import build.raft.app.shell.withAuthenticatedRouteOwner
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftBackButton
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.app.components.raftCardSurface
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.AuthApiResult
import build.raft.app.data.AuthAuthorityChangedMessage
import build.raft.app.data.AuthLoginResponse
import build.raft.app.data.AuthLossResolution
import build.raft.app.data.AuthMobileOAuthLinkCompleteResponse
import build.raft.app.data.AuthMobileOAuthStartResponse
import build.raft.app.data.AuthRefreshResponse
import build.raft.app.data.AuthSocialCompleteResponse
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedEmailVerificationResult
import build.raft.app.data.RaftAuthSessionTerminalSource
import build.raft.app.data.ServerSwitcherOrderPreference
import build.raft.app.data.RaftAuthApi
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.has
import build.raft.app.iconName
import build.raft.app.localized
import build.raft.app.localizedErrorPresentationStrings
import build.raft.app.localizedMessage
import build.raft.app.loginPageData
import build.raft.app.optBoolean
import build.raft.app.raftPlatformSupportsMultiShellHost
import build.raft.app.shell.raftShellStoreScopeOrNull
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.raftSharedPreferenceStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.AUTH_ABOUT_PAGE
import build.raft.app.AuthActionButton
import build.raft.app.AuthButtonTone
import build.raft.app.AuthCenterMark
import build.raft.app.AuthError
import build.raft.app.AuthFormColumn
import build.raft.app.AuthLogoBadge
import build.raft.app.AuthScaffold
import build.raft.app.AuthSuccessNotice
import build.raft.app.AuthTextField
import build.raft.app.LOGIN_PAGE
import build.raft.app.MembersPageHeader
import build.raft.app.shell.RAFT_DOCUMENT_SHELL
import build.raft.app.shell.RAFT_SERVER_SWITCH_KEEP_WINDOW
import build.raft.app.shell.RAFT_SERVER_SWITCH_WINDOW_ID
import build.raft.app.shell.RAFT_SHELL_INSTANCE_ID
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftCachedImage
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftTopPageHeader
import build.raft.app.RaftTraceIdNote
import build.raft.app.RaftType
import kotlinx.coroutines.flow.map
import build.raft.app.data.map
import kotlinx.coroutines.launch
import build.raft.app.components.RaftSlotIcon

private const val AUTH_REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
internal const val AUTH_REPLACE_SHELL_ROOT = "slockReplaceShellRoot"

internal class AuthChooseServerVisualApi(
    private val user: StoredAuthUser,
    private val servers: List<StoredAuthServer>
) : RaftAuthApi {
    override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthApiResult<AuthSocialCompleteResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun fetchSocialAuthProviders(platform: String): AuthApiResult<Set<String>> =
        AuthApiResult.Failure(code = 400)

    override suspend fun startMobileOAuthLink(
        provider: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthStartResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun completeMobileOAuthLink(
        provider: String,
        code: String,
        codeVerifier: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthLinkCompleteResponse> =
        AuthApiResult.Failure(code = 400)

    override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> =
        AuthApiResult.Failure(code = 400)

    override suspend fun verifyEmail(token: String): AuthApiResult<Unit> =
        AuthApiResult.Failure(code = 400)

    override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
        AuthApiResult.Success(user)

    override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> =
        AuthApiResult.Failure(code = 401)

    override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
        AuthApiResult.Success(Unit)

    override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
        AuthApiResult.Success(servers)

    override suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference> =
        AuthApiResult.Success(ServerSwitcherOrderPreference(servers.map { it.id }))

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        AuthApiResult.Failure(code = 400)
}

internal fun Pager.openAppShellDeveloper() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_ABOUT_PAGE,
            JSONObject().put("pageName", AUTH_ABOUT_PAGE)
        )
}

internal fun Pager.openAuthChooseServerPage(
    storeScope: RaftShellStoreScope,
    documentShell: Boolean
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            AUTH_CHOOSE_SERVER_PAGE,
            authChooseServerPageData(
                replaceCurrent = false,
                fromShell = true,
                storeScope = storeScope,
                documentShell = documentShell
            )
        )
}

internal fun Pager.openAppShellReplacingCurrentPage(pageData: JSONObject) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(RAFT_APP_SHELL_PAGE, pageData)
}

internal fun Pager.openLoginReplacingCurrentPage(message: String? = null, traceId: String? = null) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(
            LOGIN_PAGE,
            loginPageData(
                replaceCurrent = true,
                message = message,
                traceId = traceId
            )
        )
}

internal fun authChooseServerPageData(
    replaceCurrent: Boolean = false,
    fromShell: Boolean = false,
    skipInitialLoad: Boolean = false,
    storeScope: RaftShellStoreScope? = null,
    documentShell: Boolean = false
): JSONObject =
    raftAuthRoutePageData(AUTH_CHOOSE_SERVER_PAGE, replaceCurrent)
        .apply {
            if (fromShell) {
                put(AUTH_CHOOSE_SERVER_FROM_SHELL, true)
            }
            if (skipInitialLoad) {
                put(AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD, true)
            }
            storeScope?.let { scope ->
                put(RAFT_SHELL_INSTANCE_ID, scope.shellInstanceId)
                scope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
                put(RAFT_DOCUMENT_SHELL, documentShell)
            }
        }

internal fun authChooseServerUsesScopedStore(pageData: JSONObject): Boolean =
    pageData.optBoolean(AUTH_CHOOSE_SERVER_FROM_SHELL, false) ||
        pageData.raftShellStoreScopeOrNull() != null

internal fun appShellPageDataForServerSelection(
    sourceStoreScope: RaftShellStoreScope,
    sourceDocumentShell: Boolean = false,
    selectedServerId: String?,
    multiShellHostEnabled: Boolean = false,
    suppressRestoreLanding: Boolean = false,
    routeOwner: AuthenticatedRouteOwner? = null,
    // The chooser page replaces itself with the shell; a shell that opens another server's window
    // stays where it is (replacing it would close the source window).
    replaceCurrent: Boolean = true,
    // Post-login workspace selection (`fromShell = false`) opens the default launcher shell rather
    // than spawning a document task and finishing the launcher task.
    fromShell: Boolean = true,
    windowShowingPickedServer: String? = null,
): JSONObject {
    val normalizedServerId = selectedServerId?.trim()?.takeIf { it.isNotBlank() }
    val opensDocumentShell =
        fromShell &&
            isMultiShellCrossServerSelection(
                sourceStoreScope = sourceStoreScope,
                selectedServerId = normalizedServerId,
                multiShellHostEnabled = multiShellHostEnabled
            )
    return appShellPageData(
        replaceCurrent = replaceCurrent,
        suppressRestoreLanding = suppressRestoreLanding
    ).apply {
        normalizedServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        if (opensDocumentShell && routeOwner != null) {
            put(RAFT_DOCUMENT_SHELL, true)
            withAuthenticatedRouteOwner(routeOwner)
        } else {
            put(RAFT_SHELL_INSTANCE_ID, sourceStoreScope.shellInstanceId)
            put(RAFT_DOCUMENT_SHELL, fromShell && sourceDocumentShell && multiShellHostEnabled)
        }
        val window =
            if (fromShell && multiShellHostEnabled) {
                windowShowingPickedServer?.trim()?.takeIf { it.isNotEmpty() && it != sourceStoreScope.shellInstanceId }
            } else {
                null
            }
        if (window != null) {
            put(RAFT_SERVER_SWITCH_WINDOW_ID, window)
            if (!has(AppShellInitialTabKey)) put(RAFT_SERVER_SWITCH_KEEP_WINDOW, true)
        } else if (fromShell && replaceCurrent && !opensDocumentShell) {
            // Same-shell server selection/creation from a page pushed over the shell (e.g.
            // AuthChooseServerPage): replace down to a single shell root rather than stacking a
            // second AppShell controller over the previous one (#proj-mobile:#407).
            put(AUTH_REPLACE_SHELL_ROOT, true)
        }
    }
}

/**
 * The "open workspaces in separate app windows" setting as it applies here: only where the
 * platform hosts several shell windows (Android, multi-scene iPad), read from the chat layout
 * preference store. Every server switch entry reads it through this one function.
 */
internal fun raftMultiShellHostEnabled(): Boolean =
    raftPlatformSupportsMultiShellHost() &&
        KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore()).readMultiShellHostEnabledSync()

/**
 * Phase 3 multi-session realtime: every server with an open window keeps its own realtime session
 * (see `RaftRealtimeSessionDemand`). Needs the multi-window setting; off by default, and while off the
 * process keeps the single current-server session.
 */
internal fun raftMultiServerRealtimeEnabled(): Boolean =
    raftMultiShellHostEnabled() &&
        KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore()).readMultiServerRealtimeEnabledSync()

/**
 * A server picked inside a shell (server menu, wide top bar strip, Home switcher, drawer header):
 * with the multi-window setting on and a server other than the one this window shows NOW
 * ([currentServerId] — not the scope's launch-time server, which is null for a launcher-started
 * window and stale after in-place switches), the page data that opens (or brings to front) that
 * server's own window — the launch the Choose workspace page uses, minus replacing the source page.
 * Null means switch in place; so does an unknown current server.
 *
 * [keepTab] is the top-level tab the switch keeps (D1a, as the in-place switch does): the window
 * opens on it (`slockInitialTab`, an intent, not product state) instead of the default Home. Null
 * or "home" opens Home. Pushed pages are not carried.
 *
 * [sourceWindowServerId] is the server a document window is bound to (its route owner): picking
 * that server there switches in place, since this window already is that server's window.
 *
 * [windowShowingPickedServer] is the shell instance id of another window that shows the picked
 * server now (`RaftRealtimeSessionDemand.shellShowing`; the launcher window counts for the server
 * it shows): the page data names it ([RAFT_SERVER_SWITCH_WINDOW_ID]) so the host uses that window
 * and the server keeps one window. A switch that keeps no tab names no destination, so that
 * window comes forward as it is ([RAFT_SERVER_SWITCH_KEEP_WINDOW]); with a kept tab it is opened
 * on that tab as before.
 */
internal fun appShellServerSwitchNewWindowPageDataOrNull(
    sourceStoreScope: RaftShellStoreScope,
    currentServerId: String?,
    sourceDocumentShell: Boolean,
    selectedServerId: String,
    multiShellHostEnabled: Boolean,
    routeOwner: AuthenticatedRouteOwner?,
    keepTab: String? = null,
    sourceWindowServerId: String? = null,
    windowShowingPickedServer: String? = null
): JSONObject? {
    if (routeOwner == null) return null
    val current = currentServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // This window is the picked server's own window (a document shell bound to it) that ended up
    // showing another server (a tap on another server's notification switched it in place):
    // switch back in place. Opening "its window" would only rebuild this same window on the
    // server it shows, so the switch never happened (Hands 3a465552).
    val picked = selectedServerId.trim()
    if (sourceDocumentShell && picked.isNotEmpty() && sourceWindowServerId?.trim() == picked) return null
    // Decide against the server on screen: the source scope as it is now.
    val currentScope = RaftShellStoreScope.create(sourceStoreScope.shellInstanceId, current)
    if (!isMultiShellCrossServerSelection(currentScope, selectedServerId, multiShellHostEnabled)) return null
    return appShellPageDataForServerSelection(
        sourceStoreScope = currentScope,
        sourceDocumentShell = sourceDocumentShell,
        selectedServerId = selectedServerId,
        multiShellHostEnabled = multiShellHostEnabled,
        suppressRestoreLanding = true,
        routeOwner = routeOwner,
        replaceCurrent = false
    ).apply {
        val tab = keepTab?.let(::retainedAppShellSelectedTab)
        if (tab != null && tab != "home") put(AppShellInitialTabKey, tab)
        val keepsTab = has(AppShellInitialTabKey)
        val window =
            windowShowingPickedServer?.trim()?.takeIf { it.isNotEmpty() && it != sourceStoreScope.shellInstanceId }
        if (window != null) {
            put(RAFT_SERVER_SWITCH_WINDOW_ID, window)
            if (!keepsTab) put(RAFT_SERVER_SWITCH_KEEP_WINDOW, true)
        }
    }
}

/**
 * Opens [serverId]'s own shell window for a cross-server switch ([pageData] from
 * [appShellServerSwitchNewWindowPageDataOrNull]). The user switched, so the socket reconnect that
 * follows refetches agent status at once ([noteServerSelected], the reconnect jitter's
 * `onServerSelected`) before the window opens. The shell's selection collector cannot do it here:
 * Android brings back the server's existing document window (`AndroidShellWindowRegistry
 * .targetForOwner`), whose selection store already holds [serverId] and never emits again, so the
 * reconnect waited the 0–20 s jitter while Home showed the statuses of the server's last visit
 * (Hands c76f162d: 9.3 s).
 */
internal fun openAppShellServerSwitchWindow(
    serverId: String,
    pageData: JSONObject,
    noteServerSelected: (String) -> Unit,
    open: (JSONObject) -> Unit
) {
    serverId.trim().takeIf { it.isNotEmpty() }?.let(noteServerSelected)
    open(pageData)
}

internal fun isMultiShellCrossServerSelection(
    sourceStoreScope: RaftShellStoreScope,
    selectedServerId: String?,
    multiShellHostEnabled: Boolean
): Boolean =
    multiShellHostEnabled &&
        !selectedServerId.isNullOrBlank() &&
        selectedServerId.trim() != sourceStoreScope.initialServerId

internal fun appShellPageData(
    replaceCurrent: Boolean = false,
    suppressRestoreLanding: Boolean = false
): JSONObject =
    raftAuthRoutePageData(RAFT_APP_SHELL_PAGE, replaceCurrent)
        .apply {
            if (suppressRestoreLanding) {
                put(APP_SHELL_SUPPRESS_RESTORE_LANDING, true)
            }
        }

private fun raftAuthRoutePageData(pageName: String, replaceCurrent: Boolean): JSONObject =
    JSONObject().apply {
        put("pageName", pageName)
        if (replaceCurrent) {
            put(AUTH_REPLACE_CURRENT_PAGE, true)
        }
    }

@Composable
internal fun AppShellServerGateContent(
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    skipInitialLoad: Boolean = false,
    selectServerInCurrentShell: Boolean = true,
    onReady: (StoredAuthSession) -> Unit,
    onSignOut: () -> Unit,
    onSessionCleared: (StoredAuthSession) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    var latestSession by remember(session) { mutableStateOf(session) }
    var mode by remember(session.servers) { mutableStateOf(if (session.servers.isEmpty()) "empty" else "choose") }
    var createName by remember { mutableStateOf(TextFieldValue("")) }
    var createSlug by remember { mutableStateOf(TextFieldValue("")) }
    var createSlugTouched by remember { mutableStateOf(false) }
    var pendingServerId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingServers by remember(session, skipInitialLoad) {
        mutableStateOf(!authChooseServerShouldSkipInitialLoad(skipInitialLoad, session.servers.size))
    }
    var loadAttempt by remember { mutableStateOf(0) }
    var errorPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    val errorMessage = errorPresentation.localizedMessage()
    val showPinnedSignOut = serverGateShouldPinSignOut(errorPresentation)

    LaunchedEffect(loadAttempt) {
        if (authChooseServerShouldSkipInitialLoad(skipInitialLoad, latestSession.servers.size)) {
            authDataSource.logDiagnostics(
                "auth_choose_server_load_result result=skipped reason=cached_servers_present cachedServers=${latestSession.servers.size}"
            )
            loadingServers = false
            return@LaunchedEffect
        }
        loadingServers = true
        when (val result = runCatchingPreservingCancellation { authDataSource.loadServers() }.getOrNull()) {
            is SharedAuthResult.Success -> {
                authDataSource.logDiagnostics(
                    "auth_choose_server_load_result result=success serversCount=${result.data.servers.size} selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                )
                latestSession = result.data
                errorPresentation = null
            }
            is SharedAuthResult.Failure -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=failure code=${result.code}")
                errorPresentation = result.authGateMessage()
            }
            SharedAuthResult.Unauthenticated -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=unauthenticated action=stay_on_server_picker")
                errorPresentation = serverGateUnauthenticatedMessage()
            }
            null -> {
                authDataSource.logDiagnostics("auth_choose_server_load_result result=failure code=exception")
                errorPresentation = AuthGateMessage.Static(Res.string.auth_error_load_servers.desc())
            }
        }
        loadingServers = false
    }
    LaunchedEffect(latestSession.servers.size) {
        if (mode != "create") {
            mode = if (latestSession.servers.isEmpty()) "empty" else "choose"
        }
    }

    AuthScaffold(
        contentTopPadding = 72.dp,
        contentScrollable = true,
        topBarHorizontalPadding = 8.dp,
        topBarBrandImagePadding = 0.dp,
        topLeadingContent = onBack?.let { back ->
            {
                RaftBackButton(onClick = back, label = stringResource(Res.string.common_back))
            }
        }
    ) {
        AuthCenterMark()
        Text(
            text =
                when (mode) {
                    "create" -> stringResource(Res.string.auth_server_title_create)
                    "empty" ->
                        if (loadingServers) {
                            stringResource(Res.string.auth_server_title_checking)
                        } else {
                            stringResource(Res.string.auth_server_title_first)
                        }
                    else -> stringResource(Res.string.auth_server_title_choose)
                },
            // Web AuthPageIntro h1: text-title (22/28 700).
            style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Text(
            text =
                StringDesc.ResourceFormatted(
                    Res.string.auth_server_signed_in_as,
                    listOf(latestSession.user.bestDisplayName)
                ).localized(),
            // Web AuthPageIntro description: text-subhead (15/20 400).
            style = RaftType.Subhead.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            AuthError(errorMessage, traceId = errorPresentation?.traceId)
            if (showPinnedSignOut) {
                AuthActionButton(
                    text = stringResource(Res.string.auth_action_sign_out),
                    onClick = {
                        pendingServerId = "__logout__"
                        loading = true
                    },
                    tone = AuthButtonTone.White,
                    enabled = !loading
                )
            }
            if (loadingServers) {
                Text(
                    text = stringResource(Res.string.auth_server_loading),
                    style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    textAlign = TextAlign.Center
                )
            } else if (latestSession.servers.isEmpty() && errorMessage != null) {
                AuthActionButton(
                    text = stringResource(Res.string.auth_action_retry),
                    onClick = {
                        errorPresentation = null
                        loadAttempt += 1
                    },
                    tone = AuthButtonTone.Pink,
                    enabled = !loading
                )
            } else if (mode == "create") {
                Text(
                    text = stringResource(Res.string.auth_server_create_hint),
                    style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
                AuthTextField(
                    value = createName,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = { nextValue ->
                        createName = nextValue
                        // Web parity (ServerSelector.tsx): the slug mirrors the
                        // name until the user edits the slug directly.
                        if (!createSlugTouched) {
                            val slug = authCreateServerSlugFromName(nextValue.text)
                            createSlug = TextFieldValue(slug, selection = TextRange(slug.length))
                        }
                    },
                    label = stringResource(Res.string.auth_label_server_name),
                    required = true,
                    enabled = !loading
                )
                AuthTextField(
                    value = createSlug,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = { nextValue ->
                        createSlugTouched = true
                        createSlug = authCreateServerSlugFieldValue(nextValue)
                    },
                    label = stringResource(Res.string.auth_label_server_slug),
                    placeholder = "my-team",
                    enabled = !loading
                )
                AuthActionButton(
                    text =
                        if (loading) {
                            stringResource(Res.string.auth_state_creating_server)
                        } else {
                            stringResource(Res.string.auth_action_create_server)
                        },
                    onClick = {
                        loading = true
                        errorPresentation = null
                        pendingServerId = "__create__"
                    },
                    tone = AuthButtonTone.Pink,
                    enabled = createName.text.isNotBlank() && !loading,
                    loading = loading
                )
                AuthActionButton(
                    text =
                        if (latestSession.servers.isEmpty()) {
                            stringResource(Res.string.auth_action_back)
                        } else {
                            stringResource(Res.string.auth_action_back_to_servers)
                        },
                    onClick = { mode = if (latestSession.servers.isEmpty()) "empty" else "choose" },
                    tone = AuthButtonTone.White,
                    enabled = !loading
                )
            } else if (mode == "empty") {
                AppShellServerEmptyState(
                    onCreateServer = { mode = "create" }
                )
            } else {
                latestSession.servers.forEach { server ->
                    RaftListRowGroup(modifier = Modifier.fillMaxWidth()) {
                        RaftListRow(
                            title = server.name,
                            variant = RaftListRowVariant.Plain,
                            description =
                                if (pendingServerId == server.id) {
                                    stringResource(Res.string.auth_state_opening_server)
                                } else {
                                    "/${server.slug.orEmpty().ifBlank { server.id }}"
                                },
                            enabled = !loading,
                            onClick = {
                                loading = true
                                errorPresentation = null
                                pendingServerId = server.id
                            }
                        )
                    }
                }
                AuthActionButton(
                    text = stringResource(Res.string.auth_action_create_new_server),
                    onClick = { mode = "create" },
                    tone = AuthButtonTone.Yellow,
                    enabled = !loading
                )
            }
            if (!showPinnedSignOut) {
                RaftDesignSystemTheme {
                    RaftTextLink(
                        text = stringResource(Res.string.auth_action_sign_out),
                        enabled = !loading,
                        onClick = {
                            pendingServerId = "__logout__"
                            loading = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = RaftTheme.typography.callout.copy(textAlign = TextAlign.Center),
                        maxLines = 1
                    )
                }
            }
        }
    }

    LaunchedEffect(pendingServerId) {
        val target = pendingServerId ?: return@LaunchedEffect
        if (target == "__logout__") {
            val clearedSession = latestSession
            when (val result = authDataSource.logout()) {
                is SharedAuthResult.Success -> {
                    onSessionCleared(clearedSession)
                    onSignOut()
                }
                is SharedAuthResult.Failure -> errorPresentation = result.authGateMessage()
                SharedAuthResult.Unauthenticated -> {
                    onSessionCleared(clearedSession)
                    onSignOut()
                }
            }
            pendingServerId = null
            loading = false
            return@LaunchedEffect
        }
        val result =
            if (target == "__create__") {
                authDataSource.createServer(
                    createName.text,
                    slug = createSlug.text,
                    selectCreatedServer = selectServerInCurrentShell,
                )
            } else if (!selectServerInCurrentShell) {
                latestSession.withAppShellSelectedServer(target)?.let { SharedAuthResult.Success(it) }
                    ?: authDataSource.selectServer(target)
            } else {
                authDataSource.selectServer(target)
            }
        pendingServerId = null
        loading = false
        when (result) {
            is SharedAuthResult.Success -> {
                authDataSource.logDiagnostics(
                    "auth_choose_server_action_result target=$target result=success selectedServer=${!result.data.selectedServerId.isNullOrBlank()}"
                )
                onReady(result.data)
            }
            is SharedAuthResult.Failure -> {
                authDataSource.logDiagnostics("auth_choose_server_action_result target=$target result=failure code=${result.code}")
                errorPresentation = serverGateActionFailureMessage(target, result)
            }
            SharedAuthResult.Unauthenticated -> {
                authDataSource.logDiagnostics("auth_choose_server_action_result target=$target result=unauthenticated action=stay_on_server_picker")
                errorPresentation = serverGateUnauthenticatedMessage()
            }
        }
    }

}

internal fun authChooseServerShouldSkipInitialLoad(
    skipInitialLoad: Boolean,
    cachedServersCount: Int
): Boolean =
    skipInitialLoad && cachedServersCount > 0

internal sealed interface AppShellOrderedAuthEffect {
    data class AuthLoss(val resolution: AuthLossResolution) : AppShellOrderedAuthEffect
    data class SessionChanged(val fingerprint: String) : AppShellOrderedAuthEffect
    data class SessionInvalidated(val generation: Long?) : AppShellOrderedAuthEffect
}

internal suspend fun appShellRunIfAuthSessionTerminalRevision(
    terminalSource: RaftAuthSessionTerminalSource,
    expectedGeneration: Long,
    action: () -> Unit,
): Boolean =
    terminalSource.runIfAuthSessionTerminalRevision(expectedGeneration, action)

internal suspend fun appShellRunIfAuthSessionTerminalOrAuthorityRevision(
    terminalSource: RaftAuthSessionTerminalSource,
    expectedGeneration: Long,
    expectedSession: StoredAuthSession?,
    action: () -> Unit,
): Long? =
    terminalSource.runIfAuthSessionTerminalOrAuthorityRevision(
        expectedGeneration = expectedGeneration,
        expectedSession = expectedSession,
        action = action,
    )

internal fun appShellAuthLossEffectShouldApply(
    capturedSession: StoredAuthSession?,
    currentSession: StoredAuthSession?,
): Boolean =
    currentSession == null ||
        (
            capturedSession != null &&
            currentSession.user.id == capturedSession.user.id &&
                currentSession.accessToken == capturedSession.accessToken &&
                currentSession.refreshToken == capturedSession.refreshToken
        )

internal fun serverGateActionFailureMessage(target: String, failure: SharedAuthResult.Failure): AuthGateMessage =
    if (target == "__create__" && failure.message == AuthAuthorityChangedMessage) {
        AuthGateMessage.Static(Res.string.auth_error_session_changed_retry_create_server.desc())
    } else {
        failure.authGateMessage()
    }

/** The client's localized copy when it has one ([SharedAuthResult.Failure.display]), else the server's words. */
internal fun SharedAuthResult.Failure.authGateMessage(): AuthGateMessage =
    display?.let { AuthGateMessage.Static(it, traceId = traceId) } ?: AuthGateMessage.Dynamic(message, traceId = traceId)

// Web parity: ServerSelector.tsx `toSlug` — lowercase, then every character
// outside [a-z0-9-] becomes "-" (no collapsing, no trimming). The create form
// mirrors the name into the slug through this until the slug is hand-edited,
// and normalizes direct slug input through it too.
private val AuthCreateServerSlugDisallowed = Regex("[^a-z0-9-]")

internal fun authCreateServerSlugFromName(value: String): String =
    value.lowercase().replace(AuthCreateServerSlugDisallowed, "-")

/**
 * Normalizes committed slug input without interrupting an active IME composition.
 *
 * Direct slug edits intentionally keep the web `toSlug` behavior, but rebuilding a value while
 * the native input service owns [TextFieldValue.composition] would commit marked text early. Once
 * composition finishes, selection offsets are mapped through the same prefix transformation.
 */
internal fun authCreateServerSlugFieldValue(value: TextFieldValue): TextFieldValue {
    if (value.composition != null) return value
    val normalizedText = authCreateServerSlugFromName(value.text)
    if (normalizedText == value.text) return value
    fun mappedOffset(offset: Int): Int =
        authCreateServerSlugFromName(value.text.substring(0, offset.coerceIn(0, value.text.length))).length

    return TextFieldValue(
        text = normalizedText,
        selection = TextRange(
            mappedOffset(value.selection.start),
            mappedOffset(value.selection.end),
        ),
    )
}

internal fun serverGateUnauthenticatedMessage(): AuthGateMessage =
    AuthGateMessage.Static(
        Res.string.auth_error_session_refresh_choose_server.desc(),
        pinSignOut = true
    )

internal fun serverGateShouldPinSignOut(errorPresentation: AuthGateMessage?): Boolean =
    (errorPresentation as? AuthGateMessage.Static)?.pinSignOut == true

@Composable
private fun AppShellServerEmptyState(
    onCreateServer: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(Res.string.auth_server_empty_hint),
            style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthActionButton(
            text = stringResource(Res.string.auth_action_create_new_server),
            onClick = onCreateServer,
            tone = AuthButtonTone.Pink
        )
    }
}


@Composable
internal fun AppShellAuthErrorContent(
    message: String,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: (() -> Unit)? = null,
    topBanner: (@Composable () -> Unit)? = null
) {
    AuthScaffold(
        onDiagnosticsShortcut = onOpenDiagnostics,
        topLeadingContent = onBack?.let { back ->
            {
                RaftBackButton(onClick = back, label = stringResource(Res.string.common_back))
            }
        },
        topBanner = topBanner
    ) {
        AuthLogoBadge("RAFT")
        Text(
            text = stringResource(Res.string.auth_title_session_error),
            style = RaftType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        AuthError(message)
        AuthActionButton(text = stringResource(Res.string.auth_action_retry), onClick = onRetry, tone = AuthButtonTone.Pink)
        AuthActionButton(text = stringResource(Res.string.auth_action_sign_in), onClick = onSignIn, tone = AuthButtonTone.White)
    }
}

@Composable
internal fun AppShellEmailVerificationGateContent(
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit
) {
    var loading by remember { mutableStateOf(false) }
    var errorPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    var sentPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    var pendingResendNonce by remember { mutableStateOf(0) }
    var pendingSignOut by remember { mutableStateOf(false) }
    val accountEmail = session.user.email.takeIf { it.isNotBlank() }
    val errorMessage = errorPresentation.localizedMessage()
    val sentMessage = sentPresentation.localizedMessage()

    LaunchedEffect(pendingResendNonce) {
        if (pendingResendNonce == 0) return@LaunchedEffect
        val result =
            runCatchingPreservingCancellation { authDataSource.resendVerification() }
                .getOrNull()
        loading = false
        when (result) {
            is SharedAuthResult.Success -> {
                errorPresentation = null
                sentPresentation = AuthGateMessage.Static(Res.string.auth_notice_verification_email_sent.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=success")
            }
            is SharedAuthResult.Failure -> {
                sentPresentation = null
                errorPresentation = result.authGateMessage()
                authDataSource.logDiagnostics("auth_resend_verification_result result=failure code=${result.code}")
            }
            SharedAuthResult.Unauthenticated -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Static(Res.string.auth_error_session_refresh.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=unauthenticated")
            }
            null -> {
                sentPresentation = null
                errorPresentation = AuthGateMessage.Static(Res.string.auth_error_verification_email_send.desc())
                authDataSource.logDiagnostics("auth_resend_verification_result result=failure code=exception")
            }
        }
    }

    // Web EmailVerificationPage (#12237): the link opened somewhere else (the mail app, a browser), so
    // coming back to the app re-reads the account and the gate moves on by itself instead of waiting
    // for Refresh. Only after the app was left: the gate itself was just shown from a fresh read.
    val recheckScope = rememberCoroutineScope()
    val currentOnRefresh by rememberUpdatedState(onRefresh)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, authDataSource) {
        var left = false
        var checking = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> left = true
                    Lifecycle.Event.ON_RESUME ->
                        if (left && !checking) {
                            left = false
                            checking = true
                            recheckScope.launch {
                                val result =
                                    runCatchingPreservingCancellation { authDataSource.refreshEmailVerificationStatus() }
                                        .getOrNull()
                                checking = false
                                if (result is SharedEmailVerificationResult.Verified) {
                                    authDataSource.logDiagnostics("auth_email_verification_recheck result=verified")
                                    currentOnRefresh()
                                }
                            }
                        }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pendingSignOut) {
        if (!pendingSignOut) return@LaunchedEffect
        authDataSource.logout()
        pendingSignOut = false
        loading = false
        onSignOut()
    }

    AuthScaffold {
        AuthLogoBadge("RAFT")
        Text(
            text = stringResource(Res.string.auth_title_verify_email),
            // Web AuthPageIntro h1: text-title (22/28 700).
            style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Text(
            text =
                if (accountEmail != null) {
                    StringDesc.ResourceFormatted(
                        Res.string.auth_hint_verify_email_address,
                        listOf(accountEmail)
                    ).localized()
                } else {
                    stringResource(Res.string.auth_hint_verify_email)
                },
            // Web EmailVerificationPage: text-callout, muted (14/20 400).
            style = RaftType.Callout.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        // Web `pages.emailVerification.checkSpam`: footnote, muted, under the address.
        Text(
            text = stringResource(Res.string.auth_hint_verify_email_check_spam),
            style = RaftType.Footnote.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = TextAlign.Center
        )
        AuthFormColumn {
            sentMessage?.let {
                AuthSuccessNotice(
                    title = stringResource(Res.string.auth_title_email_sent),
                    detail = it
                )
            }
            AuthError(errorMessage, traceId = errorPresentation?.traceId)
            AuthActionButton(
                text = stringResource(Res.string.auth_action_resend_verification),
                onClick = {
                    loading = true
                    errorPresentation = null
                    sentPresentation = null
                    pendingResendNonce += 1
                },
                tone = AuthButtonTone.Pink,
                enabled = !loading,
                loading = loading
            )
            AuthActionButton(
                text = stringResource(Res.string.auth_action_verified_refresh),
                onClick = onRefresh,
                tone = AuthButtonTone.White,
                enabled = !loading
            )
            RaftDesignSystemTheme {
                RaftTextLink(
                    text = stringResource(Res.string.auth_action_sign_out),
                    enabled = !loading,
                    onClick = {
                        loading = true
                        errorPresentation = null
                        sentPresentation = null
                        pendingSignOut = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = RaftTheme.typography.callout.copy(textAlign = TextAlign.Center),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * First in-app frame while the stored session restores. Matches the static iOS launch screen
 * (iosApp Info.plist UILaunchScreen: RaftLaunchLogo on RaftLaunchBackground) and Android's
 * splash hold: the app-icon mark, 108dp, centred on the app canvas (cream in light, layer
 * canvas in dark). No tagline, tilt or border, so the launch -> app hand-off does not jump.
 */
@Composable
internal fun AppShellRestoreSplashContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (RaftAppThemeState.isDark) RaftColor.Canvas else RaftColor.SurfaceCream),
        contentAlignment = Alignment.Center
    ) {
        RaftCachedImage(
            source = "assets://raft/raft-launch-logo.png",
            contentDescription = null,
            modifier = Modifier.size(108.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
internal fun PageErrorContent(
    title: String,
    subtitle: String?,
    iconName: RaftLucideIconName?,
    headerContentStartPadding: Dp = 20.dp,
    headerTitleStyle: TextStyle = RaftType.Headline,
    message: String,
    code: String,
    traceId: String? = null,
    retryLabel: String? = stringResource(Res.string.common_retry),
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RaftTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = iconName,
            contentStartPadding = headerContentStartPadding,
            titleStyle = headerTitleStyle
        )
        PageErrorBody(
            message = message,
            code = code,
            traceId = traceId,
            retryLabel = retryLabel,
            onRetry = onRetry
        )
    }
}

@Composable
internal fun MembersPageErrorContent(
    message: String,
    code: String,
    onRetry: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
    traceId: String? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MembersPageHeader(title = stringResource(Res.string.tab_members), onBack = onBack)
        PageErrorBody(
            message = message,
            code = code,
            traceId = traceId,
            retryLabel = stringResource(Res.string.common_retry),
            onRetry = onRetry
        )
    }
}

@Composable
private fun PageErrorBody(
    message: String,
    code: String,
    traceId: String? = null,
    retryLabel: String?,
    onRetry: (() -> Unit)?
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .raftSafeAreaHorizontalPadding()
                .padding(RaftAppSpacing.Md)
                .raftCardSurface()
                .padding(RaftAppSpacing.Md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)) {
            Text(text = message, style = RaftType.ButtonBold, color = RaftColor.TextBlack)
            val codeMessage = code.userFacingErrorCodeMessage(localizedErrorPresentationStrings())
            // A page whose message is already the generic sentence its code resolves to says it once.
            if (codeMessage != message) {
                Text(
                    text = codeMessage,
                    style = RaftType.Caption,
                    color = RaftColor.DangerStrong
                )
            }
            RaftTraceIdNote(traceId = traceId)
            if (retryLabel != null && onRetry != null) {
                RaftButton(
                    text = retryLabel,
                    onClick = onRetry,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) }
                )
            }
        }
    }
}
