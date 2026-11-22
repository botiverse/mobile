package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RaftRichTextContractTest {
    @Test
    fun inlineCodeShieldsMentionsChannelsThreadsAndTasksFromRaftRefs() {
        val body = "Plain @artin #markdown专修 then `@artin #markdown专修:692d9e88 task #42 #99`"
        val document =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownChannels = setOf("markdown专修"),
                    knownMentions = setOf("artin"),
                    viewerUserId = "artin",
                    knownTaskNumbers = setOf(42, 99)
                )
            )

        val refs = document.inlineTokens.filter { it.kind == RaftRichTextInlineKind.RaftRef }
        val inlineCode = document.inlineTokens.single { it.kind == RaftRichTextInlineKind.InlineCode }

        assertEquals(listOf("@artin", "#markdown专修"), refs.map { it.text })
        assertEquals("`@artin #markdown专修:692d9e88 task #42 #99`", inlineCode.sourceText)
        assertEquals("@artin #markdown专修:692d9e88 task #42 #99", inlineCode.text)
        assertEquals(listOf("@artin", "#markdown专修"), document.actions.map { it.label })
    }

    @Test
    fun unclosedBacktickDoesNotTurnRemainingPlainTextIntoInlineCode() {
        val document =
            buildRaftRichTextDocument(
                "a`b c @artin",
                RaftRichTextContext(knownMentions = setOf("artin"))
            )

        assertEquals(emptyList(), document.inlineTokens.filter { it.kind == RaftRichTextInlineKind.InlineCode })
        assertEquals(listOf("@artin"), document.actions.map { it.label })
    }

    @Test
    fun viewerContextDeterminesSelfMentionChrome() {
        val body = "Ping @artin"
        val selfView =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownMentions = setOf("artin"),
                    viewerUserId = "artin"
                )
            )
        val otherView =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownMentions = setOf("artin"),
                    viewerUserId = "cindy"
                )
            )

        val selfMention = selfView.inlineTokens.single { it.kind == RaftRichTextInlineKind.RaftRef }
        val otherMention = otherView.inlineTokens.single { it.kind == RaftRichTextInlineKind.RaftRef }

        assertEquals(RaftRichTextChromeKind.SelfMention, selfMention.chrome)
        assertEquals("selfMention", selfMention.styleTokenId)
        assertEquals(RaftRichTextChromeKind.OrdinaryMention, otherMention.chrome)
        assertEquals("ordinaryMention", otherMention.styleTokenId)
    }

    @Test
    fun unknownPeerDoesNotBecomeDirectMessageThreadAction() {
        val document =
            buildRaftRichTextDocument(
                "Open dm:@unknown:1c50c30a",
                RaftRichTextContext(knownDirectMessagePeers = setOf("artin"))
            )

        assertFalse(document.actions.any { it.refKind == RaftRichTextRefKind.DirectMessageThread })
        assertFalse(document.inlineTokens.any { it.refKind == RaftRichTextRefKind.DirectMessageThread })
    }
}
