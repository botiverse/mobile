package build.raft.app.image

import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression: the reaction sprite atlas (and every other Raft image) is drawn via Compose
 * `drawImage`; a Coil `HARDWARE` bitmap crashed any software-Canvas draw ("Software rendering
 * doesn't support hardware bitmaps", visual capture `root.draw(Canvas(bitmap))`, view snapshots).
 * The Raft loader must default every request to software bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
class RaftImageLoaderSoftwareBitmapTest {
    @Test
    fun raftLoaderDefaultsRequestsToSoftwareBitmaps() {
        val context = RuntimeEnvironment.getApplication()
        val loader = raftBuildImageLoader(context)
        val request =
            ImageRequest.Builder(context)
                .data("assets://reactions/reaction_atlas_v1.png")
                .defaults(loader.defaults)
                .build()
        assertFalse(request.allowHardware, "Raft image requests must not decode into Bitmap.Config.HARDWARE")
    }
}
