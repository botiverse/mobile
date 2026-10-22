package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ReadReceiptPeerKind
import build.raft.app.model.ScopeReadUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * read_receipts_v0 wire contract. Payload shapes are the ones the server tests assert, botiverse/slock
 * staging dac70954080092d54811442e24f900b2bb5df0bc, packages/server/src/routes/channels.inbox.api.test.ts
 * lines 1970-2455 (the `GET /api/channels/:id` detail spreads `getPeerReadHydrate`), with the seeded ids
 * replaced by readable literals; the surrounding detail fields are a trimmed channel detail.
 */
class PeerReadReceiptsContractTest {
    private fun detail(extra: String) =
        """{"id":"ch-1","name":"receipt-channel","type":"private","serverId":"srv-1","joined":true,
            "lastReadSeq":3,"activityMuteSupported":true$extra}"""

    @Test fun agentOnlyPeerStatesHydratePerPeer() {
        // "exposes ONLY agent watermarks (never human)": [{ peerKind: "agent", peerId: agent.id, maxReadSeq: second.seq }]
        val scope = parsePeerReadReceiptHydrate(
            detail(""","peerReadStates":[{"peerKind":"agent","peerId":"agent-1","maxReadSeq":2}]"""),
        )
        val peers = assertIs<PeerReadReceiptScope.Peers>(scope).peers
        assertEquals(listOf(PeerReadState(ReadReceiptPeerKind.Agent, "agent-1", 2)), peers)
    }

    @Test fun dmPeerWatermarkAndThreadParentHydrate() {
        // "hydrates a DM peer watermark" and "covers threads through their parent channel" (maxReadSeq 0 before any read).
        val dm = parsePeerReadReceiptHydrate(
            """{"id":"dm-1","type":"dm","peerReadStates":[{"peerKind":"agent","peerId":"agent-dm","maxReadSeq":1}]}""",
        )
        assertEquals(1L, assertIs<PeerReadReceiptScope.Peers>(dm).peers.single().maxReadSeq)
        val thread = parsePeerReadReceiptHydrate(
            """{"id":"th-1","type":"thread","parentChannelId":"ch-1",
                "peerReadStates":[{"peerKind":"agent","peerId":"agent-1","maxReadSeq":0}]}""",
        )
        assertEquals(0L, assertIs<PeerReadReceiptScope.Peers>(thread).peers.single().maxReadSeq)
    }

    @Test fun offScopesCarryNoHydrateAndRemovedPeersLeaveAnEmptyScope() {
        // Flag off, #all, a thread under #all: neither key.
        assertNull(parsePeerReadReceiptHydrate(detail("")))
        // "removed peers must disappear even when cursor rows remain": peerReadStates: [] is an enabled, empty scope.
        assertEquals(PeerReadReceiptScope.Peers(emptyList()), parsePeerReadReceiptHydrate(detail(""","peerReadStates":[]""")))
    }

    @Test fun largeScopesDegradeToTheAnonymousSummary() {
        // "degrades large scopes": LIMIT + 2 = 52 agents, one read through `first`.
        val large = parsePeerReadReceiptHydrate(
            detail(""","peerReadSummary":{"peerCount":52,"readCountAtSeq":[{"seq":0,"count":52},{"seq":1,"count":1}]}"""),
        )
        val summary = assertIs<PeerReadReceiptScope.Summary>(large).summary
        assertEquals(52, summary.peerCount)
        assertEquals(listOf(PeerReadCountAtSeq(0, 52), PeerReadCountAtSeq(1, 1)), summary.readCountAtSeq)
        // "at exactly LIMIT+1 agents": already a summary on hydrate.
        val edge = parsePeerReadReceiptHydrate(
            detail(""","peerReadSummary":{"peerCount":51,"readCountAtSeq":[{"seq":0,"count":51}]}"""),
        )
        assertEquals(51, assertIs<PeerReadReceiptScope.Summary>(edge).summary.peerCount)
        assertEquals(PeerReadReceiptProjection(read = true, readCount = 1, peerCount = 52), projectPeerReadReceipt(large, 1))
        assertEquals(PeerReadReceiptProjection(read = false, readCount = 0, peerCount = 52), projectPeerReadReceipt(large, 2))
        // A summary scope never answers per agent.
        assertNull(projectAgentReadReceipt(large, "agent-1", 1))
    }

