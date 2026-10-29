package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Matrix
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import build.raft.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pages stacked over Home (channel, DM, thread, settings detail) open with a horizontal push and
 * close to the right. Their host window is translucent (Theme.Raft.Translucent), and Android swaps
 * the platform's default activity animations for the translucent pair, which slides up from the
 * bottom like a sheet (Android 1.16.1: a DM opened from Home rose from below). The host theme
 * names app-owned animations so the platform has nothing to swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftPagePushTransitionTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun conversationPagesOpenInAHostWhoseThemePushesHorizontally() {
        // Channel, DM and thread conversations all open as PAGE_THREAD, from Home, Activity,
        // search, links and notifications alike (Pager.openRaftPage -> RaftCmpActivity.start).
        val conversationHost = raftHostActivityClass(RaftCmpActivity.PAGE_THREAD)
        val otherPushedPageHost = raftHostActivityClass(RaftCmpActivity.PAGE_SETTINGS_STORAGE)
        for (host in listOf(conversationHost, otherPushedPageHost, RaftLegacyHostActivity::class.java)) {
            assertEquals(
                host.simpleName,
                PagePushAnimations(
                    openEnter = R.anim.raft_page_push_enter,
                    openExit = R.anim.raft_page_push_hold,
                    closeEnter = R.anim.raft_page_push_hold,
                    closeExit = R.anim.raft_page_pop_exit,
                ),
                pagePushAnimations(manifestTheme(host)),
            )
        }
    }

    @Test
    fun pushEnterSlidesInFromTheRightAndPopExitLeavesToTheRight() {
        val width = 1080
        val height = 2400
        assertEquals(Offset(width.toFloat(), 0f), offsetAt(R.anim.raft_page_push_enter, width, height, fraction = 0f))
        assertEquals(Offset(0f, 0f), offsetAt(R.anim.raft_page_push_enter, width, height, fraction = 1f))
        assertEquals(Offset(0f, 0f), offsetAt(R.anim.raft_page_pop_exit, width, height, fraction = 0f))
        assertEquals(Offset(width.toFloat(), 0f), offsetAt(R.anim.raft_page_pop_exit, width, height, fraction = 1f))
        // The page underneath stays put while the top page slides over it.
        for (fraction in listOf(0f, 0.5f, 1f)) {
            assertEquals(Offset(0f, 0f), offsetAt(R.anim.raft_page_push_hold, width, height, fraction))
        }
    }

    @Test
    fun dialogAndSheetWindowsKeepTheirWindowAnimations() {
        // RaftWindowDialog windows inherit the host theme; only activity open/close changed.
        val attrs = intArrayOf(android.R.attr.windowEnterAnimation, android.R.attr.windowExitAnimation)
        val pageHost = windowAnimationIds(R.style.Theme_Raft_Translucent, attrs)
        val baseTheme = windowAnimationIds(R.style.Theme_Raft, attrs)
        assertEquals(baseTheme, pageHost)
        assertNotEquals(
            windowAnimationStyle(R.style.Theme_Raft),
            windowAnimationStyle(R.style.Theme_Raft_Translucent),
        )
    }

    private data class PagePushAnimations(val openEnter: Int, val openExit: Int, val closeEnter: Int, val closeExit: Int)

    private data class Offset(val x: Float, val y: Float)

    private fun manifestTheme(host: Class<out Activity>): Int =
        context.packageManager.getActivityInfo(ComponentName(context, host), PackageManager.GET_META_DATA).theme

    private fun windowAnimationStyle(themeRes: Int): Int {
        val theme = context.resources.newTheme().apply { applyStyle(themeRes, true) }
        val array = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowAnimationStyle))
        return try {
            array.getResourceId(0, Resources.ID_NULL)
        } finally {
            array.recycle()
        }
    }

    private fun windowAnimationIds(themeRes: Int, attrs: IntArray): List<Int> {
        val style = windowAnimationStyle(themeRes)
        val array = context.obtainStyledAttributes(style, attrs)
        return try {
            attrs.indices.map { array.getResourceId(it, Resources.ID_NULL) }
        } finally {
            array.recycle()
        }
    }

    private fun pagePushAnimations(themeRes: Int): PagePushAnimations {
        val ids =
            windowAnimationIds(
                themeRes,
                intArrayOf(
                    android.R.attr.activityOpenEnterAnimation,
                    android.R.attr.activityOpenExitAnimation,
                    android.R.attr.activityCloseEnterAnimation,
                    android.R.attr.activityCloseExitAnimation,
                ),
            )
        return PagePushAnimations(ids[0], ids[1], ids[2], ids[3])
    }

    private fun offsetAt(animRes: Int, width: Int, height: Int, fraction: Float): Offset {
        val animation = AnimationUtils.loadAnimation(context, animRes)
        animation.initialize(width, height, width, height)
        animation.startTime = 0L
        val transformation = Transformation()
        animation.getTransformation((animation.duration * fraction).toLong(), transformation)
        val values = FloatArray(9)
        transformation.matrix.getValues(values)
        return Offset(values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y])
    }
}
