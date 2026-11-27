package build.raft.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import build.raft.app.data.MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.ServerFeatureFlagLastKnownStore
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.model.ServerId
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.ProcessDiagnosticFeatureFlagOverrides
import build.raft.app.thread.key
import build.raft.webcontainer.LocalAndroidWebFileChooser
import build.raft.webcontainer.WebContainerAndroidFileChooser
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.resource.WebResourceInterceptor
import build.raft.webcontainer.resource.WebResourceResponseData
import build.raft.webcontainer.resource.WebResourceSource

/**
 * Non-blocking snapshot check of `mobile_web_container_v1` for Android startup hooks
 * (such as `RaftAndroidWebViewWarmup` opting into off-main-thread `WebViewCompat.startUpWebView`).
 *
 * Resolution order (fails closed to `false` on any error):
 * 1. Entitled in-process diagnostic override (matching `rememberRaftWebContainerV1Enabled()`).
 * 2. Authoritative live or per-server last-known evaluation from [SharedServerFeatureFlags] when
 *    decided (neither pending nor undetermined — explicit per-server `false` is never overridden).
 * 3. Direct disk read of [ServerFeatureFlagLastKnownStore] when `RaftSharedStoreRegistry` or
 *    `accountBinding` have not initialized yet on the first CMP frame.
 */
fun isRaftAndroidWebContainerEnabledSnapshot(
    preferenceStoreProvider: () -> SharedPreferenceStore? = { runCatching { raftSharedPreferenceStore() }.getOrNull() },
): Boolean =
    runCatching {
        val provider = RaftSharedStoreRegistry.currentOrNull()
        val session = provider?.let { runCatching { it.authStore.readCommittedSessionSnapshot() }.getOrNull() }
        val serverId =
            session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
                ?: session?.servers?.firstOrNull()?.id?.trim().orEmpty()
        val environmentId =
            provider?.let { runCatching { it.apiEnvironmentStore.current().apiBaseUrl }.getOrNull() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val accountId = session?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
        resolveRaftAndroidWebContainerEnabledSnapshot(
            serverFeatureFlags = provider?.serverFeatureFlags,
            registry = ProcessDiagnosticFeatureFlagOverrides.registry,
            serverId = serverId,
            signedOut = provider != null && session == null,
            environmentId = environmentId,
            accountId = accountId,
            preferenceStoreProvider = preferenceStoreProvider,
        )
    }.getOrDefault(false)

internal fun resolveRaftAndroidWebContainerEnabledSnapshot(
    serverFeatureFlags: SharedServerFeatureFlags?,
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverId: String,
    signedOut: Boolean = false,
    environmentId: String? = null,
    accountId: String? = null,
    preferenceStoreProvider: () -> SharedPreferenceStore?,
): Boolean {
    val diagnostic = registry.snapshot(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY)
    if (diagnostic.entitled) {
        when (diagnostic.mode) {
            DiagnosticFeatureFlagOverrideMode.ForceOn -> return true
            DiagnosticFeatureFlagOverrideMode.ForceOff -> return false
            DiagnosticFeatureFlagOverrideMode.Server -> Unit
        }
    }
    if (signedOut) return false
    val normalizedServerId = serverId.trim()
    if (serverFeatureFlags != null && normalizedServerId.isNotEmpty()) {
        val sid = ServerId(normalizedServerId)
        val hasDecidedValue =
            serverFeatureFlags.snapshotFor(sid).resolved ||
                (!serverFeatureFlags.isDecisionPending(sid, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY) &&
                    !serverFeatureFlags.isDecisionUndetermined(sid, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY))
        if (hasDecidedValue) {
            return serverFeatureFlags.isEnabled(sid, MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY)
        }
    }
    val prefs = preferenceStoreProvider() ?: return false
    return ServerFeatureFlagLastKnownStore.readColdStartFlag(
        store = prefs,
        key = MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY,
        preferredServerId = normalizedServerId.ifEmpty { null },
        preferredEnvironmentId = environmentId,
        preferredAccountId = accountId,
    )
}

private fun unwrapWebContainerFileChooserHost(context: Context): RaftWebViewFileChooserHost? {
    var current: Context? = context
    while (current != null) {
        if (current is RaftWebViewFileChooserHost) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

private val RaftAndroidWebContainerFileChooser =
    WebContainerAndroidFileChooser { context, params, callback ->
        val host = unwrapWebContainerFileChooserHost(context)
        if (host == null) {
            callback.onReceiveValue(null)
            false
        } else {
            host.launchSandboxWebViewFileChooser(params, callback)
        }
    }

/**
 * Bridges [RaftAndroidWebViewHooks.mainDocumentProvider] (off-main-thread host document caches
 * such as release notes) into `:web-container`'s [WebResourceInterceptor] pipeline.
 */
internal val RaftAndroidMainDocumentResourceInterceptor =
    WebResourceInterceptor { request ->
        if (!request.isMainFrame || !request.method.equals("GET", ignoreCase = true)) return@WebResourceInterceptor null
        val provider = RaftAndroidWebViewHooks.mainDocumentProvider ?: return@WebResourceInterceptor null
        val document = runCatching { provider(request.url) }.getOrNull() ?: return@WebResourceInterceptor null
        WebResourceResponseData(
            mimeType = document.mimeType,
            encoding = "utf-8",
            statusCode = 200,
            reasonPhrase = "OK",
            body = document.bytes,
            source = WebResourceSource.HOST_CACHE,
        )
    }

private val WebContainerHandsSurfacePattern = Regex("^[A-Za-z0-9_-]{1,48}$")

internal fun formatWebContainerHandsSurface(
    surfaceId: String,
    parked: Boolean = false,
): String {
    val trimmed = surfaceId.trim()
    val clean =
        when {
            trimmed.isEmpty() -> "default"
            WebContainerHandsSurfacePattern.matches(trimmed) -> trimmed
            else -> "other"
        }
    return if (parked) "web_container:$clean:parked" else "web_container:$clean"
}

internal actual fun onRaftWebContainerPlatformTelemetry(event: WebContainerTelemetryEvent) {
    if (event is WebContainerTelemetryEvent.RenderProcessGone) {
        RaftWebViewRendererGone.recordSignal(
            surface = formatWebContainerHandsSurface(event.surfaceId, parked = event.parked),
            didCrash = event.didCrash,
            rendererPriorityAtExit = event.rendererPriorityAtExit ?: -1,
        )
    }
}

@Composable
internal actual fun rememberRaftWebContainerPlatformResourceInterceptor(): WebResourceInterceptor? =
    RaftAndroidMainDocumentResourceInterceptor

@Composable
internal actual fun RaftWebContainerPlatformScope(
    config: RaftWebViewConfig,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val effectiveKey =
        if (config.effectiveStoragePartition == RaftWebViewStoragePartition.IsolatedThirdParty) null else config.keepAliveKey
    DisposableEffect(config.url, config.diagnosticsSurface, effectiveKey) {
        RaftAndroidWebViewKeepAlive.abortInFlightPrewarmForForeground(
            foregroundSurface = config.diagnosticsSurface,
            foregroundKeepAliveKey = effectiveKey,
        )
        onDispose {}
    }
    if (LocalAndroidWebFileChooser.current != null) {
        content()
    } else {
        CompositionLocalProvider(LocalAndroidWebFileChooser provides RaftAndroidWebContainerFileChooser) {
            content()
        }
    }
}
