package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberRaftPngFileWriter(): RaftPngFileWriter =
    remember {
        RaftPngFileWriter { image, namePrefix ->
            val skiaImage = Image.makeFromBitmap(image.asSkiaBitmap())
            val bytes =
                try {
                    skiaImage.encodeToData(EncodedImageFormat.PNG)?.bytes
                } finally {
                    skiaImage.close()
                }
            if (bytes == null || bytes.isEmpty()) return@RaftPngFileWriter null
            // NSTemporaryDirectory is one of the roots the file-preview retirement admits.
            val dir = NSTemporaryDirectory() + RAFT_SNAPSHOT_DIR
            NSFileManager.defaultManager.createDirectoryAtPath(
                dir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            val path = "$dir/$namePrefix${NSUUID().UUIDString}.png"
            val data =
                bytes.usePinned { pinned ->
                    NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                }
            if (data.writeToFile(path, atomically = true)) NSURL.fileURLWithPath(path).absoluteString else null
        }
    }

private const val RAFT_SNAPSHOT_DIR = "raft_snapshots"
