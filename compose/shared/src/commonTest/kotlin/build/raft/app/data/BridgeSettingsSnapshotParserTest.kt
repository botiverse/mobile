package build.raft.app.data

import build.raft.app.toRaftJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BridgeSettingsSnapshotParserTest {
    @Test
    fun storageParsesPrimitiveRowsJsonFallback() {
        val response =
            buildJsonObject {
                put("status", "ok")
                put("totalSizeLabel", "1.2 GB")
                put("rowsJson", storageRowsJson(target = "imageCache").toRaftJsonString())
            }

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
            buildJsonObject {
                put("status", "ok")
                put("rows", storageRowsJson(target = "structured"))
                put("rowsJson", storageRowsJson(target = "fallback").toRaftJsonString())
            }

        val snapshot = response.toStorageSnapshot()

        assertEquals(listOf("structured"), snapshot.rows.map { it.target })
    }

    @Test
    fun storageMalformedRowsJsonProducesEmptyRows() {
        val response = buildJsonObject {
            put("status", "ok")
            put("rowsJson", "not-json")
        }

        assertEquals(emptyList(), response.toStorageSnapshot().rows)
    }

    @Test
    fun developerParsesPrimitiveOptionsJsonFallback() {
        val response =
            buildJsonObject {
                put("status", "ok")
                put("environmentKey", "staging")
                put("optionsJson", environmentOptionsJson(key = "staging").toRaftJsonString())
            }

        val snapshot = response.toDeveloperSnapshot()

        assertEquals("staging", snapshot.environmentKey)
        assertEquals(listOf("staging"), snapshot.options.map { it.key })
        assertEquals("Staging", snapshot.options.single().label)
        assertEquals("https://api.staging.example/api/", snapshot.options.single().apiBaseUrl)
    }

    @Test
    fun developerStructuredOptionsTakePrecedenceForBackwardCompatibility() {
        val response =
            buildJsonObject {
                put("status", "ok")
                put("options", environmentOptionsJson(key = "structured"))
                put("optionsJson", environmentOptionsJson(key = "fallback").toRaftJsonString())
            }

        val snapshot = response.toDeveloperSnapshot()

        assertEquals(listOf("structured"), snapshot.options.map { it.key })
    }

    @Test
    fun developerMalformedOptionsJsonFallsBackToDefaultOptions() {
        val response = buildJsonObject {
            put("status", "ok")
            put("optionsJson", "not-json")
        }

        val snapshot = response.toDeveloperSnapshot()

        assertEquals(sharedSettingsDefaultApiEnvironmentOptions(), snapshot.options)
    }

    @Test
    fun developerCarriesScopedHandsChangelogIntoUpdateSnapshot() {
        val markdown = "# D / 1000004\n\n- Dedicated Alpha notes"
        val response =
            buildJsonObject {
                put("status", "ok")
                put("updateAvailable", true)
                put("latestVersionCode", 1_000_004L)
                put("latestChangelog", markdown)
            }

        val update = response.toDeveloperSnapshot().updateCheck

        assertEquals(1_000_004L, update.latestVersionCode)
        assertEquals(markdown, update.latestChangelog)
    }

    @Test
    fun developerParsesHandsTransactionStateWithoutTrustingDisplayLabels() {
        val response =
            buildJsonObject {
                put("status", "ok")
                put("updateTransactionState", "ready_to_install")
                put("updateTransactionTargetVersionCode", 1_000_011L)
                put("updateTransactionRetryable", true)
                put("updateCheckStatusLabel", "已准备好")
            }

        val update = response.toDeveloperSnapshot().updateCheck

        assertEquals(SharedSettingsUpdateTransactionState.ReadyToInstall, update.transactionState)
        assertEquals(1_000_011L, update.transactionTargetVersionCode)
        assertTrue(update.transactionRetryable)
        assertEquals("已准备好", update.statusLabel)
    }

    @Test
    fun developerParsesResolvedPatchAndFullDownloadSizes() {
        val update =
            buildJsonObject {
                put("status", "ok")
                put("updateDownloadSizesResolved", true)
                put("updatePatchDownloadSizeBytes", 95_256L)
                put("updateFullDownloadSizeBytes", 94_411_540L)
            }
                .toDeveloperSnapshot()
                .updateCheck

        assertTrue(update.downloadSizesResolved)
        assertEquals(95_256L, update.patchDownloadSizeBytes)
        assertEquals(94_411_540L, update.fullDownloadSizeBytes)
    }

    @Test
    fun developerClampsInvalidDownloadSizesToUnknown() {
        val update =
            buildJsonObject {
                put("status", "ok")
                put("updateDownloadSizesResolved", true)
                put("updatePatchDownloadSizeBytes", -1L)
                put("updateFullDownloadSizeBytes", -2L)
            }
                .toDeveloperSnapshot()
                .updateCheck

        assertEquals(0L, update.patchDownloadSizeBytes)
        assertEquals(0L, update.fullDownloadSizeBytes)
    }

    @Test
    fun developerFailsUnknownHandsTransactionStateClosedToLegacyMapping() {
        val update =
            buildJsonObject {
                put("status", "ok")
                put("updateTransactionState", "future_state")
            }
                .toDeveloperSnapshot()
                .updateCheck

        assertEquals(SharedSettingsUpdateTransactionState.Unknown, update.transactionState)
    }

    private fun storageRowsJson(target: String): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("target", target)
                    put("title", "Image cache")
                    put("detail", "Downloaded image previews kept for faster viewing.")
                    put("sizeLabel", "120 MB")
                    put("clearEnabled", true)
                }
            )
        }

    private fun environmentOptionsJson(key: String): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("key", key)
                    put("label", "Staging")
                    put("apiBaseUrl", "https://api.staging.example/api/")
                    put("socketBaseUrl", "wss://socket.staging.example")
                    put("webUrl", "https://staging.example")
                }
            )
        }
}
