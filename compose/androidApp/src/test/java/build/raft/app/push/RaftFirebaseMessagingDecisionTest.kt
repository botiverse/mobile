package build.raft.app.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The FCM `type` split of the unified notification contract (slock docs/contracts/notifications.md
 * §4.3) and the device's notifications switch (§3.1): off stops alerts, not retractions or syncs.
 */
class RaftFirebaseMessagingDecisionTest {
    @Test
    fun eachTypeTakesItsPath() {
        val cases =
            listOf(
                mapOf("type" to "alert") to RaftFcmMessageDecision.Alert,
                // An older server sends no type: its data message is an alert.
                mapOf("serverId" to "s1") to RaftFcmMessageDecision.Alert,
                mapOf("type" to "retract", "serverId" to "s1", "conversationId" to "c1", "throughSeq" to "4") to RaftFcmMessageDecision.Retract,
                mapOf("type" to "sync", "badge" to "3") to RaftFcmMessageDecision.Sync,
                mapOf("type" to "something_new") to RaftFcmMessageDecision.DropUnknownType,
            )
        for ((data, expected) in cases) {
            assertEquals(data.toString(), expected, raftFcmMessageDecision(data, alertsEnabled = { true }))
        }
    }

    @Test
    fun switchedOffNotificationsDropAlertsButStillRetractAndSync() {
        var switchRead = false
        val off = {
            switchRead = true
            false
        }
        assertEquals(RaftFcmMessageDecision.DropAlertsDisabled, raftFcmMessageDecision(mapOf("type" to "alert"), off))
        switchRead = false
        assertEquals(RaftFcmMessageDecision.Retract, raftFcmMessageDecision(mapOf("type" to "retract"), off))
        assertEquals(RaftFcmMessageDecision.Sync, raftFcmMessageDecision(mapOf("type" to "sync"), off))
        assertFalse("the switch is read only for an alert", switchRead)
    }
}
