package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.KeyValueRecompositionProfilerPreferenceStore
import build.raft.app.data.PersistentChatLayoutPreferenceRepository
import build.raft.app.data.PersistentLocaleRepository
import build.raft.app.data.PersistentRecompositionProfilerPreferenceRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsEffect
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.runCatchingPreservingCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager

internal data class StandaloneSettingsEnvironment(
    val dataSource: SharedSettingsDataSource,
    val authDataSource: SharedAuthDataSource,
    val serverMembersStore: SharedServerMembersStore,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val storeScope: SlockShellStoreScope,
)

@Composable
internal fun rememberStandaloneSettingsEnvironment(
    pager: Pager,
    bridge: BridgeModule
): StandaloneSettingsEnvironment {
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val authDataSource = remember(sharedStores, storeScope) {
        sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope)
    }
    val serverMembersStore = remember(sharedStores, storeScope) {
        sharedStores.serverMembersStore(storeScope)
    }
    val scope = rememberCoroutineScope()
    val settingsCapabilities =
        remember(bridge, scope) {
            BridgeSettingsPlatformCapabilities(bridge, scope.kuiklyBridgeCallContext())
        }
    val sharedPreferenceStore = remember { slockSharedPreferenceStore() }
    val dataSource =
        remember(authStore, networkClient, sharedPreferenceStore, settingsCapabilities) {
            SharedSettingsDataSource(
                sessionStore = authStore,
                settingsRepository =
                    RealSettingsRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        logger = { message -> bridge.log("settings_standalone_repository $message") },
                        warningLogger = { message -> bridge.log("settings_standalone_repository_warning $message") }
                    ),
                serverMembersStore = serverMembersStore,
                localeRepository = PersistentLocaleRepository(KeyValueLocalePreferenceStore(sharedPreferenceStore)),
                chatLayoutPreferenceRepository =
                    PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(sharedPreferenceStore)),
                recompositionProfilerPreferenceRepository =
                    PersistentRecompositionProfilerPreferenceRepository(
                        KeyValueRecompositionProfilerPreferenceStore(sharedPreferenceStore)
                    ),
                apiEnvironmentStore = sharedStores.apiEnvironmentStore,
                platformCapabilities = settingsCapabilities,
                diagnosticsLogger = { message -> bridge.log("settings_standalone $message") }
            )
        }
    return remember(
        dataSource,
        authDataSource,
        serverMembersStore,
        settingsCapabilities,
        storeScope,
    ) {
        StandaloneSettingsEnvironment(
            dataSource = dataSource,
            authDataSource = authDataSource,
            serverMembersStore = serverMembersStore,
            settingsCapabilities = settingsCapabilities,
            storeScope = storeScope,
        )
    }
}

@Composable
internal fun StandaloneSettingsEffectHandler(
    pager: Pager,
    environment: StandaloneSettingsEnvironment,
    state: SharedSettingsState,
    dispatch: (SharedSettingsIntent) -> Unit
) {
    LaunchedEffect(state.effect) {
        val effect = state.effect ?: return@LaunchedEffect
        when (effect) {
            SharedSettingsEffect.Logout -> {
                runCatchingPreservingCancellation { environment.authDataSource.logout() }
                pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                    .openPage(LOGIN_PAGE, loginPageData(replaceCurrent = true))
            }
            is SharedSettingsEffect.ApiEnvironmentChanged -> {
                environment.settingsCapabilities.bridgeToast(effect.message)
                runCatchingPreservingCancellation { environment.authDataSource.logout() }
                pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                    .openPage(LOGIN_PAGE, loginPageData(replaceCurrent = true))
            }
            is SharedSettingsEffect.OpenUrl -> {
                environment.settingsCapabilities.openUrl(effect.url)
            }
            is SharedSettingsEffect.CopyText -> {
                environment.settingsCapabilities.bridgeCopyToClipboard(effect.text)
            }
            is SharedSettingsEffect.ClearStorage -> {
                val result = environment.settingsCapabilities.clearStorage(effect.target)
                when (result) {
                    is DataResult.Content ->
                        dispatch(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = true,
                                snapshot = result.data
                            )
                        )
                    is DataResult.Empty ->
                        dispatch(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.reason.name
                            )
                        )
                    is DataResult.Error ->
                        dispatch(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.error.code
                            )
                        )
                    is DataResult.NoPermission ->
                        dispatch(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.blocker.code
                            )
                        )
                }
            }
            else -> Unit
        }
        dispatch(SharedSettingsIntent.ConsumeEffect)
    }
}
