package build.raft.app.shared

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

internal abstract class SlockAndroidAvatarCacheTestSupport {
    protected fun newManager(
        store: SlockAndroidAvatarDiskStore,
        executor: SlockAvatarCacheWorkExecutor,
        scheduler: SlockAvatarCacheTimeoutScheduler = ManualScheduler(),
        clearDecodedMemory: () -> Unit = {},
        retireDecodedOwner: (String) -> Unit = {},
        retireDecodedSubject: (String, String) -> Unit = { _, _ -> },
        fetcher: (SlockAndroidAvatarRequest, Boolean) -> SlockAndroidAvatarNetworkResponse
    ): SlockAndroidAvatarCacheManager =
        SlockAndroidAvatarCacheManager(
            store = store,
            networkFetcher = SlockAndroidAvatarNetworkFetcher(fetcher),
            contentValidator = { bytes, _ -> bytes.firstOrNull() == 'B'.code.toByte() },
            workExecutor = executor,
            timeoutScheduler = scheduler,
            resultTimeoutMillis = 100L,
            rollbackGraceMillis = 1_000L,
            clearDecodedMemory = clearDecodedMemory,
            retireDecodedOwner = retireDecodedOwner,
            retireDecodedSubject = retireDecodedSubject
        )

    protected fun newStore(root: File): SlockAndroidAvatarDiskStore =
        SlockAndroidAvatarDiskStore(root = root)

    protected fun prepare(
        manager: SlockAndroidAvatarCacheManager,
        request: SlockAndroidAvatarRequest
    ) {
        assertNotNull(manager.inspect(request.entryKey, request.commitGeneration))
    }

    protected fun assertPreparedDescriptorRevokedAndLaterGenerationAdmitted(
        root: File,
        terminalMutation: TerminalMutation
    ) {
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        val store = newStore(root)
        var networkCalls = 0
        val manager =
            newManager(store, executor, scheduler) { _, _ ->
                networkCalls++
                SlockAndroidAvatarNetworkResponse(200, validBytes("fresh"), "image/test")
            }
        val old = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 8L)
        val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
        prepare(manager, old)
        assertEquals(1, manager.retainedPreparationCount())

