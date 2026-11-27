package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.AppLocale
import build.raft.app.data.ThreadFindState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.markdown.LocalRaftMarkdownFindQuery
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.resources.StringDesc
import build.raft.app.thread.ThreadFindBar
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Search in this thread (web `ThreadSearchBar`, slock staging e4c09353f), the bar over the top of
 * the message list, under the header, with its seam below it and the open match under it
 * (`info-soft` row, its words marked):
 * - `thread.find.results` / `.dark`: "launch", 2/3, Previous / Next / Close;
 * - `thread.find.loading`: "Loading" while the whole thread is read;
 * - `thread.find.no-match`: 0/0, Previous / Next disabled;
 * - `thread.find.task-empty`: a task's bar before any words ("Search this task", no count).
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class ThreadFindBarScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType

    @BeforeTest
    fun english() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restore() {
        StringDesc.localeType = previousLocale
    }

    @Test
    fun results() = capture("thread.find.results", RaftAppAppearance.Light, Results)

    @Test
    fun noMatch() =
        capture("thread.find.no-match", RaftAppAppearance.Light, Results.copy(query = "zebra", matches = emptyList(), activeIndex = 0), marked = false)

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        state: ThreadFindState,
        marked: Boolean = true,
        taskSurface: Boolean = false,
    ) = RaftRobolectricScreenshot.capture(name, appearance, reportDir = REPORT_DIR, activities = activities) {
        Host(appearance) {
            Column(Modifier.fillMaxSize()) {
                ThreadFindBar(state = state, taskSurface = taskSurface, onIntent = {}, autoFocus = false)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (marked) RaftColor.InfoSoft else RaftColor.Canvas)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Artin", style = RaftType.Body.withRaftFontWeight(FontWeight.Bold), color = RaftColor.TextBlack)
                        CompositionLocalProvider(LocalRaftMarkdownFindQuery provides state.query.takeIf { marked }) {
                            RaftMarkdownBody(body = "Ship the **launch** checklist before the Launch review, then `launch` it.")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Host(appearance: RaftAppAppearance, content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply {
                    pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) })
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) { content() }
            }
        }
    }

    private companion object {
        const val REPORT_DIR = "build/reports/home-screenshots"

        val Results =
            ThreadFindState(
                threadChannelId = "thread-1",
                open = true,
                query = "launch",
                activeIndex = 1,
                matches = listOf("m-1", "m-2", "m-3"),
            )
    }
}
