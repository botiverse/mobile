package build.raft.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RaftComposedCharacterBoundaryRuntimeTest {
    @Test
    fun androidIcuPreservesExtendedGraphemeClusters() {
        assertEquals("क्ष".length, raftFirstComposedCharacterEnd("क्षtool"))
        assertEquals("👩‍💻".length, raftFirstComposedCharacterEnd("👩‍💻tool"))
        assertEquals("e\u0301".length, raftFirstComposedCharacterEnd("e\u0301tool"))
    }
}
