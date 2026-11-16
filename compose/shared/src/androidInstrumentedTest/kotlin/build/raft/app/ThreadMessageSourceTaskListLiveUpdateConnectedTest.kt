package build.raft.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.resources.Res
import build.raft.app.resources.thread_author_you
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Suspected scenario of crash c41fd092 (an emulator, 1.24.0 release build): the tasks under a
 * message ([ThreadMessageSourceTaskList], one `RaftTaskCompactRow` per task, the composable the
 * channel/thread page puts under a message) in the message `LazyColumn`, getting live status and
 * assignee updates while rows are reused. That trigger is inferred from a concurrent timeline (another
 * client changed the task 2 s before the crash); it is not proven. Before #4798 the row read its
 * groups' `FirstBaseline` while measuring, which Compose 1.12 mishandles for reused lazy items
 * (RectManager "LayoutNode not found in RectList", androidx fd550bed79). The desktop
 * `RaftTaskCompactRowReuseTest` only shows that measure-time condition on the JVM; it did not
 * reproduce the device crash. This runs the suspected scenario on Android's own Compose runtime
 * (RectManager, lazy prefetch, real frames); whether it reproduces the original crash is decided
 * only by running it with #4798 reverted.
 *
 * Everything is in memory: typed [ThreadMessageSourceTaskItem]s in a snapshot list, an empty
 * avatar resolver (no URLs) and in-memory agent presence flows. No network, server or sign-in.
 *
 * Waits: every step synchronises on Compose idleness ([androidx.compose.ui.test.junit4.ComposeTestRule.runOnIdle]
 * / `waitForIdle`) or advances the test's own frame clock; there is no polling and no sleep.
 */
