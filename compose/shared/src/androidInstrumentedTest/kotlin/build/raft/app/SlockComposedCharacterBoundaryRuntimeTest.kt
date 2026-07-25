package build.raft.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlockComposedCharacterBoundaryRuntimeTest {
    @Test
    fun androidIcuPreservesExtendedGraphemeClusters() {
        assertEquals("क्ष".length, slockFirstComposedCharacterEnd("क्षtool"))
        assertEquals("👩‍💻".length, slockFirstComposedCharacterEnd("👩‍💻tool"))
        assertEquals("e\u0301".length, slockFirstComposedCharacterEnd("e\u0301tool"))
    }
}
