package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.BridgeModule
import build.raft.app.data.NetworkRaftServerSetupProjectionApi
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RaftServerSetupProjectionRepository
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.ServerFeatureFlagSnapshot
import build.raft.app.data.WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY
import build.raft.app.model.ServerId
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class ServerSetupAdmissionHost(
    val featureDecision: ServerSetupFeatureDecision,
    val state: ServerSetupAdmissionState?,
    val retry: () -> Unit,
    val content: @Composable () -> Unit,
    /** This server already passed the check earlier in this process (see [ServerSetupAdmissionMemory]). */
    val previouslyAllowed: Boolean = false,
) {
    val allowsHome: Boolean
        get() = serverSetupAllowsHome(featureDecision, state) ||
            serverSetupStillAllowsHome(previouslyAllowed, state)
}

/**
 * A server that passed the check before keeps Home while its check re-runs (a server switch
 * restarts it): the shell no longer blanks to the loading cover for a round trip on every switch.
 * Only a fresh Blocked or Failed result takes the screen.
 */
internal fun serverSetupStillAllowsHome(
    previouslyAllowed: Boolean,
    state: ServerSetupAdmissionState?,
): Boolean =
    previouslyAllowed && state !is ServerSetupAdmissionState.Blocked && state !is ServerSetupAdmissionState.Failed

/**
 * Process-wide record of the servers whose setup check allowed Home (Admitted or Bypassed), per
 * environment and account. Main thread only. A Blocked or Failed result forgets the server.
 */
internal object ServerSetupAdmissionMemory {
    private val allowed = mutableSetOf<String>()

    fun key(environmentId: String, accountId: String, serverId: String): String =
        "$environmentId|$accountId|$serverId"

    fun contains(key: String): Boolean = key in allowed

    fun record(key: String, allowsHome: Boolean, state: ServerSetupAdmissionState?) {
        when {
            allowsHome -> allowed += key
            state is ServerSetupAdmissionState.Blocked || state is ServerSetupAdmissionState.Failed -> allowed -= key
        }
    }

