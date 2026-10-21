package build.raft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the whole phone Home root (`screens.home.channel-list`), no emulator:
 * [HomeChannelListVisualContent] (the same generated VisualFixtureData rows the connected
 * capture and the React case render) at the visual-testing viewport, 390x844dp at 3x
 * (1170x2532, like the CI emulator), light and dark.
 *
 * Every run writes `build/reports/home-screenshots/<name>.png`. Verify (default) compares it
 * with the golden `src/androidUnitTest/screenshots/<name>.png` within the design-system
 * screenshot tolerance (±8 per channel, 0.1% of pixels; RaftScreenshot) and fails on a
 * missing golden or a mismatch, leaving `<name>.actual.png` and `<name>.diff.png` beside it.
 * Record: run with `RAFT_SCREENSHOT_RECORD=true` (or `-Draft.screenshot.record=true`) to
 * (re)write the goldens. CI uploads the report folder as `shared-home-screenshots`.
 *
 * Robolectric native graphics, not the device: text rasterises differently from a phone, so
 * these guard Home's own layout and colours against regressions; cross-platform parity stays
 * the connected capture's job.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class HomeChannelListScreenshotTest {
    private val previousAppearance = RaftAppThemeState.appearance

    @After
    fun restoreAppearance() {
        RaftAppThemeState.apply(previousAppearance)
    }

    @Test
    fun homeChannelListLight() = capture("screens.home.channel-list", RaftAppAppearance.Light)

    @Test
    fun homeChannelListDark() = capture("screens.home.channel-list.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftAppThemeState.apply(appearance)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager =
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            }
        activity.setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { HomeChannelListVisualContent() }
        }
        settle()
        val composeView = findComposeRoot(activity.window.decorView)
        assertNotNull(composeView, "no compose root")
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        verifyOrRecord(name, bitmap)
    }

    private fun verifyOrRecord(name: String, actual: Bitmap) {
        val reportDir = File("build/reports/home-screenshots").apply { mkdirs() }
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

    private companion object {
        const val GOLDEN_DIR = "src/androidUnitTest/screenshots"
        const val CHANNEL_DELTA = 8
        const val MAX_MISMATCH_RATIO = 0.001

        val record: Boolean =
            System.getProperty("raft.screenshot.record").toBoolean() ||
                System.getenv("RAFT_SCREENSHOT_RECORD").toBoolean()

        /** Pixels outside ±[CHANNEL_DELTA] on any ARGB channel; paints them red in [diff]. */
        fun screenshotMismatches(expected: Bitmap, actual: Bitmap, diff: Bitmap): Int {
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
}
