package build.raft.app.compat.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.toBitmap

/**
 * The raw [ImageBitmap] handed to `drawImage` callers (reaction sprite atlas, previews). The Raft
 * loader already disables hardware bitmaps (`raftPlatformImageLoaderDefaults`); this guards any
 * other loader/cached value, because drawing a `HARDWARE` bitmap into a software Canvas throws.
 */
internal actual fun coil3.Image.toRaftImageBitmap(): ImageBitmap = toBitmap().raftSoftwareBitmap().asImageBitmap()

internal fun Bitmap.raftSoftwareBitmap(): Bitmap =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false) ?: this
    } else {
        this
    }

internal actual val raftKeyboardHeightExcludesNavigationBars: Boolean = true
