package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task #179: the font face picker is a local preference. These tests assert the behaviour a user
 * can observe — what is the default, does a choice survive a restart, and does every option map to
 * a family set with the pairing web uses — not how the code happens to be written.
 */
class FontPreferenceTest {
    @Test
    fun defaultMatchesWebsDefaultTheme() {
        // Web's default theme is `brutal`, whose sans is Hanken Grotesk, so a user with no stored
        // choice must land on the same face as web rather than on the face mobile shipped before.
        assertEquals(FontPreference.HankenGrotesk, FontPreference.Default)
        assertEquals(FontPreference.HankenGrotesk, FontPreference.fromStorageKey(null))
        assertEquals(FontPreference.HankenGrotesk, FontPreference.fromStorageKey(""))
        assertEquals(FontPreference.HankenGrotesk, FontPreference.fromStorageKey("not-a-font"))
    }

    @Test
    fun everyOptionRoundTripsThroughItsStorageKey() {
        val repository =
            PersistentFontPreferenceRepository(
                KeyValueFontPreferenceStore(InMemorySlockKeyValueStore())
            )

        FontPreference.entries.forEach { option ->
            val selected = runBlocking { repository.selectFontPreference(option) }
            val restored = runBlocking { repository.getFontPreference() }

            assertEquals(option, selected.current, "selecting $option reports it back")
            assertEquals(option, restored.current, "$option survives a reload")
            assertEquals(option, FontPreference.fromStorageKey(option.storageKey))
        }
    }

    @Test
    fun allFourOptionsAreOffered() {
        assertEquals(
            listOf(
                FontPreference.HankenGrotesk,
                FontPreference.GeistInter,
                FontPreference.SpaceGrotesk,
                FontPreference.System
            ),
            FontPreference.entries.toList()
        )
    }

    @Test
    fun choicesArePairedTheWayWebPairsThem() {
        // "Geist + Inter" is web's elegant pair: Geist for body, Inter for headings.
        assertEquals("geist", FontPreference.GeistInter.sansFamily)
        assertEquals("inter", FontPreference.GeistInter.headingFamily)

        // "Space Grotesk + Space Mono" travels together as the face mobile used before this option.
        assertEquals("space_grotesk", FontPreference.SpaceGrotesk.sansFamily)
        assertEquals("space_grotesk", FontPreference.SpaceGrotesk.headingFamily)
        assertEquals("space_mono", FontPreference.SpaceGrotesk.monoFamily)

        // Hanken owns both roles, so no separate heading face.
        assertEquals("hanken_grotesk", FontPreference.HankenGrotesk.sansFamily)
        assertEquals("hanken_grotesk", FontPreference.HankenGrotesk.headingFamily)
    }

    @Test
    fun monoFollowsTheOptionAndWebUsesGeistMonoForBothWebAlignedFaces() {
        assertEquals("geist_mono", FontPreference.HankenGrotesk.monoFamily)
        assertEquals("geist_mono", FontPreference.GeistInter.monoFamily)
        assertEquals("space_mono", FontPreference.SpaceGrotesk.monoFamily)
        // "System" means the host's own face all the way through, mono included.
        assertNull(FontPreference.System.monoFamily)
    }

    @Test
    fun systemOptionNamesNoBundledFamilySoTheHostFaceIsUsed() {
        // A non-null family here would render a bundled face instead of the platform's.
        assertNull(FontPreference.System.sansFamily)
        assertNull(FontPreference.System.headingFamily)
    }

    @Test
    fun aStoredChoiceSurvivesAndOverridesTheDefault() {
        val store = InMemorySlockKeyValueStore()
        val first =
            PersistentFontPreferenceRepository(KeyValueFontPreferenceStore(store))

        // Nothing stored yet -> the web-aligned default.
        assertEquals(FontPreference.HankenGrotesk, runBlocking { first.getFontPreference() }.current)

        // Choose a non-default option, then read it back through a FRESH repository over the same
        // storage. That is what a cold start does, so this is the restart contract, not internals.
        runBlocking { first.selectFontPreference(FontPreference.SpaceGrotesk) }
        val afterRestart =
            PersistentFontPreferenceRepository(KeyValueFontPreferenceStore(store))

        assertEquals(FontPreference.SpaceGrotesk, runBlocking { afterRestart.getFontPreference() }.current)
    }
}
