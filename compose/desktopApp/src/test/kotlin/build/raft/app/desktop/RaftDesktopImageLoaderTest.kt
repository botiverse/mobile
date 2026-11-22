package build.raft.app.desktop

import build.raft.app.image.raftBuildImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Raft's painters ask Coil for the original size; on desktop (and iOS, which shares Coil's
 * non-Android request service and Skia decoder) the loader caps default decoded bitmaps at 2048 px
 * on the long edge while allowing explicit high-resolution export overrides and preview -> editor
 * memory-cache reuse (#wg-mobile-feedback:#269).
 */
class RaftDesktopImageLoaderTest {
    private fun ensureTempDataDir() {
        // The loader's disk cache lives under the data directory: keep it out of the user's.
        if (System.getProperty("raft.desktop.dataDir").isNullOrBlank()) {
            System.setProperty("raft.desktop.dataDir", Files.createTempDirectory("raft-image").toFile().absolutePath)
        }
    }

    @Test fun `the desktop loader caps decoded bitmaps at 2048 px`() {
        ensureTempDataDir()
        val loader = raftBuildImageLoader(PlatformContext.INSTANCE)
        try {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE).data("https://example.invalid/a.png").defaults(loader.defaults).build()
            assertEquals(Size(2048, 2048), request.maxBitmapSize)
        } finally {
            loader.shutdown()
        }
    }

    @Test fun `unbounded skia decode caps at 2048 px while export override decodes full size and bounded preview hits memory cache`() = runBlocking {
        ensureTempDataDir()
        val tempFile = Files.createTempFile("raft-large-photo-", ".png").toFile()
        val loader = raftBuildImageLoader(PlatformContext.INSTANCE)
        try {
            val sourceImage = BufferedImage(2600, 1300, BufferedImage.TYPE_INT_ARGB)
            ImageIO.write(sourceImage, "png", tempFile)
            val fileUri = tempFile.toURI().toString()

            val unboundedResult =
                assertIs<SuccessResult>(
                    loader.execute(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .data(fileUri)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                )
            assertEquals(2048, unboundedResult.image.width)
            assertEquals(1024, unboundedResult.image.height)

            val exportResult =
                assertIs<SuccessResult>(
                    loader.execute(
                        ImageRequest.Builder(PlatformContext.INSTANCE)
                            .data(fileUri)
                            .size(Size(2600, 2600))
                            .scale(Scale.FIT)
                            .precision(Precision.INEXACT)
                            .maxBitmapSize(Size(2600, 2600))
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .build()
                    )
                )
            assertEquals(2600, exportResult.image.width)
            assertEquals(1300, exportResult.image.height)

            val previewRequest =
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(fileUri)
                    .size(Size(2048, 2048))
                    .scale(Scale.FIT)
                    .precision(Precision.INEXACT)
                    .build()
            val previewResult = assertIs<SuccessResult>(loader.execute(previewRequest))
            assertEquals(2048, previewResult.image.width)
            assertEquals(1024, previewResult.image.height)

            val editorRequest =
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(fileUri)
                    .size(Size(2048, 2048))
                    .scale(Scale.FIT)
                    .precision(Precision.INEXACT)
                    .build()
            val editorResult = assertIs<SuccessResult>(loader.execute(editorRequest))
            assertEquals(DataSource.MEMORY_CACHE, editorResult.dataSource)
            assertEquals(2048, editorResult.image.width)
            assertEquals(1024, editorResult.image.height)
        } finally {
            loader.shutdown()
            tempFile.delete()
        }
    }
}
