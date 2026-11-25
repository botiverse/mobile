@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package build.raft.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * iOS off-screen share image rendering, Skia picture playback, PNG temp-file writing, and stale
 * `raft-message-share-*.png` cache pruning (#proj-mobile:#380).
 */
class ThreadShareImageOffscreenIosTest {
    @Test
    fun offscreenShareImageIsEnabledOnIos() {
        assertTrue(threadShareImageRendersOffscreen)
    }

    @Test
    fun skiaPictureRecordingRasterisesOffscreenAndHonorsCancellation() {
        val recording =
            recordThreadShareImage(width = 64, height = 32) { canvas ->
                val paint = Paint().apply { color = Color(0xFFFFD440) }
                canvas.drawRect(Rect(0f, 0f, 64f, 32f), paint)
            }
        try {
            val bitmap = recording.rasterise()
            assertEquals(64, bitmap.width)
            assertEquals(32, bitmap.height)
            // Skia Bitmap.getColor returns ARGB 0xFFFFD440 for the filled pixel.
            assertEquals(0xFFFFD440.toInt(), bitmap.asSkiaBitmap().getColor(10, 10))
        } finally {
            recording.release()
        }

        val cancelledRecording =
            recordThreadShareImage(width = 32, height = ThreadShareImageRasterBandPx + 64) { canvas ->
                val paint = Paint().apply { color = Color.White }
                canvas.drawRect(Rect(0f, 0f, 32f, (ThreadShareImageRasterBandPx + 64).toFloat()), paint)
            }
        try {
            var checks = 0
            assertFailsWith<CancellationException> {
                cancelledRecording.rasterise(
                    checkCancelled = {
                        checks += 1
                        if (checks == 2) throw CancellationException("width changed")
                    },
                )
            }
            assertEquals(2, checks)
        } finally {
            cancelledRecording.release()
        }
    }

    @Test
    fun pngWriterRoundTripsFileUriAndPrunesOnlyExpiredShareImages() {
        val fileManager = NSFileManager.defaultManager
        val dir = "${NSTemporaryDirectory().trimEnd('/')}/raft-share-image-test-${NSUUID().UUIDString}"
        assertTrue(
            fileManager.createDirectoryAtPath(
                dir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        )
        try {
            val now = NSDate().timeIntervalSince1970
            val expiredTime = now - RAFT_SHARE_IMAGE_MAX_AGE_SECONDS - 120.0
            val freshTime = now - 60.0

            val expiredSharePath = "$dir/${ThreadShareImageFilePrefix}expired.png"
            val freshSharePath = "$dir/${ThreadShareImageFilePrefix}fresh.png"
            val expiredComposerSnapshotPath = "$dir/raft_snapshot_expired.png"

            writeDummyFile(expiredSharePath, expiredTime, fileManager)
            writeDummyFile(freshSharePath, freshTime, fileManager)
            writeDummyFile(expiredComposerSnapshotPath, expiredTime, fileManager)

            val recording =
                recordThreadShareImage(width = 16, height = 16) { canvas ->
                    val paint = Paint().apply { color = Color(0xFF141111) }
                    canvas.drawRect(Rect(0f, 0f, 16f, 16f), paint)
                }
            val rendered =
                try {
                    recording.rasterise()
                } finally {
                    recording.release()
                }

            val fileRef =
                writeRaftIosPngToDirectory(
                    image = rendered,
                    namePrefix = ThreadShareImageFilePrefix,
                    dir = dir,
                    nowEpochSeconds = now,
                    fileManager = fileManager,
                )
            assertNotNull(fileRef)
            assertTrue(fileRef.startsWith("file:///"), "expected file:/// URI, got $fileRef")

            val writtenPath = assertNotNull(threadShareImageFilePath(fileRef))
            assertTrue(fileManager.fileExistsAtPath(writtenPath), "written PNG missing at $writtenPath")

            val data = assertNotNull(NSData.dataWithContentsOfFile(writtenPath))
            assertTrue(data.length.toInt() > 8)
            val header = ByteArray(8)
            header.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, 8u) }
            // PNG file signature: 89 50 4E 47 0D 0A 1A 0A.
            assertEquals(0x89.toByte(), header[0])
            assertEquals('P'.code.toByte(), header[1])
            assertEquals('N'.code.toByte(), header[2])
            assertEquals('G'.code.toByte(), header[3])

            // Stale share image was pruned; fresh share image and expired composer snapshot stay.
            assertFalse(fileManager.fileExistsAtPath(expiredSharePath))
            assertTrue(fileManager.fileExistsAtPath(freshSharePath))
            assertTrue(fileManager.fileExistsAtPath(expiredComposerSnapshotPath))
        } finally {
            fileManager.removeItemAtPath(dir, error = null)
        }
    }

    private fun writeDummyFile(path: String, modifiedEpochSeconds: Double, fileManager: NSFileManager) {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val data =
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        assertTrue(data.writeToFile(path, atomically = true))
        val date = NSDate.dateWithTimeIntervalSince1970(modifiedEpochSeconds)
        assertTrue(
            fileManager.setAttributes(
                mapOf(NSFileModificationDate to date),
                ofItemAtPath = path,
                error = null,
            ),
        )
    }
}
