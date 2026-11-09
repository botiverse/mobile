package build.raft.app.shared

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import build.raft.app.R
import build.raft.app.RaftAppAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The window theme follows the app's light/dark choice (RaftAndroidNightMode): the choice becomes
 * the configuration's night bits, and Theme.Raft resolves a light or a dark platform theme from
 * them, so WebViews (`prefers-color-scheme` from android:isLightTheme), system dialogs and bar
 * icons match the app. Theme.Raft was a light-only theme, so pages opened in the app stayed light
 * in the dark theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftAndroidNightModeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun lightAndDarkPinTheNightBitsAndSystemFollowsTheDevice() {
        val darkDevice = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        val lightDevice = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
        assertEquals(Configuration.UI_MODE_NIGHT_NO, raftNightBits(RaftAppAppearance.Light, darkDevice))
        assertEquals(Configuration.UI_MODE_NIGHT_YES, raftNightBits(RaftAppAppearance.Dark, lightDevice))
        assertEquals(Configuration.UI_MODE_NIGHT_YES, raftNightBits(RaftAppAppearance.System, darkDevice))
        assertEquals(Configuration.UI_MODE_NIGHT_NO, raftNightBits(RaftAppAppearance.System, lightDevice))
        assertEquals(
            Configuration.UI_MODE_NIGHT_NO,
            raftNightBits(RaftAppAppearance.System, Configuration.UI_MODE_NIGHT_UNDEFINED),
        )
    }

    @Test
    fun onlyTheNightBitsChange() {
        val desk = Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_NO
        assertEquals(
            Configuration.UI_MODE_TYPE_DESK or Configuration.UI_MODE_NIGHT_YES,
            raftUiModeWithNight(desk, Configuration.UI_MODE_NIGHT_YES),
        )
        assertEquals(desk, raftUiModeWithNight(desk, Configuration.UI_MODE_NIGHT_NO))
    }

    @Test
    fun applicationNightModeFollowsTheChoice() {
        assertEquals(UiModeManager.MODE_NIGHT_NO, raftApplicationNightMode(RaftAppAppearance.Light))
        assertEquals(UiModeManager.MODE_NIGHT_YES, raftApplicationNightMode(RaftAppAppearance.Dark))
        // AUTO clears the app's override: the device setting applies.
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, raftApplicationNightMode(RaftAppAppearance.System))
    }

    @Test
    @Config(sdk = [29, 30, 34], qualifiers = "notnight")
    fun lightModeWindowThemesAreLight() {
        windowThemes.forEach { style -> assertWindowTheme(style, dark = false) }
    }

    @Test
    @Config(sdk = [29, 30, 34], qualifiers = "night")
    fun darkModeWindowThemesAreDark() {
        windowThemes.forEach { style -> assertWindowTheme(style, dark = true) }
    }

    /**
     * The themes a window showing pages has: the manifest's and the hosts' setTheme. Below API 31
     * Theme.Raft.AppShellStarting is the compat splash, which hands over to Theme.Raft
     * (postSplashScreenTheme) before any page shows.
     */
    private val windowThemes: List<Int>
        get() =
            listOfNotNull(
                R.style.Theme_Raft,
                R.style.Theme_Raft_Translucent,
                R.style.Theme_Raft_AppShellStarting.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
            )

    private val themeAttrs =
        intArrayOf(
            android.R.attr.isLightTheme,
            android.R.attr.windowLightStatusBar,
            android.R.attr.windowLightNavigationBar,
            android.R.attr.windowActionBar,
            android.R.attr.windowNoTitle,
            android.R.attr.forceDarkAllowed,
        ).sortedArray()

    private fun assertWindowTheme(style: Int, dark: Boolean) {
        val label = context.resources.getResourceEntryName(style)
        val theme = context.resources.newTheme().apply { applyStyle(style, true) }
        val array = theme.obtainStyledAttributes(themeAttrs)
        try {
            fun bool(attr: Int, default: Boolean) = array.getBoolean(themeAttrs.indexOf(attr), default)
            assertEquals("$label isLightTheme", !dark, bool(android.R.attr.isLightTheme, dark))
            assertEquals("$label windowLightStatusBar", !dark, bool(android.R.attr.windowLightStatusBar, dark))
            assertEquals("$label windowLightNavigationBar", !dark, bool(android.R.attr.windowLightNavigationBar, dark))
            // The shared items stay in both modes.
            assertFalse("$label windowActionBar", bool(android.R.attr.windowActionBar, true))
            assertTrue("$label windowNoTitle", bool(android.R.attr.windowNoTitle, false))
            assertFalse("$label forceDarkAllowed", bool(android.R.attr.forceDarkAllowed, true))
        } finally {
            array.recycle()
        }
        if (style != R.style.Theme_Raft_Translucent) {
            // No light flash behind a dark first frame: the window takes the mode's canvas.
            val canvas = if (dark) 0xFF1B1B19.toInt() else 0xFFFFFAEF.toInt()
            assertEquals("$label windowBackground", canvas, windowBackgroundColor(theme))
        }
    }

    private fun windowBackgroundColor(theme: Resources.Theme): Int {
        val array = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            return array.getColor(0, 0)
        } finally {
            array.recycle()
        }
    }
}
