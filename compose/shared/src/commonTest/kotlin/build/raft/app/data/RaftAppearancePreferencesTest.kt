package build.raft.app.data

import build.raft.app.RaftAppAppearance
import build.raft.app.RaftAppThemeState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftAppearancePreferencesTest {
    private class MapPreferenceStore : SharedPreferenceStore {
        val values = mutableMapOf<String, String?>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            values[key] = value
        }
    }

    @AfterTest
    fun restore() {
        RaftAppAppearanceRuntime.installRepositoryForTests(null)
        RaftAppThemeState.apply(RaftAppAppearance.Light)
        RaftAppThemeState.onSystemDarkChanged(false)
    }

    @Test
    fun defaultsToSystemLikeTheWeb() {
        val repository = KeyValueRaftAppearancePreferenceRepository(MapPreferenceStore())
        assertEquals(RaftAppAppearance.System, repository.readAppearanceSync())
    }

    @Test
    fun storesTheWebModeValuesAndIgnoresUnknownOnes() {
        val backing = MapPreferenceStore()
        val repository = KeyValueRaftAppearancePreferenceRepository(backing)
        for ((appearance, stored) in listOf(
            RaftAppAppearance.Light to "light",
            RaftAppAppearance.Dark to "dark",
            RaftAppAppearance.System to "system",
        )) {
            repository.saveAppearanceSync(appearance)
            assertEquals(stored, backing.values[KEY_APPEARANCE_THEME_MODE])
            assertEquals(appearance, KeyValueRaftAppearancePreferenceRepository(backing).readAppearanceSync())
        }
        backing.values[KEY_APPEARANCE_THEME_MODE] = "elegant-dark"
        assertEquals(RaftAppAppearance.System, repository.readAppearanceSync())
    }

    @Test
    fun runtimeAppliesTheStoredChoiceAndSavesNewOnes() {
        val backing = MapPreferenceStore().apply { values[KEY_APPEARANCE_THEME_MODE] = "dark" }
        RaftAppAppearanceRuntime.installRepositoryForTests(KeyValueRaftAppearancePreferenceRepository(backing))
        RaftAppThemeState.onSystemDarkChanged(false)

        RaftAppAppearanceRuntime.ensureLoaded()
        assertEquals(RaftAppAppearance.Dark, RaftAppAppearanceRuntime.appearance)
        assertTrue(RaftAppAppearanceRuntime.isDark)
        assertEquals(2, RaftAppAppearanceRuntime.hostStyleCode)

        val hostCodes = mutableListOf<Int>()
        RaftAppAppearanceRuntime.setHostListener { hostCodes += it }
        RaftAppAppearanceRuntime.select(RaftAppAppearance.System)
        assertEquals("system", backing.values[KEY_APPEARANCE_THEME_MODE])
        assertFalse(RaftAppAppearanceRuntime.isDark, "System follows the (light) OS setting")
        assertEquals(listOf(2, 0), hostCodes)
        RaftAppAppearanceRuntime.setHostListener(null)
    }
}
