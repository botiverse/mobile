package build.raft.app.data.realtime

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerReceiverStateVersionStoreTest {
    @Test
    fun appliesOnlyStrictlyNewerVersionsPerServerMemberAndScope() {
        val store = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val server = ServerId("server-1")
        val member = MemberId("member-1")

        val first =
            store.recordApplied(
                serverId = server,
                memberId = member,
                scope = PerReceiverStateScope.ReadState,
                incomingVersion = 5
            )
        val same =
            store.recordApplied(
                serverId = server,
                memberId = member,
                scope = PerReceiverStateScope.ReadState,
                incomingVersion = 5
            )
        val stale =
            store.recordApplied(
                serverId = server,
                memberId = member,
                scope = PerReceiverStateScope.ReadState,
                incomingVersion = 4
            )
        val nextScope =
            store.recordApplied(
                serverId = server,
                memberId = member,
                scope = PerReceiverStateScope.NotificationPrefs,
                incomingVersion = 4
            )

        assertTrue(first.shouldApply)
        assertFalse(same.shouldApply)
        assertFalse(stale.shouldApply)
        assertTrue(nextScope.shouldApply)
        assertEquals(5, store.readVersion(server, member, PerReceiverStateScope.ReadState))
        assertEquals(4, store.readVersion(server, member, PerReceiverStateScope.NotificationPrefs))
    }

    @Test
    fun gatesReadStateVersionsPerScopeIdIndependently() {
        // Server PR #4175: read_state versions live on the per-(user, channel)
        // cursor row, so channel A at version 9 must not swallow channel B's
        // version 3 — each scopeId carries its own counter.
        val store = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val server = ServerId("server-1")
        val member = MemberId("member-1")
        val channelA = ChannelId("channel-a")
        val channelB = ChannelId("channel-b")

        val firstA =
            store.recordApplied(
                serverId = server,
                memberId = member,
                scope = PerReceiverStateScope.ReadState,
                incomingVersion = 9,
                scopeId = channelA
            )
        val firstB =
            store.recordApplied(
                serverId = server,
                memberId = member,
                scope = PerReceiverStateScope.ReadState,
                incomingVersion = 3,
                scopeId = channelB
            )
        val staleA =
            store.recordApplied(
                serverId = server,
                memberId = member,
                scope = PerReceiverStateScope.ReadState,
                incomingVersion = 8,
                scopeId = channelA
            )

        assertTrue(firstA.shouldApply)
        assertTrue(firstB.shouldApply)
        assertFalse(staleA.shouldApply)
        assertEquals(9, store.readVersion(server, member, PerReceiverStateScope.ReadState, channelA))
        assertEquals(3, store.readVersion(server, member, PerReceiverStateScope.ReadState, channelB))
        // Family-level (scopeId = null) remains a distinct key: server_order
        // keeps gating there without seeing per-channel counters.
        assertEquals(0, store.readVersion(server, member, PerReceiverStateScope.ReadState))
    }

    @Test
    fun gatesTheSameReadScopeIndependentlyPerServer() {
        val store = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val member = MemberId("member-1")
        val channel = ChannelId("channel-shared-id")
        val serverA = ServerId("server-a")
        val serverB = ServerId("server-b")

        store.writeAcceptedVersion(
            serverId = serverA,
            memberId = member,
            scope = PerReceiverStateScope.ReadState,
            acceptedVersion = 5,
            scopeId = channel
        )

        assertTrue(
            store.decide(
                serverId = serverB,
                memberId = member,
                scope = PerReceiverStateScope.ReadState,
                incomingVersion = 3,
                scopeId = channel
            ).shouldApply
        )
        assertEquals(5, store.readVersion(serverA, member, PerReceiverStateScope.ReadState, channel))
        assertEquals(0, store.readVersion(serverB, member, PerReceiverStateScope.ReadState, channel))
    }

    @Test
    fun persistsReadStateVersionAndWatermarkCheckpointPerServerScope() {
        val store = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val member = MemberId("member-1")
        val channel = ChannelId("channel-shared-id")
        val serverA = ServerId("server-a")
        val serverB = ServerId("server-b")

        store.writeReadStateCheckpoint(
            serverId = serverA,
            memberId = member,
            scopeId = channel,
            checkpoint = PerReceiverReadStateCheckpoint(version = 7, maxReadSeq = 101)
        )

        assertEquals(
            PerReceiverReadStateCheckpoint(version = 7, maxReadSeq = 101),
            store.readReadStateCheckpoint(serverA, member, channel)
        )
        assertEquals(
            PerReceiverReadStateCheckpoint(),
            store.readReadStateCheckpoint(serverB, member, channel)
        )
    }

    @Test
    fun readStateFactUsesOneAuthorityAndAllowsExactProjectionReplay() {
        val store = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val server = ServerId("server-1")
        val member = MemberId("member-1")
        val channel = ChannelId("channel-1")

        assertEquals(
            PerReceiverReadStateFactResult.Accepted,
            store.applyOrObserveReadStateFact(server, member, channel, incomingVersion = 3, incomingMaxReadSeq = 30)
        )
        assertEquals(
            PerReceiverReadStateFactResult.Current,
            store.applyOrObserveReadStateFact(server, member, channel, incomingVersion = 3, incomingMaxReadSeq = 30)
        )
        assertEquals(
            PerReceiverReadStateFactResult.Stale,
            store.applyOrObserveReadStateFact(server, member, channel, incomingVersion = 3, incomingMaxReadSeq = 29)
        )
        assertEquals(
            PerReceiverReadStateCheckpoint(version = 3, maxReadSeq = 30),
            store.readReadStateCheckpoint(server, member, channel)
        )
    }

    @Test
    fun readStateFactAcceptsHigherVersionCursorRewind() {
        val store = PerReceiverStateVersionStore(InMemoryRaftKeyValueStore())
        val server = ServerId("server-1")
        val member = MemberId("member-1")
        val channel = ChannelId("channel-1")
        store.applyOrObserveReadStateFact(server, member, channel, incomingVersion = 3, incomingMaxReadSeq = 30)

        assertEquals(
            PerReceiverReadStateFactResult.Accepted,
            store.applyOrObserveReadStateFact(server, member, channel, incomingVersion = 4, incomingMaxReadSeq = 10)
        )
        assertEquals(
            PerReceiverReadStateCheckpoint(version = 4, maxReadSeq = 10),
            store.readReadStateCheckpoint(server, member, channel)
        )
    }

    @Test
    fun doesNotShareRealtimeSeqCursorKeysOrSemantics() {
        val keyValueStore = InMemoryRaftKeyValueStore()
        val versionStore = PerReceiverStateVersionStore(keyValueStore)
        val seqCursor = RealtimeAppliedSeqCursorStore(keyValueStore)
        val server = ServerId("server-1")
        val member = MemberId("member-1")

        seqCursor.saveAppliedLastSeq(server, 99)
        assertEquals(0, versionStore.readVersion(server, member, PerReceiverStateScope.ReadState))

        versionStore.recordApplied(
            serverId = server,
            memberId = member,
            scope = PerReceiverStateScope.ReadState,
            incomingVersion = 12
        )
        assertEquals(99, seqCursor.readAppliedLastSeq(server))
    }
}
