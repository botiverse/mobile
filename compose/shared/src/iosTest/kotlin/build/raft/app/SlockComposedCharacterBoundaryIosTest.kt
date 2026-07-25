package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockComposedCharacterBoundaryIosTest {
    @Test
    fun foundationPreservesExtendedGraphemeClusters() {
        assertEquals("क्ष".length, slockFirstComposedCharacterEnd("क्षtool"))
        assertEquals("👩‍💻".length, slockFirstComposedCharacterEnd("👩‍💻tool"))
        assertEquals("e\u0301".length, slockFirstComposedCharacterEnd("e\u0301tool"))
        assertEquals("tool".length, slockLastComposedCharacterStart("toolक्ष"))
        assertEquals("tool".length, slockLastComposedCharacterStart("tool👩‍💻"))
        assertEquals("tool".length, slockLastComposedCharacterStart("toole\u0301"))
    }
}
