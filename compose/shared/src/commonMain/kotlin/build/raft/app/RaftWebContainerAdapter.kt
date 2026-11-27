package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import build.raft.app.data.MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.settings.LocalRaftSettingsWideDetailClose
import build.raft.app.thread.key
import build.raft.webcontainer.WEB_MEDIA_CAPTURE_DENIED_REASON
import build.raft.webcontainer.WebContainerConfig
import build.raft.webcontainer.WebContainerController
import build.raft.webcontainer.WebContainerEvents
import build.raft.webcontainer.WebContainerOrigin
import build.raft.webcontainer.WebContainerView
import build.raft.webcontainer.WebDocumentStartScript
import build.raft.webcontainer.WebDownloadResult
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.WebNavigationPolicy
import build.raft.webcontainer.WebNavigationStarter
import build.raft.webcontainer.WebNavigationState
import build.raft.webcontainer.WebOfflineBundleDescriptor
import build.raft.webcontainer.WebRequestHeaderRule
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbPolicy
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.jsb.JsbResult
import build.raft.webcontainer.jsb.JsbStandardCapabilities
import build.raft.webcontainer.jsb.JsbStandardEvents
import build.raft.webcontainer.jsb.JsbStandardHost
import build.raft.webcontainer.jsb.JsbThemeInfo
import build.raft.webcontainer.jsb.buildThemeChangedEventPayload
import build.raft.webcontainer.jsb.registerStandardCapabilities
import build.raft.webcontainer.parseWebContainerOrigin
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.rememberWebContainerController
import build.raft.webcontainer.resource.CompositeWebResourceInterceptor
import build.raft.webcontainer.resource.WebResourceInterceptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val RAFT_WEB_CONTAINER_LOG_TAG: String = "RaftWebContainer"
internal const val RAFT_WEB_CONTAINER_THIRD_PARTY_SCOPE_ID: String = "raft_third_party"
internal const val RAFT_WEB_CONTAINER_LEGACY_HOST_METHOD: String = "raft.host.emit"
internal const val RAFT_WEB_CONTAINER_LEGACY_HOST_CAPABILITY: String = "raft.host.legacy_emit"

private val FailClosedMalformedOriginLock = WebContainerOrigin(scheme = "https", host = "invalid.invalid")

/**
 * Optional explicit subtree override for routing `RaftSandboxWebView` / `RaftWebView` through the
 * standalone `compose/web-container` engine (`WebContainerView`).
 *
 * - `null` (default): resolves live from [MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY] +
 *   [ProcessDiagnosticFeatureFlagOverrides.registry], failing closed to `false` everywhere unless
 *   explicitly enabled by server rule or entitled local diagnostic override.
 * - `true` / `false`: forces the container path on or off for the subtree (tests / previews).
 */
internal val LocalRaftUseWebContainer = staticCompositionLocalOf<Boolean?> { null }

/**
 * Optional host implementation of `:web-container`'s standard JSB capability pack (`app.info`,
 * `ui.theme`, `ui.haptic`, `ui.clipboard`, `ui.toast`, `nav.openExternal`, `nav.close`,
 * `auth.status`). Granted strictly on trusted [RaftWebViewStoragePartition.Default] surfaces.
 */
internal val LocalRaftWebContainerJsbHost = staticCompositionLocalOf<JsbStandardHost?> { null }

/** Optional custom resource interceptor layered ahead of platform document caches. */
internal val LocalRaftWebContainerResourceInterceptor = staticCompositionLocalOf<WebResourceInterceptor?> { null }

/** Offline bundle descriptor + local asset interceptor pair supplied to a surface subtree. */
internal data class RaftWebContainerOfflineBundleSpec(
    val descriptor: WebOfflineBundleDescriptor,
    val interceptor: WebResourceInterceptor,
)

/** Optional offline bundle spec wired into [WebContainerConfig.offlineBundle] and resource interception. */
internal val LocalRaftWebContainerOfflineBundle = staticCompositionLocalOf<RaftWebContainerOfflineBundleSpec?> { null }

/**
 * Side-effect-free resolution of [MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY] safe to call during
 * Compose composition without mutating `MutableStateFlow` or recording diagnostic state.
 */
internal fun resolveMobileWebContainerV1EnabledPure(
    serverFeatureFlags: SharedServerFeatureFlags?,
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverId: String,
): Boolean {
    val normalizedServerId = serverId.trim()
    val serverEffective =
        if (serverFeatureFlags != null && normalizedServerId.isNotEmpty()) {
            serverFeatureFlags.isEnabled(ServerId(normalizedServerId), MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY)
        } else {
            false
        }
    val snapshot = registry.snapshot(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY)
    if (!snapshot.entitled) return serverEffective
    return when (snapshot.mode) {
        DiagnosticFeatureFlagOverrideMode.ForceOn -> true
        DiagnosticFeatureFlagOverrideMode.ForceOff -> false
        DiagnosticFeatureFlagOverrideMode.Server -> serverEffective
    }
}

