package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** When the two triggers become the one platform rating request a device ever makes. */
class RaftRatingPromptCoordinatorTest {
    private class FakeShell : RaftRatingPromptShellContext {
        var account: RaftRatingPromptAccount? = RaftRatingPromptAccount("u-1", "srv-a", listOf("srv-a", "srv-b"))
        var hasComputer: Boolean? = true
        var computerChecks = 0

        override suspend fun account(): RaftRatingPromptAccount? = account

        override suspend fun serverHasComputer(serverId: String): Boolean? {
            computerChecks++
            return hasComputer
        }
    }

    private class Harness(scope: TestScope, val store: InMemoryRaftKeyValueStore = InMemoryRaftKeyValueStore()) {
        var today = 20_000L
        var environment = "production"
        var recentError = false
        var hostAnswer = true
        var hostCalls = 0
        var host: RaftStoreReviewRequester? = RaftStoreReviewRequester { hostCalls++; hostAnswer }
        val tracked = mutableListOf<RaftAppRatingPromptTrigger>()
        val shell = FakeShell()
        val coordinator =
            RaftRatingPromptCoordinator(
                repository = { RaftRatingPromptRepository(store) },
                todayEpochDay = { today },
                environmentKey = { environment },
                recentServerError = { recentError },
                requester = { host },
                track = { tracked += it },
                scope = scope.backgroundScope,
                requestContext = EmptyCoroutineContext,
            ).also { it.bindShell(shell) }

        /** Opened on 5 distinct days, the first 4 days before today. */
        fun activeFiveDays() {
            for (offset in listOf(4L, 3L, 2L, 1L, 0L)) {
                today = 20_000L - offset
                coordinator.onAppForeground()
            }
        }

        val requested: Boolean
            get() = RaftRatingPromptRepository(store).read().requested
    }

    private fun task(
        status: TaskStatus = TaskStatus.Done,
        creatorId: String = "u-1",
        assigneeKind: MemberKind = MemberKind.Agent,
    ) = TaskSummary(
        id = TaskId("task-1"),
        title = "Ship it",
        status = status,
        assignee = MemberProfile(MemberId("a-1"), "Agent", "agent", assigneeKind),
        creator = MemberProfile(MemberId(creatorId), "Me", "me", MemberKind.Human),
        updatedAtLabel = "now",
    )

    @Test
    fun anEligibleApprovalAsksOnceAndNeverAgainOnThisDevice() = runTest {
        val h = Harness(this)
        h.activeFiveDays()
        h.coordinator.onActionCardApproved()
        runCurrent()
        assertEquals(1, h.hostCalls)
        assertEquals(listOf(RaftAppRatingPromptTrigger.ActionCardApproved), h.tracked)
        assertTrue(h.requested)

        // Later triggers, and a new app version over the same device store, never ask again.
        h.coordinator.onActionCardApproved()
        h.coordinator.onTaskOpened(task())
        h.coordinator.onTaskListShown()
        runCurrent()
        val upgraded = Harness(this, h.store)
        upgraded.coordinator.onActionCardApproved()
        runCurrent()
        assertEquals(1 to 0, h.hostCalls to upgraded.hostCalls)
        assertEquals(1, h.tracked.size)
    }

    @Test
    fun twoQuickSuccessesMakeOneRequest() = runTest {
        val h = Harness(this)
        h.activeFiveDays()
        h.coordinator.onActionCardApproved()
        h.coordinator.onActionCardApproved()
        runCurrent()
        assertEquals(1, h.hostCalls)
        assertEquals(1, h.tracked.size)
    }

    @Test
    fun aHostThatCouldNotAskRecordsNothingAndAHostlessPlatformNeverEvaluates() = runTest {
        val h = Harness(this)
        h.activeFiveDays()
        h.hostAnswer = false
        assertEquals(RaftRatingPromptOutcome.Skipped("host_unavailable"), h.coordinator.evaluate(RaftAppRatingPromptTrigger.ActionCardApproved))
        assertFalse(h.requested)
        assertTrue(h.tracked.isEmpty(), "no event without a call")
        h.hostAnswer = true
        assertEquals(RaftRatingPromptOutcome.Requested, h.coordinator.evaluate(RaftAppRatingPromptTrigger.ActionCardApproved))

        // Desktop installs no host: nothing is read or loaded.
        val desktop = Harness(this)
        desktop.activeFiveDays()
        desktop.host = null
        desktop.coordinator.onActionCardApproved()
        runCurrent()
        assertEquals(0 to 0, desktop.hostCalls to desktop.shell.computerChecks)
        assertFalse(desktop.requested)
    }

