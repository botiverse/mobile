package build.raft.app.markdown

import build.raft.app.thread.isKnownThreadMarkdownTag
import build.raft.app.model.TaskStatus
import com.tencent.kuiklybase.markdown.model.parseMarkdownInlineSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `#channel:#7` / `dm:@peer:#7` task refs (Web `raftRefs.ts` task ref regexes). */
class RaftMarkdownTaskRefTest {
    private fun refs(source: String): List<String> =
        parseMarkdownInlineSyntax(source).raftRefs.map { it.sourceText }

    @Test
    fun channelTaskRefIsOneToken() {
        assertEquals(listOf("#triage:#169"), refs("好像移动端不支持 #triage:#169 这样的？"))
        assertEquals(listOf("#triage:#169"), refs("see #triage:#169."))
        assertEquals(listOf("#triage:#169"), refs("(#triage:#169)"))
        assertEquals(listOf("#triage:#169", "#wg-task-v2:#9"), refs("#triage:#169, #wg-task-v2:#9"))
    }

    @Test
    fun qualifiedChipReadsOnlyItsConversationsStatus() {
        val byNumber = mapOf(169 to TaskStatus.Done, 42 to TaskStatus.InReview)
        val byRef = mapOf(raftMarkdownTaskRefStatusKey(169, channelName = "Triage")!! to TaskStatus.InProgress)
        fun action(label: String, channel: String? = null, peer: String? = null) =
            RaftRichTextAction(
                id = label,
                kind = RaftRichTextActionKind.OpenRaftRef,
                refKind = RaftRichTextRefKind.Task,
                label = label,
                channelName = channel,
                directMessagePeerName = peer
            )
        assertEquals(TaskStatus.InProgress, raftMarkdownTaskChipStatus(action("#triage:#169", "triage"), byNumber, byRef))
        // Another channel's #169 is not this one, even though the by-number map has a #169.
        assertNull(raftMarkdownTaskChipStatus(action("#general:#169", "general"), byNumber, byRef))
        assertNull(raftMarkdownTaskChipStatus(action("dm:@peer:#169", peer = "peer"), byNumber, byRef))
        assertEquals(TaskStatus.InReview, raftMarkdownTaskChipStatus(action("#42"), byNumber, byRef))
    }

    /**
     * #proj-mobile:#107, Web MessageItem `createRaftBareTaskRefRegex`: a bare `#4442` that is no
     * known task of the conversation being read is plain text, with nothing to tap (so no "can't
     * open" toast). A known `#7` and a qualified `#triage:#169` keep their chips.
     */
    @Test
    fun unresolvableBareTaskRefIsPlainTextLikeTheWeb() {
        val bare = "#4442".threadInlineTagMatches().single().tag
        assertEquals(ThreadMarkdownTagKind.Task, bare.kind)
        assertFalse(bare.isKnownThreadMarkdownTag(emptySet(), emptySet(), emptySet(), knownTaskNumbers = setOf(7)))
        // No task list is no known task, not "every number is a task".
        assertFalse(bare.isKnownThreadMarkdownTag(emptySet(), emptySet(), emptySet(), knownTaskNumbers = null))
        assertFalse(bare.isKnownThreadMarkdownTag(emptySet(), emptySet(), emptySet(), knownTaskNumbers = emptySet()))

        val isTagValid: (ThreadMarkdownTag) -> Boolean = { tag ->
            tag.isKnownThreadMarkdownTag(setOf("triage"), emptySet(), emptySet(), knownTaskNumbers = setOf(7))
        }
        val body = "fixed in #4442, see #7 and #triage:#169"
        val plan = RaftMarkdownInlineTokenPlanner().plan(content = body, isTagValid = isTagValid)
        assertEquals(listOf("#7", "#triage:#169"), plan.tagTokens().map { it.label })
        val document =
            buildRaftRichTextDocumentFromMarkdownPlan(source = body, content = body, tokenPlan = plan)
        assertEquals(listOf("#7", "#triage:#169"), document.actions.map { it.label })
        assertTrue(document.actions.none { it.label.contains("4442") }, "an unresolvable ref has no tap action")
    }
}
