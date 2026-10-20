package build.raft.app.markdown

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Teeth for the Option B P1 v3 reconnaissance census (task #20, reviewed spec
 * by XiShi, threads fe23d152 / 9e1fcbc6 / 806dfec3). Contracts under test:
 * - predicate: scouting whitelist — every Markdown/HTML/entity/URL/email/tag/
 *   escape candidate rejected; formatted-as-candidate is the failure mode;
 * - commit semantics: onRemembered submits ONLY when a valid render cost
 *   arrived; abandoned passes and rows without a valid render change NO
 *   field (valid-unique-row basis across all six aggregates); a valid 0us
 *   cost is distinguishable from unset;
 * - per-open instance model: construction is the reset — no callback-order
 *   dependency; dispose/re-enter counts once per instance; a new instance
 *   counts afresh; two interleaved sessions cannot cross-pollute;
 * - single content-free aggregate emission with locked field vocabulary;
 * - gate counterfactual seam: predicateCalls.
 */
class RaftMarkdownPlainCandidateCensusTest {
    @BeforeTest
    fun setUp() {
        RaftMarkdownPlainCandidatePredicate.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        RaftMarkdownPlainCandidatePredicate.resetForTest()
    }

    private fun field(line: String, key: String): String? =
        Regex("$key=(\\S+)").find(line)?.groupValues?.get(1)

    @Test
    fun everyMarkdownConstructIsRejected() {
        val formatted = listOf(
            "`code`", "has ` tick", "*em*", "_em_", "# heading", "> quote",
            "a | b", "[link](x)", "plain (parens)", "<div>", "~~strike~~",
            "@mention", "a & b", "back\\slash", "- item", "+ item",
            "a - b", "key = value", "time: now", "path/segment",
            "1. ordered item", "12. also ordered",
            "two\nlines", "tab\there", "crlf\r\n",
            "double  space", " leading space", "trailing space ",
            "http://example.com", "https://x", "www.example.com",
            "WWW.EXAMPLE.COM", "user@host.tld", "&amp;", "![alt](img)",
            "---", "===", "    indented code", ""
        )
        for (body in formatted) {
            assertFalse(
                RaftMarkdownPlainCandidatePredicate.isConservativePlainCandidate(body),
                "must be rejected: ${body.replace("\n", "\\n").replace("\r", "\\r")}"
            )
        }
    }

    @Test
    fun plainProseIsACandidate() {
        val plain = listOf(
            "hello world",
            "Deploy finished, all green.",
            "中文句子，标点齐全。",
            "混合 mixed 文本 123!",
            "他说：“可以了！”",
            "Wait, really? Yes; truly."
        )
        for (body in plain) {
            assertTrue(
                RaftMarkdownPlainCandidatePredicate.isConservativePlainCandidate(body),
                "must be a candidate: $body"
            )
        }
    }

    @Test
    fun abandonedFirstPassLeavesNoTraceAndRetryCommitsOnce() {
        val emitted = mutableListOf<String>()
        val session = RaftMarkdownPlainCandidateSession(emit = emitted::add)

        val abandoned = RaftMarkdownRowCensusToken(session, "row-1", candidate = true, chars = 11)
        abandoned.recordValidRender(9_999)
        abandoned.onAbandoned()
        session.flush()
        assertEquals("0", field(emitted.last(), "totalRows"))

        val retry = RaftMarkdownRowCensusToken(session, "row-1", candidate = true, chars = 11)
        retry.recordValidRender(1_200)
        retry.onRemembered()
        session.flush()
        val line = emitted.last()
        assertEquals("1", field(line, "totalRows"))
        assertEquals("1", field(line, "candidateRows"))
        assertEquals("1200", field(line, "candidateRenderUs"))
    }

    @Test
    fun committedRowWithoutValidRenderChangesNoField() {
        val emitted = mutableListOf<String>()
        val session = RaftMarkdownPlainCandidateSession(emit = emitted::add)

        // Committed composition, but no valid render cost ever arrived
        // (e.g. sink suppressed or render aborted): all six fields unchanged.
        RaftMarkdownRowCensusToken(session, "row-1", candidate = true, chars = 25).onRemembered()
        session.flush()
        val line = emitted.single()
        assertEquals("0", field(line, "totalRows"))
        assertEquals("0", field(line, "candidateRows"))
        assertEquals("0", field(line, "totalChars"))
        assertEquals("0", field(line, "candidateChars"))
        assertEquals("0", field(line, "totalRenderUs"))
        assertEquals("0", field(line, "candidateRenderUs"))
    }

    @Test
    fun validZeroCostIsDistinguishableFromUnset() {
        val emitted = mutableListOf<String>()
        val session = RaftMarkdownPlainCandidateSession(emit = emitted::add)
        RaftMarkdownRowCensusToken(session, "row-1", candidate = true, chars = 8)
            .apply { recordValidRender(0) }.onRemembered()
        session.flush()
        val line = emitted.single()
        assertEquals("1", field(line, "totalRows"))
        assertEquals("0", field(line, "totalRenderUs"))
    }

