package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.ReferenceLinkHandlerImpl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Teeth for the F1 document-scoping premise (task #20 PR-2, reviewed by
 * XiShi): ReferenceLinkHandlerImpl carries a mutable stored map with no
 * clear, so the default handler must be created per document — never shared
 * globally. The composition side of the contract (Markdown()'s default is
 * `remember(state) { ReferenceLinkHandlerImpl() }` and
 * rememberMarkdownParserState is keyed on content, so a document switch gets
 * a fresh state and therefore a fresh handler) is structural; this file
 * locks the pure invariant it depends on: instances are isolated, and a
 * label defined in document A must not resolve through document B's handler.
 */
class RaftMarkdownReferenceLinkHandlerScopeTest {
    @Test
    fun freshInstanceDoesNotSeeAnotherDocumentsDefinitions() {
        val docA = ReferenceLinkHandlerImpl()
        docA.store("shared-label", "https://doc-a.example/target")

        val docB = ReferenceLinkHandlerImpl()
        // Doc B never defined the label: lookup must NOT leak doc A's
        // destination (find falls back to empty string).
        assertEquals("", docB.find("shared-label"))
        // Doc A keeps its own definition.
        assertEquals("https://doc-a.example/target", docA.find("shared-label"))
    }

    @Test
    fun sameInstanceReusedAcrossRecompositionKeepsDefinitions() {
        val handler = ReferenceLinkHandlerImpl()
        handler.store("label", "https://example/target")
        // Pure recomposition of the same document reuses the instance — the
        // definitions written during earlier passes must remain readable.
        assertEquals("https://example/target", handler.find("label"))
        assertEquals("https://example/target", handler.find("LABEL"))
    }
}
