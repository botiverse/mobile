package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.BridgeSlockAvatarNativeCache
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.desc

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
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val sourceDocumentShell = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(SLOCK_DOCUMENT_SHELL, false)
    }
    val skipInitialLoad = remember(pager.pageData.params.toString()) {
        pager.pageData.params.optBoolean(AUTH_CHOOSE_SERVER_SKIP_INITIAL_LOAD, false)
    }
    val closeChooser = remember(pager) {
        { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    }
    val sharedStores = remember { SlockSharedStoreRegistry.get(bridge) }
    rememberAuthRuntimeCapabilities(
        store = sharedStores.authRuntimeCapabilityStore,
        pageData = pager.pageData.params
    )
    val bridgeCallScope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, bridgeCallScope) {
            BridgeSlockAvatarNativeCache(
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
                        AuthGateMessage.Static(MR.strings.auth_error_choose_server_sign_in_required.desc())
                    } else {
                        null
                    }
            }
            .onFailure {
                session = null
                authDataSource.logDiagnostics(
                    "auth_choose_server_page_session result=failure restoreAttempt=$restoreAttempt"
                )
                errorPresentation = AuthGateMessage.Static(MR.strings.auth_error_restore_session.desc())
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
                            ?: stringResource(MR.strings.auth_error_choose_server_sign_in_required),
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
                    val multiShellHostEnabled =
                        slockPlatformSupportsMultiShellHost() &&
                            KeyValueChatLayoutPreferenceStore(slockSharedPreferenceStore())
                                .readMultiShellHostEnabledSync()
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
