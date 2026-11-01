package build.raft.designsystem.theme

import java.awt.GraphicsEnvironment

/**
 * The desktop app: a mouse or trackpad. A headless JVM (the designsystem's desktopTest sets
 * `java.awt.headless`) has no pointer, so tests keep the default scale unless they pass one.
 */
actual fun raftPlatformPointerIsFine(): Boolean = !GraphicsEnvironment.isHeadless()
