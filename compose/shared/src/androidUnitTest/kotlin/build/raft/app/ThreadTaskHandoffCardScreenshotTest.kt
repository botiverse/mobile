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
 * The task hand-off card in the timeline (web `TaskHandoffCard.tsx`, botiverse/slock v1.21.64,
 * #13927 / #14007 / #14091), light and dark: waiting ("[avatar] @otter → [avatar] @xxchan", its
 * time, the reason), handed back by the assignee, taken back by its actor (both faded), between a
 * one-line assignment notice. Reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class ThreadTaskHandoffCardScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun handoffLight() = capture("thread.task-handoff", RaftAppAppearance.Light)

    @Test fun handoffDark() = capture("thread.task-handoff.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
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
    val waiting = f.handoff
    // Bob hands it to xxchan, who hands it back; xxchan hands another to Bob and takes it back.
    val handedBack = f.line("h2", "2026-11-10T09:00:00.000Z", f.bob, f.xx, "Can you check the numbers before Friday?")
    val back = f.line("b2", "2026-11-10T09:30:00.000Z", f.xx, f.bob)
    val tookBack = f.line("h3", "2026-11-10T10:00:00.000Z", f.xx, f.bob, "Review the release copy, please.")
    val took = f.line("t3", "2026-11-10T10:51:00.000Z", f.xx, f.xx)
    val timeline = listOf(waiting, handedBack, back, tookBack, took)
    timeline.forEach { ThreadSystemMessageRow(message = it, timestampValue = it.timestampLabel, timeline = timeline) }
}
