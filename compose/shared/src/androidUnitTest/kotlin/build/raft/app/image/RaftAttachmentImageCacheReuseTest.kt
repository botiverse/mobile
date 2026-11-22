package build.raft.app.image

import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Product-hypothesis test for #proj-mobile:#293:
 * an original attachment image already downloaded in the message list hits Coil's cache when opened
 * in full-screen preview even after the 5-minute presigned R2 URL rotates, without re-downloading.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RaftAttachmentImageCacheReuseTest {
    @Test
    fun imageViewedInThreadReusesCoilCacheInPreviewAfterPresignedUrlChanges() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val attachmentId = "4239508c-1111-4222-8333-444444444444"
        val cacheKey = assertNotNull(raftAttachmentOriginalImageCacheKey(attachmentId))
        val resignedPreviewUrl =
            "https://r2.example/attachments/$attachmentId.png?X-Amz-Signature=rotated-signature-after-5m&X-Amz-Expires=300"
        val pngBytes =
            ByteArrayOutputStream().use { out ->
                val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(0xFF228844.toInt())
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                out.toByteArray()
            }
        assertEquals("image/png", raftSniffImageMimeType(pngBytes), "fixture bytes must be a real PNG")

        val loader = raftBuildImageLoader(context)
        try {
            val diskCache = assertNotNull(loader.diskCache)
            val editor = assertNotNull(diskCache.openEditor(cacheKey))
            diskCache.fileSystem.write(editor.data) { write(pngBytes) }
            diskCache.fileSystem.write(editor.metadata) {
                // Even if older than 24h, an immutable attachment id entry never re-downloads.
                writeUtf8(RaftImageDiskMetadata("image/png", storedAtEpochMillis = 1L).encode())
            }
            editor.commit()
            assertNotNull(diskCache.openSnapshot(cacheKey), "disk cache entry must be readable after commit").close()
            loader.memoryCache?.clear()

            val previewRequest =
                ImageRequest.Builder(context)
                    .data(resignedPreviewUrl)
                    .defaults(loader.defaults)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .build()
            val result = loader.execute(previewRequest)
            if (result is ErrorResult) {
                throw AssertionError(
                    "Expected SuccessResult from Coil disk cache, got ErrorResult(${result.throwable})",
                    result.throwable,
                )
            }
            val success = assertIs<SuccessResult>(result)
            assertEquals(DataSource.DISK, success.dataSource)
        } finally {
            runCatching { loader.memoryCache?.clear() }
            runCatching { loader.diskCache?.remove(cacheKey) }
            runCatching { loader.shutdown() }
            resetRaftImageLoaderForTest()
            SingletonImageLoader.reset()
            raftInstallImageLoader()
        }
    }
}
