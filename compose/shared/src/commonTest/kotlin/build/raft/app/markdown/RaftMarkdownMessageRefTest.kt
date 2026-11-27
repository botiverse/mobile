package build.raft.app.markdown

import build.raft.app.thread.isKnownThreadMarkdownTag
import build.raft.app.thread.threadEventAllowed
import build.raft.app.thread.threadEventRequiresWriteAccess
import com.tencent.kuiklybase.markdown.model.parseMarkdownInlineSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `#channel msg=id` / `dm:@peer:short msg=id` message refs (Web `raftRefs.ts` `createRaftMessageRefRegex`). */
class RaftMarkdownMessageRefTest {
    private fun refs(source: String): List<String> =
        parseMarkdownInlineSyntax(source).raftRefs.map { it.sourceText }

    private fun tag(source: String): ThreadMarkdownTag = source.threadInlineTagMatches().single().tag

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
    fun anInvalidIdLeavesThePlainTargetAndTheTailAsText() {
        listOf("#proj msg=", "#proj msg=a", "#proj msg=-abc", "#proj msg=!!", "#proj msg= ab12", "#proj msg", "#proj ms=ab12")
            .forEach { source -> assertEquals(listOf("#proj"), refs(source), source) }
        assertEquals(listOf("#proj:a8293493"), refs("#proj:a8293493 msg=!"))
        assertEquals(listOf("dm:@peer"), refs("dm:@peer msg=!"))
        assertEquals(listOf("dm:@peer:a8293493"), refs("dm:@peer:a8293493 msg=x"))
        assertEquals(ThreadMarkdownTagKind.Channel, tag("#proj msg=!").kind)
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
    fun openingARefNeedsNoWriteAccess() {
        assertFalse(threadEventRequiresWriteAccess("openMarkdownMessageRef"))
        assertTrue(threadEventAllowed(writeActionsEnabled = false, "openMarkdownMessageRef"))
    }
}
