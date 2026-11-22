package build.raft.app.data

import coil3.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoilArtifactScreenshotDiskCacheTest {
    private val envA = "https://api-a.example"
    private val envB = "https://api-b.example"
    private val ownerA1 = ArtifactScreenshotDiskOwner(envA, "user-1")
    private val ownerA2 = ArtifactScreenshotDiskOwner(envA, "user-2")
    private val ownerB1 = ArtifactScreenshotDiskOwner(envB, "user-1")

    @Test
    fun isolatesOwnersFiltersClearServerDrainsPendingEvictionsFencesWritesAndCleansIndexes() = runBlocking {
        val dir = Files.createTempDirectory("raft-artifact-shot-cache-test").toFile()
        val coilCache =
            DiskCache.Builder()
                .directory(dir.toOkioPath())
                .maxSizeBytes(4L * 1024 * 1024)
                .build()
        try {
            val prefs = InMemoryRaftKeyValueStore()
            var activeCoil: DiskCache? = coilCache
            val disk =
                CoilArtifactScreenshotDiskCache(
                    preferences = prefs,
                    diskCacheProvider = { activeCoil },
                    maxEntriesPerServer = 2,
                )

            val k1 = ArtifactScreenshotKey("server-1", "card-1", "file-1")
            val k2 = ArtifactScreenshotKey("server-1", "card-2", "file-2")
            val k3 = ArtifactScreenshotKey("server-1", "card-3", "file-3")
            val kOtherServer = ArtifactScreenshotKey("server-2", "card-1", "file-1")

            // Environment / account / server isolation.
            disk.write(ownerA1, k1, byteArrayOf(1, 1))
            disk.write(ownerA2, k1, byteArrayOf(2, 2))
            disk.write(ownerB1, k1, byteArrayOf(3, 3))
            disk.write(ownerA1, kOtherServer, byteArrayOf(4, 4))

            assertContentEquals(byteArrayOf(1, 1), disk.read(ownerA1, k1))
            assertContentEquals(byteArrayOf(2, 2), disk.read(ownerA2, k1))
            assertContentEquals(byteArrayOf(3, 3), disk.read(ownerB1, k1))
            assertContentEquals(byteArrayOf(4, 4), disk.read(ownerA1, kOtherServer))

            // Per-(env, account) LRU cap evicts only ownerA1's oldest entry, leaving ownerA2/ownerB1 intact.
            disk.write(ownerA1, k2, byteArrayOf(1, 2))
            disk.write(ownerA1, k3, byteArrayOf(1, 3))
            assertNull(disk.read(ownerA1, k1), "oldest entry for ownerA1 evicted past maxEntriesPerServer=2")
            assertContentEquals(byteArrayOf(1, 2), disk.read(ownerA1, k2))
            assertContentEquals(byteArrayOf(1, 3), disk.read(ownerA1, k3))
            assertContentEquals(byteArrayOf(2, 2), disk.read(ownerA2, k1), "other account not evicted")
            assertContentEquals(byteArrayOf(3, 3), disk.read(ownerB1, k1), "other env not evicted")

            // clearServer with account + environment filter drops only ownerA1 on server-1.
            disk.clearServer(serverId = "server-1", accountId = "user-1", environmentId = envA)
            assertNull(disk.read(ownerA1, k2))
            assertNull(disk.read(ownerA1, k3))
            assertContentEquals(byteArrayOf(2, 2), disk.read(ownerA2, k1))
            assertContentEquals(byteArrayOf(3, 3), disk.read(ownerB1, k1))
            assertContentEquals(byteArrayOf(4, 4), disk.read(ownerA1, kOtherServer))

            // Write-after-evict fence: token captured before clearServer cannot land on disk afterwards.
            val staleToken = disk.captureWriteToken()
            disk.clearServer(serverId = "server-1", accountId = "user-1", environmentId = envA)
            disk.write(ownerA1, k1, byteArrayOf(9, 9), writeToken = staleToken)
            assertNull(disk.read(ownerA1, k1), "fenced write must not resurrect after clearServer")

            // Fenced remove: stale Gone removal captured before clearServer cannot delete a newer session's write.
            disk.write(ownerA1, k1, byteArrayOf(5, 5))
            val staleRemoveToken = disk.captureWriteToken()
            disk.clearServer(serverId = "server-1", accountId = "user-1", environmentId = envA)
            disk.write(ownerA1, k1, byteArrayOf(6, 6))
            disk.remove(ownerA1, k1, writeToken = staleRemoveToken)
            assertContentEquals(byteArrayOf(6, 6), disk.read(ownerA1, k1), "stale fenced remove must not delete newer write")

            // Null diskCacheProvider during clearServer queues pending eviction and removes bytes once Coil initializes.
            disk.write(ownerA1, k1, byteArrayOf(5, 5))
            assertContentEquals(byteArrayOf(5, 5), disk.read(ownerA1, k1))
            activeCoil = null
            disk.clearServer(serverId = "server-1", accountId = "user-1", environmentId = envA)
            assertTrue(prefs.getString(CoilArtifactScreenshotDiskCache.PendingEvictKey) != null)
            activeCoil = coilCache
            assertNull(disk.read(ownerA1, k1), "pending eviction must drain and delete stranded Coil entry on init")
            assertNull(prefs.getString(CoilArtifactScreenshotDiskCache.PendingEvictKey))

            // clearAll removes BOTH tracked server index keys AND underlying Coil disk entries.
            disk.clearAll()
            assertNull(prefs.getString(CoilArtifactScreenshotDiskCache.TrackedServersKey))
            assertNull(prefs.getString(CoilArtifactScreenshotDiskCache.ServerIndexKeyPrefix + "server-1"))
            assertNull(prefs.getString(CoilArtifactScreenshotDiskCache.ServerIndexKeyPrefix + "server-2"))
            assertNull(disk.read(ownerA1, kOtherServer), "clearAll must physically remove tracked Coil disk entries")
            assertNull(disk.read(ownerA2, k1), "clearAll must physically remove all accounts' tracked entries")

            // clearAllAsync drains before a subsequent session write without re-fencing or wiping the new write.
            disk.write(ownerA1, k1, byteArrayOf(7, 7))
            val delayedScope = CoroutineScope(SupervisorJob())
            disk.clearAllAsync(delayedScope)
            // Immediate new-session write runs right after clearAllAsync: must drain old clear first and keep new bytes.
            val newSessionToken = disk.captureWriteToken()
            disk.write(ownerA2, k2, byteArrayOf(8, 8), writeToken = newSessionToken)
            assertNull(disk.read(ownerA1, k1), "prior session entry wiped by queued clearAllAsync")
            assertContentEquals(byteArrayOf(8, 8), disk.read(ownerA2, k2), "new session write survives clearAllAsync")
            delayedScope.cancel()
        } finally {
            coilCache.shutdown()
            dir.deleteRecursively()
        }
    }
}
