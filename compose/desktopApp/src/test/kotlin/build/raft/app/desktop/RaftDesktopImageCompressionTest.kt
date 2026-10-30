package build.raft.app.desktop

import build.raft.app.data.RaftDesktopComposerTemporaryFiles
import build.raft.app.data.RaftDesktopImageCompression
import build.raft.app.data.RaftDesktopImageCompressionCancelled
import build.raft.app.data.RaftDesktopThreadImageCompressor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class RaftDesktopImageCompressionTest {
    private val root = Files.createTempDirectory("raft-compress-test").toFile()
    private val out = File(root, "clipboard").apply { mkdirs() }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun `the longest side is scaled to 2048 and smaller images are not enlarged`() {
        assertEquals(2048 to 512, RaftDesktopImageCompression.targetSize(4000, 1000))
        assertEquals(1024 to 2048, RaftDesktopImageCompression.targetSize(3000, 6000))
        assertEquals(2048 to 2048, RaftDesktopImageCompression.targetSize(2048, 2048))
        assertEquals(800 to 600, RaftDesktopImageCompression.targetSize(800, 600))
    }

    @Test fun `a large PNG becomes a 2048 px JPEG at quality 82`() {
        val source = File(root, "big.png").apply { writeBytes(encode(image(4000, 1000) { it.clear(Color.makeRGB(10, 120, 200)) }, EncodedImageFormat.PNG)) }
        val output = File(out, "composer-image-a.jpg")
        RaftDesktopImageCompression.compress(source, output)
        val bytes = output.readBytes()
        Codec.makeFromData(Data.makeFromBytes(bytes)).use { codec ->
            assertEquals(EncodedImageFormat.JPEG, codec.encodedImageFormat)
            assertEquals(2048, codec.width)
            assertEquals(512, codec.height)
        }
        // libjpeg quality 82 scales the base luminance table by 36%: its first entry 16 becomes 6
        // (quality 90 would give 3, quality 75 gives 8).
        assertEquals(6, firstLuminanceQuantizer(bytes))
        assertFalse(File(out, "composer-image-a.jpg.part").exists())
    }

    @Test fun `a small image keeps its size`() {
        val source = File(root, "small.png").apply { writeBytes(encode(image(300, 200) { it.clear(Color.RED) }, EncodedImageFormat.PNG)) }
        val output = File(out, "composer-image-b.jpg")
        RaftDesktopImageCompression.compress(source, output)
        Codec.makeFromData(Data.makeFromBytes(output.readBytes())).use { assertEquals(300 to 200, it.width to it.height) }
    }

    @Test fun `EXIF orientation is applied (rotate 90 clockwise)`() {
        // Stored 40x20: left half red, right half blue. Orientation 6 shows it rotated 90 degrees
        // clockwise: 20x40 with red on top.
        val stored =
            image(40, 20) { canvas ->
                Paint().use { paint ->
                    paint.color = Color.RED
                    canvas.drawRect(Rect.makeLTRB(0f, 0f, 20f, 20f), paint)
                    paint.color = Color.BLUE
                    canvas.drawRect(Rect.makeLTRB(20f, 0f, 40f, 20f), paint)
                }
            }
        val source = File(root, "rotated.jpg").apply { writeBytes(withExifOrientation(encode(stored, EncodedImageFormat.JPEG, 100), 6)) }
        Codec.makeFromData(Data.makeFromBytes(source.readBytes())).use { assertEquals("RIGHT_TOP", it.encodedOrigin.name) }
        val output = File(out, "composer-image-c.jpg")
        RaftDesktopImageCompression.compress(source, output)
        val bitmap = decode(output)
        assertEquals(20 to 40, bitmap.width to bitmap.height)
        assertReddish(bitmap.getColor(10, 5))
        assertBluish(bitmap.getColor(10, 35))

        // Orientation 8 (rotate 270): red at the bottom. Orientation 3 (180): red on the right.
        val left = File(root, "left.jpg").apply { writeBytes(withExifOrientation(encode(stored(), EncodedImageFormat.JPEG, 100), 8)) }
        RaftDesktopImageCompression.compress(left, File(out, "composer-image-c8.jpg"))
        decode(File(out, "composer-image-c8.jpg")).let {
            assertEquals(20 to 40, it.width to it.height)
            assertBluish(it.getColor(10, 5))
            assertReddish(it.getColor(10, 35))
        }
        val upsideDown = File(root, "180.jpg").apply { writeBytes(withExifOrientation(encode(stored(), EncodedImageFormat.JPEG, 100), 3)) }
        RaftDesktopImageCompression.compress(upsideDown, File(out, "composer-image-c3.jpg"))
        decode(File(out, "composer-image-c3.jpg")).let {
            assertEquals(40 to 20, it.width to it.height)
            assertBluish(it.getColor(5, 10))
            assertReddish(it.getColor(35, 10))
        }
    }

    /** 40x20, left half red, right half blue. */
    private fun stored(): org.jetbrains.skia.Image =
        image(40, 20) { canvas ->
            Paint().use { paint ->
                paint.color = Color.RED
                canvas.drawRect(Rect.makeLTRB(0f, 0f, 20f, 20f), paint)
                paint.color = Color.BLUE
                canvas.drawRect(Rect.makeLTRB(20f, 0f, 40f, 20f), paint)
            }
        }

    @Test fun `transparent pixels become white`() {
        val source = File(root, "alpha.png").apply { writeBytes(encode(image(16, 16) { it.clear(Color.TRANSPARENT) }, EncodedImageFormat.PNG)) }
        val output = File(out, "composer-image-d.jpg")
        RaftDesktopImageCompression.compress(source, output)
        val color = decode(output).getColor(8, 8)
        assertTrue(Color.getR(color) > 245 && Color.getG(color) > 245 && Color.getB(color) > 245, "not white: ${Integer.toHexString(color)}")
    }

    @Test fun `a GIF gives its first frame as a JPEG`() {
        // Skia has no GIF encoder; a minimal 1x1 GIF89a.
        val gif =
            byteArrayOf(
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0x80.toByte(), 0, 0,
                0xFF.toByte(), 0, 0, 0, 0, 0, 0x21, 0xF9.toByte(), 4, 0, 0, 0, 0, 0,
                0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3B,
            )
        val source = File(root, "a.gif").apply { writeBytes(gif) }
        val output = File(out, "composer-image-g.jpg")
        RaftDesktopImageCompression.compress(source, output)
        Codec.makeFromData(Data.makeFromBytes(output.readBytes())).use { assertEquals(EncodedImageFormat.JPEG, it.encodedImageFormat) }
    }

    @Test fun `undecodable input fails and leaves nothing behind`() {
        val svg = File(root, "logo.svg").apply { writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>") }
        val output = File(out, "composer-image-e.jpg")
        assertFailsWith<IOException> { RaftDesktopImageCompression.compress(svg, output) }
        assertFalse(output.exists())
        assertFalse(File(out, "composer-image-e.jpg.part").exists())
    }

    @Test fun `a cancelled compression leaves nothing behind`() {
        val source = File(root, "c.png").apply { writeBytes(encode(image(64, 64) { it.clear(Color.RED) }, EncodedImageFormat.PNG)) }
        val output = File(out, "composer-image-f.jpg")
        assertFailsWith<RaftDesktopImageCompressionCancelled> { RaftDesktopImageCompression.compress(source, output) { true } }
        assertFalse(output.exists())
    }

    @Test fun `videos and non-images are not compressor input`() {
        val file = File(root, "x.png")
        assertTrue(RaftDesktopImageCompression.isCompressibleInput("x.png", "image/png", file))
        assertFalse(RaftDesktopImageCompression.isCompressibleInput("clip.mov", "image/jpeg", file))
        assertFalse(RaftDesktopImageCompression.isCompressibleInput("x.png", "image/png", File(root, "x.mp4")))
        assertFalse(RaftDesktopImageCompression.isCompressibleInput("doc.pdf", "application/pdf", file))
        assertEquals("photo.jpg", RaftDesktopImageCompression.outputFileName("photo.png"))
        assertEquals("a_b.jpg", RaftDesktopImageCompression.outputFileName("a:b.heic"))
    }

    @Test fun `the bridge answers the Android envelope and only reads allowed sources`() {
        val source = File(root, "paste.png").apply { writeBytes(encode(image(32, 32) { it.clear(Color.GREEN) }, EncodedImageFormat.PNG)) }
        val request = assertNotNull(RaftDesktopThreadImageCompressor.request("r1", "file:${source.absolutePath}", "paste.png", "image/png"))
        val ok = assertNotNull(RaftDesktopThreadImageCompressor.run(request, out) { true })
        assertEquals(200, ok["code"]!!.jsonPrimitive.int)
        val file = ok["files"]!!.jsonArray.single().jsonObject
        assertEquals("image/jpeg", file["mimeType"]!!.jsonPrimitive.content)
        assertEquals("composer_image_compressor", file["source"]!!.jsonPrimitive.content)
        assertEquals("paste.jpg", file["fileName"]!!.jsonPrimitive.content)
        val outputFile = File(file["fileRef"]!!.jsonPrimitive.content)
        assertEquals(out.canonicalFile, outputFile.parentFile.canonicalFile)
        assertTrue(outputFile.name.startsWith(RaftDesktopImageCompression.OUTPUT_PREFIX))
        assertEquals(outputFile.length(), file["sizeBytes"]!!.jsonPrimitive.long)

        val refused = assertNotNull(RaftDesktopThreadImageCompressor.run(request.copy(requestId = "r2"), out) { false })
        assertEquals(400, refused["code"]!!.jsonPrimitive.int)
        assertEquals("attachment_image_compression_failed", refused["errorCode"]!!.jsonPrimitive.content)

        assertNull(RaftDesktopThreadImageCompressor.request("", source.absolutePath, "paste.png", "image/png"))
        assertNull(RaftDesktopThreadImageCompressor.request("r3", source.absolutePath, "a/b.png", "image/png"))
        assertNull(RaftDesktopThreadImageCompressor.request("r4", source.absolutePath, "paste.png", "application/pdf"))
    }

    @Test fun `compressed JPEGs are retired and swept like pasted images`() {
        val jpeg = File(out, "composer-image-x.jpg").apply { writeBytes(byteArrayOf(1)) }
        val result = RaftDesktopComposerTemporaryFiles.retire(out, "s", setOf(jpeg.absolutePath)) {}
        assertEquals(setOf(jpeg.absolutePath), result.quarantinedFileRefs)
        val now = System.currentTimeMillis()
        val stale = File(out, "composer-image-old.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(now - 4L * 24 * 3600 * 1000) }
        RaftDesktopComposerTemporaryFiles.sweep(out, now)
        assertFalse(stale.exists())
    }

    private fun image(width: Int, height: Int, draw: (org.jetbrains.skia.Canvas) -> Unit): org.jetbrains.skia.Image =
        Surface.makeRasterN32Premul(width, height).use { surface ->
            draw(surface.canvas)
            surface.makeImageSnapshot()
        }

    private fun encode(image: org.jetbrains.skia.Image, format: EncodedImageFormat, quality: Int = 100): ByteArray =
        image.use { assertNotNull(it.encodeToData(format, quality)).bytes }

    private fun decode(file: File): Bitmap = Codec.makeFromData(Data.makeFromBytes(file.readBytes())).use { it.readPixels() }

    private fun assertReddish(color: Int) =
        assertTrue(Color.getR(color) > 200 && Color.getB(color) < 60, "not red: ${Integer.toHexString(color)}")

    private fun assertBluish(color: Int) =
        assertTrue(Color.getB(color) > 200 && Color.getR(color) < 60, "not blue: ${Integer.toHexString(color)}")

    /** The first value of the first DQT table (8-bit precision). */
    private fun firstLuminanceQuantizer(jpeg: ByteArray): Int {
        var i = 2
        while (i + 4 < jpeg.size) {
            check(jpeg[i] == 0xFF.toByte())
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xDB) return jpeg[i + 5].toInt() and 0xFF
            i += 2 + length
        }
        error("no DQT")
    }

    /** [jpeg] with an APP1 Exif segment carrying [orientation], right after SOI. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff =
            byteArrayOf(
                0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian header, IFD0 at 8
                0x00, 0x01, // one entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // Orientation, SHORT, count 1
                0x00, orientation.toByte(), 0x00, 0x00, // value
                0x00, 0x00, 0x00, 0x00, // no next IFD
            )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        return ByteArrayOutputStream().apply {
            write(jpeg, 0, 2)
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()))
            write(payload)
            write(jpeg, 2, jpeg.size - 2)
        }.toByteArray()
    }
}
