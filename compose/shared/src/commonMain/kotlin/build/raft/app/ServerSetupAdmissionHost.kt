package build.raft.app

import build.raft.app.resources.*
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch

internal data class ServerSetupAdmissionHost(
    val state: ServerSetupAdmissionState?,
    val retry: () -> Unit,
    val content: @Composable () -> Unit,
    /** This server already passed the check earlier in this process (see [ServerSetupAdmissionMemory]). */
    val previouslyAllowed: Boolean = false,
) {
    val allowsHome: Boolean
        get() = serverSetupAllowsHome(state) ||
            serverSetupStillAllowsHome(previouslyAllowed, state)
}

/**
 * A server admitted earlier in this process keeps Home: its check is not run again (see
 * [runServerSetupCheck]), and a server switch no longer blanks to the loading cover.
 */
internal fun serverSetupStillAllowsHome(
    previouslyAllowed: Boolean,
    state: ServerSetupAdmissionState?,
): Boolean =
    previouslyAllowed && !serverSetupBlocksHome(state)

/**
 * Process-wide record of the servers whose setup projection said Admitted (`blocksChat` false),
 * per environment and account. Setup cannot become blocking again once complete, so an admitted
 * server is never re-checked in this process. A Blocked result forgets the server; a failed check
 * records nothing, so the next mount or foreground tries again. Main thread only.
 */
internal object ServerSetupAdmissionMemory {
    private val allowed = mutableSetOf<String>()

    fun key(environmentId: String, accountId: String, serverId: String): String =
        "$environmentId|$accountId|$serverId"

    fun contains(key: String): Boolean = key in allowed

    fun record(key: String, state: ServerSetupAdmissionState?) {
        when (state) {
            is ServerSetupAdmissionState.Admitted -> allowed += key
            is ServerSetupAdmissionState.Blocked -> allowed -= key
            else -> Unit
        }
    }

    internal fun clearForTest() = allowed.clear()
}

/**
 * The server decides who sees setup (`GET servers/:id/setup-projection` -> `blocksChat`), as on
 * Web; the app adds no flag of its own. Only a positive `blocksChat` shows setup: a failed check
 * (error, empty/bad body, timeout, offline) lets Home through unless setup was already showing.
 */
internal fun serverSetupAllowsHome(state: ServerSetupAdmissionState?): Boolean =
    state is ServerSetupAdmissionState.Admitted ||
        (state is ServerSetupAdmissionState.Failed && !state.duringSetup)

/** Setup (or its Retry screen, once setup was showing) takes the screen over Home. */
internal fun serverSetupBlocksHome(state: ServerSetupAdmissionState?): Boolean =
    state is ServerSetupAdmissionState.Blocked ||
        (state is ServerSetupAdmissionState.Failed && state.duringSetup)

/** The setup check has an answer for this server (Admitted, Blocked or Failed). */
internal fun serverSetupAdmissionResolved(state: ServerSetupAdmissionState?): Boolean =
    state is ServerSetupAdmissionState.Admitted ||
        state is ServerSetupAdmissionState.Blocked ||
        state is ServerSetupAdmissionState.Failed

/**
 * One setup check for [identity]. A server admitted earlier in this process sends no request (also
 * on return to foreground); otherwise publishes Loading, then the result.
 */
internal suspend fun runServerSetupCheck(
    controller: ServerSetupAdmissionController,
    identity: ServerSetupAdmissionIdentity,
    memoryKey: String,
    setupShowing: Boolean,
    publish: (ServerSetupAdmissionState) -> Unit,
) {
    if (ServerSetupAdmissionMemory.contains(memoryKey)) return
    publish(controller.begin(identity))
    val loaded = controller.load(identity) ?: return
    val result =
        if (loaded is ServerSetupAdmissionState.Failed && setupShowing) loaded.copy(duringSetup = true) else loaded
    ServerSetupAdmissionMemory.record(memoryKey, result)
    publish(result)
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
    var refreshEpoch by remember(identity) { mutableStateOf(0) }
    var state by remember(identity) { mutableStateOf<ServerSetupAdmissionState?>(null) }
    val memoryKey = ServerSetupAdmissionMemory.key(environmentId, accountId, serverId)
    var setupShown by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(repository, identity, refreshEpoch, refreshSignal) {
        runServerSetupCheck(controllerState, identity, memoryKey, setupShowing = setupShown) {
            if (it is ServerSetupAdmissionState.Blocked) setupShown = true
            state = it
        }
    }

    val retry = { refreshEpoch += 1 }
    // Read on every composition, not remembered: after a Blocked result forgets the server, a
    // retry's Loading must not show Home again.
    val previouslyAllowed = ServerSetupAdmissionMemory.contains(memoryKey)
    return ServerSetupAdmissionHost(
        state = state,
        previouslyAllowed = previouslyAllowed,
        retry = retry,
        content = {
            when (state) {
                null -> SettingsWebViewLoadingCover()
                is ServerSetupAdmissionState.Loading -> SettingsWebViewLoadingCover()
                is ServerSetupAdmissionState.Failed ->
                    if ((state as ServerSetupAdmissionState.Failed).duringSetup) {
                        SettingsWebViewErrorContent(
                            actionLabel = stringResource(Res.string.common_retry),
                            actionIcon = RaftLucideIconName.RefreshCw,
                            onAction = retry,
                        )
                    }
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
                                                                duringSetup = true,
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
        },
    )
}

private val nextServerSetupGeneration = atomic(0L)
