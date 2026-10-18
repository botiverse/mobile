package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal class SlockSandboxWebViewNode : DeclarativeBaseView<SlockSandboxWebViewAttr, SlockSandboxWebViewEvent>() {
    private var appliedUrl: String? = null
    private var appliedAllowScripts: Boolean? = null
    private var appliedAllowNavigation: Boolean? = null
    private var appliedDomStorageEnabled: Boolean? = null
    private var appliedUseWideViewPort: Boolean? = null
    private var appliedCacheEnabled: Boolean? = null
    private var appliedEphemeral: Boolean? = null
    private var appliedInjectedJavaScript: String? = null
    private var appliedAllowedNavigationSchemes: String? = null
    private var appliedHostEventsEnabled: Boolean? = null
    private var boundController: SlockWebViewController? = null

    override fun createAttr(): SlockSandboxWebViewAttr = SlockSandboxWebViewAttr()

    override fun createEvent(): SlockSandboxWebViewEvent = SlockSandboxWebViewEvent()

    override fun viewName(): String = VIEW_NAME

    fun applyWebViewAttrs(
        url: String,
        allowScripts: Boolean,
        allowNavigation: Boolean,
        domStorageEnabled: Boolean,
        useWideViewPort: Boolean = false,
        cacheEnabled: Boolean,
        ephemeral: Boolean,
        injectedJavaScript: String,
        allowedNavigationSchemes: String,
        hostEventsEnabled: Boolean = false,
        force: Boolean = false
    ) {
        if (
            !force &&
            appliedUrl == url &&
            appliedAllowScripts == allowScripts &&
            appliedAllowNavigation == allowNavigation &&
            appliedDomStorageEnabled == domStorageEnabled &&
            appliedUseWideViewPort == useWideViewPort &&
            appliedCacheEnabled == cacheEnabled &&
            appliedEphemeral == ephemeral &&
            appliedInjectedJavaScript == injectedJavaScript &&
            appliedAllowedNavigationSchemes == allowedNavigationSchemes &&
            appliedHostEventsEnabled == hostEventsEnabled
        ) {
            return
        }
        appliedUrl = url
        appliedAllowScripts = allowScripts
        appliedAllowNavigation = allowNavigation
        appliedDomStorageEnabled = domStorageEnabled
        appliedUseWideViewPort = useWideViewPort
        appliedCacheEnabled = cacheEnabled
        appliedEphemeral = ephemeral
        appliedInjectedJavaScript = injectedJavaScript
        appliedAllowedNavigationSchemes = allowedNavigationSchemes
        appliedHostEventsEnabled = hostEventsEnabled
        getViewAttr().run {
            allowScripts(allowScripts)
            allowNavigation(allowNavigation)
            domStorageEnabled(domStorageEnabled)
            useWideViewPort(useWideViewPort)
            cacheEnabled(cacheEnabled)
            ephemeral(ephemeral)
            injectedJavaScript(injectedJavaScript)
            allowedNavigationSchemes(allowedNavigationSchemes)
            hostEventsEnabled(hostEventsEnabled)
            src(url)
        }
    }

    fun bindController(controller: SlockWebViewController) {
        if (boundController === controller) return
        boundController?.unbindCommandDispatcher(this)
        boundController = controller
        controller.bindCommandDispatcher(this) { command ->
            callRenderViewMethod(METHOD_NAVIGATION_COMMAND, command)
        }
    }

    override fun didRemoveFromParentView() {
        boundController?.unbindCommandDispatcher(this)
        boundController = null
        super.didRemoveFromParentView()
    }

    companion object {
        const val VIEW_NAME = "SlockSandboxWebView"
        private const val METHOD_NAVIGATION_COMMAND = "navigationCommand"
    }
}

internal class SlockSandboxWebViewAttr : Attr() {
    fun src(value: String): SlockSandboxWebViewAttr {
        "src" with value
        return this
    }

    fun allowScripts(value: Boolean): SlockSandboxWebViewAttr {
        "allowScripts" with value
        return this
    }

    fun allowNavigation(value: Boolean): SlockSandboxWebViewAttr {
        "allowNavigation" with value
        return this
    }

    /**
     * task #188: lets the WebView honour `width=` in the page's viewport meta. Android ignores it
     * otherwise, so a desktop-width viewport injection has no effect there.
     */
    fun useWideViewPort(value: Boolean): SlockSandboxWebViewAttr {
        "useWideViewPort" with value
        return this
    }

    fun cacheEnabled(value: Boolean): SlockSandboxWebViewAttr {
        "cacheEnabled" with value
        return this
    }

    fun ephemeral(value: Boolean): SlockSandboxWebViewAttr {
        "ephemeral" with value
        return this
    }

    fun injectedJavaScript(value: String): SlockSandboxWebViewAttr {
        "injectedJavaScript" with value
        return this
    }

    fun allowedNavigationSchemes(value: String): SlockSandboxWebViewAttr {
        "allowedNavigationSchemes" with value
        return this
    }

