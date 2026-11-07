package build.raft.app

// iOS still exports the share image from HRBridgeModule's native screenshot of the live rows (see
// ThreadShareImageOffscreen.kt for what it needs before it can render off screen too).

internal actual val threadShareImageRendersOffscreen: Boolean = false