/**
 * Resolves [MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY] against [serverFeatureFlags] and [registry],
 * recording consumer evidence via `registry.decide(...)` when a full server scope is present.
 * Call from an effect / non-composition boundary.
 */
internal fun resolveMobileWebContainerV1Enabled(
    serverFeatureFlags: SharedServerFeatureFlags?,
    registry: DiagnosticFeatureFlagOverrideRegistry,
    environmentId: String,
    accountId: String,
    serverId: String,
): Boolean {
    val normalizedServerId = serverId.trim()
    val serverEffective =
        if (serverFeatureFlags != null && normalizedServerId.isNotEmpty()) {
            serverFeatureFlags.isEnabled(ServerId(normalizedServerId), MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY)
        } else {
            false
        }
    val normalizedEnv = environmentId.trim().trimEnd('/')
    val normalizedAccount = accountId.trim()
    return if (normalizedEnv.isNotEmpty() && normalizedAccount.isNotEmpty() && normalizedServerId.isNotEmpty()) {
        registry.decide(
            key = MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY,
            serverEffective = serverEffective,
            scope =
                DiagnosticFeatureFlagScope(
                    environmentId = normalizedEnv,
                    accountId = normalizedAccount,
                    serverId = normalizedServerId,
                ),
        ).finalEffective
    } else {
        resolveMobileWebContainerV1EnabledPure(
            serverFeatureFlags = serverFeatureFlags,
            registry = registry,
            serverId = normalizedServerId,
        )
    }
}

@Composable
internal fun rememberRaftWebContainerV1Enabled(): Boolean {
    val override = LocalRaftUseWebContainer.current
    val provider = RaftSharedStoreRegistry.currentOrNull()
    val overrideRevision by
        ProcessDiagnosticFeatureFlagOverrides.registry
            .revisionFor(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY)
            .collectAsState()
    val fallbackRevisionFlow = remember { MutableStateFlow(0L).asStateFlow() }
    val serverRevisionFlow = provider?.serverFeatureFlags?.snapshotRevision ?: fallbackRevisionFlow
    val serverRevision by serverRevisionFlow.collectAsState()
    val session =
        if (provider != null) {
            runCatching { provider.authStore.readCommittedSessionSnapshot() }.getOrNull()
        } else {
            null
        }
    val accountId = session?.user?.id?.trim().orEmpty()
    val serverId =
        session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: session?.servers?.firstOrNull()?.id?.trim().orEmpty()
    val environmentId =
        if (provider != null) {
            runCatching { provider.apiEnvironmentStore.current().apiBaseUrl }.getOrDefault("")
        } else {
            ""
        }
    // Record consumer evidence off-composition only when diagnostic overrides are entitled, so
    // release builds with the flag OFF incur zero StateFlow mutation during composition.
    LaunchedEffect(override, provider, environmentId, accountId, serverId, overrideRevision, serverRevision) {
        if (override != null) return@LaunchedEffect
        val registry = ProcessDiagnosticFeatureFlagOverrides.registry
        if (!registry.snapshot(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY).entitled) return@LaunchedEffect
        if (environmentId.isNotBlank() && accountId.isNotBlank() && serverId.isNotBlank()) {
            resolveMobileWebContainerV1Enabled(
                serverFeatureFlags = provider?.serverFeatureFlags,
                registry = registry,
                environmentId = environmentId,
                accountId = accountId,
                serverId = serverId,
            )
        }
    }
    if (override != null) return override
    return remember(provider, serverId, overrideRevision, serverRevision) {
        resolveMobileWebContainerV1EnabledPure(
            serverFeatureFlags = provider?.serverFeatureFlags,
            registry = ProcessDiagnosticFeatureFlagOverrides.registry,
            serverId = serverId,
        )
    }
}

/**
 * Returns `true` only for fatal renderer circuit-breaker / process-gone-during-load watchdog
 * outcomes. Soft blank-screen / stall outcomes are logged via telemetry while leaving each
 * surface's opt-in [RaftWebViewLoadWatchdog] in charge of retry/error presentation.
 */
internal fun isFatalWebContainerWatchdogReason(reason: String): Boolean =
    reason.startsWith("render_process_")

/**
 * Maps legacy [RaftWebViewConfig] into a standalone [WebContainerConfig] without losing
 * isolation boundaries, navigation locks, origin-scoped headers, or legacy `window.RaftHost`
 * compatibility.
 */
