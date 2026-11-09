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

    @Test
    fun droppedIdsComeBeforeAddedOnes() {
        assertEquals(listOf("a", "c", "d"), raftFilterSelectionToggles(linkedSetOf("a", "b"), listOf("b", "c", "d", "c")))
    }

    @Test
    fun theSameSelectionTogglesNothing() {
        assertEquals(emptyList<String>(), raftFilterSelectionToggles(linkedSetOf("a", "b"), listOf("b", "a")))
    }
}
