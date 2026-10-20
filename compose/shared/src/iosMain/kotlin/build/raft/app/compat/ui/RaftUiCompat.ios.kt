package build.raft.app.compat.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.toBitmap

internal actual fun coil3.Image.toRaftImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()

internal actual val raftKeyboardHeightExcludesNavigationBars: Boolean = false