internal fun RaftWebViewConfig.toWebContainerConfig(
    isDarkTheme: Boolean,
    standardJsbCapabilities: Set<String> = emptySet(),
    offlineBundle: WebOfflineBundleDescriptor? = null,
): WebContainerConfig {
    val isolationScope =
        when (effectiveStoragePartition) {
            RaftWebViewStoragePartition.Default -> WebIsolationScope.Default
            RaftWebViewStoragePartition.Ephemeral -> WebIsolationScope.Ephemeral
            RaftWebViewStoragePartition.IsolatedThirdParty ->
                WebIsolationScope.Named(RAFT_WEB_CONTAINER_THIRD_PARTY_SCOPE_ID)
        }
    val hasExplicitMainFrameOrigin = !mainFrameOrigin.isNullOrBlank()
    val parsedLockedOrigin = mainFrameOrigin?.let(::parseWebContainerOrigin)
    val malformedMainFrameOrigin = hasExplicitMainFrameOrigin && parsedLockedOrigin == null
    // Fail closed if caller specified a malformed non-blank mainFrameOrigin: lock to an
    // unmatchable origin, forbid navigation, and disable scripts/JSB.
    val effectiveLockedOrigin =
        if (malformedMainFrameOrigin) FailClosedMalformedOriginLock else parsedLockedOrigin
    val initialOrigin = parseWebContainerOrigin(url)
    val scriptOrigins: Set<WebContainerOrigin> =
        when {
            malformedMainFrameOrigin -> emptySet()
            parsedLockedOrigin != null && initialOrigin != null && parsedLockedOrigin != initialOrigin -> emptySet()
            parsedLockedOrigin != null -> setOf(parsedLockedOrigin)
            initialOrigin != null -> setOf(initialOrigin)
            else -> emptySet()
        }
    val headerRule =
        raftHost?.let { header ->
            parseWebContainerOrigin(header.origin)?.let { origin ->
                runCatching {
                    WebRequestHeaderRule(
                        headerName = RAFT_HOST_HEADER,
                        headerValue = header.value,
                        allowedOrigin = origin,
                        maxSameOriginReissues = RAFT_HOST_REISSUE_BUDGET,
                    )
                }.getOrNull()
            }
        }
    val enableLegacyHostJsb = hostEventsEnabled && allowScripts && scriptOrigins.isNotEmpty()
    // Standard capabilities are strictly limited to trusted Default-partition surfaces.
    val effectiveStandardCapabilities =
        if (
            allowScripts &&
            !malformedMainFrameOrigin &&
            scriptOrigins.isNotEmpty() &&
            effectiveStoragePartition == RaftWebViewStoragePartition.Default
        ) {
            standardJsbCapabilities
        } else {
            emptySet()
        }
    val grantedCapabilities =
        buildSet {
            if (enableLegacyHostJsb) add(RAFT_WEB_CONTAINER_LEGACY_HOST_CAPABILITY)
            addAll(effectiveStandardCapabilities)
        }
    val jsbPolicy =
        if (grantedCapabilities.isNotEmpty()) {
            JsbPolicy(
                enabled = true,
                allowedOrigins = scriptOrigins,
                grantedCapabilities = grantedCapabilities,
            )
        } else {
            JsbPolicy.Disabled
        }
    val scripts =
        buildList {
            if (enableLegacyHostJsb) {
                add(
                    WebDocumentStartScript(
                        id = "raft_legacy_host_shim",
                        source = RAFT_LEGACY_HOST_SHIM_SCRIPT,
                        allowedOrigins = scriptOrigins,
                        mainFrameOnly = true,
                    ),
                )
            }
            if (allowScripts && injectedJavaScript.isNotBlank() && scriptOrigins.isNotEmpty()) {
                add(
                    WebDocumentStartScript(
                        id = "raft_injected",
                        source = injectedJavaScript,
                        allowedOrigins = scriptOrigins,
                        mainFrameOnly = true,
                    ),
                )
            }
        }
    val effectiveKeepAliveKey =
        if (effectiveStoragePartition == RaftWebViewStoragePartition.IsolatedThirdParty) null else keepAliveKey
    val effectiveOfflineBundle =
        if (
            !malformedMainFrameOrigin &&
            scriptOrigins.isNotEmpty() &&
            effectiveStoragePartition == RaftWebViewStoragePartition.Default
        ) {
            offlineBundle
        } else {
            null
        }
    val effectiveAudioCaptureOrigin =
        if (
            !malformedMainFrameOrigin &&
            effectiveStoragePartition != RaftWebViewStoragePartition.IsolatedThirdParty &&
            parsedLockedOrigin != null &&
            !audioCaptureAllowedOrigin.isNullOrBlank()
        ) {
            parseWebContainerOrigin(audioCaptureAllowedOrigin)?.takeIf { it == parsedLockedOrigin }
        } else {
            null
        }
    return WebContainerConfig(
        url = url,
        surfaceId = diagnosticsSurface,
        isolationScope = isolationScope,
        allowScripts = allowScripts && !malformedMainFrameOrigin,
        domStorageEnabled = domStorageEnabled,
        cacheEnabled = cacheEnabled,
        allowPinchZoom = allowPinchZoom,
        desktopViewportMode = useWideViewPort,
        backgroundArgb = backgroundArgb,
        persistBackgroundAfterPaint = persistBackgroundAfterPaint,
        androidInsetMode = androidInsetMode,
        isDarkTheme = isDarkTheme,
        coverActive = coverActive,
        allowsBackForwardNavigationGestures = allowsBackForwardNavigationGestures,
        showsFormAccessoryBar = showsFormAccessoryBar,
        allowJsDialogs = allowJsDialogs,
        keepAliveKey = effectiveKeepAliveKey,
        navigationPolicy =
            WebNavigationPolicy(
                allowNavigation = allowNavigation && !malformedMainFrameOrigin,
                allowedSchemes = allowedNavigationSchemes,
                lockedMainFrameOrigin = effectiveLockedOrigin,
                allowedSubframeHosts = frameHosts,
                allowNewWindows = allowNewWindows && !malformedMainFrameOrigin,
                requestHeaderRule = headerRule,
                restoreBootstrapPathMarkers = setOf(RAFT_WEBVIEW_BOOTSTRAP_PATH),
                audioCaptureAllowedOrigin = effectiveAudioCaptureOrigin,
            ),
        jsbPolicy = jsbPolicy,
        documentStartScripts = scripts,
        offlineBundle = effectiveOfflineBundle,
        maxRuntimeJavaScriptLength = RaftWebViewMaxRuntimeJavaScriptLength,
    )
}