    internal fun clearForTest() = allowed.clear()
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

internal suspend fun resolveServerSetupFeatureDecision(
    featureFlags: SharedServerFeatureFlags,
    serverId: ServerId,
    evaluationContext: String? = null,
): ServerSetupFeatureDecision =
    try {
        serverSetupFeatureDecision(featureFlags.prefetch(serverId, evaluationContext = evaluationContext))
            .let { if (it == ServerSetupFeatureDecision.Checking) ServerSetupFeatureDecision.Bypassed else it }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        ServerSetupFeatureDecision.Bypassed
    }

@Composable
internal fun rememberServerSetupAdmissionHost(
    session: StoredAuthSession,
    serverId: String,
    environmentId: String,
    webBaseUrl: String,
    networkClient: RaftNetworkClient,
    shellAuthStore: RaftAuthSessionStore,
    authEvents: RaftAuthSessionEventSource,
    featureFlags: SharedServerFeatureFlags,
    authDataSource: SharedAuthDataSource,
    bridge: BridgeModule,
    openExternalUrl: (String) -> Unit,
    refreshSignal: Int,
): ServerSetupAdmissionHost {
    val scope = rememberCoroutineScope()
    val controller = rememberRaftWebViewController()
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
        RaftServerSetupProjectionRepository(NetworkRaftServerSetupProjectionApi(networkClient))
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
                resolveServerSetupFeatureDecision(
                    featureFlags = featureFlags,
                    serverId = ServerId(serverId),
                    evaluationContext = environmentId,
                )
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
    val memoryKey = ServerSetupAdmissionMemory.key(environmentId, accountId, serverId)
    val previouslyAllowed = remember(memoryKey) { ServerSetupAdmissionMemory.contains(memoryKey) }
    LaunchedEffect(memoryKey, featureDecision, state) {
        ServerSetupAdmissionMemory.record(memoryKey, serverSetupAllowsHome(featureDecision, state), state)
    }
    return ServerSetupAdmissionHost(
        featureDecision = featureDecision,
        state = state,
        previouslyAllowed = previouslyAllowed,
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
                            actionIcon = RaftLucideIconName.RefreshCw,
                            onAction = retry,
                        )
                        is ServerSetupAdmissionState.Admitted -> Unit
                        is ServerSetupAdmissionState.Blocked -> {
                            RaftBackHandler(enabled = true, label = "server_setup_onboarding") { }
                            val binding = rememberSettingsWebViewHostAuthBinding(authSnapshot, controller)
                            val url = remember(webBaseUrl, serverSlug, generation) {
                                serverSetupOnboardingUrl(webBaseUrl, serverSlug, generation)
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
                                var currentDocumentUrl by remember(identity) { mutableStateOf(url) }
                                val injectedJavaScript = remember(session, binding) {
                                    """
                                        (function() {
                                          try {
                                            ${settingsWebViewAuthStorageJavaScript(session, binding)};
                                            ${serverSetupHostBridgeJavaScript(identity)}
                                          } catch (_) {}
                                        })();
                                    """.trimIndent()
                                }
                                Box(Modifier.fillMaxSize().background(RaftColor.SurfaceCream)) {
                                    SettingsWebViewContent(
                                        url = url,
                                        allowNavigation = false,
                                        allowedNavigationSchemes = setOf("http", "https"),
                                        injectedJavaScript = injectedJavaScript,
                                        controller = controller,
                                        generationKey = generation,
                                        onNavigationBlocked = { blockedUrl ->
                                            val event = decodeServerSetupHostBridgeNavigation(blockedUrl, bridge::urlDecode)
                                            bridge.log(
                                                "server_setup_navigation_blocked scheme=${blockedUrl.substringBefore(':')} " +
                                                    "event=${when (event) {
                                                        is ServerSetupHostEvent.StateMayHaveChanged -> "completion"
                                                        is ServerSetupHostEvent.SwitchServer -> "server_switch"
                                                        null -> "unknown"
                                                    }}"
                                            )
                                            when (event) {
                                                is ServerSetupHostEvent.StateMayHaveChanged -> {
                                                    val controlledDocument =
                                                        serverSetupControlledDocumentMatchesOrigin(currentDocumentUrl, webBaseUrl)
                                                    val wakeMatches = event.wake.matches(identity, serverSlug)
                                                    val accepted = controlledDocument && wakeMatches && controllerState.acceptsWake(identity)
                                                    bridge.log(
                                                        "server_setup_completion_wake accepted=$accepted " +
                                                            "controlledDocument=$controlledDocument wakeMatches=$wakeMatches"
                                                    )
                                                    if (accepted) refreshEpoch += 1
                                                }
                                                is ServerSetupHostEvent.SwitchServer -> {
                                                    val targetId = serverSetupSwitchTargetId(
                                                        session = session,
                                                        identity = identity,
                                                        intent = event.intent,
                                                        activeOwner = controllerState.acceptsWake(identity),
                                                        controlledDocument = serverSetupControlledDocumentMatchesOrigin(
                                                            currentDocumentUrl,
                                                            webBaseUrl,
                                                        ),
                                                    )
                                                    if (targetId != null && controllerState.claimSwitch(identity)) {
                                                        state = ServerSetupAdmissionState.Loading(identity)
                                                        scope.launch {
                                                            when (
                                                                authDataSource.selectServer(
                                                                    serverId = targetId,
                                                                    expectedSelectedServerId = serverId,
                                                                )
                                                            ) {
                                                                is SharedAuthResult.Success<*> -> Unit
                                                                else -> state = controllerState.begin(identity).let {
                                                                    ServerSetupAdmissionState.Failed(
                                                                        identity,
                                                                        build.raft.app.data.ScreenError(
                                                                            code = "server_setup_switch_failed",
                                                                            retryable = true,
                                                                        ),
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                null -> if (!blockedUrl.startsWith("$SERVER_SETUP_BRIDGE_SCHEME:")) {
                                                    openExternalUrl(blockedUrl)
                                                }
                                            }
                                        },
                                        onUrlChange = { currentDocumentUrl = it },
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
