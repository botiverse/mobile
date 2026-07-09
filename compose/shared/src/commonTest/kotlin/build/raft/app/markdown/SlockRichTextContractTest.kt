package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                    highlightedMentions = setOf("artin")
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
        assertNull(inlineCode.actionId)
        assertEquals(refs.map { it.id }, document.actions.map { it.id })
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
    fun richTextDocumentGoldenJsonIsStable() {
        val body = "Ping @artin in #markdown专修 for #42 and `code #99`"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
                    knownChannels = setOf("markdown专修"),
                    knownMentions = setOf("artin"),
                    highlightedMentions = setOf("artin"),
                    knownTaskNumbers = setOf(42)
                )
            )

        assertEquals(
            """
            {"source":"Ping @artin in #markdown专修 for #42 and `code #99`","content":"Ping @artin in #markdown专修 for #42 and `code #99`","blocks":[{"id":"block:0","kind":"paragraph","range":[0,49],"inlineTokenIds":["text:0-5","slock:5","text:11-15","slock:15","text:26-31","slock:31","text:34-39","slock-code:39"]}],"inlineTokens":[{"id":"text:0-5","kind":"text","range":[0,5],"text":"Ping ","sourceText":"Ping "},{"id":"slock:5","kind":"slockRef","range":[5,11],"text":"@artin","sourceText":"@artin","chrome":"selfMention","actionId":"slock:5","refKind":"userMention"},{"id":"text:11-15","kind":"text","range":[11,15],"text":" in ","sourceText":" in "},{"id":"slock:15","kind":"slockRef","range":[15,26],"text":"#markdown专修","sourceText":"#markdown专修","chrome":"channel","actionId":"slock:15","refKind":"channel"},{"id":"text:26-31","kind":"text","range":[26,31],"text":" for ","sourceText":" for "},{"id":"slock:31","kind":"slockRef","range":[31,34],"text":"#42","sourceText":"#42","chrome":"task","actionId":"slock:31","refKind":"task"},{"id":"text:34-39","kind":"text","range":[34,39],"text":" and ","sourceText":" and "},{"id":"slock-code:39","kind":"inlineCode","range":[39,49],"text":"code #99","sourceText":"`code #99`","chrome":"inlineCode"}],"actions":[{"id":"slock:5","kind":"openSlockRef","refKind":"userMention","label":"@artin"},{"id":"slock:15","kind":"openSlockRef","refKind":"channel","label":"#markdown专修","channelName":"markdown专修"},{"id":"slock:31","kind":"openSlockRef","refKind":"task","label":"#42"}]}
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
