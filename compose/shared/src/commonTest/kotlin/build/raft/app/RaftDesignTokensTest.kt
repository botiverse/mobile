package build.raft.app

import build.raft.app.components.RaftBrutalActionButtonDefaultTextStyle
import build.raft.app.components.RaftSpinnerActiveSweepAngle
import build.raft.app.components.RaftSpinnerPixelRestAlpha
import build.raft.app.components.RaftAppSpinnerSize
import build.raft.app.components.raftAppSpinnerLitCell
import build.raft.app.components.raftSpinnerStepAt
import build.raft.app.data.FontPreference
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import build.raft.app.compat.ui.raftFontResourceName
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class RaftDesignTokensTest {
    // The face state is process-wide; restore it so this suite cannot leak a face into others.
    @AfterTest
    fun restoreDefaultFace() {
        RaftFontFaceState.apply(FontPreference.Default)
    }

    private fun expectedRenderedWeight(weight: FontWeight): FontWeight =
        if (raftPlatformPreservesEncodedFontWeightForFallback()) weight else FontWeight.Normal

    @Test
    fun raftWeightsResolveToFontFilesWithoutSyntheticWeight() {
        val bold = RaftType.Body.withRaftFontWeight(FontWeight.Bold)
        val semiBold = RaftType.Body.withRaftFontWeight(FontWeight.SemiBold)
        val medium = RaftType.Body.withRaftFontWeight(FontWeight.Medium)

        assertEquals(RaftType.ButtonBold.fontFamily, bold.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), bold.fontWeight)
        assertEquals(expectedRenderedWeight(FontWeight.SemiBold), semiBold.fontWeight)
        assertEquals(expectedRenderedWeight(FontWeight.Medium), medium.fontWeight)
        assertNotEquals(RaftType.Body.fontFamily, bold.fontFamily)
    }

    @Test
    fun resolvedRaftFontFamilyRepairsPlainWeightCopies() {
        val resolved = RaftType.Body.copy(fontWeight = FontWeight.Bold).withResolvedRaftFontFamily()

        assertEquals(RaftType.ButtonBold.fontFamily, resolved.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), resolved.fontWeight)
    }

    @Test
    fun resolvedRaftFontFamilyPreservesResolvedWeightFamily() {
        val resolved = RaftType.ButtonBold.withResolvedRaftFontFamily()

        assertEquals(RaftType.ButtonBold.fontFamily, resolved.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), resolved.fontWeight)
    }

    @Test
    fun raftSpanWeightsResolveToFontFilesWithoutSyntheticWeight() {
        val bold = raftSpanStyleForWeight(FontWeight.Bold)

        assertEquals(RaftType.ButtonBold.fontFamily, bold.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), bold.fontWeight)
    }

    @Test
    fun raftFontFamiliesUseIosRegisteredPostScriptNames() {
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
                RaftType.Body to "$psPrefix-Regular",
                RaftType.Button to "$psPrefix-Medium",
                RaftType.PageTitle to "$psPrefix-SemiBold",
                RaftType.ButtonBold to "$psPrefix-Bold",
            )
        expected.forEach { (style, name) ->
            assertEquals(raftFontResourceName(name), style.fontFamily.raftFontName(), "default face $defaultFace")
        }
    }

    @Test
    fun everyBundledWeightNameIsDistinctSoIosCanTellWeightsApart() {
        // A variable font instanced per weight keeps ONE PostScript name unless the name table is
        // rewritten, and then iOS collapses every weight into a single face (all text renders at
        // Regular). Assert the names the renderer will actually ask for are pairwise distinct.
        FontPreference.entries.filter { it.sansFamily != null }.forEach { preference ->
            RaftFontFaceState.apply(preference)
            val names =
                listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
                    .map { raftFontFamilyForWeight(it).raftFontName() }
            assertEquals(
                names.size,
                names.toSet().size,
                "$preference exposes duplicate PostScript names: $names"
            )
            assertTrue(names.none { it.isNullOrBlank() }, "$preference has an unnamed weight: $names")
        }
        RaftFontFaceState.apply(FontPreference.Default)
    }

    @Test
    fun contentTextSharesOneSize() {
        // artin 2026-10-18 (1.14.1): body copy, the composer, message text and sidebar names share
        // RaftTextSize.Content instead of each hardcoding its own size (14sp after the 16sp trial).
        assertEquals(14.sp, RaftTextSize.Content)
        assertEquals(20.sp, RaftTextSize.ContentLineHeight)
        for (style in listOf(RaftType.Body, RaftType.Input, raftMessageBodyTextStyle(null))) {
            assertEquals(RaftTextSize.Content, style.fontSize)
            assertEquals(RaftTextSize.ContentLineHeight, style.lineHeight)
        }
        for (style in listOf(RaftType.ListItem, RaftType.ListItemBold)) {
            assertEquals(RaftTextSize.Content, style.fontSize)
            assertEquals(RaftTextSize.ListItemLineHeight, style.lineHeight)
        }
    }

    @Test
    fun shadowTokensMatchRaftUiScale() {
        // raft-ui 0.6.0: controls never cast a shadow (`--theme-shadow-xs/sm/md` are 0);
        // only stickers keep the hard 4px offset.
        assertEquals(0.dp, RaftShadow.BrutalActive)
        assertEquals(0.dp, RaftShadow.BrutalSmall)
        assertEquals(0.dp, RaftShadow.Brutal)
        assertEquals(0.dp, RaftShadow.BrutalLarge)
        assertEquals(4.dp, RaftShadow.Sticker)
    }

    @Test
    fun brutalActionButtonDefaultStyleIsBold() {
        assertEquals(RaftType.ButtonBold.fontFamily, RaftBrutalActionButtonDefaultTextStyle.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), RaftBrutalActionButtonDefaultTextStyle.fontWeight)
    }

    @Test
    fun authActionButtonStylesAreBold() {
        val largeStyle = authActionButtonTextStyle(AuthActionButtonVariant.Large, RaftColor.TextBlack)
        val defaultStyle = authActionButtonTextStyle(AuthActionButtonVariant.Default, RaftColor.TextBlack)

        assertEquals(RaftType.ButtonBold.fontFamily, largeStyle.fontFamily)
        assertEquals(RaftType.ButtonBold.fontFamily, defaultStyle.fontFamily)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), largeStyle.fontWeight)
        assertEquals(expectedRenderedWeight(FontWeight.Bold), defaultStyle.fontWeight)
    }

    @Test
    fun brutalSpinnerActiveArcMatchesReactRingTopCap() {
        assertEquals(46f, RaftSpinnerActiveSweepAngle)
    }

    @Test
    fun raftSpinnerSizesMatchRaftUiPixelGrid() {
        // raft-ui Spinner `--spinner-pixel` / `--spinner-gap` per size.
        assertEquals(listOf(4f to 1.5f, 5f to 2f, 6f to 2f, 8f to 3f), RaftAppSpinnerSize.entries.map { it.pixel.value to it.gap.value })
        assertEquals(12f, RaftAppSpinnerSize.Sm.extent.value)
        assertEquals(19f, RaftAppSpinnerSize.Lg.extent.value)
    }

    @Test
    fun raftSpinnerStepsClockwiseEveryQuarterSecond() {
        // raft-spinner-pixel: 1s steps(1); delays 0 / .25 / .75 / .5s on cells TL, TR, BL, BR.
        assertEquals(listOf(0, 0, 1, 2, 3, 0), listOf(0, 249, 250, 500, 999, 1000).map(::raftSpinnerStepAt))
        assertEquals(listOf(0, 1, 3, 2), (0 until 4).map(::raftAppSpinnerLitCell))
        assertEquals(0, raftAppSpinnerLitCell(4))
        // The canvas redraws only when the derived step changes: four times per 1s cycle.
        val stepChanges = (1 until 1000).count { raftSpinnerStepAt(it) != raftSpinnerStepAt(it - 1) }
        assertEquals(3, stepChanges)
        assertEquals(0, raftSpinnerStepAt(1000))
        assertEquals(0.2f, RaftSpinnerPixelRestAlpha)
    }

    private fun Any?.raftFontName(): String? =
        // CMP switch: compares the normalized bundled-face key (see raftTestBundledFontKey).
        (this as? FontListFontFamily)?.fonts?.singleOrNull()?.raftTestBundledFontKey()
}
