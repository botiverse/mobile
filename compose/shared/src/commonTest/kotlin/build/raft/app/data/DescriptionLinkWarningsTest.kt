package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `warnings` of a profile save answer (botiverse/slock #15236, shared
 * `descriptionLinkWarnings.ts`) and the fixes each one offers, in the shared order.
 */
class DescriptionLinkWarningsTest {
    @Test
    fun readsTheWarningsOfAnAgentSaveAnswer() {
        val body =
            """
            {"id":"a1","name":"kirby","description":"See https://app.raft.build/artifacts/pt_1",
             "warnings":[
               {"code":"conversation","url":"https://app.raft.build/artifacts/pt_1","object":{"kind":"artifact","id":"pt_1"},"fixes":["..."]},
               {"code":"server_partial","url":"https://app.raft.build/artifacts/pt_2","object":{"kind":"artifact","id":"pt_2"},
                "server":{"id":"s1","name":"Northwind"},"unreadable":["guests","other_servers"],"fixes":[]},
               {"code":"brand_new","url":"https://example.com/x","object":{"kind":"artifact","id":"x"},"fixes":[]},
               {"code":"unavailable","object":{"kind":"artifact","id":"y"},"fixes":[]}
             ]}
            """.trimIndent()
        assertEquals(
            listOf(
                DescriptionLinkWarning(DescriptionLinkWarningCode.Conversation, "https://app.raft.build/artifacts/pt_1"),
                DescriptionLinkWarning(
                    DescriptionLinkWarningCode.ServerPartial,
                    "https://app.raft.build/artifacts/pt_2",
                    serverName = "Northwind",
                    serverId = "s1",
                    unreadableByGuests = true,
                    unreadableByOtherServers = true,
                ),
                // A newer code is kept (worded as "can't open"); a warning without its link is dropped.
                DescriptionLinkWarning(DescriptionLinkWarningCode.Unknown, "https://example.com/x"),
            ),
            parseSharedDescriptionLinkWarnings(body),
        )
        assertEquals(emptyList(), parseSharedDescriptionLinkWarnings("""{"id":"a1","name":"kirby"}"""))
        assertEquals(emptyList(), parseSharedDescriptionLinkWarnings("not json"))
    }

    @Test
    fun theFixesOfALinkAreEachOfferedOnceWidestAudienceLast() {
        // Shared descriptionLinkFixes: an agent's description is its server's only, so no "anyone with the link" for a conversation.
        assertEquals(
            listOf(DescriptionLinkFix.PublicCopy, DescriptionLinkFix.LeaveOut, DescriptionLinkFix.WidenToServer),
            descriptionLinkFixesOf(listOf(DescriptionLinkWarningCode.Conversation), DescriptionLinkSubject.Agent),
        )
        assertEquals(
            listOf(DescriptionLinkFix.PublicCopy, DescriptionLinkFix.LeaveOut, DescriptionLinkFix.WidenToServer, DescriptionLinkFix.MakePublic),
            descriptionLinkFixesOf(
                listOf(DescriptionLinkWarningCode.Conversation, DescriptionLinkWarningCode.OtherServer),
                DescriptionLinkSubject.Person,
            ),
        )
        assertEquals(listOf(DescriptionLinkFix.ReplaceLink), descriptionLinkFixesOf(listOf(DescriptionLinkWarningCode.Unavailable), DescriptionLinkSubject.Agent))
        assertEquals(emptyList(), descriptionLinkFixesOf(listOf(DescriptionLinkWarningCode.Unknown), DescriptionLinkSubject.Agent))
    }
}
