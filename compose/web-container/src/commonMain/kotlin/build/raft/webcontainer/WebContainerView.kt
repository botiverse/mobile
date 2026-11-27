package build.raft.webcontainer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.resource.WebResourceInterceptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Imperative commands dispatched from the host app into an attached [WebContainerView]. */
sealed interface WebContainerCommand {
    data object GoBack : WebContainerCommand

    data object GoForward : WebContainerCommand

    data object Reload : WebContainerCommand

    data object Stop : WebContainerCommand

    /** Non-queued ephemeral navigation command (dropped if unbound to avoid ABA replay). */
    data class LoadUrl(val url: String) : WebContainerCommand

    /** Non-queued ephemeral runtime JavaScript evaluation (fenced to allowed trusted origin). */
    data class EvaluateJavaScript(val script: String) : WebContainerCommand

    /** Non-queued read-only DOM visibility probe. */
    data object ProbeContent : WebContainerCommand

    /** Non-queued host->web JSB event emission. */
    data class EmitJsbEvent(
        val name: String,
        val payload: JsonElement = JsonObject(emptyMap()),
    ) : WebContainerCommand
}

/**
 * Imperative handle bound to a [WebContainerView].
 *
 * Navigation-control commands (`goBack`, `goForward`, `reload`, `stop`) may queue briefly before
 * initial attachment, whereas ephemeral document commands (`loadUrl`, `evaluateJavaScript`,
 * `probeContent`, `emitJsbEvent`) drop immediately when unbound so they can never replay against a
 * different document later.
 */
class WebContainerController(
    private val maxCommandsPerDrain: Int = 16,
) {
    private var ownerToken: Any? = null
    private var dispatcher: ((WebContainerCommand) -> Unit)? = null
    private val pendingQueue = ArrayDeque<WebContainerCommand>()
    private var draining: Boolean = false

    val isBound: Boolean
        get() = dispatcher != null

    fun goBack() = send(WebContainerCommand.GoBack, queueIfUnbound = true)

    fun goForward() = send(WebContainerCommand.GoForward, queueIfUnbound = true)

    fun reload() = send(WebContainerCommand.Reload, queueIfUnbound = true)

    fun stop() = send(WebContainerCommand.Stop, queueIfUnbound = true)

    fun loadUrl(url: String): Boolean = send(WebContainerCommand.LoadUrl(url), queueIfUnbound = false)

    fun evaluateJavaScript(script: String): Boolean =
        send(WebContainerCommand.EvaluateJavaScript(script), queueIfUnbound = false)

    fun probeContent(): Boolean = send(WebContainerCommand.ProbeContent, queueIfUnbound = false)

    fun emitJsbEvent(
        name: String,
        payload: JsonElement = JsonObject(emptyMap()),
    ): Boolean = send(WebContainerCommand.EmitJsbEvent(name, payload), queueIfUnbound = false)

    internal fun bindDispatcher(
        owner: Any,
        dispatch: (WebContainerCommand) -> Unit,
    ) {
        ownerToken = owner
        dispatcher = dispatch
        drain()
    }

    internal fun unbindDispatcher(owner: Any) {
        if (ownerToken === owner) {
            ownerToken = null
            dispatcher = null
            pendingQueue.clear()
        }
    }

    private fun send(
        command: WebContainerCommand,
        queueIfUnbound: Boolean,
    ): Boolean {
        val currentDispatcher = dispatcher
        if (currentDispatcher == null) {
            if (queueIfUnbound && pendingQueue.size < maxCommandsPerDrain) {
                pendingQueue.addLast(command)
            }
            return false
        }
        if (draining) {
            if (pendingQueue.size < maxCommandsPerDrain) {
                pendingQueue.addLast(command)
            }
            return true
        }
        pendingQueue.addLast(command)
        drain()
        return true
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            var count = 0
            while (count < maxCommandsPerDrain && pendingQueue.isNotEmpty()) {
                val activeDispatcher = dispatcher ?: break
                val next = pendingQueue.removeFirst()
                count += 1
                activeDispatcher(next)
            }
            if (count >= maxCommandsPerDrain && pendingQueue.isNotEmpty()) {
                pendingQueue.clear()
            }
        } finally {
            draining = false
        }
    }
}

/** Remembers a [WebContainerController] across recompositions. */
@Composable
fun rememberWebContainerController(): WebContainerController = remember { WebContainerController() }

/** Event callbacks emitted by [WebContainerView]. */
data class WebContainerEvents(
    val onNavigationStateChange: (WebNavigationState) -> Unit = {},
    val onUrlChange: (String) -> Unit = {},
    val onLoadCommit: () -> Unit = {},
    val onLoadSuccess: () -> Unit = {},
    val onLoadFailure: (String) -> Unit = {},
    val onNavigationBlocked: (url: String, reason: String) -> Unit = { _, _ -> },
    /** Optional richer variant of [onNavigationBlocked] carrying whether a real user gesture initiated it. */
    val onNavigationBlockedWithGesture: ((url: String, reason: String, isUserGesture: Boolean) -> Unit)? = null,
    val onContentProbe: (Boolean?) -> Unit = {},
    val onContentProcessGone: (didCrash: Boolean) -> Unit = {},
    val onWatchdogTerminalError: (reason: String) -> Unit = {},
    val onDownloadRequested: ((WebDownloadResult) -> Unit)? = null,
    val onJsDialog: ((WebJsDialogRequest) -> Unit)? = null,
    val catchesMainFrameNavigation: ((WebMainFrameInterceptContext) -> Boolean)? = null,
    val routesMainFrameNavigation: ((String) -> Boolean)? = null,
    /** Optional richer variant of [routesMainFrameNavigation] carrying redirect and committed-document context. */
    val routesMainFrameNavigationWithContext: ((WebMainFrameRouteContext) -> Boolean)? = null,
)

/**
 * Pure-content cross-platform Hybrid WebView Container.
 *
 * Renders only the web viewport with zero bottom-sheet or toolbar chrome coupling so callers can
 * host it inside full-screen pages, right-push panes, or embedded cards identically.
 */
@Composable
fun WebContainerView(
    config: WebContainerConfig,
    modifier: Modifier = Modifier,
    events: WebContainerEvents = WebContainerEvents(),
    controller: WebContainerController = rememberWebContainerController(),
    jsbRegistry: JsbRegistry = JsbRegistry.Empty,
    resourceInterceptor: WebResourceInterceptor? = null,
    telemetrySink: WebContainerTelemetrySink? = null,
) {
    WebContainerPlatformHost(
        config = config,
        events = events,
        controller = controller,
        jsbRegistry = jsbRegistry,
        resourceInterceptor = resourceInterceptor,
        telemetrySink = telemetrySink,
        modifier = modifier,
    )
}

@Composable
internal expect fun WebContainerPlatformHost(
    config: WebContainerConfig,
    events: WebContainerEvents,
    controller: WebContainerController,
    jsbRegistry: JsbRegistry,
    resourceInterceptor: WebResourceInterceptor?,
    telemetrySink: WebContainerTelemetrySink?,
    modifier: Modifier,
)
