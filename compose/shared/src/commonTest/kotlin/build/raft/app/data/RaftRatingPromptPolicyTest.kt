package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rating prompt rules (spec v8): once per device, never internal, 3 days / 5 active days / a Computer. */
class RaftRatingPromptPolicyTest {
    private val raftServer = "bccd5969-5c94-498c-9221-625f87d5ca12"
    private val day = 20_000L

    private fun eligible(): RaftRatingPromptInput =
        RaftRatingPromptInput(
            record = RaftRatingPromptRecord(firstSeenEpochDay = day - 3, activeDayCount = 5, lastActiveEpochDay = day),
            todayEpochDay = day,
            environmentKey = "production",
            joinedServerIds = listOf("srv-customer"),
            currentServerHasComputer = true,
            recentServerError = false,
        )

    @Test
    fun anEligibleDeviceIsAskedAndEachRuleBlocksOnItsOwn() {
        assertEquals(RaftRatingPromptDecision.Request, RaftRatingPromptPolicy.decide(eligible()))
        val base = eligible()
        val cases =
            listOf(
                base.copy(record = base.record.copy(requested = true)) to RaftRatingPromptBlock.AlreadyRequested,
                base.copy(joinedServerIds = listOf("srv-customer", raftServer)) to RaftRatingPromptBlock.InternalAccount,
                base.copy(environmentKey = "staging") to RaftRatingPromptBlock.InternalAccount,
                base.copy(environmentKey = SharedApiEnvironmentStore.CustomKey) to RaftRatingPromptBlock.InternalAccount,
                base.copy(environmentKey = "") to RaftRatingPromptBlock.InternalAccount,
                base.copy(recentServerError = true) to RaftRatingPromptBlock.RecentError,
                base.copy(record = base.record.copy(firstSeenEpochDay = day - 2)) to RaftRatingPromptBlock.TooSoonSinceFirstOpen,
                base.copy(record = base.record.copy(firstSeenEpochDay = null)) to RaftRatingPromptBlock.TooSoonSinceFirstOpen,
                base.copy(record = base.record.copy(activeDayCount = 4)) to RaftRatingPromptBlock.TooFewActiveDays,
                base.copy(currentServerHasComputer = false) to RaftRatingPromptBlock.NoComputer,
            )
        for ((input, block) in cases) {
            assertEquals(RaftRatingPromptDecision.Skip(block), RaftRatingPromptPolicy.decide(input), block.name)
        }
    }

    @Test
    fun internalServersAreTheProductionRaftServerAndEveryServerElsewhere() {
        assertEquals(setOf(raftServer), RaftInternalServers.serverIdsFor("production"))
        assertNull(RaftInternalServers.serverIdsFor("staging"))
        assertTrue(RaftInternalServers.isInternalAccount("production", listOf(" ${raftServer.uppercase()} ")))
        assertFalse(RaftInternalServers.isInternalAccount("production", listOf("srv-a", "srv-b")))
        assertFalse(RaftInternalServers.isInternalAccount("production", emptyList()))
        assertTrue(RaftInternalServers.isInternalAccount("staging", emptyList()))
    }

    @Test
    fun aTaskCountsOnlyWhenTheViewerGaveItToAnAgentAndItIsDone() {
        assertTrue(RaftRatingPromptPolicy.isOwnFinishedAgentTask(TaskStatus.Done, "u-1", MemberKind.Agent, "u-1"))
        val notIt =
            listOf(
                Triple(TaskStatus.InReview, "u-1", MemberKind.Agent),
                Triple(TaskStatus.Closed, "u-1", MemberKind.Agent),
                Triple(TaskStatus.Done, "u-2", MemberKind.Agent),
                Triple(TaskStatus.Done, null, MemberKind.Agent),
                Triple(TaskStatus.Done, "u-1", MemberKind.Human),
                Triple(TaskStatus.Done, "u-1", null),
            )
        for ((status, creator, assignee) in notIt) {
            assertFalse(RaftRatingPromptPolicy.isOwnFinishedAgentTask(status, creator, assignee, "u-1"), "$status $creator $assignee")
        }
        assertFalse(RaftRatingPromptPolicy.isOwnFinishedAgentTask(TaskStatus.Done, "", MemberKind.Agent, ""))
    }

    @Test
    fun theRecordCountsDistinctLocalDaysAndKeepsTheFirstOne() {
        val store = InMemoryRaftKeyValueStore()
        val repository = RaftRatingPromptRepository(store)
        assertEquals(RaftRatingPromptRecord(), repository.read())
        repository.recordActiveDay(day)
        repository.recordActiveDay(day)
        repository.recordActiveDay(day + 1)
        // The clock or the time zone moved back: that day does not count again.
        repository.recordActiveDay(day)
        repository.recordActiveDay(day + 5)
        assertEquals(RaftRatingPromptRecord(firstSeenEpochDay = day, activeDayCount = 3, lastActiveEpochDay = day + 5), repository.read())
        repository.markRequested()
        assertTrue(RaftRatingPromptRepository(store).read().requested, "the flag is persisted")
    }

    @Test
    fun aLocalDayFollowsTheOffset() {
        val utcMidnight = day * 86_400_000L
        assertEquals(day, raftLocalEpochDay(utcMidnight, 0))
        assertEquals(day - 1, raftLocalEpochDay(utcMidnight, -60), "23:00 the previous day at UTC-1")
        assertEquals(day, raftLocalEpochDay(utcMidnight - 60_000L, 8 * 60), "07:59 at UTC+8")
    }
}
