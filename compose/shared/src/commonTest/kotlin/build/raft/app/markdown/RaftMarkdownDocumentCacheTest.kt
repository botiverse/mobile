package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class RaftMarkdownDocumentCacheTest {
    private val mentionsValid: (ThreadMarkdownTag) -> Boolean = { it.kind == ThreadMarkdownTagKind.UserMention }
    private val nothingValid: (ThreadMarkdownTag) -> Boolean = { false }

    private fun documentFor(
        body: String,
        isTagValid: (ThreadMarkdownTag) -> Boolean = mentionsValid,
        highlightedMentions: Set<String> = emptySet(),
        resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
        scope: String = "scope-a",
    ): Pair<RaftRichTextDocument, Boolean> {
        val parserContent = RaftMarkdownRenderInputCache.parserContent(raftMarkdownPreparedBody(body)).parserContent
        val plan =
            RaftMarkdownInlineTokenRegistry.plan(
                parserContent,
                highlightedMentions,
                isTagValid,
                resolveTagLabel = resolveTagLabel,
                inlineContentScope = scope
            )
        return RaftMarkdownRenderInputCache.documentWithHit(source = body, content = parserContent, tokenPlan = plan)
    }

    @Test
    fun anyChangedInputMisses() {
        val body = "doc-cache-miss: ping @bob about the release"
        val (base, _) = documentFor(body)
        // (label, changed inputs) -> each must build a different document, never reuse [base].
        val variants: List<Pair<String, () -> Pair<RaftRichTextDocument, Boolean>>> =
            listOf(
                "mention no longer a member" to { documentFor(body, isTagValid = nothingValid) },
                "mention now highlighted" to { documentFor(body, highlightedMentions = setOf("bob")) },
                "member display label changed" to { documentFor(body, resolveTagLabel = { "Bobby" }) },
                "different row scope" to { documentFor(body, scope = "scope-b") },
                "body edited" to { documentFor("$body!") },
            )
        variants.forEach { (label, lookup) ->
            val (document, hit) = lookup()
            assertFalse(hit, "$label: expected a miss")
            assertNotSame(base, document, label)
            assertTrue(document != base, "$label: document must differ")
        }
        assertEquals(1, documentFor(body, isTagValid = mentionsValid).first.actions.size)
        assertEquals(0, documentFor(body, isTagValid = nothingValid).first.actions.size)
    }
}
