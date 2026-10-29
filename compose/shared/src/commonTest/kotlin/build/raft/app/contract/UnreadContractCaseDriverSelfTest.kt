package build.raft.app.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The thread driver's `holdActive` is a probe (a visible read held or sent). A probe that never
 * sees a read would pass every `holdActive: true` case unchecked, so this pins both answers on
 * the app's own rule: a marked answer keeps the hold, a nothing answer ends it (and sends the read).
 */
class UnreadContractCaseDriverSelfTest {
    private fun threadCase(outcome: String, markedUnread: Boolean) =
        """
        {"formatVersion":1,"id":"R3-self","rule":"R3","title":"t","status":"agreed",
         "given":{"conversation":{"id":"thread-1","kind":"thread"},"openThread":"thread-1"},
         "steps":[{"act":"markUnread","surface":"threadMenu"},
                  {"act":"serverResponds","status":200,"body":{"ok":true,"unreadCount":0,"maxReadSeq":40,
                   "readStateVersion":7,"markedUnread":$markedUnread,"outcome":"$outcome"}}],
         "expect":{"requests":[{"method":"POST","path":"/api/channels/thread-1/unread"}],
                   "state":{"holdActive":true,"outcome":"$outcome"}}}
        """.trimIndent()

    private suspend fun TestScope.failuresOf(text: String): List<String> {
        val case = assertIs<UnreadCaseDecoding.Decoded>(decodeUnreadContractCase("R3-self", text)).case
        val plan = assertIs<UnreadCasePlan.Run>(planUnreadCase(case))
        assertEquals("threadMenu", plan.driver.name)
        return with(plan.driver) { run(case) }
    }

    @Test
    fun theHoldProbeSeesAHeldAndAnEndedHold() = runTest {
        assertEquals(emptyList(), failuresOf(threadCase("marked", markedUnread = true)))
        assertEquals(
            listOf("at the end: holdActive: expected true, got false"),
            failuresOf(threadCase("nothing", markedUnread = false)),
            "nothing to mark ends the hold, so the probe's read goes out",
        )
    }
}
