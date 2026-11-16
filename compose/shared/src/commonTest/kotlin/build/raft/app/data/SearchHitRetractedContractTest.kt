package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 077: a search hit its owner retracted (botiverse/slock staging `searchService.ts`
 * `MessageSearchResult.retracted`, set from `delegated_by_agent_id IS NOT NULL AND deleted_at IS NOT
 * NULL`; `content` is then the server's placeholder). The web row shows the muted retracted line
 * (`MessageHitRow` `isRetractedPreviewOf(result.retracted, result.content)`): the flag when the
 * server sent one, else its placeholder text (a server older than the flag).
 */
class SearchHitRetractedContractTest {
    private fun hit(id: String, content: String, retracted: String?): String =
        """
        {"id":"$id","seq":1,"channelId":"channel-1","threadId":null,"parentMessageId":null,
         "parentChannelId":"channel-1","parentChannelName":"general","parentChannelType":"channel",
         "parentChannelArchivedAt":null,"senderId":"user-alice","senderType":"user","senderName":"Alice",
         "channelName":"general","channelType":"channel","channelArchivedAt":null,
         "content":"$content","snippet":"$content","createdAt":"2026-11-10T09:00:00.000Z"
         ${retracted?.let { ""","retracted":$it""" }.orEmpty()}}
        """.trimIndent()

    @Test
    fun theFlagWinsAndTheServerPlaceholderMarksHitsFromAServerWithoutIt() {
        val placeholder = ServerRetractedMessageContent
        val body =
            """
            {"results":[
              ${hit("flagged", placeholder, "true")},
              ${hit("flag-false", placeholder, "false")},
              ${hit("no-flag-placeholder", placeholder, null)},
              ${hit("plain", "ship the release notes", null)}
            ],"hasMore":false,"historyLimited":false}
            """.trimIndent()

        val items = parseSharedSearchItems(body)?.items ?: error("search page did not parse")

        assertEquals(
            listOf(
                "flagged" to true,
                "flag-false" to false,
                "no-flag-placeholder" to true,
                "plain" to false,
            ),
            items.map { it.lastMessageId?.value to it.latestRetracted },
        )
    }

    @Test
    fun anIncompleteWalkCarriesWhereItGotToOnlyWithACursorToContinue() {
        val withCursor =
            parseSharedSearchItems(
                """{"results":[],"hasMore":false,"incomplete":true,"nextCursor":"c-1","searchedThrough":"2026-09-30T08:00:00.000Z","searchedMessages":10000}"""
            ) ?: error("page did not parse")
        assertEquals(
            Triple(true, true, "2026-09-30T08:00:00.000Z"),
            Triple(withCursor.hasMore, withCursor.incomplete, withCursor.searchedThrough),
        )

        // web `searchIncomplete = continuation.incomplete && continuation.nextCursor !== null`.
        val noCursor =
            parseSharedSearchItems("""{"results":[],"hasMore":false,"incomplete":true,"searchedThrough":"2026-09-30T08:00:00.000Z"}""")
                ?: error("page did not parse")
        assertEquals(false to false, noCursor.hasMore to noCursor.incomplete)

        val complete = parseSharedSearchItems("""{"results":[],"hasMore":false}""") ?: error("page did not parse")
        assertEquals(false to null, complete.incomplete to complete.searchedThrough)
    }
}
