package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.TaskAmendResult
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The compact task head (web slock #15639), as the conversation page composes it (its first row,
 * for a reader who may edit the task): with no description, Add a description is an icon button
 * beside the title's pencil, not a row of its own, and opens the description's editor; with one,
 * the description keeps its own pencil. There is no Created at row: Created by carries when the
 * task was created for screen readers (and as its tooltip under a pointer).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TaskHeadCompactUiTest {
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

    private val artin = MemberProfile(id = MemberId("user-artin"), displayName = "artin", handle = "artin", kind = MemberKind.Human)

    private val task =
        TaskSummary(
            id = TaskId("task-41"),
            title = "Compact task head",
            status = TaskStatus.InProgress,
            taskNumber = 41,
            channelId = ChannelId("channel-apps"),
            channelName = "proj-apps",
            creator = artin,
            updatedAtLabel = "2026-11-08T10:30:00.000Z",
            createdAt = "2026-11-08T10:30:00.000Z",
            revision = 3L,
        )

    @Test
    fun withNoDescriptionAddADescriptionSitsBesideTheTitlesPencilAndOpensTheEditor() =
        runRobolectricComposeUiTest {
            val scrollState = TaskHeadScrollState()
            setContent { Host { Head(task, scrollState) } }

            val pencil = onNodeWithTag(TaskHeadTitleEditTestTag, useUnmergedTree = true).getBoundsInRoot()
            val add = onNodeWithTag(TaskHeadAddDescriptionTestTag, useUnmergedTree = true).getBoundsInRoot()
            // An icon button named for screen readers, in the title's row after its pencil.
            onNodeWithContentDescription("Add a description").assertExists()
            assertEquals(pencil.top, add.top, "Add a description is not on the title's line")
            assertTrue(add.left >= pencil.right, "Add a description is not after the title's pencil: pencil=$pencil add=$add")
            // Not a row of its own (the button's words), and no description to edit yet.
            onNodeWithText("Add a description").assertDoesNotExist()
            onNodeWithTag(TaskHeadDescriptionEditTestTag, useUnmergedTree = true).assertDoesNotExist()

            onNodeWithTag(TaskHeadAddDescriptionTestTag, useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(TaskHeadEditField.Description, scrollState.edit.field)
            onNodeWithTag(TaskHeadDescriptionInputTestTag, useUnmergedTree = true).assertExists()
            // While it is open, no button offers another edit.
            onNodeWithTag(TaskHeadAddDescriptionTestTag, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(TaskHeadTitleEditTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun createdByCarriesWhenTheTaskWasCreatedAndThereIsNoCreatedAtRow() =
        runRobolectricComposeUiTest {
            setContent { Host { Head(task, remember { TaskHeadScrollState() }) } }

            onNodeWithText("Created at").assertDoesNotExist()
            // Read after the creator's name, on the node a screen reader stops at.
            onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Created at Nov 8, 2026, 10:30"))
                .assert(hasText("artin"))
            // Web TaskProperties: Created by stays plain text with no avatar face.
            onNodeWithTag(TaskHeadCreatorFaceTestTag, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun statusAndAssigneeSitOnOneRowWithAvatarYouAndSourceNotRecorded() =
        runRobolectricComposeUiTest {
            var openedProfile: TaskHeadPerson? = null
            val assignedTask = task.copy(assignee = artin, sourceRecorded = false, sourceMessageId = null)
            setContent {
                Host {
                    Head(
                        task = assignedTask,
                        scrollState = remember { TaskHeadScrollState() },
                        viewerUserId = "user-artin",
                        onOpenAssigneeProfile = { openedProfile = it },
                    )
                }
            }

            val statusBounds = onNodeWithTag(TaskHeadStatusTestTag, useUnmergedTree = true).getBoundsInRoot()
            val assigneeBounds = onNodeWithTag(TaskHeadAssigneeTestTag, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(
                kotlin.math.abs(statusBounds.top.value - assigneeBounds.top.value) < 12f,
                "Status and Assignee should sit on one row on a 360dp phone: status=$statusBounds assignee=$assigneeBounds",
            )
            assertTrue(
                assigneeBounds.left >= statusBounds.right,
                "Assignee should follow Status on the same row: status=$statusBounds assignee=$assigneeBounds",
            )
            onNodeWithText("You").assertExists()
            onNodeWithContentDescription("Open artin's profile").assertExists()
            onNodeWithText("Source").assertExists()
            onNodeWithText("Not recorded").assertExists()
            onNodeWithTag(TaskHeadAssigneeAvatarTestTag, useUnmergedTree = true).assertExists().performClick()
            waitForIdle()
            assertEquals(TaskHeadPerson(MemberKind.Human, "user-artin"), openedProfile)
        }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    /** The head as the conversation page draws it (its first row), with the title and description editable. */
    @Composable
    private fun Head(
        task: TaskSummary,
        scrollState: TaskHeadScrollState,
        viewerUserId: String? = null,
        onOpenAssigneeProfile: ((TaskHeadPerson) -> Unit)? = null,
    ) {
        Box(Modifier.fillMaxSize()) {
            TaskConversationHeadRoute(
                task = task,
                state = TaskConversationHeaderState(ChannelId("conv-41")),
                details = TaskConversationDetailsState(),
                statusUpdating = false,
                assigneeCandidates = listOf(artin),
                timeContext = null,
                onUpdateStatus = {},
                onAssign = {},
                onOpenSource = {},
                onOpenEarlierDiscussion = {},
                onOpenCreator = {},
                scrollState = scrollState,
                placement = TaskHeadPlacement.FirstRow,
                onAmend = { _, _ -> TaskAmendResult.Stale(currentRevision = null) },
                viewerUserId = viewerUserId,
                onOpenAssigneeProfile = onOpenAssigneeProfile,
            )
        }
    }
}
