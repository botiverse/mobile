package build.raft.app.markdown

import build.raft.app.isKnownThreadMarkdownTag
import build.raft.app.threadEventAllowed
import build.raft.app.threadEventRequiresWriteAccess
import com.tencent.kuiklybase.markdown.model.parseMarkdownInlineSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `#channel msg=id` / `dm:@peer:short msg=id` message refs (Web `raftRefs.ts` `createRaftMessageRefRegex`). */
class RaftMarkdownMessageRefTest {
    private fun refs(source: String): List<String> =
        parseMarkdownInlineSyntax(source).raftRefs.map { it.sourceText }

    private fun tag(source: String): ThreadMarkdownTag = source.threadInlineTagMatches().single().tag

    private val fullId = "3d6b824f-d8c1-420e-8a04-7d21fee6fb1f"

    @Test
    fun allFourFormsAreOneToken() {
        assertEquals(listOf("#proj msg=2cebeb18"), refs("see #proj msg=2cebeb18 now"))
        assertEquals(listOf("#proj:a8293493 msg=2cebeb18"), refs("see #proj:a8293493 msg=2cebeb18 now"))
        assertEquals(listOf("dm:@peer msg=2cebeb18"), refs("see dm:@peer msg=2cebeb18 now"))
        assertEquals(listOf("dm:@peer:a8293493 msg=2cebeb18"), refs("see dm:@peer:a8293493 msg=2cebeb18 now"))
        assertEquals(listOf("dm:@skyzh~agent:a8293493 msg=2cebeb18"), refs("dm:@skyzh~agent:a8293493 msg=2cebeb18"))
        assertEquals(listOf("#产品 msg=2cebeb18"), refs("去#产品 msg=2cebeb18"))
    }

    @Test
    fun shortAndFullMessageIds() {
        assertEquals(listOf("#proj msg=ab"), refs("#proj msg=ab"))
        assertEquals(listOf("#proj msg=$fullId"), refs("#proj msg=$fullId"))
        assertEquals(listOf("dm:@peer:a8293493 msg=$fullId"), refs("dm:@peer:a8293493 msg=$fullId"))
        assertEquals(fullId, parseRaftMarkdownMessageRefLabel("#proj msg=$fullId")?.messageId)
    }

    @Test
    fun whitespaceBeforeMsgIsAnyRunWithinOneTextNode() {
        assertEquals(listOf("#proj   msg=ab12"), refs("#proj   msg=ab12"))
        assertEquals(listOf("#proj\tmsg=ab12"), refs("#proj\tmsg=ab12"))
        assertEquals(listOf("#proj\nmsg=ab12"), refs("#proj\nmsg=ab12"))
        assertEquals(listOf("#proj"), refs("#proj  \nmsg=ab12"))
        assertEquals(listOf("#proj"), refs("#proj\n\nmsg=ab12"))
    }

    @Test
    fun codeSpansCodeBlocksAndLinksKeepTheirSource() {
        assertEquals(emptyList(), refs("`#proj msg=2cebeb18` and `dm:@peer msg=2cebeb18`"))
        assertEquals(emptyList(), refs("```\n#proj msg=2cebeb18\n```"))
        assertEquals(emptyList(), refs("[#proj msg=2cebeb18](https://example.com)"))
        assertEquals(listOf("#proj msg=2cebeb18"), refs("`code` then #proj msg=2cebeb18"))
        // The code starts after the target: the tail is not there to read.
        assertEquals(listOf("#proj"), refs("#proj `msg=2cebeb18`"))
    }

    @Test
    fun adjacentPunctuationStaysOutside() {
        assertEquals(listOf("#proj msg=2cebeb18"), refs("(#proj msg=2cebeb18)."))
        assertEquals(listOf("#proj msg=2cebeb18"), refs("#proj msg=2cebeb18, then"))
        assertEquals(listOf("#proj:a8293493 msg=2cebeb18"), refs("#proj:a8293493 msg=2cebeb18: \"quote\""))
        assertEquals(listOf("#proj msg=2cebeb18"), refs("#proj msg=2cebeb18。下一句"))
        assertEquals(listOf("dm:@peer msg=2cebeb18"), refs("**dm:@peer msg=2cebeb18**"))
        assertEquals(listOf("#a msg=ab12", "#b msg=cd34"), refs("#a msg=ab12, #b msg=cd34"))
    }

