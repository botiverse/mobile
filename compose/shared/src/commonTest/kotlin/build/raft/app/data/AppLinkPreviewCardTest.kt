package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The v1 read-only card catalog (`appPreviewCardSchema`) as the web reads it. Vectors copied from
 * botiverse/slock staging 3652d3703232dd2e61b8f07b0217befe94337118:
 *  - `packages/server/src/routes/appLinkPreview.api.test.ts` `card` (the App's answer the server relays),
 *  - `packages/web/tests/appLinkChips.behavior.test.tsx` `statusCard` / `preview(...)`,
 *  - `packages/shared/src/appLinkPreview.test.ts` "read-only catalog rejects scripts, dynamic
 *    expressions, actions and malformed trees" (`valid` and every `invalid` case).
 */
class AppLinkPreviewCardTest {
    private fun card(json: String) = parseRaftAppLinkCard(Json.parseToJsonElement(json))

    private fun answer(preview: String) =
        parseAppLinkPreviewAnswer("""{"previews":[$preview],"prompts":[],"appIcons":{}}""")!!

    private val statusCard =
        """{"root":"stack","elements":{
          "stack":{"type":"Stack","props":{},"children":["state","detail"]},
          "state":{"type":"Badge","props":{"text":"Approved"}},
          "detail":{"type":"Text","props":{"text":"Ready for review","tone":"muted"}}}}"""

    @Test
    fun theServersPreviewCarriesItsBadgeCard() {
        val result =
            answer(
                """{"version":1,"title":"Private PR","card":{"root":"status","elements":{"status":{"type":"Badge","props":{"text":"Approved"}}}},""" +
                    """"url":"https://preview.example.test/pr/1","appName":"Preview App"}""",
            )["https://preview.example.test/pr/1"] as AppLinkResult.Card
        assertEquals("Private PR", result.title)
        assertEquals(
            RaftAppLinkCard(root = "status", elements = mapOf("status" to RaftAppLinkCardElement.Badge("Approved"))),
            result.card,
        )
    }

    @Test
    fun theWebsStatusCardKeepsItsTreeAndTones() {
        val result =
            answer("""{"version":1,"url":"https://app.example.test/pr/123","appName":"Sample App","title":"PR 123: Fix sign-in","card":$statusCard}""")
                .values.single() as AppLinkResult.Card
        val parsed = assertNotNull(result.card)
        assertEquals(1, parsed.version)
        assertEquals(RaftAppLinkCardElement.Stack(listOf("state", "detail")), parsed.element(parsed.root))
        assertEquals(RaftAppLinkCardElement.Text("Ready for review", RaftAppLinkTextTone.Muted), parsed.element("detail"))
        assertEquals(listOf("stack", "state", "detail"), parsed.elements.keys.toList())
        // Without a card the preview is still a chip; tone defaults to `default`.
        assertNull((answer("""{"version":1,"url":"https://a.example.test/1","appName":"A","title":"T"}""").values.single() as AppLinkResult.Card).card)
        assertEquals(
            RaftAppLinkCardElement.Text("Ready", RaftAppLinkTextTone.Default),
            card("""{"root":"t","elements":{"t":{"type":"Text","props":{"text":"Ready"}}}}""")!!.element("t"),
        )
    }

    @Test
    fun theSharedCatalogVectors() {
        val valid = """{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["text"]},"text":{"type":"Text","props":{"text":"Ready"}}}}"""
        assertNotNull(card(valid))
        val invalid =
            listOf(
                """{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["text"]},"text":{"type":"Text","props":{"text":"Ready"}}},"state":{"secret":"no"}}""",
                """{"root":"text","elements":{"text":{"type":"Text","props":{"text":{"${'$'}state":"/secret"}}}}}""",
                """{"root":"text","elements":{"text":{"type":"Text","props":{"text":"x"},"on":{"press":{"action":"fetch"}}}}}""",
                """{"root":"x","elements":{"x":{"type":"Stack","props":{},"children":["x"]}}}""",
                """{"root":"x","elements":{"x":{"type":"iframe","props":{"src":"https://evil.test"}}}}""",
                """{"root":"root","elements":{"root":{"type":"Stack","props":{},"children":["missing"]}}}""",
            )
        invalid.forEach { assertNull(card(it), it) }
    }

    @Test
    fun aStrictPreviewWithAnUnreadableCardDropsThePreviewsButNotThePrompts() {
        val good = """{"version":1,"url":"https://a.example.test/1","appName":"A","title":"T"}"""
        val prompt = """"prompts":[{"url":"https://b.example.test/1","app":{"id":"b","name":"B","icon":null}}]"""
        for (bad in listOf(
            """{"version":1,"url":"https://a.example.test/2","appName":"A","title":"T","card":{"root":"x","elements":{"x":{"type":"Button","props":{}}}}}""",
            """{"version":1,"url":"https://a.example.test/2","appName":"A","title":"T","card":null}""",
            """{"version":1,"url":"https://a.example.test/2","appName":"A","title":"T","icon":"https://a.example.test/i.png"}""",
            """{"version":"1","url":"https://a.example.test/2","appName":"A","title":"T"}""",
        )) {
            val results = parseAppLinkPreviewAnswer("""{"previews":[$good,$bad],$prompt}""")!!
            assertEquals(listOf("https://b.example.test/1"), results.keys.toList(), bad)
        }
        assertTrue(parseAppLinkPreviewAnswer("""{"previews":[$good],$prompt}""")!!["https://a.example.test/1"] is AppLinkResult.Card)
    }
}
