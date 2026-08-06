package build.raft.app.shared

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SlockAndroidAvatarCacheTest : SlockAndroidAvatarCacheTestSupport() {
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
        var source: String? = null
        assertEquals(
            SlockAndroidAvatarMemoryDelivery.Delivered,
            manager.deliverMemory(
                request = diskRequest,
                payloadProvider = { memory.get(decodeKey) },
                callback = { source = it }
            )
        )
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
        executor.runAll()
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
    fun authoritativeAbsenceAndInvalidContentRetireStaleNativeMappingAndDecodedEntry() =
        withTempRoot { root ->
            listOf(
                SlockAndroidAvatarNetworkResponse(404),
                SlockAndroidAvatarNetworkResponse(410),
                SlockAndroidAvatarNetworkResponse(200, "not-an-image".toByteArray(), "image/test")
            ).forEachIndexed { index, response ->
                val caseRoot = root.resolve(index.toString())
                val request = request(mode = SlockAndroidAvatarReadMode.RenderStaleRevalidate, generation = 2L)
                val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
                val store =
                    SlockAndroidAvatarDiskStore(
                        root = caseRoot,
                        onEntriesRemoved = { keys -> keys.forEach(memory::removeEntry) }
                    )
                prime(store, request.entryKey, 1L, validBytes("stale"))
                memory.put(decodeKey(request), "decoded")
                val manager =
                    newManager(store, DirectExecutor()) { _, admitted ->
                        assertTrue(admitted)
                        response
                    }
                prepare(manager, request)
                var rendered: String? = null
                var result: SlockAndroidAvatarLoadResult? = null

                manager.load(request) { rendered = it?.bytes?.toText() }
                manager.awaitResult(request) { result = it }

                assertEquals("Bstale", rendered)
                val expectedOutcome =
                    if (response.statusCode in setOf(404, 410)) "resource_absent" else "invalid_content"
                assertEquals(expectedOutcome, result?.status)
                assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, expectedOutcome))
                assertNull(store.entry(request.entryKey))
                assertFalse(caseRoot.resolve("${request.nativeBytesKey}.bin").exists())
                assertEquals(0, memory.size())
            }
        }

    @Test
    fun temporaryTransportPreservesStaleNativeMappingAndDecodedEntry() = withTempRoot { root ->
        val request = request(mode = SlockAndroidAvatarReadMode.RenderStaleRevalidate, generation = 2L)
        val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
        val store =
            SlockAndroidAvatarDiskStore(
                root = root,
                onEntriesRemoved = { keys -> keys.forEach(memory::removeEntry) }
            )
        prime(store, request.entryKey, 1L, validBytes("stale"))
        memory.put(decodeKey(request), "decoded")
        val manager =
            newManager(store, DirectExecutor()) { _, admitted ->
                assertTrue(admitted)
                SlockAndroidAvatarNetworkResponse(503)
            }
        var rendered: String? = null
        var result: SlockAndroidAvatarLoadResult? = null

        prepare(manager, request)
        manager.load(request) { rendered = it?.bytes?.toText() }
        manager.awaitResult(request) { result = it }

        assertEquals("Bstale", rendered)
        assertEquals("temporary_transport", result?.status)
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "temporary_transport"))
        assertEquals(1L, store.entry(request.entryKey)?.generation)
        assertEquals("Bstale", store.read(request.entryKey)?.bytes?.toText())
        assertTrue(root.resolve("${request.nativeBytesKey}.bin").isFile)
        assertEquals(1, memory.size())
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
        assertEquals(
            SlockAndroidAvatarMemoryDelivery.Delivered,
            manager.deliverMemory(successor, payloadProvider = { "decoded" }, callback = {})
        )
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
    fun postPromoteUnreadableCandidateRestoresPreviousBytesBeforeReturningFailure() = withTempRoot { root ->
        val store = newStore(root)
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 2L)
        prime(store, request.entryKey, 1L, validBytes("old"))
        var validationCall = 0
        val manager =
            SlockAndroidAvatarCacheManager(
                store = store,
                networkFetcher =
                    SlockAndroidAvatarNetworkFetcher { _, _ ->
                        SlockAndroidAvatarNetworkResponse(200, validBytes("candidate"), "image/test")
                    },
                contentValidator = { bytes, _ ->
                    validationCall++
                    bytes.firstOrNull() == 'B'.code.toByte() && validationCall != 3
                },
                workExecutor = DirectExecutor(),
                timeoutScheduler = ManualScheduler(),
                resultTimeoutMillis = 100L,
                rollbackGraceMillis = 1_000L
            )
        prepare(manager, request)
        var result: SlockAndroidAvatarLoadResult? = null
        manager.awaitResult(request) { result = it }

        assertEquals("success", result?.status)
        val mutation = manager.finalize(request, "successful_bytes")

        assertTrue(mutation is SlockAndroidAvatarMutation.Failed)
        assertEquals(3, validationCall)
        assertEquals(1L, store.entry(request.entryKey)?.generation)
        assertEquals("Bold", store.read(request.entryKey)?.bytes?.toText())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".rollback") })
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
    fun controlPlaneResultPublicationCannotCancelPausedPerViewDiskDelivery() =
        withTempRoot { root ->
            val cases =
                listOf(
                    Triple(SlockAndroidAvatarReadMode.RenderFresh, 200, "rendered"),
                    Triple(SlockAndroidAvatarReadMode.RenderStaleRevalidate, 304, "not_modified"),
                    Triple(SlockAndroidAvatarReadMode.RenderStaleRevalidate, 0, "temporary_transport")
                )
            cases.forEachIndexed { index, (mode, statusCode, expectedStatus) ->
                val executor = ManualExecutor()
                val store = newStore(root.resolve("case-$index"))
                val request = request(mode = mode, generation = 2L)
                prime(store, request.entryKey, generation = 1L, bytes = validBytes("disk"))
                val manager =
                    newManager(store, executor) { _, _ ->
                        SlockAndroidAvatarNetworkResponse(statusCode = statusCode)
                    }
                prepare(manager, request)
                val deliveries = mutableListOf<String?>()
                val statuses = mutableListOf<String>()

                manager.load(request) { payload -> deliveries += payload?.bytes?.toText() }
                manager.awaitResult(request) { statuses += it.status }
                assertEquals(2, executor.size)

                executor.runNext()
                assertEquals(2, executor.size)
                executor.runNext()
                assertEquals(listOf(expectedStatus), statuses)
                assertTrue(deliveries.isEmpty())

                executor.runAll()
                assertEquals(listOf("Bdisk"), deliveries)
            }
        }

    @Test
    fun fetchNotModifiedKeepsDiskDeliveryPendingUntilFinalizeAndServesLateConsumer() =
        withTempRoot { root ->
            val executor = ManualExecutor()
            val store = newStore(root)
            val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 2L)
            prime(store, request.entryKey, generation = 1L, bytes = validBytes("disk"))
            val manager =
                newManager(store, executor) { _, _ ->
                    SlockAndroidAvatarNetworkResponse(statusCode = 304)
                }
            prepare(manager, request)
            val early = mutableListOf<String?>()
            val late = mutableListOf<String?>()
            val statuses = mutableListOf<String>()

            manager.load(request) { payload -> early += payload?.bytes?.toText() }
            manager.awaitResult(request) { statuses += it.status }
            executor.runAll()
            assertEquals(listOf("not_modified"), statuses)
            assertTrue(early.isEmpty())

            assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "not_modified"))
            manager.load(request) { payload -> late += payload?.bytes?.toText() }
            executor.runAll()

            assertEquals(listOf("Bdisk"), early)
            assertEquals(listOf("Bdisk"), late)
        }

    @Test
    fun overlappingSameGenerationRenderFreshLoadsEachReceiveDiskBytesExactlyOnce() =
        withTempRoot { root ->
            val executor = ManualExecutor()
            val store = newStore(root)
            val request = request(mode = SlockAndroidAvatarReadMode.RenderFresh, generation = 2L)
            prime(store, request.entryKey, generation = 1L, bytes = validBytes("disk"))
            val manager =
                newManager(store, executor) { _, _ ->
                    error("network must not run for RenderFresh")
                }
            prepare(manager, request)
            val first = mutableListOf<String?>()
            val second = mutableListOf<String?>()

            manager.load(request) { payload -> first += payload?.bytes?.toText() }
            manager.load(request) { payload -> second += payload?.bytes?.toText() }
            assertEquals(2, executor.size)
            executor.runAll()

            assertEquals(listOf("Bdisk"), first)
            assertEquals(listOf("Bdisk"), second)
        }

    @Test
    fun openRollbackKeyCannotBeEvictedOrResurrectedByUnrelatedPromotion() =
        withTempRoot { root ->
            listOf(false, true).forEach { commitSecond ->
                val caseRoot = root.resolve(if (commitSecond) "commit" else "rollback")
                var failNextIndexCommit = false
                val store =
                    SlockAndroidAvatarDiskStore(
                        root = caseRoot,
                        maxEntryBytes = 5L,
                        maxManagedBytes = 5L,
                        beforeIndexCommit = {
                            if (failNextIndexCommit) {
                                failNextIndexCommit = false
                                error("injected first rollback index failure")
                            }
                        }
                    )
                val first = entryKey('a', 'b', 'c')
                val second = entryKey('d', 'e', 'f')
                val oldFirst = byteArrayOf(1)
                val candidateFirst = byteArrayOf(2)
                val secondBytes = ByteArray(5) { 3 }
                prime(store, first, generation = 1L, bytes = oldFirst)
                val firstStage = requireNotNull(store.stage(first, 2L, candidateFirst))
                val firstRollback = requireNotNull(store.promote(firstStage, "image/test"))
                assertEquals(2L, store.entry(first)?.generation)

                val blockedSecond = requireNotNull(store.stage(second, 3L, secondBytes))
                assertNull(store.promote(blockedSecond, "image/test"))
                assertEquals(2L, store.entry(first)?.generation)
                assertNull(store.entry(second))

                failNextIndexCommit = true
                assertFalse(store.rollback(firstRollback))
                assertEquals(2L, store.entry(first)?.generation)
                assertArrayEquals(candidateFirst, store.read(first)?.bytes)

                val blockedAfterFailure = requireNotNull(store.stage(second, 3L, secondBytes))
                assertNull(store.promote(blockedAfterFailure, "image/test"))
                assertTrue(store.rollback(firstRollback))
                assertEquals(1L, store.entry(first)?.generation)
                assertArrayEquals(oldFirst, store.read(first)?.bytes)

                val secondStage = requireNotNull(store.stage(second, 3L, secondBytes))
                val secondRollback = requireNotNull(store.promote(secondStage, "image/test"))
                assertEquals(3L, store.entry(second)?.generation)
                if (commitSecond) {
                    assertTrue(store.commitRollback(secondRollback))
                    assertNull(store.entry(first))
                    assertArrayEquals(secondBytes, store.read(second)?.bytes)
                } else {
                    assertTrue(store.rollback(secondRollback))
                    assertEquals(1L, store.entry(first)?.generation)
                    assertArrayEquals(oldFirst, store.read(first)?.bytes)
                    assertNull(store.entry(second))
                }
                assertFalse(
                    caseRoot.listFiles().orEmpty()
                        .filter { it.name.endsWith(".bin") || it.name.endsWith(".rollback") }
                        .any { file ->
                            runCatching { file.readBytes().contentEquals(candidateFirst) }.getOrDefault(false)
                        }
                )
            }
        }

    @Test
    fun ordinaryImageSvgSniffPreservesLongLeadingWhitespaceWithoutContentType() {
        val bytes =
            (" ".repeat(1_024) + "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
                .toByteArray(StandardCharsets.UTF_8)

        assertTrue(slockOrdinaryImageLooksLikeSvg(bytes, contentType = null))
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
    fun failedRollbackRetainsRecordUntilPreviousBytesAndGenerationAreRestored() = withTempRoot { root ->
        var failIndexCommit = false
        val store =
            SlockAndroidAvatarDiskStore(
                root = root,
                beforeIndexCommit = {
                    if (failIndexCommit) error("injected rollback index failure")
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
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "successful_bytes"))
        assertEquals(2L, store.entry(request.entryKey)?.generation)
        assertEquals("Bcandidate", root.resolve("${request.nativeBytesKey}.bin").readBytes().toText())

        failIndexCommit = true
        assertTrue(manager.rollback(request) is SlockAndroidAvatarMutation.Failed)
        assertEquals(1, manager.retainedOperationCount())
        assertEquals(2L, store.entry(request.entryKey)?.generation)
        assertEquals("Bcandidate", root.resolve("${request.nativeBytesKey}.bin").readBytes().toText())

        failIndexCommit = false
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.rollback(request))
        assertEquals(0, manager.retainedOperationCount())
        assertEquals(1L, store.entry(request.entryKey)?.generation)
        assertEquals("Bold", store.read(request.entryKey)?.bytes?.toText())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".rollback") })
    }

    @Test
    fun failedRollbackCandidateDeleteRetainsAuthorityAfterOldIndexWasPublished() = withTempRoot { root ->
        var rejectCandidateDelete = true
        val store =
            SlockAndroidAvatarDiskStore(
                root = root,
                deleteManagedFile = { file ->
                    if (rejectCandidateDelete && file.exists() && file.name.endsWith(".bin")) {
                        rejectCandidateDelete = false
                        false
                    } else {
                        !file.exists() || file.delete()
                    }
                }
            )
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 1L)
        val manager =
            newManager(store, DirectExecutor()) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("candidate"), "image/test")
            }
        prepare(manager, request)
        manager.awaitResult(request) {}
        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.finalize(request, "successful_bytes"))

        assertTrue(manager.rollback(request) is SlockAndroidAvatarMutation.Failed)
        assertEquals(1, manager.retainedOperationCount())
        assertTrue(root.resolve("${request.nativeBytesKey}.bin").isFile)
        assertTrue(store.indexedEntries().isEmpty())

        assertEquals(SlockAndroidAvatarMutation.Accepted, manager.rollback(request))
        assertEquals(0, manager.retainedOperationCount())
        assertTrue(store.indexedEntries().isEmpty())
        assertFalse(root.resolve("${request.nativeBytesKey}.bin").exists())
    }

    @Test
    fun failedOwnerRetirementRetainsTransientCleanupAuthorityForRetry() = withTempRoot { root ->
        assertTransientDeleteFailurePreservesTerminalAuthority(root, TerminalMutation.RetireOwner)
    }

    @Test
    fun failedSubjectRetirementRetainsTransientCleanupAuthorityForRetry() = withTempRoot { root ->
        assertTransientDeleteFailurePreservesTerminalAuthority(root, TerminalMutation.RetireSubject)
    }

    @Test
    fun failedSettingsClearRetainsTransientCleanupAuthorityForRetry() = withTempRoot { root ->
        assertTransientDeleteFailurePreservesTerminalAuthority(root, TerminalMutation.Clear)
    }

    @Test
    fun ownerRetirementAfterRestartRetainsStageDeleteAuthority() = withTempRoot { root ->
        assertRestartTransientDeleteFailurePreservesTerminalAuthority(
            root,
            TerminalMutation.RetireOwner,
            TransientFileKind.Stage
        )
    }

    @Test
    fun ownerRetirementAfterRestartRetainsRollbackDeleteAuthority() = withTempRoot { root ->
        assertRestartTransientDeleteFailurePreservesTerminalAuthority(
            root,
            TerminalMutation.RetireOwner,
            TransientFileKind.Rollback
        )
    }

    @Test
    fun subjectRetirementAfterRestartRetainsStageDeleteAuthority() = withTempRoot { root ->
        assertRestartTransientDeleteFailurePreservesTerminalAuthority(
            root,
            TerminalMutation.RetireSubject,
            TransientFileKind.Stage
        )
    }

    @Test
    fun subjectRetirementAfterRestartRetainsRollbackDeleteAuthority() = withTempRoot { root ->
        assertRestartTransientDeleteFailurePreservesTerminalAuthority(
            root,
            TerminalMutation.RetireSubject,
            TransientFileKind.Rollback
        )
    }

    @Test
    fun terminalMutationsClosePausedDiskDeliveryBeforeSuccessAndAllowLaterGeneration() =
        withTempRoot { root ->
            TerminalMutation.entries.forEach { terminalMutation ->
                assertPausedDeliveryClosedAndLaterGenerationAdmitted(
                    root = root.resolve("disk-${terminalMutation.name}"),
                    terminalMutation = terminalMutation,
                    finalizeBeforeTerminal = false
                )
            }
        }

    @Test
    fun terminalMutationsClosePausedFinalizedFetchBeforeSuccessAndAllowLaterGeneration() =
        withTempRoot { root ->
            TerminalMutation.entries.forEach { terminalMutation ->
                assertPausedDeliveryClosedAndLaterGenerationAdmitted(
                    root = root.resolve("fetch-${terminalMutation.name}"),
                    terminalMutation = terminalMutation,
                    finalizeBeforeTerminal = true
                )
            }
        }

    @Test
    fun revokedMemoryHitCannotReachDrawableBeforeAdmissionAndLaterGenerationStillRenders() =
        withTempRoot { root ->
            TerminalMutation.entries.forEach { terminalMutation ->
                val caseRoot = root.resolve(terminalMutation.name)
                val request = request(mode = SlockAndroidAvatarReadMode.RenderFresh, generation = 2L)
                val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
                val manager =
                    newManager(
                        store = newStore(caseRoot),
                        executor = DirectExecutor(),
                        clearDecodedMemory = memory::clear,
                        retireDecodedOwner = memory::removeOwner,
                        retireDecodedSubject = memory::removeSubject
                    ) { _, _ -> error("network must not run for decoded-memory delivery") }
                prepare(manager, request)
                memory.put(decodeKey(request), "old")
                assertTrue(applyTerminalMutation(manager, request, terminalMutation))
                assertEquals(0, memory.size())

                var revokedLookupCount = 0
                var drawableCount = 0
                var closureCount = 0
                val revoked =
                    manager.deliverMemory(
                        request = request,
                        payloadProvider = {
                            revokedLookupCount++
                            memory.get(decodeKey(request))
                        },
                        callback = { drawableCount++ }
                    )
                if (revoked == SlockAndroidAvatarMemoryDelivery.Rejected) closureCount++
                assertEquals(SlockAndroidAvatarMemoryDelivery.Rejected, revoked)
                assertEquals(0, revokedLookupCount)
                assertEquals(0, drawableCount)
                assertEquals(1, closureCount)

                val fresh = request.copy(commitGeneration = request.commitGeneration + 1L)
                prepare(manager, fresh)
                memory.put(decodeKey(fresh), "fresh")
                assertEquals(
                    SlockAndroidAvatarMemoryDelivery.Delivered,
                    manager.deliverMemory(
                        request = fresh,
                        payloadProvider = { memory.get(decodeKey(fresh)) },
                        callback = { drawableCount++ }
                    )
                )
                assertEquals(1, drawableCount)
            }
        }

    @Test
    fun settingsClearWaitsForReadPayloadDeliveryBeforeReturning() = withTempRoot { root ->
        val request = request(mode = SlockAndroidAvatarReadMode.RenderFresh, generation = 2L)
        val store = newStore(root)
        prime(store, request.entryKey, 1L, validBytes("disk"))
        val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
        val manager =
            newManager(
                store = store,
                executor = DirectExecutor(),
                clearDecodedMemory = memory::clear,
                retireDecodedOwner = memory::removeOwner,
                retireDecodedSubject = memory::removeSubject
            ) { _, _ -> error("network must not run") }
        prepare(manager, request)
        val deliveryEntered = CountDownLatch(1)
        val resumeDelivery = CountDownLatch(1)
        val terminalStarted = CountDownLatch(1)
        val terminalFinished = CountDownLatch(1)
        var terminalResult = false

        val deliveryThread =
            thread(name = "avatar-read-delivery") {
                manager.load(request) { payload ->
                    if (payload != null) {
                        deliveryEntered.countDown()
                        assertTrue(resumeDelivery.await(5, TimeUnit.SECONDS))
                        memory.put(decodeKey(request), payload.bytes.toText())
                    }
                }
            }
        assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS))
        val terminalThread =
            thread(name = "avatar-read-clear") {
                terminalStarted.countDown()
                memory.clear()
                terminalResult = manager.clearAll()
                terminalFinished.countDown()
            }
        assertTrue(terminalStarted.await(5, TimeUnit.SECONDS))
        val returnedBeforeDeliveryFinished = terminalFinished.await(250, TimeUnit.MILLISECONDS)
        resumeDelivery.countDown()
        deliveryThread.join(5_000)
        terminalThread.join(5_000)

        assertFalse(returnedBeforeDeliveryFinished)
        assertTrue(terminalResult)
        assertEquals(0, memory.size())
    }

    @Test
    fun settingsClearWaitsForFinalizedFetchDeliveryBeforeReturning() = withTempRoot { root ->
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 2L)
        val store = newStore(root)
        val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
        val manager =
            newManager(
                store = store,
                executor = DirectExecutor(),
                clearDecodedMemory = memory::clear,
                retireDecodedOwner = memory::removeOwner,
                retireDecodedSubject = memory::removeSubject
            ) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("fetched"), "image/test")
            }
        prepare(manager, request)
        val deliveryEntered = CountDownLatch(1)
        val resumeDelivery = CountDownLatch(1)
        val terminalStarted = CountDownLatch(1)
        val terminalFinished = CountDownLatch(1)
        var terminalResult = false
        manager.load(request) { payload ->
            if (payload != null) {
                deliveryEntered.countDown()
                assertTrue(resumeDelivery.await(5, TimeUnit.SECONDS))
                memory.put(decodeKey(request), payload.bytes.toText())
            }
        }
        manager.awaitResult(request) {}

        val finalizeThread =
            thread(name = "avatar-fetch-finalize") {
                assertEquals(
                    SlockAndroidAvatarMutation.Accepted,
                    manager.finalize(request, "successful_bytes")
                )
            }
        assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS))
        val terminalThread =
            thread(name = "avatar-fetch-clear") {
                terminalStarted.countDown()
                memory.clear()
                terminalResult = manager.clearAll()
                terminalFinished.countDown()
            }
        assertTrue(terminalStarted.await(5, TimeUnit.SECONDS))
        val returnedBeforeDeliveryFinished = terminalFinished.await(250, TimeUnit.MILLISECONDS)
        resumeDelivery.countDown()
        finalizeThread.join(5_000)
        terminalThread.join(5_000)

        assertFalse(returnedBeforeDeliveryFinished)
        assertTrue(terminalResult)
        assertEquals(0, memory.size())
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
        val memory = SlockAvatarDecodedMemoryCache<String>(100) { it.length }
        val manager =
            newManager(
                store = store,
                executor = executor,
                clearDecodedMemory = memory::clear,
                retireDecodedOwner = memory::removeOwner,
                retireDecodedSubject = memory::removeSubject
            ) { _, _ ->
                SlockAndroidAvatarNetworkResponse(200, validBytes("late"), "image/test")
            }
        val request = request(mode = SlockAndroidAvatarReadMode.Fetch, generation = 5L)
        prepare(manager, request)
        memory.put(decodeKey(request), "decoded")
        var awaitCount = 0
        var renderCount = 0
        manager.awaitResult(request) { awaitCount++ }
        manager.load(request) { renderCount++ }

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


}
