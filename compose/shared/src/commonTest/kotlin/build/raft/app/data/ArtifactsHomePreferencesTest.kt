package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** web `artifactDashboardPrefs.ts` `readArtifactDashboardPrefs`: the web's key and shape; anything unreadable is the default. */
class ArtifactsHomePreferencesTest {
    @Test
    fun readsWhatTheWebWritesAndDefaultsTheRest() {
        assertEquals(ArtifactsHomePrefs(ArtifactsHomeView.List, ArtifactsHomeSort.Updated), parseArtifactsHomePrefs("""{"view":"list","widths":{"author":200},"listSort":"updated"}"""))
        assertEquals(ArtifactsHomePrefs(), parseArtifactsHomePrefs(null))
        assertEquals(ArtifactsHomePrefs(), parseArtifactsHomePrefs("not json"))
        assertEquals(ArtifactsHomePrefs(), parseArtifactsHomePrefs("""{"view":"cards","listSort":"title","order":"updated"}"""))
    }

    @Test
    fun writesAndReadsBackUnderTheWebKey() {
        val store = object : SharedPreferenceStore {
            val values = mutableMapOf<String, String?>()
            override fun getString(key: String): String? = values[key]
            override fun putString(key: String, value: String?) { values[key] = value }
        }
        val repository = ArtifactsHomePreferenceRepository(store)
        repository.write(ArtifactsHomePrefs(ArtifactsHomeView.List, ArtifactsHomeSort.Opened))
        assertEquals(setOf("raft:artifacts:dashboard:v1"), store.values.keys)
        assertEquals(ArtifactsHomePrefs(ArtifactsHomeView.List, ArtifactsHomeSort.Opened), repository.read())
    }
}
