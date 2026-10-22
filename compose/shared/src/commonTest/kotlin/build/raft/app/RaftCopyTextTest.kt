package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a long press ([build.raft.designsystem.components.raftCopyable]) copies on the pages the
 * copy audit covered: the raw value, a handle with its "@", never a label, status or stand-in.
 */
class RaftCopyTextTest {
    @Test
    fun channelSettingsNameCopiesWithoutTheHash() {
        assertEquals("general", channelSettingsCopyName("general"))
        assertEquals("general", channelSettingsCopyName("#general"))
    }

    @Test
    fun accountHandleCopiesWithItsAt() {
        assertEquals("@alice", settingsAccountHeaderHandleLabel("alice"))
        assertEquals("@alice", settingsAccountHeaderHandleLabel(" @alice "))
        // Blank: nothing to copy (the field shows the placeholder).
        assertNull(settingsAccountHeaderHandleLabel(""))
    }

    @Test
    fun conversationHeaderCopiesChannelAndDmNamesOnly() {
        val cases =
            listOf(
                Triple("general", RaftThreadSurfaceKind.Channel, "general"),
                Triple("Ada Lovelace", RaftThreadSurfaceKind.DirectMessage, "Ada Lovelace"),
                // Stand-ins shown before the name loads.
                Triple("Channel", RaftThreadSurfaceKind.Channel, null),
                Triple("Direct Message", RaftThreadSurfaceKind.DirectMessage, null),
                // A reply thread's title previews the parent message (a message body).
                Triple("ship the build tonight", RaftThreadSurfaceKind.ReplyThread, null),
                Triple("  ", RaftThreadSurfaceKind.Channel, null),
            )
        cases.forEach { (title, kind, expected) ->
            assertEquals(expected, threadHeaderCopyText(title, kind), "title=$title kind=$kind")
        }
    }

    @Test
    fun taskDetailTitleCopiesTheTaskReference() {
        assertEquals("#42", threadTaskDetailTitleCopyText(42, "general"))
        assertEquals("general", threadTaskDetailTitleCopyText(null, "general"))
        assertNull(threadTaskDetailTitleCopyText(null, " "))
        assertNull(threadTaskDetailTitleCopyText(null, null))
    }

    @Test
    fun joinLinkCopiesAnAbsoluteUrlAgainstTheActiveEnvironment() {
        val staging = "https://staging.raft.build"
        assertEquals("https://app.raft.build/join/abc", settingsServerJoinLinkCopyUrl("https://app.raft.build/join/abc", staging))
        assertEquals("https://staging.raft.build/join/abc", settingsServerJoinLinkCopyUrl("/join/abc", staging))
        assertEquals("https://staging.raft.build/join/abc", settingsServerJoinLinkCopyUrl("join/abc", "$staging/"))
        // No environment URL: a relative link isn't copied against a hardcoded host.
        assertNull(settingsServerJoinLinkCopyUrl("/join/abc", ""))
        assertNull(settingsServerJoinLinkCopyUrl(null, staging))
        assertNull(settingsServerJoinLinkCopyUrl(" ", staging))
    }

    @Test
    fun fileDetailHeaderCopiesTheFileNameNotAStandIn() {
        val fallback = "File"
        assertEquals("report.pdf", RaftFilePreviewPayload(name = "report.pdf").raftFilePreviewHeaderCopyText(fallback))
        assertEquals("notes.md", RaftFilePreviewPayload(path = "/work/docs/notes.md").raftFilePreviewHeaderCopyText(fallback))
        assertEquals("a.txt", RaftFilePreviewPayload(externalFileName = "a.txt").raftFilePreviewHeaderCopyText(fallback))
        assertNull(RaftFilePreviewPayload().raftFilePreviewHeaderCopyText(fallback))
    }
}
