package build.raft.app.shared

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockAndroidAvatarCacheTest {
    @Test
    fun descriptorRequiresExactManagedContractOpaqueKeysAndHttpTransport() {
        val valid = requestJson(generation = 7L)
        val model = encodeModel(valid)

        val parsed = SlockAndroidAvatarCacheContract.parseImageModel(model)
        assertTrue(parsed is SlockAndroidAvatarDescriptorParse.Managed)
        assertEquals(7L, (parsed as SlockAndroidAvatarDescriptorParse.Managed).request.commitGeneration)

        listOf(
            JSONObject(valid.toString()).put("protocol", "slock_avatar_cache_v0"),
            JSONObject(valid.toString()).put("managedSet", "all_images"),
            JSONObject(valid.toString()).put("nativePartitionKey", "owner-a"),
            JSONObject(valid.toString()).put("remoteUrl", "file:///private/avatar.png"),
            JSONObject(valid.toString()).put("remoteUrl", "https://user:secret@example.test/avatar.png"),
            JSONObject(valid.toString()).put("readMode", "network_first"),
            JSONObject(valid.toString()).put("commitGeneration", "0"),
            JSONObject(valid.toString()).put("blob", "base64-secret")
        ).forEach { invalid ->
            assertEquals(
                SlockAndroidAvatarDescriptorParse.Invalid,
                SlockAndroidAvatarCacheContract.parseImageModel(encodeModel(invalid))
            )
        }
        assertEquals(
            SlockAndroidAvatarDescriptorParse.NotManaged,
            SlockAndroidAvatarCacheContract.parseImageModel("https://example.test/avatar.png")
        )
    }

    @Test
    fun bridgeResultAcknowledgesManagedSetWithoutReturningPathOrBytes() {
        val response =
            SlockAndroidAvatarLoadResult(
                status = "success",
                sizeBytes = 128L,
                eTag = "etag",
                lastModified = "today",
                contentType = "image/png"
            ).toBridgeMap(512L)

        assertEquals("typed_avatar_only", response["managedSet"])
        assertEquals("opaque_partition_subject_bytes_v1", response["managedIndex"])
        assertEquals("avatar_only", response["budgetScope"])
        assertEquals(true, response["indexedRetirement"])
        assertEquals(512L, response["managedBytes"])
        assertFalse(response.keys.any { it.contains("path", ignoreCase = true) || it.contains("blob", ignoreCase = true) })
        assertFalse(response.values.any { it is ByteArray })
    }

    @Test
    fun decodedMemoryThenManagedDiskThenNetworkOrdering() = withTempRoot { root ->
        val executor = DirectExecutor()
        var networkCalls = 0
        val store = newStore(root.resolve("image_cache/slock-avatar-cache-v1"))
        val manager =
            newManager(store, executor) { _, _ ->
                networkCalls++
                SlockAndroidAvatarNetworkResponse(200, validBytes("network"), "image/test")
            }
        val memory = SlockAvatarDecodedMemoryCache<String>(1024) { it.length }
        val diskRequest = request(mode = SlockAndroidAvatarReadMode.RenderFresh, generation = 2L)
        prepare(manager, diskRequest)
        prime(store, diskRequest.entryKey, generation = 1L, bytes = validBytes("disk"))
        val decodeKey = decodeKey(diskRequest)

        memory.put(decodeKey, "memory")
        var source: String? = memory.get(decodeKey)
        if (source != null) manager.recordMemoryRendered(diskRequest)
        if (source == null) manager.load(diskRequest) { source = it?.bytes?.toText() }
        assertEquals("memory", source)
        assertEquals(0, networkCalls)

        memory.clear()
        manager.load(diskRequest) { source = it?.bytes?.toText() }
        assertEquals("Bdisk", source)
        assertEquals(0, networkCalls)

        val networkRequest =
            request(
                mode = SlockAndroidAvatarReadMode.Fetch,
                generation = 3L,
                bytesKey = key('d')
            )
        prepare(manager, networkRequest)
        var rendered: String? = null
        var result: SlockAndroidAvatarLoadResult? = null
        manager.load(networkRequest) { rendered = it?.bytes?.toText() }
        manager.awaitResult(networkRequest) { result = it }
        assertEquals("success", result?.status)
        assertNull(rendered)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(networkRequest, "successful_bytes"))
        assertEquals("Bnetwork", rendered)
        assertEquals(1, networkCalls)
    }

    @Test
    fun opaqueGenerationSingleFlightAndEveryAwaiterCompletesExactlyOnce() = withTempRoot { root ->
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        var networkCalls = 0
        val manager =
            newManager(newStore(root), executor, scheduler) { _, _ ->
                networkCalls++
                SlockAndroidAvatarNetworkResponse(200, validBytes("one"), "image/test")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 9L)
        prepare(manager, request)
        var firstCount = 0
        var secondCount = 0
        var renderCount = 0

        manager.awaitResult(request) { firstCount++ }
        manager.awaitResult(request) { secondCount++ }
        manager.load(request) { if (it != null) renderCount++ }
        assertEquals(1, executor.size)
        assertEquals(1, manager.activeNetworkCount())

        executor.runAll()
        assertEquals(1, networkCalls)
        assertEquals(1, firstCount)
        assertEquals(1, secondCount)
        assertEquals(0, renderCount)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "successful_bytes"))
        assertEquals(1, renderCount)

        manager.awaitResult(request) { firstCount++ }
        assertEquals(2, firstCount)
        executor.runAll()
        scheduler.runAll()
        assertEquals(2, firstCount)
        assertEquals(1, secondCount)
        assertEquals(1, networkCalls)
    }

    @Test
    fun awaitResultTimeoutIsBoundedAndLateNetworkCannotDeliverAgain() = withTempRoot { root ->
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        val manager =
            newManager(newStore(root), executor, scheduler) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("late"), "image/test")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 4L)
        prepare(manager, request)
        val statuses = mutableListOf<String>()
        var renderCount = 0
        manager.awaitResult(request) { statuses += it.status }
        manager.load(request) { renderCount++ }

        scheduler.runNext()
        assertEquals(listOf("temporary_transport"), statuses)
        assertEquals(1, renderCount)
        executor.runAll()
        scheduler.runAll()
        assertEquals(listOf("temporary_transport"), statuses)
        assertEquals(1, renderCount)
        assertEquals(0L, manager.managedBytes())
    }

    @Test
    fun inFlightOperationRegistryRejectsWorkBeyondItsBound() = withTempRoot { root ->
        val executor = ManualExecutor()
        val manager =
            newManager(newStore(root), executor) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("never"), "image/test")
            }
        var rejectedStatus: String? = null
        for (generation in 1L..SlockAndroidAvatarCacheContract.MaxRetainedOperations.toLong()) {
            val request =
                request(
                    mode = SlockAndroidAvatarReadMode.Fetch,
                    generation = generation,
                    bytesKey = generation.toString(16).padStart(64, '0')
                )
            prepare(manager, request)
            manager.awaitResult(request) {}
        }
        val rejected =
            request(
                mode = SlockAndroidAvatarReadMode.Fetch,
                generation = SlockAndroidAvatarCacheContract.MaxRetainedOperations + 1L,
                bytesKey = key('f')
            )
        prepare(manager, rejected)
        manager.awaitResult(rejected) { rejectedStatus = it.status }

        assertEquals(SlockAndroidAvatarCacheContract.MaxRetainedOperations, manager.retainedOperationCount())
        assertEquals("temporary_transport", rejectedStatus)
        assertTrue(manager.clearAll())
    }

    @Test
    fun staleBytesRenderBeforeConditionalRefreshCompletes() = withTempRoot { root ->
        val executor = ManualExecutor()
        val store = newStore(root)
        val manager =
            newManager(store, executor) { _, admitted ->
                assertTrue(admitted)
                SlockAndroidAvatarNetworkResponse(304, eTag = "etag-2")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.RenderStaleRevalidate, generation = 2L)
        prepare(manager, request)
        prime(store, request.entryKey, 1L, validBytes("stale"))
        var rendered: String? = null
        var result: SlockAndroidAvatarLoadResult? = null

        manager.load(request) { rendered = it?.bytes?.toText() }
        assertNull(rendered)
        executor.runNext()
        assertEquals("Bstale", rendered)
        assertEquals(1, executor.size)
        manager.awaitResult(request) { result = it }
        assertNull(result)
        executor.runNext()
        assertEquals("not_modified", result?.status)
    }

    @Test
    fun notModifiedNeverRewritesAdmittedBytes() = withTempRoot { root ->
        val store = newStore(root)
        val request = request(mode = SlockAndroidAvatarReadMode.RenderStaleRevalidate, generation = 2L)
        prime(store, request.entryKey, 1L, validBytes("stable"))
        val bytesFile = root.resolve("${request.nativeBytesKey}.bin")
        assertTrue(bytesFile.setLastModified(1_700_000_000_000L))
        val modifiedBefore = bytesFile.lastModified()
        val bytesBefore = bytesFile.readBytes()
        val manager =
            newManager(store, DirectExecutor()) { _, admitted ->
                assertTrue(admitted)
                SlockAndroidAvatarNetworkResponse(304, eTag = "new-etag", lastModified = "now")
            }
        prepare(manager, request)
        var result: SlockAndroidAvatarLoadResult? = null
        manager.awaitResult(request) { result = it }
        assertEquals("not_modified", result?.status)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "not_modified"))
        assertArrayEquals(bytesBefore, bytesFile.readBytes())
        assertEquals(modifiedBefore, bytesFile.lastModified())
    }

    @Test
    fun successfulBytesStayStagedUntilAtomicFinalize() = withTempRoot { root ->
        val store = newStore(root)
        val manager =
            newManager(store, DirectExecutor()) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("candidate"), "image/test", "etag")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 11L)
        prepare(manager, request)
        var result: SlockAndroidAvatarLoadResult? = null
        var rendered: String? = null
        manager.load(request) { rendered = it?.bytes?.toText() }
        manager.awaitResult(request) { result = it }

        assertEquals("success", result?.status)
        assertNull(store.entry(request.entryKey))
        assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".stage") })
        assertNull(rendered)

        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "successful_bytes"))
        assertEquals("Bcandidate", store.read(request.entryKey)?.bytes?.toText())
        assertEquals("Bcandidate", rendered)
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".stage") })
    }

    @Test
    fun awaiterMayFinalizeBeforeAdapterAndLateAdapterStillRendersCommittedBytes() = withTempRoot { root ->
        var networkCalls = 0
        val manager =
            newManager(newStore(root), DirectExecutor()) { _, _ ->
                networkCalls++
                SlockAndroidAvatarNetworkResponse(200, validBytes("committed"), "image/test")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 12L)
        prepare(manager, request)
        var status: String? = null
        manager.awaitResult(request) { status = it.status }
        assertEquals("success", status)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "successful_bytes"))

        var rendered: String? = null
        manager.load(request) { rendered = it?.bytes?.toText() }
        assertEquals("Bcommitted", rendered)
        assertEquals(1, networkCalls)
    }

    @Test
    fun lateGenerationRollbackRestoresPreviousBytesAndIndex() = withTempRoot { root ->
        val store = newStore(root)
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 2L)
        prime(store, request.entryKey, 1L, validBytes("old"))
        val manager =
            newManager(store, DirectExecutor()) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("new"), "image/test")
            }
        prepare(manager, request)
        manager.awaitResult(request) {}
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "successful_bytes"))
        assertEquals("Bnew", store.read(request.entryKey)?.bytes?.toText())

        val successor = request.copy(commitGeneration = 3L, readMode = SlockAndroidAvatarReadMode.RenderFresh)
        prepare(manager, successor)
        manager.recordMemoryRendered(successor)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.rollback(request))
        assertEquals("Bold", store.read(request.entryKey)?.bytes?.toText())
        assertEquals(1L, store.entry(request.entryKey)?.generation)
    }

    @Test
    fun atomicFinalizeFailureRestoresPreviousBytesAndIndexBeforeReturningError() = withTempRoot { root ->
        var failIndexCommit = false
        val store =
            SlockAndroidAvatarDiskStore(
                root = root,
                beforeIndexCommit = {
                    if (failIndexCommit) error("injected index fsync failure")
                }
            )
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 2L)
        prime(store, request.entryKey, 1L, validBytes("old"))
        val manager =
            newManager(store, DirectExecutor()) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("candidate"), "image/test")
            }
        prepare(manager, request)
        manager.awaitResult(request) {}

        failIndexCommit = true
        val mutation = manager.finalize(request, "successful_bytes")
        assertTrue(mutation is SlockAndroidAvatarMutation.Failed)
        failIndexCommit = false
        assertEquals("Bold", store.read(request.entryKey)?.bytes?.toText())
        assertEquals(1L, store.entry(request.entryKey)?.generation)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.rollback(request))
    }

    @Test
    fun entryCapRejectsOverFiveMiBWithoutStaging() = withTempRoot { root ->
        val store = newStore(root)
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 1L)
        assertEquals(5 * 1024 * 1024, SlockAndroidAvatarCacheContract.MaxEntryBytes)
        assertNull(
            store.stage(
                request.entryKey,
                request.commitGeneration,
                ByteArray(SlockAndroidAvatarCacheContract.MaxEntryBytes + 1) { 'B'.code.toByte() }
            )
        )
        val manager =
            newManager(store, DirectExecutor()) { _, _ ->
                SlockAndroidAvatarNetworkResponse(
                    statusCode = 200,
                    bytes = ByteArray(0),
                    contentType = "image/png",
                    exceededMaxBytes = true
                )
            }
        prepare(manager, request)
        var result: SlockAndroidAvatarLoadResult? = null
        manager.awaitResult(request) { result = it }
        assertEquals("invalid_content", result?.status)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "invalid_content"))
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".stage") || it.name.endsWith(".bin") })
    }

    @Test
    fun avatarOnlyLruNeverTouchesAdjacentOrdinaryImageFiles() = withTempRoot { imageRoot ->
        val ordinary = imageRoot.resolve("ordinary-preview.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 8, 7))
        }
        var now = 1L
        val store =
            SlockAndroidAvatarDiskStore(
                root = imageRoot.resolve("slock-avatar-cache-v1"),
                maxEntryBytes = 5L,
                maxManagedBytes = 8L,
                clockMillis = { now++ }
            )
        val first = entryKey('a', 'b', 'c')
        val second = entryKey('a', 'd', 'e')
        prime(store, first, 1L, byteArrayOf(1, 1, 1, 1, 1))
        prime(store, second, 2L, byteArrayOf(2, 2, 2, 2, 2))

        assertNull(store.entry(first))
        assertNotNull(store.entry(second))
        assertTrue(store.managedBytes() <= 8L)
        assertTrue(ordinary.isFile)
        assertArrayEquals(byteArrayOf(9, 8, 7), ordinary.readBytes())
    }

    @Test
    fun rollbackAndStageFilesRemainPhysicallyBounded() = withTempRoot { root ->
        val store =
            SlockAndroidAvatarDiskStore(
                root = root,
                maxEntryBytes = 5L,
                maxManagedBytes = 8L
            )
        val first = entryKey('a', 'b', 'c')
        val second = entryKey('a', 'd', 'e')
        val third = entryKey('f', 'a', 'b')
        prime(store, first, 1L, byteArrayOf(1, 1, 1, 1, 1))
        val secondStage = requireNotNull(store.stage(second, 2L, byteArrayOf(2, 2, 2, 2, 2)))
        val pendingRollback = requireNotNull(store.promote(secondStage, "image/test"))

        assertNull(store.stage(third, 3L, byteArrayOf(3, 3, 3, 3, 3)))
        val physicalBytes =
            root.listFiles().orEmpty()
                .filter { it.name.endsWith(".bin") || it.name.endsWith(".stage") || it.name.endsWith(".rollback") }
                .sumOf(File::length)
        assertTrue(physicalBytes <= 13L)
        store.commitRollback(pendingRollback)
        assertTrue(store.managedBytes() <= 8L)
    }

    @Test
    fun ownerAndSubjectRetirementUseOnlyOpaqueIndexMembership() = withTempRoot { root ->
        val store = newStore(root)
        val ownerA1 = entryKey('a', 'b', 'c')
        val ownerA2 = entryKey('a', 'd', 'e')
        val ownerB = entryKey('f', 'b', 'a')
        prime(store, ownerA1, 1L, validBytes("a1"))
        prime(store, ownerA2, 2L, validBytes("a2"))
        prime(store, ownerB, 3L, validBytes("b"))
        val manager = newManager(store, DirectExecutor()) { _, _ -> error("network must not run") }

        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.retireSubject(key('a'), key('b')))
        assertNull(store.entry(ownerA1))
        assertNotNull(store.entry(ownerA2))
        assertNotNull(store.entry(ownerB))

        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.retireOwner(key('a')))
        assertNull(store.entry(ownerA2))
        assertNotNull(store.entry(ownerB))
    }

    @Test
    fun descriptorWithoutSuccessfulInspectPreparationCannotStartNativeWork() = withTempRoot { root ->
        val executor = ManualExecutor()
        val scheduler = ManualScheduler()
        var networkCalls = 0
        val manager =
            newManager(newStore(root), executor, scheduler) { _, _ ->
                networkCalls++
                SlockAndroidAvatarNetworkResponse(200, validBytes("unexpected"), "image/test")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 4L)
        val statuses = mutableListOf<String>()
        var renderCount = 0

        manager.awaitResult(request) { statuses += it.status }
        manager.load(request) { renderCount++ }
        executor.runAll()
        scheduler.runAll()

        assertEquals(listOf("temporary_transport"), statuses)
        assertEquals(1, renderCount)
        assertEquals(0, networkCalls)
        assertEquals(0, manager.retainedPreparationCount())
        assertEquals(0, manager.retainedOperationCount())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".stage") || it.name.endsWith(".bin") })
    }

    @Test
    fun settingsClearFencesPreparedButNeverAdmittedDescriptor() = withTempRoot { root ->
        assertPreparedDescriptorRevokedAndLaterGenerationAdmitted(root, TerminalMutation.Clear)
    }

    @Test
    fun ownerRetireFencesPreparedButNeverAdmittedDescriptor() = withTempRoot { root ->
        assertPreparedDescriptorRevokedAndLaterGenerationAdmitted(root, TerminalMutation.RetireOwner)
    }

    @Test
    fun subjectRetireFencesPreparedButNeverAdmittedDescriptor() = withTempRoot { root ->
        assertPreparedDescriptorRevokedAndLaterGenerationAdmitted(root, TerminalMutation.RetireSubject)
    }

    @Test
    fun failedOwnerRetirementPreservesOpaqueMembershipForRetry() = withTempRoot { root ->
        assertDeleteFailurePreservesManagerRetirementAuthority(root) { manager, key ->
            manager.retireOwner(key.partitionKey)
        }
    }

    @Test
    fun failedSubjectRetirementPreservesOpaqueMembershipForRetry() = withTempRoot { root ->
        assertDeleteFailurePreservesManagerRetirementAuthority(root) { manager, key ->
            manager.retireSubject(key.partitionKey, key.subjectKey)
        }
    }

    @Test
    fun failedSettingsClearPreservesOpaqueMembershipForRetry() = withTempRoot { root ->
        assertDeleteFailurePreservesCleanupAuthority(root) { store, _ ->
            store.clearAll()
        }
    }

    @Test
    fun settingsClearClosesMemoryDiskAndInflightAndLateCompletionCannotRecreate() = withTempRoot { root ->
        val executor = ManualExecutor()
        val store = newStore(root.resolve("image_cache/slock-avatar-cache-v1"))
        val database = root.resolve("slock_shared.db").apply { writeText("keep") }
        val ordinary = root.resolve("image_cache/ordinary.bin").apply {
            parentFile?.mkdirs()
            writeText("ordinary")
        }
        val manager =
            newManager(store, executor) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("late"), "image/test")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 5L)
        prepare(manager, request)
        val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
        memory.put(decodeKey(request), "decoded")
        var awaitCount = 0
        var renderCount = 0
        manager.awaitResult(request) { awaitCount++ }
        manager.load(request) { renderCount++ }

        memory.clear()
        assertTrue(manager.clearAll())
        assertEquals(1, awaitCount)
        assertEquals(1, renderCount)
        assertEquals(0, memory.size())
        executor.runAll()
        assertEquals(0L, manager.managedBytes())
        assertFalse(root.resolve("image_cache/slock-avatar-cache-v1/${request.nativeBytesKey}.bin").exists())
        assertTrue(database.isFile)
        assertEquals("keep", database.readText())
        assertTrue(ordinary.isFile)
        assertEquals("ordinary", ordinary.readText())
    }

    @Test
    fun retireRejectsLateCompletionButAllowsHigherSharedGeneration() = withTempRoot { root ->
        val executor = ManualExecutor()
        val store = newStore(root)
        var responseName = "late"
        val manager =
            newManager(store, executor) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes(responseName), "image/test")
            }
        val old = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 8L)
        prepare(manager, old)
        var oldResultCount = 0
        manager.awaitResult(old) { oldResultCount++ }
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.retireOwner(old.nativePartitionKey))
        assertEquals(1, oldResultCount)
        executor.runAll()
        assertNull(store.entry(old.entryKey))

        responseName = "fresh"
        val fresh = old.copy(commitGeneration = 9L)
        prepare(manager, fresh)
        var freshStatus: String? = null
        manager.awaitResult(fresh) { freshStatus = it.status }
        executor.runAll()
        assertEquals("success", freshStatus)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(fresh, "successful_bytes"))
        assertEquals("Bfresh", store.read(fresh.entryKey)?.bytes?.toText())
    }

    private fun newManager(
        store: SlockAndroidAvatarDiskStore,
        executor: SlockAvatarCacheWorkExecutor,
        scheduler: SlockAvatarCacheTimeoutScheduler = ManualScheduler(),
        fetcher: (SlockAndroidAvatarRequest, Boolean) -> SlockAndroidAvatarNetworkResponse
    ): SlockAndroidAvatarCacheManager =
        SlockAndroidAvatarCacheManager(
            store = store,
            networkFetcher = SlockAndroidAvatarNetworkFetcher(fetcher),
            contentValidator = { bytes, _ -> bytes.firstOrNull() == 'B'.code.toByte() },
            workExecutor = executor,
            timeoutScheduler = scheduler,
            resultTimeoutMillis = 100L,
            rollbackGraceMillis = 1_000L
        )

    private fun newStore(root: File): SlockAndroidAvatarDiskStore =
        SlockAndroidAvatarDiskStore(root = root)

    private fun prepare(
        manager: SlockAndroidAvatarCacheManager,
        request: SlockAndroidAvatarRequest
    ) {
        assertNotNull(manager.inspect(request.entryKey, request.commitGeneration))
    }

    private fun assertPreparedDescriptorRevokedAndLaterGenerationAdmitted(
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

    private fun assertDeleteFailurePreservesManagerRetirementAuthority(
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

    private fun assertDeleteFailurePreservesCleanupAuthority(
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

    private fun prime(
        store: SlockAndroidAvatarDiskStore,
        key: SlockAndroidAvatarEntryKey,
        generation: Long,
        bytes: ByteArray
    ) {
        val stage = requireNotNull(store.stage(key, generation, bytes))
        val rollback = requireNotNull(store.promote(stage, "image/test"))
        store.commitRollback(rollback)
    }

    private enum class TerminalMutation {
        Clear,
        RetireOwner,
        RetireSubject
    }

    private fun request(
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

    private fun requestJson(generation: Long): JSONObject =
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

    private fun encodeModel(json: JSONObject): String =
        "slock-avatar-cache-v1:" +
            Base64.getEncoder().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))

    private fun decodeKey(request: SlockAndroidAvatarRequest) =
        SlockAndroidAvatarDecodeKey(request.entryKey, 64, 64, true, "cover")

    private fun entryKey(partition: Char, subject: Char, bytes: Char) =
        SlockAndroidAvatarEntryKey(key(partition), key(subject), key(bytes))

    private fun key(char: Char): String = char.toString().repeat(64)

    private fun validBytes(label: String): ByteArray = "B$label".toByteArray()

    private fun ByteArray.toText(): String = String(this, StandardCharsets.UTF_8)

    private inline fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("slock-avatar-cache-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class DirectExecutor : SlockAvatarCacheWorkExecutor {
        override fun execute(block: () -> Unit) = block()
    }

    private class ManualExecutor : SlockAvatarCacheWorkExecutor {
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

    private class ManualScheduler : SlockAvatarCacheTimeoutScheduler {
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
