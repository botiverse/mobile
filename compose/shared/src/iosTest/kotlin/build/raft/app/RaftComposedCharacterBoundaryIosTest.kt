package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftComposedCharacterBoundaryIosTest {
    @Test
    fun foundationPreservesExtendedGraphemeClusters() {
        assertNull(raftFirstComposedCharacterEnd(""))
        assertEquals(1, raftFirstComposedCharacterEnd("CODE_SPAN"))
        assertEquals("क्ष".length, raftFirstComposedCharacterEnd("क्षtool"))
        assertEquals("👩‍💻".length, raftFirstComposedCharacterEnd("👩‍💻tool"))
        assertEquals("e\u0301".length, raftFirstComposedCharacterEnd("e\u0301tool"))
    }
}
