package build.raft.app

import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.markdown.ThreadMarkdownTagKind
import build.raft.app.markdown.threadMarkdownTagDisplayLabel
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.resolveThreadMarkdownTagDisplayLabel
import build.raft.app.thread.threadMarkdownMentionDisplayLabels
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadMarkdownMentionFirstFrameTest {
    @Test
    fun mentionLabelsPreferChannelMembersAndKeepTheHandleWhenUnknown() {
        val labels =
            threadMarkdownMentionDisplayLabels(
                members = listOf(RaftThreadMemberItem(id = "u-1", displayName = "Channel Name", handle = "same")),
                memberCandidates = listOf(RaftThreadMemberItem(id = "u-2", displayName = "Directory Name", handle = "same"))
            )

        assertEquals("@Channel Name", resolveThreadMarkdownTagDisplayLabel(mention("same"), labels))
        assertEquals(
            threadMarkdownTagDisplayLabel("@nobody"),
            resolveThreadMarkdownTagDisplayLabel(mention("nobody"), labels)
        )
    }

    private fun mention(handle: String): ThreadMarkdownTag =
        ThreadMarkdownTag("@$handle", ThreadMarkdownTagKind.UserMention)
}
