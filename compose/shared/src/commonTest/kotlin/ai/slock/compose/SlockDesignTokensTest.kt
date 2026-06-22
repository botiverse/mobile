package ai.slock.compose

import com.tencent.kuikly.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SlockDesignTokensTest {
    @Test
    fun slockWeightsResolveToFontFilesWithoutSyntheticWeight() {
        val bold = SlockType.Body.withSlockFontWeight(FontWeight.Bold)
        val semiBold = SlockType.Body.withSlockFontWeight(FontWeight.SemiBold)
        val medium = SlockType.Body.withSlockFontWeight(FontWeight.Medium)

        assertEquals(SlockType.ButtonBold.fontFamily, bold.fontFamily)
        assertEquals(FontWeight.Normal, bold.fontWeight)
        assertEquals(FontWeight.Normal, semiBold.fontWeight)
        assertEquals(FontWeight.Normal, medium.fontWeight)
        assertNotEquals(SlockType.Body.fontFamily, bold.fontFamily)
    }

    @Test
    fun resolvedSlockFontFamilyRepairsPlainWeightCopies() {
        val resolved = SlockType.Body.copy(fontWeight = FontWeight.Bold).withResolvedSlockFontFamily()

        assertEquals(SlockType.ButtonBold.fontFamily, resolved.fontFamily)
        assertEquals(FontWeight.Normal, resolved.fontWeight)
    }

    @Test
    fun resolvedSlockFontFamilyPreservesResolvedWeightFamily() {
        val resolved = SlockType.ButtonBold.withResolvedSlockFontFamily()

        assertEquals(SlockType.ButtonBold.fontFamily, resolved.fontFamily)
        assertEquals(FontWeight.Normal, resolved.fontWeight)
    }
}
