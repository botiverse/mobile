package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun RaftWebContainerPlatformScope(
    config: RaftWebViewConfig,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    content()
}
