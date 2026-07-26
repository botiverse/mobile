package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockComposedCharacterBoundaryIosTest {
    @Test
    fun foundationPreservesExtendedGraphemeClusters() {
        assertNull(slockFirstComposedCharacterEnd(""))
        assertEquals(1, slockFirstComposedCharacterEnd("CODE_SPAN"))
        assertEquals("क्ष".length, slockFirstComposedCharacterEnd("क्षtool"))
        assertEquals("👩‍💻".length, slockFirstComposedCharacterEnd("👩‍💻tool"))
        assertEquals("e\u0301".length, slockFirstComposedCharacterEnd("e\u0301tool"))
    }
}
