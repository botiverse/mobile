package ai.slock.compose

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.data.BridgeSettingsPlatformCapabilities
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.KeyValueChatLayoutPreferenceStore
import ai.slock.compose.data.KeyValueLocalePreferenceStore
import ai.slock.compose.data.KeyValueRecompositionProfilerPreferenceStore
import ai.slock.compose.data.KuiklySlockAuthTransport
import ai.slock.compose.data.KuiklySlockNetworkClient
import ai.slock.compose.data.PersistentChatLayoutPreferenceRepository
import ai.slock.compose.data.PersistentLocaleRepository
import ai.slock.compose.data.PersistentRecompositionProfilerPreferenceRepository
import ai.slock.compose.data.RealSettingsRepository
import ai.slock.compose.data.SharedAuthDataSource
import ai.slock.compose.data.SharedComputersStore
import ai.slock.compose.data.SharedSettingsDataSource
import ai.slock.compose.data.SharedSettingsEffect
import ai.slock.compose.data.SharedSettingsIntent
import ai.slock.compose.data.SharedSettingsState
import ai.slock.compose.data.SlockComputersApiClient
import ai.slock.compose.data.SlockComputersNetworkApi
import ai.slock.compose.data.SlockSharedStoreRegistry
import ai.slock.compose.data.kuiklySharedPreferenceStore
import ai.slock.compose.data.runCatchingPreservingCancellation
import ai.slock.compose.network.SlockNetworkModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager

internal data class StandaloneSettingsEnvironment(
    val dataSource: SharedSettingsDataSource,
    val authDataSource: SharedAuthDataSource,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities
)

@Composable
internal fun rememberStandaloneSettingsEnvironment(
    pager: Pager,
    bridge: BridgeModule
): StandaloneSettingsEnvironment {
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(pager, bridge) }
    val authStore = sharedStores.authStore
    val networkModule = remember(pager) { pager.acquireModule<SlockNetworkModule>(SlockNetworkModule.MODULE_NAME) }
    val networkClient =
        remember(authStore, networkModule) {
            KuiklySlockNetworkClient(
                sessionStore = authStore,
                networkModule = networkModule,
                warningLogger = { message -> bridge.log("settings_standalone_network $message") }
            )
        }
    val authDataSource =
        remember(networkModule) {
            SharedAuthDataSource(
                transport = KuiklySlockAuthTransport(networkModule = networkModule),
                sessionStore = authStore,
                diagnosticsLogger = { message -> bridge.log("settings_standalone_auth $message") }
            )
        }
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
    val sharedPreferenceStore = remember(pager) { kuiklySharedPreferenceStore(pager) }
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
    return remember(dataSource, authDataSource, settingsCapabilities) {
        StandaloneSettingsEnvironment(
            dataSource = dataSource,
            authDataSource = authDataSource,
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
