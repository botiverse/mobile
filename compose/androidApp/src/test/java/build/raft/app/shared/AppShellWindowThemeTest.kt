package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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
    fun separateServerWindowStartsLikeTheFirstWindow() {
        assertEquals(
            manifestTheme(RaftAppShellActivity::class.java),
            manifestTheme(RaftDocumentShellActivity::class.java),
        )
        // Android 12+: the shell window's own theme is already Theme.Raft, before any setTheme.
        assertNoActionBar("manifest", manifestTheme(RaftDocumentShellActivity::class.java))
    }

    @Test
    fun pushedPagesKeepTheirManifestTheme() {
        assertNull(appShellThemeWithoutHomeSplash(RaftCmpActivity.PAGE_THREAD))
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
