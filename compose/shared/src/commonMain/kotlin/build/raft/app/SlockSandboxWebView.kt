package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.app.compat.json.JSONObject

internal data class SlockWebViewNavigationState(
    val url: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0
) {
    companion object {
        fun decode(params: Any?): SlockWebViewNavigationState {
            val json = params as? JSONObject ?: JSONObject()
            return SlockWebViewNavigationState(
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

internal class SlockWebViewController {
    private data class CommandBinding(
        val owner: Any,
        val dispatch: (String) -> Unit
    )

    private var commandBinding: CommandBinding? = null
    private val pendingCommands = mutableListOf<String>()
    private var flushingCommands: Boolean = false

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

    private fun issueCommand(command: String) {
        val binding = commandBinding
        if (binding == null || flushingCommands) {
            pendingCommands += command
        } else {
            binding.dispatch(command)
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
        internal const val COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX = "evaluateJavaScript:"
    }
}

@Composable
internal fun rememberSlockWebViewController(): SlockWebViewController = remember { SlockWebViewController() }

@Composable
@NonRestartableComposable
internal fun SlockSandboxWebView(
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
    onUrlChange: (String) -> Unit = {},
    controller: SlockWebViewController,
    allowedNavigationSchemes: Set<String> = emptySet(),
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> }
) {
    // TODO(cmp-switch): TEMPORARY placeholder. The sandbox WebView was a native Kuikly view
    // (Android SlockSandboxWebViewRenderView, iOS SlockSandboxWebView.m); it must come back as an
    // expect/actual AndroidView/UIKitView with the same attrs, commands and events (see the
    // migration assessment §1.6 / §3.9). Until then nothing loads and no callback fires.
    Box(modifier.background(Color(0xFFF2F2F2)), contentAlignment = Alignment.Center) {
        BasicText("WebView: $url")
    }
}

/**
 * Retains the pre-controller trailing-lambda source contract: the final lambda is [onUrlChange].
 */
@Composable
@NonRestartableComposable
internal fun SlockSandboxWebView(
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
    SlockSandboxWebView(
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
        controller = rememberSlockWebViewController(),
        allowedNavigationSchemes = emptySet(),
        onNavigationStateChange = {}
    )
}

@Composable
@NonRestartableComposable
internal fun SlockWebView(
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
    onUrlChange: (String) -> Unit = {},
    controller: SlockWebViewController,
    allowedNavigationSchemes: Set<String> = emptySet(),
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> }
) {
    SlockSandboxWebView(
        url = url,
        modifier = modifier,
        allowScripts = allowScripts,
        allowNavigation = allowNavigation,
        domStorageEnabled = domStorageEnabled,
        cacheEnabled = cacheEnabled,
        ephemeral = ephemeral,
        injectedJavaScript = injectedJavaScript,
        hostEventsEnabled = hostEventsEnabled,
        onLoadCommit = onLoadCommit,
        onLoadSuccess = onLoadSuccess,
        onLoadFailure = onLoadFailure,
        onNavigationBlocked = onNavigationBlocked,
        onUrlChange = onUrlChange,
        controller = controller,
        allowedNavigationSchemes = allowedNavigationSchemes,
        onNavigationStateChange = onNavigationStateChange,
        onHostMessage = onHostMessage
    )
}

/**
 * Retains the pre-controller trailing-lambda source contract: the final lambda is [onUrlChange].
 */
@Composable
@NonRestartableComposable
internal fun SlockWebView(
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
    SlockWebView(
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
        controller = rememberSlockWebViewController(),
        allowedNavigationSchemes = emptySet(),
        onNavigationStateChange = {}
    )
}

private fun Any?.optStringValue(key: String, fallback: String): String =
    when (this) {
        is JSONObject -> this.optString(key, fallback)
        else -> fallback
    }

internal fun Set<String>.toSlockWebViewAllowedNavigationSchemesValue(): String =
    asSequence()
        .map { value -> value.trim().removeSuffix(":").lowercase() }
        .filter { value -> value.isNotEmpty() }
        .distinct()
        .sorted()
        .joinToString(",")
