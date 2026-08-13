package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.data.NetworkSlockServerSetupProjectionApi
import build.raft.app.data.SlockAuthSessionEventSource
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.SlockServerSetupProjectionRepository
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY
import build.raft.app.model.ServerId
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException

internal data class ServerSetupAdmissionHost(
    val featureDecision: ServerSetupFeatureDecision,
    val state: ServerSetupAdmissionState?,
    val retry: () -> Unit,
    val content: @Composable () -> Unit,
) {
    val allowsHome: Boolean
        get() = serverSetupAllowsHome(featureDecision, state)
}

internal enum class ServerSetupFeatureDecision { Checking, Bypassed, Enabled }

internal fun serverSetupFeatureDecision(snapshot: ServerFeatureFlagSnapshot): ServerSetupFeatureDecision =
    when {
        !snapshot.resolved -> ServerSetupFeatureDecision.Checking
        snapshot.isEnabled(WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY) -> ServerSetupFeatureDecision.Enabled
        else -> ServerSetupFeatureDecision.Bypassed
    }

internal fun serverSetupAllowsHome(
    featureDecision: ServerSetupFeatureDecision,
    state: ServerSetupAdmissionState?,
): Boolean =
    featureDecision == ServerSetupFeatureDecision.Bypassed ||
        (featureDecision == ServerSetupFeatureDecision.Enabled && state is ServerSetupAdmissionState.Admitted)

@Composable
internal fun rememberServerSetupAdmissionHost(
    session: StoredAuthSession,
    serverId: String,
    environmentId: String,
    webBaseUrl: String,
    networkClient: SlockNetworkClient,
    shellAuthStore: SlockAuthSessionStore,
    authEvents: SlockAuthSessionEventSource,
    featureFlags: SharedServerFeatureFlags,
    bridge: BridgeModule,
    openExternalUrl: (String) -> Unit,
    refreshSignal: Int,
): ServerSetupAdmissionHost {
    val controller = rememberSlockWebViewController()
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(shellAuthStore, authEvents)
    val accountId = session.user.id.trim()
    val serverSlug = session.servers.firstOrNull { it.id == serverId }?.slug
        ?: session.selectedServerSlug?.takeIf { session.currentServerId == serverId }
        ?: ""
    val generation = remember(environmentId, accountId, serverId) {
        "native:${nextServerSetupGeneration.incrementAndGet()}"
    }
    val identity = remember(environmentId, accountId, serverId, generation) {
        ServerSetupAdmissionIdentity(environmentId, accountId, serverId, generation)
    }
    val repository = remember(networkClient) {
        SlockServerSetupProjectionRepository(NetworkSlockServerSetupProjectionApi(networkClient))
    }
    val controllerState = remember(repository) { ServerSetupAdmissionController(repository) }
    var featureDecision by remember(identity) {
        mutableStateOf(serverSetupFeatureDecision(featureFlags.snapshotFor(ServerId(serverId))))
    }
    var refreshEpoch by remember(identity) { mutableStateOf(0) }
    var state by remember(identity) { mutableStateOf<ServerSetupAdmissionState?>(null) }
    LaunchedEffect(featureFlags, identity) {
        if (featureDecision == ServerSetupFeatureDecision.Checking) {
            featureDecision =
                try {
                    serverSetupFeatureDecision(featureFlags.prefetch(ServerId(serverId)))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    ServerSetupFeatureDecision.Bypassed
                }
            if (featureDecision == ServerSetupFeatureDecision.Checking) {
                featureDecision = ServerSetupFeatureDecision.Bypassed
            }
        }
    }
    LaunchedEffect(repository, identity, featureDecision, refreshEpoch, refreshSignal) {
        if (featureDecision != ServerSetupFeatureDecision.Enabled) {
            state = null
            return@LaunchedEffect
        }
        state = controllerState.begin(identity)
        controllerState.load(identity)?.let { state = it }
    }

    val retry = { refreshEpoch += 1 }
    return ServerSetupAdmissionHost(
        featureDecision = featureDecision,
        state = state,
        retry = retry,
        content = {
            when {
                featureDecision == ServerSetupFeatureDecision.Checking -> SettingsWebViewLoadingCover()
                featureDecision == ServerSetupFeatureDecision.Bypassed -> Unit
                else ->
                    when (state) {
                        null -> SettingsWebViewLoadingCover()
                        is ServerSetupAdmissionState.Loading -> SettingsWebViewLoadingCover()
                        is ServerSetupAdmissionState.Failed -> SettingsWebViewErrorContent(
                            actionLabel = stringResource(MR.strings.common_retry),
                            actionIcon = SlockLucideIconName.RefreshCw,
                            onAction = retry,
                        )
                        is ServerSetupAdmissionState.Admitted -> Unit
                        is ServerSetupAdmissionState.Blocked -> {
                            SlockBackHandler(enabled = true, label = "server_setup_onboarding") { }
                            val binding = rememberSettingsWebViewHostAuthBinding(authSnapshot, controller)
                            val url = remember(webBaseUrl, serverSlug, generation) {
                                serverSetupOnboardingUrl(webBaseUrl, serverSlug, generation, bridge::urlEncode)
                            }
                            if (
                                binding == null ||
                                binding.accountId != accountId ||
                                binding.serverId != serverId ||
                                url == null
                            ) {
                                SettingsWebViewLoadingCover()
                            } else {
                                var documentReady by remember(identity) { mutableStateOf(false) }
                                val injectedJavaScript = remember(session, binding) {
                                    """
                                        (function() {
                                          try {
                                            ${settingsWebViewAuthStorageJavaScript(session, binding)};
                                            ${serverSetupHostBridgeJavaScript()}
                                          } catch (_) {}
                                        })();
                                    """.trimIndent()
                                }
                                Box(Modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
                                    SettingsWebViewContent(
                                        url = url,
                                        allowNavigation = false,
                                        allowedNavigationSchemes = setOf("http", "https"),
                                        injectedJavaScript = injectedJavaScript,
                                        controller = controller,
                                        generationKey = generation,
                                        onNavigationBlocked = { blockedUrl ->
                                            val wake = decodeServerSetupBridgeNavigation(blockedUrl, bridge::urlDecode)
                                            if (
                                                wake?.matches(identity, serverSlug) == true &&
                                                controllerState.acceptsWake(identity)
                                            ) {
                                                refreshEpoch += 1
                                            } else if (!blockedUrl.startsWith("$SERVER_SETUP_BRIDGE_SCHEME:")) {
                                                openExternalUrl(blockedUrl)
                                            }
                                        },
                                        onDocumentReadyChange = { documentReady = it },
                                    )
                                    SettingsWebViewAccessTokenSyncEffect(
                                        snapshot = authSnapshot,
                                        documentReady = documentReady,
                                        controller = controller,
                                        binding = binding,
                                        surface = SettingsWebViewSurface.Onboarding,
                                    )
                                }
                            }
                        }
                    }
            }
        },
    )
}

private val nextServerSetupGeneration = atomic(0L)
