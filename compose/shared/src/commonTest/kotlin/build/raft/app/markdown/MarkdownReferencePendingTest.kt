package build.raft.app.markdown

import build.raft.app.thread.ThreadMarkdownReferencePendingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun scopedActionIdentityKeepsReusedRowsIndependent() {
        val firstPlan = plan("#general #42", scope = "message-a")
        val secondPlan = plan("#general #42", scope = "message-b")
        val firstChannelAction = document(firstPlan).actions.first { it.refKind == RaftRichTextRefKind.Channel }
        val secondChannelAction = document(secondPlan).actions.first { it.refKind == RaftRichTextRefKind.Channel }
        assertNotEquals(firstChannelAction.id, secondChannelAction.id)

        val activeFirst =
            buildRaftRichTextDocumentFromMarkdownPlan(
                source = firstPlan.content,
                content = firstPlan.content,
                tokenPlan = firstPlan,
                activeActionId = firstChannelAction.id,
                activeActionIndicator = RaftMarkdownPendingActionIndicator
            )
        val inactiveSecond =
            buildRaftRichTextDocumentFromMarkdownPlan(
                source = secondPlan.content,
                content = secondPlan.content,
                tokenPlan = secondPlan,
                activeActionId = firstChannelAction.id,
                activeActionIndicator = RaftMarkdownPendingActionIndicator
            )

        assertEquals(
            "#general$RaftMarkdownPendingActionIndicator",
            activeFirst.inlineTokens.first { it.actionId == firstChannelAction.id }.text
        )
        assertEquals(
            "#general",
            inactiveSecond.inlineTokens.first { it.actionId == secondChannelAction.id }.text
        )
    }

    private fun plan(content: String, scope: String): MarkdownInlineTokenPlan =
        RaftMarkdownInlineTokenRegistry.plan(
            content = content,
            isTagValid = { true },
            inlineContentScope = scope
        )

    private fun document(plan: MarkdownInlineTokenPlan): RaftRichTextDocument =
        buildRaftRichTextDocumentFromMarkdownPlan(
            source = plan.content,
            content = plan.content,
            tokenPlan = plan
        )
}
