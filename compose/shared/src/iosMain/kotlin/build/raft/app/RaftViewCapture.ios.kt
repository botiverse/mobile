package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberRaftPngFileWriter(): RaftPngFileWriter =
    remember {
        RaftPngFileWriter { image, namePrefix ->
            // NSTemporaryDirectory is one of the roots the file-preview retirement admits.
            val dir = NSTemporaryDirectory().trimEnd('/') + "/" + RAFT_SNAPSHOT_DIR
            writeRaftIosPngToDirectory(image = image, namePrefix = namePrefix, dir = dir)
        }
    }

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun writeRaftIosPngToDirectory(
    image: ImageBitmap,
    namePrefix: String,
    dir: String,
    nowEpochSeconds: Double = NSDate().timeIntervalSince1970,
    fileManager: NSFileManager = NSFileManager.defaultManager,
): String? {
    val skiaImage = Image.makeFromBitmap(image.asSkiaBitmap())
    val bytes =
        try {
            val encoded = skiaImage.encodeToData(EncodedImageFormat.PNG)
            if (encoded != null) {
                try {
                    encoded.bytes
                } finally {
                    encoded.close()
                }
            } else {
                null
            }
        } finally {
            skiaImage.close()
        }
    if (bytes == null || bytes.isEmpty()) return null
    fileManager.createDirectoryAtPath(
        dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    if (namePrefix == ThreadShareImageFilePrefix) {
        pruneExpiredRaftIosShareImages(
            dir = dir,
            nowEpochSeconds = nowEpochSeconds,
            fileManager = fileManager,
        )
    }
    val path = "$dir/$namePrefix${NSUUID().UUIDString}.png"
    val data =
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    return if (data.writeToFile(path, atomically = true)) NSURL.fileURLWithPath(path).absoluteString else null
}

/**
 * Prunes stale share-image PNGs (`raft-message-share-*.png`) older than [maxAgeSeconds] from
 * [dir] without touching composer snapshot drafts (`raft_snapshot_*.png`), which follow the
 * composer temporary-file retirement lifecycle.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun pruneExpiredRaftIosShareImages(
    dir: String,
    nowEpochSeconds: Double = NSDate().timeIntervalSince1970,
    maxAgeSeconds: Double = RAFT_SHARE_IMAGE_MAX_AGE_SECONDS,
    fileManager: NSFileManager = NSFileManager.defaultManager,
) {
    val cutoffSeconds = nowEpochSeconds - maxAgeSeconds
    val entries = fileManager.contentsOfDirectoryAtPath(dir, error = null) ?: return
    for (entry in entries) {
        val name = entry as? String ?: continue
        if (!name.startsWith(ThreadShareImageFilePrefix) || !name.endsWith(".png")) continue
        val itemPath = "$dir/$name"
        val attributes = fileManager.attributesOfItemAtPath(itemPath, error = null) ?: continue
        val modified = attributes[NSFileModificationDate] as? NSDate ?: continue
        if (modified.timeIntervalSince1970 < cutoffSeconds) {
            fileManager.removeItemAtPath(itemPath, error = null)
        }
    }
}

private const val RAFT_SNAPSHOT_DIR = "raft_snapshots"
internal const val RAFT_SHARE_IMAGE_MAX_AGE_SECONDS = 24.0 * 60.0 * 60.0
