package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import build.raft.designsystem.components.raftPaneGridNoFade
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.compat.json.JSONObject
import build.raft.webcontainer.WebContainerAndroidInsetMode
import build.raft.webcontainer.WebJsDialogRequest

internal data class RaftWebViewNavigationState(
    val url: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    /**
     * Counts the main-frame loads this web view has started (Android `onPageStarted`, iOS
     * `didStartProvisionalNavigation`, desktop the not-loading to loading edge). A change means a
     * new load began, also when the last one never ended (a link or a redirect mid-load); the load
     * line starts again on it ([raftWebPageLoadState]).
     */
    val loadGeneration: Int = 0,
) {
    companion object {
        fun decode(params: Any?): RaftWebViewNavigationState {
            val json = params as? JSONObject ?: JSONObject()
            return RaftWebViewNavigationState(
                url = json.optString("url", ""),
                title = json.optString("title", ""),
                canGoBack = json.optBoolean("canGoBack", false),
                canGoForward = json.optBoolean("canGoForward", false),
                isLoading = json.optBoolean("isLoading", false),
                progress = json.optInt("progress", 0).coerceIn(0, 100)
            )
        }
    }
}

/**
 * Re-entrancy-safe dispatcher for [RaftWebViewNavigationState] notifications shared across
 * Android, iOS, and Desktop WebView hosts.
 *
 * When an `onNavigationStateChange` callback synchronously issues a controller command (such as
 * `controller.stop()` while deflecting a routed/disallowed URL) that triggers another notification
 * while the first callback is still on the stack, the re-entrant request is coalesced and drained
 * iteratively with a hard cap instead of recursing unboundedly into native `stopLoading()` JNI.
 */
internal class RaftWebViewNavigationStateRelay(
    private val maxReentrantRounds: Int = DEFAULT_MAX_REENTRANT_ROUNDS,
) {
    private var lastStateKey: String = ""
    private var notifying: Boolean = false
    private var pendingNotify: Boolean = false
    private var pendingForce: Boolean = false

    fun reset() {
        lastStateKey = ""
        pendingNotify = false
        pendingForce = false
    }

    fun notify(
        force: Boolean,
        isActive: () -> Boolean,
        readState: () -> RaftWebViewNavigationState,
        deliver: (RaftWebViewNavigationState) -> Unit,
    ) {
        if (!isActive()) return
        if (notifying) {
            pendingNotify = true
            if (force) pendingForce = true
            return
        }
        notifying = true
        try {
            var nextForce = force
            var rounds = 0
            while (isActive()) {
                pendingNotify = false
                pendingForce = false
                val state = readState()
                val stateKey = state.toString()
                if (nextForce || stateKey != lastStateKey) {
                    lastStateKey = stateKey
                    deliver(state)
                }
                if (!pendingNotify || !isActive()) break
                rounds += 1
                if (rounds >= maxReentrantRounds) break
                nextForce = pendingForce
            }
        } finally {
            notifying = false
        }
    }

    companion object {
        internal const val DEFAULT_MAX_REENTRANT_ROUNDS: Int = 4
    }
}

internal class RaftWebViewController {
    private data class CommandBinding(
        val owner: Any,
        val dispatch: (String) -> Unit
    )

    private var commandBinding: CommandBinding? = null
    private val pendingCommands = mutableListOf<String>()
    private var flushingCommands: Boolean = false
    private var dispatchingCommand: Boolean = false

    fun goBack() = issueCommand(COMMAND_GO_BACK)

    fun goForward() = issueCommand(COMMAND_GO_FORWARD)

    fun reload() = issueCommand(COMMAND_RELOAD)

    fun stop() = issueCommand(COMMAND_STOP)

    /**
     * Dispatches an ephemeral script only to the currently attached renderer. Runtime auth
     * updates must never queue across detach/recreate, otherwise an old page's token update can
     * be delivered to a replacement WebView instance (ABA).
     */
    fun evaluateJavaScript(script: String): Boolean {
        if (script.isBlank() || flushingCommands) return false
        val binding = commandBinding ?: return false
        binding.dispatch(COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX + script)
        return true
    }

