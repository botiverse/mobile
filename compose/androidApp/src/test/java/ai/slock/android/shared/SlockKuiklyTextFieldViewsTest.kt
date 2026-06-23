package ai.slock.android.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SlockKuiklyTextFieldViewsTest {
    @Test
    fun lineCountParserAcceptsNumericPropVariants() {
        assertEquals(4, 4.toSlockLineCount())
        assertEquals(4, 4L.toSlockLineCount())
        assertEquals(4, 4.0f.toSlockLineCount())
        assertEquals(4, 4.0.toSlockLineCount())
        assertEquals(4, "4".toSlockLineCount())
        assertEquals(4, "4.0".toSlockLineCount())
        assertEquals(1, null.toSlockLineCount())
        assertEquals(1, "not-a-number".toSlockLineCount())
    }
}
