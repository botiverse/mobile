package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
    fun sameInputsHitAndReturnTheSameDocument() {
        val body = "doc-cache-hit: ping @alice about the release"
        val (first, firstHit) = documentFor(body)
        val (second, secondHit) = documentFor(body)
        assertFalse(firstHit)
        assertTrue(secondHit)
        assertSame(first, second)
        assertEquals(
            buildRaftRichTextDocumentFromMarkdownPlan(
                source = body,
                content = first.content,
                tokenPlan =
                    RaftMarkdownInlineTokenRegistry.plan(first.content, emptySet(), mentionsValid, inlineContentScope = "scope-a")
            ),
            first,
            "cached document must equal a fresh build"
        )
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

    @Test
    fun lruKeepsAtMostMaxEntriesAndEvictsLeastRecentlyUsed() {
        val cache = RaftMarkdownLruCache<String, Int>(maxEntries = 2)
        cache.getOrPut("a") { 1 }
        cache.getOrPut("b") { 2 }
        assertEquals(1, cache.get("a")) // "a" is now the most recent
        cache.getOrPut("c") { 3 }
        assertEquals(2, cache.size)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
        assertEquals(1 to true, cache.getOrPut("a") { 99 })
    }

    @Test
    fun warmedDocumentIsWhatTheRowLooksUp() = runTest {
        val body = "doc-cache-warm: **notes** for @carol\n\n- one\n- two"
        val context = RaftMarkdownDocumentWarmContext(emptySet(), mentionsValid, null)
        val result = warmRaftMarkdownRichTextDocuments(listOf(RaftMarkdownDocumentWarmRequest(body, "scope-w")), context)
        assertEquals(RaftMarkdownDocumentWarmResult(requestedCount = 1, builtCount = 1, cacheHitCount = 0), result)
        val (_, hit) = documentFor(body, scope = "scope-w")
        assertTrue(hit, "row lookup after warm should hit")
        val again = warmRaftMarkdownRichTextDocuments(listOf(RaftMarkdownDocumentWarmRequest(body, "scope-w")), context)
        assertEquals(1, again.cacheHitCount)
    }
}
