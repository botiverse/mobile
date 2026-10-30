package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.designsystem.tokens.RaftThemeId
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the whole phone Home root (`screens.home.channel-list`), no emulator:
 * [HomeChannelListVisualContent] (the same generated VisualFixtureData rows the connected
 * capture and the React case render) at the visual-testing viewport, 390x844dp at 3x
 * (1170x2532, like the CI emulator), light and dark.
 *
 * Goldens `src/androidUnitTest/screenshots/<name>.png`, compared, recorded and reported as
 * [RaftRobolectricScreenshot] does (Roborazzi, design-system tolerance): `<name>_actual.png` and
 * `<name>_compare.png` land in `build/reports/home-screenshots/` on a mismatch, and CI uploads
 * them as `shared-home-screenshots` when it fails.
 *
 * Robolectric native graphics, not the device: text rasterises differently from a phone, so
 * these guard Home's own layout and colours against regressions; cross-platform parity stays
 * the connected capture's job.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class HomeChannelListScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val previousAppearance = RaftAppThemeState.appearance
    private val previousRaftTheme = RaftAppThemeState.raftTheme
    private val previousDecor = RaftAppThemeState.decor

    @After
    fun restoreAppearance() {
        RaftAppThemeState.apply(previousAppearance)
        RaftAppThemeState.setRaftTheme(previousRaftTheme, previousDecor)
    }

    @Test
    fun homeChannelListLight() = capture("screens.home.channel-list", RaftAppAppearance.Light)

    @Test
    fun homeChannelListDark() = capture("screens.home.channel-list.dark", RaftAppAppearance.Dark)

    // A server theme worn app-wide (per-server theme step 4): Bots repaints the frame (light blue
    // in light mode) and, in dark mode, the canvas and cards too.
    @Test
    fun homeChannelListBotsLight() =
        capture("screens.home.channel-list.bots", RaftAppAppearance.Light, RaftThemeId.Bots)

    @Test
    fun homeChannelListBotsDark() =
        capture("screens.home.channel-list.bots.dark", RaftAppAppearance.Dark, RaftThemeId.Bots)

    private fun capture(name: String, appearance: RaftAppAppearance, theme: RaftThemeId = RaftThemeId.Raft) {
        RaftAppThemeState.apply(appearance)
        RaftAppThemeState.setRaftTheme(theme)
        val activity = activities.launch()
        // Moko string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(activity)
        val pager =
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
            }
        activity.setContent {
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { HomeChannelListVisualContent() }
        }
        settle()
        val composeView = findComposeRoot(activity.window.decorView)
        assertNotNull(composeView, "no compose root")
        val bitmap = Bitmap.createBitmap(composeView.width, composeView.height, Bitmap.Config.ARGB_8888)
        composeView.draw(android.graphics.Canvas(bitmap))
        verifyOrRecord(name, bitmap)
    }

    private fun verifyOrRecord(name: String, actual: Bitmap) =
        RaftRobolectricScreenshot.verifyOrRecord(name, actual, File("build/reports/home-screenshots"))

    private fun settle() {
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }
    }

    private fun findComposeRoot(view: View): View? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
