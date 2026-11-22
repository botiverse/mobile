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
}
