package build.raft.app.image

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Real encoded images for the animated-image tests (decoded by the JDK's ImageIO when generated:
 * 3 frames, 4x4).
 */
internal object RaftAnimatedImageFixtures {
    /**
     * GIF89a, 4x4, looping forever (NETSCAPE2.0), three frames: solid red for 10cs, solid green
     * for 20cs, then half blue / half black with a 0cs delay (which players stretch to 100ms).
     */
    @OptIn(ExperimentalEncodingApi::class)
    val threeFrameGif: ByteArray =
        Base64.decode(
            "R0lGODlhBAAEAIEAAAAAAP8AAAD/AAAA/yH/C05FVFNDQVBFMi4wAwEAAAAh+QQECgAAACwAAAAABAAEAAACCkyYMGHChAkTJgUA" +
                "IfkEBBQAAAAsAAAAAAQABAAAAgqUKFGiRIkSJUoFACH5BAQAAAAALAAAAAAEAAQAAAIK3Lhx40aAAAECBQA7"
        )

    /** The first bytes of a PNG (signature + IHDR start). */
    val pngHeader: ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 4, 0)

    /** A WebP extended-format header (`RIFF....WEBPVP8X` + flags) with or without the animation bit. */
    fun webpVp8xHeader(animated: Boolean): ByteArray =
        "RIFF".encodeToByteArray() + byteArrayOf(0x40, 0, 0, 0) + "WEBPVP8X".encodeToByteArray() +
            byteArrayOf(10, 0, 0, 0) + byteArrayOf(if (animated) 0b10 else 0b10000)

    /** A simple (lossy, single-image) WebP header: never animated. */
    val webpVp8Header: ByteArray =
        "RIFF".encodeToByteArray() + byteArrayOf(0x40, 0, 0, 0) + "WEBPVP8 ".encodeToByteArray() + byteArrayOf(10, 0, 0, 0, 0)

    /**
     * Frame delays of a GIF in milliseconds, read from its Graphic Control Extensions (one per
     * image descriptor), so tests can drive the player with the fixture's real timing.
     */
    fun gifFrameDelaysMillis(bytes: ByteArray): List<Int> {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun skipSubBlocks(start: Int): Int {
            var i = start
            while (u8(i) != 0) i += u8(i) + 1
            return i + 1
        }
        val delays = mutableListOf<Int>()
        var pendingDelay = 0
        val flags = u8(10)
        var i = 13 + if (flags and 0x80 != 0) 3 * (1 shl ((flags and 0x07) + 1)) else 0
        while (i < bytes.size) {
            when (u8(i)) {
                0x21 -> {
                    if (u8(i + 1) == 0xF9) pendingDelay = (u8(i + 4) or (u8(i + 5) shl 8)) * 10
                    i = skipSubBlocks(i + 2)
                }
                0x2C -> {
                    delays += pendingDelay
                    pendingDelay = 0
                    val localFlags = u8(i + 9)
                    i += 10 + if (localFlags and 0x80 != 0) 3 * (1 shl ((localFlags and 0x07) + 1)) else 0
                    i = skipSubBlocks(i + 1) // LZW minimum code size, then the image data
                }
                0x3B -> break
                else -> error("unexpected GIF block 0x${u8(i).toString(16)} at $i")
            }
        }
        return delays
    }
}
