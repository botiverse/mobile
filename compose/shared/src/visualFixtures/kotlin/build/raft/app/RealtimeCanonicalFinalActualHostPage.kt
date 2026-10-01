package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.BasePager
import build.raft.app.base.Utils
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.DataResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.NetworkSlockTasksApi
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealForwardMessageTargetRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealtimeCanonicalFinalSinkCapturedRegistration
import build.raft.app.data.RealtimeCanonicalFinalSinkEnvelope
import build.raft.app.data.RealtimeCanonicalFinalSinkIntegrationSeam
import build.raft.app.data.RealtimeCanonicalFinalSinkLazyRouter
import build.raft.app.data.RealtimeCanonicalFinalSinkDeliveryLedgerKey
import build.raft.app.data.RealtimeCanonicalFinalSinkSurfaceKey
import build.raft.app.data.durablePartition
import build.raft.app.data.RealtimeCanonicalFinalOwnerEntry
import build.raft.app.data.RealtimeScopeAuthority
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAuthGateIntent
import build.raft.app.data.SharedAuthGateViewModel
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeDataSource
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedMembersStore
import build.raft.app.data.SharedSettingsApiEnvironmentRequest
import build.raft.app.data.SharedTasksDataSource
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.data.SlockHomeApi
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAgentsNetworkApi
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SlockSharedCanonicalFinalIntegrationFactory
import build.raft.app.data.SlockSharedCanonicalFinalIntegrationFactoryScope
import build.raft.app.data.SlockSharedStoreFactoryBindingHandle
import build.raft.app.data.SlockSharedStoreProvider
import build.raft.app.data.SlockSharedStoreProviderFactory
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockSharedKeyValueStoreProvider
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.HomeSnapshot
import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CurrentGrantMessageShadowCommitOutcome
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.CodecModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val ActualHostPageName = "compose_realtime_canonical_final_actual_host"
private const val ActualHostLogTag = "CanonicalFinalHost"
private const val ActualHostTraceMarker = "canonical_final_actual_host_trace"
// Shared platform logging caps the full message at 2,048 characters. Keep the JSON itself below that native
// transport ceiling (and therefore below the externally frozen 3,000-byte evidence ceiling) so a valid phase can
// never be silently truncated before the connected test recomputes its hash.
private const val ActualHostMaxPayloadBytes = 1_800
private const val ActualHostMaxRegistrations = 8
private const val ActualHostServerA = "host-server-a"
private const val ActualHostServerB = "host-server-b"

private fun standaloneThreadSurfaces(nonce: String): List<String> =
    listOf(
        "ThreadCore:actual-host-thread-$nonce@$ActualHostServerA",
        "ThreadFeature:actual-host-thread-$nonce@$ActualHostServerA",
    )

private fun sharedAppSurfaces(): List<String> =
    listOf(
        "AppShellDomain@$ActualHostServerA",
        "AppShellHomeUnread@$ActualHostServerB",
    )

private fun sharedComposedSurfaces(nonce: String): List<String> =
    standaloneThreadSurfaces(nonce).take(2) +
        listOf(
            "AppShellDomain@$ActualHostServerA",
            "AppShellHomeUnread@$ActualHostServerB",
        )

/** Parses a registration trace into slot key ("surface@serverId") → exact registrationId. */
private fun registrationIdsBySlot(trace: String): Map<String, String> {
    if (trace.isBlank()) return emptyMap()
    return trace.split(';').associate { entry ->
        val fields = entry.split(',')
        check(fields.size == 5) { "malformed registration trace entry: $entry" }
        "${fields[0]}@${fields[2]}" to fields[3]
    }
}

/**
 * Successor-retention contract check. Real components may legitimately re-register their own slot while
 * still mounted (minting a fresh exact handle during async loading), so this intentionally does NOT require
 * byte equality with a frozen snapshot. It requires: every expected slot is present, and no slot has fallen
 * back to the forbidden (old-generation) registrationId. Failure messages carry the actual trace.
 */
/**
 * Deterministic terminal-payload builder. Never throws: the registrations capture is best-effort and the
 * final rendered UTF-8 JSON is budgeted to [maxBytes] by dropping optional diagnostics first and then
 * halving the error text, so exactly one parseable terminal sentinel is always emitted even for the
 * largest failure message, the maximum registration trace, or a diagnostics capture that itself throws.
 * Public so the connected suite can prove these properties deterministically on device.
 */
fun buildCanonicalFinalTerminalPayload(
    runNonce: String,
    terminalSeq: Int,
    phaseCount: Int,
    lastSeq: Int,
    chainSha256: String,
    success: Boolean,
    error: String,
    maxBytes: Int,
    captureRegistrations: () -> String,
): String {
    val registrationsSnapshot =
        runCatching(captureRegistrations).getOrElse { failure ->
            "capture-failed:${failure.message.orEmpty().take(120)}"
        }

    fun render(errorText: String, registrationsText: String): String =
        JSONObject().apply {
            put("runNonce", runNonce)
            put("seq", terminalSeq)
            put("terminal", true)
            put("success", success)
            put("error", errorText)
            put("phaseCount", phaseCount)
            put("lastSeq", lastSeq)
            put("chainSha256", chainSha256)
            put("registrations", registrationsText)
        }.toString()

    var errorText = error
    var registrationsText = registrationsSnapshot
    var payload = render(errorText, registrationsText)
    if (payload.encodeToByteArray().size > maxBytes) {
        registrationsText = "omitted:over-budget"
        payload = render(errorText, registrationsText)
    }
    while (payload.encodeToByteArray().size > maxBytes && errorText.isNotEmpty()) {
        errorText = errorText.take(errorText.length / 2)
        payload = render(errorText, registrationsText)
    }
    return payload
}

/**
 * Successor-retention barrier. A live successor may legitimately re-register its own slot while
 * async first-load state settles, and the production lifecycle is dispose-then-register, so the
 * slot set can be transiently INCOMPLETE between frames (run 30076537436 attempt 1: ThreadFeature
 * absent at a single sample while every other successor id stayed in place). Slot completeness is
 * therefore sampled until it settles, bounded by [timeoutMs]; a persistent gap is a real
 * retention loss and fails with the actual trace.
 *
 * The non-restoration contract is NOT deferred: every sample — initial and each resample — must
 * be free of old-generation ids. A slot that transiently restores a forbidden id fails
 * immediately even if a fresh id would have replaced it later. Public so the connected suite can
 * prove all three behaviors deterministically with a scripted sampler.
 */
suspend fun awaitCanonicalFinalSuccessorRetention(
    expectedSurfaces: List<String>,
    forbiddenIdsBySlot: Map<String, String>,
    label: String,
    timeoutMs: Long = 15_000L,
    delayMs: Long = 50L,
    sample: suspend () -> String,
): String {
    val expected = expectedSurfaces.sorted()

    fun assertNoRestoration(trace: String) {
        registrationIdsBySlot(trace).forEach { (slot, registrationId) ->
            val forbidden = forbiddenIdsBySlot[slot] ?: return@forEach
            check(registrationId != forbidden) {
                "$label; slot $slot restored old-generation registration $forbidden actual=$trace"
            }
        }
    }

    var trace = sample()
    assertNoRestoration(trace)
    var elapsed = 0L
    while (registrationIdsBySlot(trace).keys.sorted() != expected && elapsed < timeoutMs) {
        delay(delayMs)
        elapsed += delayMs
        trace = sample()
        assertNoRestoration(trace)
    }
    check(registrationIdsBySlot(trace).keys.sorted() == expected) {
        "$label; successor slots incomplete after ${elapsed}ms settle expected=$expected actual=$trace"
    }
    return trace
}

private suspend fun checkSuccessorRetained(
    run: ActualHostRun,
    provider: SlockSharedStoreProvider,
    scopeKey: String,
    expectedSurfaces: List<String>,
    forbiddenIdsBySlot: Map<String, String>,
    label: String,
) {
    awaitCanonicalFinalSuccessorRetention(
        expectedSurfaces = expectedSurfaces,
        forbiddenIdsBySlot = forbiddenIdsBySlot,
        label = label,
    ) {
        run.registrationTrace(provider, scopeKey)
    }
}

