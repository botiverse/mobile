package build.raft.app.compat.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * TODO(cmp-switch): Kuikly `KuiklyFont(name, weight, style)` — a bundled face addressed by its
 * PostScript-style name ("SpaceGrotesk-Bold"). Non-composable on purpose: the Slock type tokens
 * build FontFamily objects outside composition.
 *
 * Android: the TTFs are :shared Android resources (res/font/space_grotesk_bold.ttf, ...).
 * iOS: the same TTFs ship in the app bundle (UIAppFonts); Compose loads the file's bytes
 * (Skia cannot resolve a PostScript name as a SystemFont family).
 * Unknown names fall back to the platform's Space Grotesk Regular / system face.
 */
expect fun KuiklyFont(name: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font

/** "SpaceGrotesk-SemiBold" -> "space_grotesk_semibold" (Android resource / file base name). */
internal fun raftFontResourceName(name: String): String =
    name.replace("SemiBold", "Semibold")
        .replace("-", "_")
        .replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .lowercase()
