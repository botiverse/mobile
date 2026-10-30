package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.LocalRaftMarkdownTaskStatuses
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * artin 10-30: task ref chips ("#3953", "#3954", ...) lost their side padding on an Android 15+
 * phone and clipped their last digit. Rendered on SDK 35 (Android 15 Minikin, where the bug shows)
 * so the golden carries the real engine's chip widths: unknown 4-digit refs, short refs and status
 * chips, light and dark. Reports land in `build/reports/home-screenshots/`; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h260dp-xxhdpi")
class ThreadMessageRefPillPaddingScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun refPillPaddingLight() = capture("thread.ref-pill-padding", RaftAppAppearance.Light)

    @Test
    fun refPillPaddingDark() = capture("thread.ref-pill-padding.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftMarkdownTaskStatuses provides mapOf(92 to TaskStatus.InReview, 145 to TaskStatus.Done),
            ) {
                RaftDesignSystemTheme {
                    RaftMarkdownBody(
                        body = refPillBody,
                        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(12.dp),
                    )
                }
            }
        }
    }
}

private val refPillBody =
    "Scope: everything on main since 1.17.0, including #3953, #3954, #3967, #3968 and #3980 " +
        "before we cut it.\n\n" +
        "- Short refs #4 and #61 stay padded too\n" +
        "- Status chips #92 and #145 keep their pixel"
