package build.raft.app

import build.raft.app.compat.json.JSONObject

import build.raft.app.resources.*
import build.raft.app.settings.AUTH_CHOOSE_SERVER_FROM_SHELL
import build.raft.app.settings.AUTH_CHOOSE_SERVER_PAGE
import build.raft.app.settings.AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD
import build.raft.app.settings.AppShellAuthErrorContent
import build.raft.app.settings.AppShellRestoreSplashContent
import build.raft.app.settings.AppShellServerGateContent
import build.raft.app.settings.AuthGateMessage
import build.raft.app.settings.SignedOutAppUpdateBanner
import build.raft.app.settings.appShellPageDataForServerSelection
import build.raft.app.settings.authChooseServerUsesScopedStore
import build.raft.app.settings.isMultiShellCrossServerSelection
import build.raft.app.settings.localizedMessage
import build.raft.app.settings.openAppShellDeveloper
import build.raft.app.settings.openAppShellReplacingCurrentPage
import build.raft.app.settings.openAppShellServerSwitchWindow
import build.raft.app.settings.openLoginReplacingCurrentPage
import build.raft.app.settings.raftMultiShellHostEnabled
import build.raft.app.shell.RAFT_DOCUMENT_SHELL
import build.raft.app.shell.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
import build.raft.app.shell.raftShellStoreScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.base.Utils
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.BridgeRaftAvatarNativeCache
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.raftNowEpochMillis
import build.raft.app.data.raftProcessRealtimeSessionDemand
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.localizedMessage
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager

@Page(AUTH_CHOOSE_SERVER_PAGE, supportInLocal = true)
internal class ComposeAuthChooseServerPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthChooseServerPageContent() }
    }
}

@Composable
private fun AuthChooseServerPageContent() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember { Utils.bridgeModule(pager.pagerId) }
    val fromShell = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(AUTH_CHOOSE_SERVER_FROM_SHELL, false)
    }
    val usesScopedStore = remember(pager.pageData.params.toString()) {
        authChooseServerUsesScopedStore(pager.pageData.params)
    }
    val sourceStoreScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sourceDocumentShell = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(RAFT_DOCUMENT_SHELL, false)
    }
    val skipInitialLoad = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD, false)
    }
    val closeChooser = remember(pager) {
        { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    }
    val sharedStores = remember { RaftSharedStoreRegistry.get(bridge) }
    rememberAuthRuntimeCapabilities(
        store = sharedStores.authRuntimeCapabilityStore,
        pageData = pager.pageData.params
    )
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeRaftAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext()
            )
        }
    val authSessionStore = remember(sharedStores, usesScopedStore, sourceStoreScope) {
        if (usesScopedStore) sharedStores.authStore(sourceStoreScope) else sharedStores.authStore
    }
    val authDataSource = remember(sharedStores, usesScopedStore, sourceStoreScope, avatarNativeCache) {
        if (usesScopedStore) {
            sharedStores.createAuthDataSourceForCurrentEnvironment(sourceStoreScope, avatarNativeCache)
        } else {
            sharedStores.createAuthDataSourceForCurrentEnvironment(avatarNativeCache)
        }
    }
    var session by remember { mutableStateOf<StoredAuthSession?>(null) }
    var errorPresentation by remember { mutableStateOf<AuthGateMessage?>(null) }
    var restoreAttempt by remember { mutableStateOf(0) }
    val errorMessage = errorPresentation.localizedMessage()

    LaunchedEffect(fromShell, usesScopedStore, skipInitialLoad) {
        authDataSource.logDiagnostics(
            "auth_choose_server_page_enter fromShell=$fromShell scopedStore=$usesScopedStore " +
                "skipInitialLoad=$skipInitialLoad"
        )
    }

    LaunchedEffect(authSessionStore, restoreAttempt) {
        runCatchingPreservingCancellation { authSessionStore.readSession() }
            .onSuccess { restored ->
                session = restored
                authDataSource.logDiagnostics(
                    "auth_choose_server_page_session result=${if (restored == null) "missing" else "success"} " +
                        "serversCount=${restored?.servers?.size ?: 0} " +
                        "selectedServer=${!restored?.selectedServerId.isNullOrBlank()} " +
                        "restoreAttempt=$restoreAttempt"
                )
                errorPresentation =
                    if (restored == null) {
                        AuthGateMessage.Static(Res.string.auth_error_choose_server_sign_in_required.desc())
                    } else {
                        null
                    }
            }
            .onFailure {
                session = null
                authDataSource.logDiagnostics(
                    "auth_choose_server_page_session result=failure restoreAttempt=$restoreAttempt"
                )
                errorPresentation = AuthGateMessage.Static(Res.string.auth_error_restore_session.desc())
            }
    }

    when (val restoredSession = session) {
        null -> {
            if (errorMessage == null) {
                AppShellRestoreSplashContent()
            } else {
                AppShellAuthErrorContent(
                    message =
                        errorMessage
                            ?: stringResource(Res.string.auth_error_choose_server_sign_in_required),
                    onRetry = {
                        errorPresentation = null
                        session = null
                        restoreAttempt += 1
                    },
                    onSignIn = { pager.openLoginReplacingCurrentPage() },
                    onOpenDiagnostics = { pager.openAppShellDeveloper() },
                    onBack = closeChooser.takeIf { fromShell },
                    topBanner = { SignedOutAppUpdateBanner(pager, bridge) }
                )
            }
        }
        else -> {
            val currentSourceServerId =
                restoredSession.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: sourceStoreScope.initialServerId?.trim()?.takeIf { it.isNotEmpty() }
            val currentSourceScope =
                if (fromShell && currentSourceServerId != null) {
                    RaftShellStoreScope.create(sourceStoreScope.shellInstanceId, currentSourceServerId)
                } else {
                    sourceStoreScope
                }
            val selectInCurrentShell = !(fromShell && raftMultiShellHostEnabled())
            AppShellServerGateContent(
                session = restoredSession.copy(selectedServerId = null, selectedServerSlug = null),
                authDataSource = authDataSource,
                skipInitialLoad = skipInitialLoad,
                selectServerInCurrentShell = selectInCurrentShell,
                onReady = { readySession ->
                    pager.openAppShellForSelectedServer(
                        readySession = readySession,
                        sourceStoreScope = currentSourceScope,
                        sourceDocumentShell = sourceDocumentShell,
                        environmentId = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                        bridge = bridge,
                        fromShell = fromShell,
                        onSameServerInShell = closeChooser,
                        noteServerSelected = sharedStores::noteServerSwitchedToWindow,
                    )
                },
                onSignOut = {
                    bridge.realtimeDisconnect("logout")
                    pager.openLoginReplacingCurrentPage()
                },
                onBack = closeChooser.takeIf { fromShell }
            )
        }
    }
}

