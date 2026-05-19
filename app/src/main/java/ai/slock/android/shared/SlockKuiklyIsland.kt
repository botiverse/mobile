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
import androidx.compose.ui.platform.LocalConfiguration
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