    fun hostEventsEnabled(value: Boolean): SlockSandboxWebViewAttr {
        "hostEventsEnabled" with value
        return this
    }

}

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

internal class SlockSandboxWebViewEvent : Event() {
    fun loadCommit(handler: () -> Unit) {
        register(EVENT_LOAD_COMMIT) {
            handler()
        }
    }

    fun loadSuccess(handler: () -> Unit) {
        register(EVENT_LOAD_SUCCESS) {
            handler()
        }
    }

    fun loadFailure(handler: (String) -> Unit) {
        register(EVENT_LOAD_FAILURE) { params ->
            handler(params.optStringValue("reason", "unknown"))
        }
    }

    fun navigationBlocked(handler: (String) -> Unit) {
        register(EVENT_NAVIGATION_BLOCKED) { params ->
            handler(params.optStringValue("url", ""))
        }
    }

    fun urlChange(handler: (String) -> Unit) {
        register(EVENT_URL_CHANGE) { params ->
            handler(params.optStringValue("url", ""))
        }
    }

    fun navigationStateChange(handler: (SlockWebViewNavigationState) -> Unit) {
        register(EVENT_NAVIGATION_STATE_CHANGE) { params ->
            handler(SlockWebViewNavigationState.decode(params))
        }
    }

    fun hostMessage(handler: (String, JSONObject) -> Unit) {
        register(EVENT_HOST_MESSAGE) { params ->
            val raw = params.optStringValue("message", "")
            if (raw.isBlank()) return@register
            val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return@register
            val kind = parsed.optString("kind", "").trim()
            val payload = parsed.optJSONObject("payload") ?: return@register
            val acceptedKind = when (kind) {
                "ready", "close", "auth-required", "integration-unavailable" -> kind
                "feedback:ready" -> "ready"
                "feedback:close" -> "close"
                "feedback:auth-required" -> "auth-required"
                "feedback:integration-unavailable" -> "integration-unavailable"
                FeedbackDiagnosticsBridge.START_KIND -> FeedbackDiagnosticsBridge.START_KIND
                FeedbackDiagnosticsBridge.CANCEL_KIND -> FeedbackDiagnosticsBridge.CANCEL_KIND
                else -> null
            } ?: return@register
            handler(acceptedKind, payload)
        }
    }

    companion object {
        const val EVENT_LOAD_COMMIT = "loadCommit"
        const val EVENT_LOAD_SUCCESS = "loadSuccess"
        const val EVENT_LOAD_FAILURE = "loadFailure"
        const val EVENT_NAVIGATION_BLOCKED = "navigationBlocked"
        const val EVENT_URL_CHANGE = "urlChange"
        const val EVENT_NAVIGATION_STATE_CHANGE = "navigationStateChange"
        const val EVENT_HOST_MESSAGE = "hostMessage"
    }
}

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
    val allowedNavigationSchemesValue = allowedNavigationSchemes.toSlockWebViewAllowedNavigationSchemesValue()
    val updateAttrs =
        remember(
            url,
            allowScripts,
            allowNavigation,
            domStorageEnabled,
            cacheEnabled,
            ephemeral,
            injectedJavaScript,
            allowedNavigationSchemesValue,
            hostEventsEnabled
        ) {
            { view: SlockSandboxWebViewNode ->
                view.applyWebViewAttrs(
                    url = url,
                    allowScripts = allowScripts,
                    allowNavigation = allowNavigation,
                    domStorageEnabled = domStorageEnabled,
                    cacheEnabled = cacheEnabled,
                    ephemeral = ephemeral,
                    injectedJavaScript = injectedJavaScript,
                    allowedNavigationSchemes = allowedNavigationSchemesValue,
                    hostEventsEnabled = hostEventsEnabled
                )
            }
        }
    MakeKuiklyComposeNode<SlockSandboxWebViewNode>(
        factory = { SlockSandboxWebViewNode() },
        modifier = modifier,
        viewInit = {
            event {
                loadCommit(onLoadCommit)
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
                urlChange(onUrlChange)
                navigationStateChange(onNavigationStateChange)
                hostMessage(onHostMessage)
            }
            applyWebViewAttrs(
                url = url,
                allowScripts = allowScripts,
                allowNavigation = allowNavigation,
                domStorageEnabled = domStorageEnabled,
                cacheEnabled = cacheEnabled,
                ephemeral = ephemeral,
                injectedJavaScript = injectedJavaScript,
                allowedNavigationSchemes = allowedNavigationSchemesValue,
                hostEventsEnabled = hostEventsEnabled,
                force = true
            )
            bindController(controller)
        },
        viewUpdate = { view ->
            view.event {
                loadCommit(onLoadCommit)
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
                urlChange(onUrlChange)
                navigationStateChange(onNavigationStateChange)
                hostMessage(onHostMessage)
            }
            updateAttrs(view)
            view.bindController(controller)
        }
    )
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
