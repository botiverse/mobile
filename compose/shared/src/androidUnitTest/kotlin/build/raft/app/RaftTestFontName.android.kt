package build.raft.app

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.ResourceFont
import build.raft.app.shared.R

internal actual fun Font.raftTestBundledFontKey(): String? {
    val resId = (this as? ResourceFont)?.resId ?: return null
    return R.font::class.java.fields.firstOrNull { field ->
        runCatching { field.getInt(null) == resId }.getOrDefault(false)
    }?.name
}
