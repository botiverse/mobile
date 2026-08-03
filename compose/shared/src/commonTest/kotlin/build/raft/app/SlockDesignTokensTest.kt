package build.raft.app

import build.raft.app.components.SlockBrutalActionButtonDefaultTextStyle
import build.raft.app.components.SlockSpinnerActiveSweepAngle
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.text.font.FontListFontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.KuiklyFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SlockDesignTokensTest {
    private fun expectedRenderedWeight(weight: FontWeight): FontWeight =
        if (slockPlatformPreservesEncodedFontWeightForFallback()) weight else FontWeight.Normal

    @Test
    fun slockWeightsResolveToFontFilesWithoutSyntheticWeight() {
        val bold = SlockType.Body.withSlockFontWeight(FontWeight.Bold)
        val semiBold = SlockType.Body.withSlockFontWeight(FontWeight.SemiBold)
        val medium = SlockType.Body.withSlockFontWeight(FontWeight.Medium)

        assertEquals(SlockType.ButtonBold.fontFamily, bold.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), bold.fontWeight)
        assertEquals(expectedRenderedWeight(FontWeight.SemiBold), semiBold.fontWeight)
        assertEquals(expectedRenderedWeight(FontWeight.Medium), medium.fontWeight)
        assertNotEquals(SlockType.Body.fontFamily, bold.fontFamily)
    }

    @Test
    fun resolvedSlockFontFamilyRepairsPlainWeightCopies() {
        val resolved = SlockType.Body.copy(fontWeight = FontWeight.Bold).withResolvedSlockFontFamily()

        assertEquals(SlockType.ButtonBold.fontFamily, resolved.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), resolved.fontWeight)
    }

    @Test
    fun resolvedSlockFontFamilyPreservesResolvedWeightFamily() {
        val resolved = SlockType.ButtonBold.withResolvedSlockFontFamily()

        assertEquals(SlockType.ButtonBold.fontFamily, resolved.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), resolved.fontWeight)
    }

    @Test
    fun slockSpanWeightsResolveToFontFilesWithoutSyntheticWeight() {
        val bold = slockSpanStyleForWeight(FontWeight.Bold)

        assertEquals(SlockType.ButtonBold.fontFamily, bold.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), bold.fontWeight)
    }

    @Test
    fun slockFontFamiliesUseIosRegisteredPostScriptNames() {
        assertEquals("SpaceGrotesk-Regular", SlockType.Body.fontFamily.slockFontName())
        assertEquals("SpaceGrotesk-Medium", SlockType.Button.fontFamily.slockFontName())
        assertEquals("SpaceGrotesk-SemiBold", SlockType.PageTitle.fontFamily.slockFontName())
        assertEquals("SpaceGrotesk-Bold", SlockType.ButtonBold.fontFamily.slockFontName())
    }

    @Test
    fun typographyMatchesReactComponentBaseline() {
        assertEquals(14.sp, SlockType.Body.fontSize)
        assertEquals(20.sp, SlockType.Body.lineHeight)
        assertEquals(14.sp, SlockType.Input.fontSize)
        assertEquals(20.sp, SlockType.Input.lineHeight)
    }

    @Test
    fun brutalShadowTokensMatchReactCssScale() {
        assertEquals(1.dp, SlockShadow.BrutalActive)
        assertEquals(2.dp, SlockShadow.BrutalSmall)
        assertEquals(4.dp, SlockShadow.Brutal)
        assertEquals(6.dp, SlockShadow.BrutalLarge)
    }

    @Test
    fun brutalActionButtonDefaultStyleIsBold() {
        assertEquals(SlockType.ButtonBold.fontFamily, SlockBrutalActionButtonDefaultTextStyle.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), SlockBrutalActionButtonDefaultTextStyle.fontWeight)
    }

    @Test
    fun authActionButtonStylesAreBold() {
        val largeStyle = authActionButtonTextStyle(AuthActionButtonVariant.Large, SlockColor.TextBlack)
        val defaultStyle = authActionButtonTextStyle(AuthActionButtonVariant.Default, SlockColor.TextBlack)

        assertEquals(SlockType.ButtonBold.fontFamily, largeStyle.fontFamily)
        assertEquals(SlockType.ButtonBold.fontFamily, defaultStyle.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), largeStyle.fontWeight)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), defaultStyle.fontWeight)
    }

    @Test
    fun spinnerActiveArcMatchesReactRingTopCap() {
        assertEquals(46f, SlockSpinnerActiveSweepAngle)
    }

    private fun Any?.slockFontName(): String? =
        ((this as? FontListFontFamily)?.fonts?.singleOrNull() as? KuiklyFont)?.fontName
}
