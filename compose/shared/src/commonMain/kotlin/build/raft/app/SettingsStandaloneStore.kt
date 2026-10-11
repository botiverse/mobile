package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.BridgeSlockAvatarNativeCache
import build.raft.app.data.ChatLayoutPreferenceRepository
import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.KeyValueLinkOpenPreferenceStore
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.KeyValueRealtimeTransportPreferenceStore
import build.raft.app.network.currentSlockNetworkTransportController
import build.raft.app.data.KeyValueHttp3PreferenceStore
import build.raft.app.data.PersistentChatLayoutPreferenceRepository
import build.raft.app.data.PersistentLinkOpenPreferenceRepository
import build.raft.app.data.PersistentLocaleRepository
import build.raft.app.data.NotificationPreferenceRepository
import build.raft.app.data.RealSettingsRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.SharedSettingsDataSource
import build.raft.app.data.SharedSettingsEffect
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.data.KuiklyChatLayoutPreferenceEvents
import build.raft.app.data.KuiklyNotificationPreferenceEvents
import build.raft.app.data.kuiklyChatLayoutPreferenceRepository
import build.raft.app.data.kuiklyNotificationPreferenceRepository
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.data.KeyValueThreadReverseLayoutPreferenceStore
import build.raft.app.data.MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY
import build.raft.app.data.currentFeatureFlagClientContext
import build.raft.app.model.ServerId
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

internal data class StandaloneSettingsEnvironment(
    val dataSource: SharedSettingsDataSource,
    val authDataSource: SharedAuthDataSource,
    val authStore: SlockAuthSessionStore,
    val serverMembersStore: SharedServerMembersStore,
    val settingsCapabilities: BridgeSettingsPlatformCapabilities,
    val chatLayoutPreferenceRepository: ChatLayoutPreferenceRepository,
    val notificationPreferenceRepository: NotificationPreferenceRepository,
    val storeScope: SlockShellStoreScope,
    val webBaseUrl: String,
)

/**
 * Standalone Settings pages must resolve their Web origin synchronously from
 * the canonical environment store. The developer screen state is loaded
 * asynchronously and can still be empty when a standalone WebView is created.
 */
internal fun standaloneSettingsWebBaseUrl(apiEnvironmentStore: SharedApiEnvironmentStore): String =
    apiEnvironmentStore.current().webUrl

