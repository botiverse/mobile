package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.theme.RaftTheme

/** Android never renders the Cupertino style at runtime; the design-system spinner stands in. */
@Composable
internal actual fun RaftCupertinoActivityIndicator(
    revealedTicks: Int,
    animating: Boolean,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (animating || revealedTicks > 0) {
            RaftSpinner(
                size = RaftSpinnerSize.Md,
                color = RaftTheme.colors.foregroundMuted,
                stillStep = if (animating) null else (revealedTicks - 1) * 4 / RaftActivityIndicatorTickCount,
            )
        }
    }
}

@Composable
internal actual fun rememberRaftCupertinoRefreshHaptic(): RaftCupertinoRefreshHaptic =
    remember { RaftNoCupertinoRefreshHaptic }

private object RaftNoCupertinoRefreshHaptic : RaftCupertinoRefreshHaptic {
    override fun prepare() = Unit

    override fun trigger() = Unit
}
