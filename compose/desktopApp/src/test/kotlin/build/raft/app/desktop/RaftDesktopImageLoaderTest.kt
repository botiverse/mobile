package build.raft.app.desktop

import build.raft.app.image.raftBuildImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Size
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Raft's painters ask Coil for the original size; on desktop the loader caps a decoded image at
 * 2048 px on the long edge (Coil's default 4096: a 12 MP photo was ~48 MB of pixels per image).
 */
class RaftDesktopImageLoaderTest {
    @Test fun `the desktop loader caps decoded bitmaps at 2048 px`() {
        // The loader's disk cache lives under the data directory: keep it out of the user's.
        if (System.getProperty("raft.desktop.dataDir").isNullOrBlank()) {
            System.setProperty("raft.desktop.dataDir", Files.createTempDirectory("raft-image").toFile().absolutePath)
        }
        val loader = raftBuildImageLoader(PlatformContext.INSTANCE)
        val request = ImageRequest.Builder(PlatformContext.INSTANCE).data("https://example.invalid/a.png").defaults(loader.defaults).build()
        assertEquals(Size(2048, 2048), request.maxBitmapSize)
        loader.shutdown()
    }
}
