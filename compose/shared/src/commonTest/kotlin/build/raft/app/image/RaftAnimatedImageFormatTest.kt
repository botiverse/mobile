package build.raft.app.image

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Buffer

class RaftAnimatedImageFormatTest {
    @Test
    fun sniffsAnimatedFormatsFromLeadingBytes() {
        val cases =
            listOf(
                "three-frame GIF" to (RaftAnimatedImageFixtures.threeFrameGif to RaftAnimatedImageFormat.Gif),
                "GIF87a" to ("GIF87a".encodeToByteArray() + ByteArray(15) to RaftAnimatedImageFormat.Gif),
                "animated WebP" to (RaftAnimatedImageFixtures.webpVp8xHeader(animated = true) to RaftAnimatedImageFormat.AnimatedWebP),
                "extended still WebP" to (RaftAnimatedImageFixtures.webpVp8xHeader(animated = false) to null),
                "simple WebP" to (RaftAnimatedImageFixtures.webpVp8Header to null),
                "PNG" to (RaftAnimatedImageFixtures.pngHeader to null),
                "truncated WebP header" to (RaftAnimatedImageFixtures.webpVp8xHeader(animated = true).copyOf(16) to null),
                "empty" to (ByteArray(0) to null),
            )
        cases.forEach { (label, case) ->
            val (bytes, expected) = case
            assertEquals(expected, raftSniffAnimatedImageFormat(bytes), label)
            val source = Buffer().write(bytes)
            assertEquals(expected, raftSniffAnimatedImageFormat(source), "$label (source)")
            assertEquals(bytes.size.toLong(), source.size, "$label: sniffing must not consume the source")
        }
    }

    @Test
    fun viewerRequestsAnimationOnlyForGifAndWebpMetadata() {
        val cases =
            listOf(
                Triple("image/gif", listOf("cat.png"), true),
                Triple("image/webp", emptyList<String?>(), true),
                Triple("IMAGE/GIF; charset=binary", emptyList(), true),
                Triple("image/png", listOf("cat.gif"), false),
                Triple("image/jpeg", emptyList(), false),
                Triple("", listOf("", "Cat.GIF"), true),
                Triple("application/octet-stream", listOf("loop.webp"), true),
                Triple("", listOf("https://cdn.example/a/loop.gif?sig=1#x"), true),
                Triple("", listOf("https://cdn.example/a.gif/still.png?x=.gif"), false),
                Triple("", listOf(null, "photo.jpg"), false),
            )
        cases.forEach { (mime, names, expected) ->
            assertEquals(expected, raftImageMayBeAnimated(mime, *names.toTypedArray()), "mime=$mime names=$names")
        }
    }

    @Test
    fun fixtureIsARealThreeFrameGif() {
        assertEquals(listOf(100, 200, 0), RaftAnimatedImageFixtures.gifFrameDelaysMillis(RaftAnimatedImageFixtures.threeFrameGif))
    }
}