    @Test
    fun eachRuleKeepsThePromptAwayAndTheComputerListIsCheckedLast() = runTest {
        val cases: List<Pair<String, (Harness) -> Unit>> =
            listOf(
                RaftRatingPromptBlock.InternalAccount.name to { h: Harness ->
                    h.shell.account = h.shell.account!!.copy(joinedServerIds = listOf("srv-a", "bccd5969-5c94-498c-9221-625f87d5ca12"))
                },
                RaftRatingPromptBlock.InternalAccount.name to { h: Harness -> h.environment = "staging" },
                RaftRatingPromptBlock.RecentError.name to { h: Harness -> h.recentError = true },
                RaftRatingPromptBlock.TooFewActiveDays.name to { h: Harness -> h.store.putString(RaftRatingPromptRepository.KeyActiveDayCount, "4") },
                RaftRatingPromptBlock.TooSoonSinceFirstOpen.name to { h: Harness -> h.store.putString(RaftRatingPromptRepository.KeyFirstSeenDay, "19998") },
                "signed_out" to { h: Harness -> h.shell.account = null },
            )
        for ((reason, change) in cases) {
            val h = Harness(this)
            h.activeFiveDays()
            change(h)
            assertEquals(RaftRatingPromptOutcome.Skipped(reason), h.coordinator.evaluate(RaftAppRatingPromptTrigger.ActionCardApproved), reason)
            assertEquals(0 to 0, h.hostCalls to h.shell.computerChecks, reason)
        }
        for (hasComputer in listOf(false, null)) {
            val h = Harness(this)
            h.activeFiveDays()
            h.shell.hasComputer = hasComputer
            assertEquals(
                RaftRatingPromptOutcome.Skipped(RaftRatingPromptBlock.NoComputer.name),
                h.coordinator.evaluate(RaftAppRatingPromptTrigger.ActionCardApproved),
                "computers=$hasComputer",
            )
            assertEquals(1 to 0, h.shell.computerChecks to h.hostCalls)
        }
        // No shell shown (signed out, onboarding): never.
        val noShell = Harness(this)
        noShell.activeFiveDays()
        noShell.coordinator.unbindShell(noShell.shell)
        assertEquals(RaftRatingPromptOutcome.Skipped("no_shell"), noShell.coordinator.evaluate(RaftAppRatingPromptTrigger.ActionCardApproved))
    }

    @Test
    fun aFinishedOwnAgentTaskAsksWhenTheListShowsAgainNotWhenItOpens() = runTest {
        val h = Harness(this)
        h.activeFiveDays()
        h.coordinator.onTaskOpened(task())
        runCurrent()
        assertEquals(0, h.hostCalls, "opening the task asks nothing")
        h.coordinator.onTaskListShown()
        runCurrent()
        assertEquals(1, h.hostCalls)
        assertEquals(listOf(RaftAppRatingPromptTrigger.TaskResultViewed), h.tracked)
    }

    @Test
    fun aTaskThatIsNotTheViewersFinishedAgentTaskOrALeftAppDoesNotAsk() = runTest {
        val notIt = listOf(task(status = TaskStatus.InReview), task(creatorId = "u-2"), task(assigneeKind = MemberKind.Human))
        for (opened in notIt) {
            val h = Harness(this)
            h.activeFiveDays()
            h.coordinator.onTaskOpened(opened)
            h.coordinator.onTaskListShown()
            runCurrent()
            assertEquals(0, h.hostCalls, opened.toString())
        }
        val h = Harness(this)
        h.activeFiveDays()
        // The app went to the background in between: coming back is a launch, not the list.
        h.coordinator.onTaskOpened(task())
        h.coordinator.onAppBackground()
        h.coordinator.onTaskListShown()
        // The list showing again with no task opened asks nothing either.
        h.coordinator.onTaskListShown()
        runCurrent()
        assertEquals(0, h.hostCalls)
        assertFalse(h.requested)
    }
}
