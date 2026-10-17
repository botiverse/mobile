package build.raft.app

import build.raft.app.components.SlockBrutalActionButtonDefaultTextStyle
import build.raft.app.components.SlockSpinnerActiveSweepAngle
import build.raft.app.data.FontPreference
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.ui.text.font.FontListFontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.font.KuiklyFont
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class SlockDesignTokensTest {
    // The face state is process-wide; restore it so this suite cannot leak a face into others.
    @AfterTest
    fun restoreDefaultFace() {
        SlockFontFaceState.apply(FontPreference.Default)
    }

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
        // The contract is: whatever face the default option resolves to, its per-weight names must
        // be the PostScript names iOS has registered from Info.plist/UIAppFonts, because that is
        // how the family resolves on iOS at all. It is NOT "the default is a particular typeface"
        // -- task #179 moved the default from Space Grotesk to Hanken Grotesk to match web, and a
        // test hardcoding the old face would have blocked that for the wrong reason.
        val defaultFace = FontPreference.Default
        val psPrefix =
            when (defaultFace) {
                FontPreference.HankenGrotesk -> "HankenGrotesk"
                FontPreference.GeistInter -> "Geist"
                FontPreference.SpaceGrotesk -> "SpaceGrotesk"
                // The system option names no bundled family, so there is no PostScript name to check.
                FontPreference.System -> return
            }
        val expected =
            mapOf(
                SlockType.Body to "$psPrefix-Regular",
                SlockType.Button to "$psPrefix-Medium",
                SlockType.PageTitle to "$psPrefix-SemiBold",
                SlockType.ButtonBold to "$psPrefix-Bold",
            )
        expected.forEach { (style, name) ->
            assertEquals(name, style.fontFamily.slockFontName(), "default face $defaultFace")
        }
    }

    @Test
    fun everyBundledWeightNameIsDistinctSoIosCanTellWeightsApart() {
        // A variable font instanced per weight keeps ONE PostScript name unless the name table is
        // rewritten, and then iOS collapses every weight into a single face (all text renders at
        // Regular). Assert the names the renderer will actually ask for are pairwise distinct.
        FontPreference.entries.filter { it.sansFamily != null }.forEach { preference ->
            SlockFontFaceState.apply(preference)
            val names =
                listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
                    .map { slockFontFamilyForWeight(it).slockFontName() }
            assertEquals(
                names.size,
                names.toSet().size,
                "$preference exposes duplicate PostScript names: $names"
            )
            assertTrue(names.none { it.isNullOrBlank() }, "$preference has an unnamed weight: $names")
        }
        SlockFontFaceState.apply(FontPreference.Default)
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
