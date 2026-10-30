package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task #164, stdrc's O2: the read-warehouse stale notice (info PanelNotice with the pending
 * spinner) directly under the head of the phone Activity tab (above the filters) and of the phone
 * Home (above the list), light and dark. The rows and their counts are the ordinary fixtures,
 * unchanged: the notice is the only difference from the plain captures.
 *
 * Top 470dp of a 390dp-wide phone, enough for the head, the notice and the first rows.
 * Goldens: `src/androidUnitTest/screenshots/<name>.png`; record with `:shared:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h470dp-xxhdpi")
class ReadWarehouseStaleNoticeScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun activityLight() = captureActivity("activity.rw-stale-notice", RaftAppAppearance.Light)

    @Test fun activityDark() = captureActivity("activity.rw-stale-notice.dark", RaftAppAppearance.Dark)

    @Test fun homeLight() = captureHome("home.rw-stale-notice", RaftAppAppearance.Light)

    @Test fun homeDark() = captureHome("home.rw-stale-notice.dark", RaftAppAppearance.Dark)

    private fun captureActivity(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = ReportDir, activities = activities) {
            val pager = remember { visualPager() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                // The app root's design-system theme, as in the running app.
                RaftDesignSystemTheme { RaftActivityTabVisualContent(stale = true) }
            }
        }

    private fun captureHome(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = ReportDir, activities = activities) {
            val pager = remember { visualPager() }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                HomeChannelListVisualContent(unreadStale = true)
            }
        }

    private fun visualPager(): Pager =
        object : Pager() {}.apply {
            pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
        }

    private companion object {
        const val ReportDir = "build/reports/home-screenshots"
    }
}