    @Test
    fun disposeAndReenterCountsOncePerInstanceAndAfreshOnNewInstance() {
        val emitted = mutableListOf<String>()
        val open1 = RaftMarkdownPlainCandidateSession(emit = emitted::add)
        val tokenA = RaftMarkdownRowCensusToken(open1, "row-1", candidate = false, chars = 40)
        tokenA.recordValidRender(2_000)
        tokenA.onRemembered()
        tokenA.onForgotten() // lazy dispose
        val tokenB = RaftMarkdownRowCensusToken(open1, "row-1", candidate = false, chars = 40)
        tokenB.recordValidRender(500)
        tokenB.onRemembered() // re-enter: first-commit-wins
        open1.flush()
        var line = emitted.last()
        assertEquals("1", field(line, "totalRows"))
        assertEquals("2000", field(line, "totalRenderUs"))

        // New open = new instance: same key counts afresh, no reset call.
        val open2 = RaftMarkdownPlainCandidateSession(emit = emitted::add)
        RaftMarkdownRowCensusToken(open2, "row-1", candidate = false, chars = 40)
            .apply { recordValidRender(700) }.onRemembered()
        open2.flush()
        line = emitted.last()
        assertEquals("1", field(line, "totalRows"))
        assertEquals("700", field(line, "totalRenderUs"))
    }

    @Test
    fun interleavedSessionsCannotCrossPollute() {
        val emittedA = mutableListOf<String>()
        val emittedB = mutableListOf<String>()
        val openA = RaftMarkdownPlainCandidateSession(emit = emittedA::add)
        val openB = RaftMarkdownPlainCandidateSession(emit = emittedB::add)

        // Interleaved commits against both concurrently-alive opens.
        RaftMarkdownRowCensusToken(openA, "row-1", candidate = true, chars = 10)
            .apply { recordValidRender(1_000) }.onRemembered()
        RaftMarkdownRowCensusToken(openB, "row-9", candidate = false, chars = 70)
            .apply { recordValidRender(7_000) }.onRemembered()
        RaftMarkdownRowCensusToken(openA, "row-2", candidate = false, chars = 20)
            .apply { recordValidRender(2_000) }.onRemembered()

        openA.flush()
        openB.flush()
        val a = emittedA.single()
        val b = emittedB.single()
        assertEquals("2", field(a, "totalRows"))
        assertEquals("3000", field(a, "totalRenderUs"))
        assertEquals("1", field(b, "totalRows"))
        assertEquals("7000", field(b, "totalRenderUs"))
    }

    @Test
    fun aggregatesShareTheValidUniqueRowBasisAcrossAllSixFields() {
        val emitted = mutableListOf<String>()
        val session = RaftMarkdownPlainCandidateSession(emit = emitted::add)
        RaftMarkdownRowCensusToken(session, "plain", candidate = true, chars = 10)
            .apply { recordValidRender(1_000) }.onRemembered()
        RaftMarkdownRowCensusToken(session, "rich", candidate = false, chars = 30)
            .apply { recordValidRender(4_000) }.onRemembered()
        // Committed but never valid: excluded from ALL fields uniformly.
        RaftMarkdownRowCensusToken(session, "no-valid", candidate = true, chars = 99).onRemembered()
        session.flush()
        val line = emitted.single()
        assertEquals("2", field(line, "totalRows"))
        assertEquals("1", field(line, "candidateRows"))
        assertEquals("40", field(line, "totalChars"))
        assertEquals("10", field(line, "candidateChars"))
        assertEquals("5000", field(line, "totalRenderUs"))
        assertEquals("1000", field(line, "candidateRenderUs"))
    }

    @Test
    fun onlyAggregateLinesAreEmittedAndTheyAreContentFree() {
        val emitted = mutableListOf<String>()
        val session = RaftMarkdownPlainCandidateSession(emit = emitted::add)
        RaftMarkdownRowCensusToken(session, "secret-key", candidate = true, chars = 16)
            .apply { recordValidRender(1_000) }.onRemembered()
        assertTrue(emitted.isEmpty(), "no per-row emission")
        session.flush()
        val line = emitted.single()
        assertTrue(line.startsWith("SlockMarkdownSpike phase=markdown.plain_candidate.open_aggregate "))
        assertFalse("secret" in line)
        val fields = line.removePrefix("SlockMarkdownSpike ").split(" ")
            .map { it.substringBefore("=") }
        assertEquals(
            listOf(
                "phase", "flushSeq", "totalRows", "candidateRows",
                "totalChars", "candidateChars", "totalRenderUs", "candidateRenderUs"
            ),
            fields
        )
    }

    @Test
    fun zeroPredicateCallsWithoutInvocation() {
        assertEquals(0, RaftMarkdownPlainCandidatePredicate.predicateCalls)
    }

    @Test
    fun offPathProviderSetIsTheBaseArrayByIdentity() {
        // Round-4 contract: with a null census value the base ProvidedValue
        // array is returned BY IDENTITY - no copy, no appended entry, no new
        // ProvidedValue - so the diagnostics-OFF provider scope is unchanged.
        val base = arrayOf<androidx.compose.runtime.ProvidedValue<*>>(
            LocalRaftMarkdownPlainCandidateSession provides RaftMarkdownPlainCandidateSession()
        )
        val off = raftMarkdownProvidedValuesWith(
            base, LocalRaftMarkdownPlainCandidateSession, null
        )
        assertTrue(off === base, "OFF must return the base array by identity")
    }

    @Test
    fun onPathProviderSetAppendsExactlyTheCensusLocal() {
        val base = arrayOf<androidx.compose.runtime.ProvidedValue<*>>()
        val session = RaftMarkdownPlainCandidateSession()
        val on = raftMarkdownProvidedValuesWith(
            base, LocalRaftMarkdownPlainCandidateSession, session
        )
        assertEquals(1, on.size)
    }
}
