package build.raft.app

import build.raft.app.components.SlockBrutalActionButtonDefaultTextStyle
import build.raft.app.components.SlockSpinnerActiveSweepAngle
import build.raft.app.components.SlockSpinnerPixelRestAlpha
import build.raft.app.components.SlockSpinnerSize
import build.raft.app.components.slockSpinnerLitCell
import build.raft.app.components.slockSpinnerStepAt
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
    fun contentTextSharesOneSize() {
        // artin 2026-10-18 (1.14.1): body copy, the composer, message text and sidebar names share
        // SlockTextSize.Content instead of each hardcoding its own size (14sp after the 16sp trial).
        assertEquals(14.sp, SlockTextSize.Content)
        assertEquals(20.sp, SlockTextSize.ContentLineHeight)
        for (style in listOf(SlockType.Body, SlockType.Input, slockMessageBodyTextStyle(null))) {
            assertEquals(SlockTextSize.Content, style.fontSize)
            assertEquals(SlockTextSize.ContentLineHeight, style.lineHeight)
        }
        for (style in listOf(SlockType.ListItem, SlockType.ListItemBold)) {
            assertEquals(SlockTextSize.Content, style.fontSize)
            assertEquals(SlockTextSize.ListItemLineHeight, style.lineHeight)
        }
    }

    @Test
    fun shadowTokensMatchRaftUiScale() {
        // raft-ui 0.6.0: controls never cast a shadow (`--theme-shadow-xs/sm/md` are 0);
        // only stickers keep the hard 4px offset.
        assertEquals(0.dp, SlockShadow.BrutalActive)
        assertEquals(0.dp, SlockShadow.BrutalSmall)
        assertEquals(0.dp, SlockShadow.Brutal)
        assertEquals(0.dp, SlockShadow.BrutalLarge)
        assertEquals(4.dp, SlockShadow.Sticker)
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
    fun brutalSpinnerActiveArcMatchesReactRingTopCap() {
        assertEquals(46f, SlockSpinnerActiveSweepAngle)
    }

    @Test
    fun raftSpinnerSizesMatchRaftUiPixelGrid() {
        // raft-ui Spinner `--spinner-pixel` / `--spinner-gap` per size.
        assertEquals(listOf(4f to 1.5f, 5f to 2f, 6f to 2f, 8f to 3f), SlockSpinnerSize.entries.map { it.pixel.value to it.gap.value })
        assertEquals(12f, SlockSpinnerSize.Sm.extent.value)
        assertEquals(19f, SlockSpinnerSize.Lg.extent.value)
    }

    @Test
    fun raftSpinnerStepsClockwiseEveryQuarterSecond() {
        // raft-spinner-pixel: 1s steps(1); delays 0 / .25 / .75 / .5s on cells TL, TR, BL, BR.
        assertEquals(listOf(0, 0, 1, 2, 3, 0), listOf(0, 249, 250, 500, 999, 1000).map(::slockSpinnerStepAt))
        assertEquals(listOf(0, 1, 3, 2), (0 until 4).map(::slockSpinnerLitCell))
        assertEquals(0, slockSpinnerLitCell(4))
        assertEquals(0.2f, SlockSpinnerPixelRestAlpha)
    }

    private fun Any?.slockFontName(): String? =
        ((this as? FontListFontFamily)?.fonts?.singleOrNull() as? KuiklyFont)?.fontName
}