    /**
     * Asks the attached renderer whether its top-level document shows anything
     * ([RaftWebViewContentProbeScript]); the answer comes as [RaftWebViewEvents.onContentProbe].
     * Never queued: a probe is about the document showing now. Returns whether it was sent.
     */
    fun probeDocumentContent(): Boolean {
        if (flushingCommands) return false
        val binding = commandBinding ?: return false
        binding.dispatch(COMMAND_PROBE_DOCUMENT_CONTENT)
        return true
    }

    /**
     * Loads [url] in the currently attached renderer (e.g. an OAuth answer or denial URL in the
     * in-app browser, #proj-workspace:#356 §5). Never queued across detach/recreate so a one-time
     * credential URL can never replay into a replacement WebView. Returns whether it was sent.
     */
    fun loadUrl(url: String): Boolean {
        val target = url.trim()
        if (target.isEmpty() || flushingCommands) return false
        val binding = commandBinding ?: return false
        binding.dispatch(COMMAND_LOAD_URL_PREFIX + target)
        return true
    }

    private fun issueCommand(command: String) {
        val binding = commandBinding
        if (binding == null || flushingCommands || dispatchingCommand) {
            // Coalesce duplicate consecutive stops queued during synchronous re-entry.
            if (command == COMMAND_STOP && pendingCommands.lastOrNull() == COMMAND_STOP) return
            pendingCommands += command
            return
        }
        dispatchingCommand = true
        try {
            binding.dispatch(command)
            var reentrantDrains = 0
            while (pendingCommands.isNotEmpty() && reentrantDrains < MAX_REENTRANT_COMMAND_DRAINS) {
                val activeBinding = commandBinding ?: return
                reentrantDrains += 1
                activeBinding.dispatch(pendingCommands.removeAt(0))
            }
            if (pendingCommands.isNotEmpty() && commandBinding != null) {
                pendingCommands.clear()
            }
        } finally {
            dispatchingCommand = false
        }
    }

    internal fun bindCommandDispatcher(owner: Any, dispatch: (String) -> Unit) {
        commandBinding = CommandBinding(owner = owner, dispatch = dispatch)
        flushPendingCommands()
    }

    internal fun unbindCommandDispatcher(owner: Any) {
        if (commandBinding?.owner === owner) {
            commandBinding = null
        }
    }

    private fun flushPendingCommands() {
        if (flushingCommands) return
        flushingCommands = true
        try {
            while (pendingCommands.isNotEmpty()) {
                val binding = commandBinding ?: return
                binding.dispatch(pendingCommands.removeAt(0))
            }
        } finally {
            flushingCommands = false
        }
    }

    companion object {
        internal const val COMMAND_GO_BACK = "goBack"
        internal const val COMMAND_GO_FORWARD = "goForward"
        internal const val COMMAND_RELOAD = "reload"
        internal const val COMMAND_STOP = "stop"
        internal const val COMMAND_PROBE_DOCUMENT_CONTENT = "probeDocumentContent"
        internal const val COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX = "evaluateJavaScript:"
        internal const val COMMAND_LOAD_URL_PREFIX = "loadUrl:"
        private const val MAX_REENTRANT_COMMAND_DRAINS = 8
    }
}

@Composable
internal fun rememberRaftWebViewController(): RaftWebViewController = remember { RaftWebViewController() }

