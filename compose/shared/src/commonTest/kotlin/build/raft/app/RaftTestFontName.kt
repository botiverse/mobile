package build.raft.app

import androidx.compose.ui.text.font.Font

/**
 * Normalized bundled-face key of a [Font] built by `KuiklyFont(name, ...)`: the resource / file
 * base name ("space_grotesk_semibold", see `raftFontResourceName`), or null when the font is not
 * a bundled face. Kuikly's `KuiklyFont.fontName` exposed the PostScript name directly; upstream
 * Android resolves bundled faces to `ResourceFont(resId)`, so the key is recovered per platform.
 */
internal expect fun Font.raftTestBundledFontKey(): String?