/**
 * Pure Kotlin JSB registry combining legacy `raft.host.emit` (when [hostEventsEnabled]) with
 * `:web-container`'s standard capability pack (when [standardHost] is supplied).
 */
internal fun buildRaftWebContainerJsbRegistry(
    hostEventsEnabled: Boolean,
    dispatchRawHostMessage: (String) -> Unit,
    standardHost: JsbStandardHost? = null,
): JsbRegistry {
    if (!hostEventsEnabled && standardHost == null) return JsbRegistry.Empty
    val registry = JsbRegistry()
    if (hostEventsEnabled) {
        registry.register(
            method = RAFT_WEB_CONTAINER_LEGACY_HOST_METHOD,
            requiredCapabilities = setOf(RAFT_WEB_CONTAINER_LEGACY_HOST_CAPABILITY),
        ) { _, params ->
            val kind = (params["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
            if (kind.isEmpty()) {
                return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "Missing non-empty 'kind'")
            }
            val payloadElement = params["payload"]
            val payloadObj =
                when (payloadElement) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> payloadElement
                    else -> return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "'payload' must be a JSON object")
                }
            val wire =
                buildJsonObject {
                    put("kind", kind)
                    put("payload", payloadObj)
                }.toString()
            if (raftDecodeWebViewHostMessage(wire) == null) {
                return@register JsbResult.Failure(JsbErrorCode.INVALID_PARAMS, "Unsupported host event kind '$kind'")
            }
            dispatchRawHostMessage(wire)
            JsbResult.Success(buildJsonObject { put("accepted", true) })
        }
    }
    if (standardHost != null) {
        registry.registerStandardCapabilities(standardHost)
    }
    return registry
}

/**
 * Pure Kotlin JSB registry translating legacy `window.RaftHost.emit(kind, payload)` calls arriving
 * via `window.raftBridge.invoke("raft.host.emit", ...)` into [RaftWebViewEvents.dispatchHostMessage]
 * (preserving the strict 5-kind allowlist in [raftDecodeWebViewHostMessage]).
 */
internal fun buildRaftLegacyHostJsbRegistry(
    hostEventsEnabled: Boolean,
    dispatchRawHostMessage: (String) -> Unit,
): JsbRegistry =
    buildRaftWebContainerJsbRegistry(
        hostEventsEnabled = hostEventsEnabled,
        dispatchRawHostMessage = dispatchRawHostMessage,
        standardHost = null,
    )

internal fun WebNavigationStarter.toRaftSignInStarter(): RaftSignInStarter =
    when (this) {
        WebNavigationStarter.Launch -> RaftSignInStarter.Launch
        WebNavigationStarter.Page,
        WebNavigationStarter.Link,
        WebNavigationStarter.Form,
        -> RaftSignInStarter.Page
        WebNavigationStarter.BackForward,
        WebNavigationStarter.Reload,
        WebNavigationStarter.Other,
        -> RaftSignInStarter.Other
    }

