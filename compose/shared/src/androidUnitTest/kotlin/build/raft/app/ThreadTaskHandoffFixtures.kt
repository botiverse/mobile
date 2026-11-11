package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import build.raft.app.data.TaskAssignmentNotice
import build.raft.app.data.TaskAssignmentNoticeParty
import build.raft.app.data.TaskAssignmentPartyType
import build.raft.app.data.toMessageMetadata
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate

/**
 * The hand-offs of botiverse/slock v1.21.64 `packages/web/tests/taskHandoffNotice.test.ts` (Otter,
 * xxchan, Bob on task #51) as the timeline holds them: system lines whose projected metadata is
 * the line's `actionMetadata` ([toMessageMetadata]).
 */
internal object ThreadTaskHandoffFixtures {
    val otter = TaskAssignmentNoticeParty(TaskAssignmentPartyType.Agent, "6a1d3c5e-7f80-4b9a-8c1d-2e3f40516273", "otter", "Otter")
    val xx = TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "0e1f2a3b-4c5d-4e6f-8a7b-9c0d1e2f3a4b", "xxchan", "xxchan")
    val bob = TaskAssignmentNoticeParty(TaskAssignmentPartyType.User, "1f2a3b4c-5d6e-4f70-8b9c-0d1e2f3a4b5c", "bob", "Bob")

    fun line(id: String, at: String, actor: TaskAssignmentNoticeParty, assignee: TaskAssignmentNoticeParty?, reason: String? = null): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "system",
            authorName = "system",
            timestampLabel = at,
            messageType = "system",
            metadata = TaskAssignmentNotice(51, actor, assignee, reason).toMessageMetadata(),
            body = "📌 ${actor.displayName} assigned #51 \"Ship it\"${assignee?.let { " to @${it.name}" }.orEmpty()}${reason?.let { ": $it" }.orEmpty()}",
        )

    val handoff = line("h1", "2026-11-10T08:00:00.000Z", otter, xx, "Your call: ship Friday or Monday? The numbers are in the sheet.")
    val handedBack = line("b1", "2026-11-10T09:30:00.000Z", xx, otter)
    val tookBack = line("t1", "2026-11-10T10:51:00.000Z", otter, otter)

    /** 2026-11-10, UTC, 24 h. */
    val timeContext = RaftTimeContext(localOffsetMinutes = 0, todayEpochDay = raftEpochDayForDate(2026, 11, 10), nowEpochMillis = 1_794_312_000_000L)

    @Composable
    fun WithTime(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(timeContext), content = content)
    }
}
