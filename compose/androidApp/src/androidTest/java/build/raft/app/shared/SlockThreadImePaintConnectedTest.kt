package build.raft.app.shared

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import build.raft.app.threadNewerAutoMessageLabel
import build.raft.app.ThreadNewerAutoPaginationVisualFixtureProbe
import java.util.ArrayDeque
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlockThreadImePaintConnectedTest {
    @Test
    fun repeatedImeResizeAndSendKeepVisibleThreadRowsPainted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val intent =
            Intent(instrumentation.targetContext, SlockKuiklyActivity::class.java)
                .putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, PageName)
                .putExtra(
                    SlockKuiklyActivity.KEY_PAGE_DATA,
                    JSONObject()
                        .put("runNonce", nonce)
                        .put("showComposer", true)
                        .toString(),
                )

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            dismissAndroidCompatibilityDialog()
            try {
                waitUntil(RenderTimeoutMs, "initial Thread rows and editable composer") {
                    visibleMessageNodes().isNotEmpty() && editableNode() != null
                }
            } catch (failure: AssertionError) {
                throw AssertionError("${failure.message}; accessibility=${accessibilityTreeSummary()}", failure)
            }

            repeat(ImeCycles) { cycle ->
                val editor = requireNotNull(editableNode())
                assertTrue(
                    "cycle=$cycle composer click failed",
                    editor.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                )
                instrumentation.waitForIdleSync()
                SystemClock.sleep(ImeSettleMs)
                assertVisibleMessagesPainted(cycle, "shown")

                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(ImeSettleMs)
                assertVisibleMessagesPainted(cycle, "hidden")
            }

            repeat(SendCycles) { cycle ->
                val body = "IME-SEND-$cycle"
                val editor = requireNotNull(editableNode())
                assertTrue(
                    "cycle=$cycle set composer text failed",
                    editor.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                body,
                            )
                        },
                    ),
                )
                instrumentation.waitForIdleSync()
                SystemClock.sleep(InputSettleMs)
                tapSendButton(scenario, editor)
                waitUntil(SendTimeoutMs, "sent row $body") {
                    ThreadNewerAutoPaginationVisualFixtureProbe.sentRequestCount(nonce) >= cycle + 1
                }
                SystemClock.sleep(SendSettleMs)
                assertVisibleMessagesPainted(cycle, "sent")
            }
        }
    }

    private fun assertVisibleMessagesPainted(cycle: Int, phase: String) {
        val nodes = visibleMessageNodes()
        assertTrue("cycle=$cycle phase=$phase has no visible message nodes", nodes.isNotEmpty())
        val screenshot =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(),
            )
        val painted = nodes.count { node -> screenshot.hasNonBackgroundPixels(node.bounds) }
        assertTrue(
            "cycle=$cycle phase=$phase visible=${nodes.size} painted=$painted",
            painted > 0,
        )
    }

    private fun visibleMessageNodes(): List<VisibleNode> {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return emptyList()
        val labels =
            (1L..16L).map(::threadNewerAutoMessageLabel) +
                (0 until SendCycles).map { cycle -> "IME-SEND-$cycle" }
        return labels.mapNotNull { label ->
            root.findAccessibilityNodeInfosByText(label)
                .mapNotNull { node ->
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    bounds.takeIf { it.width() > 0 && it.height() > 0 }?.let(::VisibleNode)
                }
                .firstOrNull()
        }
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return null
    }

    private fun tapSendButton(
        scenario: ActivityScenario<SlockKuiklyActivity>,
        editor: AccessibilityNodeInfo,
    ) {
        val editorBounds = Rect().also(editor::getBoundsInScreen)
        var tapX = 0f
        var tapY = 0f
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            tapX = root.width - SendButtonRightInsetDp * density
            tapY = editorBounds.bottom + SendButtonBelowEditorDp * density
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "input touchscreen tap ${tapX.toInt()} ${tapY.toInt()}",
            ),
        ).bufferedReader().use { it.readText() }
        instrumentation.waitForIdleSync()
    }

    private fun dismissAndroidCompatibilityDialog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + CompatibilityDialogWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val button =
                root?.findAccessibilityNodeInfosByText("Don't Show Again")?.firstOrNull()
                    ?: root?.findAccessibilityNodeInfosByText("OK")?.firstOrNull()
            if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(CompatibilityDialogSettleMs)
                return
            }
            SystemClock.sleep(PollMs)
        }
    }

    private fun accessibilityTreeSummary(): String {
        val root =
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                ?: return "<no-root>"
        val entries = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || entries.size >= MaxAccessibilitySummaryEntries) return
            entries +=
                "d=$depth text=${node.text?.toString().orEmpty()} " +
                    "description=${node.contentDescription?.toString().orEmpty()} " +
                    "class=${node.className} editable=${node.isEditable}"
            repeat(node.childCount) { index -> visit(node.getChild(index), depth + 1) }
        }
        visit(root, 0)
        return entries.joinToString(" | ").take(MaxAccessibilitySummaryChars)
    }

    private fun Bitmap.hasNonBackgroundPixels(bounds: Rect): Boolean {
        val left = bounds.left.coerceIn(0, width)
        val top = bounds.top.coerceIn(0, height)
        val right = bounds.right.coerceIn(left, width)
        val bottom = bounds.bottom.coerceIn(top, height)
        var sampled = 0
        for (y in top until bottom step PixelStride) {
            for (x in left until right step PixelStride) {
                val color = getPixel(x, y)
                if (
                    Color.alpha(color) > 0 &&
                    Color.red(color) < BackgroundThreshold &&
                    Color.green(color) < BackgroundThreshold &&
                    Color.blue(color) < BackgroundThreshold
                ) {
                    sampled += 1
                    if (sampled >= MinimumPaintedSamples) return true
                }
            }
        }
        return false
    }

    private fun waitUntil(timeoutMs: Long, description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(PollMs)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private data class VisibleNode(val bounds: Rect)

    private companion object {
        const val PageName = "compose_thread_newer_auto_pagination_visual"
        const val ImeCycles = 20
        const val SendCycles = 6
        const val ImeSettleMs = 250L
        const val InputSettleMs = 150L
        const val RenderTimeoutMs = 20_000L
        const val SendTimeoutMs = 8_000L
        const val SendSettleMs = 500L
        const val CompatibilityDialogWaitMs = 2_000L
        const val CompatibilityDialogSettleMs = 250L
        const val PollMs = 100L
        const val PixelStride = 2
        const val BackgroundThreshold = 235
        const val MinimumPaintedSamples = 3
        const val SendButtonRightInsetDp = 28f
        const val SendButtonBelowEditorDp = 24f
        const val MaxAccessibilitySummaryEntries = 80
        const val MaxAccessibilitySummaryChars = 8_000
    }
}
