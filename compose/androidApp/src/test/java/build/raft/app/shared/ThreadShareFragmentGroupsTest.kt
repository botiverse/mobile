package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadShareFragmentGroupsTest {
    @Test
    fun fragmentsGroupOneMessagePerBlankLineSeparatedBlock() {
        assertEquals(
            listOf(listOf("/a-1.png", "/a-2.png"), listOf("/b-1.png")),
            threadShareFragmentGroups("/a-1.png\n/a-2.png\n\n/b-1.png"),
        )
        // Stray blank lines and padding never make an empty message.
        assertEquals(
            listOf(listOf("/a.png"), listOf("/b.png")),
            threadShareFragmentGroups("\n /a.png \n\n\n/b.png\n"),
        )
        assertEquals(emptyList<List<String>>(), threadShareFragmentGroups(""))
    }
}
