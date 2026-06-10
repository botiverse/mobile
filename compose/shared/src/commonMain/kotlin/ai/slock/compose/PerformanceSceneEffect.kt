package ai.slock.compose

import ai.slock.compose.base.BridgeModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

@Composable
internal fun PerformanceSceneEffect(
    bridge: BridgeModule,
    sceneName: String
) {
    DisposableEffect(bridge, sceneName) {
        bridge.enterPerformanceScene(sceneName)
        onDispose {
            bridge.exitPerformanceScene(sceneName)
        }
    }
}