@Page(ActualHostPageName, supportInLocal = true)
internal class RealtimeCanonicalFinalActualHostPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val nonce = pageData.params.optString("runNonce").trim()
        val port = pageData.params.optInt("blackholePort")
        require(nonce.isNotBlank()) { "actual host run nonce is required" }
        require(port in 1..65535) { "actual host loopback port is required" }
        val preconfiguredEnvironment =
            SharedApiEnvironmentStore(
                SlockSharedKeyValueStoreProvider.create(
                    namespace = SharedApiEnvironmentStore.Namespace,
                    storageName = "actual-host API environment",
                ).store,
            ).save(actualHostEnvironmentRequest(nonce, port))
        checkNotNull(preconfiguredEnvironment) { "actual host could not preconfigure the loopback environment" }
        val run = ActualHostRun(nonce = nonce, blackholePort = port)
        run.binding =
            SlockSharedStoreRegistry.bindFactoryBeforeFirstGet(
                SlockSharedStoreProviderFactory { request ->
                    run.recordProviderFactoryCall()
                    SlockSharedStoreProvider.create(
                        eventBus = request.eventBus,
                        source = request.source,
                        bridge = request.bridge,
                        canonicalFinalIntegrationFactory =
                            SlockSharedCanonicalFinalIntegrationFactory { dependencies ->
                                run.recordIntegrationFactoryCall(dependencies)
                                dependencies.createIntegration(
                                    t4FlagKey = "visual-host-never-enabled-$nonce",
                                ).also(run::recordIntegration)
                            },
                    ).also(run::recordProvider)
                },
            )
        setContent { RealtimeCanonicalFinalActualHostContent(run) }
    }
}