    @Test fun malformedHydratesDisableTheScopeInsteadOfHalfApplying() {
        val rejected =
            listOf(
                ""","peerReadStates":[],"peerReadSummary":{"peerCount":0,"readCountAtSeq":[]}""",
                ""","peerReadStates":[{"peerKind":"bot","peerId":"a","maxReadSeq":1}]""",
                ""","peerReadStates":[{"peerKind":"agent","peerId":"","maxReadSeq":1}]""",
                ""","peerReadStates":[{"peerKind":"agent","peerId":"a","maxReadSeq":-1}]""",
                ""","peerReadStates":[{"peerKind":"agent","peerId":"a","maxReadSeq":1.5}]""",
                ""","peerReadStates":[{"peerKind":"agent","peerId":"a","maxReadSeq":1},{"peerKind":"agent","peerId":"a","maxReadSeq":2}]""",
                ""","peerReadSummary":{"peerCount":3,"readCountAtSeq":[{"seq":4,"count":2},{"seq":4,"count":1}]}""",
                ""","peerReadSummary":{"peerCount":3,"readCountAtSeq":[{"seq":4,"count":2},{"seq":10,"count":3}]}""",
                ""","peerReadSummary":{"peerCount":3,"readCountAtSeq":[{"seq":4,"count":4}]}""",
                ""","peerReadStates":null""",
            )
        rejected.forEach { extra -> assertNull(parsePeerReadReceiptHydrate(detail(extra)), extra) }
        assertNull(parsePeerReadReceiptHydrate("not json"))
    }

    @Test fun scopeReadUpdatedIsSubscribedAndMapsOnlyTheTwoServerShapes() {
        assertTrue(RealtimeSocketEvents.ScopeReadUpdated in RealtimeSocketEvents.ServerWireEvents)
        assertEquals("scope_read:updated", RealtimeSocketEvents.ScopeReadUpdated)
        val mapper = RealtimeEventMapper(diagnosticsLogger = {})
        // "emits only advancing agent frames": { scopeId, peerKind: "agent", peerId, maxReadSeq }
        val advance = assertIs<ScopeReadUpdated>(
            mapper.map(
                RealtimeSocketEvents.ScopeReadUpdated,
                """{"scopeId":"ch-1","peerKind":"agent","peerId":"agent-1","maxReadSeq":3}""",
            ),
        )
        assertEquals(ScopeReadUpdated("ch-1", ReadReceiptPeerKind.Agent, "agent-1", 3), advance)
        // Large scope: { scopeId, summaryChanged: true } and no peer identity.
        val summary = assertIs<ScopeReadUpdated>(
            mapper.map(RealtimeSocketEvents.ScopeReadUpdated, """{"scopeId":"ch-1","summaryChanged":true}"""),
        )
        assertEquals(ScopeReadUpdated("ch-1", summaryChanged = true), summary)
        listOf(
            """{"scopeId":"ch-1","summaryChanged":false}""",
            """{"scopeId":"","summaryChanged":true}""",
            """{"scopeId":"ch-1","peerKind":"agent","peerId":"agent-1"}""",
            """{"scopeId":"ch-1","peerKind":"robot","peerId":"agent-1","maxReadSeq":3}""",
            """{"scopeId":"ch-1","peerKind":"agent","peerId":"","maxReadSeq":3}""",
            """{"scopeId":"ch-1","peerKind":"agent","peerId":"agent-1","maxReadSeq":-1}""",
        ).forEach { payload -> assertNull(mapper.map(RealtimeSocketEvents.ScopeReadUpdated, payload), payload) }
        // Decoration of the open list only: no Home or unread work.
        assertFalse(advance.updatesHomeSnapshot())
        assertFalse(advance.affectsActivityUnreadSummary())
    }

    @Test fun agentProjectionIsUnknownRatherThanUnreadWithoutEvidence() {
        val scope = PeerReadReceiptScope.Peers(
            listOf(
                PeerReadState(ReadReceiptPeerKind.Agent, "agent-1", 9),
                PeerReadState(ReadReceiptPeerKind.Human, "agent-2", 99),
            ),
        )
        assertEquals(true, projectAgentReadReceipt(scope, "agent-1", 9))
        assertEquals(false, projectAgentReadReceipt(scope, "agent-1", 10))
        // peerKind is load-bearing: a human peer never satisfies an agent mention.
        assertNull(projectAgentReadReceipt(scope, "agent-2", 1))
        assertNull(projectAgentReadReceipt(scope, "agent-3", 1))
        assertNull(projectAgentReadReceipt(scope, "agent-1", null))
        assertNull(projectAgentReadReceipt(null, "agent-1", 1))
        assertEquals(PeerReadReceiptProjection(false, 0, 0), projectPeerReadReceipt(scope, null))
    }
}
