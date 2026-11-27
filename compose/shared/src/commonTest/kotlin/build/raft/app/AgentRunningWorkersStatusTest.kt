package build.raft.app

import build.raft.app.data.AgentActivityDetailKindIdle
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.data.RaftWorkingAgent
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.mergeWorkingNowWithWorkerBusy
import build.raft.app.data.parseAgentRunningWorkers
import build.raft.app.members.isMembersWorkingCountAgent
import build.raft.app.members.membersRemoteProfileAgentStatusIndicatorStateOrNull
import build.raft.app.members.nextMembersWorkingCountHoldExpiryMs
import build.raft.app.model.AgentCredentialHoldReason
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentProviderLimitHoldKind
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentReadinessFact
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.resources.*
import build.raft.app.thread.workingAmong
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for `runningWorkers` presence (botiverse/slock #16819,
 * `docs/contracts/worker-snapshot.md` Presence): defensive parsing, precedence, joint-channel
 * presence-only mode, Working now merging, Members working count, channel working-here exclusion,
 * and Android widget dot/text mapping.
 */
class AgentRunningWorkersStatusTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(raw: String): Int? = parseAgentRunningWorkers(json.parseToJsonElement(raw))

    private val quota =
        AgentDeliveryHold(
            reason = AgentDeliveryHoldReason.ProviderLimit,
            providerLimitDetail = AgentProviderLimitHoldKind.QuotaExhausted,
            untilMs = 10_000L,
            since = 1_000L,
        )
    private val signInHold =
        AgentDeliveryHold(
            reason = AgentDeliveryHoldReason.CredentialInvalid,
            credentialDetail = AgentCredentialHoldReason.UserReauth,
            since = 1_000L,
        )
    private val holdingReadiness =
        AgentReadiness(
            state = "blocked",
            primary = AgentReadinessFact(code = "computer_offline"),
        )
    private val readyReadiness = AgentReadiness(state = "ready")

    @Test
    fun parseAgentRunningWorkersMatchesSharedContract() {
        assertEquals(1, parse("1"))
        assertEquals(3, parse("3"))
        assertEquals(2, parse("2.0"))
        assertNull(parseAgentRunningWorkers(null))
        assertNull(parse("null"))
        assertNull(parse("0"))
        assertNull(parse("-1"))
        assertNull(parse("1.5"))
        assertNull(parse(""""2""""))
        assertNull(parse("true"))
        assertNull(parse("[]"))
        assertNull(parse("{}"))
    }

    @Test
    fun precedenceRulesMatchContract() {
        val one =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
                runningWorkers = 1,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.BrandYellow, one.color)
        assertEquals(AgentRunningWorkersDisplay(count = 1, presenceOnly = false), one.busyThroughWorkers(nowMs = 5_000L))
        assertEquals(
            AgentRunningWorkersTextSpec(res = Res.string.agent_status_working_through_worker),
            one.busyThroughWorkers(nowMs = 5_000L)?.textSpec(),
        )

        val three =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
                runningWorkers = 3,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.BrandYellow, three.color)
        assertEquals(
            AgentRunningWorkersTextSpec(res = Res.string.agent_status_working_through_workers, countArg = 3),
            three.busyThroughWorkers(nowMs = 5_000L)?.textSpec(),
        )

        val peer =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
                runningWorkers = 1,
                runningWorkersPresenceOnly = true,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.BrandYellow, peer.color)
        assertEquals(AgentRunningWorkersDisplay(count = 1, presenceOnly = true), peer.busyThroughWorkers(nowMs = 5_000L))
        assertEquals(
            AgentRunningWorkersTextSpec(res = Res.string.agent_status_working_through_workers_presence),
            peer.busyThroughWorkers(nowMs = 5_000L)?.textSpec(),
        )

        // Own thinking/working takes precedence (shows its own state, not busy-through-Workers).
        val selfWorking =
            SharedServerMemberStatus(
                status = AgentStatus.Working,
                presence = Presence.Away,
                external = false,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.BrandYellow, selfWorking.color)
        assertNull(selfWorking.busyThroughWorkers(nowMs = 5_000L))

        val selfThinking =
            SharedServerMemberStatus(
                status = AgentStatus.Thinking,
                presence = Presence.Away,
                external = false,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertNull(selfThinking.busyThroughWorkers(nowMs = 5_000L))

        // Error, offline, disconnected, idle stay themselves.
        val error =
            SharedServerMemberStatus(
                status = AgentStatus.Error,
                presence = Presence.Offline,
                external = false,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.AccentOrange, error.color)
        assertNull(error.busyThroughWorkers(nowMs = 5_000L))

        val offline =
            SharedServerMemberStatus(
                status = AgentStatus.Offline,
                presence = Presence.Offline,
                external = false,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.PresenceOffline, offline.color)
        assertNull(offline.busyThroughWorkers(nowMs = 5_000L))

        // Active delivery hold wins (orange light); once provider_limit expires, busy-through-Workers applies.
        val heldStatus =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
                deliveryHold = quota,
                runningWorkers = 2,
            )
        val whileHeld = heldStatus.toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.AccentOrange, whileHeld.color)
        assertNull(whileHeld.busyThroughWorkers(nowMs = 5_000L))

        val afterHoldExpired = heldStatus.toRaftAgentStatusIndicatorState(nowEpochMillis = 10_000L)
        assertEquals(RaftColor.BrandYellow, afterHoldExpired.color)
        assertEquals(AgentRunningWorkersDisplay(count = 2), afterHoldExpired.busyThroughWorkers(nowMs = 10_000L))

        // Older daemon idle credentialHold suppresses Workers — including after withoutActivityDetail().
        val credHeld =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                detailKind = AgentActivityDetailKindIdle,
                credentialHold = AgentCredentialHoldReason.UserReauth,
                external = false,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        assertEquals(RaftColor.AccentLime, credHeld.color)
        assertNull(credHeld.busyThroughWorkers(nowMs = 5_000L))
        assertNull(credHeld.withoutActivityDetail().busyThroughWorkers(nowMs = 5_000L))

        // Blocked Computer, paused assistant, holding readiness, external, likelyOnline, App Bot suppress Workers.
        assertNull(
            agentRunningWorkersDisplayOf(
                status = AgentStatus.Online,
                runningWorkers = 2,
                blocked = AgentBlockedState.ComputerAccessRevoked,
                nowMs = 5_000L,
            )
        )
        assertNull(
            agentRunningWorkersDisplayOf(
                status = AgentStatus.Online,
                runningWorkers = 2,
                paused = true,
                nowMs = 5_000L,
            )
        )
        assertNull(
            agentRunningWorkersDisplayOf(
                status = AgentStatus.Online,
                runningWorkers = 2,
                readiness = holdingReadiness,
                nowMs = 5_000L,
            )
        )
        assertEquals(
            AgentRunningWorkersDisplay(count = 2),
            agentRunningWorkersDisplayOf(
                status = AgentStatus.Online,
                runningWorkers = 2,
                readiness = readyReadiness,
                nowMs = 5_000L,
            ),
        )
        assertNull(
            agentRunningWorkersDisplayOf(
                status = AgentStatus.Online,
                runningWorkers = 2,
                external = true,
                nowMs = 5_000L,
            )
        )
        assertNull(
            agentRunningWorkersDisplayOf(
                status = AgentStatus.Online,
                runningWorkers = 2,
                likelyOnline = true,
                nowMs = 5_000L,
            )
        )
        assertNull(
            agentRunningWorkersDisplayOf(
                status = AgentStatus.Online,
                runningWorkers = 2,
                appBot = true,
                nowMs = 5_000L,
            )
        )
    }

    @Test
    fun withoutActivityDetailPreservesCoarseRunningWorkersAndRemoteProfileForcesPresenceOnly() {
        val detailed =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        val coarse = detailed.withoutActivityDetail()
        assertEquals(2, coarse.runningWorkers)
        assertEquals(RaftColor.BrandYellow, coarse.color)
        assertEquals(AgentRunningWorkersDisplay(count = 2), coarse.busyThroughWorkers(nowMs = 5_000L))

        val remote =
            membersRemoteProfileAgentStatusIndicatorStateOrNull(
                summaryStatus = "active",
                summaryActivity = "online",
                runningWorkers = 3,
                runningWorkersPresenceOnly = true,
            )
        assertEquals(1, remote?.runningWorkers)
        assertEquals(true, remote?.runningWorkersPresenceOnly)
        assertEquals(RaftColor.BrandYellow, remote?.color)
        assertEquals(AgentRunningWorkersDisplay(count = 1, presenceOnly = true), remote?.busyThroughWorkers())
    }

    @Test
    fun membersAgentRunningWorkersDisplayPrefersLiveIndicatorOverStaleSummary() {
        val summaryWithWorkers =
            AgentDetailSummary(
                id = "a1",
                name = "Kirby",
                status = "active",
                activity = "online",
                runningWorkers = 2,
            )
        // No live status yet -> summary fallback applies.
        assertEquals(
            AgentRunningWorkersDisplay(count = 2),
            membersAgentRunningWorkersDisplay(
                indicator = null,
                summary = summaryWithWorkers,
                external = false,
                blocked = null,
                paused = false,
                nowMs = 5_000L,
            ),
        )
        // Live clear frame (online, runningWorkers = null) wipes stale summary count.
        val liveCleared =
            raftAgentStatusIndicatorState(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
            )
        assertNull(
            membersAgentRunningWorkersDisplay(
                indicator = liveCleared,
                summary = summaryWithWorkers,
                external = false,
                blocked = null,
                paused = false,
                nowMs = 5_000L,
            ),
        )
        // Joint channel remote profile never exposes a count.
        assertEquals(
            AgentRunningWorkersDisplay(count = 1, presenceOnly = true),
            membersAgentRunningWorkersDisplay(
                indicator = null,
                summary = summaryWithWorkers,
                external = false,
                blocked = null,
                paused = false,
                nowMs = 5_000L,
                presenceOnly = true,
            ),
        )
    }

    @Test
    fun membersWorkingCountIncludesBusyThroughWorkersAndChannelWorkingAmongExcludesThem() {
        val selfWorking =
            SharedServerMemberStatus(status = AgentStatus.Working, presence = Presence.Away, external = false)
                .toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        val selfThinking =
            SharedServerMemberStatus(status = AgentStatus.Thinking, presence = Presence.Away, external = false)
                .toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        val workerBusy =
            SharedServerMemberStatus(status = AgentStatus.Online, presence = Presence.Active, external = false, runningWorkers = 2)
                .toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        val heldWorkerBusy =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
                deliveryHold = quota,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        val pausedWorkerBusy =
            SharedServerMemberStatus(
                status = AgentStatus.Online,
                presence = Presence.Active,
                external = false,
                paused = true,
                runningWorkers = 2,
            ).toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)
        val plainOnline =
            SharedServerMemberStatus(status = AgentStatus.Online, presence = Presence.Active, external = false)
                .toRaftAgentStatusIndicatorState(nowEpochMillis = 5_000L)

        assertTrue(isMembersWorkingCountAgent(selfWorking, nowMs = 5_000L))
        assertTrue(isMembersWorkingCountAgent(selfThinking, nowMs = 5_000L))
        assertTrue(isMembersWorkingCountAgent(workerBusy, nowMs = 5_000L))
        assertFalse(isMembersWorkingCountAgent(heldWorkerBusy, nowMs = 5_000L))
        assertTrue(isMembersWorkingCountAgent(heldWorkerBusy, nowMs = 10_000L))
        assertEquals(10_000L, nextMembersWorkingCountHoldExpiryMs(arrayOf(selfWorking, heldWorkerBusy, plainOnline), nowMs = 5_000L))
        assertNull(nextMembersWorkingCountHoldExpiryMs(arrayOf(selfWorking, heldWorkerBusy, plainOnline), nowMs = 10_000L))
        assertFalse(isMembersWorkingCountAgent(pausedWorkerBusy, nowMs = 5_000L))
        assertFalse(isMembersWorkingCountAgent(plainOnline, nowMs = 5_000L))

        // Workspace Working now merges own-work rows then non-duplicate Worker-busy agents.
        val ownItem =
            RaftLiveAgentActivityItem(
                agentId = "a-self",
                name = "Niko",
                avatarWireValue = null,
                status = AgentStatus.Working,
                startedAt = 100L,
            )
        val merged =
            mergeWorkingNowWithWorkerBusy(
                liveItems = listOf(ownItem),
                workerBusy =
                    listOf(
                        RaftWorkingAgent("a-self", "Niko", null, AgentStatus.Online, runningWorkers = 1),
                        RaftWorkingAgent("a-workers", "Kirby", null, AgentStatus.Online, runningWorkers = 2),
                    ),
            )
        assertEquals(listOf("a-self", "a-workers"), merged.map { it.agentId })
        assertEquals(0, merged[0].runningWorkers)
        assertEquals(2, merged[1].runningWorkers)
        assertEquals(AgentStatus.Online, merged[1].status)

        // Channel "N agents working here" NEVER counts busy-through-Workers rows.
        val channelWorking = merged.workingAmong(setOf("a-self", "a-workers"))
        assertEquals(listOf("a-self"), channelWorking.map { it.agentId })
    }

    @Test
    fun widgetDotAndTextFollowRunningWorkersRulesAndPrecedence() {
        assertEquals(
            RaftAgentStatusDotKind.Yellow,
            raftAgentStatusDotKind("online", external = false, runningWorkers = 2, nowEpochMillis = 5_000L),
        )
        assertEquals(
            RaftAgentStatusDotKind.Lime,
            raftAgentStatusDotKind("online", external = false, runningWorkers = 0, nowEpochMillis = 5_000L),
        )
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind("error", external = false, runningWorkers = 2, nowEpochMillis = 5_000L),
        )
        assertEquals(
            RaftAgentStatusDotKind.Gray,
            raftAgentStatusDotKind("offline", external = false, runningWorkers = 2, nowEpochMillis = 5_000L),
        )
        assertEquals(
            RaftAgentStatusDotKind.Orange,
            raftAgentStatusDotKind("online", external = false, deliveryHold = signInHold, runningWorkers = 2, nowEpochMillis = 5_000L),
        )
        assertEquals(
            RaftAgentStatusDotKind.Yellow,
            raftAgentStatusDotKind("online", external = false, deliveryHold = quota, runningWorkers = 2, nowEpochMillis = 10_000L),
        )
        assertEquals(
            Res.string.agent_status_working_through_worker.desc(),
            raftAgentWidgetStatusText("online", null, null, external = false, runtime = "claude", description = null, runningWorkers = 1),
        )
        assertEquals(
            Res.string.agent_status_working_through_workers.format(3),
            raftAgentWidgetStatusText("online", null, null, external = false, runtime = "claude", description = null, runningWorkers = 3),
        )
        assertEquals(
            Res.string.member_row_status_working.desc(),
            raftAgentWidgetStatusText("working", null, null, external = false, runtime = "claude", description = null, runningWorkers = 2),
        )
    }
}
