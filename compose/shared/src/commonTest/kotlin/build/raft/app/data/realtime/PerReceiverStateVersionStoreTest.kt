package build.raft.app.data.realtime

import build.raft.app.data.InMemorySlockKeyValueStore
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerReceiverStateVersionStoreTest {
    @Test
    fun appliesOnlyStrictlyNewerVersionsPerServerMemberAndScope() {
        val store = PerReceiverStateVersionStore(InMemorySlockKeyValueStore())
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
    fun doesNotShareRealtimeSeqCursorKeysOrSemantics() {
        val keyValueStore = InMemorySlockKeyValueStore()
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