internal fun WebNavigationState.toRaftWebViewNavigationState(): RaftWebViewNavigationState =
    RaftWebViewNavigationState(
        url = url,
        title = title,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        isLoading = isLoading,
        progress = progress,
        loadGeneration = loadGeneration,
    )

internal fun WebDownloadResult.toRaftWebViewDownload(): RaftWebViewDownload =
    when (this) {
        is WebDownloadResult.Bytes -> RaftWebViewDownload.Bytes(data, fileName, mimeType)
        is WebDownloadResult.Url -> RaftWebViewDownload.Url(url, pageFileName, contentDisposition, mimeType)
        is WebDownloadResult.Failed -> RaftWebViewDownload.Failed(reason)
    }

/** Composes offline-bundle, custom, and platform document-cache interceptors in precedence order. */
internal fun buildRaftWebContainerResourceInterceptor(
    surfaceId: String,
    offlineBundleInterceptor: WebResourceInterceptor? = null,
    customInterceptor: WebResourceInterceptor? = null,
    platformInterceptor: WebResourceInterceptor? = null,
    telemetrySink: WebContainerTelemetrySink? = RaftWebContainerTelemetrySink,
): WebResourceInterceptor? {
    val chain = listOfNotNull(offlineBundleInterceptor, customInterceptor, platformInterceptor)
    if (chain.isEmpty()) return null
    return CompositeWebResourceInterceptor(
        interceptors = chain,
        surfaceId = surfaceId,
        telemetrySink = telemetrySink,
    )
}

/** Translates legacy [RaftWebViewController] string commands onto [WebContainerController]. */
internal fun dispatchRaftCommandToWebContainer(
    command: String,
    target: WebContainerController,
): Boolean {
    val trimmed = command.trim()
    return when {
        trimmed == RaftWebViewController.COMMAND_GO_BACK -> {
            target.goBack()
            true
        }
        trimmed == RaftWebViewController.COMMAND_GO_FORWARD -> {
            target.goForward()
            true
        }
        trimmed == RaftWebViewController.COMMAND_RELOAD -> {
            target.reload()
            true
        }
        trimmed == RaftWebViewController.COMMAND_STOP -> {
            target.stop()
            true
        }
        trimmed == RaftWebViewController.COMMAND_PROBE_DOCUMENT_CONTENT -> target.probeContent()
        command.startsWith(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX) -> {
            val script = command.removePrefix(RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX)
            if (script.isBlank()) false else target.evaluateJavaScript(script)
        }
        command.startsWith(RaftWebViewController.COMMAND_LOAD_URL_PREFIX) -> {
            val url = command.removePrefix(RaftWebViewController.COMMAND_LOAD_URL_PREFIX).trim()
            if (url.isEmpty()) false else target.loadUrl(url)
        }
        else -> false
    }
}

/** Tracks the latest committed document URL for [RaftWebViewMainFrameCatchAsk]. */
internal class RaftWebContainerCommittedStateTracker {
    var currentUrl: String = ""
        private set
    var committedPageUrl: String = ""
        private set
    var hasCommitted: Boolean = false
        private set

    fun onUrlObserved(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            currentUrl = trimmed
        }
    }

    fun onCommit() {
        hasCommitted = true
        if (currentUrl.isNotEmpty()) {
            committedPageUrl = currentUrl
        }
    }

    fun reset() {
        currentUrl = ""
        committedPageUrl = ""
        hasCommitted = false
    }

    fun buildCatchAsk(
        url: String,
        starter: WebNavigationStarter,
        priorHops: List<String>,
    ): RaftWebViewMainFrameCatchAsk =
        RaftWebViewMainFrameCatchAsk(
            url = url,
            starter = starter.toRaftSignInStarter(),
            page = committedPageUrl,
            committed = hasCommitted,
            hops = priorHops,
        )
}

