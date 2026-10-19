package build.raft.app.visual

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import build.raft.app.shared.SlockKuiklyActivity
import build.raft.app.shared.SlockCmpActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class KmpHomeScrollBoundsConnectedTest {
    @Test
    fun homeListStopsAtLastItemAfterRepeatedSwipes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent =
            Intent(context, SlockKuiklyActivity::class.java)
                .putExtra("pageName", SlockCmpActivity.PAGE_HOME)
                .putExtra("pageData", longHomePageData().toString())

        ActivityScenario.launch<SlockKuiklyActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.let { controller ->
                        controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                }
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(2_500)

            repeat(18) {
                swipeUp()
                Thread.sleep(120)
            }
            Thread.sleep(600)
            val bottom = captureScreenshot()

            repeat(5) {
                swipeUp()
                Thread.sleep(120)
            }
            Thread.sleep(600)
            val afterExtraSwipes = captureScreenshot()

            val changedRatio = bottom.changedPixelRatio(afterExtraSwipes)
            bottom.recycle()
            afterExtraSwipes.recycle()

            println("Home scroll bottom extra-swipe changedRatio=$changedRatio")
            assertTrue(
                "Home list kept moving after bottom; changedRatio=$changedRatio",
                changedRatio < 0.01f
            )
        }
    }

    private fun swipeUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.78f
        val endY = metrics.heightPixels * 0.28f
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0))
        val steps = 8
        repeat(steps) { index ->
            val eventTime = downTime + ((index + 1) * 16L)
            val fraction = (index + 1).toFloat() / steps.toFloat()
            val y = startY + ((endY - startY) * fraction)
            instrumentation.sendPointerSync(MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime + 160L, MotionEvent.ACTION_UP, x, endY, 0))
        instrumentation.waitForIdleSync()
    }

    private fun captureScreenshot(): Bitmap =
        requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Unable to capture Home scroll screenshot"
        }

    private fun Bitmap.changedPixelRatio(other: Bitmap): Float {
        require(width == other.width && height == other.height) {
            "Bitmap size mismatch: ${width}x$height vs ${other.width}x${other.height}"
        }
        val row = IntArray(width)
        val otherRow = IntArray(width)
        var changed = 0
        var total = 0
        val top = (height * 0.10f).toInt()
        val bottom = (height * 0.90f).toInt()
        for (y in top until bottom) {
            getPixels(row, 0, width, 0, y, width, 1)
            other.getPixels(otherRow, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                if (row[x] != otherRow[x]) changed++
                total++
            }
        }
        return if (total == 0) 1f else changed.toFloat() / total.toFloat()
    }

    private fun longHomePageData(): JSONObject {
        val payload =
            JSONObject()
                .put("instanceId", "scroll-bounds-home")
                .put("currentServerName", "Slock Android")
                .put("currentServerId", "srv_android")
                .put("serverUnreadCount", 42)
                .put("inboxUnreadCount", 9)
                .put("savedCount", 4)
                .put("applyTopSafeArea", false)
                .put("contentRefreshEnabled", false)
                .put("bottomContentPaddingDp", 24)
                .put(
                    "channels",
                    JSONArray().apply {
                        repeat(24) { index ->
                            put(
                                conversation(
                                    id = "channel_$index",
                                    name = "channel-${index.toString().padStart(2, '0')}",
                                    unreadCount = if (index % 4 == 0) index + 1 else 0,
                                    joined = index % 5 != 0
                                )
                            )
                        }
                    }
                )
                .put(
                    "directMessages",
                    JSONArray().apply {
                        repeat(18) { index ->
                            put(
                                conversation(
                                    id = "dm_$index",
                                    name = "Agent ${index.toString().padStart(2, '0')}",
                                    unreadCount = if (index % 3 == 0) index + 1 else 0,
                                    avatarLabel = "A$index",
                                    presence = if (index % 2 == 0) "Online" else "Offline"
                                )
                            )
                        }
                    }
                )
        return JSONObject()
            .put("instanceId", "scroll-bounds-home")
            .put("payload", payload.toString())
    }

    private fun conversation(
        id: String,
        name: String,
        unreadCount: Int,
        avatarLabel: String = name.take(2).uppercase(),
        presence: String? = null,
        joined: Boolean = true
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("unreadCount", unreadCount)
            .put("avatarLabel", avatarLabel)
            .put("joined", joined)
            .apply {
                if (presence != null) put("presence", presence)
            }
}
