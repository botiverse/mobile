package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.raftMarkdownAnnotatedStringMemoKeys
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Teeth for the opt-in AnnotatedString memo contract (task #20 PR-2, second
 * review round by XiShi): `raftMarkdownAnnotatedStringMemoKeys` is the single
 * decision point used by both MarkdownParagraph and MarkdownText.
 * - The Unspecified sentinel (and ONLY it, by identity) disables the memo →
 *   legacy rebuild-every-recomposition semantics for generic callers whose
 *   annotators may be dynamic behind a stable identity.
 * - With a revision: same identity settings + revision A→B must yield
 *   different keys (rebuild), unchanged revision yields equal keys (reuse).
 * - null and structurally-equal values are legitimate revisions — no
 *   collision with the sentinel.
 */
class RaftMarkdownAnnotationRevisionTest {
    private val settings = Any() // identity-stable stand-in for AnnotatorSettings

    @Test
    fun sameIdentitySettingsRevisionChangeYieldsDifferentKeys() {
        val a = raftMarkdownAnnotatedStringMemoKeys(
            "content", "node", "style", settings, listOf("alpha")
        )
        val b = raftMarkdownAnnotatedStringMemoKeys(
            "content", "node", "style", settings, listOf("bravo")
        )
        assertNotNull(a)
        assertNotNull(b)
        assertFalse(a.contentEquals(b), "revision A->B must change the memo keys (force rebuild)")
    }
}
