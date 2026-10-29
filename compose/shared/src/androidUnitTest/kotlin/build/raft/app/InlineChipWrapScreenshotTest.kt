package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
 * Inline chips that wrap to the start of a line (artin 10-29, iOS: a task chip "#15244" moved to
 * the next line and left a thin rounded sliver of its box at the end of the previous one). Each
 * body is laid out at a ladder of narrow widths so, at some width, a chip lands right at a line
 * start after CJK and after Latin text: an unknown-task badge, a status task chip, a #channel ref
 * and an @mention. No width may paint a box fragment without its label.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1400dp-xxhdpi")
class InlineChipWrapScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun chipWrapLight() =
        RaftRobolectricScreenshot.capture("markdown.inline-chip-wrap", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftMarkdownTaskStatuses provides mapOf(15245 to TaskStatus.InReview),
            ) {
                RaftDesignSystemTheme { ChipWrapLadder() }
            }
        }
}

private val bodies =
    listOf(
        "以及返工几轮。今天 #15244 周卡在 Unity 里连挂四轮",
        "以及返工几轮。今天 #15245 周卡在 Unity 里",
        "See you all today #15244 is stuck in Unity",
        "以及返工几轮。今天 #general 再说",
        "Ping the team today @alice please",
    )

private val widths = listOf(listOf(92, 98, 104), listOf(110, 116, 122))

@Composable
private fun ChipWrapLadder() {
    Column(
        Modifier.fillMaxSize().background(RaftColor.Canvas).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bodies.forEach { body ->
            widths.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    row.forEach { width -> RaftMarkdownBody(body = body, modifier = Modifier.width(width.dp)) }
                }
            }
        }
    }
}
