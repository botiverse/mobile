package build.raft.app.markdown

import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.compositionLocalOf
import com.tencent.kuiklybase.markdown.compose.RaftMarkdownRowRenderCostSink

/**
 * Task #20 Option B P1 v3: diagnostics-only reconnaissance census for the
 * fast-path decision gate (reviewed spec by XiShi, threads fe23d152 /
 * 9e1fcbc6 / 806dfec3). SCOUTING data, not a rendering contract — hence the
 * conservativePlainCandidate naming.
 *
 * v3 shape (review round 3): the census session is a PER-OPEN INSTANCE owned
 * by the Thread page — construction is the reset, so there is no global
 * collector, no begin/reset call, and no dependency on RememberObserver
 * callback order. The instance is provided via [LocalRaftMarkdownPlainCandidateSession]
 * only inside the Thread page subtree: markdown surfaces outside it (e.g.
 * file preview rows with their own performanceKey) see null and never
 * participate. Two interleaved opens hold two independent instances and
 * cannot cross-pollute.
 *
 * Dataflow:
 * - RaftMarkdownBody (gated: diagnostics ON only — the Local read itself is
 *   behind the gate) creates one [RaftMarkdownRowCensusToken] per row with
 *   the predicate verdict + exact char count, referencing the session
 *   instance, and provides it as the vendor row-render-cost sink.
 * - The vendor forwards renderNodesUs to the token STRICTLY on the valid
 *   finishRender path; the vendor reads the sink Local ONLY when its node
 *   timing is active (diagnostics OFF = zero Local read, zero work).
 * - The token buffers the cost as a NULLABLE pending value and submits to
 *   the session ONLY in [RememberObserver.onRemembered] AND only if a valid
 *   render cost actually arrived — a committed row without a valid render
 *   (e.g. sink suppressed, aborted render) contributes to NO field, keeping
 *   all six aggregates on the same valid-unique-row basis. A valid 0us cost
 *   is distinguishable from unset (non-null zero counts).
 * - The session dedupes by row key (in-memory only, never emitted),
 *   first-commit-wins; lazy dispose/re-enter counts once per open; a new
 *   open is a new instance and counts afresh.
 * - Emission: a SINGLE content-free open-level aggregate line per flush
 *   (cross-row totals only); the Thread page flushes from its latched
 *   first-content LaunchedEffect, which runs after the apply pass's
 *   observer callbacks.
 */
class RaftMarkdownPlainCandidateSession(
    private val emit: (String) -> Unit = { line -> println(line) },
) {
    private val seenKeys = HashSet<String>()
    private var flushSeq = 0
    private var totalRows = 0
    private var candidateRows = 0
    private var totalChars = 0L
    private var candidateChars = 0L
    private var totalRenderUs = 0L
    private var candidateRenderUs = 0L

    /** First committed valid submission per key wins within this open. */
    fun submitUnique(rowKey: String, candidate: Boolean, chars: Int, renderUs: Long) {
        if (!seenKeys.add(rowKey)) return
        totalRows++
        totalChars += chars
        totalRenderUs += renderUs
        if (candidate) {
            candidateRows++
            candidateChars += chars
            candidateRenderUs += renderUs
        }
    }

    /**
     * Emits the SINGLE open-level aggregate (content-free: cross-row totals
     * only, no keys, no per-row lengths). flushSeq lets analysis take the
     * last flush of an open if several fire.
     */
    fun flush() {
        flushSeq++
        emit(
            "SlockMarkdownSpike phase=markdown.plain_candidate.open_aggregate flushSeq=$flushSeq " +
                "totalRows=$totalRows candidateRows=$candidateRows " +
                "totalChars=$totalChars candidateChars=$candidateChars " +
                "totalRenderUs=$totalRenderUs candidateRenderUs=$candidateRenderUs"
        )
    }
}

/**
 * Provided by the Thread page around its content subtree (diagnostics ON
 * only); null everywhere else — non-Thread markdown surfaces never create
 * tokens and cannot write into a thread open's census.
 */
val LocalRaftMarkdownPlainCandidateSession =
    compositionLocalOf<RaftMarkdownPlainCandidateSession?> { null }

/**
 * Per-row census token. Commits to its session ONLY when the composition
 * commits (onRemembered) AND a valid render cost arrived; abandoned passes
 * and rows without a valid render leave zero trace in any field.
 */
