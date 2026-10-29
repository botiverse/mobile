package build.raft.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long

/** Settings > Storage on desktop: the host answers `loadStorage` instead of "not implemented". */
class RaftDesktopStorageTest {
    @Test fun `storage measures the database, the cache and the whole data directory`() {
        val data = Files.createTempDirectory("raft-storage").toFile()
        try {
            val database = File(data, "databases").apply { mkdirs() }
            val cache = File(data, "cache/raft_image_cache").apply { mkdirs() }
            File(database, "raft.db").writeBytes(ByteArray(300))
            File(cache, "a.bin").writeBytes(ByteArray(120))
            File(data, "kv").apply { mkdirs() }.resolve("prefs").writeBytes(ByteArray(5))

            val payload = RaftDesktopSettingsCapability.storagePayload(database, File(data, "cache"), data)

            assertEquals("ok", (payload["status"] as JsonPrimitive).content)
            assertEquals(425L, (payload["totalSizeBytes"] as JsonPrimitive).long)
            val rows = (payload["rows"] as JsonArray).map { it as JsonObject }
            assertEquals(listOf("database", "cache"), rows.map { (it["target"] as JsonPrimitive).content })
            assertEquals(listOf(300L, 120L), rows.map { (it["sizeBytes"] as JsonPrimitive).long })
            // Nothing offers Clear until desktop can clear safely.
            assertEquals(listOf(false, false), rows.map { (it["clearEnabled"] as JsonPrimitive).boolean })
        } finally {
            data.deleteRecursively()
        }
    }
}
