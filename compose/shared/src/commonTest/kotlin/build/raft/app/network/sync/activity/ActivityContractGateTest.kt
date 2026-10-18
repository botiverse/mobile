package build.raft.app.network.sync.activity

import build.raft.app.network.sync.activity.contract.v1.ACTIVITY_SYNC_CONTRACT_REVISION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityContractGateTest {
    @Test
    fun exactRevisionMatchEnablesTheEngine() {
        val decision = decideActivitySyncEngine("raft.activity-sync;revision=$ACTIVITY_SYNC_CONTRACT_REVISION")
        assertTrue(decision.engineEnabled)
        assertEquals(ActivityContractDecision.Reason.Match, decision.reason)
    }

    @Test
    fun anyOtherRevisionFallsBackToInbox() {
        val newer = decideActivitySyncEngine("raft.activity-sync;revision=3", clientRevision = 2)
        val older = decideActivitySyncEngine("raft.activity-sync;revision=1", clientRevision = 2)
        assertFalse(newer.engineEnabled)
        assertFalse(older.engineEnabled)
        assertEquals(ActivityContractDecision.Reason.Mismatch, newer.reason)
        assertEquals(
            "activity_contract decision=inbox_fallback reason=Mismatch client_revision=2 server_revision=3",
            newer.diagnostic,
        )
    }

    @Test
    fun missingOrMalformedHeaderFromAnOlderServerFallsBack() {
        assertEquals(ActivityContractDecision.Reason.HeaderMissing, decideActivitySyncEngine(null).reason)
        assertEquals(ActivityContractDecision.Reason.HeaderMissing, decideActivitySyncEngine("  ").reason)
        assertEquals(ActivityContractDecision.Reason.HeaderUnparseable, decideActivitySyncEngine("raft.activity-sync").reason)
        assertEquals(ActivityContractDecision.Reason.HeaderUnparseable, decideActivitySyncEngine("raft.activity-sync;revision=02").reason)
        assertFalse(decideActivitySyncEngine(null).engineEnabled)
        assertEquals("activity_contract decision=inbox_fallback reason=HeaderMissing client_revision=$ACTIVITY_SYNC_CONTRACT_REVISION server_revision=none", decideActivitySyncEngine(null).diagnostic)
    }

    @Test
    fun anotherContractNameNeverEnablesTheEngine() {
        val decision = decideActivitySyncEngine("raft.other-sync;revision=$ACTIVITY_SYNC_CONTRACT_REVISION")
        assertFalse(decision.engineEnabled)
        assertEquals(ActivityContractDecision.Reason.ContractMismatch, decision.reason)
    }
}
