package build.raft.app.markdown

import build.raft.app.isKnownThreadMarkdownTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An agent's Lounge as a ref, `lounge:@agent` (botiverse/slock #16279, `f2994cb90`). The first two
 * tests are the Lounge cases of `packages/shared/src/raftRefs.test.ts`, with its inputs and
 * expectations.
 */
class RaftLoungeRefTest {
    @Test
    fun loungeRefsParseAndFormatRoundTripEveryForm() {
        val cases =
            listOf(
                "lounge:@ops" to RaftLoungeRef.Lounge("ops"),
                "lounge:@运维" to RaftLoungeRef.Lounge("运维"),
                "lounge:@ops:abcd1234" to RaftLoungeRef.Thread("ops", "abcd1234"),
                "lounge:@ops:abc123" to RaftLoungeRef.Thread("ops", "abc123"),
                "lounge:@ops:#7" to RaftLoungeRef.Task("ops", 7),
                "lounge:@ops msg=0123abcd-ef" to RaftLoungeRef.Message("ops", "0123abcd-ef", threadParentShortId = null),
                "lounge:@ops:abcd1234 msg=0123abcd" to RaftLoungeRef.Message("ops", "0123abcd", threadParentShortId = "abcd1234"),
            )
        for ((raw, parsed) in cases) {
            assertEquals(parsed, parseRaftLoungeRef(raw), raw)
            assertEquals(raw, parsed.format(), raw)
        }
        assertEquals("lounge:@ops:#7", formatRaftLoungeTaskRef("ops", 7))
        // Web `taskRefConversationTarget`: the conversation the task ref names.
        assertEquals("lounge:@ops", parseRaftMarkdownTaskRefLabel(formatRaftLoungeTaskRef("ops", 7))?.conversationLabel)
        // Not lounge refs: no `@`, a dotted or kinded name, a non-hex suffix, a second separator.
        for (bad in listOf("lounge:ops", "lounge:@", "lounge:@ops.x", "lounge:@ops~agent", "lounge:@ops:xyz", "lounge:@ops:#0", "lounge:@ops:#7:#8")) {
            assertNull(parseRaftLoungeRef(bad), bad)
        }
    }

    @Test
    fun loungeRefsAreDetectedInTextTaskThreadAndTheLoungeItself() {
        val source =
            "ask in lounge:@ops, see lounge:@ops:abcd1234 and ${formatRaftLoungeTaskRef("ops", 3)}; not lounge:@ops.x or `lounge:@code`"
        val refs = source.threadInlineTagMatches()
        assertEquals(listOf("lounge:@ops", "lounge:@ops:abcd1234", formatRaftLoungeTaskRef("ops", 3)), refs.map { it.tag.label })
        assertEquals(refs.map { it.tag.label }, refs.map { source.substring(it.range) })
        assertEquals(
            listOf(ThreadMarkdownTagKind.Lounge, ThreadMarkdownTagKind.LoungeThread, ThreadMarkdownTagKind.Task),
            refs.map { it.tag.kind },
        )
        assertEquals(listOf("ops", "ops", "ops"), refs.map { it.tag.loungeAgentName })
    }

    @Test
    fun aMalformedLoungeTokenStaysTextAndNeverMentionsItsAgent() {
        // Web `createRaftMalformedLoungeRefRegex`: `@ops` inside `lounge:@ops~agent` is not a mention.
        val source = "ask lounge:@ops~agent, not @ops"
        val refs = source.threadInlineTagMatches()
        assertEquals(listOf(source.lastIndexOf("@ops")), refs.map { it.range.first })
        assertEquals(ThreadMarkdownTagKind.UserMention, refs.single().tag.kind)
    }

    @Test
    fun aLoungeRefNamesTheAgentByItsCurrentNameAsTheServerDoes() {
        val ops = RaftLoungeRefAgent(id = "agent-1", name = "ops")
        val opsCapital = RaftLoungeRefAgent(id = "agent-2", name = "Ops")
        val qa = RaftLoungeRefAgent(id = "agent-3", name = "qa")
        assertEquals(qa, resolveRaftLoungeRefAgent("QA", listOf(ops, qa)), "any case names the one agent of that name")
        // Two agents differ only in case: the exact one wins, and with none exact the name is ambiguous.
        assertEquals(ops, resolveRaftLoungeRefAgent("ops", listOf(ops, opsCapital, qa)))
        assertEquals(opsCapital, resolveRaftLoungeRefAgent("Ops", listOf(ops, opsCapital, qa)))
        assertNull(resolveRaftLoungeRefAgent("OPS", listOf(ops, opsCapital, qa)))
        // After a rename the old name names nothing, never the wrong Lounge.
        assertNull(resolveRaftLoungeRefAgent("ops", listOf(ops.copy(name = "ops-renamed"), qa)))
    }

    @Test
    fun aLoungeRefIsAChipOnlyWhenItsAgentResolves() {
        val agents = listOf(RaftLoungeRefAgent(id = "agent-1", name = "ops"))
        fun String.chip(loungeAgents: List<RaftLoungeRefAgent>): Boolean =
            threadInlineTagMatches().single().tag.isKnownThreadMarkdownTag(
                knownChannelNames = emptySet(),
                knownMentionNames = emptySet(),
                knownDirectMessagePeerNames = emptySet(),
                loungeAgents = loungeAgents,
            )
        for (ref in listOf("lounge:@ops", "lounge:@Ops:abcd1234", "lounge:@ops:#7", "lounge:@ops msg=0123abcd")) {
            assertTrue(ref.chip(agents), ref)
            // Lounges off for the reader (the flag, a Guest): the page knows no Lounge agent.
            assertFalse(ref.chip(emptyList()), ref)
        }
        assertFalse("lounge:@ghost".chip(agents), "a name no agent has is plain text")
    }

    @Test
    fun aLoungeChipIsAPlaceAndAThreadInItIsAThread() {
        val document =
            buildRaftRichTextDocument(
                "see lounge:@ops and lounge:@ops:abcd1234",
                RaftRichTextContext(knownLoungeAgents = setOf("ops")),
            )
        val chips = document.inlineTokens.filter { it.kind == RaftRichTextInlineKind.RaftRef }
        assertEquals(listOf("lounge:@ops", "lounge:@ops:abcd1234"), chips.map { it.text })
        assertEquals(listOf(RaftRichTextChromeKind.Channel, RaftRichTextChromeKind.Thread), chips.map { it.chrome })
        assertEquals(listOf(RaftRichTextRefKind.Lounge, RaftRichTextRefKind.LoungeThread), document.actions.map { it.refKind })
        assertEquals(listOf("ops", "ops"), document.actions.map { it.loungeAgentName })
        assertEquals(
            emptyList(),
            buildRaftRichTextDocument("see lounge:@ops", RaftRichTextContext()).actions,
            "an agent the reader does not know: plain text",
        )
    }
}
