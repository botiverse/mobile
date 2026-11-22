package build.raft.app.image

import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Product-hypothesis test for #proj-mobile:#290:
 * after restart, an avatar/server icon already present in Coil's disk cache is promoted into Coil's
 * memory cache without touching the network so frame 1 hits synchronously from memory.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RaftAvatarStartupPrewarmTest {
    @Test
    fun restartedImageLoaderPrewarmsDiskCachedAvatarIntoMemoryForSyncFrameOneHit(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val avatarUrl = "https://cdn.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp"
        val retiredHostUrl = "https://content.raft.build/avatars/agents/0123456789abcdef0123456789abcdef.webp"
        val cacheKey = raftNetworkImageCacheKey(avatarUrl)
        assertEquals(cacheKey, raftNetworkImageCacheKey(retiredHostUrl), "retired content.raft.build host must share cache key")
        val pngBytes =
            ByteArrayOutputStream().use { out ->
                val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(0xFF336699.toInt())
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                out.toByteArray()
            }

        var loader = raftBuildImageLoader(context)
        try {
            val diskCache = assertNotNull(loader.diskCache, "Raft loader must configure a disk cache")
            val editor = assertNotNull(diskCache.openEditor(cacheKey))
            diskCache.fileSystem.write(editor.data) { write(pngBytes) }
            diskCache.fileSystem.write(editor.metadata) {
                writeUtf8(RaftImageDiskMetadata("image/png", raftImageNowEpochMillis()).encode())
            }
            editor.commit()
            loader.memoryCache?.clear()
            loader.shutdown()

            // Simulated cold restart: fresh ImageLoader with an empty memory cache over the same disk cache.
            loader = raftBuildImageLoader(context)
            assertNull(raftAvatarMemoryCacheBitmap(cacheKey), "cold memory cache starts empty before prewarm")

            val promoted =
                raftPrewarmCachedImagesFromDisk(
                    context = context,
                    urls = listOf(retiredHostUrl, "pixel:robot", "https://cdn.raft.build/avatars/agents/ffffffffffffffffffffffffffffffff.webp"),
                    imageLoader = loader,
                )

            assertEquals(1, promoted, "only disk-cached avatar promotes into memory; non-HTTP and uncached URLs never hit network")
            assertNotNull(
                raftAvatarMemoryCacheBitmap(cacheKey),
                "restarted frame 1 hits Coil memory cache synchronously after disk prewarm",
            )
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
