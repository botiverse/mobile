package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The task hand-off message row and card in the timeline (web `MessageItem.tsx` +
 * `TaskHandoffCard.tsx`, botiverse/slock #15631): actor's message with "Assigned to",
 * "Assigned back to", "Reassigned from … to …", a hand-off without a reason, and a one-line claim.
 * Reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadTaskHandoffCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun handoffLight() = capture("thread.task-handoff", RaftAppAppearance.Light)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = ThreadTaskHandoffFixtures.xx.id),
                LocalThreadTaskTitlesByNumber provides mapOf(51 to "Ship it"),
            ) {
                RaftDesignSystemTheme {
                    ThreadTaskHandoffFixtures.WithTime {
                        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) { Lines() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Lines() {
    val f = ThreadTaskHandoffFixtures
    val assigned = f.handoff
    val back = f.line("h2", "2026-11-10T09:00:00.000Z", f.xx, f.otter, "Looks good, go ahead.")
    val reassigned = f.line("h3", "2026-11-10T09:30:00.000Z", f.bob, f.xx, "Otter is away", previousAssignee = f.otter)
    val noReason = f.line("h4", "2026-11-10T10:00:00.000Z", f.xx, f.bob, reason = null)
    val took = f.line("t1", "2026-11-10T10:51:00.000Z", f.bob, f.bob)
    val timeline = listOf(assigned, back, reassigned, noReason, took)
    timeline.forEach { ThreadSystemMessageRow(message = it, timestampValue = it.timestampLabel, timeline = timeline) }
}