@Composable
@NonRestartableComposable
internal fun RaftSandboxWebView(
    url: String,
    modifier: Modifier = Modifier,
    allowScripts: Boolean = true,
    allowNavigation: Boolean = false,
    domStorageEnabled: Boolean = false,
    /** task #188: let the WebView honour `width=` in the page's viewport meta (Android needs it). */
    useWideViewPort: Boolean = false,
    cacheEnabled: Boolean = false,
    ephemeral: Boolean = true,
    injectedJavaScript: String = "",
    hostEventsEnabled: Boolean = false,
    onLoadCommit: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    /** [RaftWebViewEvents.onNavigationBlockedWithGesture]. */
    onNavigationBlockedWithGesture: ((url: String, isUserGesture: Boolean) -> Unit)? = null,
    onUrlChange: (String) -> Unit = {},
    controller: RaftWebViewController,
    allowedNavigationSchemes: Set<String> = emptySet(),
    onNavigationStateChange: (RaftWebViewNavigationState) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> },
    mainFrameOrigin: String? = null,
    frameHosts: Set<String> = emptySet(),
    keepAliveKey: String? = null,
    /** Guards this WebView's load ([RaftWebViewLoadWatchdog]): wired to its events, timer and probe run here. */
    loadWatchdog: RaftWebViewLoadWatchdog? = null,
    /** A file the page saves (see [RaftWebViewEvents.onDownloadRequested]); null: as a blocked navigation. */
    onDownloadRequested: ((RaftWebViewDownload) -> Unit)? = null,
    /** A new top-level document routed as a link (see [RaftWebViewEvents.routesMainFrameNavigation]); null: loaded. */
    routesMainFrameNavigation: ((String) -> Boolean)? = null,
    /** Context-aware variant of [routesMainFrameNavigation] (see [RaftWebViewEvents.routesMainFrameNavigationWithContext]). */
    routesMainFrameNavigationWithContext: ((RaftWebViewMainFrameRouteAsk) -> Boolean)? = null,
    /** [RaftWebViewConfig.diagnosticsSurface]. */
    diagnosticsSurface: String = RAFT_WEBVIEW_SURFACE_DEFAULT,
    /** [RaftWebViewConfig.showsFormAccessoryBar]. */
    showsFormAccessoryBar: Boolean = false,
    /** [RaftWebViewConfig.allowPinchZoom]. */
    allowPinchZoom: Boolean = false,
    /** [RaftWebViewConfig.storagePartition]; null derives from [ephemeral]. */
    storagePartition: RaftWebViewStoragePartition? = null,
    /** [RaftWebViewConfig.allowsBackForwardNavigationGestures]. */
    allowsBackForwardNavigationGestures: Boolean = false,
    /** [RaftWebViewConfig.raftHost]. */
    raftHost: RaftHostHeader? = null,
    /** [RaftWebViewConfig.coverActive]. */
    coverActive: Boolean = false,
    /** [RaftWebViewEvents.catchesMainFrameNavigation]. */
    catchesMainFrameNavigation: ((RaftWebViewMainFrameCatchAsk) -> Boolean)? = null,
    /** [RaftWebViewEvents.onContentProcessGone]. */
    onContentProcessGone: ((String) -> Unit)? = null,
    /** [RaftWebViewConfig.allowJsDialogs]. */
    allowJsDialogs: Boolean = false,
    /** [RaftWebViewConfig.allowNewWindows]. */
    allowNewWindows: Boolean = false,
    /** [RaftWebViewEvents.onJsDialog]. */
    onJsDialog: ((WebJsDialogRequest) -> Unit)? = null,
    /** [RaftWebViewConfig.backgroundArgb]; null defaults to `RaftTheme.colors.layerCanvas`. */
    backgroundArgb: Int? = null,
    /** [RaftWebViewConfig.persistBackgroundAfterPaint]. */
    persistBackgroundAfterPaint: Boolean = false,
    /** [RaftWebViewConfig.androidInsetMode]. */
    androidInsetMode: WebContainerAndroidInsetMode = WebContainerAndroidInsetMode.HostHandled,
    /** [RaftWebViewConfig.audioCaptureAllowedOrigin]. */
    audioCaptureAllowedOrigin: String? = null,
) {
    val useWebContainer = rememberRaftWebContainerV1Enabled()
    val loadAttempt =
        remember(url, diagnosticsSurface, useWebContainer, keepAliveKey) {
            val prewarmHit = keepAliveKey?.let { key -> raftWebViewKeptDocument(key)?.loaded == true } == true
            MobileWebViewLoadTrace.startForegroundOpen(
                diagnosticsSurface = diagnosticsSurface,
                useWebContainer = useWebContainer,
                prewarmHit = prewarmHit,
            )
        }
    if (loadWatchdog != null) {
        val watchdogPhase = loadWatchdog.state.phase
        androidx.compose.runtime.LaunchedEffect(loadAttempt, watchdogPhase) {
            when (watchdogPhase) {
                RaftWebViewLoadPhase.Ready ->
                    loadAttempt.finish(
                        outcome = MobileWebViewLoadOutcome.Ok,
                        errorCode = MobileWebViewLoadErrorCode.None,
                        uiErrorShown = false,
                    )
                RaftWebViewLoadPhase.Stalled ->
                    loadAttempt.finish(
                        outcome = MobileWebViewLoadOutcome.Timeout,
                        errorCode = MobileWebViewLoadErrorCode.Timeout,
                        uiErrorShown = true,
                    )
                RaftWebViewLoadPhase.ProcessGone ->
                    loadAttempt.finish(
                        outcome = MobileWebViewLoadOutcome.Failed,
                        errorCode = MobileWebViewLoadErrorCode.RenderProcessGone,
                        uiErrorShown = true,
                    )
                RaftWebViewLoadPhase.Loading,
                RaftWebViewLoadPhase.Failed,
                -> Unit
            }
        }
    }
    val events = remember { RaftWebViewEvents() }
    events.catchesMainFrameNavigation = catchesMainFrameNavigation
    events.routesMainFrameNavigation = routesMainFrameNavigation
    events.routesMainFrameNavigationWithContext = routesMainFrameNavigationWithContext
    events.onLoadCommit = onLoadCommit
    events.onLoadSuccess = {
        loadWatchdog?.onLoadFinished()
        if (loadWatchdog == null) {
            loadAttempt.finish(
                outcome = MobileWebViewLoadOutcome.Ok,
                errorCode = MobileWebViewLoadErrorCode.None,
                uiErrorShown = false,
            )
        }
        onLoadSuccess()
    }
    events.onLoadFailure = { reason ->
        loadWatchdog?.onLoadFailed()
        val isTimeout = "timeout" in reason.lowercase() || "timed_out" in reason.lowercase() || "stalled" in reason.lowercase()
        val outcome = if (isTimeout) MobileWebViewLoadOutcome.Timeout else MobileWebViewLoadOutcome.Failed
        loadAttempt.finish(
            outcome = outcome,
            errorCode = classifyMobileWebViewErrorCode(reason, outcome),
            uiErrorShown = true,
        )
        onLoadFailure(reason)
    }
    events.onNavigationBlocked = onNavigationBlocked
    events.onNavigationBlockedWithGesture = onNavigationBlockedWithGesture
    events.onDownloadRequested = onDownloadRequested
    events.onJsDialog = onJsDialog
    events.onUrlChange = onUrlChange
    events.onNavigationStateChange =
        if (loadWatchdog == null) onNavigationStateChange else { state -> loadWatchdog.onNavigationState(state); onNavigationStateChange(state) }
    events.onHostMessage = onHostMessage
    events.onContentProbe = { hasContent -> loadWatchdog?.onContentProbe(hasContent) }
    events.onContentProcessGone = { reason ->
        loadWatchdog?.onProcessGone(reason)
        if (loadWatchdog == null) {
            loadAttempt.finish(
                outcome = MobileWebViewLoadOutcome.Failed,
                errorCode = MobileWebViewLoadErrorCode.RenderProcessGone,
                uiErrorShown = true,
            )
        }
        onContentProcessGone?.invoke(reason)
    }
    if (loadWatchdog != null) RaftWebViewLoadWatchdogEffect(loadWatchdog, controller)
    val resolvedBackgroundArgb = backgroundArgb ?: RaftTheme.colors.layerCanvas.toArgb()
    val allowedNavigationSchemesValue = allowedNavigationSchemes.toRaftWebViewAllowedNavigationSchemesValue()
    val config =
        remember(
            url,
            allowScripts,
            allowNavigation,
            domStorageEnabled,
            useWideViewPort,
            cacheEnabled,
            ephemeral,
            injectedJavaScript,
            allowedNavigationSchemesValue,
            hostEventsEnabled,
            mainFrameOrigin,
            frameHosts,
            keepAliveKey,
            resolvedBackgroundArgb,
            persistBackgroundAfterPaint,
            androidInsetMode,
            diagnosticsSurface,
            showsFormAccessoryBar,
            allowPinchZoom,
            storagePartition,
            allowsBackForwardNavigationGestures,
            raftHost,
            coverActive,
            allowJsDialogs,
            allowNewWindows,
            audioCaptureAllowedOrigin,
        ) {
            RaftWebViewConfig(
                url = url,
                allowScripts = allowScripts,
                allowNavigation = allowNavigation,
                domStorageEnabled = domStorageEnabled,
                useWideViewPort = useWideViewPort,
                cacheEnabled = cacheEnabled,
                ephemeral = ephemeral,
                injectedJavaScript = injectedJavaScript,
                allowedNavigationSchemes =
                    allowedNavigationSchemesValue.split(',').filter { it.isNotEmpty() }.toSet(),
                hostEventsEnabled = hostEventsEnabled,
                mainFrameOrigin = mainFrameOrigin,
                frameHosts = frameHosts,
                keepAliveKey = keepAliveKey,
                backgroundArgb = resolvedBackgroundArgb,
                persistBackgroundAfterPaint = persistBackgroundAfterPaint,
                androidInsetMode = androidInsetMode,
                diagnosticsSurface = diagnosticsSurface,
                showsFormAccessoryBar = showsFormAccessoryBar,
                allowPinchZoom = allowPinchZoom,
                storagePartition = storagePartition,
                allowsBackForwardNavigationGestures = allowsBackForwardNavigationGestures,
                raftHost = raftHost,
                coverActive = coverActive,
                allowJsDialogs = allowJsDialogs,
                allowNewWindows = allowNewWindows,
                audioCaptureAllowedOrigin = audioCaptureAllowedOrigin,
            )
        }
    // Full-bleed: in a clipped pane of the wide row (the Artifact column) the page is cut hard at the pane's edge, never
    // faded (raftPaneGridNoFade): a fade over a dark page glows. A native view (iOS WKWebView) is clipped as it is.
    if (useWebContainer) {
        RaftWebContainerAdaptedHost(
            config = config,
            events = events,
            controller = controller,
            modifier = modifier.raftPaneGridNoFade(),
        )
    } else {
        RaftSandboxWebViewHost(
            config = config,
            events = events,
            controller = controller,
            modifier = modifier.raftPaneGridNoFade(),
        )
    }
}

