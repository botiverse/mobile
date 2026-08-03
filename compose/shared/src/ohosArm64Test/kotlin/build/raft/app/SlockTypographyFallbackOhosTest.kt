package build.raft.app

import com.tencent.kuikly.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlockTypographyFallbackOhosTest {
    @Test
    fun spaceGroteskFacesKeepSemanticWeightForCjkFallback() {
        assertTrue(slockPlatformPreservesEncodedFontWeightForFallback())
        assertEquals(FontWeight.Normal, SlockType.Body.fontWeight)
        assertEquals(FontWeight.Medium, SlockType.Button.fontWeight)
        assertEquals(FontWeight.SemiBold, SlockType.PageTitle.fontWeight)
        assertEquals(FontWeight.Bold, SlockType.ButtonBold.fontWeight)
    }

    @Test
    fun richTextSpanKeepsBoldSemanticWeightForCjkFallback() {
        assertEquals(FontWeight.Bold, slockSpanStyleForWeight(FontWeight.Bold).fontWeight)
    }
}
