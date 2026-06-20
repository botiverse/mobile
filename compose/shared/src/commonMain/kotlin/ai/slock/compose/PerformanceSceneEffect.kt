package ai.slock.compose

import ai.slock.compose.base.BridgeModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

@Composable
internal fun PerformanceSceneEffect(
    bridge: BridgeModule,
    sceneName: String,
    active: Boolean = true
) {
    DisposableEffect(bridge, sceneName, active) {
        if (active) {
            bridge.enterPerformanceScene(sceneName)
            onDispose {
                bridge.exitPerformanceScene(sceneName)
            }
        } else {
            onDispose {
            }
        }
    }
}
