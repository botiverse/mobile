package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class SlockRichTextContractTest {
    @Test
    fun richTextDocumentSeparatesTokensChromeAndActions() {
        val body = "Ping @artin in #markdown专修:692d9e88 for #42 and `#not-task`"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
                    knownChannels = setOf("markdown专修"),
                    knownMentions = setOf("artin"),
                    viewerUserId = "artin"
                )
            )

        val refs = document.inlineTokens.filter { it.kind == SlockRichTextInlineKind.SlockRef }
        val inlineCode = document.inlineTokens.single { it.kind == SlockRichTextInlineKind.InlineCode }

        assertEquals(listOf("@artin", "#markdown专修:692d9e88", "#42"), refs.map { it.text })
        assertEquals(
            listOf(
                SlockRichTextChromeKind.SelfMention,
                SlockRichTextChromeKind.Thread,
                SlockRichTextChromeKind.Task
            ),
            refs.map { it.chrome }
        )
        assertEquals("#not-task", inlineCode.text)
        assertEquals("`#not-task`", inlineCode.sourceText)
        assertEquals(SlockRichTextChromeKind.InlineCode, inlineCode.chrome)
        assertEquals("inlineCode", inlineCode.styleTokenId)
        assertEquals(
            listOf("selfMention", "thread", "task"),
            refs.map { it.styleTokenId }
        )
        assertNull(inlineCode.actionId)
        assertEquals(refs.map { it.id }, document.actions.map { it.id })
    }

    @Test
    fun inlineCodeShieldsMentionsChannelsThreadsAndTasksFromSlockRefs() {
        val body = "Plain @artin #markdown专修 then `@artin #markdown专修:692d9e88 task #42 #99`"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
                    knownChannels = setOf("markdown专修"),
                    knownMentions = setOf("artin"),
                    viewerUserId = "artin",
                    knownTaskNumbers = setOf(42, 99)
                )
            )

        val refs = document.inlineTokens.filter { it.kind == SlockRichTextInlineKind.SlockRef }
        val inlineCode = document.inlineTokens.single { it.kind == SlockRichTextInlineKind.InlineCode }

        assertEquals(listOf("@artin", "#markdown专修"), refs.map { it.text })
        assertEquals("`@artin #markdown专修:692d9e88 task #42 #99`", inlineCode.sourceText)
        assertEquals("@artin #markdown专修:692d9e88 task #42 #99", inlineCode.text)
        assertEquals(listOf("@artin", "#markdown专修"), document.actions.map { it.label })
    }

    @Test
    fun singleInlineCodeSpanKeepsBacktickInsideAsCodeAndOutsideAsText() {
        val document = buildSlockRichTextDocument("a`b`c")

        assertEquals(
            listOf(
                SlockRichTextInlineKind.Text to "a",
                SlockRichTextInlineKind.InlineCode to "b",
                SlockRichTextInlineKind.Text to "c"
            ),
            document.inlineTokens.map { it.kind to it.text }
        )
        assertEquals("`b`", document.inlineTokens.single { it.kind == SlockRichTextInlineKind.InlineCode }.sourceText)
        assertEquals(emptyList(), document.actions)
    }

    @Test
    fun canonicalAstOwnsAmbiguousBacktickRunSemantics() {
        val document = buildSlockRichTextDocument("x`a``b`y `c`")

        assertEquals(
            listOf(
                SlockRichTextInlineKind.Text to "x",
                SlockRichTextInlineKind.InlineCode to "a``b",
                SlockRichTextInlineKind.Text to "y ",
                SlockRichTextInlineKind.InlineCode to "c"
            ),
            document.inlineTokens.map { it.kind to it.text }
        )
        assertEquals(listOf("`a``b`", "`c`"), document.inlineTokens
            .filter { it.kind == SlockRichTextInlineKind.InlineCode }
            .map { it.sourceText })
        assertEquals(emptyList(), document.actions)
    }

    @Test
    fun unclosedBacktickDoesNotTurnRemainingPlainTextIntoInlineCode() {
        val document =
            buildSlockRichTextDocument(
                "a`b c @artin",
                SlockRichTextContext(knownMentions = setOf("artin"))
            )

        assertEquals(emptyList(), document.inlineTokens.filter { it.kind == SlockRichTextInlineKind.InlineCode })
        assertEquals(listOf("@artin"), document.actions.map { it.label })
    }

    @Test
    fun realHandsPackageMarkdownKeepsBacktickedPackageScopesInlineCode() {
        val body =
            "两个包: **`@botiverse/hands-node`** schema 走子导出 " +
                "`@botiverse/hands-node/logs/schema`; adapter **`@botiverse/hands-electron`**. " +
                "@QA-Tester 的 Phase 1 验收口径 fold 进 spec。@artin 给个 go。"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
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
                .filter { it.kind == SlockRichTextInlineKind.InlineCode }
                .map { it.text }
        )
        assertEquals(listOf("@QA-Tester", "@artin"), document.actions.map { it.label })
    }

    @Test
    fun viewerContextDeterminesSelfMentionChrome() {
        val body = "Ping @artin"
        val selfView =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
                    knownMentions = setOf("artin"),
                    viewerUserId = "artin"
                )
            )
        val otherView =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
                    knownMentions = setOf("artin"),
                    viewerUserId = "cindy"
                )
            )

        val selfMention = selfView.inlineTokens.single { it.kind == SlockRichTextInlineKind.SlockRef }
        val otherMention = otherView.inlineTokens.single { it.kind == SlockRichTextInlineKind.SlockRef }

        assertEquals(SlockRichTextChromeKind.SelfMention, selfMention.chrome)
        assertEquals("selfMention", selfMention.styleTokenId)
        assertEquals(SlockRichTextChromeKind.OrdinaryMention, otherMention.chrome)
        assertEquals("ordinaryMention", otherMention.styleTokenId)
    }

    @Test
    fun ordinaryMentionBeforeTaskDoesNotMergeOrUpgradeChrome() {
        val body = "@KMP-专家 #520 Phase 5"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
                    knownMentions = setOf("KMP-专家")
                )
            )

        val refs = document.inlineTokens.filter { it.kind == SlockRichTextInlineKind.SlockRef }

        assertEquals(listOf("@KMP-专家", "#520"), refs.map { it.text })
        assertEquals(
            listOf(SlockRichTextChromeKind.OrdinaryMention, SlockRichTextChromeKind.Task),
            refs.map { it.chrome }
        )
        assertEquals(listOf(SlockRichTextRefKind.UserMention, SlockRichTextRefKind.Task), refs.map { it.refKind })
        assertFalse(refs.first().range.endExclusive > refs.last().range.start)
    }

    @Test
    fun externalMarkdownLinkLabelDoesNotBecomeNestedTaskChrome() {
        val body = "Review [#1487 pin entities](https://github.com/botiverse/mobile/pull/1487)"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(knownTaskNumbers = setOf(1487))
            )

        assertFalse(document.inlineTokens.any { it.kind == SlockRichTextInlineKind.SlockRef })
        assertFalse(document.actions.any { it.kind == SlockRichTextActionKind.OpenSlockRef })
    }

    @Test
    fun directMessageThreadActionRoundTripPreservesIdentityAndThreadChrome() {
        val document =
            buildSlockRichTextDocument(
                "Open dm:@artin:1c50c30a",
                SlockRichTextContext(knownDirectMessagePeers = setOf("artin"))
            )

        val token = document.inlineTokens.single { it.kind == SlockRichTextInlineKind.SlockRef }
        val action = document.actions.single()
        assertEquals("dm:@artin:1c50c30a", token.sourceText)
        assertEquals(SlockRichTextChromeKind.Thread, token.chrome)
        assertEquals(SlockRichTextRefKind.DirectMessageThread, token.refKind)
        assertEquals(SlockRichTextRefKind.DirectMessageThread, action.refKind)
        assertEquals("artin", action.directMessagePeerName)
        assertEquals("1c50c30a", action.threadShortId)
    }

    @Test
    fun kindPinnedDirectMessageThreadRefCarriesKindedPeerAndBareTwinStaysLinkable() {
        // `skyzh` is held by a human and an agent: Home lists the bare name too
        // (opens the first DM, Web `findDmByPeer`) plus both kinded forms.
        val context = SlockRichTextContext(knownDirectMessagePeers = setOf("skyzh", "skyzh~agent", "skyzh~human"))
        val document =
            buildSlockRichTextDocument(
                "Open dm:@skyzh~agent:1c50c30a, dm:@skyzh~human:2d60d40b and dm:@skyzh:3e70e50c",
                context
            )

        assertEquals(
            listOf("skyzh~agent", "skyzh~human", "skyzh"),
            document.actions.filter { it.refKind == SlockRichTextRefKind.DirectMessageThread }.map { it.directMessagePeerName }
        )
        assertFalse(document.inlineTokens.any { it.sourceText == "@skyzh" })
    }

    @Test
    fun directMessageRefWithoutShortIdBecomesDirectMessageAction() {
        val document =
            buildSlockRichTextDocument(
                "Open dm:@skyzh~human, dm:@skyzh and dm:@nobody",
                SlockRichTextContext(knownDirectMessagePeers = setOf("skyzh", "skyzh~agent", "skyzh~human"))
            )

        assertEquals(
            listOf("dm:@skyzh~human" to "skyzh~human", "dm:@skyzh" to "skyzh"),
            document.actions.map { action ->
                assertEquals(SlockRichTextRefKind.DirectMessage, action.refKind)
                action.label to action.directMessagePeerName
            }
        )
        assertFalse(document.inlineTokens.any { it.kind == SlockRichTextInlineKind.SlockRef && it.sourceText.contains("nobody") })
    }

    @Test
    fun unknownPeerKindSuffixStaysPlainText() {
        val document =
            buildSlockRichTextDocument(
                "Open dm:@skyzh~bot:1c50c30a",
                SlockRichTextContext(
                    knownMentions = setOf("skyzh"),
                    knownDirectMessagePeers = setOf("skyzh", "skyzh~agent", "skyzh~human")
                )
            )

        assertFalse(document.inlineTokens.any { it.kind == SlockRichTextInlineKind.SlockRef })
        assertTrue(document.actions.isEmpty())
    }

    @Test
    fun unknownPeerDoesNotBecomeDirectMessageThreadAction() {
        val document =
            buildSlockRichTextDocument(
                "Open dm:@unknown:1c50c30a",
                SlockRichTextContext(knownDirectMessagePeers = setOf("artin"))
            )

        assertFalse(document.actions.any { it.refKind == SlockRichTextRefKind.DirectMessageThread })
        assertFalse(document.inlineTokens.any { it.refKind == SlockRichTextRefKind.DirectMessageThread })
    }

    @Test
    fun directMessagePeerValidityDoesNotMakeTheSameNameAnOrdinaryMention() {
        val document =
            buildSlockRichTextDocument(
                "Open dm:@artin:1c50c30a then @artin",
                SlockRichTextContext(knownDirectMessagePeers = setOf("artin"))
            )

        assertEquals(
            listOf(SlockRichTextRefKind.DirectMessageThread),
            document.actions.map { it.refKind }
        )
    }

    @Test
    fun richTextDocumentGoldenJsonIsStable() {
        val body = "Ping @artin in #markdown专修 for #42 and `code #99`"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
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
            SlockRichTextCapabilities(
                consumesDocument = true,
                consumesChromeTokens = true,
                declaredDivergences =
                    listOf(
                        SlockRichTextDeclaredDivergence(
                            id = "ios-a-bridge-inline-code",
                            description = "A bridge keeps temporary inline-code padding until native adapter lands."
                        )
                    )
            )

        assertEquals("SLOCK_RICHTEXT_CAPS", SLOCK_RICHTEXT_CAPS)
        assertNull(unknown.consumesDocument)
        assertNull(unknown.hasHitTestAdapter)
        assertEquals(true, declared.consumesDocument)
        assertEquals(1, declared.declaredDivergences.size)
        assertFalse(declared.declaredDivergences.isEmpty())
    }
}
