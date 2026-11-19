package build.raft.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIScreen

/** A touch screen until a mouse or trackpad is used ([raftTrackPointerInput]). */
actual fun raftPlatformInitialPointerInput(): RaftPointerInput = RaftPointerInput.Touch

/** The screen's width in points (orientation aware), which Compose on iOS lays out 1:1 as dp. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun raftPlatformUnmeasuredWindowWidth(): Dp? =
    UIScreen.mainScreen.bounds.useContents { size.width }.takeIf { it > 0.0 }?.dp
