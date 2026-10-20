package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.SlockReleaseNotesSectionGapProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockReleaseNotesSectionGapConnectedTest {
    @Test
    fun zhLargeFontNeutralInlineCodeNeedsNoExtraHeadingClearance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalFontScale = instrumentation.shell("settings get system font_scale").trim().ifBlank { "1.0" }
        try {
            instrumentation.setFailureDisplayProfile()
            instrumentation.setFontScale("1.3")

            val noGap = captureFixture(instrumentation, FixtureZeroGap)
            val defaultGap = captureFixture(instrumentation, FixtureDefaultGap)
            val releaseNotesGap = captureFixture(instrumentation, FixtureEightDpGap)

            assertTrue(
                "Neutral inline code must not retain legacy yellow paint inside the H2 bounds: $noGap",
                noGap.yellowPixelsInsideHeading <= MaximumClearedPixels
            )
            assertEquals(
                "Omitting sectionHeadingTopPadding must preserve the ordinary Markdown zero-gap pixels",
                noGap,
                defaultGap
            )
            assertTrue(
                "Release Notes must also stay free of legacy yellow inline-code paint: $releaseNotesGap",
                releaseNotesGap.yellowPixelsInsideHeading <= MaximumClearedPixels
            )
            assertTrue(
                "The Release Notes heading must move below the zero-gap position: " +
                    "noGap=$noGap releaseNotes=$releaseNotesGap",
                releaseNotesGap.headingTopPx > noGap.headingTopPx
            )
        } finally {
            instrumentation.setFontScale(originalFontScale)
            instrumentation.resetDisplayProfile()
        }
    }

    private fun captureFixture(
        instrumentation: Instrumentation,
        fixture: String
    ): SectionGapCapture {
        val context = instrumentation.targetContext
        val probeGeneration = "release-notes-section-gap-$fixture-${System.nanoTime()}"
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", SlockKuiklyPages.FLOATING_OVERLAY_TOUCH_GUARD_VISUAL)
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("fixture", fixture)
                        .put("instanceId", probeGeneration)
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        return ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val headingBounds = instrumentation.waitForHeadingBounds(probeGeneration)
            val screenshot = captureActivityBitmap(scenario)
            try {
                SectionGapCapture(
                    headingTopPx = headingBounds.top,
                    headingBottomPx = headingBounds.bottom,
                    yellowPixelsInsideHeading = screenshot.yellowInlineCodePixels(headingBounds)
                )
            } finally {
                screenshot.recycle()
            }
        }
    }

    private fun Instrumentation.waitForHeadingBounds(probeGeneration: String): Rect {
        repeat(HeadingPollAttempts) {
            waitForIdleSync()
            val bounds = SlockReleaseNotesSectionGapProbe.snapshot(probeGeneration)
            if (bounds != null) {
                return Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            SystemClock.sleep(HeadingPollIntervalMs)
        }
        error("Unable to receive the section-heading onGloballyPositioned probe")
    }

    private fun captureActivityBitmap(scenario: ActivityScenario<SlockCmpActivity>): Bitmap {
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            val root = activity.findViewById<android.view.View>(android.R.id.content)
            require(root.width > 0 && root.height > 0) {
                "Activity root has invalid size ${root.width}x${root.height}"
            }
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                root.draw(Canvas(output))
            }
        }
        return requireNotNull(bitmap) { "Unable to capture Release Notes section-gap fixture" }
    }

    private fun Bitmap.yellowInlineCodePixels(bounds: Rect): Int {
        val safe = Rect(
            bounds.left.coerceIn(0, width),
            bounds.top.coerceIn(0, height),
            bounds.right.coerceIn(0, width),
            bounds.bottom.coerceIn(0, height)
        )
        var count = 0
        for (y in safe.top until safe.bottom) {
            for (x in safe.left until safe.right) {
                val pixel = getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                if (red >= YellowRedMin && green in YellowGreenRange && blue <= YellowBlueMax) {
                    count++
                }
            }
        }
        return count
    }

    private fun Instrumentation.setFontScale(value: String) {
        shell("settings put system font_scale $value")
        shell("am broadcast -a android.intent.action.CONFIGURATION_CHANGED")
        SystemClock.sleep(ConfigurationSettleMs)
    }

    private fun Instrumentation.setFailureDisplayProfile() {
        shell("wm size 720x1280")
        shell("wm density 240")
        shell("am broadcast -a android.intent.action.CONFIGURATION_CHANGED")
        SystemClock.sleep(ConfigurationSettleMs)
    }

    private fun Instrumentation.resetDisplayProfile() {
        shell("wm size reset")
        shell("wm density reset")
        shell("am broadcast -a android.intent.action.CONFIGURATION_CHANGED")
        SystemClock.sleep(ConfigurationSettleMs)
    }

    private fun Instrumentation.shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { input ->
            input.bufferedReader().readText()
        }

    private data class SectionGapCapture(
        val headingTopPx: Int,
        val headingBottomPx: Int,
        val yellowPixelsInsideHeading: Int
    )

    private companion object {
        private const val FixtureZeroGap = "release-notes-section-gap-zero"
        private const val FixtureEightDpGap = "release-notes-section-gap-eight"
        private const val FixtureDefaultGap = "release-notes-section-gap-default"
        private const val RenderSettleMs = 2_000L
        private const val ConfigurationSettleMs = 800L
        private const val HeadingPollAttempts = 20
        private const val HeadingPollIntervalMs = 250L
        private const val MaximumClearedPixels = 2
        private const val YellowRedMin = 245
        private val YellowGreenRange = 205..245
        private const val YellowBlueMax = 220
    }
}
