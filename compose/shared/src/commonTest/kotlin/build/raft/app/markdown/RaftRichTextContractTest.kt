package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RaftRichTextContractTest {
    @Test
    fun richTextDocumentSeparatesTokensChromeAndActions() {
        val body = "Ping @artin in #markdown专修:692d9e88 for #42 and `#not-task`"
        val document =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownChannels = setOf("markdown专修"),
                    knownMentions = setOf("artin"),
                    viewerUserId = "artin"
                )
            )

        val refs = document.inlineTokens.filter { it.kind == RaftRichTextInlineKind.RaftRef }
        val inlineCode = document.inlineTokens.single { it.kind == RaftRichTextInlineKind.InlineCode }

        assertEquals(listOf("@artin", "#markdown专修:692d9e88", "#42"), refs.map { it.text })
        assertEquals(
            listOf(
                RaftRichTextChromeKind.SelfMention,
                RaftRichTextChromeKind.Thread,
                RaftRichTextChromeKind.Task
            ),
            refs.map { it.chrome }
        )
        assertEquals("#not-task", inlineCode.text)
        assertEquals("`#not-task`", inlineCode.sourceText)
        assertEquals(RaftRichTextChromeKind.InlineCode, inlineCode.chrome)
        assertEquals("inlineCode", inlineCode.styleTokenId)
        assertEquals(
            listOf("selfMention", "thread", "task"),
            refs.map { it.styleTokenId }
        )
        assertNull(inlineCode.actionId)
        assertEquals(refs.map { it.id }, document.actions.map { it.id })
    }

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
    fun singleInlineCodeSpanKeepsBacktickInsideAsCodeAndOutsideAsText() {
        val document = buildRaftRichTextDocument("a`b`c")

        assertEquals(
            listOf(
                RaftRichTextInlineKind.Text to "a",
                RaftRichTextInlineKind.InlineCode to "b",
                RaftRichTextInlineKind.Text to "c"
            ),
            document.inlineTokens.map { it.kind to it.text }
        )
        assertEquals("`b`", document.inlineTokens.single { it.kind == RaftRichTextInlineKind.InlineCode }.sourceText)
        assertEquals(emptyList(), document.actions)
    }

    @Test
    fun canonicalAstOwnsAmbiguousBacktickRunSemantics() {
        val document = buildRaftRichTextDocument("x`a``b`y `c`")

        assertEquals(
            listOf(
                RaftRichTextInlineKind.Text to "x",
                RaftRichTextInlineKind.InlineCode to "a``b",
                RaftRichTextInlineKind.Text to "y ",
                RaftRichTextInlineKind.InlineCode to "c"
            ),
            document.inlineTokens.map { it.kind to it.text }
        )
        assertEquals(listOf("`a``b`", "`c`"), document.inlineTokens
            .filter { it.kind == RaftRichTextInlineKind.InlineCode }
            .map { it.sourceText })
        assertEquals(emptyList(), document.actions)
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
    fun realHandsPackageMarkdownKeepsBacktickedPackageScopesInlineCode() {
        val body =
            "两个包: **`@botiverse/hands-node`** schema 走子导出 " +
                "`@botiverse/hands-node/logs/schema`; adapter **`@botiverse/hands-electron`**. " +
                "@QA-Tester 的 Phase 1 验收口径 fold 进 spec。@artin 给个 go。"
        val document =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownMentions = setOf("QA-Tester", "artin"),
                    viewerUserId = "artin",
                    knownTaskNumbers = emptySet()
                )
            )

        assertEquals(
            listOf(
                "@botiverse/hands-node",
                "@botiverse/hands-node/logs/schema",
                "@botiverse/hands-electron"
            ),
            document.inlineTokens
                .filter { it.kind == RaftRichTextInlineKind.InlineCode }
                .map { it.text }
        )
        assertEquals(listOf("@QA-Tester", "@artin"), document.actions.map { it.label })
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
    fun ordinaryMentionBeforeTaskDoesNotMergeOrUpgradeChrome() {
        val body = "@KMP-专家 #520 Phase 5"
        val document =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownMentions = setOf("KMP-专家")
                )
            )

        val refs = document.inlineTokens.filter { it.kind == RaftRichTextInlineKind.RaftRef }

        assertEquals(listOf("@KMP-专家", "#520"), refs.map { it.text })
        assertEquals(
            listOf(RaftRichTextChromeKind.OrdinaryMention, RaftRichTextChromeKind.Task),
            refs.map { it.chrome }
        )
        assertEquals(listOf(RaftRichTextRefKind.UserMention, RaftRichTextRefKind.Task), refs.map { it.refKind })
        assertFalse(refs.first().range.endExclusive > refs.last().range.start)
    }

    @Test
    fun externalMarkdownLinkLabelDoesNotBecomeNestedTaskChrome() {
        val body = "Review [#1487 pin entities](https://github.com/botiverse/mobile/pull/1487)"
        val document =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(knownTaskNumbers = setOf(1487))
            )

        assertFalse(document.inlineTokens.any { it.kind == RaftRichTextInlineKind.RaftRef })
        assertFalse(document.actions.any { it.kind == RaftRichTextActionKind.OpenRaftRef })
    }

    @Test
    fun directMessageThreadActionRoundTripPreservesIdentityAndThreadChrome() {
        val document =
            buildRaftRichTextDocument(
                "Open dm:@artin:1c50c30a",
                RaftRichTextContext(knownDirectMessagePeers = setOf("artin"))
            )

        val token = document.inlineTokens.single { it.kind == RaftRichTextInlineKind.RaftRef }
        val action = document.actions.single()
        assertEquals("dm:@artin:1c50c30a", token.sourceText)
        assertEquals(RaftRichTextChromeKind.Thread, token.chrome)
        assertEquals(RaftRichTextRefKind.DirectMessageThread, token.refKind)
        assertEquals(RaftRichTextRefKind.DirectMessageThread, action.refKind)
        assertEquals("artin", action.directMessagePeerName)
        assertEquals("1c50c30a", action.threadShortId)
    }

    @Test
    fun kindPinnedDirectMessageThreadRefCarriesKindedPeerAndBareTwinStaysLinkable() {
        // `skyzh` is held by a human and an agent: Home lists the bare name too
        // (opens the first DM, Web `findDmByPeer`) plus both kinded forms.
        val context = RaftRichTextContext(knownDirectMessagePeers = setOf("skyzh", "skyzh~agent", "skyzh~human"))
        val document =
            buildRaftRichTextDocument(
                "Open dm:@skyzh~agent:1c50c30a, dm:@skyzh~human:2d60d40b and dm:@skyzh:3e70e50c",
                context
            )

        assertEquals(
            listOf("skyzh~agent", "skyzh~human", "skyzh"),
            document.actions.filter { it.refKind == RaftRichTextRefKind.DirectMessageThread }.map { it.directMessagePeerName }
        )
        assertFalse(document.inlineTokens.any { it.sourceText == "@skyzh" })
    }

    @Test
    fun directMessageRefWithoutShortIdBecomesDirectMessageAction() {
        val document =
            buildRaftRichTextDocument(
                "Open dm:@skyzh~human, dm:@skyzh and dm:@nobody",
                RaftRichTextContext(knownDirectMessagePeers = setOf("skyzh", "skyzh~agent", "skyzh~human"))
            )

        assertEquals(
            listOf("dm:@skyzh~human" to "skyzh~human", "dm:@skyzh" to "skyzh"),
            document.actions.map { action ->
                assertEquals(RaftRichTextRefKind.DirectMessage, action.refKind)
                action.label to action.directMessagePeerName
            }
        )
        assertFalse(document.inlineTokens.any { it.kind == RaftRichTextInlineKind.RaftRef && it.sourceText.contains("nobody") })
    }

    @Test
    fun unknownPeerKindSuffixStaysPlainText() {
        val document =
            buildRaftRichTextDocument(
                "Open dm:@skyzh~bot:1c50c30a",
                RaftRichTextContext(
                    knownMentions = setOf("skyzh"),
                    knownDirectMessagePeers = setOf("skyzh", "skyzh~agent", "skyzh~human")
                )
            )

        assertFalse(document.inlineTokens.any { it.kind == RaftRichTextInlineKind.RaftRef })
        assertTrue(document.actions.isEmpty())
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

    @Test
    fun directMessagePeerValidityDoesNotMakeTheSameNameAnOrdinaryMention() {
        val document =
            buildRaftRichTextDocument(
                "Open dm:@artin:1c50c30a then @artin",
                RaftRichTextContext(knownDirectMessagePeers = setOf("artin"))
            )

        assertEquals(
            listOf(RaftRichTextRefKind.DirectMessageThread),
            document.actions.map { it.refKind }
        )
    }

    @Test
    fun richTextDocumentGoldenJsonIsStable() {
        val body = "Ping @artin in #markdown专修 for #42 and `code #99`"
        val document =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownChannels = setOf("markdown专修"),
                    knownMentions = setOf("artin"),
                    viewerUserId = "artin",
                    knownTaskNumbers = setOf(42)
                )
            )

        assertEquals(
            """
            {"source":"Ping @artin in #markdown专修 for #42 and `code #99`","content":"Ping @artin in #markdown专修 for #42 and `code #99`","blocks":[{"id":"block:0","kind":"paragraph","range":[0,49],"inlineTokenIds":["text:0-5","slock:5","text:11-15","slock:15","text:26-31","slock:31","text:34-39","slock-code:39"]}],"inlineTokens":[{"id":"text:0-5","kind":"text","range":[0,5],"text":"Ping ","sourceText":"Ping "},{"id":"slock:5","kind":"slockRef","range":[5,11],"text":"@artin","sourceText":"@artin","chrome":"selfMention","styleTokenId":"selfMention","actionId":"slock:5","refKind":"userMention"},{"id":"text:11-15","kind":"text","range":[11,15],"text":" in ","sourceText":" in "},{"id":"slock:15","kind":"slockRef","range":[15,26],"text":"#markdown专修","sourceText":"#markdown专修","chrome":"channel","styleTokenId":"channel","actionId":"slock:15","refKind":"channel"},{"id":"text:26-31","kind":"text","range":[26,31],"text":" for ","sourceText":" for "},{"id":"slock:31","kind":"slockRef","range":[31,34],"text":"#42","sourceText":"#42","chrome":"task","styleTokenId":"task","actionId":"slock:31","refKind":"task"},{"id":"text:34-39","kind":"text","range":[34,39],"text":" and ","sourceText":" and "},{"id":"slock-code:39","kind":"inlineCode","range":[39,49],"text":"code #99","sourceText":"`code #99`","chrome":"inlineCode","styleTokenId":"inlineCode"}],"actions":[{"id":"slock:5","kind":"openSlockRef","refKind":"userMention","label":"@artin"},{"id":"slock:15","kind":"openSlockRef","refKind":"channel","label":"#markdown专修","channelName":"markdown专修"},{"id":"slock:31","kind":"openSlockRef","refKind":"task","label":"#42"}]}
            """.trimIndent(),
            document.toGoldenJson()
        )
    }

    @Test
    fun richTextCapabilitiesTreatMissingAsUnknownAndDivergencesAsMetadata() {
        val unknown = richTextCapabilities
        val declared =
            RaftRichTextCapabilities(
                consumesDocument = true,
                consumesChromeTokens = true,
                declaredDivergences =
                    listOf(
                        RaftRichTextDeclaredDivergence(
                            id = "ios-a-bridge-inline-code",
                            description = "A bridge keeps temporary inline-code padding until native adapter lands."
                        )
                    )
            )

        assertEquals("SLOCK_RICHTEXT_CAPS", RAFT_RICHTEXT_CAPS)
        assertNull(unknown.consumesDocument)
        assertNull(unknown.hasHitTestAdapter)
        assertEquals(true, declared.consumesDocument)
        assertEquals(1, declared.declaredDivergences.size)
        assertFalse(declared.declaredDivergences.isEmpty())
    }
}
