package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * task #800 rendering evidence (ZhangYi's two hard bars, measured on a real
 * emulator render — not structural assertions):
 *  1. a single-character right-aligned bubble measures width < the long
 *     reference's cap AND its End edge lines up with the long bubble's;
 *  2. the End edge stays put across sent / sending / failed states (the
 *     in-flow spinner only adds width on the left).
 *
 * The fixture page renders four right-aligned own-message cards separated by
 * pure-red 4dp markers; bands are segmented by scanning marker rows, and
 * bubble edges are the extreme non-background pixels inside each band.
 */
class SlockThreadAlignRightBoundsConnectedTest {
    @Test
    fun rightAlignedBubblesHugTheEndEdgeAcrossSendStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", "compose_thread_align_right_visual")
                .putExtra(SlockKuiklyActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { _ ->
            instrumentation.waitForIdleSync()
            Thread.sleep(2_500)
            val screenshot =
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                    "Unable to capture align-right fixture screenshot"
                }
            try {
                val bands = screenshot.markerBands()
                assertTrue("Expected 4 fixture bands between markers, got ${bands.size}", bands.size >= 4)
                val long = screenshot.bandContentEdges(bands[0])
                val short = screenshot.bandContentEdges(bands[1])
                val sending = screenshot.bandContentEdges(bands[2])
                val failed = screenshot.bandContentEdges(bands[3])
                println(
                    "ALIGN_RIGHT_BOUNDS long=$long short=$short sending=$sending failed=$failed " +
                        "screen=${screenshot.width}x${screenshot.height}"
                )

                val longWidth = long.right - long.left
                val shortWidth = short.right - short.left
                // Bar 1a: the single-character bubble is far below the cap the
                // long reference reaches (a full-width box would measure ≈ longWidth).
                assertTrue(
                    "short bubble width $shortWidth must be < half the long reference width $longWidth",
                    shortWidth < longWidth / 2
                )
                // Bar 1b: End edges line up (same End coordinate system).
                assertTrue(
                    "short End edge ${short.right} must align with long End edge ${long.right}",
                    abs(short.right - long.right) <= EdgeTolerancePx
                )
                // Bar 2: sending → sent → failed keep the End edge fixed.
                assertTrue(
                    "sending End edge ${sending.right} must equal sent End edge ${short.right}",
                    abs(sending.right - short.right) <= EdgeTolerancePx
                )
                assertTrue(
                    "failed End edge ${failed.right} must equal sent End edge ${short.right}",
                    abs(failed.right - short.right) <= EdgeTolerancePx
                )
                // The sending band carries the in-flow spinner LEFT of the
                // bubble: its leftmost content must extend further left than
                // the sent single-character band's.
                assertTrue(
                    "sending band left ${sending.left} must be < sent band left ${short.left} (spinner on the left)",
                    sending.left < short.left - 2
                )
            } finally {
                screenshot.recycle()
            }
        }
    }

    private data class BandEdges(val left: Int, val right: Int) {
        override fun toString(): String = "[$left,$right]"
    }

    private data class Band(val top: Int, val bottom: Int)

    /** Rows made (mostly) of the pure-red marker split the fixture bands. */
    private fun Bitmap.markerBands(): List<Band> {
        val markerRows = BooleanArray(height)
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            var red = 0
            for (pixel in row) {
                if (Color.red(pixel) > 200 && Color.green(pixel) < 60 && Color.blue(pixel) < 60) red++
            }
            markerRows[y] = red > width / 2
        }
        val bands = mutableListOf<Band>()
        var bandStart = -1
        for (y in 0 until height) {
            if (markerRows[y]) {
                if (bandStart in 0 until y - 8) bands.add(Band(bandStart, y - 1))
                bandStart = -1
            } else if (bandStart < 0) {
                bandStart = y
            }
        }
        // Content above the first marker (status bar area) produces a fake
        // first band only if no marker precedes it; the fixture draws a marker
        // before every card, so bands collected between markers are the cards.
        return bands.filter { it.bottom - it.top > 16 }
    }

    /** Extreme non-background columns inside a band (background = near-white). */
    private fun Bitmap.bandContentEdges(band: Band): BandEdges {
        var left = width
        var right = -1
        val row = IntArray(width)
        for (y in band.top..band.bottom) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                val isBackground =
                    Color.red(pixel) > 235 && Color.green(pixel) > 235 && Color.blue(pixel) > 235
                if (!isBackground) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        assertTrue("band $band has no rendered content", right >= left)
        return BandEdges(left, right)
    }

    private companion object {
        const val EdgeTolerancePx = 3
    }
}