@Composable
private fun RealtimeCanonicalFinalActualHostContent(run: ActualHostRun) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val codec = remember(pager.pagerId) { pager.acquireModule<CodecModule>(CodecModule.MODULE_NAME) }
    val provider = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val sharedScope = remember(run.nonce) { SlockShellStoreScope.create("canonical-final-app-${run.nonce}", ActualHostServerA) }
    val appShellAuthGateViewModel: SharedAuthGateViewModel =
        viewModel(key = appShellAuthGateViewModelKey(pager.pagerId)) {
            SharedAuthGateViewModel(provider.createAuthDataSourceForCurrentEnvironment(sharedScope))
        }
    val standaloneThreadScope =
        remember(run.nonce) { SlockShellStoreScope.create("canonical-final-thread-${run.nonce}", ActualHostServerA) }
    val standaloneThreadHost =
        remember(provider, standaloneThreadScope, bridge) {
            createActualThreadHost(provider, standaloneThreadScope, bridge, run)
        }
    val sharedThreadHost =
        remember(provider, sharedScope, bridge) {
            createActualThreadHost(provider, sharedScope, bridge, run)
        }
    var standaloneThreadA by remember { mutableStateOf(false) }
    var standaloneThreadB by remember { mutableStateOf(false) }
    var sharedAppA by remember { mutableStateOf(false) }
    var sharedAppB by remember { mutableStateOf(false) }
    var sharedThreadA by remember { mutableStateOf(false) }
    var sharedThreadB by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (standaloneThreadA) {
            key("actual-standalone-thread-a") {
                ActualThreadContent(standaloneThreadHost)
            }
        }
        if (standaloneThreadB) {
            key("actual-standalone-thread-b") {
                ActualThreadContent(standaloneThreadHost)
            }
        }
        if (sharedAppA) {
            key("actual-shared-app-a") {
                SlockAppShellContent(pageVisibleTick = 1)
            }
        }
        if (sharedAppB) {
            key("actual-shared-app-b") {
                SlockAppShellContent(pageVisibleTick = 2)
            }
        }
        if (sharedThreadA) {
            key("actual-shared-thread-a") {
                ActualThreadContent(sharedThreadHost)
            }
        }
        if (sharedThreadB) {
            key("actual-shared-thread-b") {
                ActualThreadContent(sharedThreadHost)
            }
        }
    }

    LaunchedEffect(run) {
        val authLedgerJob =
            launch(start = CoroutineStart.UNDISPATCHED) {
                provider.authStore.authSessionEvents.collect(run::recordAuthEvent)
            }
        var egressProbeJob: Job? = null
        try {
            check(run.provider === provider) { "registry returned a provider outside the bound full create" }
            check(run.providerFactoryCount() == 1) { "full provider factory must run exactly once" }
            check(run.integrationFactoryCount() == 1) { "integration factory must run exactly once" }
            check(provider.authStore.readSession() == null) { "actual host requires pm-clear empty auth state" }
            check(pager.pageData.params.optString(SLOCK_SHELL_INSTANCE_ID) == sharedScope.shellInstanceId) {
                "AppShell page data must carry the exact app host scope"
            }
            check(pager.pageData.params.optString(SLOCK_SHELL_SERVER_ID) == ActualHostServerA) {
                "AppShell page data must carry the exact selected server"
            }

            val expectedEnvironment =
                provider.apiEnvironmentStore.save(
                    actualHostEnvironmentRequest(run.nonce, run.blackholePort),
                )
            checkNotNull(expectedEnvironment) { "loopback environment was rejected" }
            check(provider.apiEnvironmentStore.current() == expectedEnvironment) { "loopback environment did not persist" }
            run.recordEnvironment(expectedEnvironment.apiBaseUrl, expectedEnvironment.socketBaseUrl, expectedEnvironment.webUrl)
            run.emitPhase(codec, provider, component = "host", phase = "environment-ready", scopeKey = sharedScope.shellInstanceId)

            provider.authStore.replaceAuthenticatedSession(actualHostSession(run.nonce))
            waitUntil("authenticated session") { provider.authStore.readSession()?.user?.id == "host-user-${run.nonce}" }
            waitUntil("typed authenticated auth event") { "Authenticated:1" in run.authEventNames() }
            // Exercise the same scoped production HTTP client the actual components own. The instrumentation-owned
            // server accepts and immediately resets loopback connections; it proves that the full provider graph
            // resolves api/socket/web to loopback without serial 30-second network stalls, while the workflow's
            // target-UID non-loopback counters remain exact zero.
            egressProbeJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    provider.networkClient(sharedScope).request(
                        priority = DataRequestPriority.Foreground,
                        path = "/actual-host-loopback-probe",
                    )
                }
            run.emitPhase(codec, provider, component = "host", phase = "session-authenticated", scopeKey = sharedScope.shellInstanceId)

            val probeHomeStore =
                provider.getOrCreateHomeStore(
                    scope = sharedScope,
                    dataSource = actualHostProbeHomeDataSource(provider, sharedScope),
                )
            probeHomeStore.accept(SharedHomeIntent.Start)
            waitUntil("probe Home content") {
                probeHomeStore.state.value.content is SharedHomeContentState.Content
            }
            val probeAgentStore = provider.agentDetailStore(sharedScope)
            val probeAgentId = MemberId("actual-host-agent-${run.nonce}")
            // #308 / canonical-final fixture: ApplyAgentActivityUpdate is now
            // gated on the currently-selected agent — the probe MUST bind the
            // identity through the real SelectMember intent or every agent
            // projection assertion below stays null-fenced.
            probeAgentStore.dispatch(SharedAgentDetailIntent.SelectMember(memberId = probeAgentId))
            waitUntil("probe agent selected") {
                probeAgentStore.state.value.agentId == probeAgentId
            }
            val initialAgentSeq = probeAgentStore.state.value.agentActivitySeq[probeAgentId]
            val initialBackgroundUnread = probeHomeStore.state.value.serverUnreadCounts[ActualHostServerB] ?: 0

            // Segment A: a standalone Thread still owns a shell scope, not a component-private authority.
            check(provider.realtimeAuthorityProvider.authorityFlow(standaloneThreadScope.shellInstanceId).value == null)
            run.emitPhase(
                codec,
                provider,
                component = "standalone-thread",
                phase = "before-mount",
                scopeKey = standaloneThreadScope.shellInstanceId,
            )
            standaloneThreadA = true
            delay(1_000)
            val standaloneFirstAuthority =
                provider.realtimeAuthorityProvider.authorityFlow(standaloneThreadScope.shellInstanceId).value
                    ?: error("C2 missing binder-gated standalone Thread authority")
            check(standaloneFirstAuthority.grantsByServer.keys.containsAll(setOf(ActualHostServerA, ActualHostServerB))) {
                "C2 standalone Thread authority must expose both real session servers"
            }
            waitUntil("standalone Thread Core+Feature registrations") {
                run.registrationSurfaces(provider, standaloneThreadScope.shellInstanceId) ==
                    standaloneThreadSurfaces(run.nonce)
            }
            val standaloneRouteKey = "actual-host-thread-${run.nonce}"
            val standaloneOldCoreHandle =
                run.currentRegistrationHandle(
                    provider,
                    standaloneThreadScope,
                    RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore(standaloneRouteKey),
                ) ?: error("missing old standalone Thread Core exact handle")
            val standaloneOldFeatureHandle =
                run.currentRegistrationHandle(
                    provider,
                    standaloneThreadScope,
                    RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature(standaloneRouteKey),
                ) ?: error("missing old standalone Thread Feature exact handle")
            val standaloneFirstRegistrations = run.registrationTrace(provider, standaloneThreadScope.shellInstanceId)
            run.emitPhase(
                codec,
                provider,
                component = "standalone-thread",
                phase = "first-mounted",
                scopeKey = standaloneThreadScope.shellInstanceId,
            )

            provider.authStore.updateTokens(
                accessToken = "host-access-rotated-${run.nonce}",
                refreshToken = "host-refresh-rotated-${run.nonce}",
            )
            waitUntil("standalone Thread authority rotation") {
                val next = provider.realtimeAuthorityProvider.authorityFlow(standaloneThreadScope.shellInstanceId).value
                next != null &&
                    next.lease.id == standaloneFirstAuthority.lease.id &&
                    next.witness.connectionFamilyId != standaloneFirstAuthority.witness.connectionFamilyId
            }
            waitUntil("typed token rotation auth event") { "TokensRotated:2" in run.authEventNames() }
            check(run.registrationTrace(provider, standaloneThreadScope.shellInstanceId) == standaloneFirstRegistrations) {
                "standalone Thread rotation must keep Core/Feature handles and sink refs stable"
            }
            run.emitPhase(
                codec,
                provider,
                component = "standalone-thread",
                phase = "auth-rotated",
                scopeKey = standaloneThreadScope.shellInstanceId,
            )

            standaloneThreadB = true
            waitUntil("standalone Thread successor surface replacement") {
                run.registrationSurfaces(provider, standaloneThreadScope.shellInstanceId) == standaloneThreadSurfaces(run.nonce) &&
                    run.registrationTrace(provider, standaloneThreadScope.shellInstanceId) != standaloneFirstRegistrations
            }
            check(
                provider.realtimeAuthorityProvider.authorityFlow(standaloneThreadScope.shellInstanceId).value?.lease?.id ==
                    standaloneFirstAuthority.lease.id,
            ) { "Thread component replacement must not replace the shared shell lease" }
            run.emitPhase(
                codec,
                provider,
                component = "standalone-thread",
                phase = "replacement-mounted",
                scopeKey = standaloneThreadScope.shellInstanceId,
            )
            standaloneThreadA = false
            withFrameNanos { }
            withFrameNanos { }
            waitUntil("old standalone Thread dispose retains successor surfaces") {
                val currentCore =
                    run.currentRegistrationHandle(
                        provider,
                        standaloneThreadScope,
                        RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore(standaloneRouteKey),
                    )
                val currentFeature =
                    run.currentRegistrationHandle(
                        provider,
                        standaloneThreadScope,
                        RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature(standaloneRouteKey),
                    )
                run.registrationSurfaces(provider, standaloneThreadScope.shellInstanceId) ==
                    standaloneThreadSurfaces(run.nonce) &&
                    currentCore != null &&
                    currentFeature != null &&
                    currentCore != standaloneOldCoreHandle &&
                    currentFeature != standaloneOldFeatureHandle
            }
            val standaloneCurrentCoreHandle =
                run.currentRegistrationHandle(
                    provider,
                    standaloneThreadScope,
                    RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore(standaloneRouteKey),
                ) ?: error("missing successor standalone Thread Core exact handle after old dispose")
            val standaloneCurrentFeatureHandle =
                run.currentRegistrationHandle(
                    provider,
                    standaloneThreadScope,
                    RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature(standaloneRouteKey),
                ) ?: error("missing successor standalone Thread Feature exact handle after old dispose")
            run.recordStandaloneDisposeWitness(
                oldCoreRegistrationId = standaloneOldCoreHandle.registrationId,
                oldFeatureRegistrationId = standaloneOldFeatureHandle.registrationId,
                currentCoreRegistrationId = standaloneCurrentCoreHandle.registrationId,
                currentFeatureRegistrationId = standaloneCurrentFeatureHandle.registrationId,
            )
            run.emitPhase(
                codec,
                provider,
                component = "standalone-thread",
                phase = "old-disposed",
                scopeKey = standaloneThreadScope.shellInstanceId,
            )
            run.clearStandaloneDisposeWitness()
            standaloneThreadB = false
            waitUntil("standalone Thread component surface disposal") {
                run.registrationSurfaces(provider, standaloneThreadScope.shellInstanceId).isEmpty()
            }
            check(provider.realtimeAuthorityProvider.authorityFlow(standaloneThreadScope.shellInstanceId).value != null) {
                "Thread component final dispose must retain the shell authority"
            }
            run.emitPhase(
                codec,
                provider,
                component = "standalone-thread",
                phase = "final-component-disposed",
                scopeKey = standaloneThreadScope.shellInstanceId,
            )
            provider.releaseShellStoreScope(standaloneThreadScope)
            waitUntil("standalone Thread outer shell release") {
                provider.realtimeAuthorityProvider.authorityFlow(standaloneThreadScope.shellInstanceId).value == null
            }
            run.emitPhase(
                codec,
                provider,
                component = "standalone-thread",
                phase = "outer-shell-released",
                scopeKey = standaloneThreadScope.shellInstanceId,
            )

            // Segment B: AppShell and Thread share one production shell authority and compose one selected mask.
            check(provider.realtimeAuthorityProvider.authorityFlow(sharedScope.shellInstanceId).value == null)
            run.emitPhase(codec, provider, component = "shared", phase = "before-mount", scopeKey = sharedScope.shellInstanceId)
            sharedAppA = true
            val sharedFirstAuthority =
                waitForAuthority(provider, sharedScope.shellInstanceId, requireServers = setOf(ActualHostServerA, ActualHostServerB))
            waitUntil("shared AppShell Domain+Unread registrations") {
                run.registrationSurfaces(provider, sharedScope.shellInstanceId) == sharedAppSurfaces()
            }
            run.emitPhase(codec, provider, component = "shared", phase = "app-mounted", scopeKey = sharedScope.shellInstanceId)

            sharedThreadA = true
            waitUntil("shared AppShell+Thread composed registrations") {
                run.registrationSurfaces(provider, sharedScope.shellInstanceId) == sharedComposedSurfaces(run.nonce)
            }
            val sharedComposedRegistrations = run.registrationTrace(provider, sharedScope.shellInstanceId)
            run.emitPhase(codec, provider, component = "shared", phase = "thread-mounted", scopeKey = sharedScope.shellInstanceId)

            val observedAgentBaseline = initialAgentSeq ?: -1L
            val selectedRepairSeq = maxOf(observedAgentBaseline + 101L, 1_001L)
            val selectedOverlapSeq = selectedRepairSeq + 1L
            val selectedReadySeq = selectedOverlapSeq + 1L
            val selectedRepairEvent = actualHostAgentPresence(probeAgentId, selectedRepairSeq)
            val selectedReadyEvent = actualHostAgentPresence(probeAgentId, selectedReadySeq)
            val selectedOverlapEvent = actualHostAgentPresence(probeAgentId, selectedOverlapSeq)
            val backgroundRepairEvent = actualHostBackgroundMessage(run.nonce, "repair", seq = 2_001L)
            val backgroundOverlapEvent = actualHostBackgroundMessage(run.nonce, "overlap", seq = 2_002L)
            val backgroundReadyEvent = actualHostBackgroundMessage(run.nonce, "ready", seq = 2_003L)

            val preReadyBaseline = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerA,
                entry = RealtimeCanonicalFinalOwnerEntry.Selected,
                eventKey = "selected-repair-${run.nonce}",
                event = selectedRepairEvent,
            )
            val preReadySelected = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                preReadySelected.coreLedger == preReadyBaseline.coreLedger + 1,
                "pre-ready selected event must reach ThreadCore",
            )
            run.expect(
                preReadySelected.featureLedger == preReadyBaseline.featureLedger + 1,
                "pre-ready selected event must reach ThreadFeature",
            )
            run.expect(
                preReadySelected.domainLedger == preReadyBaseline.domainLedger,
                "pre-ready App Domain must fail closed without a delivered ledger entry",
            )
            run.expect(
                preReadySelected.unreadLedger == preReadyBaseline.unreadLedger,
                "selected event must not enter App unread",
            )
            run.expect(
                preReadySelected.agentSeq == observedAgentBaseline,
                "pre-ready App Domain must not mutate the agent projection",
            )
            run.emitPhase(codec, provider, component = "shared", phase = "pre-ready-selected", scopeKey = sharedScope.shellInstanceId)

            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-repair-${run.nonce}",
                event = backgroundRepairEvent,
            )
            val preReadyUnread = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                preReadyUnread.unreadLedger == preReadySelected.unreadLedger,
                "pre-ready App Unread must fail closed without a delivered ledger entry",
            )
            run.expect(
                preReadyUnread.backgroundUnread == initialBackgroundUnread,
                "pre-ready App Unread must not mutate the background unread projection",
            )
            run.expect(
                preReadyUnread.coreLedger == preReadySelected.coreLedger &&
                    preReadyUnread.featureLedger == preReadySelected.featureLedger &&
                    preReadyUnread.domainLedger == preReadySelected.domainLedger,
                "background event must not enter selected surfaces",
            )
            run.emitPhase(codec, provider, component = "shared", phase = "pre-ready-unread", scopeKey = sharedScope.shellInstanceId)

            val domainRegistrationA = preReadySelected.domainHandle
            val unreadRegistrationA = preReadyUnread.unreadHandle
            val staleAppDomainHandle =
                run.currentRegistrationHandle(
                    provider,
                    sharedScope,
                    RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain,
                ) ?: error("missing old App A Domain exact handle")
            val staleAppUnreadHandle =
                run.currentRegistrationHandle(
                    provider,
                    sharedScope,
                    RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread,
                ) ?: error("missing old App A Unread exact handle")
            sharedAppB = true
            sharedThreadB = true
            waitUntil("shared component successor surface replacement") {
                run.registrationSurfaces(provider, sharedScope.shellInstanceId) == sharedComposedSurfaces(run.nonce) &&
                    run.registrationTrace(provider, sharedScope.shellInstanceId) != sharedComposedRegistrations
            }
            val sharedReplacement = run.registrationTrace(provider, sharedScope.shellInstanceId)
            val sharedOldGenerationIds = registrationIdsBySlot(sharedComposedRegistrations)
            check(registrationIdsBySlot(sharedReplacement).none { (slot, id) -> sharedOldGenerationIds[slot] == id }) {
                "shared replacement must mint new registrations for every slot; old=$sharedComposedRegistrations actual=$sharedReplacement"
            }
            check(provider.realtimeAuthorityProvider.authorityFlow(sharedScope.shellInstanceId).value?.lease?.id == sharedFirstAuthority.lease.id) {
                "shared component replacement must not replace the shell lease"
            }
            val overlapBaseline = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            val domainRegistrationB = overlapBaseline.domainHandle
            val unreadRegistrationB = overlapBaseline.unreadHandle
            run.expect(
                domainRegistrationA.isNotBlank() && domainRegistrationB.isNotBlank() && domainRegistrationA != domainRegistrationB,
                "App successor must own a distinct exact Domain registration handle",
            )
            run.expect(
                unreadRegistrationA.isNotBlank() && unreadRegistrationB.isNotBlank() && unreadRegistrationA != unreadRegistrationB,
                "App successor must own a distinct exact Unread registration handle",
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerA,
                entry = RealtimeCanonicalFinalOwnerEntry.Selected,
                eventKey = "selected-overlap-${run.nonce}",
                event = selectedOverlapEvent,
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-overlap-${run.nonce}",
                event = backgroundOverlapEvent,
            )
            val overlapPreReady = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                overlapPreReady.lastDispatchDomainHandle == domainRegistrationB,
                "overlap K2 must be captured against the successor Domain handle",
            )
            run.expect(
                overlapPreReady.lastDispatchUnreadHandle == unreadRegistrationB,
                "overlap KB2 must be captured against the successor Unread handle",
            )
            run.expect(
                overlapPreReady.domainLedger == overlapBaseline.domainLedger,
                "not-ready successor Domain must not write K2 into its delivered ledger",
            )
            run.expect(
                overlapPreReady.agentSeq == observedAgentBaseline,
                "not-ready successor Domain must not mutate the projection",
            )
            run.expect(
                overlapPreReady.unreadLedger == overlapBaseline.unreadLedger,
                "not-ready successor Unread must not write KB2 into its delivered ledger",
            )
            run.expect(
                overlapPreReady.backgroundUnread == initialBackgroundUnread,
                "not-ready successor Unread must not mutate the projection",
            )
            run.expect(overlapPreReady.coreLedger == overlapBaseline.coreLedger + 1, "overlap selected event must still enter Core")
            run.expect(overlapPreReady.featureLedger == overlapBaseline.featureLedger + 1, "overlap selected event must still enter Feature")
            run.emitPhase(codec, provider, component = "shared", phase = "overlap-pre-ready", scopeKey = sharedScope.shellInstanceId)

            // Replay A's two captured old exact handles through the production provider after B owns the slots.
            // This is the sole lifecycle-driver exception and proves a delayed/duplicate stale cleanup cannot
            // remove or mutate the successor. The real App A DisposableEffect cleanup runs immediately after.
            run.expect(
                !provider.realtimeAuthorityProvider.unregisterCanonicalFinalSink(staleAppDomainHandle),
                "stale App A Domain exact-handle replay must be rejected",
            )
            run.expect(
                !provider.realtimeAuthorityProvider.unregisterCanonicalFinalSink(staleAppUnreadHandle),
                "stale App A Unread exact-handle replay must be rejected",
            )
            checkSuccessorRetained(
                run = run,
                provider = provider,
                scopeKey = sharedScope.shellInstanceId,
                expectedSurfaces = sharedComposedSurfaces(run.nonce),
                forbiddenIdsBySlot = sharedOldGenerationIds,
                label = "stale App A exact-handle replay must retain all successor registrations",
            )
            val afterStaleExactHandleReplay = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                afterStaleExactHandleReplay.domainHandle != domainRegistrationA &&
                    afterStaleExactHandleReplay.unreadHandle != unreadRegistrationA &&
                    afterStaleExactHandleReplay.coreLedger == overlapPreReady.coreLedger &&
                    afterStaleExactHandleReplay.featureLedger == overlapPreReady.featureLedger &&
                    afterStaleExactHandleReplay.domainLedger == overlapPreReady.domainLedger &&
                    afterStaleExactHandleReplay.unreadLedger == overlapPreReady.unreadLedger &&
                    afterStaleExactHandleReplay.agentSeq == overlapPreReady.agentSeq &&
                    afterStaleExactHandleReplay.backgroundUnread == overlapPreReady.backgroundUnread,
                "stale App A cleanup must retain successor-generation handles, ledgers, and projections",
            )
            run.emitPhase(
                codec,
                provider,
                component = "shared",
                phase = "stale_exact_handle_replay",
                scopeKey = sharedScope.shellInstanceId,
            )

            sharedAppA = false
            delay(350)
            checkSuccessorRetained(
                run = run,
                provider = provider,
                scopeKey = sharedScope.shellInstanceId,
                expectedSurfaces = sharedComposedSurfaces(run.nonce),
                forbiddenIdsBySlot = sharedOldGenerationIds,
                label = "real App A cleanup must retain all successor registrations",
            )
            val afterRealOldAppDispose = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                afterRealOldAppDispose.domainHandle != domainRegistrationA &&
                    afterRealOldAppDispose.unreadHandle != unreadRegistrationA &&
                    afterRealOldAppDispose.coreLedger == overlapPreReady.coreLedger &&
                    afterRealOldAppDispose.featureLedger == overlapPreReady.featureLedger &&
                    afterRealOldAppDispose.domainLedger == overlapPreReady.domainLedger &&
                    afterRealOldAppDispose.unreadLedger == overlapPreReady.unreadLedger &&
                    afterRealOldAppDispose.agentSeq == overlapPreReady.agentSeq &&
                    afterRealOldAppDispose.backgroundUnread == overlapPreReady.backgroundUnread,
                "real App A cleanup must retain successor-generation handles, ledgers, and projections",
            )
            run.emitPhase(
                codec,
                provider,
                component = "shared",
                phase = "real_old_app_dispose",
                scopeKey = sharedScope.shellInstanceId,
            )

            appShellAuthGateViewModel.accept(SharedAuthGateIntent.ServerReady(actualHostSession(run.nonce)))
            delay(500)
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerA,
                entry = RealtimeCanonicalFinalOwnerEntry.Selected,
                eventKey = "selected-repair-${run.nonce}",
                event = selectedRepairEvent,
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-repair-${run.nonce}",
                event = backgroundRepairEvent,
            )
            val repaired = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                repaired.domainLedger == preReadyBaseline.domainLedger + 1,
                "ready replay must add exactly one Domain ledger entry",
            )
            run.expect(
                repaired.unreadLedger == preReadyBaseline.unreadLedger + 1,
                "ready replay must add exactly one Unread ledger entry",
            )
            run.expect(repaired.agentSeq == selectedRepairSeq, "ready replay must repair the real agent projection")
            run.expect(
                repaired.backgroundUnread == initialBackgroundUnread + 1,
                "ready replay must repair the real background unread projection",
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerA,
                entry = RealtimeCanonicalFinalOwnerEntry.Selected,
                eventKey = "selected-repair-${run.nonce}",
                event = selectedRepairEvent,
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-repair-${run.nonce}",
                event = backgroundRepairEvent,
            )
            val repairedReplay = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(repairedReplay == repaired, "same raw repair replay must not mutate any ledger or projection twice")
            run.emitPhase(codec, provider, component = "shared", phase = "ready-repaired", scopeKey = sharedScope.shellInstanceId)

            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerA,
                entry = RealtimeCanonicalFinalOwnerEntry.Selected,
                eventKey = "selected-overlap-${run.nonce}",
                event = selectedOverlapEvent,
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-overlap-${run.nonce}",
                event = backgroundOverlapEvent,
            )
            val overlapRepaired = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                overlapRepaired.domainHandle == domainRegistrationB &&
                    overlapRepaired.lastDispatchDomainHandle == domainRegistrationB,
                "overlap repair must remain bound to the successor Domain handle",
            )
            run.expect(
                overlapRepaired.domainLedger == repaired.domainLedger + 1,
                "ready successor replay must add exactly one Domain ledger entry",
            )
            run.expect(
                overlapRepaired.unreadLedger == repaired.unreadLedger + 1,
                "ready successor replay must add exactly one Unread ledger entry",
            )
            run.expect(overlapRepaired.agentSeq == selectedOverlapSeq, "ready successor replay must repair the agent projection once")
            run.expect(
                overlapRepaired.backgroundUnread == repaired.backgroundUnread + 1,
                "ready successor replay must repair the background unread projection once",
            )
            run.expect(
                overlapRepaired.coreLedger == overlapPreReady.coreLedger &&
                    overlapRepaired.featureLedger == overlapPreReady.featureLedger,
                "successor repair must not redeliver Thread surfaces",
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerA,
                entry = RealtimeCanonicalFinalOwnerEntry.Selected,
                eventKey = "selected-overlap-${run.nonce}",
                event = selectedOverlapEvent,
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-overlap-${run.nonce}",
                event = backgroundOverlapEvent,
            )
            val overlapReplay = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(overlapReplay == overlapRepaired, "successor same-raw replay must be idempotent")
            run.emitPhase(codec, provider, component = "shared", phase = "overlap-repaired", scopeKey = sharedScope.shellInstanceId)

            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerA,
                entry = RealtimeCanonicalFinalOwnerEntry.Selected,
                eventKey = "selected-ready-${run.nonce}",
                event = selectedReadyEvent,
            )
            val readySelected = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(readySelected.coreLedger == overlapRepaired.coreLedger + 1, "ready selected event must enter Core once")
            run.expect(readySelected.featureLedger == overlapRepaired.featureLedger + 1, "ready selected event must enter Feature once")
            run.expect(readySelected.domainLedger == overlapRepaired.domainLedger + 1, "ready selected event must enter Domain once")
            run.expect(readySelected.unreadLedger == overlapRepaired.unreadLedger, "ready selected event must not enter Unread")
            run.expect(readySelected.agentSeq == selectedReadySeq, "ready selected event must update the real Domain projection")

            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-ready-${run.nonce}",
                event = backgroundReadyEvent,
            )
            val readyBackground = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(readyBackground.coreLedger == readySelected.coreLedger, "background event must not enter Core")
            run.expect(readyBackground.featureLedger == readySelected.featureLedger, "background event must not enter Feature")
            run.expect(readyBackground.domainLedger == readySelected.domainLedger, "background event must not enter Domain")
            run.expect(readyBackground.unreadLedger == readySelected.unreadLedger + 1, "background event must enter Unread once")
            run.expect(
                readyBackground.backgroundUnread == overlapRepaired.backgroundUnread + 1,
                "background event must increment the real B unread projection once",
            )
            run.dispatchCanonical(
                provider = provider,
                scope = sharedScope,
                serverId = ActualHostServerB,
                entry = RealtimeCanonicalFinalOwnerEntry.BackgroundUnread,
                eventKey = "background-ready-${run.nonce}",
                event = backgroundReadyEvent,
            )
            val readyBackgroundReplay = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(readyBackgroundReplay == readyBackground, "ready background same-raw replay must be idempotent")
            run.emitPhase(codec, provider, component = "shared", phase = "ready-mask", scopeKey = sharedScope.shellInstanceId)

            provider.authStore.updateTokens(
                accessToken = "host-access-shared-rotated-${run.nonce}",
                refreshToken = "host-refresh-shared-rotated-${run.nonce}",
            )
            waitUntil("shared shell authority rotation") {
                val next = provider.realtimeAuthorityProvider.authorityFlow(sharedScope.shellInstanceId).value
                next != null &&
                    next.lease.id == sharedFirstAuthority.lease.id &&
                    next.witness.connectionFamilyId != sharedFirstAuthority.witness.connectionFamilyId
            }
            waitUntil("second typed token rotation auth event") { "TokensRotated:3" in run.authEventNames() }
            checkSuccessorRetained(
                run = run,
                provider = provider,
                scopeKey = sharedScope.shellInstanceId,
                expectedSurfaces = sharedComposedSurfaces(run.nonce),
                forbiddenIdsBySlot = sharedOldGenerationIds,
                label = "shared shell rotation must retain successor-generation registrations",
            )
            run.emitPhase(codec, provider, component = "shared", phase = "auth-rotated", scopeKey = sharedScope.shellInstanceId)

            run.emitPhase(codec, provider, component = "shared", phase = "replacements-mounted", scopeKey = sharedScope.shellInstanceId)
            sharedThreadA = false
            delay(350)
            checkSuccessorRetained(
                run = run,
                provider = provider,
                scopeKey = sharedScope.shellInstanceId,
                expectedSurfaces = sharedComposedSurfaces(run.nonce),
                forbiddenIdsBySlot = sharedOldGenerationIds,
                label = "old shared component disposal must retain all successor surfaces",
            )
            val afterOldDispose = run.captureProbe(provider, sharedScope, probeAgentId, probeHomeStore)
            run.expect(
                afterOldDispose.domainHandle != domainRegistrationA &&
                    afterOldDispose.unreadHandle != unreadRegistrationA &&
                    afterOldDispose.coreLedger == readyBackground.coreLedger &&
                    afterOldDispose.featureLedger == readyBackground.featureLedger &&
                    afterOldDispose.domainLedger == readyBackground.domainLedger &&
                    afterOldDispose.unreadLedger == readyBackground.unreadLedger &&
                    afterOldDispose.backgroundUnread == readyBackground.backgroundUnread,
                "disposing old Thread A must retain successor B handles, ledgers, and unread projection",
            )
            run.emitPhase(codec, provider, component = "shared", phase = "old-components-disposed", scopeKey = sharedScope.shellInstanceId)

            sharedThreadB = false
            waitUntil("shared Thread final component disposal") {
                run.registrationSurfaces(provider, sharedScope.shellInstanceId) == sharedAppSurfaces()
            }
            check(provider.realtimeAuthorityProvider.authorityFlow(sharedScope.shellInstanceId).value != null)
            run.emitPhase(codec, provider, component = "shared", phase = "thread-final-disposed", scopeKey = sharedScope.shellInstanceId)

            sharedAppB = false
            waitUntil("shared AppShell final component disposal") {
                run.registrationSurfaces(provider, sharedScope.shellInstanceId).isEmpty()
            }
            check(provider.realtimeAuthorityProvider.authorityFlow(sharedScope.shellInstanceId).value != null) {
                "shared shell authority must survive final component disposal"
            }
            run.emitPhase(codec, provider, component = "shared", phase = "app-final-disposed", scopeKey = sharedScope.shellInstanceId)
            provider.releaseShellStoreScope(sharedScope)
            waitUntil("shared outer shell release") {
                provider.realtimeAuthorityProvider.authorityFlow(sharedScope.shellInstanceId).value == null
            }
            run.emitPhase(codec, provider, component = "shared", phase = "outer-shell-released", scopeKey = sharedScope.shellInstanceId)

            check(run.authEventNames().none { it.startsWith("Cleared:") }) { "auth session was cleared during actual host run" }
            run.requireNoProbeViolations()
            run.emitTerminal(codec, success = true, error = "", provider = provider, scopeKey = sharedScope.shellInstanceId)
        } catch (throwable: Throwable) {
            run.emitTerminal(
                codec = codec,
                success = false,
                error = throwable.message.orEmpty().take(1800),
                provider = provider,
                scopeKey = sharedScope.shellInstanceId,
            )
        } finally {
            egressProbeJob?.cancel()
            authLedgerJob.cancel()
        }
    }
}

