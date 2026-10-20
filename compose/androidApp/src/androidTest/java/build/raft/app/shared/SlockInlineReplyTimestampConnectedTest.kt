package build.raft.app.shared

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockInlineReplyTimestampConnectedTest {
    @Test
    fun replyTimestampTapOpensParentWithoutInstallingToggleSemantics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockCmpActivity::class.java)
                .putExtra("pageName", "compose_thread_message_row_visual")
                .putExtra(
                    "pageData",
                    JSONObject()
                        .put("instanceId", "inline-reply-timestamp-parent-owner")
                        .put("visualFixture", "inline_replies")
                        .put("interactionProbe", "reply-timestamp-owner")
                        .toString()
                )
                .putExtra(SlockCmpActivity.KEY_SUPPRESS_SOFT_KEYBOARD, true)

        ActivityScenario.launch<SlockCmpActivity>(intent).use { scenario ->
            instrumentation.dismissCompatibilityDialogIfPresent()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RenderSettleMs)

            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val normalTimestamp = root.findNode { node ->
                node.contentDescription?.toString() == MessageTimestampDescription
            }
            assertTrue(
                "Normal message timestamp remains an independent toggle control",
                normalTimestamp.isClickable
            )
            val normalBounds = Rect().also(normalTimestamp::getBoundsInScreen)
            instrumentation.tapScreenPoint(normalBounds.exactCenterX(), normalBounds.exactCenterY())
            SystemClock.sleep(TapSettleMs)
            val toggledRoot = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            assertFalse(
                "Ordinary message timestamp must retain relative/absolute toggling; " +
                    "bounds=$normalBounds tree=${toggledRoot.accessibilitySummary()}",
                toggledRoot.anyNode { node ->
                    node.contentDescription?.toString() == MessageTimestampDescription
                }
            )

            val replySurface = toggledRoot.findNode { node ->
                node.contentDescription?.toString()?.startsWith(ReplySurfacePrefix) == true
            }
            val replyDescription = requireNotNull(replySurface.contentDescription).toString()
            assertFalse(
                "Production reply timestamps must be parseable under the fixture's live context; " +
                    "description=$replyDescription",
                UnknownTimestampLabel in replyDescription,
            )
            val timestampHitTarget = toggledRoot.findNode { node ->
                node.contentDescription?.toString() == ReplyTimestampHitTargetDescription
            }
            val timestampBounds = Rect().also(timestampHitTarget::getBoundsInScreen)
            val replyBounds = Rect().also(replySurface::getBoundsInScreen)
            assertTrue(
                "Measured timestamp bounds must be non-empty and contained by the reply surface; " +
                    "timestamp=$timestampBounds reply=$replyBounds",
                !timestampBounds.isEmpty && replyBounds.contains(timestampBounds),
            )
            assertFalse(
                "Measured reply timestamp bounds must not expose an independent Button target",
                toggledRoot.anyNode { node ->
                    if (node.className?.toString()?.contains("Button", ignoreCase = true) != true) {
                        false
                    } else {
                        val nodeBounds = Rect().also(node::getBoundsInScreen)
                        Rect.intersects(nodeBounds, timestampBounds)
                    }
                }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                assertNull(
                    "Reply timestamp must not expose relative/absolute state semantics",
                    replySurface.stateDescription
                )
            }
            val beforeYellowPixels = scenario.captureRoot().countPixelsNear(BrandYellow)
            instrumentation.tapScreenPoint(
                x = timestampBounds.exactCenterX(),
                y = timestampBounds.exactCenterY(),
            )
            SystemClock.sleep(TapSettleMs)

            val afterRoot = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            assertTrue(
                "Reply timestamp must not toggle its own display mode",
                afterRoot.anyNode { node ->
                    node.contentDescription?.toString() == replyDescription
                }
            )
            val afterYellowPixels = scenario.captureRoot().countPixelsNear(BrandYellow)
            assertTrue(
                "Tapping the reply timestamp must reach the gray preview's open-thread callback; " +
                    "bounds=$timestampBounds yellowBefore=$beforeYellowPixels yellowAfter=$afterYellowPixels",
                afterYellowPixels - beforeYellowPixels >= MinOpenMarkerPixels
            )
        }
    }

    private fun AccessibilityNodeInfo.findNode(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo {
        if (predicate(this)) return this
        repeat(childCount) { index ->
            val child = getChild(index) ?: return@repeat
            runCatching { child.findNode(predicate) }.getOrNull()?.let { return it }
        }
        error("Missing accessibility node; tree=${accessibilitySummary()}")
    }

    private fun AccessibilityNodeInfo.anyNode(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (predicate(this)) return true
        repeat(childCount) { index ->
            if (getChild(index)?.anyNode(predicate) == true) return true
        }
        return false
    }

    private fun AccessibilityNodeInfo.accessibilitySummary(): String {
        val rows = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()?.takeIf(String::isNotBlank)
            val description = node.contentDescription?.toString()?.takeIf(String::isNotBlank)
            if (text != null || description != null || node.isClickable) {
                rows +=
                    "text=$text desc=$description class=${node.className} " +
                        "clickable=${node.isClickable} state=${
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.stateDescription else null
                        }"
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(::visit)
            }
        }
        visit(this)
        return rows.joinToString(" | ")
    }

    private fun Instrumentation.tapScreenPoint(x: Float, y: Float) {
        runShellInput("input tap ${x.toInt()} ${y.toInt()}")
        waitForIdleSync()
    }

    private fun Instrumentation.runShellInput(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { input ->
            input.readBytes()
        }
    }

    private fun Instrumentation.dismissCompatibilityDialogIfPresent() {
        repeat(5) {
            val dismiss =
                uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Don't Show Again")
                    ?.firstOrNull()
            if (dismiss?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                waitForIdleSync()
                SystemClock.sleep(300L)
                return
            }
            SystemClock.sleep(200L)
        }
    }

    private fun ActivityScenario<SlockCmpActivity>.captureRoot(): Bitmap {
        var bitmap: Bitmap? = null
        onActivity { activity ->
            val root = activity.window.decorView.rootView
            require(root.width > 0 && root.height > 0)
            bitmap =
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { output ->
                    root.draw(Canvas(output))
                }
        }
        return requireNotNull(bitmap)
    }

    private fun Bitmap.countPixelsNear(target: Int): Int {
        val row = IntArray(width)
        var count = 0
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                if (
                    Color.alpha(pixel) >= 220 &&
                    kotlin.math.abs(Color.red(pixel) - Color.red(target)) <= 12 &&
                    kotlin.math.abs(Color.green(pixel) - Color.green(target)) <= 12 &&
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(target)) <= 12
                ) {
                    count += 1
                }
            }
        }
        recycle()
        return count
    }

    private companion object {
        private const val RenderSettleMs = 2_000L
        private const val TapSettleMs = 600L
        private const val MinOpenMarkerPixels = 1_000
        private const val ReplySurfacePrefix = "8 replies"
        private const val UnknownTimestampLabel = "unknown time"
        private const val ReplyTimestampHitTargetDescription = "Inline reply timestamp hit target"
        private const val MessageTimestampDescription = "Exact time, 18:05"
        private val BrandYellow = Color.rgb(255, 212, 64)
    }
}
