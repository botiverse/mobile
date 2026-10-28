package build.raft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import build.raft.designsystem.components.applyRaftSystemBarsAppearance
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric native-graphics screenshots of shared Compose UI, no emulator. The test class
 * carries `@GraphicsMode(NATIVE)` and a `@Config` viewport; [capture] applies [appearance],
 * renders [content] in a fresh activity, writes `<reportDir>/<name>.png` on every run and
 * verifies it against the golden `src/androidUnitTest/screenshots/<name>.png` within the
 * design-system screenshot tolerance (±8 per channel, 0.1% of pixels; RaftScreenshot), leaving
 * `<name>.actual.png` and `<name>.diff.png` beside the report on a mismatch.
 * Record: `RAFT_SCREENSHOT_RECORD=true` (or `-Draft.screenshot.record=true`) (re)writes goldens.
 *
 * Text rasterises differently from a phone, so these guard layout and colours against
 * regressions; cross-platform parity stays the connected capture's job.
 *
 * CJK goldens (zh-CN / ja qualifiers) are CI's render. Robolectric bundles the fonts, so the
 * layout and the glyph forms match on every machine, but some Noto CJK glyphs rasterise a pixel
 * differently on another host: 0.1–0.25% of a page's pixels, over the tolerance. Take those
 * goldens from the CI run's `shared-home-screenshots` artifact (`<name>.actual.png`), not from a
 * local record (settings.storage.zh's golden is CI's render too).
 */
internal object RaftRobolectricScreenshot {
    private const val GOLDEN_DIR = "src/androidUnitTest/screenshots"
    private const val CHANNEL_DELTA = 8
    private const val MAX_MISMATCH_RATIO = 0.001

    private val record: Boolean =
        System.getProperty("raft.screenshot.record").toBoolean() ||
            System.getenv("RAFT_SCREENSHOT_RECORD").toBoolean()

    fun capture(
        name: String,
        appearance: RaftAppAppearance,
        reportDir: String,
        activities: RobolectricComposeActivityRule,
        // The view drawn: the activity's compose root by default; a dialog test passes the
        // dialog window's decor view (a RaftDialog renders in its own window).
        root: (ComponentActivity) -> View? = { it.window.decorView },
        content: @Composable (ComponentActivity) -> Unit,
    ) {
        val previousAppearance = RaftAppThemeState.appearance
        try {
            RaftAppThemeState.apply(appearance)
            val activity = activities.launch()
            // The host's bar icons as the app sets them for this mode: a dialog window copies them
            // (#3847), so leaving them to whatever ran before made dialog captures differ by a row.
            activity.window.applyRaftSystemBarsAppearance(RaftAppThemeState.isDark)
            // Moko string resources read the app context through the shared SQLDelight holder.
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
            // Spinners hold still on their first step: a capture is one deterministic frame.
            activity.setContent { CompositionLocalProvider(LocalRaftSpinnerStillStep provides 0) { content(activity) } }
            settle()
            val composeView = root(activity)?.let(::findComposeRoot)
            assertNotNull(composeView, "no compose root")
            val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
            composeView.draw(android.graphics.Canvas(bitmap))
            verifyOrRecord(name, bitmap, File(reportDir))
        } finally {
            RaftAppThemeState.apply(previousAppearance)
        }
    }

    private fun verifyOrRecord(name: String, actual: Bitmap, reportDir: File) {
        reportDir.mkdirs()
        File(reportDir, "$name.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val golden = File(GOLDEN_DIR, "$name.png")
        if (record) {
            golden.parentFile.mkdirs()
            golden.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("recorded ${golden.path} (${actual.width}x${actual.height})")
            return
        }
        val actualCopy = File(reportDir, "$name.actual.png")
        if (!golden.exists()) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("missing golden ${golden.path}; record with RAFT_SCREENSHOT_RECORD=true (actual: ${actualCopy.path})")
        }
        val expected = BitmapFactory.decodeFile(golden.path) ?: fail("unreadable golden ${golden.path}")
        if (expected.width != actual.width || expected.height != actual.height) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name: size ${actual.width}x${actual.height} != golden ${expected.width}x${expected.height}")
        }
        val diff = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
        val mismatched = screenshotMismatches(expected, actual, diff)
        val ratio = mismatched.toDouble() / (actual.width * actual.height)
        if (ratio > MAX_MISMATCH_RATIO) {
            actualCopy.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(reportDir, "$name.diff.png").outputStream().use { diff.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name: $mismatched pixels (${"%.4f".format(ratio * 100)}%) differ from ${golden.path}; actual and diff in ${reportDir.path}")
        }
    }

    private fun settle() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /** Pixels outside ±[CHANNEL_DELTA] on any ARGB channel; paints them red in [diff]. */
    private fun screenshotMismatches(expected: Bitmap, actual: Bitmap, diff: Bitmap): Int {
        var mismatched = 0
        for (y in 0 until actual.height) {
            for (x in 0 until actual.width) {
                val a = actual.getPixel(x, y)
                val e = expected.getPixel(x, y)
                val same = (0 until 32 step 8).all { shift -> abs(((a ushr shift) and 0xFF) - ((e ushr shift) and 0xFF)) <= CHANNEL_DELTA }
                if (same) {
                    diff.setPixel(x, y, (a and 0x00FFFFFF) or 0x40000000)
                } else {
                    mismatched++
                    diff.setPixel(x, y, 0xFFFF0000.toInt())
                }
            }
        }
        return mismatched
    }
}
