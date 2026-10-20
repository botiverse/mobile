package build.raft.app

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.platform.PlatformFont
import build.raft.app.compat.ui.raftFontResourceName

@OptIn(ExperimentalTextApi::class)
internal actual fun Font.raftTestBundledFontKey(): String? =
    (this as? PlatformFont)?.identity?.let(::raftFontResourceName)