@RunWith(AndroidJUnit4::class)
class ThreadMessageSourceTaskListLiveUpdateConnectedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class FakeMember(val id: String, val kind: MemberKind, val name: String, val viewer: Boolean = false)

    @Test
    fun taskRowsUnderReusedMessagesFollowLiveStatusAndAssigneeUpdates() {
        val tasks = mutableStateListOf<ThreadMessageSourceTaskItem?>().apply {
            addAll(List(MessageCount) { index -> initialTask(index) })
        }
        val presence = Members.filterNotNull().filter { it.kind == MemberKind.Agent }.associate { it.id to MutableStateFlow(presenceState(0)) }
        val agentStatusResolver: RaftAgentStatusResolver = { id -> id?.let { presence[it] } ?: flowOf(null) }
        val appearance = mutableStateOf(RaftAppearance.Light)
        val listState = LazyListState()
        val labels = mutableMapOf<TaskStatus, String>()
        var youLabel = ""

        compose.setContent {
            RaftTheme(appearance = appearance.value, systemDark = false) {
                Statuses.forEach { labels[it] = it.localizedLabel() }
                youLabel = stringResource(Res.string.thread_author_you)
                LazyColumn(state = listState, modifier = Modifier.width(ListWidth).height(ListHeight).testTag(ListTag)) {
                    items(MessageCount, key = { it }) { index ->
                        Column(Modifier.fillMaxWidth().testTag(messageTag(index))) {
                            BasicText(messageBody(index))
                            ThreadMessageSourceTaskList(
                                tasks = listOfNotNull(tasks[index]),
                                memberAvatarResolver = EmptyRaftMemberAvatarResolver,
                                agentStatusResolver = agentStatusResolver,
                                onOpenTask = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val random = Random(0xC41FD092L)
        val stepPx = with(compose.density) { ScrollStep.toPx() }
        var direction = 1f
        var updates = 0
        var checks = 0

        fun visibleTaskIndices(): List<Int> =
            listState.layoutInfo.visibleItemsInfo.map { it.index }.filter { tasks[it] != null }

        // One live update (status and assignee, sometimes the agent's presence) on a visible row.
        fun updateAVisibleRow() {
            val visible = visibleTaskIndices()
            if (visible.isEmpty()) return
            val index = visible[random.nextInt(visible.size)]
            val task = requireNotNull(tasks[index])
            val member = Members[random.nextInt(Members.size)]
            tasks[index] =
                task.copy(
                    status = Statuses[(Statuses.indexOf(task.status) + 1 + random.nextInt(Statuses.size - 1)) % Statuses.size],
                    assigneeId = member?.id,
                    assigneeKind = member?.kind,
                    assigneeName = member?.name,
                    assigneeIsViewer = member?.viewer == true,
                )
            if (member?.kind == MemberKind.Agent && random.nextInt(4) == 0) {
                presence.getValue(member.id).value = presenceState(random.nextInt(3))
            }
            updates++
        }

        fun assertVisibleRowsShowTheirLatestTask() {
            compose.waitForIdle()
            val visible = compose.runOnIdle { visibleTaskIndices() }
            assertTrue("no task row is on screen", visible.isNotEmpty())
            for (index in visible) {
                val task = requireNotNull(tasks[index])
                val texts =
                    compose
                        .onAllNodes(hasAnyAncestor(hasTestTag(messageTag(index))), useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                        .toSet()
                val shownStatuses = labels.filterValues { it in texts }.keys
                assertEquals("message $index (update $updates) shows status $shownStatuses, not ${task.status}", setOf(task.status), shownStatuses)
                val expectedAssignee = if (task.assigneeIsViewer) youLabel else task.assigneeName
                val shownAssignees = (Members.mapNotNull { it?.name } + youLabel).filter { it in texts }.toSet()
                assertEquals(
                    "message $index (update $updates) shows assignee $shownAssignees, not $expectedAssignee",
                    setOfNotNull(expectedAssignee),
                    shownAssignees,
                )
                checks++
            }
        }

        for (theme in listOf(RaftAppearance.Light, RaftAppearance.Dark)) {
            compose.runOnIdle { appearance.value = theme }
            repeat(StepsPerAppearance) { step ->
                compose.runOnIdle {
                    // Update and scroll in the same frame: a reused row composes while a visible one changes.
                    updateAVisibleRow()
                    if (!listState.canScrollForward && direction > 0f) direction = -1f
                    if (!listState.canScrollBackward && direction < 0f) direction = 1f
                    listState.dispatchRawDelta(direction * stepPx * (0.25f + random.nextFloat() * 1.5f))
                    if (step % 3 == 0) updateAVisibleRow()
                }
                if (step % FlingEvery == FlingEvery - 1) {
                    // A fling driven frame by frame, a live update on every frame of it.
                    compose.mainClock.autoAdvance = false
                    compose.onNodeWithTag(ListTag).performTouchInput { if (direction > 0f) swipeUp() else swipeDown() }
                    repeat(FlingFrames) {
                        compose.runOnUiThread { updateAVisibleRow() }
                        compose.mainClock.advanceTimeByFrame()
                    }
                    compose.mainClock.autoAdvance = true
                }
                if (step % CheckEvery == CheckEvery - 1) assertVisibleRowsShowTheirLatestTask()
            }
            assertVisibleRowsShowTheirLatestTask()
        }

        assertTrue("only $updates live updates ran", updates >= MinimumUpdates)
        assertTrue("only $checks rows were checked", checks > 0)
    }

    private companion object {
        const val MessageCount = 80
        const val StepsPerAppearance = 120
        const val FlingEvery = 15
        const val FlingFrames = 12
        const val CheckEvery = 10
        const val MinimumUpdates = 400
        const val ListTag = "source-task-messages"

        // Narrow (under RaftTaskCompactList's 320dp minimum, as a phone message column is) so the
        // assignee and status group wraps under long titles and long names, and stays on the
        // first line for short ones: updates flip rows between the two layouts.
        val ListWidth = 280.dp
        val ListHeight = 420.dp
        val ScrollStep = 96.dp

        val Statuses = TaskStatus.entries.filter { it != TaskStatus.Unknown }

        // null: unassigned (the row has no assignee part).
        val Members: List<FakeMember?> =
            listOf(
                FakeMember("agent-lagoon", MemberKind.Agent, "Lagoon"),
                FakeMember("agent-harbor", MemberKind.Agent, "Harbor-Pilot-Night-Shift"),
                FakeMember("user-viewer", MemberKind.Human, "Viewer Person", viewer = true),
                FakeMember("user-mira", MemberKind.Human, "Mira Okonkwo-Vasquez"),
                null,
            )

        val Titles =
            listOf(
                "Fix it",
                "Ship the preview build",
                "Investigate why the reused task row under a message crashes on live updates",
                "Docs",
            )

        fun messageTag(index: Int) = "source-task-message-$index"

        fun messageBody(index: Int): String =
            "Message $index " + "with a body that wraps over more lines ".repeat(index % 4)

        // Every seventh message has no task: its slot's content differs when it is reused.
        fun initialTask(index: Int): ThreadMessageSourceTaskItem? {
            if (index % 7 == 3) return null
            val member = Members[index % Members.size]
            return ThreadMessageSourceTaskItem(
                taskId = "task-$index",
                taskNumber = 100 + index,
                title = Titles[index % Titles.size],
                status = Statuses[index % Statuses.size],
                replyCount = index % 4,
                assigneeId = member?.id,
                assigneeKind = member?.kind,
                assigneeName = member?.name,
                assigneeIsViewer = member?.viewer == true,
            )
        }

        // Presence light: none, or one of two colours.
        fun presenceState(variant: Int): RaftAgentStatusIndicatorState? =
            when (variant) {
                0 -> null
                1 -> RaftAgentStatusIndicatorState(status = null, color = Color(0xFF22C55E), label = "", external = false)
                else -> RaftAgentStatusIndicatorState(status = null, color = Color(0xFFF59E0B), label = "", external = false)
            }
    }
}
