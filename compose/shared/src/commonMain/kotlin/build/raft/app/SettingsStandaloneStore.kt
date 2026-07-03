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
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsEffect
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SlockSharedStoreRegistry
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
    val settingsCapabilities: BridgeSettingsPlatformCapabilities
)

@Composable
internal fun rememberStandaloneSettingsEnvironment(
    pager: Pager,
    bridge: BridgeModule
): StandaloneSettingsEnvironment {
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(pager, bridge) }
    val authStore = sharedStores.authStore
    val networkClient = sharedStores.networkClient
    val authDataSource = sharedStores.authDataSource
    val computersApiClient =
        remember(networkClient) {
            SlockComputersApiClient(SlockComputersNetworkApi(networkClient))
        }
    val scope = rememberCoroutineScope()
    val computersStore =
        remember(computersApiClient, scope) {
            SharedComputersStore(
                computersApiClient = computersApiClient,
                scope = scope,
                logger = { message -> bridge.log("settings_standalone_$message") }
            )
        }
    val settingsCapabilities = remember(bridge) { BridgeSettingsPlatformCapabilities(bridge) }
    val sharedPreferenceStore = remember(pager) { slockSharedPreferenceStore() }
    val dataSource =
        remember(authStore, networkClient, computersStore, sharedPreferenceStore, settingsCapabilities) {
            SharedSettingsDataSource(
                sessionStore = authStore,
                settingsRepository =
                    RealSettingsRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        logger = { message -> bridge.log("settings_standalone_repository $message") },
                        warningLogger = { message -> bridge.log("settings_standalone_repository_warning $message") }
                    ),
                computersSource = computersStore,
                serverMembersStore = sharedStores.serverMembersStore,
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
    return remember(dataSource, authDataSource, sharedStores.serverMembersStore, settingsCapabilities) {
        StandaloneSettingsEnvironment(
            dataSource = dataSource,
            authDataSource = authDataSource,
            serverMembersStore = sharedStores.serverMembersStore,
            settingsCapabilities = settingsCapabilities
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
            is SharedSettingsEffect.OpenUrl -> {
                environment.settingsCapabilities.openUrl(effect.url)
            }
            is SharedSettingsEffect.CopyText -> {
                environment.settingsCapabilities.bridgeCopyToClipboard(effect.text)
            }
            is SharedSettingsEffect.OpenComputer -> {
                pager.openSettingsComputerDetailPage(effect.machineId, null)
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
