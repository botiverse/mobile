package build.raft.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The Raft Design System fixes the faces: Space Grotesk for text, Space Mono for mono. These
 * assert the RESOLVED families the renderer receives.
 */
class FontFaceResolutionTest {
    @Test
    fun bodyAndMonoResolveBundledFaces() {
        assertNotEquals(FontFamily.Default, raftFontFamilyForWeight(null))
        assertNotEquals(FontFamily.Default, raftMonoFamilyForWeight(null))
        assertNotEquals(raftFontFamilyForWeight(null), raftMonoFamilyForWeight(null))
    }

    @Test
    fun monoCollapsesToTheTwoShippedWeights() {
        // Space Mono ships Regular and Bold only; a missing mono weight would fall back to sans.
        assertEquals(raftMonoFamilyForWeight(FontWeight.Normal), raftMonoFamilyForWeight(FontWeight.Medium))
        assertEquals(raftMonoFamilyForWeight(FontWeight.Normal), raftMonoFamilyForWeight(FontWeight.Light))
        assertEquals(raftMonoFamilyForWeight(FontWeight.Bold), raftMonoFamilyForWeight(FontWeight.Black))
        assertNotEquals(raftMonoFamilyForWeight(FontWeight.Normal), raftMonoFamilyForWeight(FontWeight.Bold))
        assertEquals(RaftMonoFamily, raftMonoFamilyForWeight(null))
    }

    @Test
    fun theTypeTokensUseTheDesignSystemFace() {
        assertEquals(raftFontFamilyForWeight(FontWeight.Normal), RaftType.Body.fontFamily)
        assertEquals(raftFontFamilyForWeight(FontWeight.Bold), RaftType.ButtonBold.fontFamily)
    }
}
