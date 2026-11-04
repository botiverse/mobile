package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AnnouncementLabelTest {
    @Test
    fun announcementWithoutLabelReadsTheDefaultLabel() {
        assertEquals("What is new", announcementLabelOrDefault(label = null, default = "What is new"))
    }

    @Test
    fun announcementWithItsOwnLabelKeepsIt() {
        assertEquals("Release 1.19", announcementLabelOrDefault(label = "Release 1.19", default = "What is new"))
    }
}
