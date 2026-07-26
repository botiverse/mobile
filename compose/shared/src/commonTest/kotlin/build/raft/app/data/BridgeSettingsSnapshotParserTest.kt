package build.raft.app.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeSettingsSnapshotParserTest {
    @Test
    fun storageParsesPrimitiveRowsJsonFallback() {
        val response =
            JSONObject()
                .put("status", "ok")
                .put("totalSizeLabel", "1.2 GB")
                .put("rowsJson", storageRowsJson(target = "imageCache").toString())

        val snapshot = response.toStorageSnapshot()

        assertEquals("1.2 GB", snapshot.totalSizeLabel)
        assertEquals(1, snapshot.rows.size)
        assertEquals("imageCache", snapshot.rows.single().target)
        assertEquals("Image cache", snapshot.rows.single().title)
        assertEquals("120 MB", snapshot.rows.single().sizeLabel)
        assertTrue(snapshot.rows.single().clearEnabled)
    }

    @Test
    fun storageStructuredRowsTakePrecedenceForBackwardCompatibility() {
        val response =
            JSONObject()
                .put("status", "ok")
                .put("rows", storageRowsJson(target = "structured"))
                .put("rowsJson", storageRowsJson(target = "fallback").toString())

        val snapshot = response.toStorageSnapshot()

        assertEquals(listOf("structured"), snapshot.rows.map { it.target })
    }

    @Test
    fun storageMalformedRowsJsonProducesEmptyRows() {
        val response = JSONObject().put("status", "ok").put("rowsJson", "not-json")

        assertEquals(emptyList(), response.toStorageSnapshot().rows)
    }

    @Test
    fun developerParsesPrimitiveOptionsJsonFallback() {
        val response =
            JSONObject()
                .put("status", "ok")
                .put("environmentKey", "staging")
                .put("optionsJson", environmentOptionsJson(key = "staging").toString())

        val snapshot = response.toDeveloperSnapshot()

        assertEquals("staging", snapshot.environmentKey)
        assertEquals(listOf("staging"), snapshot.options.map { it.key })
        assertEquals("Staging", snapshot.options.single().label)
        assertEquals("https://api.staging.example/api/", snapshot.options.single().apiBaseUrl)
    }

    @Test
    fun developerStructuredOptionsTakePrecedenceForBackwardCompatibility() {
        val response =
            JSONObject()
                .put("status", "ok")
                .put("options", environmentOptionsJson(key = "structured"))
                .put("optionsJson", environmentOptionsJson(key = "fallback").toString())

        val snapshot = response.toDeveloperSnapshot()

        assertEquals(listOf("structured"), snapshot.options.map { it.key })
    }

    @Test
    fun developerMalformedOptionsJsonFallsBackToDefaultOptions() {
        val response = JSONObject().put("status", "ok").put("optionsJson", "not-json")

        val snapshot = response.toDeveloperSnapshot()

        assertEquals(sharedSettingsDefaultApiEnvironmentOptions(), snapshot.options)
    }

    @Test
    fun developerCarriesScopedHandsChangelogIntoUpdateSnapshot() {
        val markdown = "# D / 1000004\n\n- Dedicated Alpha notes"
        val response =
            JSONObject()
                .put("status", "ok")
                .put("updateAvailable", true)
                .put("latestVersionCode", 1_000_004L)
                .put("latestChangelog", markdown)

        val update = response.toDeveloperSnapshot().updateCheck

        assertEquals(1_000_004L, update.latestVersionCode)
        assertEquals(markdown, update.latestChangelog)
    }

    private fun storageRowsJson(target: String): JSONArray =
        JSONArray().apply {
            put(
                JSONObject()
                    .put("target", target)
                    .put("title", "Image cache")
                    .put("detail", "Downloaded image previews kept for faster viewing.")
                    .put("sizeLabel", "120 MB")
                    .put("clearEnabled", true)
            )
        }

    private fun environmentOptionsJson(key: String): JSONArray =
        JSONArray().apply {
            put(
                JSONObject()
                    .put("key", key)
                    .put("label", "Staging")
                    .put("apiBaseUrl", "https://api.staging.example/api/")
                    .put("socketBaseUrl", "wss://socket.staging.example")
                    .put("webUrl", "https://staging.example")
            )
        }
}
