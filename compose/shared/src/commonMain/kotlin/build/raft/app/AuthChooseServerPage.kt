package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.BridgeRaftAvatarNativeCache
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
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
                    onBack = closeChooser.takeIf { fromShell }
                )
            }
        }
        else ->
            AppShellServerGateContent(
                session = restoredSession.copy(selectedServerId = null, selectedServerSlug = null),
                authDataSource = authDataSource,
                skipInitialLoad = skipInitialLoad,
                onReady = { readySession ->
                    val multiShellHostEnabled = raftMultiShellHostEnabled()
                    val opensDocumentShell =
                        isMultiShellCrossServerSelection(
                            sourceStoreScope = sourceStoreScope,
                            selectedServerId = readySession.selectedServerId,
                            multiShellHostEnabled = multiShellHostEnabled
                        )
                    if (!opensDocumentShell) {
                        bridge.realtimeDisconnect("server_changed")
                    }
                    pager.openAppShellReplacingCurrentPage(
                        pageData =
                            appShellPageDataForServerSelection(
                                sourceStoreScope = sourceStoreScope,
                                sourceDocumentShell = sourceDocumentShell,
                                selectedServerId = readySession.selectedServerId,
                                multiShellHostEnabled = multiShellHostEnabled,
                                suppressRestoreLanding = true,
                                routeOwner = AuthenticatedRouteOwner(
                                    environmentId =
                                        sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                                    accountId = readySession.user.id,
                                    serverId = readySession.selectedServerId.orEmpty()
                                ).normalizedOrNull()
                            )
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