/** Formats structured [WebContainerTelemetryEvent]s into `(isWarn, logLine)` without raw query tokens. */
internal fun formatWebContainerTelemetryLog(event: WebContainerTelemetryEvent): Pair<Boolean, String> =
    when (event) {
        is WebContainerTelemetryEvent.StartupCompleted ->
            false to
                buildString {
                    append("web_container_startup status=ready elapsedMs=${event.elapsedMs}")
                    if (!event.engine.isNullOrBlank()) append(" engine=${event.engine}")
                }
        is WebContainerTelemetryEvent.StartupFailed ->
            true to
                buildString {
                    append("web_container_startup status=failed reason=${event.reason}")
                    if (!event.engine.isNullOrBlank()) append(" engine=${event.engine}")
                }
        is WebContainerTelemetryEvent.NavigationBlocked ->
            false to "web_container_nav_blocked surface=${event.surfaceId} reason=${event.reason} url=${event.sanitizedUrl}"
        is WebContainerTelemetryEvent.RenderProcessGone ->
            true to
                buildString {
                    append(
                        "web_container_renderer_gone surface=${event.surfaceId} " +
                            "didCrash=${event.didCrash} recovered=${event.recovered} attempt=${event.attempt}",
                    )
                    if (event.parked) append(" parked=true")
                    event.rendererPriorityAtExit?.let {
                        append(" priority=${raftWebContainerRendererPriorityLabel(it)}")
                    }
                    if (!event.engine.isNullOrBlank()) append(" engine=${event.engine}")
                }
        is WebContainerTelemetryEvent.WatchdogTerminalError ->
            true to
                buildString {
                    append("web_container_watchdog_terminal surface=${event.surfaceId} reason=${event.reason}")
                    if (!event.engine.isNullOrBlank()) append(" engine=${event.engine}")
                }
        is WebContainerTelemetryEvent.JsbCallCompleted ->
            (!event.success) to "web_container_jsb surface=${event.surfaceId} method=${event.method} ok=${event.success} code=${event.errorCode ?: "ok"} durationMs=${event.durationMs}"
        is WebContainerTelemetryEvent.ResourceIntercepted ->
            false to "web_container_resource surface=${event.surfaceId} hit=${event.hit} source=${event.source ?: "network"} fallback=${event.fallbackReason ?: "none"} url=${event.sanitizedUrl}"
        is WebContainerTelemetryEvent.PoolEvent ->
            (event.action in TracedWebContainerPoolAnomalyActions) to
                buildString {
                    append("web_container_pool action=${event.action} size=${event.poolSizeAfter}")
                    if (!event.surfaceId.isNullOrBlank()) append(" surface=${event.surfaceId}")
                    if (event.reloaded != null) append(" reloaded=${event.reloaded}")
                    if (!event.engine.isNullOrBlank()) append(" engine=${event.engine}")
                }
        is WebContainerTelemetryEvent.IsolationScopeCleared ->
            false to "web_container_scope_cleared scope=${event.scope} evicted=${event.evictedPooledCount} result=${event.result}"
        // Every web page's request is denied by policy (WEB_MEDIA_CAPTURE_DENIED_REASON): expected, not a warning.
        is WebContainerTelemetryEvent.MediaCapturePermission ->
            (!event.grantedPrompt && event.reason != WEB_MEDIA_CAPTURE_DENIED_REASON) to
                buildString {
                    append("web_container_media_capture surface=${event.surfaceId}")
                    append(" decision=${if (event.grantedPrompt) "prompt" else "deny"}")
                    append(" kind=${event.kind}")
                    if (!event.reason.isNullOrBlank()) append(" reason=${event.reason}")
                    if (event.missingKeys.isNotEmpty()) append(" missingKeys=${event.missingKeys.joinToString(",")}")
                    append(" mainFrame=${event.isMainFrame} origin=${event.origin}")
                }
    }

/**
 * Platform-specific crash-monitoring hook for [WebContainerTelemetryEvent] (e.g. forwarding Android
 * renderer deaths into [RaftWebViewRendererGone] so Hands non-fatal tickets stay active under
 * `mobile_web_container_v1`).
 */
internal expect fun onRaftWebContainerPlatformTelemetry(event: WebContainerTelemetryEvent)

internal val RaftWebContainerTelemetrySink: WebContainerTelemetrySink =
    WebContainerTelemetrySink { event ->
        runCatching {
            val (isWarn, message) = formatWebContainerTelemetryLog(event)
            if (isWarn) {
                raftLogWarn(RAFT_WEB_CONTAINER_LOG_TAG, message)
            } else {
                raftLogInfo(RAFT_WEB_CONTAINER_LOG_TAG, message)
            }
        }
        runCatching { RaftWebContainerTrace.report(event) }
        runCatching { onRaftWebContainerPlatformTelemetry(event) }
    }

/** Exposes Raft's process-wide container telemetry sink to host-app entry points (e.g. Android warmup). */
fun raftWebContainerTelemetrySink(): WebContainerTelemetrySink = RaftWebContainerTelemetrySink

/**
 * Platform wrapper applied around [WebContainerView] inside `:shared` (Desktop supplies the
 * off-EDT `RaftDesktopWebContainerEngine` or the "Open in browser" fallback when scripts are
 * disabled / on Windows; Android and iOS pass through).
 */
@Composable
internal expect fun RaftWebContainerPlatformScope(
    config: RaftWebViewConfig,
    modifier: Modifier,
    content: @Composable () -> Unit,
)

@Composable
internal expect fun rememberRaftWebContainerPlatformResourceInterceptor(): WebResourceInterceptor?