@Composable
private fun ActualThreadContent(host: ActualThreadHost) {
    val timeContextProvider = rememberLiveSlockTimeContext(host.payload.toSlockTimeCapabilities())
    SharedThreadRouteContent(
        initialPayload = host.payload,
        timeContextProvider = timeContextProvider,
        bridge = host.bridge,
        dataSource = host.dataSource,
        forwardTargetRepository = host.forwardTargetRepository,
        threadSendScope = host.provider.threadSendScope,
        authStore = host.authStore,
        tasksDataSource = host.tasksDataSource,
        tasksDataStore = host.provider.tasksDataStore,
        computersStore = host.computersStore,
        membersStore = host.membersStore,
        serverMembersStore = host.provider.serverMembersStore(host.scope),
        conversationIdentityProvider = host.provider.conversationIdentityProvider(host.scope),
        storeScope = host.scope,
        composerDraftPersistSlice = host.provider.composerDraftPersistSlice,
        eventBus = host.provider.eventBus,
        onBack = host.run::recordThreadUiCallback,
        onOpenThreadPayload = { host.run.recordThreadUiCallback() },
        onReplaceThreadPayload = { host.run.recordThreadUiCallback() },
        onOpenParentThread = { _, _ -> false },
        onAuthRequired = { host.run.recordThreadUiCallback() },
    )
}

