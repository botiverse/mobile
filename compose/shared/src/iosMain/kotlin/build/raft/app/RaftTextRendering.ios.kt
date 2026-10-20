package build.raft.app

import androidx.compose.ui.text.style.TextMotion

// iOS keeps the platform default; its text rendering is tuned separately.
internal actual fun raftPlatformTextMotion(): TextMotion? = null
