package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.RaftMarkdownAnnotationRevisionUnspecified
import com.tencent.kuiklybase.markdown.compose.raftMarkdownAnnotatedStringMemoKeys
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun unspecifiedSentinelDisablesMemoization() {
        assertNull(
            raftMarkdownAnnotatedStringMemoKeys(
                "content", "node", "style", settings, RaftMarkdownAnnotationRevisionUnspecified
            )
        )
    }

    @Test
    fun nullIsALegitimateRevisionNotTheSentinel() {
        assertNotNull(
            raftMarkdownAnnotatedStringMemoKeys("content", "node", "style", settings, null)
        )
    }

    @Test
    fun sentinelComparisonIsByIdentityNotEquality() {
        // Nothing equal-but-not-identical to the sentinel can disable the
        // memo: the check is identity (===). Any other object value enables
        // memoization.
        assertNotNull(
            raftMarkdownAnnotatedStringMemoKeys("content", "node", "style", settings, Any())
        )
    }

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

    @Test
    fun unchangedRevisionAsFreshEqualListYieldsEqualKeys() {
        // Simulates a resolver identity change with identical results: the
        // snapshot is a fresh list with equal content — keys must stay equal
        // so the memo reuses (remember compares keys with equals()).
        val a = raftMarkdownAnnotatedStringMemoKeys(
            "content", "node", "style", settings, listOf("alpha", null)
        )
        val b = raftMarkdownAnnotatedStringMemoKeys(
            "content", "node", "style", settings, mutableListOf<String?>("alpha", null).toList()
        )
        assertNotNull(a)
        assertNotNull(b)
        assertContentEquals(a, b, "equal-content revision must keep the memo keys equal (reuse)")
    }
}
