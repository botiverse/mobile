package build.raft.app.shared

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real BitmapFactory chain behind local image previews: a bounds pass followed by a
 * sampled decode, both from file streams.  The pure sampling unit test cannot see that the bounds
 * pass returns null by design.
 */
@RunWith(AndroidJUnit4::class)
class SlockLocalImageSampledDecodeConnectedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun smallImageDecodesAtFullSize() {
        val file = writePng("small", width = 64, height = 48)
        try {
            val bitmap = slockDecodeSampledBitmap({ FileInputStream(file) }, targetEdgePx = 256)
            assertNotNull(bitmap)
            assertEquals(64, bitmap!!.width)
            assertEquals(48, bitmap.height)
        } finally {
            file.delete()
        }
    }

    @Test
    fun overlongImageDecodesWithinTheEdgeCeiling() {
        // 4800 px exceeds the 4096 px ceiling; the strip keeps the test bitmap ~2 MB.
        val file = writePng("overlong", width = 4800, height = 100)
        try {
            val bitmap = slockDecodeSampledBitmap({ FileInputStream(file) }, targetEdgePx = 4096)
            assertNotNull(bitmap)
            assertEquals(2400, bitmap!!.width)
            assertEquals(50, bitmap.height)
            assertTrue(bitmap.width <= SLOCK_MAX_BITMAP_DECODE_EDGE_PX)
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingSourceReturnsNull() {
        assertNull(slockDecodeSampledBitmap({ null }, targetEdgePx = 256))
    }

    private fun writePng(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 120, 40))
        val file = File(context.cacheDir, "sampled-decode-$name-${System.nanoTime()}.png")
        file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        bitmap.recycle()
        return file
    }
}
