package build.raft.app

import build.raft.app.data.FontPreference
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * raftMultiWeightFontFamily: one family per face with every bundled weight, the way the
 * designsystem type tokens pick a weight with `copy(fontWeight = …)`.
 */
class RaftMultiWeightFontFamilyTest {
    private val sansWeights = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    @Test
    fun bundledFacesCarryEveryShippedWeightAndMonoOnlyRegularAndBold() {
        data class Case(val preference: FontPreference, val sansKey: String, val monoKey: String)
        listOf(
            Case(FontPreference.HankenGrotesk, "hanken_grotesk", "geist_mono"),
            Case(FontPreference.GeistInter, "geist", "geist_mono"),
            Case(FontPreference.SpaceGrotesk, "space_grotesk", "space_mono"),
        ).forEach { case ->
            val sans = assertIs<FontListFontFamily>(raftMultiWeightFontFamily("sans", case.preference), "${case.preference} sans")
            assertEquals(sansWeights, sans.fonts.map { it.weight }, "${case.preference} sans weights")
            assertEquals(
                listOf("light", "regular", "medium", "semibold", "bold").map { "${case.sansKey}_$it" },
                sans.fonts.map { it.raftTestBundledFontKey() },
                "${case.preference} sans faces"
            )

            // Space Mono / Geist Mono ship only 400 and 700.
            val mono = assertIs<FontListFontFamily>(raftMultiWeightFontFamily("mono", case.preference), "${case.preference} mono")
            assertEquals(listOf(FontWeight.Normal, FontWeight.Bold), mono.fonts.map { it.weight }, "${case.preference} mono weights")
            assertEquals(
                listOf("${case.monoKey}_regular", "${case.monoKey}_bold"),
                mono.fonts.map { it.raftTestBundledFontKey() },
                "${case.preference} mono faces"
            )
        }
    }

    @Test
    fun systemFaceFallsBackToThePlatformFamilies() {
        assertSame(FontFamily.Default, raftMultiWeightFontFamily("sans", FontPreference.System))
        assertSame(FontFamily.Monospace, raftMultiWeightFontFamily("mono", FontPreference.System))
    }
}
