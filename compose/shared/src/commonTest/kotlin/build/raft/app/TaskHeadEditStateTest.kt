package build.raft.app

import build.raft.app.data.ScreenError
import build.raft.app.data.TaskAmendPatch
import build.raft.app.data.TaskAmendResult
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The task head's title / description editor (web `TaskHeadFieldEditor`, slock #14114). */
class TaskHeadEditStateTest {
    private val task =
        TaskSummary(
            id = TaskId("task-9"),
            title = "Old title",
            status = TaskStatus.Todo,
            taskNumber = 9,
            description = "Why it matters",
            revision = 7L,
            updatedAtLabel = "now",
        )

    private class Sender(private vararg val answers: TaskAmendResult) {
        val sent = mutableListOf<Pair<TaskAmendPatch, Long?>>()

        suspend fun send(patch: TaskAmendPatch, revision: Long?): TaskAmendResult {
            sent += patch to revision
            return answers[sent.size - 1]
        }
    }

    @Test
    fun aSavedTitleClosesTheEditor() = runTest {
        val sender = Sender(TaskAmendResult.Saved(task.copy(title = "New title", revision = 8L)))
        val edit = TaskHeadEditState()
        edit.open(task, TaskHeadEditField.Title)
        assertEquals("Old title", edit.draft)

        edit.onDraftChange("  New title ")
        edit.save(task, sender::send)

        assertEquals(listOf<Pair<TaskAmendPatch, Long?>>(TaskAmendPatch.Title("New title") to 7L), sender.sent)
        assertNull(edit.field)
        assertNull(edit.problem)
    }

    @Test
    fun aRefusalKeepsTheDraftWithTheServerSentence() = runTest {
        val refusal = ScreenError(code = "tasks_amend_403", retryable = false, serverMessage = "You must join this channel to modify tasks", traceId = "0123456789abcdef0123456789abcdef")
        val sender = Sender(TaskAmendResult.Failed(refusal))
        val edit = TaskHeadEditState()
        edit.open(task, TaskHeadEditField.Description)
        edit.onDraftChange("Rewritten")

        edit.save(task, sender::send)

        assertEquals(TaskHeadEditField.Description, edit.field)
        assertEquals("Rewritten", edit.draft)
        assertEquals(TaskHeadEditProblem.Failed(refusal), edit.problem)
        assertFalse(edit.busy)
    }

    @Test
    fun anEmptyTitleAndAnUnchangedFieldSendNothing() = runTest {
        val sender = Sender()
        val edit = TaskHeadEditState()
        edit.open(task, TaskHeadEditField.Title)
        edit.onDraftChange("   ")
        edit.save(task, sender::send)
        assertEquals(TaskHeadEditProblem.TitleRequired, edit.problem)
        // Typing clears the refusal; the same text again just closes.
        edit.onDraftChange("Old title ")
        assertNull(edit.problem)
        edit.save(task, sender::send)
        assertNull(edit.field)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun aBlankDescriptionIsSentToClearIt() = runTest {
        val sender = Sender(TaskAmendResult.Saved(task.copy(description = null, revision = 8L)))
        val edit = TaskHeadEditState()
        edit.open(task, TaskHeadEditField.Description)
        edit.onDraftChange("  ")
        edit.save(task, sender::send)
        assertEquals(TaskAmendPatch.Description(""), sender.sent.single().first)
        assertNull(edit.field)
    }

    @Test
    fun someoneElsesChangeWarnsFirstThenTheNextSaveReplacesIt() = runTest {
        val sender = Sender(TaskAmendResult.Saved(task.copy(title = "Mine", revision = 9L)))
        val edit = TaskHeadEditState()
        edit.open(task, TaskHeadEditField.Title)
        edit.onDraftChange("Mine")
        val theirs = task.copy(title = "Theirs", revision = 8L)

        edit.save(theirs, sender::send)
        assertEquals(TaskHeadEditProblem.Stale, edit.problem)
        assertTrue(sender.sent.isEmpty())

        edit.save(theirs, sender::send)
        assertEquals(TaskAmendPatch.Title("Mine") to 8L, sender.sent.single())
        assertNull(edit.field)
    }

    @Test
    fun aLostRaceRetriesAgainstTheReportedRevision() = runTest {
        val sender = Sender(TaskAmendResult.Stale(currentRevision = 12L), TaskAmendResult.Saved(task.copy(title = "Mine", revision = 13L)))
        val edit = TaskHeadEditState()
        edit.open(task, TaskHeadEditField.Title)
        edit.onDraftChange("Mine")

        edit.save(task, sender::send)
        assertIs<TaskHeadEditProblem.Stale>(edit.problem)
        assertEquals("Mine", edit.draft)

        edit.save(task, sender::send)
        assertEquals(listOf(7L, 12L), sender.sent.map { it.second })
        assertNull(edit.field)
    }

    @Test
    fun theCreatorOrAnOwnerOrAdminMayDelete() {
        val creator = build.raft.app.model.MemberProfile(build.raft.app.model.MemberId("user-1"), "Ana", "ana", build.raft.app.model.MemberKind.Human)
        val made = task.copy(creator = creator)
        assertTrue(made.canBeDeletedBy("user-1", "member"))
        assertFalse(made.canBeDeletedBy("user-2", "member"))
        assertTrue(made.canBeDeletedBy("user-2", "admin"))
        assertTrue(made.canBeDeletedBy("user-2", "owner"))
        assertFalse(made.copy(readOnlyReason = "historical_joint_task").canBeDeletedBy("user-1", "owner"))
    }

    @Test
    fun onlyAnEditableTaskOffersTheEdit() {
        assertTrue(task.canAmendText())
        assertFalse(task.copy(isLegacy = true).canAmendText())
        assertFalse(task.copy(readOnlyReason = "historical_joint_task").canAmendText())
    }
}
