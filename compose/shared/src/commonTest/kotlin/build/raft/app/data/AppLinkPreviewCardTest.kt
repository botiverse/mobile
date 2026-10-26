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
    fun limitsMatchTheSchema() {
        fun chain(depth: Int): String {
            val ids = (0..depth).map { "n$it" }
            val elements =
                ids.mapIndexed { index, id ->
                    if (index == depth) """"$id":{"type":"Badge","props":{"text":"end"}}"""
                    else """"$id":{"type":"Stack","props":{},"children":["${ids[index + 1]}"]}"""
                }
            return """{"root":"n0","elements":{${elements.joinToString(",")}}}"""
        }
        assertNotNull(card(chain(4)), "four levels below the root")
        assertNull(card(chain(5)), "five levels below the root")
        fun flat(count: Int, children: Int = count - 1): String {
            val leaves = (1 until count).map { "l$it" }
            val leafJson = leaves.joinToString(",") { """"$it":{"type":"Badge","props":{"text":"x"}}""" }
            val stack = """"root":{"type":"Stack","props":{},"children":[${leaves.take(children).joinToString(",") { "\"$it\"" }}]}"""
            return """{"root":"root","elements":{$stack${if (leafJson.isEmpty()) "" else ",$leafJson"}}}"""
        }
        // One Stack holds at most 12 children; a card at most 16 elements.
        assertNotNull(card(flat(13)))
        assertNull(card(flat(14)), "13 children")
        // Root + two Stacks of 7 and [bLeaves] Badges: 16 elements with 6, 17 with 7.
        fun twoStacks(bLeaves: Int): String {
            fun leaves(prefix: String, count: Int) = (1..count).map { "$prefix$it" }
            fun stack(id: String, children: List<String>) =
                """"$id":{"type":"Stack","props":{},"children":[${children.joinToString(",") { "\"$it\"" }}]}"""
            val all = leaves("a", 7) + leaves("b", bLeaves)
            val elements =
                listOf(stack("r", listOf("a", "b")), stack("a", leaves("a", 7)), stack("b", leaves("b", bLeaves))) +
                    all.map { """"$it":{"type":"Badge","props":{"text":"x"}}""" }
            return """{"root":"r","elements":{${elements.joinToString(",")}}}"""
        }
        assertEquals(16, card(twoStacks(6))!!.elements.size)
        assertNull(card(twoStacks(7)), "17 elements")
        // An element no path reaches, or one reached twice, is not one tree.
        assertNull(card("""{"root":"a","elements":{"a":{"type":"Badge","props":{"text":"x"}},"b":{"type":"Badge","props":{"text":"y"}}}}"""))
        assertNull(card("""{"root":"r","elements":{"r":{"type":"Stack","props":{},"children":["a","a"]},"a":{"type":"Badge","props":{"text":"x"}}}}"""))
    }

    @Test
    fun keysPropsAndTextLengthsAreStrict() {
        fun text(props: String) = card("""{"root":"t","elements":{"t":{"type":"Text","props":$props}}}""")
        fun badge(text: String) = card("""{"root":"b","elements":{"b":{"type":"Badge","props":{"text":"$text"}}}}""")
        assertNotNull(text("""{"text":"${"a".repeat(500)}"}"""))
        assertNull(text("""{"text":"${"a".repeat(501)}"}"""))
        assertNull(text("""{"text":""}"""))
        assertNull(text("""{"text":"x","tone":"warning"}"""), "warning is v2")
        assertNull(text("""{"text":"x","tone":null}"""))
        assertNull(text("""{"text":1}"""))
        assertNotNull(badge("a".repeat(60)))
        assertNull(badge("a".repeat(61)))
        assertNull(card("""{"root":"b","elements":{"b":{"type":"Badge","props":{"text":"x","tone":"neutral"}}}}"""), "Badge tone is v2")
        assertNull(card("""{"root":"s","elements":{"s":{"type":"Stack","props":{"direction":"row"}}}}"""), "Stack props are v2")
        assertNull(card("""{"root":"s","elements":{"s":{"type":"Stack","props":{},"children":null}}}"""))
        assertNull(card("""{"root":"t","elements":{"t":{"type":"Text","props":{"text":"x"},"children":[]}}}"""))
        assertNotNull(card("""{"root":"s","elements":{"s":{"type":"Stack","props":{}}}}"""), "children are optional")
        // Ids: the key pattern, and no JavaScript prototype names.
        assertNull(card("""{"root":"1a","elements":{"1a":{"type":"Badge","props":{"text":"x"}}}}"""))
        assertNull(card("""{"root":"constructor","elements":{"constructor":{"type":"Badge","props":{"text":"x"}}}}"""))
        assertNotNull(card("""{"root":"${"a".repeat(64)}","elements":{"${"a".repeat(64)}":{"type":"Badge","props":{"text":"x"}}}}"""))
        assertNull(card("""{"root":"${"a".repeat(65)}","elements":{"${"a".repeat(65)}":{"type":"Badge","props":{"text":"x"}}}}"""))
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