/**
 * Retains the pre-controller trailing-lambda source contract: the final lambda is [onUrlChange].
 */
@Composable
@NonRestartableComposable
internal fun RaftSandboxWebView(
    url: String,
    modifier: Modifier = Modifier,
    allowScripts: Boolean = true,
    allowNavigation: Boolean = false,
    domStorageEnabled: Boolean = false,
    /** task #188: let the WebView honour `width=` in the page's viewport meta (Android needs it). */
    useWideViewPort: Boolean = false,
    cacheEnabled: Boolean = false,
    ephemeral: Boolean = true,
    injectedJavaScript: String = "",
    onLoadCommit: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    onUrlChange: (String) -> Unit = {}
) {
    RaftSandboxWebView(
        url = url,
        modifier = modifier,
        allowScripts = allowScripts,
        allowNavigation = allowNavigation,
        domStorageEnabled = domStorageEnabled,
        useWideViewPort = useWideViewPort,
        cacheEnabled = cacheEnabled,
        ephemeral = ephemeral,
        injectedJavaScript = injectedJavaScript,
        onLoadCommit = onLoadCommit,
        onLoadSuccess = onLoadSuccess,
        onLoadFailure = onLoadFailure,
        onNavigationBlocked = onNavigationBlocked,
        onUrlChange = onUrlChange,
        controller = rememberRaftWebViewController(),
        allowedNavigationSchemes = emptySet(),
        onNavigationStateChange = {}
    )
}

