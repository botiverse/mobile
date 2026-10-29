package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * artin 10-29 (Compose desktop): the reader's own @mention (yellow sticker) showed an underline
 * beside its label. The chip's padding carried a style-less link that Material3 Text drew as a
 * default link (primary colour + underline); Skia (desktop, iOS) underlined the padding gaps,
 * Android's text layout did not. One message with the reader's @mention, someone else's @mention,
 * a #channel, a thread, a task chip and a plain web link: no chip may show an underline; only the
 * web link keeps its. Latin only (the host has no CJK font). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h400dp-xxhdpi")
class ChipLinkUnderlineScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun chipsHaveNoUnderline() =
        RaftRobolectricScreenshot.capture("markdown.chip-link-underline", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftMarkdownTaskStatuses provides mapOf(92 to TaskStatus.InReview),
            ) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(12.dp)) {
                        RaftMarkdownBody(
                            body = "@artin can you check #general and #general:692d9e88 with @ada, then #92 and see https://example.com/docs for more.",
                            modifier = Modifier.width(360.dp),
                            highlightedMentions = setOf("artin"),
                            isTagValid = { true },
                        )
                    }
                }
            }
        }
}
