package build.raft.app

import build.raft.app.data.ComputerReadinessAction
import build.raft.app.data.ComputerReadinessActor
import build.raft.app.data.ComputerReadinessAttention
import build.raft.app.data.ComputerReadinessAttentionItem
import build.raft.app.data.ComputerReadinessBehaviour
import build.raft.app.data.ComputerReadinessEntry
import build.raft.app.data.ComputerReadinessFact
import build.raft.app.data.ComputerReadinessResource
import build.raft.app.data.ComputerReadinessScope
import build.raft.app.data.ComputerReadinessSnapshot
import build.raft.app.data.ComputerReadinessState
import build.raft.app.data.ComputersRackGroupBy
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerHour
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftStatusVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Presentation model tests for Computers needs-attention top summary, header counts,
 * Needs-attention status filter, Restarting/Updating windows, and readiness light colors
 * (`#wg-mobile-sync:#337`, botiverse/slock #14554, #14587, #14602).
 */
class RaftComputerReadinessModelTest {
    private val now = 1_794_312_000_000L

    private fun computer(
        id: String,
        name: String = id,
        online: Boolean = true,
        upgradeAvailable: Boolean = false,
        attention: ComputerReadinessAttention? = null,
        readiness: ComputerReadinessSnapshot? = null,
    ) =
        SettingsComputerSnapshot(
            id = id,
            name = name,
            daemonVersion = "",
            computerVersion = "1.0.53",
            isComputer = true,
            online = online,
            upgradeAvailable = upgradeAvailable,
            latestComputerVersion = "1.0.53",
            canManage = true,
            serverNeedsAttention = attention?.let { it.count > 0 },
            attention = attention,
            readiness = readiness,
        )

    private val verifyItem =
        ComputerReadinessAttentionItem(
            code = "verification_required",
            resource = ComputerReadinessResource.Login("shared:claude"),
            actor = ComputerReadinessActor.ComputerOwner,
            actorName = "Olive",
            action = ComputerReadinessAction.VerifyAccount,
            agentIds = listOf("a1", "a2"),
            agentCount = 2,
        )

    private val memberItem =
        ComputerReadinessAttentionItem(
            code = null,
            resource = null,
            actor = ComputerReadinessActor.ComputerOwner,
            actorName = "Olive",
            action = ComputerReadinessAction.Fix,
            agentIds = null,
            agentCount = 2,
        )

    private val guestItem =
        ComputerReadinessAttentionItem(
            code = null,
            resource = null,
            actor = ComputerReadinessActor.ComputerOwner,
            actorName = null,
            action = ComputerReadinessAction.Fix,
            agentIds = null,
            agentCount = 1,
        )

    @Test
    fun attentionSummaryHiddenForOlderServerOrClearFleet() {
        val legacy = listOf(computer("old-offline", online = false))
        assertNull(raftComputersAttentionSummary(legacy, now))

        val clear =
            listOf(
                computer("ok", attention = ComputerReadinessAttention(count = 0, items = emptyList(), waitingAgents = 0)),
            )
        assertNull(raftComputersAttentionSummary(clear, now))
    }

    @Test
    fun attentionSummaryProjectsSingleItemMultiComputerAndEarliestWaitingReset() {
        val singleFleet =
            listOf(
                computer(
                    "oak-linux",
                    attention = ComputerReadinessAttention(count = 1, items = listOf(verifyItem), waitingAgents = 1, waitingUntilMs = now + 6L * MillisPerDay),
                ),
                computer(
                    "elm",
                    attention = ComputerReadinessAttention(count = 0, items = emptyList(), waitingAgents = 3, waitingUntilMs = now + 3L * MillisPerHour + 10L * MillisPerMinute),
                ),
            )
        val single = raftComputersAttentionSummary(singleFleet, now)
        val headline = assertIs<RaftComputersAttentionHeadline.SingleItem>(single?.headline)
        assertEquals("oak-linux", headline.machineId)
        assertEquals(verifyItem, headline.item)
        assertEquals(4, single?.waitingAgents)
        assertEquals(RaftRackReset(RaftRelativeTimeUnit.Hour, 3), single?.waitingReset)

        // Single computer with 2 items collapses to count headline ("1 computer needs attention").
        val twoItems =
            listOf(
                computer(
                    "oak-linux",
                    attention = ComputerReadinessAttention(count = 2, items = listOf(verifyItem, memberItem), waitingAgents = 0),
                ),
            )
        assertEquals(
            RaftComputersAttentionHeadline.MultipleComputers(1),
            raftComputersAttentionSummary(twoItems, now)?.headline,
        )

        // Two computers flagged -> count headline ("2 computers need attention").
        val twoComputers =
            listOf(
                computer("a", attention = ComputerReadinessAttention(count = 1, items = listOf(memberItem))),
                computer("b", attention = ComputerReadinessAttention(count = 1, items = listOf(guestItem))),
            )
        assertEquals(
            RaftComputersAttentionHeadline.MultipleComputers(2),
            raftComputersAttentionSummary(twoComputers, now)?.headline,
        )

        // Waiting agents only (nothing actionable) keeps headline null and reports wait line.
        val waitingOnly =
            listOf(
                computer("w", attention = ComputerReadinessAttention(count = 0, waitingAgents = 2, waitingUntilMs = now + 45_000L)),
            )
        val waitSummary = raftComputersAttentionSummary(waitingOnly, now)
        assertNull(waitSummary?.headline)
        assertEquals(2, waitSummary?.waitingAgents)
        assertEquals(RaftRackReset(RaftRelativeTimeUnit.Minute, 1), waitSummary?.waitingReset)

        // Expired waitingUntil drops the "back in" reset while keeping the agent wait count.
        val expiredWait =
            listOf(
                computer("w", attention = ComputerReadinessAttention(count = 0, waitingAgents = 2, waitingUntilMs = now - 1_000L)),
            )
        assertNull(raftComputersAttentionSummary(expiredWait, now)?.waitingReset)
    }

    @Test
    fun needsAttentionFilterFollowsServerSummaryWhenPresentAndFallsBackOnOlderServers() {
        val flagged = computer("needs", attention = ComputerReadinessAttention(count = 1, items = listOf(memberItem)))
        val waitsOnly = computer("waits", attention = ComputerReadinessAttention(count = 0, waitingAgents = 3))
        val restarting = computer("restarting", online = false, attention = ComputerReadinessAttention(count = 0, waitingAgents = 1))
        val legacyOffline = computer("legacy-offline", online = false)

        val fleet = listOf(flagged, waitsOnly, restarting, legacyOffline)
        val filtered =
            raftFilterRackComputers(
                fleet,
                RaftComputersRackFilters(status = RaftComputersRackStatusFilter.Attention),
            )
        assertEquals(listOf("needs", "legacy-offline"), filtered.map { it.id })
    }

    @Test
    fun headerCountsTrackAttentionOnlyWhenServerSummarized() {
        val olderRows = listOf(computer("a"), computer("b", online = false)).map { it.toRaftComputerRow() }
        val olderCounts = raftComputersCounts(olderRows, emptyMap())
        assertEquals(RaftComputersCounts(computers = 2, online = 1, agents = 0, attention = 0, attentionSummarized = false), olderCounts)

        val newRows =
            listOf(
                computer("needs", attention = ComputerReadinessAttention(count = 1, items = listOf(memberItem))),
                computer("waits", attention = ComputerReadinessAttention(count = 0, waitingAgents = 3)),
                computer("legacy", online = false),
            ).map { it.toRaftComputerRow() }
        val newCounts = raftComputersCounts(newRows, emptyMap())
        assertEquals(RaftComputersCounts(computers = 3, online = 2, agents = 0, attention = 1, attentionSummarized = true), newCounts)
    }

    @Test
    fun attentionDotAndRestartingUpdatingWindowsMapPresenceAndLayoutCorrectly() {
        val flaggedRow =
            computer("needs", attention = ComputerReadinessAttention(count = 1, items = listOf(memberItem)))
                .toRaftComputerRow(nowMillis = now)
        assertEquals(RaftComputerDot.Attention, flaggedRow.dot)

        val restartingReadiness =
            ComputerReadinessSnapshot(
                own =
                    ComputerReadinessEntry(
                        state = ComputerReadinessState.Waiting,
                        primary =
                            ComputerReadinessFact(
                                resource = ComputerReadinessResource.Computer,
                                code = "computer_restarting",
                                behaviour = ComputerReadinessBehaviour.Waiting,
                                sinceMs = now - 30_000L,
                                untilMs = now + 240_000L,
                            ),
                        scope = ComputerReadinessScope.Computer,
                    ),
            )
        val restartingComputer =
            computer("restarting", online = false, attention = ComputerReadinessAttention(count = 0), readiness = restartingReadiness)
        val restartingRow = restartingComputer.toRaftComputerRow(nowMillis = now)
        assertTrue(restartingRow.restarting)
        assertTrue(restartingRow.present)
        assertEquals(RaftComputerDot.Restarting, restartingRow.dot)
        assertEquals(RaftComputerVersionNotice.Restarting, restartingRow.versionNotice)
        assertIs<RaftComputerCardFacts.Online>(restartingRow.cardFacts())
        // Stays in online group (not offline section) and suppresses offline restart command.
        val layout =
            raftComputersRackLayout(
                computers = listOf(restartingComputer),
                model = raftComputersRackToolbarModel(RaftComputersRackToolbarState(), RaftRackRuntimeColumns(emptyList(), emptyList())),
                groupBy = ComputersRackGroupBy.None,
                usage = emptyMap(),
                labels = RaftRackGroupLabels("Personal", "Other"),
                userId = null,
            )
        assertEquals(listOf("restarting"), layout.groups.single().computers.map { it.id })
        assertTrue(layout.offline.isEmpty())
        assertNull(
            raftComputerRackAction(
                computer = restartingComputer,
                upgrade = raftComputerUpgradeModel(restartingComputer, SettingsComputerControlState(), now),
                restart = null,
                commandContext = SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "prod", serverUrl = "https://raft.build"),
            ),
        )

        // Updating via readiness window (without upgradeRequest) renders yellow Updating… and stays present.
        val updatingReadiness =
            ComputerReadinessSnapshot(
                own =
                    ComputerReadinessEntry(
                        state = ComputerReadinessState.Waiting,
                        primary =
                            ComputerReadinessFact(
                                resource = ComputerReadinessResource.Computer,
                                code = "computer_updating",
                                behaviour = ComputerReadinessBehaviour.Waiting,
                                sinceMs = now - 30_000L,
                                untilMs = now + 240_000L,
                            ),
                        scope = ComputerReadinessScope.Computer,
                    ),
            )
        val updatingRow = computer("updating", online = false, readiness = updatingReadiness).toRaftComputerRow(nowMillis = now)
        assertTrue(updatingRow.updating)
        assertTrue(updatingRow.present)
        assertEquals(RaftComputerDot.Updating, updatingRow.dot)
        assertEquals(RaftComputerVersionNotice.Updating(""), updatingRow.versionNotice)
        // Expired updating window falls back to offline.
        val expiredUpdatingRow = computer("updating", online = false, readiness = updatingReadiness).toRaftComputerRow(nowMillis = now + 300_000L)
        assertFalse(expiredUpdatingRow.updating)
        assertFalse(expiredUpdatingRow.present)
        assertEquals(RaftComputerDot.Offline, expiredUpdatingRow.dot)
    }

    @Test
    fun readinessLightUsesOfflineForComputerOfflineErrorForBlockedOrLimitWaitAndBusyForOtherWaits() {
        fun entry(state: ComputerReadinessState, code: String) =
            ComputerReadinessEntry(
                state = state,
                primary =
                    ComputerReadinessFact(
                        resource = ComputerReadinessResource.Computer,
                        code = code,
                        behaviour = if (state == ComputerReadinessState.Blocked) ComputerReadinessBehaviour.Blocked else ComputerReadinessBehaviour.Waiting,
                        sinceMs = now,
                    ),
            )

        assertEquals(RaftPresence.Offline, raftReadinessLight(entry(ComputerReadinessState.Blocked, "computer_offline")).presence)
        assertEquals(RaftStatusVariant.Muted, raftReadinessLight(entry(ComputerReadinessState.Blocked, "computer_offline")).statusVariant)

        assertEquals(RaftPresence.Error, raftReadinessLight(entry(ComputerReadinessState.Blocked, "verification_required")).presence)
        assertEquals(RaftPresence.Error, raftReadinessLight(entry(ComputerReadinessState.Waiting, "quota_exhausted")).presence)
        assertEquals(RaftPresence.Error, raftReadinessLight(entry(ComputerReadinessState.Waiting, "rate_limited")).presence)

        assertEquals(RaftPresence.Busy, raftReadinessLight(entry(ComputerReadinessState.Waiting, "computer_restarting")).presence)
        assertEquals(RaftBadgeVariant.Busy, raftReadinessLight(entry(ComputerReadinessState.Waiting, "computer_restarting")).badgeVariant)
        assertEquals(RaftStatusVariant.Primary, raftReadinessLight(entry(ComputerReadinessState.Waiting, "computer_updating")).statusVariant)
    }
}