private data class ActualThreadHost(
    val provider: SlockSharedStoreProvider,
    val scope: SlockShellStoreScope,
    val bridge: build.raft.app.base.BridgeModule,
    val authStore: build.raft.app.data.SlockAuthSessionStore,
    val dataSource: SharedThreadDataSource,
    val forwardTargetRepository: RealForwardMessageTargetRepository,
    val tasksDataSource: SharedTasksDataSource,
    val computersStore: SharedComputersStore,
    val membersStore: SharedMembersStore,
    val payload: KuiklyThreadScreenPayload,
    val run: ActualHostRun,
)

private fun createActualThreadHost(
    provider: SlockSharedStoreProvider,
    scope: SlockShellStoreScope,
    bridge: build.raft.app.base.BridgeModule,
    run: ActualHostRun,
): ActualThreadHost {
    val authStore = provider.authStore(scope)
    val networkClient = provider.networkClient(scope)
    val memberRepository =
        RealMemberRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            logger = { message -> bridge.log("actual_host_thread_member $message") },
        )
    val channelRepository =
        RealChannelRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            warningLogger = { message -> bridge.log("actual_host_thread_channel $message") },
        )
    val routeScope = CoroutineScope(SupervisorJob())
    val membersStore =
        SharedMembersStore(
            authStore = authStore,
            channelRepository = channelRepository,
            scope = routeScope,
        )
    val computersStore =
        SharedComputersStore(
            computersApiClient = SlockComputersApiClient(SlockComputersNetworkApi(networkClient)),
            scope = routeScope,
        )
    val dataSource =
        SharedThreadDataSource(
            networkApi = ActualHostThreadApi(run),
            sessionStore = authStore,
            selectedServerStore = provider.selectedServerStore(scope),
            memberRepository = memberRepository,
            serverMembersStore = provider.serverMembersStore(scope),
            // The content fetch is the only deterministic boundary in this actual-component host. Keeping this
            // null makes SharedThreadDataSource use the typed SharedThreadNetworkApi fixture below; auth, scope,
            // stores, repositories, event bus, and the component itself all remain the real provider graph.
            conversationRepository = null,
            channelRepository = channelRepository,
            messageStore = provider.threadMessageStore,
            // Same environment source as the production ThreadPage wiring (provider-owned store).
            cacheEnvironmentIdProvider = { provider.apiEnvironmentStore.current().apiBaseUrl },
            replyCacheIdentityAuthority = {
                provider.captureThreadReplyCacheIdentity(scope)
            },
            replyCacheIdentityTransitionWaiter = { transitionAnchor ->
                provider.awaitThreadReplyCacheIdentityTransition(scope, transitionAnchor)
            },
            payloadStore = provider.threadPayloadStore,
            agentsApiClient = SlockAgentsApiClient(SlockAgentsNetworkApi(networkClient)),
            diagnosticsLogger = { message -> bridge.log("actual_host_thread_api $message") },
        )
    val payload =
        KuiklyThreadScreenPayload(
            instanceId = "actual-host-thread-${run.nonce}",
            title = "Actual host thread",
            status = "content",
            sourceThreadId = "host-channel-a",
            streamChannelId = "host-channel-a",
            canSend = true,
            showModeTabs = false,
            messages = emptyList(),
        )
    dataSource.cachePayload(payload)
    val forwardRepository =
        RealForwardMessageTargetRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            channelRepository = channelRepository,
            memberRepository = memberRepository,
        )
    run.recordThreadParameters(
        provider = provider,
        authStore = authStore,
        networkClient = networkClient,
        dataSource = dataSource,
        tasksDataStore = provider.tasksDataStore,
        composerDraftPersistSlice = provider.composerDraftPersistSlice,
        eventBus = provider.eventBus,
    )
    return ActualThreadHost(
        provider = provider,
        scope = scope,
        bridge = bridge,
        authStore = authStore,
        dataSource = dataSource,
        forwardTargetRepository = forwardRepository,
        tasksDataSource = SharedTasksDataSource(NetworkSlockTasksApi(networkClient)),
        computersStore = computersStore,
        membersStore = membersStore,
        payload = payload,
        run = run,
    )
}