        when (terminalMutation) {
            TerminalMutation.Clear -> assertTrue(manager.clearAll())
            TerminalMutation.RetireOwner ->
                assertEquals(SlockAndroidAvatarMutation.Accepted, manager.retireOwner(old.nativePartitionKey))
            TerminalMutation.RetireSubject ->
                assertEquals(
                    SlockAndroidAvatarMutation.Accepted,
                    manager.retireSubject(old.nativePartitionKey, old.nativeSubjectKey)
                )
        }
        assertEquals(0, manager.retainedPreparationCount())
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.rollback(old))

        val statuses = mutableListOf<String>()
        var renderCount = 0
        manager.awaitResult(old) { statuses += it.status }
        manager.load(old) { payload ->
            renderCount++
            payload?.let { memory.put(decodeKey(old), it.bytes.toText()) }
        }
        executor.runAll()
        scheduler.runAll()

        assertEquals(listOf("temporary_transport"), statuses)
        assertEquals(1, renderCount)
        assertEquals(0, networkCalls)
        assertEquals(0, memory.size())
        assertEquals(0L, store.managedBytes())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".stage") || it.name.endsWith(".bin") })
        assertNull(manager.inspect(old.entryKey, old.commitGeneration))

        val fresh = old.copy(commitGeneration = old.commitGeneration + 1L)
        prepare(manager, fresh)
        var freshStatus: String? = null
        manager.awaitResult(fresh) { freshStatus = it.status }
        assertEquals(1, executor.size)
        executor.runAll()
        assertEquals("success", freshStatus)
        assertEquals(1, networkCalls)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(fresh, "successful_bytes"))
        assertEquals("Bfresh", store.read(fresh.entryKey)?.bytes?.toText())
    }

    protected fun assertPausedDeliveryClosedAndLaterGenerationAdmitted(
        root: File,
        terminalMutation: TerminalMutation,
        finalizeBeforeTerminal: Boolean
    ) {
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
        val store =
            SlockAndroidAvatarDiskStore(
                root = root,
                onEntriesRemoved = { keys -> keys.forEach(memory::removeEntry) }
            )
        val request =
            request(
                mode =
                    if (finalizeBeforeTerminal) {
                        SlockAndroidAvatarReadMode.Fetch
                    } else {
                        SlockAndroidAvatarReadMode.RenderFresh
                    },
                generation = 2L
            )
        if (!finalizeBeforeTerminal) prime(store, request.entryKey, 1L, validBytes("disk"))
        val manager =
            newManager(
                store = store,
                executor = executor,
                scheduler = scheduler,
                clearDecodedMemory = memory::clear,
                retireDecodedOwner = memory::removeOwner,
                retireDecodedSubject = memory::removeSubject
            ) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("network"), "image/test")
            }
        prepare(manager, request)
        var nonNullDeliveryCount = 0
        var nullDeliveryCount = 0
        manager.load(request) { payload ->
            if (payload == null) {
                nullDeliveryCount++
            } else {
                nonNullDeliveryCount++
                memory.put(decodeKey(request), payload.bytes.toText())
            }
        }

        if (finalizeBeforeTerminal) {
            var result: SlockAndroidAvatarLoadResult? = null
            manager.awaitResult(request) { result = it }
            executor.runAll()
            assertEquals("success", result?.status)
            assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "successful_bytes"))
        } else {
            assertEquals(1, executor.size)
            executor.runNext()
        }
        assertEquals(1, executor.size)
        assertEquals(0, nonNullDeliveryCount)
        assertEquals(0, nullDeliveryCount)

        assertTrue(applyTerminalMutation(manager, request, terminalMutation))
        assertEquals(0, nonNullDeliveryCount)
        assertEquals(1, nullDeliveryCount)
        assertEquals(0, memory.size())
        assertNull(store.entry(request.entryKey))

        executor.runAll()
        scheduler.runAll()
        assertEquals(0, nonNullDeliveryCount)
        assertEquals(1, nullDeliveryCount)
        assertEquals(0, memory.size())

        val fresh =
            request.copy(
                readMode = SlockAndroidAvatarReadMode.Fetch,
                commitGeneration = request.commitGeneration + 1L
            )
        prepare(manager, fresh)
        var freshStatus: String? = null
        var freshDeliveryCount = 0
        manager.load(fresh) { payload ->
            if (payload != null) {
                freshDeliveryCount++
                memory.put(decodeKey(fresh), payload.bytes.toText())
            }
        }
        manager.awaitResult(fresh) { freshStatus = it.status }
        executor.runAll()
        assertEquals("success", freshStatus)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(fresh, "successful_bytes"))
        executor.runAll()
        assertEquals(1, freshDeliveryCount)
        assertEquals(1, memory.size())
    }

    protected fun assertDeleteFailurePreservesManagerRetirementAuthority(
        root: File,
        retire: (SlockAndroidAvatarCacheManager, SlockAndroidAvatarEntryKey) -> SlockAndroidAvatarMutation
    ) {
        assertDeleteFailurePreservesCleanupAuthority(
            root = root,
            remove = { store, key ->
                val manager = newManager(store, DirectExecutor()) { _, _ ->
                    SlockAndroidAvatarNetworkResponse(statusCode = 0)
                }
                retire(manager, key) == SlockAndroidAvatarMutation.Accepted
            }
        )
    }

    protected fun assertDeleteFailurePreservesCleanupAuthority(
        root: File,
        remove: (SlockAndroidAvatarDiskStore, SlockAndroidAvatarEntryKey) -> Boolean
    ) {
        var rejectFirstDelete = true
        val store =
            SlockAndroidAvatarDiskStore(
                root = root,
                deleteManagedFile = { file ->
                    if (rejectFirstDelete) {
                        rejectFirstDelete = false
                        false
                    } else {
                        !file.exists() || file.delete()
                    }
                }
            )
        val key = entryKey('a', 'b', 'c')
        val bytes = validBytes("retry")
        prime(store, key, generation = 1L, bytes = bytes)
        val file = root.resolve("${key.bytesKey}.bin")

        assertFalse(remove(store, key))
        assertTrue(file.isFile)
        assertNotNull(store.entry(key))
        assertEquals(bytes.size.toLong(), store.managedBytes())

        val reloaded = newStore(root)
        assertNotNull(reloaded.entry(key))
        assertTrue(remove(reloaded, key))
        assertTrue(reloaded.indexedEntries().isEmpty())
        assertEquals(0L, reloaded.managedBytes())
        assertFalse(file.exists())
    }

    protected fun assertTransientDeleteFailurePreservesTerminalAuthority(
        root: File,
        terminalMutation: TerminalMutation
    ) {
        TransientFileKind.entries.forEach { transientKind ->
            val caseRoot = root.resolve(transientKind.name.lowercase())
            var rejectFirstTransientDelete = true
            var allowRootDelete = false
            val store =
                SlockAndroidAvatarDiskStore(
                    root = caseRoot,
                    deleteManagedFile = { file ->
                        if (
                            rejectFirstTransientDelete &&
                            file.exists() &&
                            file.name.endsWith(transientKind.suffix)
                        ) {
                            rejectFirstTransientDelete = false
                            false
                        } else {
                            !file.exists() || file.delete()
                        }
                    },
                    deleteManagedRoot = { directory ->
                        !directory.exists() || (allowRootDelete && directory.deleteRecursively())
                    }
                )
            val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 2L)
            if (transientKind == TransientFileKind.Rollback) {
                prime(store, request.entryKey, 1L, validBytes("old"))
            }
            val manager =
                newManager(store, DirectExecutor()) { _, _ ->
                    SlockAndroidAvatarNetworkResponse(200, validBytes("candidate"), "image/test")
                }
            prepare(manager, request)
            var renderCount = 0
            manager.load(request) { renderCount++ }
            if (transientKind == TransientFileKind.Rollback) {
                assertEquals(
                    SlockAndroidAvatarMutation.Accepted,
                    manager.finalize(request, "successful_bytes")
                )
                assertEquals(1, renderCount)
            } else {
                assertEquals(0, renderCount)
            }

            assertFalse(applyTerminalMutation(manager, request, terminalMutation))
            assertEquals(1, renderCount)
            assertEquals(1, manager.retainedOperationCount())
            assertTrue(caseRoot.listFiles().orEmpty().any { it.name.endsWith(transientKind.suffix) })

            allowRootDelete = true
            assertTrue(applyTerminalMutation(manager, request, terminalMutation))
            assertEquals(1, renderCount)
            assertEquals(0, manager.retainedOperationCount())
            assertFalse(
                caseRoot.listFiles().orEmpty().any {
                    it.name.endsWith(".stage") || it.name.endsWith(".rollback") || it.name.endsWith(".bin")
                }
            )
        }
    }

    protected fun assertRestartTransientDeleteFailurePreservesTerminalAuthority(
        root: File,
        terminalMutation: TerminalMutation,
        transientKind: TransientFileKind
    ) {
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 2L)
        val initialStore = newStore(root)
        when (transientKind) {
            TransientFileKind.Stage ->
                assertNotNull(initialStore.stage(request.entryKey, request.commitGeneration, validBytes("staged")))
            TransientFileKind.Rollback -> {
                prime(initialStore, request.entryKey, 1L, validBytes("old"))
                val stage =
                    requireNotNull(
                        initialStore.stage(
                            request.entryKey,
                            request.commitGeneration,
                            validBytes("candidate")
                        )
                    )
                assertNotNull(initialStore.promote(stage, "image/test"))
            }
        }
        assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(transientKind.suffix) })

        var rejectTransientDeletes = true
        val restartedStore =
            SlockAndroidAvatarDiskStore(
                root = root,
                deleteManagedFile = { file ->
                    if (
                        rejectTransientDeletes &&
                        file.exists() &&
                        file.name.endsWith(transientKind.suffix)
                    ) {
                        false
                    } else {
                        !file.exists() || file.delete()
                    }
                }
            )
        val restartedManager =
            newManager(restartedStore, DirectExecutor()) { _, _ ->
                error("network must not run during recovered cleanup")
            }
        val unrelated =
            request(
                mode = SlockAndroidAvatarReadMode.Fetch,
                generation = 3L,
                partitionKey = if (terminalMutation == TerminalMutation.RetireOwner) key('d') else key('a'),
                subjectKey = key('d'),
                bytesKey = key('e')
            )

        assertTrue(applyTerminalMutation(restartedManager, unrelated, terminalMutation))
        assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(transientKind.suffix) })
        assertFalse(applyTerminalMutation(restartedManager, request, terminalMutation))
        assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(transientKind.suffix) })

        rejectTransientDeletes = false
        val retryStore =
            SlockAndroidAvatarDiskStore(
                root = root,
                deleteManagedFile = { file -> !file.exists() || file.delete() }
            )
        val retryManager =
            newManager(retryStore, DirectExecutor()) { _, _ ->
                error("network must not run during recovered cleanup retry")
            }
        assertTrue(applyTerminalMutation(retryManager, request, terminalMutation))
        assertTrue(retryStore.indexedEntries().isEmpty())
        assertFalse(
            root.listFiles().orEmpty().any {
                it.name.endsWith(".stage") || it.name.endsWith(".rollback") || it.name.endsWith(".bin")
            }
        )
    }

    protected fun applyTerminalMutation(
        manager: SlockAndroidAvatarCacheManager,
        request: SlockAndroidAvatarRequest,
        terminalMutation: TerminalMutation
    ): Boolean =
        when (terminalMutation) {
            TerminalMutation.Clear -> manager.clearAll()
            TerminalMutation.RetireOwner ->
                manager.retireOwner(request.nativePartitionKey) == SlockAndroidAvatarMutation.Accepted
            TerminalMutation.RetireSubject ->
                manager.retireSubject(request.nativePartitionKey, request.nativeSubjectKey) ==
                    SlockAndroidAvatarMutation.Accepted
        }

    protected fun prime(
        store: SlockAndroidAvatarDiskStore,
        key: SlockAndroidAvatarEntryKey,
        generation: Long,
        bytes: ByteArray
    ) {
        val stage = requireNotNull(store.stage(key, generation, bytes))
        val rollback = requireNotNull(store.promote(stage, "image/test"))
        store.commitRollback(rollback)
    }

    protected enum class TerminalMutation {
        Clear,
        RetireOwner,
        RetireSubject
    }

    protected enum class TransientFileKind(val suffix: String) {
        Stage(".stage"),
        Rollback(".rollback")
    }

    protected fun request(
        mode: SlockAndroidAvatarReadMode,
        generation: Long,
        partitionKey: String = key('a'),
        subjectKey: String = key('b'),
        bytesKey: String = key('c')
    ): SlockAndroidAvatarRequest =
        SlockAndroidAvatarRequest(
            remoteUrl = "https://example.test/avatar.png",
            nativePartitionKey = partitionKey,
            nativeSubjectKey = subjectKey,
            nativeBytesKey = bytesKey,
            readMode = mode,
            retryAtMillis = null,
            eTag = "etag-1",
            lastModified = "yesterday",
            commitGeneration = generation
        )

    protected fun requestJson(generation: Long): JSONObject =
        JSONObject()
            .put("protocol", "slock_avatar_cache_v1")
            .put("managedSet", "typed_avatar_only")
            .put("managedIndex", "opaque_partition_subject_bytes_v1")
            .put("budgetScope", "avatar_only")
            .put("indexedRetirementRequired", true)
            .put("maxEntryBytes", "5242880")
            .put("maxManagedBytes", "67108864")
            .put("remoteUrl", "https://example.test/avatar.png")
            .put("nativePartitionKey", key('a'))
            .put("nativeSubjectKey", key('b'))
            .put("nativeBytesKey", key('c'))
            .put("readMode", "fetch")
            .put("commitGeneration", generation.toString())

    protected fun encodeModel(json: JSONObject): String =
        "slock-avatar-cache-v1:" +
            Base64.getEncoder().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))

    protected fun decodeKey(request: SlockAndroidAvatarRequest) =
        SlockAndroidAvatarDecodeKey(request.entryKey, 64, 64, true, "cover")

    protected fun entryKey(partition: Char, subject: Char, bytes: Char) =
        SlockAndroidAvatarEntryKey(key(partition), key(subject), key(bytes))

    protected fun key(char: Char): String = char.toString().repeat(64)

    protected fun validBytes(label: String): ByteArray = "B$label".toByteArray()

    protected fun ByteArray.toText(): String = String(this, StandardCharsets.UTF_8)

    protected inline fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("slock-avatar-cache-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    protected class DirectExecutor : SlockAvatarCacheWorkExecutor {
        override fun execute(block: () -> Unit) = block()
    }

    protected class ManualExecutor : SlockAvatarCacheWorkExecutor {
        private val queued = ArrayDeque<() -> Unit>()
        val size: Int get() = queued.size
        override fun execute(block: () -> Unit) {
            queued.addLast(block)
        }
        fun runNext() {
            queued.removeFirst().invoke()
        }
        fun runAll() {
            while (queued.isNotEmpty()) runNext()
        }
    }

    protected class ManualScheduler : SlockAvatarCacheTimeoutScheduler {
        private data class Scheduled(val block: () -> Unit, var canceled: Boolean = false)
        private val queued = ArrayDeque<Scheduled>()
        override fun schedule(delayMillis: Long, block: () -> Unit): SlockAvatarCacheCancellation {
            val scheduled = Scheduled(block)
            queued.addLast(scheduled)
            return SlockAvatarCacheCancellation { scheduled.canceled = true }
        }
        fun runNext() {
            while (queued.isNotEmpty()) {
                val scheduled = queued.removeFirst()
                if (!scheduled.canceled) {
                    scheduled.block()
                    return
                }
            }
        }
        fun runAll() {
            while (queued.isNotEmpty()) runNext()
        }
    }
}