@Composable
internal fun RaftWebContainerAdaptedHost(
    config: RaftWebViewConfig,
    events: RaftWebViewEvents,
    controller: RaftWebViewController,
    modifier: Modifier,
) {
    val isDarkTheme = RaftAppThemeState.isDark
    val baseStandardJsbHost = LocalRaftWebContainerJsbHost.current
    val wideDetailClose = LocalRaftSettingsWideDetailClose.current
    val hasWideDetailClose = wideDetailClose != null
    val latestWideDetailClose by rememberUpdatedState(wideDetailClose)
    val standardJsbHost =
        remember(baseStandardJsbHost, hasWideDetailClose) {
            when {
                baseStandardJsbHost == null -> null
                hasWideDetailClose -> baseStandardJsbHost.withCloseOverride { latestWideDetailClose?.invoke() }
                else -> baseStandardJsbHost
            }
        }
    val offlineBundleSpec = LocalRaftWebContainerOfflineBundle.current
    val customResourceInterceptor = LocalRaftWebContainerResourceInterceptor.current
    val platformResourceInterceptor = rememberRaftWebContainerPlatformResourceInterceptor()
    val standardJsbCapabilities =
        remember(standardJsbHost, config.diagnosticsSurface, config.effectiveStoragePartition) {
            if (standardJsbHost != null) {
                resolveRaftSurfaceStandardJsbCapabilities(
                    surfaceId = config.diagnosticsSurface,
                    partition = config.effectiveStoragePartition,
                )
            } else {
                emptySet()
            }
        }
    val containerConfig =
        remember(config, isDarkTheme, standardJsbCapabilities, offlineBundleSpec?.descriptor) {
            config.toWebContainerConfig(
                isDarkTheme = isDarkTheme,
                standardJsbCapabilities = standardJsbCapabilities,
                offlineBundle = offlineBundleSpec?.descriptor,
            )
        }
    val containerController = rememberWebContainerController()
    val committedTracker = remember(config.url, config.effectiveStoragePartition) { RaftWebContainerCommittedStateTracker() }
    val jsbRegistry =
        remember(config.hostEventsEnabled, events, standardJsbHost, standardJsbCapabilities) {
            buildRaftWebContainerJsbRegistry(
                hostEventsEnabled = config.hostEventsEnabled,
                dispatchRawHostMessage = { raw -> events.dispatchHostMessage(raw) },
                standardHost = if (standardJsbCapabilities.isNotEmpty()) standardJsbHost else null,
            )
        }
    val effectiveOfflineBundleInterceptor =
        if (containerConfig.offlineBundle != null) offlineBundleSpec?.interceptor else null
    val resourceInterceptor =
        remember(
            config.diagnosticsSurface,
            effectiveOfflineBundleInterceptor,
            customResourceInterceptor,
            platformResourceInterceptor,
        ) {
            buildRaftWebContainerResourceInterceptor(
                surfaceId = config.diagnosticsSurface,
                offlineBundleInterceptor = effectiveOfflineBundleInterceptor,
                customInterceptor = customResourceInterceptor,
                platformInterceptor = platformResourceInterceptor,
            )
        }
    DisposableEffect(controller, containerController) {
        val owner = Any()
        controller.bindCommandDispatcher(owner) { command ->
            dispatchRaftCommandToWebContainer(command, containerController)
        }
        onDispose { controller.unbindCommandDispatcher(owner) }
    }
    var lastEmittedDarkTheme by remember { mutableStateOf(isDarkTheme) }
    val themeJsbGranted =
        containerConfig.jsbPolicy.isEffectivelyEnabled &&
            JsbStandardCapabilities.UI_THEME in containerConfig.jsbPolicy.grantedCapabilities
    LaunchedEffect(isDarkTheme, themeJsbGranted) {
        if (lastEmittedDarkTheme != isDarkTheme) {
            lastEmittedDarkTheme = isDarkTheme
            if (themeJsbGranted) {
                containerController.emitJsbEvent(
                    JsbStandardEvents.THEME_CHANGED,
                    buildThemeChangedEventPayload(JsbThemeInfo(isDark = isDarkTheme)),
                )
            }
        }
    }
    val hasDownloadHandler = events.onDownloadRequested != null
    val hasJsDialogHandler = events.onJsDialog != null
    val hasCatchHandler = events.catchesMainFrameNavigation != null
    val hasRouteHandler = events.routesMainFrameNavigation != null
    val hasRouteWithContextHandler = events.routesMainFrameNavigationWithContext != null
    val containerEvents =
        remember(
            events,
            committedTracker,
            hasDownloadHandler,
            hasJsDialogHandler,
            hasCatchHandler,
            hasRouteHandler,
            hasRouteWithContextHandler,
        ) {
            WebContainerEvents(
                onNavigationStateChange = { state ->
                    if (state.url.isNotBlank()) committedTracker.onUrlObserved(state.url)
                    events.onNavigationStateChange(state.toRaftWebViewNavigationState())
                },
                onUrlChange = { url ->
                    committedTracker.onUrlObserved(url)
                    events.onUrlChange(url)
                },
                onLoadCommit = {
                    committedTracker.onCommit()
                    events.onLoadCommit()
                },
                onLoadSuccess = {
                    events.onLoadSuccess()
                },
                onLoadFailure = { reason ->
                    events.onLoadFailure(reason)
                },
                onNavigationBlocked = { url, _ ->
                    events.onNavigationBlocked(url)
                },
                onNavigationBlockedWithGesture = { url, _, isUserGesture ->
                    events.onNavigationBlockedWithGesture?.invoke(url, isUserGesture)
                },
                onContentProbe = { hasContent ->
                    events.onContentProbe(hasContent)
                },
                onContentProcessGone = { didCrash ->
                    committedTracker.reset()
                    events.onContentProcessGone(if (didCrash) "crash" else "terminated")
                },
                onWatchdogTerminalError = { reason ->
                    if (isFatalWebContainerWatchdogReason(reason)) {
                        events.onLoadFailure(reason)
                    }
                },
                onDownloadRequested =
                    if (hasDownloadHandler) {
                        { result -> events.onDownloadRequested?.invoke(result.toRaftWebViewDownload()) }
                    } else {
                        null
                    },
                onJsDialog =
                    if (hasJsDialogHandler) {
                        { request ->
                            val handler = events.onJsDialog
                            if (handler != null) {
                                handler(request)
                            } else {
                                request.cancel()
                            }
                        }
                    } else {
                        null
                    },
                catchesMainFrameNavigation =
                    if (hasCatchHandler) {
                        { ctx ->
                            val catchHandler = events.catchesMainFrameNavigation
                            if (catchHandler == null) {
                                false
                            } else {
                                catchHandler(
                                    committedTracker.buildCatchAsk(
                                        url = ctx.url,
                                        starter = ctx.starter,
                                        priorHops = ctx.priorHops,
                                    ),
                                )
                            }
                        }
                    } else {
                        null
                    },
                routesMainFrameNavigation =
                    if (hasRouteHandler) {
                        { candidateUrl -> events.routesMainFrameNavigation?.invoke(candidateUrl) == true }
                    } else {
                        null
                    },
                routesMainFrameNavigationWithContext =
                    if (hasRouteWithContextHandler) {
                        { ctx ->
                            events.routesMainFrameNavigationWithContext?.invoke(
                                RaftWebViewMainFrameRouteAsk(
                                    url = ctx.url,
                                    isRedirect = ctx.isRedirect,
                                    isUserGesture = ctx.isUserGesture,
                                    committedUrl = ctx.committedDocumentUrl,
                                ),
                            ) == true
                        }
                    } else {
                        null
                    },
            )
        }
    RaftWebContainerPlatformScope(config = config, modifier = modifier) {
        WebContainerView(
            config = containerConfig,
            modifier = modifier,
            events = containerEvents,
            controller = containerController,
            jsbRegistry = jsbRegistry,
            resourceInterceptor = resourceInterceptor,
            telemetrySink = RaftWebContainerTelemetrySink,
        )
    }
}

