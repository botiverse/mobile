package build.raft.app.markdown

import build.raft.app.ThreadMarkdownReferencePendingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownReferencePendingTest {

    @Test
    fun pendingStateRejectsRepeatAndIgnoresStaleTerminal() {
        val firstStart = ThreadMarkdownReferencePendingState().start("message-a:channel")
        val firstToken = assertNotNull(firstStart.token)

        assertNull(firstStart.state.start("message-a:channel").token)
        assertNull(firstStart.state.start("message-a:task").token)

        val staleToken = firstToken.copy(generation = firstToken.generation + 1L)
        assertEquals(firstStart.state, firstStart.state.finish(staleToken))
        assertNull(firstStart.state.finish(firstToken).active)
    }

    @Test
    fun everyTerminalPathUsesTheSameExactTokenCleanup() {
        val plan = plan("Open #general", scope = "message")
        val actionId = document(plan).actions.single().id
        listOf("success", "failure", "cancel").forEach { terminal ->
            val started = ThreadMarkdownReferencePendingState().start(actionId)
            val token = assertNotNull(started.token)

            val finished = started.state.finish(token)
            val terminalDocument =
                buildSlockRichTextDocumentFromMarkdownPlan(
                    source = plan.content,
                    content = plan.content,
                    tokenPlan = plan,
                    activeActionId = finished.active?.actionId,
                    activeActionIndicator = SlockMarkdownPendingActionIndicator
                )

            assertNull(finished.active, "terminal=$terminal must clear the pending chip")
            assertEquals("#general", terminalDocument.inlineTokens.single { it.actionId == actionId }.text)
            assertEquals(1, terminalDocument.inlineTokens.count { it.actionId != null }, "terminal=$terminal must not leave a tail token")
        }
    }

    @Test
    fun scopedActionIdentityKeepsReusedRowsIndependent() {
        val firstPlan = plan("#general #42", scope = "message-a")
        val secondPlan = plan("#general #42", scope = "message-b")
        val firstChannelAction = document(firstPlan).actions.first { it.refKind == SlockRichTextRefKind.Channel }
        val secondChannelAction = document(secondPlan).actions.first { it.refKind == SlockRichTextRefKind.Channel }
        assertNotEquals(firstChannelAction.id, secondChannelAction.id)

        val activeFirst =
            buildSlockRichTextDocumentFromMarkdownPlan(
                source = firstPlan.content,
                content = firstPlan.content,
                tokenPlan = firstPlan,
                activeActionId = firstChannelAction.id,
                activeActionIndicator = SlockMarkdownPendingActionIndicator
            )
        val inactiveSecond =
            buildSlockRichTextDocumentFromMarkdownPlan(
                source = secondPlan.content,
                content = secondPlan.content,
                tokenPlan = secondPlan,
                activeActionId = firstChannelAction.id,
                activeActionIndicator = SlockMarkdownPendingActionIndicator
            )

        assertEquals(
            "#general$SlockMarkdownPendingActionIndicator",
            activeFirst.inlineTokens.first { it.actionId == firstChannelAction.id }.text
        )
        assertEquals(
            "#general",
            inactiveSecond.inlineTokens.first { it.actionId == secondChannelAction.id }.text
        )
    }

    @Test
    fun taskAndChannelPendingIndicatorsStayOnTheirExactAction() {
        val plan = plan("Open #general then #42", scope = "message")
        val base = document(plan)
        val channel = base.actions.first { it.refKind == SlockRichTextRefKind.Channel }
        val task = base.actions.first { it.refKind == SlockRichTextRefKind.Task }

        val activeTask =
            buildSlockRichTextDocumentFromMarkdownPlan(
                source = plan.content,
                content = plan.content,
                tokenPlan = plan,
                activeActionId = task.id,
                activeActionIndicator = SlockMarkdownPendingActionIndicator
            )

        assertEquals("#42$SlockMarkdownPendingActionIndicator", activeTask.inlineTokens.first { it.actionId == task.id }.text)
        assertEquals("#general", activeTask.inlineTokens.first { it.actionId == channel.id }.text)
    }

    @Test
    fun resolvedDisplayLabelKeepsThePendingIndicatorInsideTheSameAction() {
        val plan = plan("Open #general", scope = "message")
        val base = document(plan)
        val channel = base.actions.single()
        val active =
            buildSlockRichTextDocumentFromMarkdownPlan(
                source = plan.content,
                content = plan.content,
                tokenPlan = plan,
                activeActionId = channel.id,
                activeActionIndicator = SlockMarkdownPendingActionIndicator
            )
        val token = active.inlineTokens.single { it.actionId == channel.id }

        assertEquals(
            "#general-renamed$SlockMarkdownPendingActionIndicator",
            resolveSlockRichTextTokenLabel(token, "#general-renamed")
        )
    }

    @Test
    fun pendingThreadReferenceRetainsThreadChromeKindAndSetsActiveFlag() {
        val plan = plan("Open #general:1c50c30a", scope = "message")
        val base = document(plan)
        val threadAction = base.actions.single { it.refKind == SlockRichTextRefKind.Thread }

        val active =
            buildSlockRichTextDocumentFromMarkdownPlan(
                source = plan.content,
                content = plan.content,
                tokenPlan = plan,
                activeActionId = threadAction.id
            )
        val token = active.inlineTokens.single { it.actionId == threadAction.id }

        assertTrue(token.active)
        assertEquals(SlockRichTextChromeKind.Thread, token.chrome)
        assertEquals("", token.trailingIndicator)
        assertEquals("#general:1c50c30a", token.text)
    }

    @Test
    fun pendingTokenWithoutTrailingIndicatorSuppliesSpinnerInlineContent() {
        val plan = plan("Open #general", scope = "message")
        val base = document(plan)
        val channelAction = base.actions.single()

        val activeDocument =
            buildSlockRichTextDocumentFromMarkdownPlan(
                source = plan.content,
                content = plan.content,
                tokenPlan = plan,
                activeActionId = channelAction.id
            )
        val annotator =
            slockMarkdownAnnotator(
                document = activeDocument
            )
        val inlineContentMap = annotator.inlineContent?.invoke()
        assertNotNull(inlineContentMap)
        assertTrue(inlineContentMap.containsKey(slockMarkdownActionSpinnerInlineId(channelAction.id)))
    }

    private fun plan(content: String, scope: String): MarkdownInlineTokenPlan =
        SlockMarkdownInlineTokenRegistry.plan(
            content = content,
            isTagValid = { true },
            inlineContentScope = scope
        )

    private fun document(plan: MarkdownInlineTokenPlan): SlockRichTextDocument =
        buildSlockRichTextDocumentFromMarkdownPlan(
            source = plan.content,
            content = plan.content,
            tokenPlan = plan
        )
}
