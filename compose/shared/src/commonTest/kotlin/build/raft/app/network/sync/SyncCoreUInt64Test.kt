package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncCoreUInt64Test {
    private val domain = SyncDomainConfig(
        name = "uint64-test",
        density = SyncDensity.Contiguous,
        initialState = { emptyList<String>() },
        fold = { state, event, _ ->
            @Suppress("UNCHECKED_CAST")
            (state as List<String>) + (event as String)
        },
        fromSnapshot = { snapshot -> snapshot.state },
    )

    @Test
    fun decimalBoundaryAcceptsTheFullUInt64RangeAndRejectsNonCanonicalInput() {
        assertEquals(0uL, "0".toSyncSeq())
        assertEquals(9_007_199_254_740_993uL, "9007199254740993".toSyncSeq())
        assertEquals(ULong.MAX_VALUE, "18446744073709551615".toSyncSeq())
        assertEquals("18446744073709551615", ULong.MAX_VALUE.toCanonicalDecimalString())

        listOf("", "00", "01", "+1", "-1", "18446744073709551616").forEach { candidate ->
            assertFailsWith<IllegalArgumentException>(candidate) { candidate.toSyncSeq() }
        }
    }

    @Test
    fun adjacencyAndDuplicateComparisonStayExactThroughUInt64Max() {
        val core = createSyncCore(SyncCoreConfig(listOf(domain)))
        val baseline = ULong.MAX_VALUE - 2uL
        assertEquals(
            SyncIngestOutcome.Applied("scope", baseline),
            core.ingestSnapshot(
                "uint64-test",
                SyncSnapshot("scope", baseline, state = emptyList<String>()),
            ),
        )
        assertEquals(
            SyncIngestOutcome.Applied("scope", ULong.MAX_VALUE - 1uL),
            core.ingestFrame(
                "uint64-test",
                SyncFrame("scope", ULong.MAX_VALUE - 1uL, event = "penultimate"),
            ),
        )
        assertEquals(
            SyncIngestOutcome.Applied("scope", ULong.MAX_VALUE),
            core.ingestFrame(
                "uint64-test",
                SyncFrame("scope", ULong.MAX_VALUE, event = "last"),
            ),
        )
        assertEquals(
            SyncIngestOutcome.DuplicateDropped("scope", ULong.MAX_VALUE),
            core.ingestFrame(
                "uint64-test",
                SyncFrame("scope", ULong.MAX_VALUE, event = "duplicate"),
            ),
        )
        assertEquals(listOf("penultimate", "last"), core.state("uint64-test", "scope"))
        assertEquals(emptyList(), core.pendingRequests())
    }

    @Test
    fun gapAndPendingDifferenceRemainExactAboveJavaScriptSafeInteger() {
        val core = createSyncCore(SyncCoreConfig(listOf(domain)))
        val baseline = "9007199254740992".toSyncSeq()
        val arriving = "9007199254740994".toSyncSeq()
        val missing = "9007199254740993".toSyncSeq()
        core.ingestSnapshot(
            "uint64-test",
            SyncSnapshot("scope", baseline, epoch = "epoch", state = emptyList<String>()),
        )

        assertEquals(
            SyncIngestOutcome.GapRepairRequested("scope", missing, missing),
            core.ingestFrame(
                "uint64-test",
                SyncFrame("scope", arriving, epoch = "epoch", event = "must-not-apply"),
            ),
        )
        assertEquals(
            listOf(
                SyncRequestDescription.Difference(
                    domain = "uint64-test",
                    scopeId = "scope",
                    sinceSeq = baseline,
                    epoch = "epoch",
                ),
            ),
            core.pendingRequests(),
        )
        assertEquals(emptyList<String>(), core.state("uint64-test", "scope"))
    }
}
