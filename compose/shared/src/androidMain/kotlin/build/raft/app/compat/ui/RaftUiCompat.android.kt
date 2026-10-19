package build.raft.app.compat.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.toBitmap

internal actual fun coil3.Image.toRaftImageBitmap(): ImageBitmap = toBitmap().asImageBitmap()
