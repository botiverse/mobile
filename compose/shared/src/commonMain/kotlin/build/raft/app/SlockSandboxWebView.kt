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
    private var appliedCacheEnabled: Boolean? = null
    private var appliedEphemeral: Boolean? = null
    private var appliedInjectedJavaScript: String? = null
    private var appliedHostEventsEnabled: Boolean? = null

    override fun createAttr(): SlockSandboxWebViewAttr = SlockSandboxWebViewAttr()

    override fun createEvent(): SlockSandboxWebViewEvent = SlockSandboxWebViewEvent()

    override fun viewName(): String = VIEW_NAME

    fun applyWebViewAttrs(
        url: String,
        allowScripts: Boolean,
        allowNavigation: Boolean,
        domStorageEnabled: Boolean,
        cacheEnabled: Boolean,
        ephemeral: Boolean,
        injectedJavaScript: String,
        hostEventsEnabled: Boolean,
        force: Boolean = false
    ) {
        if (
            !force &&
            appliedUrl == url &&
            appliedAllowScripts == allowScripts &&
            appliedAllowNavigation == allowNavigation &&
            appliedDomStorageEnabled == domStorageEnabled &&
            appliedCacheEnabled == cacheEnabled &&
            appliedEphemeral == ephemeral &&
            appliedInjectedJavaScript == injectedJavaScript &&
            appliedHostEventsEnabled == hostEventsEnabled
        ) {
            return
        }
        appliedUrl = url
        appliedAllowScripts = allowScripts
        appliedAllowNavigation = allowNavigation
        appliedDomStorageEnabled = domStorageEnabled
        appliedCacheEnabled = cacheEnabled
        appliedEphemeral = ephemeral
        appliedInjectedJavaScript = injectedJavaScript
        appliedHostEventsEnabled = hostEventsEnabled
        getViewAttr().run {
            allowScripts(allowScripts)
            allowNavigation(allowNavigation)
            domStorageEnabled(domStorageEnabled)
            cacheEnabled(cacheEnabled)
            ephemeral(ephemeral)
            injectedJavaScript(injectedJavaScript)
            hostEventsEnabled(hostEventsEnabled)
            src(url)
        }
    }

    companion object {
        const val VIEW_NAME = "SlockSandboxWebView"
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

    fun domStorageEnabled(value: Boolean): SlockSandboxWebViewAttr {
        "domStorageEnabled" with value
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

    /**
     * Opt-in switch for the host↔web event bridge (design: `mobile/docs/host-web-event-bridge-2026-07-15.md`).
     * When true, the native renderer installs the bridge script at document-start on the
     * target document and registers a message listener bound to the WebView's captured
     * `trustedOrigin`. Default false — WebViews without a session-mutating consumer
     * (marketplace, billing, computers) do NOT expose the bridge and cannot receive
     * `session:*` events from the guest page. Each platform additionally probes its own
     * feature-support prerequisites (Android: `DOCUMENT_START_SCRIPT` AND
     * `WEB_MESSAGE_LISTENER`); if unsupported, this attr resolves to a no-op even when
     * set true (fail-closed against a "half-bridge").
     */
    fun hostEventsEnabled(value: Boolean): SlockSandboxWebViewAttr {
        "hostEventsEnabled" with value
        return this
    }
}

internal class SlockSandboxWebViewEvent : Event() {
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

    /**
     * Structured message from the embedded web guest to the host, delivered ONLY when
     * `hostEventsEnabled(true)` and the platform's feature-support probes both succeed.
     *
     * Wire contract (design r3+delta v2, MingQi APPROVE at 919c2cca):
     *
     *   native params = { "message": "<raw JSON string>" }
     *
     * The raw JSON must decode to `{ kind: string, payload: object }`. This handler
     * enforces the shared-layer checks (kind pattern + known vocabulary + payload
     * shape); anything failing them is dropped with a warn log, not delivered to the
     * caller. Native handlers enforce the channel-level controls (origin, main-frame,
     * lifecycle, ABA, UTF-8 byte cap) BEFORE the params ever reach this method.
     */
    fun hostMessage(handler: (kind: String, payload: JSONObject) -> Unit) {
        register(EVENT_HOST_MESSAGE) { params ->
            val raw = params.optStringValue("message", "")
            if (raw.isEmpty()) return@register
            val parsed = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return@register
            }
            val kind = parsed.optString("kind", "")
            if (!isKnownHostEventKind(kind)) return@register
            val payload = parsed.optJSONObject("payload") ?: return@register
            handler(kind, payload)
        }
    }

    companion object {
        const val EVENT_LOAD_SUCCESS = "loadSuccess"
        const val EVENT_LOAD_FAILURE = "loadFailure"
        const val EVENT_NAVIGATION_BLOCKED = "navigationBlocked"
        const val EVENT_URL_CHANGE = "urlChange"
        const val EVENT_HOST_MESSAGE = "hostMessage"

        /** Bridge protocol version. Web checks `window.RaftHost.version === RAFT_HOST_VERSION`. */
        const val RAFT_HOST_VERSION = "raft-host-v1"

        /**
         * Closed set of event kinds this host version understands. Unknown kinds are
         * dropped at the shared-layer parse boundary. Soft versioning: a v2 web app
         * emitting a new kind on a v1 host degrades to no delivery, not to silent
         * acceptance of an unrecognised event.
         */
        val KNOWN_HOST_EVENT_KINDS: Set<String> = setOf(
            "session:logout",
            "session:server-deleted",
            "session:server-left",
            "session:server-switched",
            "session:token-refresh-failed"
        )

        // Named per §Safety limits in the design: `^[a-z][a-z0-9]*(:[a-z][a-z0-9-]*)+$`,
        // length 4–64. This is the FLOOR — vocabulary check narrows further.
        private val HOST_EVENT_KIND_PATTERN = Regex("^[a-z][a-z0-9]*(:[a-z][a-z0-9-]*)+$")

        internal fun isKnownHostEventKind(kind: String): Boolean {
            if (kind.length !in 4..64) return false
            if (!HOST_EVENT_KIND_PATTERN.matches(kind)) return false
            return kind in KNOWN_HOST_EVENT_KINDS
        }
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
    cacheEnabled: Boolean = false,
    ephemeral: Boolean = true,
    injectedJavaScript: String = "",
    hostEventsEnabled: Boolean = false,
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> }
) {
    val updateAttrs =
        remember(url, allowScripts, allowNavigation, domStorageEnabled, cacheEnabled, ephemeral, injectedJavaScript, hostEventsEnabled) {
            { view: SlockSandboxWebViewNode ->
                view.applyWebViewAttrs(
                    url = url,
                    allowScripts = allowScripts,
                    allowNavigation = allowNavigation,
                    domStorageEnabled = domStorageEnabled,
                    cacheEnabled = cacheEnabled,
                    ephemeral = ephemeral,
                    injectedJavaScript = injectedJavaScript,
                    hostEventsEnabled = hostEventsEnabled
                )
            }
        }
    MakeKuiklyComposeNode<SlockSandboxWebViewNode>(
        factory = { SlockSandboxWebViewNode() },
        modifier = modifier,
        viewInit = {
            applyWebViewAttrs(
                url = url,
                allowScripts = allowScripts,
                allowNavigation = allowNavigation,
                domStorageEnabled = domStorageEnabled,
                cacheEnabled = cacheEnabled,
                ephemeral = ephemeral,
                injectedJavaScript = injectedJavaScript,
                hostEventsEnabled = hostEventsEnabled,
                force = true
            )
            event {
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
                urlChange(onUrlChange)
                hostMessage(onHostMessage)
            }
        },
        viewUpdate = { view ->
            updateAttrs(view)
            view.event {
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
                urlChange(onUrlChange)
                hostMessage(onHostMessage)
            }
        }
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
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    onUrlChange: (String) -> Unit = {},
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
        onLoadSuccess = onLoadSuccess,
        onLoadFailure = onLoadFailure,
        onNavigationBlocked = onNavigationBlocked,
        onUrlChange = onUrlChange,
        onHostMessage = onHostMessage
    )
}

private fun Any?.optStringValue(key: String, fallback: String): String =
    when (this) {
        is JSONObject -> this.optString(key, fallback)
        else -> fallback
    }
