package ai.slock.android.shared

import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
    val currentOnEvent = rememberUpdatedState(onEvent)
    val instanceId = remember(pageName) { UUID.randomUUID().toString() }
    val pageDataText =
            remember(pageData.toString(), instanceId) {
                JSONObject(pageData.toString())
                        .put("instanceId", instanceId)
                        .toString()
            }

    SideEffect {
        SlockDiagnosticsLogger.d(
                "SlockKuikly",
                "island_compose pageName=$pageName instanceId=$instanceId pageDataBytes=${pageDataText.length}"
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
                            pageData = JSONObject(pageDataText)
                    )
                }
        LaunchedEffect(pageDataText) {
            host.updatePageData(pageDataText)
        }
        DisposableEffect(host) {
            onDispose { host.dispose() }
        }
        AndroidView(
                modifier = modifier,
                factory = { host }
        )
        return
    }

    key(pageName, pageDataText) {
        val host =
                remember(context, pageName, pageDataText) {
                    SlockKuiklyIslandHost(
                            context = context,
                            pageName = pageName,
                            pageData = JSONObject(pageDataText)
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