@Composable
@NonRestartableComposable
internal fun RaftWebView(
    url: String,
    modifier: Modifier = Modifier,
    allowScripts: Boolean = true,
    allowNavigation: Boolean = true,
    domStorageEnabled: Boolean = true,
    cacheEnabled: Boolean = true,
    ephemeral: Boolean = false,
    injectedJavaScript: String = "",
    hostEventsEnabled: Boolean = false,
    onLoadCommit: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    /** [RaftWebViewEvents.onNavigationBlockedWithGesture]. */
    onNavigationBlockedWithGesture: ((url: String, isUserGesture: Boolean) -> Unit)? = null,
    onUrlChange: (String) -> Unit = {},
    controller: RaftWebViewController,
    allowedNavigationSchemes: Set<String> = emptySet(),
    /** [RaftWebViewConfig.mainFrameOrigin]. */
    mainFrameOrigin: String? = null,
    /** [RaftWebViewConfig.frameHosts]. */
    frameHosts: Set<String> = emptySet(),
    onNavigationStateChange: (RaftWebViewNavigationState) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> },
    loadWatchdog: RaftWebViewLoadWatchdog? = null,
    /** A new top-level document routed as a link (see [RaftWebViewEvents.routesMainFrameNavigation]); null: loaded. */
    routesMainFrameNavigation: ((String) -> Boolean)? = null,
    /** Context-aware variant of [routesMainFrameNavigation] (see [RaftWebViewEvents.routesMainFrameNavigationWithContext]). */
    routesMainFrameNavigationWithContext: ((RaftWebViewMainFrameRouteAsk) -> Boolean)? = null,
    /** [RaftWebViewConfig.diagnosticsSurface]. */
    diagnosticsSurface: String = RAFT_WEBVIEW_SURFACE_DEFAULT,
    /** [RaftWebViewConfig.showsFormAccessoryBar]. */
    showsFormAccessoryBar: Boolean = false,
    /** [RaftWebViewConfig.storagePartition]; null derives from [ephemeral]. */
    storagePartition: RaftWebViewStoragePartition? = null,
    /** [RaftWebViewConfig.allowsBackForwardNavigationGestures]. */
    allowsBackForwardNavigationGestures: Boolean = false,
    /** [RaftWebViewConfig.raftHost]. */
    raftHost: RaftHostHeader? = null,
    /** [RaftWebViewEvents.catchesMainFrameNavigation]. */
    catchesMainFrameNavigation: ((RaftWebViewMainFrameCatchAsk) -> Boolean)? = null,
    /** [RaftWebViewEvents.onContentProcessGone]. */
    onContentProcessGone: ((String) -> Unit)? = null,
    /** [RaftWebViewConfig.allowJsDialogs]. */
    allowJsDialogs: Boolean = false,
    /** [RaftWebViewConfig.allowNewWindows]. */
    allowNewWindows: Boolean = false,
    /** [RaftWebViewEvents.onJsDialog]. */
    onJsDialog: ((WebJsDialogRequest) -> Unit)? = null,
    /** [RaftWebViewConfig.backgroundArgb]; null defaults to `RaftTheme.colors.layerCanvas`. */
    backgroundArgb: Int? = null,
    /** [RaftWebViewConfig.persistBackgroundAfterPaint]. */
    persistBackgroundAfterPaint: Boolean = false,
    /** [RaftWebViewConfig.androidInsetMode]. */
    androidInsetMode: WebContainerAndroidInsetMode = WebContainerAndroidInsetMode.HostHandled,
    /** [RaftWebViewConfig.keepAliveKey]. */
    keepAliveKey: String? = null,
    /** [RaftWebViewConfig.audioCaptureAllowedOrigin]. */
    audioCaptureAllowedOrigin: String? = null,
) {
    RaftSandboxWebView(
        url = url,
        modifier = modifier,
        allowScripts = allowScripts,
        allowNavigation = allowNavigation,
        domStorageEnabled = domStorageEnabled,
        cacheEnabled = cacheEnabled,
        ephemeral = ephemeral,
        injectedJavaScript = injectedJavaScript,
        hostEventsEnabled = hostEventsEnabled,
        loadWatchdog = loadWatchdog,
        onLoadCommit = onLoadCommit,
        onLoadSuccess = onLoadSuccess,
        onLoadFailure = onLoadFailure,
        onNavigationBlocked = onNavigationBlocked,
        onNavigationBlockedWithGesture = onNavigationBlockedWithGesture,
        onUrlChange = onUrlChange,
        controller = controller,
        allowedNavigationSchemes = allowedNavigationSchemes,
        mainFrameOrigin = mainFrameOrigin,
        frameHosts = frameHosts,
        keepAliveKey = keepAliveKey,
        onNavigationStateChange = onNavigationStateChange,
        onHostMessage = onHostMessage,
        routesMainFrameNavigation = routesMainFrameNavigation,
        routesMainFrameNavigationWithContext = routesMainFrameNavigationWithContext,
        diagnosticsSurface = diagnosticsSurface,
        showsFormAccessoryBar = showsFormAccessoryBar,
        storagePartition = storagePartition,
        allowsBackForwardNavigationGestures = allowsBackForwardNavigationGestures,
        raftHost = raftHost,
        catchesMainFrameNavigation = catchesMainFrameNavigation,
        onContentProcessGone = onContentProcessGone,
        allowJsDialogs = allowJsDialogs,
        allowNewWindows = allowNewWindows,
        onJsDialog = onJsDialog,
        backgroundArgb = backgroundArgb,
        persistBackgroundAfterPaint = persistBackgroundAfterPaint,
        androidInsetMode = androidInsetMode,
        audioCaptureAllowedOrigin = audioCaptureAllowedOrigin,
    )
}