/**
 * Opens the app shell on [readySession]'s selected server in place of this page (the server chooser,
 * the join page): a server window of its own where separate windows are on, else this window's shell.
 */
internal fun Pager.openAppShellForSelectedServer(
    readySession: StoredAuthSession,
    sourceStoreScope: RaftShellStoreScope,
    sourceDocumentShell: Boolean,
    environmentId: String,
    bridge: BridgeModule,
    fromShell: Boolean = true,
    onSameServerInShell: (() -> Unit)? = null,
    noteServerSelected: (String) -> Unit = {},
    shellShowingServer: (serverId: String, exceptShellInstanceId: String) -> String? =
        raftProcessRealtimeSessionDemand::shellShowing,
    nowEpochMillis: () -> Long = ::raftNowEpochMillis,
) {
    dispatchOpenAppShellForSelectedServer(
        readySession = readySession,
        sourceStoreScope = sourceStoreScope,
        sourceDocumentShell = sourceDocumentShell,
        environmentId = environmentId,
        multiShellHostEnabled = raftMultiShellHostEnabled(),
        fromShell = fromShell,
        onSameServerInShell = onSameServerInShell,
        noteServerSelected = noteServerSelected,
        shellShowingServer = shellShowingServer,
        nowEpochMillis = nowEpochMillis,
        disconnectRealtime = bridge::realtimeDisconnect,
        openReplacingCurrentPage = ::openAppShellReplacingCurrentPage,
    )
}

internal fun dispatchOpenAppShellForSelectedServer(
    readySession: StoredAuthSession,
    sourceStoreScope: RaftShellStoreScope,
    sourceDocumentShell: Boolean,
    environmentId: String,
    multiShellHostEnabled: Boolean,
    fromShell: Boolean = true,
    onSameServerInShell: (() -> Unit)? = null,
    noteServerSelected: (String) -> Unit = {},
    shellShowingServer: (serverId: String, exceptShellInstanceId: String) -> String? = { _, _ -> null },
    nowEpochMillis: () -> Long = { 0L },
    disconnectRealtime: (reason: String) -> Unit,
    openReplacingCurrentPage: (JSONObject) -> Unit,
) {
    val selectedServerId = readySession.selectedServerId?.trim()?.takeIf { it.isNotEmpty() }
    if (
        fromShell &&
        selectedServerId != null &&
        selectedServerId == sourceStoreScope.initialServerId?.trim() &&
        onSameServerInShell != null
    ) {
        onSameServerInShell()
        return
    }
    val opensDocumentShell =
        fromShell &&
            isMultiShellCrossServerSelection(
                sourceStoreScope = sourceStoreScope,
                selectedServerId = selectedServerId,
                multiShellHostEnabled = multiShellHostEnabled
            )
    val windowShowing =
        if (fromShell && multiShellHostEnabled && selectedServerId != null) {
            shellShowingServer(selectedServerId, sourceStoreScope.shellInstanceId)
        } else {
            null
        }
    if (!opensDocumentShell && windowShowing == null) {
        disconnectRealtime("server_changed")
    }
    val pageData =
        appShellPageDataForServerSelection(
            sourceStoreScope = sourceStoreScope,
            sourceDocumentShell = sourceDocumentShell,
            selectedServerId = selectedServerId,
            multiShellHostEnabled = multiShellHostEnabled,
            suppressRestoreLanding = true,
            routeOwner = AuthenticatedRouteOwner(
                environmentId = environmentId,
                accountId = readySession.user.id,
                serverId = selectedServerId.orEmpty()
            ).normalizedOrNull(),
            fromShell = fromShell,
            windowShowingPickedServer = windowShowing,
        )
    if (opensDocumentShell || windowShowing != null) {
        pageData.put(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, nowEpochMillis())
        openAppShellServerSwitchWindow(
            serverId = selectedServerId.orEmpty(),
            pageData = pageData,
            noteServerSelected = noteServerSelected,
            open = openReplacingCurrentPage,
        )
    } else {
        openReplacingCurrentPage(pageData)
    }
}
