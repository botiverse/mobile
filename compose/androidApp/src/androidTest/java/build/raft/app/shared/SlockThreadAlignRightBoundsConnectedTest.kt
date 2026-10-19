package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * task #800 rendering evidence, oracle v3 (LiBai's four counterexamples +
 * ZhangYi's frozen re-definition of tooth #3).
 *
 * Geometry is measured ONLY through the fixture-provided tints painted on the
 * exact production geometry (LocalThreadMessageGeometryTints, null in
 * production): body = pure blue, status slot/spinner = pure magenta, 82% cap
 * container = pure green, retry button = pure yellow. Whole-card non-white
 * extremes are never scanned — the v2 run proved they only measure author
 * chrome (both bands' extreme left was 912px of header, not the spinner).
 *
 * Bands: exactly SIX red markers → exactly FIVE bands, prefix/suffix rejected.
 * Order: long cap reference / sent single char / sending / failed(+retry) /
 * retry-returned-to-sending.
 */
class SlockThreadAlignRightBoundsConnectedTest {
    @Test
    fun rightAlignedBubblesHugTheEndEdgeAcrossSendStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", "compose_thread_align_right_visual")
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { _ ->
            instrumentation.waitForIdleSync()
            Thread.sleep(2_500)
            val screenshot =
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                    "Unable to capture align-right fixture screenshot"
                }
            try {
                val bands = screenshot.exactBandsBetweenMarkers(expectedMarkers = 6)
                assertEquals("exactly five fixture bands between six markers", 5, bands.size)
                val long = screenshot.bandGeometry(bands[0])
                val sent = screenshot.bandGeometry(bands[1])
                val sending = screenshot.bandGeometry(bands[2])
                val failed = screenshot.bandGeometry(bands[3])
                val retry = screenshot.bandGeometry(bands[4])
                // The measured geometry must be AUDITABLE, not just asserted:
                // sendStatus lands these in the `am instrument -r` stream that
                // CI tees, greps per band, and uploads as an artifact.
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("ALIGN_RIGHT_GEOMETRY_long", long.toString())
                        putString("ALIGN_RIGHT_GEOMETRY_sent", sent.toString())
                        putString("ALIGN_RIGHT_GEOMETRY_sending", sending.toString())
                        putString("ALIGN_RIGHT_GEOMETRY_failed", failed.toString())
                        putString("ALIGN_RIGHT_GEOMETRY_retry", retry.toString())
                    }
                )
                println("ALIGN_RIGHT_GEOMETRY long=$long sent=$sent sending=$sending failed=$failed retry=$retry")

                // --- cap, measured independently from the green container ---
                val longCap = requireNotNull(long.cap) { "long band lost its cap tint" }
                val longBody = requireNotNull(long.body) { "long band lost its body tint" }
                val sentBody = requireNotNull(sent.body) { "sent band lost its body tint" }
                val sentCap = requireNotNull(sent.cap) { "sent band lost its cap tint" }
                val longCapWidth = longCap.width
                // Bar: the long reference actually reaches the cap …
                assertTrue(
                    "long body width ${longBody.width} must reach >= 0.98 of measured cap $longCapWidth",
                    longBody.width >= (longCapWidth * 98) / 100
                )
                // … and the single character is far below it (its own band's cap).
                assertTrue(
                    "sent body width ${sentBody.width} must be < half of measured cap ${sentCap.width}",
                    sentBody.width < sentCap.width / 2
                )

                // --- End alignment: body right edges equal across ALL bands ---
                val bodies = listOf(long to "long", sent to "sent", sending to "sending", failed to "failed", retry to "retry")
                for ((geom, name) in bodies) {
                    val body = requireNotNull(geom.body) { "$name band lost its body tint" }
                    assertTrue(
                        "$name body End edge ${body.right} must equal long body End edge ${longBody.right}",
                        abs(body.right - longBody.right) <= EdgeTolerancePx
                    )
                }

                // --- sending: spinner strictly left of body, same first line ---
                val sendingBody = requireNotNull(sending.body) { "sending band lost its body tint" }
                val spinner = requireNotNull(sending.status) { "sending band must carry the spinner tint" }
                assertTrue(
                    "spinner right ${spinner.right} must be strictly left of body left ${sendingBody.left}",
                    spinner.right < sendingBody.left
                )
                assertTrue(
                    "spinner ${spinner.vertical} must overlap the body's first line ${sendingBody.vertical}",
                    spinner.top < sendingBody.top + (sendingBody.height.coerceAtLeast(1)) &&
                        spinner.bottom > sendingBody.top
                )

                // --- retry state == sending state, 0px equivalence ---
                val retryBody = requireNotNull(retry.body) { "retry band lost its body tint" }
                val retrySpinner = requireNotNull(retry.status) { "retry band must carry the spinner tint" }
                assertEquals("retry body width == sending body width", sendingBody.width, retryBody.width)
                assertEquals("retry body right == sending body right", sendingBody.right, retryBody.right)
                assertEquals("retry spinner width == sending spinner width", spinner.width, retrySpinner.width)
                assertEquals(
                    "retry spinner offset-from-body == sending spinner offset-from-body",
                    sendingBody.left - spinner.right,
                    retryBody.left - retrySpinner.right
                )

                // --- failed: status row below the body, retry INSIDE the slot ---
                val failedBody = requireNotNull(failed.body) { "failed band lost its body tint" }
                val failedStatus = requireNotNull(failed.status) { "failed band must carry the status slot tint" }
                val retryButton = requireNotNull(failed.retry) { "failed band must carry the retry button tint" }
                assertTrue(
                    "failed status slot top ${failedStatus.top} must be below body bottom ${failedBody.bottom}",
                    failedStatus.top >= failedBody.bottom
                )
                assertTrue(
                    "retry button $retryButton must be contained in the failed status slot $failedStatus",
                    retryButton.left >= failedStatus.left && retryButton.right <= failedStatus.right &&
                        retryButton.top >= failedStatus.top && retryButton.bottom <= failedStatus.bottom
                )
                // Sent/long bands must carry no status/retry tint at all.
                assertTrue("long band must have no status tint", long.status == null && long.retry == null)
                assertTrue("sent band must have no status tint", sent.status == null && sent.retry == null)
            } finally {
                screenshot.recycle()
            }
        }
    }

    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val vertical: String get() = "[$top..$bottom]"
        override fun toString(): String = "[$left,$top,$right,$bottom]"
    }

    private data class BandGeometry(
        val body: Region?,
        val status: Region?,
        val cap: Region?,
        val retry: Region?
    ) {
        override fun toString(): String = "body=$body status=$status cap=$cap retry=$retry"
    }

    private data class Band(val top: Int, val bottom: Int)

    /**
     * Exactly [expectedMarkers] red marker runs must exist; the returned bands
     * are ONLY the regions between consecutive markers. Content above the
     * first marker (activity chrome) and below the last is rejected, never
     * misread as a band — the v2 oracle's first flaw.
     */
    private fun Bitmap.exactBandsBetweenMarkers(expectedMarkers: Int): List<Band> {
        val row = IntArray(width)
        val markerRows = BooleanArray(height)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            var red = 0
            for (pixel in row) if (pixel.isMarkerRed()) red++
            markerRows[y] = red > width / 2
        }
        val markerRuns = mutableListOf<Band>()
        var runStart = -1
        for (y in 0 until height) {
            if (markerRows[y]) {
                if (runStart < 0) runStart = y
            } else if (runStart >= 0) {
                markerRuns.add(Band(runStart, y - 1))
                runStart = -1
            }
        }
        if (runStart >= 0) markerRuns.add(Band(runStart, height - 1))
        assertEquals("exactly $expectedMarkers red marker runs", expectedMarkers, markerRuns.size)
        return markerRuns.zipWithNext { upper, lower -> Band(upper.bottom + 1, lower.top - 1) }
    }

    /** Extents of each fixture tint inside a band — never whole-card pixels. */
    private fun Bitmap.bandGeometry(band: Band): BandGeometry {
        var body: Region? = null
        var status: Region? = null
        var cap: Region? = null
        var retry: Region? = null
        val row = IntArray(width)
        for (y in band.top..band.bottom) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                when {
                    pixel.isBodyBlue() -> body = body.expand(x, y)
                    pixel.isStatusMagenta() -> status = status.expand(x, y)
                    pixel.isCapGreen() -> cap = cap.expand(x, y)
                    pixel.isRetryYellow() -> retry = retry.expand(x, y)
                }
            }
        }
        // The cap container sits BEHIND the body: its true extent is the union.
        val fullCap = when {
            cap == null -> body
            body == null -> cap
            else -> Region(
                minOf(cap.left, body.left),
                minOf(cap.top, body.top),
                maxOf(cap.right, body.right),
                maxOf(cap.bottom, body.bottom)
            )
        }
        return BandGeometry(body = body, status = status, cap = fullCap, retry = retry)
    }

    private fun Region?.expand(x: Int, y: Int): Region =
        if (this == null) Region(x, y, x, y)
        else Region(minOf(left, x), minOf(top, y), maxOf(right, x), maxOf(bottom, y))

    private fun Int.isMarkerRed() = Color.red(this) > 200 && Color.green(this) < 60 && Color.blue(this) < 60
    private fun Int.isBodyBlue() = Color.blue(this) > 200 && Color.red(this) < 60 && Color.green(this) < 60
    private fun Int.isStatusMagenta() = Color.red(this) > 200 && Color.blue(this) > 200 && Color.green(this) < 60
    private fun Int.isCapGreen() = Color.green(this) > 200 && Color.red(this) < 60 && Color.blue(this) < 60
    private fun Int.isRetryYellow() = Color.red(this) > 200 && Color.green(this) > 200 && Color.blue(this) < 60

    private companion object {
        const val EdgeTolerancePx = 3
    }
}
