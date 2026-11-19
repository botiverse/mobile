package build.raft.designsystem.theme

import java.awt.GraphicsEnvironment

/**
 * The desktop app: a mouse or trackpad. A headless JVM (the designsystem's desktopTest sets
 * `java.awt.headless`) has no pointer, so tests keep 44dp controls unless they pin one.
 */
actual fun raftPlatformInitialPointerInput(): RaftPointerInput =
    if (GraphicsEnvironment.isHeadless()) RaftPointerInput.Touch else RaftPointerInput.Fine

/** A desktop window always has a size before it composes; no guess. */
internal actual fun raftPlatformUnmeasuredWindowWidth(): androidx.compose.ui.unit.Dp? = null