    @Test
    fun anInvalidIdLeavesThePlainTargetAndTheTailAsText() {
        listOf("#proj msg=", "#proj msg=a", "#proj msg=-abc", "#proj msg=!!", "#proj msg= ab12", "#proj msg", "#proj ms=ab12")
            .forEach { source -> assertEquals(listOf("#proj"), refs(source), source) }
        assertEquals(listOf("#proj:a8293493"), refs("#proj:a8293493 msg=!"))
        assertEquals(listOf("dm:@peer"), refs("dm:@peer msg=!"))
        assertEquals(listOf("dm:@peer:a8293493"), refs("dm:@peer:a8293493 msg=x"))
        assertEquals(ThreadMarkdownTagKind.Channel, tag("#proj msg=!").kind)
    }

    @Test
    fun theIdHasNoRightBoundaryAsOnTheWeb() {
        assertEquals(listOf("#proj msg=abc"), refs("#proj msg=abc_d"))
        val longest = "a".repeat(64)
        assertEquals(listOf("#proj msg=$longest"), refs("#proj msg=${longest}bcd"))
    }

    @Test
    fun aTailMarkdownOwnsLeavesTheTarget() {
        assertEquals(listOf("#proj"), refs("#proj msg=https://example.com"))
    }

    @Test
    fun plainChannelAndThreadChipsFollowedByOtherTextAreUnchanged() {
        assertEquals(listOf("#proj"), refs("#proj and more text"))
        assertEquals(listOf("#proj:a8293493"), refs("#proj:a8293493 and more text"))
        assertEquals(listOf("dm:@peer:a8293493"), refs("dm:@peer:a8293493 and more"))
        assertEquals(listOf("#proj:#7"), refs("#proj:#7 msg=ab12"))
    }

    @Test
    fun theMessageRefWinsOverAnyPlainHashMatch() {
        // The Web's message pass runs first and claims the token whatever the name reads as.
        assertEquals(listOf("#12 msg=ab12"), refs("#12 msg=ab12"))
        assertEquals(listOf("#a1b2c3 msg=ab12"), refs("#a1b2c3 msg=ab12"))
    }

    @Test
    fun tagCarriesTheCanonicalTarget() {
        val channel = tag("#proj   MSG=ab12")
        assertEquals(ThreadMarkdownTagKind.Message, channel.kind)
        assertEquals("#proj msg=ab12", channel.label)
        assertEquals("proj", channel.channelName)
        assertNull(channel.threadShortId)
        assertNull(channel.directMessagePeerName)

        val thread = tag("#proj:A8293493 msg=ab12")
        assertEquals("#proj:A8293493 msg=ab12", thread.label)
        assertEquals("A8293493", thread.threadShortId)

        val dm = tag("DM:@skyzh~agent:a8293493 msg=ab12")
        assertEquals(ThreadMarkdownTagKind.Message, dm.kind)
        assertEquals("dm:@skyzh~agent:a8293493 msg=ab12", dm.label)
        assertEquals("skyzh~agent", dm.directMessagePeerName)
        assertNull(dm.channelName)
    }

    @Test
    fun parseRefusesEverythingElse() {
        listOf("#proj", "#proj:a8293493", "dm:@peer", "@peer msg=ab12", "msg=ab12", "#proj msg=a", "#proj:abc msg=ab12", "#a:b:c msg=ab12", "dm:@peer~bot msg=ab12", "#proj:#7 msg=ab12")
            .forEach { label -> assertNull(parseRaftMarkdownMessageRefLabel(label), label) }
    }

    @Test
    fun aRefStaysPlainTextUnlessItsConversationIsKnown() {
        fun ThreadMarkdownTag.known() =
            isKnownThreadMarkdownTag(
                knownChannelNames = setOf("proj"),
                knownMentionNames = emptySet(),
                knownDirectMessagePeerNames = setOf("peer"),
            )
        assertTrue(tag("#Proj msg=ab12").known())
        assertTrue(tag("#proj:a8293493 msg=ab12").known())
        assertTrue(tag("dm:@Peer msg=ab12").known())
        assertFalse(tag("#nope msg=ab12").known())
        assertFalse(tag("dm:@nope:a8293493 msg=ab12").known())
    }

    @Test
    fun cardMarkdownKeepsHashAsPlainText() {
        val plan = RaftMarkdownInlineTokenRegistry.plan("#proj msg=ab12 dm:@peer msg=cd34", isTagValid = raftCardMarkdownTagValid)
        assertEquals(emptyList(), plan.tokens)
    }

    @Test
    fun theChipIsTheWebsPrimarySticker() {
        val token = RaftRichTextChromeKind.Message.styleToken
        assertEquals(RaftRichTextChromeKind.SelfMention.styleToken.chipFill, token.chipFill)
        assertTrue(token.inkEdge)
    }

    @Test
    fun openingARefNeedsNoWriteAccess() {
        assertFalse(threadEventRequiresWriteAccess("openMarkdownMessageRef"))
        assertTrue(threadEventAllowed(writeActionsEnabled = false, "openMarkdownMessageRef"))
    }
}
