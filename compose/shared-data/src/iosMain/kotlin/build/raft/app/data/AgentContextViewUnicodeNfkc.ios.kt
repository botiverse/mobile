package build.raft.app.data

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.*

@OptIn(BetaInteropApi::class)
internal actual fun normalizeUnicodeNfkc(text: String): String =
    NSString.create(string = text).precomposedStringWithCompatibilityMapping
