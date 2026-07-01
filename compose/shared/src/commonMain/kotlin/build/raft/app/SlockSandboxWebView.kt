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
            appliedInjectedJavaScript == injectedJavaScript
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
        getViewAttr().run {
            allowScripts(allowScripts)
            allowNavigation(allowNavigation)
            domStorageEnabled(domStorageEnabled)
            cacheEnabled(cacheEnabled)
            ephemeral(ephemeral)
            injectedJavaScript(injectedJavaScript)
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

    companion object {
        const val EVENT_LOAD_SUCCESS = "loadSuccess"
        const val EVENT_LOAD_FAILURE = "loadFailure"
        const val EVENT_NAVIGATION_BLOCKED = "navigationBlocked"
        const val EVENT_URL_CHANGE = "urlChange"
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
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    onUrlChange: (String) -> Unit = {}
) {
    val updateAttrs =
        remember(url, allowScripts, allowNavigation, domStorageEnabled, cacheEnabled, ephemeral, injectedJavaScript) {
            { view: SlockSandboxWebViewNode ->
                view.applyWebViewAttrs(
                    url = url,
                    allowScripts = allowScripts,
                    allowNavigation = allowNavigation,
                    domStorageEnabled = domStorageEnabled,
                    cacheEnabled = cacheEnabled,
                    ephemeral = ephemeral,
                    injectedJavaScript = injectedJavaScript
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
                force = true
            )
            event {
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
                urlChange(onUrlChange)
            }
        },
        viewUpdate = { view ->
            updateAttrs(view)
            view.event {
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
                urlChange(onUrlChange)
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
        cacheEnabled = cacheEnabled,
        ephemeral = ephemeral,
        injectedJavaScript = injectedJavaScript,
        onLoadSuccess = onLoadSuccess,
        onLoadFailure = onLoadFailure,
        onNavigationBlocked = onNavigationBlocked,
        onUrlChange = onUrlChange
    )
}

private fun Any?.optStringValue(key: String, fallback: String): String =
    when (this) {
        is JSONObject -> this.optString(key, fallback)
        else -> fallback
    }
