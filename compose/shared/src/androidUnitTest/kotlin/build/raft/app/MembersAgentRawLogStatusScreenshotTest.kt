package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.parseSharedAgentActivity
import build.raft.app.members.MembersAgentActivityRow
import build.raft.app.model.MemberId
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The agent Activity › Raw log's status rows (Kirby, raft-design): the status on the title line,
 * its explanation on its own line in the detail style every other row uses, and a status with
 * no explanation on one line; Stopped and Crashed carry their reason. Thinking and Output rows
 * sit beside them for comparison. Light and dark at phone width, and light in zh-CN
 * (translated status words). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class MembersAgentRawLogStatusScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun rawLogStatusRowsLight() = capture("members.agent-activity.raw-log-status", RaftAppAppearance.Light)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply {
                        pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                    }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalInspectionMode provides true) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) { RawLog() }
                }
            }
        }
    }

    @Composable
    private fun RawLog() {
        val log = remember { rawLogStatusFixtureLog() }
        Column(Modifier.padding(16.dp)) {
            log.forEachIndexed { index, entry ->
                MembersAgentActivityRow(entry, SubagentsFixtureTimeContext, onOpenUrl = {}, last = index == log.lastIndex)
            }
        }
    }
}

/**
 * A raw log in the daemon's wire shape: Thinking, "Working" with "Tool finished", Output,
 * "Error" with "No runtime output for 15 min", "Idle" with no explanation, "Stopped" with
 * "Computer stopped" and "Crashed" with "exit code 1" (each reason alone, no " - " lead-in).
 */
internal fun rawLogStatusFixtureLog(): List<AgentActivityEntry> {
    val t0 = SubagentsFixtureNowMillis - 20 * 60_000L
    fun row(seconds: Int, entry: String) = """{"timestamp":${t0 + seconds * 1000L},"entry":$entry}"""
    val rows = listOf(
        row(0, """{"kind":"thinking","text":"Checking which call sites still read the old thread id."}"""),
        row(12, """{"kind":"status","activity":"working","detail":"Tool finished","detailKind":"tool_end"}"""),
        row(20, """{"kind":"text","text":"Found 3 call sites in packages/web."}"""),
        row(40, """{"kind":"status","activity":"error","detail":"No runtime output for 15 min"}"""),
        row(55, """{"kind":"status","activity":"online","detail":""}"""),
        row(70, """{"kind":"status","activity":"offline","detail":"Computer stopped","detailKind":"stopped"}"""),
        row(90, """{"kind":"status","activity":"offline","detail":"Crashed (exit code 1)","detailKind":"runtime_crashed"}"""),
    )
    return parseSharedAgentActivity(rows.joinToString(",", "[", "]"), MemberId("agent-raw-log-status"))!!.activity
}
