package build.raft.app.image

import coil3.Extras
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer
import okio.FileSystem
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Which decoder the real Raft loader picks for a source: coil-gif only for requests that opted
 * in through [raftAnimatedImage] (the viewer, a message's image tiles) whose bytes are GIF /
 * animated WebP, and the unchanged static decoder for everything else, including GIFs that did
 * not opt in, such as avatars (coil-gif's service-loader registration must stay off).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftAnimatedImageDecoderSelectionTest {
    @Test
    fun api28PlusPicksAnimatedImageDecoderForOptedInGifAndAnimatedWebp() {
        assertEquals(AnimatedImageDecoder::class, decoderFor(RaftAnimatedImageFixtures.threeFrameGif, animate = true))
        assertEquals(
            AnimatedImageDecoder::class,
            decoderFor(RaftAnimatedImageFixtures.webpVp8xHeader(animated = true), animate = true)
        )
        assertStaticPaths()
    }

    private fun assertStaticPaths() {
        assertStatic(decoderFor(RaftAnimatedImageFixtures.threeFrameGif, animate = false), "GIF that did not opt in")
        assertStatic(decoderFor(RaftAnimatedImageFixtures.pngHeader, animate = true), "opted-in PNG")
        assertStatic(decoderFor(RaftAnimatedImageFixtures.webpVp8xHeader(animated = false), animate = true), "opted-in still WebP")
    }

    private fun assertStatic(decoder: kotlin.reflect.KClass<*>?, label: String) {
        assertTrue(decoder in STATIC_DECODERS, "$label: expected Coil's static decoder, got $decoder")
    }

    private fun decoderFor(bytes: ByteArray, animate: Boolean): kotlin.reflect.KClass<*>? {
        val loader = raftBuildImageLoader(RuntimeEnvironment.getApplication())
        return loader.components.newDecoder(sourceResult(bytes), options(animate), loader)?.first?.let { it::class }
    }

    private fun sourceResult(bytes: ByteArray) =
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )

    private fun options(animate: Boolean) =
        Options(
            context = RuntimeEnvironment.getApplication(),
            extras = Extras.Builder().apply { if (animate) set(RaftAnimateImageKey, true) }.build()
        )

    private companion object {
        /** Coil's own static decoders (ImageDecoder-based on API 29+ for file sources, else BitmapFactory). */
        val STATIC_DECODERS = setOf(coil3.decode.BitmapFactoryDecoder::class, coil3.decode.StaticImageDecoder::class)
    }
}
