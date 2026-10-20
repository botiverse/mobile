package build.raft.app.compat.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.SystemFont
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * The bundled TTF behind [name], loaded from its bytes.
 *
 * `SystemFont(name)` does not work for these faces: Skia resolves a SystemFont through
 * `FontMgr.legacyMakeTypeface(identity)`, which CoreText matches as a FAMILY name. The Kuikly
 * names are PostScript names ("HankenGrotesk-Regular", family "Hanken Grotesk"), so the match
 * missed and every Compose text silently fell back to the system face, whatever the Settings >
 * Appearance font. Reading the very TTF the app ships (UIAppFonts, bundle root, same base name as
 * the Android `res/font` resource) makes the face exact and independent of CoreText name matching.
 *
 * The identity stays [name] (used by the font cache and by tests). Unknown names fall back to
 * Space Grotesk Regular like Android; without any bundled file (unit tests) the old SystemFont
 * path remains.
 */
@OptIn(ExperimentalTextApi::class)
actual fun KuiklyFont(name: String, weight: FontWeight, style: FontStyle): Font {
    val path = raftBundledFontPath(raftFontResourceName(name)) ?: raftBundledFontPath(RaftFallbackFontResource)
    return if (path == null) {
        SystemFont(name, weight, style)
    } else {
        Font(identity = name, getData = { raftReadBundledFont(path) }, weight = weight, style = style)
    }
}

/**
 * Face used for a KuiklyFont name the bundle does not ship (Android falls back to the same face).
 * Native text views (RaftNativeSelectableText) use it too so both renderers agree.
 */
internal const val RaftIosFallbackFontName = "SpaceGrotesk-Regular"

private val RaftFallbackFontResource = raftFontResourceName(RaftIosFallbackFontName)

private fun raftBundledFontPath(resourceName: String): String? =
    NSBundle.mainBundle.pathForResource(resourceName, ofType = "ttf")

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private fun raftReadBundledFont(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path) ?: error("Raft bundled font unreadable: $path")
    val size = data.length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return out
}
