package build.raft.app.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The vendored copy can be newer than this loader (slock README "Versioning"): what it cannot read
 * is skipped and reported for that one case, never a suite failure and never a pass.
 */
class UnreadContractCaseDecodingTest {
    private fun case(steps: String, state: String = """{"outcome":"counted"}""", version: Int = 1, extraGiven: String = "") =
        """
        {"formatVersion":$version,"id":"R1-x","rule":"R1","title":"t","status":"agreed",
         "given":{"conversation":{"id":"thread-1","kind":"thread"}$extraGiven},
         "steps":$steps,"expect":{"state":$state}}
        """.trimIndent()

    @Test
    fun readsTheKnownActsWithActAsTheDiscriminator() {
        val decoded =
            decodeUnreadContractCase(
                "R1-x",
                case(
                    """[{"act":"markUnread","surface":"activityRow"},{"act":"serverResponds","status":200,"body":{"ok":true}},""" +
                        """{"act":"leaveThread"},{"act":"replyArrives","visible":true}]""",
                    state = """{"toast":null,"outcome":"counted"}""",
                ),
            )

        val steps = assertIs<UnreadCaseDecoding.Decoded>(decoded).case.steps
        assertEquals(UnreadCaseStep.MarkUnread(UnreadCaseSurface.ActivityRow), steps[0])
        assertEquals(200, assertIs<UnreadCaseStep.ServerResponds>(steps[1]).status)
        assertEquals(UnreadCaseStep.LeaveThread, steps[2])
        assertEquals(UnreadCaseStep.ReplyArrives(visible = true), steps[3])
        assertEquals(setOf("toast", "outcome"), decoded.case.assertedStateKeys, "toast:null is asserted, not dropped")
    }

    @Test
    fun skipsAndReportsWhatThisLoaderCannotRead() {
        val unreadable =
            mapOf(
                "unknown act" to case("""[{"act":"swipeAway"}]"""),
                "newer formatVersion" to case("[]", version = 2),
                "unknown state key" to case("[]", state = """{"badgeColour":"pink"}"""),
                "unknown field in a known act" to case("""[{"act":"markUnread","surface":"threadMenu","long":true}]"""),
                "unknown surface" to case("""[{"act":"markUnread","surface":"widget"}]"""),
                "unknown given key" to case("[]", extraGiven = ""","draft":{}"""),
            )

        unreadable.forEach { (name, text) ->
            val decoded = decodeUnreadContractCase("R1-x", text)
            assertIs<UnreadCaseDecoding.Unreadable>(decoded, name)
            assertTrue(decoded.reason.isNotBlank(), name)
        }
        assertTrue(
            (decodeUnreadContractCase("R1-x", case("""[{"act":"swipeAway"}]""")) as UnreadCaseDecoding.Unreadable)
                .reason.contains("swipeAway"),
            "the report names the act",
        )
    }
}
