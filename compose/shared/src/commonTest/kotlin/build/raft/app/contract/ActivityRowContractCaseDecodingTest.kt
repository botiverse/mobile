package build.raft.app.contract

import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vendored copy can be newer than this loader (slock README "Versioning"): what it cannot read
 * is skipped and reported for that one case, never a suite failure and never a pass. And the
 * harness's probes must be able to answer both ways, or a case would pass unchecked.
 */
class ActivityRowContractCaseDecodingTest {
    private fun positionCase(
        steps: String,
        row: String = """{"unreadCount":0,"position":"99"}""",
        reread: Boolean = false,
        version: Int = 1,
        extraGiven: String = "",
    ) =
        """
        {"formatVersion":$version,"id":"x","rule":"position","title":"t","status":"agreed",
         "given":{"row":{"unreadCount":0,"hasMention":false,"position":"99","mentionOnly":false}$extraGiven},
         "steps":$steps,"expect":{"row":$row,"reread":$reread}}
        """.trimIndent()

    private fun predicateMessage(subtype: String) =
        """
        {"formatVersion":1,"id":"p","rule":"predicate","title":"t","status":"agreed",
         "given":{"viewer":{"type":"user","id":"viewer-1"},"scope":{"rowKind":"thread"},
                  "message":{"senderType":"system","senderId":"system","messageType":"system"$subtype}},
         "steps":[],"expect":{"verdict":{"counts":null,"mention":false}}}
        """.trimIndent()

    @Test
    fun readsTheActsWithExactSeqsAndKeepsAbsentAndNullApart() {
        val decoded =
            decodeActivityRowContractCase(
                "x",
                positionCase(
                    """[{"act":"liveMessage","seq":"9007199254740993","counts":null,"mentionsViewer":false},""" +
                        """{"act":"answer","row":{"unreadCount":2,"hasMention":true,"position":null,"mentionOnly":false}},""" +
                        """{"act":"read","seq":"100"}]""",
                    row = """{"position":null}""",
                ),
            )

        val case = assertIs<ActivityRowCaseDecoding.Decoded>(decoded).case
        assertEquals(ActivityRowStep.LiveMessage("9007199254740993", counts = null, mentionsViewer = false), case.steps[0])
        assertEquals(9007199254740993L, (case.steps[0] as ActivityRowStep.LiveMessage).seq.toLong(), "no float rounding")
        assertNull(assertIs<ActivityRowStep.Answer>(case.steps[1]).row.position)
        assertEquals(ActivityRowStep.Read("100"), case.steps[2])
        assertEquals(JsonNull, case.expect?.row?.get("position"), "position:null is asserted, not dropped")

        val absent = assertIs<ActivityRowCaseDecoding.Decoded>(decodeActivityRowContractCase("p", predicateMessage("")))
        val nullSubtype =
            assertIs<ActivityRowCaseDecoding.Decoded>(decodeActivityRowContractCase("p", predicateMessage(""","systemSubtype":null""")))
        assertNull(absent.case.given.message?.systemSubtype, "absent: no provenance")
        assertEquals(JsonNull, nullSubtype.case.given.message?.systemSubtype, "null: provenance without a subtype")
    }

    @Test
    fun skipsAndReportsWhatThisLoaderCannotRead() {
        val unreadable =
            mapOf(
                "unknown act" to positionCase("""[{"act":"swipeAway"}]"""),
                "newer formatVersion" to positionCase("[]", version = 2),
                "unknown row key" to positionCase("[]", row = """{"badgeColour":"pink"}"""),
                "unknown field in a known act" to positionCase("""[{"act":"read","seq":"1","source":"push"}]"""),
                "unknown given key" to positionCase("[]", extraGiven = ""","draft":{}"""),
                "unknown rule" to positionCase("[]").replace("\"rule\":\"position\"", "\"rule\":\"ordering\""),
            )

        unreadable.forEach { (name, text) ->
            val decoded = decodeActivityRowContractCase("x", text)
            assertIs<ActivityRowCaseDecoding.Unreadable>(decoded, name)
            assertTrue(decoded.reason.isNotBlank(), name)
        }
        assertTrue(
            (decodeActivityRowContractCase("x", positionCase("""[{"act":"swipeAway"}]""")) as ActivityRowCaseDecoding.Unreadable)
                .reason.contains("swipeAway"),
            "the report names the act",
        )
    }

    private suspend fun TestScope.failuresOf(text: String): List<String> {
        val case = assertIs<ActivityRowCaseDecoding.Decoded>(decodeActivityRowContractCase("x", text)).case
        val plan = assertIs<ActivityRowCasePlan.Run>(planActivityRowCase(case))
        assertEquals("position", plan.driver.name)
        return with(plan.driver) { run(case) }
    }

    @Test
    fun theRereadProbeSeesARereadAndItsAbsence() = runTest {
        val live = """[{"act":"liveMessage","seq":"100","counts":true,"mentionsViewer":false}]"""
        val noPosition = """{"unreadCount":0,"hasMention":false,"position":null,"mentionOnly":false}"""
        fun nullPositionCase(reread: Boolean) =
            positionCase(live, row = """{"unreadCount":0,"position":null}""", reread = reread)
                .replace(""""row":{"unreadCount":0,"hasMention":false,"position":"99","mentionOnly":false}""", """"row":$noPosition""")

        // A row without a position takes no live patch: the app re-reads it.
        assertEquals(emptyList(), failuresOf(nullPositionCase(reread = true)))
        assertEquals(listOf("at the end: reread: expected false, got true"), failuresOf(nullPositionCase(reread = false)))
        // A live message above the position is applied in place, with no re-read.
        val applied = """{"unreadCount":1,"position":"100"}"""
        assertEquals(emptyList(), failuresOf(positionCase(live, row = applied, reread = false)))
        assertEquals(
            listOf("at the end: reread: expected true, got false"),
            failuresOf(positionCase(live, row = applied, reread = true)),
        )
        assertEquals(
            listOf("at the end: unreadCount: expected 0, got 1"),
            failuresOf(positionCase(live, row = """{"unreadCount":0}""", reread = false)),
            "the row probe reads the shown count",
        )
    }
}
