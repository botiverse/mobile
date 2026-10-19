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
    fun defaultIsTheFaceMobileHasAlwaysShipped() {
        // artin 2026-10-18: a user with no stored choice stays on Space Grotesk; the web faces are
        // opt-in from Appearance.
        assertEquals(FontPreference.SpaceGrotesk, FontPreference.Default)
        assertEquals(FontPreference.SpaceGrotesk, FontPreference.fromStorageKey(null))
        assertEquals(FontPreference.SpaceGrotesk, FontPreference.fromStorageKey(""))
        assertEquals(FontPreference.SpaceGrotesk, FontPreference.fromStorageKey("not-a-font"))
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

        // Nothing stored yet -> the default.
        assertEquals(FontPreference.SpaceGrotesk, runBlocking { first.getFontPreference() }.current)

        // Choose a non-default option, then read it back through a FRESH repository over the same
        // storage. That is what a cold start does, so this is the restart contract, not internals.
        runBlocking { first.selectFontPreference(FontPreference.HankenGrotesk) }
        val afterRestart =
            PersistentFontPreferenceRepository(KeyValueFontPreferenceStore(store))

        assertEquals(FontPreference.HankenGrotesk, runBlocking { afterRestart.getFontPreference() }.current)
    }

    /**
     * Task #521: the restart contract above only covers the repository. What the user sees is
     * [build.raft.app.SlockFontFaceState], which is process-scoped and starts at the default — so a
     * cold start must publish the stored value to it BEFORE the first composition, otherwise the UI
     * renders the default face (observed on Android and OHOS: MMKV held geist_inter, UI reverted).
     */
    @Test
    fun coldStartPublishesTheStoredFaceToTheObservableState() {
        val store = InMemorySlockKeyValueStore()
        // A previous session stored a non-default face.
        runBlocking { KeyValueFontPreferenceStore(store).saveFontPreference(FontPreference.HankenGrotesk) }

        // Cold start: the observable state begins at the default.
        build.raft.app.SlockFontFaceState.apply(FontPreference.Default)
        assertEquals(FontPreference.Default, build.raft.app.SlockFontFaceState.preference)

        // The startup restore must publish what is stored, without waiting for a settings snapshot.
        assertEquals(
            FontPreference.HankenGrotesk,
            KeyValueFontPreferenceStore(store).readFontPreferenceSync(),
            "the store holds the user's choice"
        )
        build.raft.app.SlockFontFaceState.apply(KeyValueFontPreferenceStore(store).readFontPreferenceSync())
        assertEquals(FontPreference.HankenGrotesk, build.raft.app.SlockFontFaceState.preference)
    }

    @Test
    fun restoringWhenNothingIsStoredKeepsTheDefault() {
        // No stored value must not push an arbitrary face: fromStorageKey(null) yields the default.
        val store = InMemorySlockKeyValueStore()
        assertEquals(
            FontPreference.SpaceGrotesk,
            KeyValueFontPreferenceStore(store).readFontPreferenceSync()
        )
    }
}
