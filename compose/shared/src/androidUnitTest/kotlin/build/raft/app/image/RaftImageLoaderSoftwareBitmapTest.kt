package build.raft.app.image

import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Production keeps Coil's hardware bitmaps; instrumentation that renders into a software Canvas
 * (`root.draw(Canvas(bitmap))`) flips the Raft loader to software bitmaps via
 * [RaftImageLoaderTestOverrides.forceSoftwareBitmaps] — otherwise drawing e.g. the reaction sprite
 * atlas throws "Software rendering doesn't support hardware bitmaps".
 */
@RunWith(RobolectricTestRunner::class)
class RaftImageLoaderSoftwareBitmapTest {
    @BeforeTest
    fun setUp() = RaftImageLoaderTestOverrides.resetForTest()

    @AfterTest
    fun tearDown() = RaftImageLoaderTestOverrides.resetForTest()

    private fun ImageLoader.requestAllowsHardware(): Boolean =
        ImageRequest.Builder(RuntimeEnvironment.getApplication())
            .data("assets://reactions/reaction_atlas_v1.png")
            .defaults(defaults)
            .build()
            .allowHardware

    @Test
    fun productionLoaderKeepsCoilHardwareBitmapDefault() {
        val loader = raftBuildImageLoader(RuntimeEnvironment.getApplication())
        assertTrue(loader.requestAllowsHardware(), "production Raft loader must keep Coil's hardware-bitmap default")
    }

    @Test
    fun softwareBitmapOverrideAppliesToLoadersBuiltAfterIt() {
        RaftImageLoaderTestOverrides.forceSoftwareBitmaps()
        val loader = raftBuildImageLoader(RuntimeEnvironment.getApplication())
        assertFalse(loader.requestAllowsHardware(), "override must disable hardware bitmaps")
    }

    @Test
    fun softwareBitmapOverrideRebuildsAnAlreadyInstalledSingleton() {
        val context = RuntimeEnvironment.getApplication()
        raftInstallImageLoader()
        coil3.SingletonImageLoader.get(context)
        RaftImageLoaderTestOverrides.forceSoftwareBitmaps()
        assertFalse(
            coil3.SingletonImageLoader.get(context).requestAllowsHardware(),
            "singleton built before the override must be replaced",
        )
    }
}
