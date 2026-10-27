package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
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
import build.raft.app.data.taskStatusOptions
import build.raft.app.model.TaskStatus
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2: the bar under a task conversation's header (web TaskConversationBar + the task's Status
 * and Assignee properties), light and dark, at a 360dp phone width: a member's bar (editable status,
 * assignee, Follow Task), a followed done task (assignee frozen, Unfollow Task) and a read-only bar
 * (guest / historical joint task: plain facts, no chevrons, follow not known yet). Verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h240dp-xxhdpi")
class TaskConversationHeaderBarScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun headerBarLight() = capture("task.conversation.header-bar", RaftAppAppearance.Light)

    @Test
    fun headerBarDark() = capture("task.conversation.header-bar.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(
                        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        HeaderBarCases.forEach { model ->
                            TaskConversationHeaderBar(model = model, onSelectStatus = {}, onOpenAssignee = {}, onToggleFollow = {})
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
}

private val HeaderBarCases =
    listOf(
        TaskConversationHeaderBarModel(
            status = TaskStatus.InProgress,
            statusOptions = taskStatusOptions(TaskStatus.InProgress, canManageServer = false),
            statusUpdating = false,
            assigneeName = "Lagoon",
            assigneeEditable = true,
            followed = false,
            followPending = false,
        ),
        TaskConversationHeaderBarModel(
            status = TaskStatus.Done,
            statusOptions = taskStatusOptions(TaskStatus.Done, canManageServer = false),
            statusUpdating = false,
            assigneeName = null,
            assigneeEditable = false,
            followed = true,
            followPending = false,
        ),
        TaskConversationHeaderBarModel(
            status = TaskStatus.InReview,
            statusOptions = emptyList(),
            statusUpdating = false,
            assigneeName = "Richard Chien",
            assigneeEditable = false,
            followed = null,
            followPending = false,
        ),
    )
