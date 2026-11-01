package build.raft.syncrules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The facade's boundary conversions (the rules themselves are covered by commonTest on every target). */
class SyncRulesJsTest {
    private fun seg(low: String, high: String, oldest: Boolean = false) =
        SyncSegment(low, high, oldest, false, 1.0, "older")

    @Test
    fun seqsAbove2Pow53SurviveTheBoundaryExactly() {
        // 2^53 + 1 and + 2 touch; as JS numbers both would round to 2^53.
        val merged = unionSegment(arrayOf(seg("9007199254740990", "9007199254740993")), seg("9007199254740994", "9007199254740999"))
        assertEquals(listOf("9007199254740990" to "9007199254740999"), merged.map { it.lowSeq to it.highSeq })
        val apart = unionSegment(arrayOf(seg("9007199254740990", "9007199254740993")), seg("9007199254740995", "9007199254740999"))
        assertEquals(2, apart.size)
    }

    @Test
    fun aRawMessageWindowObjectIsReadLeniently() {
        val window: dynamic = js("({schemaVersion:1,domain:'receiver_visible_messages_v1',serverId:'s',scopeId:'c',coveredAfterSeq:0,coveredThroughSeq:'7',completeThroughLatest:true})")
        val coverage = coverageFromMessageWindow(window, "s", "c", false, "latest")!!
        assertEquals(listOf("1", "7", "true", "true", "latest"),
            listOf(coverage.lowSeq, coverage.highSeq, "${coverage.reachesOldest}", "${coverage.headAtVerify}", coverage.source))
        val unsafe: dynamic = js("({schemaVersion:1,domain:'receiver_visible_messages_v1',serverId:'s',scopeId:'c',coveredAfterSeq:0,coveredThroughSeq:2.5,completeThroughLatest:true})")
        assertNull(coverageFromMessageWindow(unsafe, "s", "c", false, "latest"))
        assertNull(coverageFromMessageWindow(null, "s", "c", false, "latest"))
    }

    @Test
    fun invalidInputThrowsWithTheRuleInTheMessage() {
        assertFailsWith<IllegalArgumentException> { unionSegment(arrayOf(seg("1", "5"), seg("6", "9")), seg("20", "21")) }
        assertFailsWith<IllegalArgumentException> { segmentOf(emptyArray(), "1e3") }
        assertFailsWith<IllegalArgumentException> { unionSegment(emptyArray(), SyncSegment("1", "2", false, false, 1.0, "bogus")) }
    }
}
