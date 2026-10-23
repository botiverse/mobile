package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a long press ([build.raft.designsystem.components.raftCopyable]) copies on the pages the
 * copy audit covered: exactly the text shown (artin: 看到啥就复制成啥), never a status or stand-in.
 */
class RaftCopyTextTest {
    @Test
    fun channelSettingsNameCopiesWithItsHashAsShown() {
        assertEquals("#general", channelSettingsHeaderName("general"))
        assertEquals("#general", channelSettingsHeaderName("#general"))
    }

    @Test
    fun accountHeaderHandleIsShownAndCopiedWithItsAt() {
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
    fun fileDetailHeaderCopiesTheFileNameNotAStandIn() {
        val fallback = "File"
        assertEquals("report.pdf", RaftFilePreviewPayload(name = "report.pdf").raftFilePreviewHeaderCopyText(fallback))
        assertEquals("notes.md", RaftFilePreviewPayload(path = "/work/docs/notes.md").raftFilePreviewHeaderCopyText(fallback))
        assertEquals("a.txt", RaftFilePreviewPayload(externalFileName = "a.txt").raftFilePreviewHeaderCopyText(fallback))
        assertNull(RaftFilePreviewPayload().raftFilePreviewHeaderCopyText(fallback))
    }
}
