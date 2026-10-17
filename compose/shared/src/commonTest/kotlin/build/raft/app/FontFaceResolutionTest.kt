package build.raft.app

import build.raft.app.data.FontPreference
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task #179: these assert the RESOLVED face the renderer receives, not the option metadata.
 *
 * That distinction matters: the metadata can say "system" (no bundled family) while the resolver
 * still hands back a proportional face, and code blocks would render in it. Asserting only the
 * metadata is exactly how that class of bug slips through.
 */
class FontFaceResolutionTest {
    // The face state is process-wide, so a test that leaves it switched would leak into unrelated
    // tests that resolve SlockType/SlockMarkdown styles. Always restore the default.
    @AfterTest
    fun restoreDefaultFace() {
        SlockFontFaceState.apply(FontPreference.Default)
    }

    private fun select(preference: FontPreference) {
        SlockFontFaceState.apply(preference)
    }

    @Test
    fun systemOptionResolvesMonoToMonospaceNotProportional() {
        select(FontPreference.System)

        // The System option promises the host's own type "mono included". If the mono role fell
        // back to FontFamily.Default it would be the platform sans face and code/timestamps would
        // render proportionally.
        assertEquals(
            FontFamily.Monospace,
            slockMonoFamilyForWeight(null),
            "system mono must resolve to the platform monospace face"
        )
        assertTrue(
            slockMonoFamilyForWeight(null) != FontFamily.Default,
            "system mono must not be the default proportional face"
        )
    }

    @Test
    fun systemOptionResolvesBodyToTheDefaultHostFace() {
        select(FontPreference.System)

        // Body text has no bundled family either, and there the default face IS the host face.
        assertEquals(FontFamily.Default, slockFontFamilyForWeight(null))
    }

    @Test
    fun everyBundledOptionResolvesItsOwnFamilyForBodyAndMono() {
        FontPreference.entries.filter { it.sansFamily != null }.forEach { preference ->
            select(preference)
            assertTrue(
                slockFontFamilyForWeight(null) != FontFamily.Default,
                "$preference must resolve a bundled body family"
            )
            assertTrue(
                slockMonoFamilyForWeight(null) != FontFamily.Default,
                "$preference must resolve a bundled mono family"
            )
        }
    }

    @Test
    fun switchingTheFaceChangesWhatTheRendererResolves() {
        // Changing the picker has to reach the renderer, otherwise the setting saves but nothing
        // repaints. Compare the resolved family across a switch.
        select(FontPreference.HankenGrotesk)
        val hankenBody = slockFontFamilyForWeight(null)
        val hankenMono = slockMonoFamilyForWeight(null)

        select(FontPreference.SpaceGrotesk)
        val spaceBody = slockFontFamilyForWeight(null)
        val spaceMono = slockMonoFamilyForWeight(null)

        assertTrue(hankenBody != spaceBody, "body face must change when the option changes")
        assertTrue(hankenMono != spaceMono, "mono face must change when the option changes")
    }

    @Test
    fun aFaceWithNoBundledMonoStillNeverResolvesMonoToProportional() {
        // Guards the whole option list, not just System: any option whose mono family is absent
        // must still land on monospace rather than silently going proportional.
        FontPreference.entries.forEach { preference ->
            select(preference)
            listOf(null, FontWeight.Bold).forEach { weight ->
                val resolved = slockMonoFamilyForWeight(weight)
                assertTrue(
                    resolved != FontFamily.Default || preference.monoFamily != null,
                    "$preference mono at weight=$weight fell back to the proportional face"
                )
            }
        }
    }
}
