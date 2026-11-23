package build.raft.app

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.home_section_create_external_channel
import build.raft.app.resources.resolve
import build.raft.designsystem.tokens.RaftThemeId
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
        // Verify the External section "+" button is actually rendered (its ~14dp glyph alone is
        // smaller than Roborazzi's 0.1% pixel tolerance on a 390x844@3x capture, #wg-mobile-sync:#207).
        val createExternalLabel = Res.string.home_section_create_external_channel.desc().resolve()
        val hasExternalCreateButton =
            (composeView as ViewRootForTest)
                .semanticsOwner
                .getAllSemanticsNodes(mergingEnabled = true)
                .any { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(createExternalLabel) == true
                }
        assertTrue(hasExternalCreateButton, "External section '+' button ($createExternalLabel) must render for owner fixture")
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
