package build.raft.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.platform.SystemFont
import build.raft.app.compat.ui.raftFontResourceName

internal actual fun Font.raftTestBundledFontKey(): String? =
    (this as? SystemFont)?.identity?.let(::raftFontResourceName)
