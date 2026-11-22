package build.raft.designsystem

import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.raftAvatarInitials
import build.raft.designsystem.components.raftFirstGrapheme
import build.raft.designsystem.components.raftUnreadCountDescription
import build.raft.designsystem.components.raftUnreadCountText
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDisplayLooksTest {
    @Test
    fun countsCapAt99() {
        assertEquals("7", raftUnreadCountText(7))
        assertEquals("99", raftUnreadCountText(99))
        assertEquals("99+", raftUnreadCountText(100))
    }

    @Test
    fun initialsNeverSplitACharacter() {
        // A surrogate pair, an emoji with a skin tone, a ZWJ family, a flag, a combining accent.
        assertEquals("\uD83D\uDE00L", raftAvatarInitials("\uD83D\uDE00 Lovelace", RaftAvatarSize.Md))
        assertEquals("\uD83D\uDC4B\uD83C\uDFFD", raftAvatarInitials("\uD83D\uDC4B\uD83C\uDFFDhi", RaftAvatarSize.Sm))
        val family = "\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, raftFirstGrapheme("${family}x"))
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7", raftFirstGrapheme("\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDE9\uD83C\uDDEA"))
        assertEquals("E\u0301", raftAvatarInitials("e\u0301mile", RaftAvatarSize.Sm))
        assertEquals("", raftFirstGrapheme(""))
    }

    @Test
    fun unreadCountReadsTheFullNumber() {
        assertEquals("4 unread", raftUnreadCountDescription(4))
        assertEquals("120 unread", raftUnreadCountDescription(120))
    }
}
