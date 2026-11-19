package build.raft.designsystem.theme

import android.content.res.Resources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A touch screen until a mouse or trackpad is used ([raftTrackPointerInput]). */
actual fun raftPlatformInitialPointerInput(): RaftPointerInput = RaftPointerInput.Touch

/** The configuration's screen width ([android.content.res.Configuration.screenWidthDp]). */
internal actual fun raftPlatformUnmeasuredWindowWidth(): Dp? =
    Resources.getSystem().configuration.screenWidthDp.takeIf { it > 0 }?.dp
