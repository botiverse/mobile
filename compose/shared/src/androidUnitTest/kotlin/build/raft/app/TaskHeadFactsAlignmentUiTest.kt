package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.taskStatusOptions
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The head's facts line up (artin 2026-11-19, a tablet: Source and Earlier discussion sat lower
 * than the facts beside them, a 48dp touch box making them taller). Every fact's value has one
 * height ([TaskHeadContent]'s facts), so on each line of the wrapping row the labels share one top
 * and one height, whatever the value (chip, face and name, link, plain text).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskHeadFactsAlignmentUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun wide() = assertFactsLineUp(sameLine = listOf("Status", "Assignee", "Source"))

    @Test
    @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun tabletUpright() = assertFactsLineUp(sameLine = listOf("Status", "Assignee", "Source"))

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun phone() = assertFactsLineUp(sameLine = emptyList())

    private fun assertFactsLineUp(sameLine: List<String>) =
        runRobolectricComposeUiTest {
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp)) {
                        TaskHeadContent(model = FactsModel, onOpenSource = {}, onOpenEarlierDiscussion = {}, onOpenCreator = {})
                    }
                }
            }
            val labels = Labels.associateWith { onNodeWithText(it, useUnmergedTree = true).getBoundsInRoot() }
            val heights = labels.values.map { (it.bottom - it.top).value }
            assertTrue(heights.max() - heights.min() <= 0.5f, "the labels are not one height: $labels")
            // Labels on one line of the row (tops within a label's height) share one top.
            val tolerance = 0.5f
            labels.entries.forEach { (a, ra) ->
                labels.entries.forEach { (b, rb) ->
                    val gap = abs(ra.top.value - rb.top.value)
                    assertTrue(
                        gap <= tolerance || gap >= heights.min(),
                        "$a and $b are on one line but $gap dp apart: ${labels.describe()}",
                    )
                }
            }
            sameLine.forEach { label ->
                assertTrue(
                    abs(labels.getValue(label).top.value - labels.getValue(sameLine.first()).top.value) <= tolerance,
                    "$label is not on ${sameLine.first()}'s line: ${labels.describe()}",
                )
            }
        }

    private fun Map<String, DpRect>.describe(): String =
        entries.joinToString { (k, r) -> "$k@${r.left.value},${r.top.value}" }
}

private val Labels = listOf("Status", "Assignee", "Source", "Created by", "Created at", "Earlier discussion")

/** Every kind of fact: chips, a link with a face, a face and name, mono text, a plain link. */
private val FactsModel =
    TaskConversationHeadModel(
        bar =
            TaskConversationHeaderBarModel(
                status = TaskStatus.InProgress,
                statusOptions = taskStatusOptions(TaskStatus.InProgress, v2Task = true),
                statusUpdating = false,
                assigneeName = "Lagoon",
                assigneeEditable = true,
                followed = true,
                followPending = false,
            ),
        title = "Task detail parity with the web",
        description = null,
        source =
            TaskHeadSource(
                authorName = "artin",
                authorKind = MemberKind.Human,
                authorAvatarWireValue = null,
                preview = "The app's task detail lacks a lot compared with web",
                time = "09:12",
                authorId = "user-artin",
            ),
        createdBy = "artin",
        creator = TaskHeadPerson(MemberKind.Human, "user-artin"),
        avatarServerId = "server-1",
        createdAt = TaskHeadDateTime(2026, 10, 28, 9, 14),
        earlierDiscussion = TaskHeadEarlierDiscussion(replyCount = 4),
        history = TaskHeadHistory.Rows(emptyList()),
    )