@Composable
internal fun rememberStandaloneSettingsEnvironment(
    pager: Pager,
    bridge: BridgeModule
): StandaloneSettingsEnvironment {
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val scope = rememberCoroutineScope()
    val avatarNativeCache =
        remember(bridge, scope) {
            BridgeSlockAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = scope.kuiklyBridgeCallContext()
            )
        }
    val authDataSource = remember(sharedStores, storeScope, avatarNativeCache) {
        sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope, avatarNativeCache)
    }
    val serverMembersStore = remember(sharedStores, storeScope) {
        sharedStores.serverMembersStore(storeScope)
    }
    val linkNavigator = rememberSlockLinkNavigator(pager, bridge)
    val copiedMessage = stringResource(MR.strings.common_copied)
    val settingsCapabilities =
        remember(bridge, scope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = scope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }
    val sharedPreferenceStore = remember { slockSharedPreferenceStore() }
    val eventBus = remember(sharedStores) { sharedStores.eventBus }
    val chatLayoutPreferenceEvents = remember(eventBus) { KuiklyChatLayoutPreferenceEvents(eventBus) }
    val chatLayoutPreferenceRepository =
        remember(sharedPreferenceStore, chatLayoutPreferenceEvents) {
            kuiklyChatLayoutPreferenceRepository(
                delegate =
                    PersistentChatLayoutPreferenceRepository(
                        KeyValueChatLayoutPreferenceStore(sharedPreferenceStore)
                    ),
                events = chatLayoutPreferenceEvents,
            )
        }
    val notificationPreferenceEvents = remember(eventBus) { KuiklyNotificationPreferenceEvents(eventBus) }
    val notificationPreferenceRepository =
        remember(notificationPreferenceEvents) {
            kuiklyNotificationPreferenceRepository(notificationPreferenceEvents)
        }
    val dataSource =
        remember(
            sharedStores,
            authStore,
            networkClient,
            sharedPreferenceStore,
            settingsCapabilities,
            chatLayoutPreferenceRepository,
            notificationPreferenceRepository,
            avatarNativeCache,
        ) {
            SharedSettingsDataSource(
                sessionStore = authStore,
                settingsRepository =
                    RealSettingsRepository(
                        sessionStore = authStore,
                        networkClient = networkClient,
                        logger = { message -> bridge.log("settings_standalone_repository $message") },
                        warningLogger = { message -> bridge.log("settings_standalone_repository_warning $message") },
                        onAppearanceSnapshot = { ownerId, snapshot ->
                            messageBodyFontSizeOwnerKey(
                                sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                                ownerId,
                            )?.let { ownerKey ->
                                sharedStores.messageBodyFontSizeStore.acceptAuthoritative(ownerKey, snapshot)
                            }
                        },
                    ),
                serverMembersStore = serverMembersStore,
                notificationPreferenceRepository = notificationPreferenceRepository,
                localeRepository = PersistentLocaleRepository(KeyValueLocalePreferenceStore(sharedPreferenceStore)),
                chatLayoutPreferenceRepository = chatLayoutPreferenceRepository,
                linkOpenPreferenceRepository =
                    PersistentLinkOpenPreferenceRepository(KeyValueLinkOpenPreferenceStore(sharedPreferenceStore)),
                apiEnvironmentStore = sharedStores.apiEnvironmentStore,
                platformCapabilities = settingsCapabilities,
                diagnosticsLogger = { message -> bridge.log("settings_standalone $message") },
                realtimeTransportPreferenceStore = KeyValueRealtimeTransportPreferenceStore(sharedPreferenceStore),
                http3PreferenceStore = KeyValueHttp3PreferenceStore(sharedPreferenceStore),
                onHttp3UserChoiceChanged = { currentSlockNetworkTransportController()?.onHttp3UserChoiceChanged() },
                threadReverseLayoutPreferenceStore = KeyValueThreadReverseLayoutPreferenceStore(sharedPreferenceStore),
                threadReverseLayoutDefault = {
                    authStore.readSession()?.let { session ->
                        session.currentServerId?.let { serverId ->
                            resolveThreadReverseLayoutEnabled(
                                registry = ProcessDiagnosticFeatureFlagOverrides.registry,
                                serverEffective =
                                    sharedStores.serverFeatureFlags.isEnabled(
                                        ServerId(serverId),
                                        MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY
                                    ),
                                buildType = currentFeatureFlagClientContext().buildType,
                                environmentId = sharedStores.apiEnvironmentStore.current().apiBaseUrl,
                                accountId = session.user.id,
                                serverId = serverId
                            )
                        }
                    } ?: false
                }
            ).withAvatarCacheRuntime(
                runtime = sharedStores.avatarCacheRuntime,
                nativeCache = avatarNativeCache
            )
        }
    return remember(
        dataSource,
        authDataSource,
        authStore,
        serverMembersStore,
        settingsCapabilities,
        chatLayoutPreferenceRepository,
        notificationPreferenceRepository,
        storeScope,
        webBaseUrl,
    ) {
        StandaloneSettingsEnvironment(
            dataSource = dataSource,
            authDataSource = authDataSource,
            authStore = authStore,
            serverMembersStore = serverMembersStore,
            settingsCapabilities = settingsCapabilities,
            chatLayoutPreferenceRepository = chatLayoutPreferenceRepository,
            notificationPreferenceRepository = notificationPreferenceRepository,
            storeScope = storeScope,
            webBaseUrl = webBaseUrl,
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
                environment.settingsCapabilities.openExternalUrl(effect.url)
            }
            is SharedSettingsEffect.CopyText -> {
                environment.settingsCapabilities.bridgeCopyToClipboard(effect.text)
            }
            is SharedSettingsEffect.ClearStorage -> {
                val result = environment.dataSource.clearStorage(effect.target)
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
                                code = result.reason.name,
                                emptyReason = result.reason
                            )
                        )
                    is DataResult.Error ->
                        dispatch(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.error.code,
                                screenError = result.error
                            )
                        )
                    is DataResult.NoPermission ->
                        dispatch(
                            SharedSettingsIntent.CompleteStorageClear(
                                effect.target,
                                ok = false,
                                code = result.blocker.code,
                                permissionBlocker = result.blocker
                            )
                        )
                }
            }
            else -> Unit
        }
        dispatch(SharedSettingsIntent.ConsumeEffect)
    }
}
