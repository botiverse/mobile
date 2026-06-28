package ai.slock.compose

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

    override fun createAttr(): SlockSandboxWebViewAttr = SlockSandboxWebViewAttr()

    override fun createEvent(): SlockSandboxWebViewEvent = SlockSandboxWebViewEvent()

    override fun viewName(): String = VIEW_NAME

    fun applyWebViewAttrs(
        url: String,
        allowScripts: Boolean,
        force: Boolean = false
    ) {
        if (!force && appliedUrl == url && appliedAllowScripts == allowScripts) {
            return
        }
        appliedUrl = url
        appliedAllowScripts = allowScripts
        getViewAttr().run {
            src(url)
            allowScripts(allowScripts)
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

    companion object {
        const val EVENT_LOAD_SUCCESS = "loadSuccess"
        const val EVENT_LOAD_FAILURE = "loadFailure"
        const val EVENT_NAVIGATION_BLOCKED = "navigationBlocked"
    }
}

@Composable
@NonRestartableComposable
internal fun SlockSandboxWebView(
    url: String,
    modifier: Modifier = Modifier,
    allowScripts: Boolean = true,
    onLoadSuccess: () -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {}
) {
    val updateAttrs =
        remember(url, allowScripts) {
            { view: SlockSandboxWebViewNode ->
                view.applyWebViewAttrs(
                    url = url,
                    allowScripts = allowScripts
                )
            }
        }
    MakeKuiklyComposeNode<SlockSandboxWebViewNode>(
        factory = { SlockSandboxWebViewNode() },
        modifier = modifier,
        viewInit = {
            applyWebViewAttrs(url = url, allowScripts = allowScripts, force = true)
            event {
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
            }
        },
        viewUpdate = { view ->
            updateAttrs(view)
            view.event {
                loadSuccess(onLoadSuccess)
                loadFailure(onLoadFailure)
                navigationBlocked(onNavigationBlocked)
            }
        }
    )
}

private fun Any?.optStringValue(key: String, fallback: String): String =
    when (this) {
        is JSONObject -> this.optString(key, fallback)
        else -> fallback
    }
