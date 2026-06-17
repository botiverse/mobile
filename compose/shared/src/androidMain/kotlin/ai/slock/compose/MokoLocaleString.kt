package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.desc.StringDesc

/**
 * Android actual for [StringDesc.localized]. Resolves the
 * `StringDesc` against the current Android `Context` so the
 * `MR.strings.<key>.toString(context)` Android-actual picks the
 * `values-zh-rCN/` folder when `StringDesc.localeType` is
 * `Custom("zh-CN")`.
 */
@Composable
actual fun StringDesc.localized(): String =
    toString(LocalContext.current)
