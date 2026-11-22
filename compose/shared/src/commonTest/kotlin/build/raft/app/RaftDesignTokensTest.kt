package build.raft.app

import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import build.raft.app.compat.ui.raftFontResourceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class RaftDesignTokensTest {
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
    fun raftFontFamiliesUseIosRegisteredPostScriptNames() {
        // The per-weight names must be the PostScript names iOS registers from UIAppFonts, because
        // that is how the family resolves on iOS at all. The design system fixes the face.
        val psPrefix = "SpaceGrotesk"
        val expected =
            mapOf(
                RaftType.Body to "$psPrefix-Regular",
                RaftType.Button to "$psPrefix-Medium",
                RaftType.Body.withRaftFontWeight(FontWeight.SemiBold) to "$psPrefix-SemiBold",
                RaftType.ButtonBold to "$psPrefix-Bold",
            )
        expected.forEach { (style, name) ->
            assertEquals(raftFontResourceName(name), style.fontFamily.raftFontName())
        }
    }

    @Test
    fun everyBundledWeightNameIsDistinctSoIosCanTellWeightsApart() {
        // A variable font instanced per weight keeps ONE PostScript name unless the name table is
        // rewritten, and then iOS collapses every weight into a single face (all text renders at
        // Regular). Assert the names the renderer will actually ask for are pairwise distinct.
        val names =
            listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
                .map { raftFontFamilyForWeight(it).raftFontName() }
        assertEquals(names.size, names.toSet().size, "duplicate PostScript names: $names")
        assertTrue(names.none { it.isNullOrBlank() }, "unnamed weight: $names")
    }

    private fun Any?.raftFontName(): String? =
        // CMP switch: compares the normalized bundled-face key (see raftTestBundledFontKey).
        (this as? FontListFontFamily)?.fonts?.singleOrNull()?.raftTestBundledFontKey()
}
