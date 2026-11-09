package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * ArtifactPreview `excerpt`'s `blocks` (botiverse/slock staging `artifactExcerptBlocks.ts`
 * `parseArtifactExcerptBlocks`, #13472 / #13488, Remy's rule): a block type this client doesn't
 * know is left out and the rest drawn; a known type that isn't valid, or nothing usable left,
 * falls back to the excerpt's text; the card is never dropped.
 */
class ArtifactExcerptBlocksTest {
    private fun blocks(json: String): List<ArtifactExcerptBlock>? = parseArtifactExcerptBlocks(Json.parseToJsonElement(json))

    private val cardId = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"

    private fun summaryOf(preview: String): ArtifactSummary {
        val card =
            """
            {"id":"$cardId","url":"https://app.raft.build/artifacts/x","title":"Plan","kind":"markdown",
             "version":2,"pinned":false,"latestVersion":2,
             "author":{"type":"agent","id":"a1","name":"lagoon","displayName":"Lagoon","avatarUrl":null},
             "createdAt":"2026-11-01T10:00:00.000Z","updatedAt":"2026-11-02T10:00:00.000Z","commentCount":0,
             "home":{"kind":"conversation","conversation":{"id":"c1","name":"proj-workspace","type":"channel"}},
             "preview":$preview}
            """.trimIndent()
        val answers = parseArtifactSummariesAnswer("""{"kind":"artifact","summaries":[{"ref":{"id":"$cardId"},"ok":true,"summary":$card}]}""")!!
        return assertIs<ArtifactSummaryAnswer.Ok>(answers[cardId], "the card is never dropped").summary
    }

