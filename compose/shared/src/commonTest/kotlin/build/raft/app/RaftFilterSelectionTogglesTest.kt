package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftFilterSelectionTogglesTest {
    @Test
    fun anAddedIdIsToggledOnce() {
        assertEquals(listOf("c"), raftFilterSelectionToggles(linkedSetOf("a", "b"), listOf("a", "b", "c")))
    }

    @Test
    fun aDroppedIdIsToggledOnce() {
        assertEquals(listOf("b"), raftFilterSelectionToggles(linkedSetOf("a", "b"), listOf("a")))
    }
}
