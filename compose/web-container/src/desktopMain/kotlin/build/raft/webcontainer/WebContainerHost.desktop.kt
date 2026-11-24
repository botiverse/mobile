package build.raft.webcontainer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import build.raft.webcontainer.jsb.JsbRegistry
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.resource.WebResourceInterceptor
import kotlinx.coroutines.delay

private const val DESKTOP_WATCHDOG_TICK_MS = 500L

@Composable
internal actual fun WebContainerPlatformHost(
    config: WebContainerConfig,
    events: WebContainerEvents,
    controller: WebContainerController,
    jsbRegistry: JsbRegistry,
    resourceInterceptor: WebResourceInterceptor?,
    telemetrySink: WebContainerTelemetrySink?,
    modifier: Modifier,
) {
    val engine = LocalDesktopWebEngine.current
    if (engine == null) {
        LaunchedEffect(config.url) {
            events.onLoadFailure("desktop_engine_unavailable")
        }
        Box(modifier = modifier)
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val sessionKey =
        remember(
            config.url,
            config.isolationScope,
            config.allowScripts,
            config.navigationPolicy,
            config.jsbPolicy,
            config.documentStartScripts,
            config.offlineBundle,
        ) {
            listOf(
                config.url,
                config.isolationScope,
                config.allowScripts,
                config.navigationPolicy,
                config.jsbPolicy,
                config.documentStartScripts,
                config.offlineBundle,
            )
        }

    key(sessionKey, engine) {
        var pageReady by remember { mutableStateOf(false) }
        val host =
            remember {
                DesktopWebContainerHostController(
                    config = config,
                    engine = engine,
                    events = events,
                    jsbRegistry = jsbRegistry,
                    scope = coroutineScope,
                    telemetrySink = telemetrySink,
                    onReadyChanged = { pageReady = it },
                )
            }
        host.events = events
        host.updateRuntimeHooks(jsbRegistry, telemetrySink)
        val displayAllowed = !config.coverActive
        val showNative = pageReady && displayAllowed
        LaunchedEffect(host, displayAllowed) {
            host.setDisplayAllowed(displayAllowed)
        }

        val nativeComponent = host.component
        if (nativeComponent == null) {
            Box(modifier = modifier)
        } else {
            // Clip SwingPanel to a 1dp corner while covered or not yet ready so Skiko's native
            // interop cutout never punches a hole through Compose loading/sheet overlays while
            // still giving the native WebView full layout dimensions via requiredSize.
            BoxWithConstraints(modifier = modifier) {
                val fullWidth = maxWidth.coerceAtLeast(1.dp)
                val fullHeight = maxHeight.coerceAtLeast(1.dp)
                Box(
                    modifier =
                        if (showNative) {
                            Modifier.fillMaxSize().background(Color.White)
                        } else {
                            Modifier.align(Alignment.BottomEnd).size(1.dp).clipToBounds()
                        },
                ) {
                    SwingPanel(
                        factory = { nativeComponent },
                        modifier =
                            if (showNative) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.requiredSize(fullWidth, fullHeight)
                            },
                    )
                }
            }
        }

        DisposableEffect(controller) {
            host.bind(controller)
            onDispose { host.unbind(controller) }
        }
        DisposableEffect(host) {
            onDispose { host.dispose() }
        }
        LaunchedEffect(host) {
            host.start()
            if (nativeComponent != null) {
                while (true) {
                    delay(DESKTOP_WATCHDOG_TICK_MS)
                    host.tickWatchdog()
                }
            }
        }
    }
}