    @Test
    fun everyKnownBlockTypeIsRead() {
        val parsed =
            blocks(
                """
                [
                  {"type":"heading","level":1,"spans":[{"text":"Launch plan"}]},
                  {"type":"paragraph","spans":[{"text":"Ship "},{"text":"Friday","bold":true},{"text":" with ","italic":true},{"text":"v2","code":true}]},
                  {"type":"quote","spans":[{"text":"Keep it small"}]},
                  {"type":"list","items":[
                    {"level":0,"order":1,"spans":[{"text":"Freeze"}]},
                    {"level":1,"checked":true,"spans":[{"text":"QA"}]},
                    {"level":0,"spans":[{"text":"Notes"}]}
                  ]},
                  {"type":"code","lines":["val a = 1","val b = 2"]},
                  {"type":"table","header":[[{"text":"Region"}],[]],"rows":[[[{"text":"EU"}],[{"text":"12"}]]]},
                  {"type":"image","alt":""},
                  {"type":"images","count":3},
                  {"type":"diagram"},
                  {"type":"rule","extra":"ignored like zod strips it"}
                ]
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                ArtifactExcerptBlock.Heading(1, listOf(ArtifactExcerptSpan("Launch plan"))),
                ArtifactExcerptBlock.Paragraph(
                    listOf(
                        ArtifactExcerptSpan("Ship "),
                        ArtifactExcerptSpan("Friday", bold = true),
                        ArtifactExcerptSpan(" with ", italic = true),
                        ArtifactExcerptSpan("v2", code = true),
                    ),
                ),
                ArtifactExcerptBlock.Quote(listOf(ArtifactExcerptSpan("Keep it small"))),
                ArtifactExcerptBlock.ListBlock(
                    listOf(
                        ArtifactExcerptListItem(level = 0, order = 1, spans = listOf(ArtifactExcerptSpan("Freeze"))),
                        ArtifactExcerptListItem(level = 1, checked = true, spans = listOf(ArtifactExcerptSpan("QA"))),
                        ArtifactExcerptListItem(level = 0, spans = listOf(ArtifactExcerptSpan("Notes"))),
                    ),
                ),
                ArtifactExcerptBlock.Code(listOf("val a = 1", "val b = 2")),
                ArtifactExcerptBlock.Table(
                    header = listOf(listOf(ArtifactExcerptSpan("Region")), emptyList()),
                    rows = listOf(listOf(listOf(ArtifactExcerptSpan("EU")), listOf(ArtifactExcerptSpan("12")))),
                ),
                ArtifactExcerptBlock.Image(""),
                ArtifactExcerptBlock.Images(3),
                ArtifactExcerptBlock.Diagram,
                ArtifactExcerptBlock.Rule,
            ),
            parsed,
        )
    }

    @Test
    fun anUnknownBlockTypeIsSkippedAndTheRestDrawn() {
        val parsed =
            blocks(
                """
                [
                  {"type":"heading","level":2,"spans":[{"text":"Plan"}]},
                  {"type":"callout","tone":"warning","spans":[{"text":"a newer Server's block"}]},
                  {"type":"paragraph","spans":[{"text":"Body"}]},
                  {"type":"math","tex":"x^2"}
                ]
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                ArtifactExcerptBlock.Heading(2, listOf(ArtifactExcerptSpan("Plan"))),
                ArtifactExcerptBlock.Paragraph(listOf(ArtifactExcerptSpan("Body"))),
            ),
            parsed,
        )
    }

    @Test
    fun anInvalidBlockOfAKnownTypeFailsTheList() {
        val invalid =
            listOf(
                """{"type":"heading","level":7,"spans":[{"text":"too deep"}]}""",
                """{"type":"heading","level":"1","spans":[{"text":"level as a string"}]}""",
                """{"type":"paragraph","spans":[]}""",
                """{"type":"paragraph","spans":[{"text":""}]}""",
                """{"type":"paragraph","spans":[{"text":"x","bold":false}]}""",
                """{"type":"list","items":[{"level":2,"spans":[{"text":"x"}]}]}""",
                """{"type":"list","items":[{"level":0,"order":1.5,"spans":[{"text":"x"}]}]}""",
                """{"type":"code","lines":[]}""",
                """{"type":"code","lines":["1","2","3","4","5"]}""",
                """{"type":"table","header":[],"rows":[]}""",
                """{"type":"images","count":1}""",
                """{"type":"image"}""",
                // No type, or one that isn't a string, is not a block (not "a newer Server's").
                """{"spans":[{"text":"no type"}]}""",
                """{"type":3}""",
                "\"just a string\"",
            )
        for (block in invalid) {
            assertNull(blocks("""[{"type":"paragraph","spans":[{"text":"ok"}]},$block]"""), block)
        }
    }

    @Test
    fun nothingUsableIsNull() {
        assertNull(blocks("[]"), "empty")
        assertNull(blocks("""[{"type":"callout"},{"type":"math"}]"""), "only unknown types: nothing to draw")
        assertNull(blocks("""{"type":"paragraph"}"""), "not a list")
        assertNull(blocks("null"))
        assertNull(parseArtifactExcerptBlocks(null as JsonElement?))
        val seventeen = (1..17).joinToString(",") { """{"type":"rule"}""" }
        assertNull(blocks("[$seventeen]"), "more than 16 blocks")
    }

    @Test
    fun aCardReadsItsBlocks() {
        val summary =
            summaryOf(
                """{"kind":"excerpt","text":"Plan\nBody","blocks":[{"type":"heading","level":1,"spans":[{"text":"Plan"}]},{"type":"future","x":1},{"type":"paragraph","spans":[{"text":"Body"}]}]}""",
            )
        val excerpt = assertNotNull(summary.excerpt)
        assertEquals("Plan\nBody", excerpt.text)
        assertEquals(
            listOf(ArtifactExcerptBlock.Heading(1, listOf(ArtifactExcerptSpan("Plan"))), ArtifactExcerptBlock.Paragraph(listOf(ArtifactExcerptSpan("Body")))),
            excerpt.blocks,
        )
    }

    @Test
    fun aCardFallsBackToItsTextWhenItsBlocksCantBeDrawn() {
        // A known type that isn't valid: the text, the card kept.
        val invalid = summaryOf("""{"kind":"excerpt","text":"Plan","blocks":[{"type":"heading","level":0,"spans":[{"text":"Plan"}]}]}""")
        assertEquals(ArtifactExcerpt("Plan", blocks = null), invalid.excerpt)
        // Only blocks of a newer Server: nothing usable, the text.
        assertEquals(ArtifactExcerpt("Plan", blocks = null), summaryOf("""{"kind":"excerpt","text":"Plan","blocks":[{"type":"hologram"}]}""").excerpt)
        // Blocks of an unexpected shape: the card still shows with its text.
        assertEquals(ArtifactExcerpt("Plan", blocks = null), summaryOf("""{"kind":"excerpt","text":"Plan","blocks":"<h1>Plan</h1>"}""").excerpt)
        // A version from before blocks.
        assertEquals(ArtifactExcerpt("Plan", blocks = null), summaryOf("""{"kind":"excerpt","text":"Plan"}""").excerpt)
        // No text: no excerpt (web `card.preview.text` required), the card still shows.
        assertNull(summaryOf("""{"kind":"excerpt","text":"","blocks":[{"type":"rule"}]}""").excerpt)
        assertNull(summaryOf("""{"kind":"screenshot","desktop":"of_desktop","phone":null}""").excerpt)
    }
}
