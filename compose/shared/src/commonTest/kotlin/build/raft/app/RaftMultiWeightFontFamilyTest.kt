package build.raft.app

import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * raftMultiWeightFontFamily: one family per role with every bundled weight, the way the
 * designsystem type tokens pick a weight with `copy(fontWeight = …)`.
 */
class RaftMultiWeightFontFamilyTest {
    private val sansWeights = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    @Test
    fun spaceGroteskCarriesEveryShippedWeightAndSpaceMonoOnlyRegularAndBold() {
        val sans = assertIs<FontListFontFamily>(raftMultiWeightFontFamily("sans"))
        assertEquals(sansWeights, sans.fonts.map { it.weight })
        assertEquals(
            listOf("light", "regular", "medium", "semibold", "bold").map { "space_grotesk_$it" },
            sans.fonts.map { it.raftTestBundledFontKey() }
        )

        val mono = assertIs<FontListFontFamily>(raftMultiWeightFontFamily("mono"))
        assertEquals(listOf(FontWeight.Normal, FontWeight.Bold), mono.fonts.map { it.weight })
        assertEquals(listOf("space_mono_regular", "space_mono_bold"), mono.fonts.map { it.raftTestBundledFontKey() })
    }
}