/**
 * Retains the pre-controller trailing-lambda source contract: the final lambda is [onUrlChange].
 */
@Composable
@NonRestartableComposable
internal fun RaftWebView(
    url: String,
    modifier: Modifier = Modifier,
    allowScripts: Boolean = true,
    allowNavigation: Boolean = true,
    domStorageEnabled: Boolean = true,
    cacheEnabled: Boolean = true,
    ephemeral: Boolean = false,
    injectedJavaScript: String = "",
    onLoadCommit: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    onUrlChange: (String) -> Unit = {}
) {
    RaftWebView(
        url = url,
        modifier = modifier,
        allowScripts = allowScripts,
        allowNavigation = allowNavigation,
        domStorageEnabled = domStorageEnabled,
        cacheEnabled = cacheEnabled,
        ephemeral = ephemeral,
        injectedJavaScript = injectedJavaScript,
        onLoadCommit = onLoadCommit,
        onLoadSuccess = onLoadSuccess,
        onLoadFailure = onLoadFailure,
        onNavigationBlocked = onNavigationBlocked,
        onUrlChange = onUrlChange,
        controller = rememberRaftWebViewController(),
        allowedNavigationSchemes = emptySet(),
        onNavigationStateChange = {}
    )
}

internal fun Set<String>.toRaftWebViewAllowedNavigationSchemesValue(): String =
    asSequence()
        .map { value -> value.trim().removeSuffix(":").lowercase() }
        .filter { value -> value.isNotEmpty() }
        .distinct()
        .sorted()
        .joinToString(",")
