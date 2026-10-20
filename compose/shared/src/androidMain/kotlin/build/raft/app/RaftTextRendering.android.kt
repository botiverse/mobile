package build.raft.app

import androidx.compose.ui.text.style.TextMotion

// Linear text with subpixel glyph positioning, no hinting: the advances the browser uses.
internal actual fun raftPlatformTextMotion(): TextMotion? = TextMotion.Animated