class RaftMarkdownRowCensusToken(
    private val session: RaftMarkdownPlainCandidateSession,
    private val rowKey: String,
    private val candidate: Boolean,
    private val chars: Int,
) : RememberObserver, RaftMarkdownRowRenderCostSink {
    private var pendingValidRenderUs: Long? = null

    override fun recordValidRender(renderNodesUs: Long) {
        // Last valid render before commit wins; the session takes the first
        // COMMITTED submission per key, so this is the row's first committed
        // valid render cost within the open.
        pendingValidRenderUs = renderNodesUs
    }

    override fun onRemembered() {
        val validCost = pendingValidRenderUs ?: return // no valid render -> no fields change
        session.submitUnique(rowKey, candidate, chars, validCost)
    }

    override fun onAbandoned() {
        // Abandoned composition pass: no trace anywhere (reviewed).
    }

    override fun onForgotten() {
        // Row left composition (e.g. lazy dispose). The open-level fact is
        // already committed; re-entry creates a new token whose submission
        // the session's seen-key set ignores.
    }
}

object RaftMarkdownPlainCandidatePredicate {
    /** Test seam: total predicate invocations (proves gate-before-scan). */
    var predicateCalls: Int = 0
        internal set

    /** Test helper. */
    fun resetForTest() {
        predicateCalls = 0
    }

    /**
     * Ultra-conservative whitelist predicate (reviewed v2 spec). Every char
     * must belong to the enumerated safe set — control chars, Tab and
     * newlines are outside it (single-line by construction), and NO
     * Markdown/HTML/URL/tag trigger is safe: backtick, asterisk, underscore,
     * hash, gt/lt, pipe, brackets, ASCII parens, tilde, at, ampersand,
     * backslash, dash, plus, equals, colon and slash are all excluded, which
     * also makes URL ("http:"), email ("@") and entity ("&...;") candidates
     * impossible by construction. Structural triggers composed of safe chars
     * are rejected separately (GFM ordered-list prefix, www-autolink,
     * double/leading/trailing spaces). Over-rejecting ordinary punctuation
     * is accepted; a formatted row judged candidate is the tooth-killed
     * failure mode.
     */
    fun isConservativePlainCandidate(body: String): Boolean {
        predicateCalls++
        if (body.isEmpty()) return false
        for (ch in body) {
            val safe =
                ch == ' ' ||
                    ch in '0'..'9' ||
                    ch in 'a'..'z' ||
                    ch in 'A'..'Z' ||
                    ch == ',' || ch == '.' || ch == '!' || ch == '?' ||
                    ch == ';' || ch == '\'' || ch == '"' ||
                    // CJK unified ideographs + kana
                    ch in '一'..'鿿' ||
                    ch in '぀'..'ヿ' ||
                    // Explicitly enumerated CJK/fullwidth punctuation only —
                    // NOT the whole fullwidth block (it mirrors ASCII incl.
                    // trigger lookalikes ＊＠＃｀ which stay excluded).
                    ch == '、' || ch == '。' || ch == '，' || ch == '！' ||
                    ch == '？' || ch == '：' || ch == '；' || ch == '（' ||
                    ch == '）' || ch == '“' || ch == '”' || ch == '‘' ||
                    ch == '’' || ch == '…' || ch == '—'
            if (!safe) return false
        }
        if (body.first() == ' ' || body.last() == ' ') return false
        if ("  " in body) return false
        if (OrderedListPrefix.containsMatchIn(body)) return false
        if ("www." in body.lowercase()) return false
        return true
    }

    private val OrderedListPrefix = Regex("""^\d+\. """)
}

/**
 * OFF-zero-work provider composition (review round 4): appends the census
 * Local ONLY when the value exists. With a null value the BASE ARRAY IS
 * RETURNED BY IDENTITY — no new ProvidedValue, no array copy, no added
 * provider entry — so the diagnostics-OFF composition path is byte-identical
 * to the pre-census provider setup. Teeth lock the identity property.
 */
fun <T : Any> raftMarkdownProvidedValuesWith(
    base: Array<androidx.compose.runtime.ProvidedValue<*>>,
    local: androidx.compose.runtime.ProvidableCompositionLocal<T?>,
    value: T?,
): Array<androidx.compose.runtime.ProvidedValue<*>> =
    if (value == null) base else base + (local provides value)