private class ActualHostThreadApi(
    private val run: ActualHostRun,
) : SharedThreadNetworkApi {
    override suspend fun channelMessages(threadId: String, limit: Int, beforeSeq: Long?, afterSeq: Long?, requestTrace: String? = null): HomeNetworkResponse {
        run.recordThreadDataRequest()
        return HomeNetworkResponse(code = 200, bodyText = """{"messages":[],"historyLimited":false}""")
    }

    override suspend fun messageSync(channelId: String, sinceSeq: Long, limit: Int, requestTrace: String? = null): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = "[]")

    override suspend fun sendMessage(channelId: String, body: String, asTask: Boolean, attachmentIds: List<String>, randomId: String?): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"messages":[],"historyLimited":false}""")

    override suspend fun executePendingMentionActions(action: String, resolutionIds: List<String>): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"results":[]}""")

    override suspend fun attachmentUrl(attachmentId: String, disposition: String?): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"url":"http://127.0.0.1/attachment"}""")

    override suspend fun attachmentHtmlPreviewUrl(attachmentId: String): HomeNetworkResponse = attachmentUrl(attachmentId)

    override suspend fun attachmentComments(attachmentId: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"comments":[],"threadChannelId":"host-comments"}""")

    override suspend fun sendAttachmentComment(attachmentId: String, body: String): HomeNetworkResponse = okResponse()
    override suspend fun markChannelReadToSeq(channelId: String, seq: Long): HomeNetworkResponse = okResponse()
    override suspend fun markChannelReadAll(channelId: String): HomeNetworkResponse = okResponse()
    override suspend fun saveMessage(messageId: String): HomeNetworkResponse = okResponse()
    override suspend fun unsaveMessage(messageId: String): HomeNetworkResponse = okResponse()
    override suspend fun addReaction(messageId: String, emoji: String): HomeNetworkResponse = okResponse()
    override suspend fun removeReaction(messageId: String, emoji: String): HomeNetworkResponse = okResponse()
    override suspend fun createOrOpenThread(channelId: String, parentMessageId: String): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"threadChannelId":"host-thread","parentMessageId":"$parentMessageId","created":true}""")

    override suspend fun messageContext(messageId: String, channelId: String?): HomeNetworkResponse =
        HomeNetworkResponse(code = 200, bodyText = """{"channelId":"$channelId","targetMessageId":"$messageId","messages":[],"historyLimited":false}""")

    private fun okResponse(): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")
}

private data class ActualHostProbeSnapshot(
    val coreLedger: Int,
    val featureLedger: Int,
    val domainLedger: Int,
    val unreadLedger: Int,
    val agentSeq: Long,
    val backgroundUnread: Int,
    val domainHandle: String,
    val unreadHandle: String,
    val lastDispatchDomainHandle: String,
    val lastDispatchUnreadHandle: String,
) {
    fun compact(): String =
        "c=$coreLedger,f=$featureLedger,d=$domainLedger,u=$unreadLedger," +
            "a=$agentSeq,b=$backgroundUnread,dh=$domainHandle,uh=$unreadHandle," +
            "xd=$lastDispatchDomainHandle,xu=$lastDispatchUnreadHandle"
}

private class ActualHostRun(
    val nonce: String,
    val blackholePort: Int,
) {
    private val lock = SynchronizedObject()
    private val identities = mutableListOf<Any>()
    private val authEvents = mutableListOf<String>()
    private val phaseHashes = mutableListOf<String>()
    private var sequence = 0
    private var providerFactoryCalls = 0
    private var integrationFactoryCalls = 0
    private var threadDataRequests = 0
    private var threadUiCallbacks = 0
    private var environment = Triple("", "", "")
    private var threadParameters = ""
    private val probeViolations = mutableListOf<String>()
    private var latestProbe = ""
    private var standaloneDisposeWitness = ""
    private var lastDispatchDomainHandle = ""
    private var lastDispatchUnreadHandle = ""
    private var canonicalRouter: RealtimeCanonicalFinalSinkLazyRouter? = null
    lateinit var binding: SlockSharedStoreFactoryBindingHandle
    var provider: SlockSharedStoreProvider? = null
        private set
    var dependencies: SlockSharedCanonicalFinalIntegrationFactoryScope? = null
        private set
    lateinit var integration: RealtimeCanonicalFinalSinkIntegrationSeam
        private set

    fun recordProviderFactoryCall() = synchronized(lock) { providerFactoryCalls += 1 }
    fun recordIntegrationFactoryCall(scope: SlockSharedCanonicalFinalIntegrationFactoryScope) =
        synchronized(lock) {
            integrationFactoryCalls += 1
            dependencies = scope
        }
    fun recordIntegration(value: RealtimeCanonicalFinalSinkIntegrationSeam) = synchronized(lock) { integration = value }
    fun recordProvider(value: SlockSharedStoreProvider) = synchronized(lock) { provider = value }
    fun providerFactoryCount(): Int = synchronized(lock) { providerFactoryCalls }
    fun integrationFactoryCount(): Int = synchronized(lock) { integrationFactoryCalls }
    fun recordThreadDataRequest() = synchronized(lock) { threadDataRequests += 1 }
    fun recordThreadUiCallback() = synchronized(lock) { threadUiCallbacks += 1 }

    fun recordStandaloneDisposeWitness(
        oldCoreRegistrationId: String,
        oldFeatureRegistrationId: String,
        currentCoreRegistrationId: String,
        currentFeatureRegistrationId: String,
    ) = synchronized(lock) {
        standaloneDisposeWitness =
            "oc=$oldCoreRegistrationId,of=$oldFeatureRegistrationId," +
                "cc=$currentCoreRegistrationId,cf=$currentFeatureRegistrationId"
    }

    fun clearStandaloneDisposeWitness() = synchronized(lock) {
        standaloneDisposeWitness = ""
    }

    fun recordEnvironment(api: String, socket: String, web: String) = synchronized(lock) {
        environment = Triple(api, socket, web)
    }

    fun recordAuthEvent(event: AuthSessionEvent) = synchronized(lock) {
        authEvents += "${event::class.simpleName}:${event.generation}"
    }

    fun authEventNames(): List<String> = synchronized(lock) { authEvents.toList() }

    fun expect(condition: Boolean, message: String) {
        if (!condition) synchronized(lock) { probeViolations += message }
    }

    fun requireNoProbeViolations() {
        val observed = synchronized(lock) { probeViolations.toList() }
        check(observed.isEmpty()) { "App delegate oracle: ${observed.joinToString(" | ")}" }
    }

    suspend fun dispatchCanonical(
        provider: SlockSharedStoreProvider,
        scope: SlockShellStoreScope,
        serverId: String,
        entry: RealtimeCanonicalFinalOwnerEntry,
        eventKey: String,
        event: build.raft.app.model.RealtimeEvent,
    ) {
        val authority =
            provider.realtimeAuthorityProvider.authorityFlow(scope.shellInstanceId).value
                ?: error("canonical probe authority missing for ${scope.shellInstanceId}")
        val grant = authority.grant(serverId) ?: error("canonical probe grant missing for $serverId")
        val captured = integration.captureForOwnerEntry(grant.scopeLeaseId, serverId, entry)
        check(captured.isNotEmpty()) { "canonical probe captured no surfaces for $entry@$serverId" }
        val router = integration.routerForPreparedEvent(captured)
        synchronized(lock) {
            canonicalRouter = router
            captured.firstOrNull { it.handle.slot.surfaceKey == RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain }
                ?.handle
                ?.let(::token)
                ?.let { handle -> lastDispatchDomainHandle = handle }
            captured.firstOrNull { it.handle.slot.surfaceKey == RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread }
                ?.handle
                ?.let(::token)
                ?.let { handle -> lastDispatchUnreadHandle = handle }
        }
        val accepted =
            router.accept(
                preflightEnabled = true,
                hasDecodedCanonical = true,
                grant = grant,
                commit = { CurrentGrantMessageShadowCommitOutcome.Committed },
                envelope = {
                    RealtimeCanonicalFinalSinkEnvelope(
                        grant = grant,
                        partition = grant.durablePartition(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID),
                        eventKey = eventKey,
                        event = event,
                        source = "actual_host_delegate_oracle",
                        capturedRegistrations = captured,
                    )
                },
                legacyDirect = {},
            )
        expect(accepted, "canonical probe was rejected for $entry@$serverId")
    }

    fun captureProbe(
        provider: SlockSharedStoreProvider,
        scope: SlockShellStoreScope,
        agentId: MemberId,
        homeStore: build.raft.app.data.SharedHomeStore,
    ): ActualHostProbeSnapshot {
        val authority = provider.realtimeAuthorityProvider.authorityFlow(scope.shellInstanceId).value
        val registrations = capturedRegistrations(authority)
        val sidecar = synchronized(lock) { canonicalRouter }?.debugSnapshot()?.sidecar
        fun ledger(serverId: String, surfaceKey: RealtimeCanonicalFinalSinkSurfaceKey): Int {
            val grant = authority?.grant(serverId) ?: return 0
            return sidecar?.deliveredLedgerSizes?.get(
                RealtimeCanonicalFinalSinkDeliveryLedgerKey(
                    partition = grant.durablePartition(SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID),
                    surfaceKey = surfaceKey,
                ),
            ) ?: 0
        }
        val domainHandle =
            registrations.firstOrNull { it.handle.slot.surfaceKey == RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain }
                ?.handle
        val unreadHandle =
            registrations.firstOrNull { it.handle.slot.surfaceKey == RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread }
                ?.handle
        val snapshot =
            synchronized(lock) {
                ActualHostProbeSnapshot(
                    coreLedger = ledger(ActualHostServerA, RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore("actual-host-thread-$nonce")),
                    featureLedger = ledger(ActualHostServerA, RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature("actual-host-thread-$nonce")),
                    domainLedger = ledger(ActualHostServerA, RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain),
                    unreadLedger = ledger(ActualHostServerB, RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread),
                    agentSeq = provider.agentDetailStore(scope).state.value.agentActivitySeq[agentId] ?: -1L,
                    backgroundUnread = homeStore.state.value.serverUnreadCounts[ActualHostServerB] ?: 0,
                    domainHandle = domainHandle?.let(::token).orEmpty(),
                    unreadHandle = unreadHandle?.let(::token).orEmpty(),
                    lastDispatchDomainHandle = lastDispatchDomainHandle,
                    lastDispatchUnreadHandle = lastDispatchUnreadHandle,
                )
            }
        synchronized(lock) { latestProbe = snapshot.compact() }
        return snapshot
    }

    fun registrationTrace(provider: SlockSharedStoreProvider, scopeKey: String): String =
        synchronized(lock) {
            registrations(provider.realtimeAuthorityProvider.authorityFlow(scopeKey).value)
        }

    fun currentRegistrationHandle(
        provider: SlockSharedStoreProvider,
        scope: SlockShellStoreScope,
        surfaceKey: RealtimeCanonicalFinalSinkSurfaceKey,
    ): build.raft.app.data.RealtimeCanonicalFinalSinkRegistrationHandle? =
        synchronized(lock) {
            capturedRegistrations(provider.realtimeAuthorityProvider.authorityFlow(scope.shellInstanceId).value)
                .singleOrNull { registration -> registration.handle.slot.surfaceKey == surfaceKey }
                ?.handle
        }

    fun registrationSurfaces(provider: SlockSharedStoreProvider, scopeKey: String): List<String> =
        synchronized(lock) {
            capturedRegistrations(provider.realtimeAuthorityProvider.authorityFlow(scopeKey).value)
                .map { registration ->
                    "${registration.surfaceLabel()}@${registration.handle.slot.serverId}"
                }
        }

    fun recordThreadParameters(
        provider: Any,
        authStore: Any,
        networkClient: Any,
        dataSource: Any,
        tasksDataStore: Any,
        composerDraftPersistSlice: Any,
        eventBus: Any,
    ) = synchronized(lock) {
        threadParameters =
            listOf(
                token(provider),
                token(authStore),
                token(networkClient),
                token(dataSource),
                token(tasksDataStore),
                token(composerDraftPersistSlice),
                token(eventBus),
            ).joinToString(",")
    }

    fun emitPhase(
        codec: CodecModule,
        provider: SlockSharedStoreProvider,
        component: String,
        phase: String,
        scopeKey: String,
    ) {
        val payload = phasePayload(provider, component, phase, scopeKey)
        emitPayload(codec, payload, terminal = false)
    }

    fun emitTerminal(
        codec: CodecModule,
        success: Boolean,
        error: String,
        provider: SlockSharedStoreProvider? = null,
        scopeKey: String? = null,
    ) {
        val terminalPayload =
            synchronized(lock) {
                buildCanonicalFinalTerminalPayload(
                    runNonce = nonce,
                    terminalSeq = sequence + 1,
                    phaseCount = phaseHashes.size,
                    lastSeq = sequence,
                    chainSha256 = codec.sha256(phaseHashes.joinToString("|")),
                    success = success,
                    error = error,
                    maxBytes = ActualHostMaxPayloadBytes,
                ) {
                    if (provider != null && scopeKey != null) {
                        registrations(provider.realtimeAuthorityProvider.authorityFlow(scopeKey).value)
                    } else {
                        ""
                    }
                }
            }
        emitPayload(codec, terminalPayload, terminal = true)
    }

    private fun phasePayload(
        provider: SlockSharedStoreProvider,
        component: String,
        phase: String,
        scopeKey: String,
    ): String = synchronized(lock) {
        val authority = provider.realtimeAuthorityProvider.authorityFlow(scopeKey).value
        val registrations = registrations(authority)
        val env = environment
        JSONObject().apply {
            put("runNonce", nonce)
            put("seq", sequence + 1)
            put("terminal", false)
            put("component", component)
            put("phase", phase)
            put("scope", scopeKey)
            put("bindingToken", token(binding))
            put("providerToken", token(provider))
            put("authStoreToken", token(provider.authStore))
            put("ownerToken", token(provider.realtimeDomainEventOwner))
            put("providerFactoryCount", providerFactoryCalls)
            put("integrationFactoryCount", integrationFactoryCalls)
            put("principal", authority?.witness?.principalId.orEmpty())
            put("lease", authority?.lease?.id.orEmpty())
            put("family", authority?.witness?.connectionFamilyId.orEmpty())
            put("authGeneration", authority?.witness?.authGeneration ?: -1L)
            put("grants", authority?.grantsByServer?.values?.sortedBy { it.serverId }?.joinToString(";") { grant ->
                "${grant.serverId},${grant.connectionFamilyId},${grant.connectionEpochId}"
            }.orEmpty())
            put("registrations", registrations)
            put("authEvents", authEvents.joinToString(";"))
            put("threadParameters", threadParameters)
            put("threadDataRequests", threadDataRequests)
            put("threadUiCallbacks", threadUiCallbacks)
            put("probe", latestProbe)
            if (standaloneDisposeWitness.isNotBlank()) {
                put("standaloneDisposeWitness", standaloneDisposeWitness)
            }
            put("apiUrl", env.first)
            put("socketUrl", env.second)
            put("webUrl", env.third)
        }.toString()
    }

    private fun registrations(authority: RealtimeScopeAuthority?): String {
        val captured = capturedRegistrations(authority)
        check(captured.size <= ActualHostMaxRegistrations) { "registration trace exceeded bound" }
        return captured.joinToString(";") { registration -> registration.traceValue() }
    }

    private fun capturedRegistrations(authority: RealtimeScopeAuthority?): List<RealtimeCanonicalFinalSinkCapturedRegistration> {
        val directory = dependencies?.registrationDirectory ?: return emptyList()
        return authority?.grantsByServer?.values
            ?.sortedBy { it.serverId }
            ?.flatMap { grant -> directory.capture(grant.scopeLeaseId, grant.serverId) }
            .orEmpty()
    }

    private fun RealtimeCanonicalFinalSinkCapturedRegistration.surfaceLabel(): String =
        when (val key = handle.slot.surfaceKey) {
            RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain -> "AppShellDomain"
            RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread -> "AppShellHomeUnread"
            is RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore -> "ThreadCore:${key.routeKey}"
            is RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature -> "ThreadFeature:${key.routeKey}"
        }

    private fun RealtimeCanonicalFinalSinkCapturedRegistration.traceValue(): String {
        val slot = handle.slot
        return "${surfaceLabel()},${slot.scopeLeaseId},${slot.serverId},${handle.registrationId},${token(sink)}"
    }

    private fun emitPayload(codec: CodecModule, payload: String, terminal: Boolean) {
        val bytes = payload.encodeToByteArray()
        check(bytes.size <= ActualHostMaxPayloadBytes) { "trace payload exceeded $ActualHostMaxPayloadBytes bytes" }
        val hash = codec.sha256(payload)
        synchronized(lock) {
            sequence += 1
            if (!terminal) phaseHashes += hash
        }
        slockLogInfo(ActualHostLogTag, "$ActualHostTraceMarker payload=$payload sha256=$hash")
    }

    private fun token(value: Any): String {
        val index = identities.indexOfFirst { existing -> existing === value }
        if (index >= 0) return "ref-${index + 1}"
        identities += value
        return "ref-${identities.size}"
    }
}

private fun actualHostProbeHomeDataSource(
    provider: SlockSharedStoreProvider,
    scope: SlockShellStoreScope,
): SharedHomeDataSource =
    SharedHomeDataSource(
        sessionStore = provider.authStore(scope),
        api =
            object : SlockHomeApi {
                override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "[]")

                override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "[]")

                override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "{}")

                override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
                    HomeNetworkResponse(code = 200, bodyText = "[]")
            },
        parseHome = { _, session ->
            DataResult.Content(
                HomeSnapshot(
                    server = ServerSummary(id = ServerId(ActualHostServerA), name = "Host A", slug = "host-a"),
                    currentMember =
                        MemberProfile(
                            id = MemberId(session.user.id),
                            displayName = session.user.bestDisplayName,
                            handle = "actual-host-user",
                            kind = MemberKind.Human,
                        ),
                    channels = emptyList(),
                    directMessages = emptyList(),
                    unreadTotal = 0,
                    serverUnreadCounts = mapOf(ActualHostServerA to 0, ActualHostServerB to 0),
                ),
            )
        },
    )

private fun actualHostAgentPresence(
    agentId: MemberId,
    serverSeq: Long,
): MemberPresenceUpdated =
    MemberPresenceUpdated(
        memberId = agentId,
        presence = Presence.Active,
        kind = MemberKind.Agent,
        agentStatus = AgentStatus.Working,
        statusLabel = "working",
        serverSeq = serverSeq,
    )

private fun actualHostBackgroundMessage(
    nonce: String,
    label: String,
    seq: Long,
): MessageNew =
    MessageNew(
        Message(
            id = MessageId("actual-host-$label-$nonce"),
            threadId = ThreadId("actual-host-background-thread"),
            channelId = ChannelId("actual-host-background-channel"),
            seq = seq,
            author =
                MemberProfile(
                    id = MemberId("actual-host-background-author"),
                    displayName = "Background Author",
                    handle = "background-author",
                    kind = MemberKind.Human,
                ),
            timestampLabel = "2026-07-15T23:00:00Z",
            body = "actual-host-background-$label",
        ),
    )

private fun actualHostSession(nonce: String): StoredAuthSession =
    StoredAuthSession(
        user =
            StoredAuthUser(
                id = "host-user-$nonce",
                email = "host-$nonce@example.invalid",
                name = "Actual Host",
            ),
        accessToken = "host-access-$nonce",
        refreshToken = "host-refresh-$nonce",
        selectedServerId = ActualHostServerA,
        selectedServerSlug = "host-a",
        servers =
            listOf(
                StoredAuthServer(id = ActualHostServerA, name = "Host A", slug = "host-a", role = "owner"),
                StoredAuthServer(id = ActualHostServerB, name = "Host B", slug = "host-b", role = "owner"),
            ),
        allowImplicitServerFallback = false,
    )

private fun actualHostEnvironmentRequest(nonce: String, port: Int): SharedSettingsApiEnvironmentRequest {
    val loopbackRoot = "https://127.0.0.1:$port"
    return SharedSettingsApiEnvironmentRequest(
        key = "actual-host-$nonce",
        label = "Actual host $nonce",
        apiBaseUrl = "$loopbackRoot/api/",
        socketBaseUrl = loopbackRoot,
        webUrl = "$loopbackRoot/web/",
    )
}

private suspend fun waitForAuthority(
    provider: SlockSharedStoreProvider,
    scopeKey: String,
    requireServers: Set<String>,
): RealtimeScopeAuthority {
    waitUntil("authority for $scopeKey") {
        val authority = provider.realtimeAuthorityProvider.authorityFlow(scopeKey).value
        authority != null && authority.grantsByServer.keys.containsAll(requireServers)
    }
    return requireNotNull(provider.realtimeAuthorityProvider.authorityFlow(scopeKey).value)
}

private suspend fun waitUntil(label: String, timeoutMs: Long = 15_000L, predicate: suspend () -> Boolean) {
    var elapsed = 0L
    while (!predicate()) {
        check(elapsed < timeoutMs) { "timeout waiting for $label" }
        delay(50)
        elapsed += 50
    }
}
