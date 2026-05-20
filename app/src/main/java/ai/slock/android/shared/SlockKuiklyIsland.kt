package ai.slock.android.shared

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import java.util.UUID

@Composable
fun SlockKuiklyPageIsland(
        pageName: String,
        pageData: JSONObject,
        modifier: Modifier = Modifier,
        supportsPageDataUpdates: Boolean = false,
        onEvent: (JSONObject) -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val currentOnEvent = rememberUpdatedState(onEvent)
    val viewportKey =
            "${configuration.screenWidthDp}x${configuration.screenHeightDp}:${configuration.orientation}"
    val instanceId = remember(pageName, viewportKey) { UUID.randomUUID().toString() }
    val kuiklyPageData =
            remember(pageData.toString(), instanceId) {
                SlockKuiklyPageData.fromJson(pageData).withInstanceId(instanceId)
            }

    SideEffect {
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_compose pageName=$pageName instanceId=$instanceId pageDataBytes=${kuiklyPageData.text.length}"
        )
    }

    DisposableEffect(instanceId) {
        SlockKuiklyEventRegistry.register(instanceId) { event ->
            currentOnEvent.value(event)
        }
        onDispose {
            SlockKuiklyEventRegistry.unregister(instanceId)
        }
    }

    if (supportsPageDataUpdates) {
        val host =
                remember(context, pageName, instanceId) {
                    SlockKuiklyIslandHost(
                            context = context,
                            pageName = pageName,
                            pageData = kuiklyPageData
                    )
                }
        LaunchedEffect(kuiklyPageData.text) {
            host.updatePageData(kuiklyPageData.text)
        }
        DisposableEffect(host) {
            onDispose { host.dispose() }
        }
        AndroidView(
                modifier = modifier,
                factory = { host },
                update = { it.updatePageData(kuiklyPageData.text) }
        )
        return
    }

    key(pageName, kuiklyPageData.text) {
        val host =
                remember(context, pageName, kuiklyPageData.text) {
                    SlockKuiklyIslandHost(
                            context = context,
                            pageName = pageName,
                            pageData = kuiklyPageData
                    )
                }
        DisposableEffect(host) {
            onDispose { host.dispose() }
        }
        AndroidView(
                modifier = modifier,
                factory = { host }
        )
    }
}

internal object SlockKuiklyEventRegistry {
    private val handlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    fun register(instanceId: String, handler: (JSONObject) -> Unit) {
        SlockDiagnosticsLogger.d("SlockKuikly", "event_registry_register instanceId=$instanceId")
        handlers[instanceId] = handler
    }

    fun unregister(instanceId: String) {
        SlockDiagnosticsLogger.d("SlockKuikly", "event_registry_unregister instanceId=$instanceId")
        handlers.remove(instanceId)
    }

    fun dispatch(params: JSONObject) {
        val instanceId = params.optString("instanceId")
        val handler = handlers[instanceId]
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "event_registry_dispatch instanceId=$instanceId hasHandler=${handler != null} action=${params.optString("action")} attachmentId=${params.optString("attachmentId")} itemId=${params.optString("itemId")} valueBytes=${params.optString("value").length}"
        )
        handler?.invoke(params)
    }
}

internal class SlockKuiklyIslandHost(
        context: Context,
        private val pageName: String,
        private val pageData: SlockKuiklyPageData
) : FrameLayout(context), KuiklyRenderViewBaseDelegatorDelegate {
    private val kuiklyRenderViewDelegator = KuiklyRenderViewBaseDelegator(this)
    private var disposed = false
    private var lastPageDataText = pageData.text

    init {
        SlockKuiklyAdapters.init(context.applicationContext)
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_attach pageName=$pageName instanceId=${pageData.instanceId} payloadBytes=${pageData.payloadBytes}"
        )
        setBackgroundColor(
                if (slockKuiklyPageIsOpaque(pageName)) {
                    Color.WHITE
                } else {
                    Color.TRANSPARENT
                }
        )
        layoutParams =
                LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                )
        kuiklyRenderViewDelegator.onAttach(
                this,
                "",
                pageName,
                pageData.toKuiklyMap()
        )
    }

    fun updatePageData(pageDataText: String) {
        if (disposed || pageDataText == lastPageDataText) {
            return
        }
        lastPageDataText = pageDataText
        val nextPageData = SlockKuiklyPageData.fromText(pageDataText)
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_update pageName=$pageName instanceId=${nextPageData.instanceId} payloadBytes=${nextPageData.payloadBytes}"
        )
        kuiklyRenderViewDelegator.sendEvent(
                "slockPageData",
                nextPageData.toKuiklyMap()
        )
    }

    override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
        kuiklyRenderExport.moduleExport(SlockKuiklyBridgeModule.MODULE_NAME) {
            SlockKuiklyBridgeModule(
                    appContext = context.applicationContext,
                    legacyIslandEventsEnabled = true
            )
        }
    }

    override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
        kuiklyRenderExport.renderViewExport(
                SlockLucideIconRenderView.VIEW_NAME,
                renderViewExportCreator = { context -> SlockLucideIconRenderView(context) }
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kuiklyRenderViewDelegator.onResume()
    }

    override fun onDetachedFromWindow() {
        kuiklyRenderViewDelegator.onPause()
        super.onDetachedFromWindow()
    }

    fun dispose() {
        if (disposed) {
            return
        }
        disposed = true
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_dispose pageName=$pageName instanceId=${pageData.instanceId}"
        )
        kuiklyRenderViewDelegator.onDetach()
    }
}
