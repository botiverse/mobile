package build.raft.app.network.sync.activity

import build.raft.app.network.sync.SyncFrame
import build.raft.app.network.sync.SyncIngestOutcome
import build.raft.app.network.sync.SyncRequestDescription
import build.raft.app.network.sync.SyncSnapshot
import build.raft.app.network.sync.SyncViolationKind
import build.raft.app.network.sync.activity.contract.v1.ActivityFilter
import build.raft.app.network.sync.activity.contract.v1.ActivityRow
import build.raft.app.network.sync.activity.contract.v1.ActivityRowTombstone
import build.raft.app.network.sync.activity.contract.v1.ActivityScope
import build.raft.app.network.sync.activity.contract.v1.ActivityWindow
import build.raft.app.network.sync.activity.contract.v1.ActorKind
import build.raft.app.network.sync.activity.contract.v1.AffectedReadState
import build.raft.app.network.sync.activity.contract.v1.ChannelActivityRow
import build.raft.app.network.sync.activity.contract.v1.SnapshotIngress
import build.raft.app.network.sync.activity.contract.v1.TombstoneReason
import build.raft.app.network.sync.activity.contract.v1.UInt64String
import build.raft.app.network.sync.createSyncCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActivitySyncDomainTest {
    private val scope = ActivityScope("srv-1", "principal-1", ActivityFilter.All, "window-1")
    private val scopeId = encodeActivityScopeId(scope)

    @Test
    fun decimalComparatorUsesMagnitudeAndPreservesValuesAboveTwoToThe53() {
        assertEquals(-1, compareUInt64String(u("9"), u("10")))
        assertEquals(1, compareUInt64String(u("10"), u("9")))
        assertEquals(1, compareUInt64String(u("9007199254740993"), u("9007199254740992")))
        assertEquals(1, compareUInt64String(u("18446744073709551615"), u("18446744073709551614")))
        assertEquals(0, compareUInt64String(u("18446744073709551615"), u("18446744073709551615")))
    }

    @Test
    fun rowsAdvanceMonotonicallyAcrossDigitLengths() {
        var state = initialActivityState()
        state = foldActivityEvent(state, frame(row("r1", "9", unreadCount = 1u)))
        state = foldActivityEvent(state, frame(row("r1", "10", unreadCount = 2u)))
        assertEquals(2u, assertIs<ChannelActivityRow>(state.rows.single()).unreadCount)

        state = foldActivityEvent(state, frame(row("r1", "3", unreadCount = 7u)))
        val retained = assertIs<ChannelActivityRow>(state.rows.single())
        assertEquals(2u, retained.unreadCount)
        assertEquals(u("10"), retained.rowVersion)
    }

    @Test
    fun tombstonesPreventStaleResurrectionButAllowStrictlyNewerReentry() {
        var state = foldActivityEvent(initialActivityState(), frame(row("r1", "5")))
        state = foldActivityEvent(state, frame(tombstones = listOf(stone("r1", "6", TombstoneReason.Deleted))))
        assertEquals(emptyList(), ids(state))

        state = foldActivityEvent(state, frame(row("r1", "5")))
        assertEquals(emptyList(), ids(state))

        state = foldActivityEvent(state, frame(row("r1", "7", "2026-07-28T11:00:00Z")))
        assertEquals(listOf("r1"), ids(state))
        assertEquals(emptyMap(), state.tombstones)
    }

    @Test
    fun staleTombstoneDoesNotRemoveNewerLiveRow() {
        var state = foldActivityEvent(initialActivityState(), frame(row("r1", "9")))
        state = foldActivityEvent(state, frame(tombstones = listOf(stone("r1", "4", TombstoneReason.Done))))
        assertEquals(listOf("r1"), ids(state))
    }

    @Test
    fun canonicalOrderIsRecentFirstWithRowIdTotalTieBreak() {
        val sameTime = "2026-07-28T10:00:00Z"
        val forward = foldActivityEvent(
            initialActivityState(),
            frame(
                row("a", "1", sameTime),
                row("b", "1", sameTime),
                row("c", "1", "2026-07-28T11:00:00Z"),
            ),
        )
        val reverse = foldActivityEvent(
            initialActivityState(),
            frame(
                row("b", "1", sameTime),
                row("a", "1", sameTime),
                row("c", "1", "2026-07-28T11:00:00Z"),
            ),
        )
        assertEquals(listOf("c", "a", "b"), ids(forward))
        assertEquals(ids(forward), ids(reverse))
    }

    @Test
    fun activityVersionAdvancesButNeverRegresses() {
        var state = foldActivityEvent(initialActivityState(), frame(activityVersion = "10"))
        state = foldActivityEvent(state, frame(activityVersion = "4"))
        assertEquals(u("10"), state.activityVersion)
        state = foldActivityEvent(state, frame(activityVersion = "11"))
        assertEquals(u("11"), state.activityVersion)
    }

    @Test
    fun readStateIsAMonotonicVersionedRegisterPerRow() {
        var state = foldActivityEvent(initialActivityState(), frame(row("r1", "1")))
        state = foldActivityEvent(
            state,
            readUpdate("r1", maxReadSeq = "42", readStateVersion = "3", activityVersion = "5"),
        )
        var current = assertIs<ChannelActivityRow>(state.rows.single())
        assertEquals(u("42"), current.maxReadSeq)
        assertEquals(u("3"), current.readStateVersion)
        assertEquals(u("5"), state.activityVersion)

        state = foldActivityEvent(
            state,
            readUpdate("r1", maxReadSeq = "7", readStateVersion = "2", activityVersion = "4"),
        )
        current = assertIs<ChannelActivityRow>(state.rows.single())
        assertEquals(u("42"), current.maxReadSeq)
        assertEquals(u("3"), current.readStateVersion)
        assertEquals(u("5"), state.activityVersion)
    }

    @Test
    fun unknownEventIsReferenceStableNoOpAndFoldIsPure() {
        val before = foldActivityEvent(initialActivityState(), frame(row("r1", "1")))
        assertSame(before, foldActivityEvent(before, mapOf("type" to "unknown")))

        val beforeRows = before.rows.toList()
        foldActivityEvent(before, frame(row("r2", "1", "2026-07-28T12:00:00Z")))
        assertEquals(beforeRows, before.rows)
    }

    @Test
    fun snapshotIsAuthoritativeAndClearsRetainedTombstones() {
        val snapshot = snapshot(
            watermark = "5",
            activityVersion = "5",
            rows = listOf(row("gone", "2")),
        )
        val state = activityStateFromSnapshot(snapshot)
        assertEquals(listOf("gone"), ids(state))
        assertEquals(emptyMap(), state.tombstones)
        assertEquals(u("5"), state.activityVersion)
        assertEquals(false, state.hasMore)
        assertEquals(true, state.complete)
    }

    @Test
    fun snapshotOwnTombstoneSurvivesAndBlocksStaleResurrection() {
        var state = activityStateFromSnapshot(
            snapshot(
                watermark = "5",
                activityVersion = "5",
                rows = emptyList(),
                tombstones = listOf(stone("gone", "6", TombstoneReason.Deleted)),
            ),
        )
        assertEquals(mapOf("gone" to u("6")), state.tombstones)

        state = foldActivityEvent(state, frame(row("gone", "5")))
        assertEquals(emptyList(), ids(state))
        assertEquals(mapOf("gone" to u("6")), state.tombstones)
    }

    @Test
    fun sameSequenceDifferentCompleteFactIsAConflictAndDoesNotMutateState() {
        val core = createSyncCore(build.raft.app.network.sync.SyncCoreConfig(listOf(createActivityDomain())))
        core.ingestSnapshot(
            ACTIVITY_DOMAIN,
            SyncSnapshot(scopeId, 1uL, "1", snapshot("1", "1", emptyList())),
        )
        val accepted = frame(row("r1", "1", unreadCount = 1u), activityVersion = "2")
        assertIs<SyncIngestOutcome.Applied>(
            core.ingestFrame(ACTIVITY_DOMAIN, SyncFrame(scopeId, 2uL, "1", accepted)),
        )

        val conflict = frame(row("r1", "1", unreadCount = 9u), activityVersion = "2")
        val outcome = core.ingestFrame(ACTIVITY_DOMAIN, SyncFrame(scopeId, 2uL, "1", conflict))
        assertEquals(
            SyncIngestOutcome.Violation(scopeId, SyncViolationKind.ProducerVersionConflict),
            outcome,
        )
        assertEquals(1u, assertIs<ChannelActivityRow>(core.activityState().rows.single()).unreadCount)
        assertEquals(SyncViolationKind.ProducerVersionConflict, core.violations().records.single().kind)
        assertTrue(core.pendingRequests().any { request ->
            request is SyncRequestDescription.Snapshot &&
                request.reason == build.raft.app.network.sync.SyncSnapshotReason.SnapshotRequired
        })
    }

    @Test
    fun realCoreRequiresBaselineThenAppliesContiguousFramesAndStopGatesGap() {
        val core = createSyncCore(build.raft.app.network.sync.SyncCoreConfig(listOf(createActivityDomain())))

        val noBaseline = core.ingestFrame(
            ACTIVITY_DOMAIN,
            SyncFrame(scopeId, seq = 1uL, epoch = "1", event = frame(row("r1", "1"))),
        )
        assertIs<SyncIngestOutcome.GapRepairRequested>(noBaseline)
        assertEquals(emptyList(), ids(core.activityState()))
        assertTrue(core.pendingRequests().any { it is SyncRequestDescription.Snapshot })

        core.ingestSnapshot(
            ACTIVITY_DOMAIN,
            SyncSnapshot(
                scopeId = scopeId,
                watermark = 1uL,
                epoch = "1",
                state = snapshot("1", "1", listOf(row("r1", "1"))),
            ),
        )
        assertEquals(listOf("r1"), ids(core.activityState()))

        val applied = core.ingestFrame(
            ACTIVITY_DOMAIN,
            SyncFrame(scopeId, seq = 2uL, epoch = "1", event = frame(row("r2", "1", "2026-07-28T12:00:00Z"))),
        )
        assertIs<SyncIngestOutcome.Applied>(applied)
        assertEquals(listOf("r2", "r1"), ids(core.activityState()))

        val gap = core.ingestFrame(
            ACTIVITY_DOMAIN,
            SyncFrame(scopeId, seq = 4uL, epoch = "1", event = frame(row("r3", "1", "2026-07-28T13:00:00Z"))),
        )
        assertIs<SyncIngestOutcome.GapRepairRequested>(gap)
        assertEquals(listOf("r2", "r1"), ids(core.activityState()))
        assertTrue(core.pendingRequests().any { it is SyncRequestDescription.Difference })
    }

    @Test
    fun scopeEncodingMatchesTheFrozenFourDimensionalIdentity() {
        assertEquals(
            "[\"srv-1\",\"principal-1\",\"all\",\"window-1\"]",
            scopeId,
        )
    }

    private fun build.raft.app.network.sync.SyncCore.activityState(): ActivityDomainState =
        state(ACTIVITY_DOMAIN, scopeId) as? ActivityDomainState ?: initialActivityState()

    private fun u(value: String) = UInt64String(value)

    private fun row(
        rowId: String,
        rowVersion: String,
        lastActivityAt: String = "2026-07-28T10:00:00Z",
        unreadCount: UInt = 0u,
    ): ChannelActivityRow =
        ChannelActivityRow(
            rowId = rowId,
            rowVersion = u(rowVersion),
            latestActivitySeq = u(rowVersion),
            lastActivityAt = lastActivityAt,
            unreadCount = unreadCount,
            hasMention = false,
            firstUnreadMessageId = null,
            firstMentionMessageId = null,
            maxReadSeq = u("0"),
            readStateVersion = u("0"),
            channelId = "channel-$rowId",
            channelName = rowId,
            channelKind = "channel",
            lastMessageId = "message-$rowId",
            lastMessagePreview = rowId,
            lastMessageSenderKind = ActorKind.User,
            lastMessageSenderId = "user-1",
            lastMessageSenderName = "User",
        )

    private fun stone(rowId: String, version: String, reason: TombstoneReason) =
        ActivityRowTombstone(rowId, u(version), reason)

    private fun frame(
        vararg rows: ActivityRow,
        tombstones: List<ActivityRowTombstone> = emptyList(),
        activityVersion: String? = null,
    ) = ActivityDomainEvent.Frame(
        rows = rows.toList(),
        tombstones = tombstones,
        activityVersion = activityVersion?.let(::u),
    )

    private fun readUpdate(
        rowId: String,
        maxReadSeq: String,
        readStateVersion: String,
        activityVersion: String,
    ) = ActivityDomainEvent.ReadStateUpdated(
        updates = listOf(
            AffectedReadState(
                scopeId = rowId,
                channelId = "channel-$rowId",
                maxReadSeq = u(maxReadSeq),
                readStateVersion = u(readStateVersion),
            ),
        ),
        activityVersion = u(activityVersion),
    )

    private fun snapshot(
        watermark: String,
        activityVersion: String,
        rows: List<ActivityRow>,
        tombstones: List<ActivityRowTombstone> = emptyList(),
    ) = SnapshotIngress(
        requestId = "request-$watermark",
        scope = scope,
        epoch = u("1"),
        watermark = u(watermark),
        activityVersion = u(activityVersion),
        window = ActivityWindow(
            rows = rows,
            tombstones = tombstones,
            nextCursor = null,
            hasMore = false,
            complete = true,
            totalCount = rows.size.toUInt(),
            totalUnreadCount = 0u,
        ),
    )

    private fun ids(state: ActivityDomainState): List<String> = state.rows.map { row ->
        when (row) {
            is ChannelActivityRow -> row.rowId
            else -> error("test fixture only creates channel rows")
        }
    }
}
