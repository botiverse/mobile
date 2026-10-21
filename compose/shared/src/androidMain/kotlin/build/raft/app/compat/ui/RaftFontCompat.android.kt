package build.raft.app.compat.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import build.raft.app.shared.R

actual fun KuiklyFont(name: String, weight: FontWeight, style: FontStyle): Font =
    Font(raftFontResourceId(raftFontResourceName(name)), weight, style)

// Static R.font references, not reflection on R.font fields: R8 inlines and strips the R class
// fields and resource shrinking drops fonts that no code references, so a getField lookup
// silently fell back to Regular for every weight in minified release builds.
private fun raftFontResourceId(resourceName: String): Int =
    when (resourceName) {
        "space_grotesk_light" -> R.font.space_grotesk_light
        "space_grotesk_regular" -> R.font.space_grotesk_regular
        "space_grotesk_medium" -> R.font.space_grotesk_medium
        "space_grotesk_semibold" -> R.font.space_grotesk_semibold
        "space_grotesk_bold" -> R.font.space_grotesk_bold
        "space_mono_regular" -> R.font.space_mono_regular
        "space_mono_bold" -> R.font.space_mono_bold
        else -> R.font.space_grotesk_regular
    }
