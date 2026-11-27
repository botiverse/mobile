package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.ThreadMessageSourceTaskItem
import build.raft.app.thread.ThreadOriginalPullState
import build.raft.app.thread.ThreadTaskSummary
import build.raft.app.thread.ThreadTaskSummaryParts
import build.raft.app.thread.appendThreadTaskSummarySubtitle
import build.raft.app.thread.foldCompletedTasks
import build.raft.app.thread.summarizeThreadTasks
import build.raft.app.thread.threadOriginalPullDownAttachmentFallback
import build.raft.app.thread.threadOriginalPullPressIsOutside
import build.raft.app.thread.threadTaskSummaryParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadOriginalPullDownTest {
    private fun task(
        id: String,
        status: TaskStatus,
        assigneeIsViewer: Boolean = false,
        assigneeKind: MemberKind? = MemberKind.Human,
    ): ThreadMessageSourceTaskItem =
        ThreadMessageSourceTaskItem(
            taskId = id,
            taskNumber = id.removePrefix("t").toIntOrNull(),
            title = "Task $id",
            status = status,
            replyCount = 0,
            assigneeId = if (assigneeKind != null) "member-$id" else null,
            assigneeKind = assigneeKind,
            assigneeName = if (assigneeKind != null) "Member $id" else null,
            assigneeIsViewer = assigneeIsViewer,
        )

    @Test
    fun foldCompletedTasksReturnsNullWhenEmptyAllOpenOrAllCompleted() {
        assertNull(foldCompletedTasks(emptyList()))
        assertNull(
            foldCompletedTasks(
                listOf(
                    task("t1", TaskStatus.Backlog),
                    task("t2", TaskStatus.Todo),
                    task("t3", TaskStatus.InProgress),
                    task("t4", TaskStatus.InReview),
                )
            )
        )
        assertNull(
            foldCompletedTasks(
                listOf(
                    task("t1", TaskStatus.Done),
                    task("t2", TaskStatus.Closed),
                )
            )
        )
    }

    @Test
    fun foldCompletedTasksSplitsOpenAndDoneClosedPreservingOrder() {
        val folded =
            assertNotNull(
                foldCompletedTasks(
                    listOf(
                        task("t1", TaskStatus.Done),
                        task("t2", TaskStatus.InProgress),
                        task("t3", TaskStatus.Closed),
                        task("t4", TaskStatus.Backlog),
                        task("t5", TaskStatus.Todo),
                        task("t6", TaskStatus.InReview),
                    )
                )
            )
        assertEquals(listOf("t2", "t4", "t5", "t6"), folded.open.map { it.taskId })
        assertEquals(listOf("t1", "t3"), folded.completed.map { it.taskId })
    }

    @Test
    fun summarizeThreadTasksCountsOpenTotalAndWaitingWhenEnabled() {
        val tasks =
            listOf(
                task("t1", TaskStatus.Todo, assigneeIsViewer = true),
                task("t2", TaskStatus.InProgress, assigneeIsViewer = false),
                task("t3", TaskStatus.InReview, assigneeIsViewer = true),
                task("t4", TaskStatus.Backlog, assigneeIsViewer = true),
                task("t5", TaskStatus.Done, assigneeIsViewer = true),
                task("t6", TaskStatus.Closed, assigneeIsViewer = true),
            )
        assertEquals(
            ThreadTaskSummary(open = 4, total = 6, waitingOnYou = 2),
            summarizeThreadTasks(tasks, waitingShown = true),
        )
        assertEquals(
            ThreadTaskSummary(open = 4, total = 6, waitingOnYou = 0),
            summarizeThreadTasks(tasks, waitingShown = false),
        )
        assertEquals(
            ThreadTaskSummary(open = 0, total = 0, waitingOnYou = 0),
            summarizeThreadTasks(emptyList(), waitingShown = true),
        )
    }

    @Test
    fun threadTaskSummaryPartsCoversZeroOneManyAllDoneAndWaiting() {
        fun format(summary: ThreadTaskSummary): ThreadTaskSummaryParts =
            threadTaskSummaryParts(
                summary = summary,
                formatOneTask = { "1 task" },
                formatManyTasks = { "$it tasks" },
                formatAllDone = { "All tasks done" },
                formatWaitingOnYou = { "$it waiting on you" },
            )

        assertEquals(ThreadTaskSummaryParts(null, null), format(ThreadTaskSummary(0, 0, 0)))
        assertEquals(ThreadTaskSummaryParts("1 task", null), format(ThreadTaskSummary(1, 3, 0)))
        assertEquals(ThreadTaskSummaryParts("2 tasks", "1 waiting on you"), format(ThreadTaskSummary(2, 4, 1)))
        assertEquals(ThreadTaskSummaryParts("All tasks done", null), format(ThreadTaskSummary(0, 3, 0)))
    }

    @Test
    fun appendThreadTaskSummarySubtitleJoinsPresentSegments() {
        assertEquals(
            "#ops · 48 replies · 2 tasks · 1 waiting on you",
            appendThreadTaskSummarySubtitle(
                "#ops · 48 replies",
                ThreadTaskSummaryParts(tasks = "2 tasks", waiting = "1 waiting on you"),
            ),
        )
        assertEquals(
            "#ops · 48 replies · All tasks done",
            appendThreadTaskSummarySubtitle(
                "#ops · 48 replies",
                ThreadTaskSummaryParts(tasks = "All tasks done", waiting = null),
            ),
        )
        assertEquals(
            "#ops · 48 replies",
            appendThreadTaskSummarySubtitle("#ops · 48 replies", ThreadTaskSummaryParts(null, null)),
        )
        assertEquals(
            "1 task",
            appendThreadTaskSummarySubtitle(null, ThreadTaskSummaryParts(tasks = "1 task", waiting = null)),
        )
    }

    @Test
    fun pullStateLifecycleToggleScrollAndOriginReturn() {
        val state = ThreadOriginalPullState()
        assertFalse(state.available)
        assertFalse(state.isOpen)

        // Cannot open while first message is still in view.
        state.toggle()
        assertFalse(state.isOpen)

        // Becoming available never opens automatically.
        state.updateAvailable(true)
        assertTrue(state.available)
        assertFalse(state.isOpen)

        // Toggle opens and closes.
        state.toggle()
        assertTrue(state.isOpen)
        state.toggle()
        assertFalse(state.isOpen)

        // Non-user scroll (e.g. arriving message) and horizontal-only drag do not close.
        state.toggle()
        assertTrue(state.isOpen)
        assertEquals(
            Offset.Zero,
            state.scrollCloseConnection.onPreScroll(Offset(0f, -24f), NestedScrollSource.SideEffect),
        )
        assertTrue(state.isOpen)
        assertEquals(
            Offset.Zero,
            state.scrollCloseConnection.onPreScroll(Offset(20f, 0f), NestedScrollSource.UserInput),
        )
        assertTrue(state.isOpen)

        // User vertical scroll closes without consuming scroll delta.
        assertEquals(
            Offset.Zero,
            state.scrollCloseConnection.onPreScroll(Offset(0f, -12f), NestedScrollSource.UserInput),
        )
        assertFalse(state.isOpen)

        // Origin returning into view closes and stays closed if scrolled away again.
        state.toggle()
        assertTrue(state.isOpen)
        state.updateAvailable(false)
        assertFalse(state.isOpen)
        state.updateAvailable(true)
        assertFalse(state.isOpen)

        // close() reports whether the panel was open.
        assertFalse(state.close())
        state.toggle()
        assertTrue(state.close())
        assertFalse(state.close())
    }

    @Test
    fun outsidePressOnlyTriggersBelowMeasuredPanelBottom() {
        assertFalse(threadOriginalPullPressIsOutside(rootY = 300f, panelBottomInRootPx = 0f))
        assertFalse(threadOriginalPullPressIsOutside(rootY = 40f, panelBottomInRootPx = 220f))
        assertFalse(threadOriginalPullPressIsOutside(rootY = 220f, panelBottomInRootPx = 220f))
        assertTrue(threadOriginalPullPressIsOutside(rootY = 221f, panelBottomInRootPx = 220f))
    }

    @Test
    fun attachmentFallbackDistinguishesImagesFromFiles() {
        assertNull(threadOriginalPullDownAttachmentFallback(emptyList(), "Image", "File"))
        assertEquals(
            "Image",
            threadOriginalPullDownAttachmentFallback(
                listOf(
                    RaftThreadAttachmentItem(id = "a1", name = "shot.png", mimeType = "image/png"),
                    RaftThreadAttachmentItem(id = "a2", name = "photo.jpg", mimeType = "IMAGE/JPEG"),
                ),
                imageLabel = "Image",
                fileLabel = "File",
            ),
        )
        assertEquals(
            "File",
            threadOriginalPullDownAttachmentFallback(
                listOf(
                    RaftThreadAttachmentItem(id = "a1", name = "shot.png", mimeType = "image/png"),
                    RaftThreadAttachmentItem(id = "a2", name = "spec.pdf", mimeType = "application/pdf"),
                ),
                imageLabel = "Image",
                fileLabel = "File",
            ),
        )
    }
}
