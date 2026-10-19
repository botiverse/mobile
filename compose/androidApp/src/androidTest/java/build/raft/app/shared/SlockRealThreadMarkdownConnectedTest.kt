package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

class SlockRealThreadMarkdownConnectedTest {
    @Test
    fun realThreadMentionChipsKeepTextAfterInitialRender() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Opt-in real-thread markdown test. Pass -Pandroid.testInstrumentationRunnerArguments.slockRealThreadMarkdown=true " +
                "and a real slockRealThreadPageData or slockRealThreadNotificationPayload.",
            args.getString(ArgEnabled)?.toBooleanStrictOrNull() == true
        )

        val launchSpec = args.realThreadLaunchSpec()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", launchSpec.pageName)
                .putExtra("pageData", launchSpec.pageData.toString())
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            val result =
                waitForMentionChipText(
                    scenario = scenario,
                    instrumentation = instrumentation,
                    timeoutMs = args.getString(ArgTimeoutMs)?.toLongOrNull() ?: RenderTimeoutMs
                )

            assertTrue(
                "Expected at least one highlighted @ mention chip in the real thread; bestScan=$result",
                result.yellowChipCount >= 1
            )
            assertTrue(
                "Found highlighted @ mention chip backgrounds with too few dark text pixels; bestScan=$result",
                result.emptyYellowChipCount == 0
            )
        }
    }

    private fun Bundle.realThreadLaunchSpec(): RealThreadLaunchSpec {
        getString(ArgPageData)?.takeIf { it.isNotBlank() }?.let { pageData ->
            return RealThreadLaunchSpec(
                pageName = getString(ArgPageName)?.takeIf { name -> name.isNotBlank() }
                    ?: SlockCmpActivity.PAGE_APP_SHELL,
                pageData = JSONObject(pageData)
            )
        }

        val notificationPayload =
            getString(ArgNotificationPayload)
                ?.takeIf { it.isNotBlank() }
                ?.let(::JSONObject)
        val pageData =
            notificationPayload?.let { payload -> SlockCmpActivity.buildUnreadNotificationAppShellPageData(payload) }
        assumeTrue(
            "Missing slockRealThreadPageData or valid slockRealThreadNotificationPayload",
            pageData != null
        )
        return RealThreadLaunchSpec(
            pageName = SlockCmpActivity.PAGE_APP_SHELL,
            pageData = pageData!!
        )
    }

    private fun waitForMentionChipText(
        scenario: ActivityScenario<SlockCmpActivity>,
        instrumentation: Instrumentation,
        timeoutMs: Long
    ): MentionChipTextScanResult {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var best = MentionChipTextScanResult()
        do {
            instrumentation.waitForIdleSync()
            val screenshot = captureActivityBitmap(scenario)
            val current = screenshot.scanMentionChipText()
            screenshot.recycle()
            if (current.yellowChipCount > 0 && current.emptyYellowChipCount == 0) {
                return current
            }
            if (current.score > best.score) {
                best = current
            }
            SystemClock.sleep(PollIntervalMs)
        } while (SystemClock.uptimeMillis() < deadline)
        return best
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<SlockCmpActivity>): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0) {
                "Activity root has invalid size ${root.width}x${root.height}"
            }
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                root.draw(Canvas(output))
            }
        }
        return requireNotNull(bitmap) { "Unable to capture real thread screenshot" }
    }

    private fun Bitmap.scanMentionChipText(): MentionChipTextScanResult {
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var yellowChipCount = 0
        var emptyYellowChipCount = 0
        var maxChipDarkPixels = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val seedIndex = y * width + x
                if (visited[seedIndex] || !getPixel(x, y).isNear(HighlightedMentionYellow, YellowTolerance)) {
                    continue
                }

                val component = collectYellowComponent(x, y, visited, queue)
                if (!component.hasMentionChipShape()) continue
                yellowChipCount++

                val darkPixels = countDarkPixels(component)
                maxChipDarkPixels = maxOf(maxChipDarkPixels, darkPixels)
                if (darkPixels < MinDarkTextPixelsPerMentionChip) {
                    emptyYellowChipCount++
                }
            }
        }

        return MentionChipTextScanResult(
            yellowChipCount = yellowChipCount,
            emptyYellowChipCount = emptyYellowChipCount,
            maxChipDarkPixels = maxChipDarkPixels
        )
    }

    private fun Bitmap.collectYellowComponent(
        startX: Int,
        startY: Int,
        visited: BooleanArray,
        queue: IntArray
    ): YellowComponent {
        var head = 0
        var tail = 0
        fun enqueue(x: Int, y: Int) {
            val index = y * width + x
            if (visited[index]) return
            visited[index] = true
            queue[tail++] = index
        }

        enqueue(startX, startY)
        var left = startX
        var right = startX
        var top = startY
        var bottom = startY
        var yellowPixels = 0

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (!getPixel(x, y).isNear(HighlightedMentionYellow, YellowTolerance)) {
                continue
            }
            yellowPixels++
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)

            if (x > 0) enqueue(x - 1, y)
            if (x + 1 < width) enqueue(x + 1, y)
            if (y > 0) enqueue(x, y - 1)
            if (y + 1 < height) enqueue(x, y + 1)
        }

        return YellowComponent(left, top, right, bottom, yellowPixels)
    }

    private fun Bitmap.countDarkPixels(component: YellowComponent): Int {
        var count = 0
        val left = (component.left + TextInsetPx).coerceAtMost(component.right)
        val right = (component.right - TextInsetPx).coerceAtLeast(component.left)
        val top = (component.top + TextInsetPx).coerceAtMost(component.bottom)
        val bottom = (component.bottom - TextInsetPx).coerceAtLeast(component.top)
        for (y in top..bottom) {
            for (x in left..right) {
                if (getPixel(x, y).isDarkTextPixel()) count++
            }
        }
        return count
    }

    private fun Int.isNear(target: Int, tolerance: Int): Boolean =
        abs(Color.red(this) - Color.red(target)) <= tolerance &&
            abs(Color.green(this) - Color.green(target)) <= tolerance &&
            abs(Color.blue(this) - Color.blue(target)) <= tolerance

    private fun Int.isDarkTextPixel(): Boolean =
        Color.alpha(this) > 180 &&
            Color.red(this) < 80 &&
            Color.green(this) < 80 &&
            Color.blue(this) < 80

    private data class RealThreadLaunchSpec(
        val pageName: String,
        val pageData: JSONObject
    )

    private data class YellowComponent(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val yellowPixels: Int
    ) {
        private val componentWidth: Int
            get() = right - left + 1

        private val componentHeight: Int
            get() = bottom - top + 1

        fun hasMentionChipShape(): Boolean =
            yellowPixels >= MinYellowChipPixels &&
                componentWidth in MinMentionChipWidthPx..MaxMentionChipWidthPx &&
                componentHeight in MinMentionChipHeightPx..MaxMentionChipHeightPx
    }

    private data class MentionChipTextScanResult(
        val yellowChipCount: Int = 0,
        val emptyYellowChipCount: Int = 0,
        val maxChipDarkPixels: Int = 0
    ) {
        val score: Int
            get() = yellowChipCount * 1_000 + maxChipDarkPixels - emptyYellowChipCount * 100
    }

    private companion object {
        private const val ArgEnabled = "slockRealThreadMarkdown"
        private const val ArgPageName = "slockRealThreadPageName"
        private const val ArgPageData = "slockRealThreadPageData"
        private const val ArgNotificationPayload = "slockRealThreadNotificationPayload"
        private const val ArgTimeoutMs = "slockRealThreadTimeoutMs"
        private const val RenderTimeoutMs = 8_000L
        private const val PollIntervalMs = 180L
        private const val YellowTolerance = 18
        private const val MinYellowChipPixels = 80
        private const val MinDarkTextPixelsPerMentionChip = 8
        private const val MinMentionChipWidthPx = 16
        private const val MaxMentionChipWidthPx = 280
        private const val MinMentionChipHeightPx = 8
        private const val MaxMentionChipHeightPx = 80
        private const val TextInsetPx = 2
        private val HighlightedMentionYellow = Color.rgb(255, 212, 64)
    }
}
