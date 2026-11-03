package build.raft.app.markdown

import build.raft.app.isKnownThreadMarkdownTag
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.threadMarkdownTaskRefStatuses
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
    fun cjkChannelNameAndNoLeftBoundary() {
        assertEquals(listOf("#产品:#3"), refs("去#产品:#3。"))
        // A letter (any script) right after the number ends nothing: `#产品` + bare `#3`, as on the Web.
        assertEquals(listOf("#产品", "#3"), refs("去#产品:#3了"))
        assertEquals(listOf("#proj-mobile:#70"), refs("任务#proj-mobile:#70，谢谢"))
    }

    @Test
    fun directMessageTaskRefsWithAndWithoutPeerKind() {
        assertEquals(listOf("dm:@peer:#7"), refs("ask dm:@peer:#7 now"))
        assertEquals(listOf("dm:@skyzh~agent:#12"), refs("dm:@skyzh~agent:#12"))
        assertEquals(listOf("dm:@skyzh~human:#3"), refs("dm:@skyzh~human:#3."))
        assertEquals(listOf("dm:@release.bot:#4"), refs("dm:@release.bot:#4"))
    }

    @Test
    fun unknownPeerKindStaysMalformedNotATaskRef() {
        assertEquals("dm:@skyzh~bot", refs("dm:@skyzh~bot:#7").first())
    }

    @Test
    fun taskRefsInsideCodeAreSource() {
        assertEquals(emptyList(), refs("`#triage:#169` and `dm:@peer:#7`"))
        assertEquals(emptyList(), refs("```\n#triage:#169\n```"))
        assertEquals(listOf("#triage:#169"), refs("`code` then #triage:#169"))
    }

    @Test
    fun numberMustEndTheToken() {
        // `#proj:#7a`, `#proj:#7-x`: not task refs; `#proj` is still a channel ref and the rest is
        // read by the later passes (bare `#7`, `#abc`), as on the Web.
        assertEquals(listOf("#proj"), refs("#proj:#7a"))
        assertEquals(listOf("#proj", "#7"), refs("#proj:#7-x"))
        assertEquals(listOf("#proj", "#abc"), refs("#proj:#abc"))
        // A second separator: `#proj` + bare `#7` / `#8`, as the Web's later passes read it.
        assertEquals(listOf("#proj", "#7", "#8"), refs("#proj:#7:#8"))
        // `dm:@peer:#7x` keeps the bare DM ref.
        assertEquals(listOf("dm:@peer"), refs("dm:@peer:#7x"))
    }

    @Test
    fun threadRefsAreUnchanged() {
        assertEquals(listOf("#markdown:692d9e88"), refs("#markdown:692d9e88"))
        assertEquals(listOf("dm:@peer:692d9e88"), refs("dm:@peer:692d9e88"))
    }

    @Test
    fun labelParser() {
        assertEquals(RaftMarkdownTaskRef(169, channelName = "triage"), parseRaftMarkdownTaskRefLabel("#triage:#169"))
        assertEquals(RaftMarkdownTaskRef(3, channelName = "产品"), parseRaftMarkdownTaskRefLabel("#产品:#3"))
        assertEquals(RaftMarkdownTaskRef(7, directMessagePeerName = "peer"), parseRaftMarkdownTaskRefLabel("dm:@peer:#7"))
        assertEquals(
            RaftMarkdownTaskRef(12, directMessagePeerName = "skyzh~agent"),
            parseRaftMarkdownTaskRefLabel("dm:@skyzh~agent:#12")
        )
        assertEquals(RaftMarkdownTaskRef(42), parseRaftMarkdownTaskRefLabel("#42"))
        assertNull(parseRaftMarkdownTaskRefLabel("#proj"))
        assertNull(parseRaftMarkdownTaskRefLabel("#proj:692d9e88"))
        assertNull(parseRaftMarkdownTaskRefLabel("#proj:#07"))
        assertNull(parseRaftMarkdownTaskRefLabel("dm:@skyzh~bot:#7"))
        assertNull(parseRaftMarkdownTaskRefLabel(":#7"))
    }

    @Test
    fun qualifiedRefBecomesTaskTagWithItsConversation() {
        val channel = "see #triage:#169".threadInlineTagMatches().single().tag
        assertEquals(ThreadMarkdownTagKind.Task, channel.kind)
        assertEquals("#triage:#169", channel.label)
        assertEquals("triage", channel.channelName)

        val dm = "dm:@skyzh~agent:#12".threadInlineTagMatches().single().tag
        assertEquals(ThreadMarkdownTagKind.Task, dm.kind)
        assertEquals("skyzh~agent", dm.directMessagePeerName)
    }

    @Test
    fun qualifiedRefIsKnownOnlyWhenItsConversationIs() {
        val tag = "#triage:#169".threadInlineTagMatches().single().tag
        assertTrue(tag.isKnownThreadMarkdownTag(setOf("triage"), emptySet(), emptySet(), knownTaskNumbers = setOf(1)))
        assertFalse(tag.isKnownThreadMarkdownTag(setOf("general"), emptySet(), emptySet()))
        val dm = "dm:@peer:#7".threadInlineTagMatches().single().tag
        assertTrue(dm.isKnownThreadMarkdownTag(emptySet(), emptySet(), setOf("peer")))
        assertFalse(dm.isKnownThreadMarkdownTag(emptySet(), emptySet(), setOf("other")))
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

    @Test
    fun taskRefStatusesKeyChannelsByNameAndOnlyTheCurrentDm() {
        fun task(number: Int, channel: String?, type: String? = "channel", status: TaskStatus = TaskStatus.Todo) =
            TaskSummary(
                id = TaskId("t-$channel-$number"),
                title = "t",
                status = status,
                taskNumber = number,
                channelId = ChannelId("c-$channel"),
                channelName = channel,
                channelType = type,
                updatedAtLabel = ""
            )
        val statuses =
            threadMarkdownTaskRefStatuses(
                channelTasks = listOf(task(7, "dm-abc", type = "dm", status = TaskStatus.InReview)),
                serverTasks = listOf(task(169, "triage", status = TaskStatus.InProgress), task(5, null)),
                currentDirectMessagePeerName = "Peer"
            )
        assertEquals(TaskStatus.InProgress, statuses[raftMarkdownTaskRefStatusKey(169, channelName = "triage")])
        assertEquals(TaskStatus.InReview, statuses[raftMarkdownTaskRefStatusKey(7, directMessagePeerName = "peer~agent")])
        assertNull(statuses[raftMarkdownTaskRefStatusKey(7, channelName = "dm-abc")])
        assertEquals(2, statuses.size)
    }
}
