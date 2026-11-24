package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import build.raft.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A server switch with "Multiple app windows" on opens the other server in its own window
 * (RaftDocumentShellActivity), keeping the current tab (#3893). Opened on a tab other than Home, the
 * shell skips the cold-start splash, and its splash-derived theme kept the platform action bar: a
 * grey "Raft" title bar above the shell and an empty yellow band under it (Android 1.17.1). Every
 * app shell window, first or second, Home or not, has no action bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppShellWindowThemeTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [29, 30, 34])
    fun appShellOpenedOnAnotherTabHasNoActionBar() {
        assertTrue(shouldInstallHomeColdStartSplash(RaftCmpActivity.PAGE_APP_SHELL, null))
        assertFalse(
            shouldInstallHomeColdStartSplash(RaftCmpActivity.PAGE_APP_SHELL, """{"slockInitialTab":"activity"}""")
        )
        val theme = appShellThemeWithoutHomeSplash(RaftCmpActivity.PAGE_APP_SHELL)
        assertEquals(R.style.Theme_Raft, theme)
        for (host in listOf(RaftAppShellActivity::class.java, RaftDocumentShellActivity::class.java)) {
            assertNoActionBar(host.simpleName, manifestTheme(host), theme!!)
        }
    }

    @Test
    @Config(sdk = [29, 30, 34])
    fun raftThemeDrawsIntoDisplayCutoutForActivitiesAndDialogs() {
        val expectedMode =
            if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_Raft, true) }
        val array = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowLayoutInDisplayCutoutMode))
        try {
            assertEquals(expectedMode, array.getInt(0, WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT))
        } finally {
            array.recycle()
        }
    }

    private fun manifestTheme(host: Class<out Activity>): Int =
        context.packageManager.getActivityInfo(ComponentName(context, host), PackageManager.GET_META_DATA).theme

    private fun assertNoActionBar(label: String, vararg styles: Int) {
        val theme = context.resources.newTheme().apply { styles.forEach { applyStyle(it, true) } }
        val array =
            theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowActionBar, android.R.attr.windowNoTitle))
        try {
            assertFalse("$label windowActionBar", array.getBoolean(0, true))
            assertTrue("$label windowNoTitle", array.getBoolean(1, false))
        } finally {
            array.recycle()
        }
    }
}
