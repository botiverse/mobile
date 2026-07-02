package build.raft.app

import build.raft.app.components.SlockBrutalActionButtonDefaultTextStyle
import com.tencent.kuikly.compose.ui.text.font.FontListFontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.KuiklyFont
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

    @Test
    fun slockFontFamiliesUseIosRegisteredPostScriptNames() {
        assertEquals("SpaceGrotesk-Regular", SlockType.Body.fontFamily.slockFontName())
        assertEquals("SpaceGrotesk-Medium", SlockType.Button.fontFamily.slockFontName())
        assertEquals("SpaceGrotesk-SemiBold", SlockType.PageTitle.fontFamily.slockFontName())
        assertEquals("SpaceGrotesk-Bold", SlockType.ButtonBold.fontFamily.slockFontName())
    }

    @Test
    fun brutalActionButtonDefaultStyleIsBold() {
        assertEquals(SlockType.ButtonBold.fontFamily, SlockBrutalActionButtonDefaultTextStyle.fontFamily)
        assertEquals(FontWeight.Normal, SlockBrutalActionButtonDefaultTextStyle.fontWeight)
    }

    private fun Any?.slockFontName(): String? =
        ((this as? FontListFontFamily)?.fonts?.singleOrNull() as? KuiklyFont)?.fontName
}