/**
 * Compatibility shim exposing legacy `window.RaftHost` (`raft-host-v1`) and `window.raftHostPort`
 * on top of `:web-container`'s pure Kotlin JSB runtime (`window.raftBridge`).
 */
internal const val RAFT_LEGACY_HOST_SHIM_SCRIPT: String =
    "(function(){'use strict';" +
        "if(window.RaftHost)return;" +
        "function forward(kind,payload){" +
        "if(typeof kind!=='string'||!kind)return;" +
        "var b=window.raftBridge;if(!b||typeof b.invoke!=='function')return;" +
        "var p=(payload&&typeof payload==='object'&&!Array.isArray(payload))?payload:{};" +
        "try{b.invoke('$RAFT_WEB_CONTAINER_LEGACY_HOST_METHOD',{kind:kind,payload:p}).catch(function(){});}catch(_){}" +
        "}" +
        "if(!window.$RaftWebViewHostPortName){" +
        "Object.defineProperty(window,'$RaftWebViewHostPortName',{value:Object.freeze({postMessage:function(raw){" +
        "if(typeof raw!=='string')return;try{var m=JSON.parse(raw);if(m&&typeof m.kind==='string'&&m.payload&&typeof m.payload==='object'&&!Array.isArray(m.payload))forward(m.kind,m.payload);}catch(_){}" +
        "}}),writable:false,configurable:false});" +
        "}" +
        "Object.defineProperty(window,'RaftHost',{value:Object.freeze({version:'raft-host-v1',capabilities:Object.freeze(['raft-feedback-v1']),emit:forward}),writable:false,configurable:false});" +
        "})();"
