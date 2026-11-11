package build.raft.app

import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import build.raft.app.cmp.RaftCmpRoot
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.compat.pager.RaftPageRegistry
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftThemeState
import build.raft.designsystem.tokens.RaftThemeId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #118 (artin): with several Raft windows open, a night -> day switch left a background window
 * half updated: its head and list surface still dark, its row text already the light theme's ink.
 *
 * Android gives each window its own configuration and delivers a uiMode change to a window only
 * while it is visible; a background window keeps the old night bits until it is shown again. The
 * page root read the window's own configuration (isSystemInDarkTheme) for the design-system
 * colours (`RaftTheme.colors`: the rows) and wrote it back into the process-wide theme that the app
 * tokens (`RaftColor`: heads, panel surfaces) and every other window follow. Two windows, a switch
 * delivered to the front one only, as RaftCmpActivity reports it (onConfigurationChanged); each
 * window's root composes as RaftCmpActivity composes it. Every window must wear one mode, the
 * reported one, whatever a background window's configuration still says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftBackgroundWindowThemeTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val previousSystemDark = RaftThemeState.App.systemDark
    private val previousAppearance = RaftAppThemeState.appearance
    private val previousTheme = RaftAppThemeState.raftTheme
    private val previousDecor = RaftAppThemeState.decor

    /** Per window (page name): the page's design-system mode and the app tokens' mode. */
    private val seen = mutableMapOf<String, Pair<Boolean, Boolean>>()

    @AfterTest
    fun restore() {
        RaftAppThemeState.setRaftTheme(previousTheme, previousDecor)
        RaftAppThemeState.onSystemDarkChanged(previousSystemDark)
        RaftAppThemeState.apply(previousAppearance)
        RaftCmpRouter.installPageProvider { pageName -> RaftPageRegistry.content(pageName) }
    }

    @Test
    fun aBackgroundWindowFollowsANightToDaySwitchCompletely() {
        RaftCmpRouter.installPageProvider { pageName -> if (pageName in WINDOWS) ({ Probe(pageName) }) else null }
        RaftAppThemeState.apply(RaftAppAppearance.System)
        // Night: both windows' configurations and the process report (RaftCmpActivity.onCreate).
        RaftAppThemeState.onSystemDarkChanged(true)
        val front = window(FRONT)
        val back = window(BACK)
        front.dispatchUiMode(Configuration.UI_MODE_NIGHT_YES)
        back.dispatchUiMode(Configuration.UI_MODE_NIGHT_YES)
        settle()
        assertEquals(true to true, seen[FRONT])
        assertEquals(true to true, seen[BACK])

        // Day, delivered to the front window only (RaftCmpActivity.onConfigurationChanged); the
        // background window keeps its night configuration.
        front.dispatchUiMode(Configuration.UI_MODE_NIGHT_NO)
        RaftAppThemeState.onSystemDarkChanged(false)
        settle()
        assertEquals(false to false, seen[BACK], "the background window is day, rows and surfaces alike")
        assertEquals(false to false, seen[FRONT])

        // Something re-composes every page root (a window wearing its server's theme, app-wide)
        // while the background window's configuration is still night.
        RaftAppThemeState.setRaftTheme(RaftThemeId.Boring)
        settle()
        assertEquals(false, RaftAppThemeState.isDark, "a stale window does not put night back")
        assertEquals(false to false, seen[FRONT], "the front window stays whole day")
        assertEquals(false to false, seen[BACK])
    }

    @Composable
    private fun Probe(window: String) {
        val pageDark = RaftTheme.isDark
        val tokensDark = RaftAppThemeState.isDark
        SideEffect { seen[window] = pageDark to tokensDark }
    }

    private fun window(pageName: String): View {
        val activity: ComponentActivity = activities.launch()
        val transport = HostBridgeTransport { _, _, _ -> }
        val host = RaftCmpHost(BridgeWireHostCapabilities(transport), NoNavigation, bridgeTransport = transport)
        val args = RaftCmpPageArgs(pageName, JsonObject(emptyMap()))
        // As RaftCmpActivity composes its roots.
        activity.setContent { RaftCmpRoot(args, host, systemDark = RaftThemeState.App.systemDark) }
        settle()
        return activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    }

    /** What the window's ViewRootImpl does when Android delivers the window a configuration. */
    private fun View.dispatchUiMode(night: Int) {
        val config = Configuration(resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        dispatchConfigurationChanged(config)
    }

    private fun settle() {
        repeat(3) { shadowOf(Looper.getMainLooper()).idle() }
    }

    private object NoNavigation : RaftCmpNavigator {
        override fun openPage(pageName: String, pageData: JsonObject) = Unit

        override fun closePage() = Unit
    }

    private companion object {
        const val FRONT = "theme118_front_window"
        const val BACK = "theme118_back_window"
        val WINDOWS = setOf(FRONT, BACK)
    }
}
