package build.raft.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID

@Composable
internal actual fun rememberRaftPngFileWriter(): RaftPngFileWriter {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        RaftPngFileWriter { image, namePrefix ->
            // Under cacheDir so the file-preview temporary-file retirement admits it.
            val dir = File(appContext.cacheDir, RAFT_SNAPSHOT_DIR).apply { mkdirs() }
            val file = File(dir, "$namePrefix${UUID.randomUUID()}.png")
            val bitmap = image.asAndroidBitmap()
            val ok = file.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            if (ok) {
                Uri.fromFile(file).toString()
            } else {
                file.delete()
                null
            }
        }
    }
}

private const val RAFT_SNAPSHOT_DIR = "raft_snapshots"
