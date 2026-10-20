package build.raft.app.compat.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import build.raft.app.shared.R

actual fun KuiklyFont(name: String, weight: FontWeight, style: FontStyle): Font {
    val resourceName = raftFontResourceName(name)
    val resId =
        runCatching { R.font::class.java.getField(resourceName).getInt(null) }.getOrNull()
            ?: R.font.space_grotesk_regular
    return Font(resId, weight, style)
}
