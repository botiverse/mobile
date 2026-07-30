package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockComposedCharacterBoundaryOhosTest {
    @Test
    fun firstComposedCharacterBoundaryMatchesUtf16Offsets() {
        assertNull(slockFirstComposedCharacterEnd(""))
        assertEquals(1, slockFirstComposedCharacterEnd("CODE_SPAN"))

        // OHOS ICU 72 predates Unicode 15.1 GB9c, so Indic conjunct
        // boundaries are intentionally not pinned to newer ICU behavior.
        assertEquals("👩‍💻".length, slockFirstComposedCharacterEnd("👩‍💻tool"))
        assertEquals("e\u0301".length, slockFirstComposedCharacterEnd("e\u0301tool"))
    }
}
